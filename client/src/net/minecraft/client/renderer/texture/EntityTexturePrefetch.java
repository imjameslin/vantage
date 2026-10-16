package net.minecraft.client.renderer.texture;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.VisibleForDebug;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * TASK E (docs/metal/CONTRACT.md 16.R5-E, {@code -Dmc.asyncEntityTextures=true}, default off): decodes the PNGs under
 * {@code textures/entity/} on a background thread after each resource reload, so that the first
 * {@link TextureManager#getTexture} of an entity texture during play (r3-travel.jfr: 15 synchronous loads on the render
 * thread in 25 s, each with two NoSuchFileException pack lookups) only uploads already-decoded pixels.
 *
 * <p>Exactness: an entry is {@link TextureContents#load} of the same identifier from the same resource manager that the
 * synchronous path reads (the reload's {@code MultiPackResourceManager}, which {@code ReloadableResourceManager}
 * delegates to until the next reload), so the texture TextureManager creates from it (sampler from the same metadata,
 * same pixels, same upload) is the one vanilla creates. Anything not ready, failed or evicted falls back to the vanilla
 * synchronous load, never to a placeholder: {@link #take} returns null and the caller does exactly what vanilla does.
 * The only divergence is a resource-pack file edited on disk without a reload (F3+T) after the prefetch read it.
 *
 * <p>Threads: {@link #start}, {@link #take}, {@link #invalidate} and {@link #close} run on the render thread
 * (TextureManager's thread); one daemon worker per reload decodes. An entry belongs to one generation (one reload); the
 * worker publishes with {@code putIfAbsent} and re-checks the generation afterwards, {@link #invalidate} bumps the
 * generation and then drains the map, and only the thread that removes an entry from the map closes its image, so every
 * decoded image is closed exactly once (taken, drained, or dropped by the worker) and no stale entry is ever returned.
 * Memory: at most {@link #MAX_BYTES} of decoded pixels are held (vanilla jar: 743 PNGs, 14.3 MB).
 */
public final class EntityTexturePrefetch implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final boolean ENABLED = flag("mc.asyncEntityTextures", false);
    /** Directories (resource paths) whose PNGs are prefetched, comma-separated. */
    static final String[] DIRECTORIES = System.getProperty("mc.asyncEntityTextures.dirs", "textures/entity").split(",");
    /** Upper bound of decoded pixel bytes held ({@code -Dmc.asyncEntityTextures.maxMB}, default 64). */
    static final long MAX_BYTES = Math.max(0L, Long.getLong("mc.asyncEntityTextures.maxMB", 64L)) << 20;

    private final ConcurrentHashMap<Identifier, EntityTexturePrefetch.Entry> ready = new ConcurrentHashMap<>();
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicLong cachedBytes = new AtomicLong();
    private volatile boolean closed;
    private @Nullable Thread worker;
    // Statistics (render thread).
    private long hits;
    private long misses;

    /** Render thread, at the start of a resource reload: every prefetched image becomes unusable and is freed. */
    public void invalidate() {
        this.generation.incrementAndGet();
        this.drain();
    }

    /**
     * Render thread, once the reload applied the textures: decodes the prefetch directories of {@code manager} (the
     * reload's resource manager) on a new daemon thread, for the current generation.
     */
    public void start(final ResourceManager manager) {
        if (this.closed) {
            return;
        }

        int gen = this.generation.get();
        Thread thread = new Thread(() -> this.run(manager, gen), "Entity texture prefetch");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 2);
        this.worker = thread;
        thread.start();
    }

    /**
     * Render thread: the prefetched contents of {@code id} (ownership passes to the caller, who applies and so closes
     * them), or null when there are none for the current resources (the caller then loads synchronously).
     */
    public @Nullable TextureContents take(final Identifier id) {
        if (this.ready.isEmpty()) {
            this.misses++;
            return null;
        }

        EntityTexturePrefetch.Entry entry = this.ready.remove(id);
        if (entry == null) {
            this.misses++;
            return null;
        }

        this.cachedBytes.addAndGet(-entry.bytes);
        if (entry.generation != this.generation.get()) {
            entry.contents.close();
            this.misses++;
            return null;
        }

        this.hits++;
        return entry.contents;
    }

    /** Render thread: stops using the prefetch (TextureManager.close) and frees what it holds. */
    @Override
    public void close() {
        this.closed = true;
        this.invalidate();
        if (this.hits > 0L || this.misses > 0L) {
            LOGGER.info("Entity texture prefetch: {} loads served from the prefetch, {} loaded synchronously", this.hits, this.misses);
        }
    }

    private void drain() {
        for (Identifier id : this.ready.keySet()) {
            EntityTexturePrefetch.Entry entry = this.ready.remove(id);
            if (entry != null) {
                this.cachedBytes.addAndGet(-entry.bytes);
                entry.contents.close();
            }
        }
    }

    /** Worker thread. */
    private void run(final ResourceManager manager, final int gen) {
        long start = System.nanoTime();
        List<Identifier> ids = new ArrayList<>();

        try {
            for (String directory : DIRECTORIES) {
                String dir = directory.trim();
                if (!dir.isEmpty()) {
                    ids.addAll(manager.listResources(dir, id -> id.getPath().endsWith(".png")).keySet());
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("Entity texture prefetch: listing resources failed; textures load synchronously", t);
            return;
        }

        int decoded = 0;
        int skipped = 0;

        for (Identifier id : ids) {
            if (this.generation.get() != gen || this.closed) {
                return;
            }

            if (this.ready.containsKey(id)) {
                continue;
            }

            TextureContents contents;
            try {
                contents = TextureContents.load(manager, id);
            } catch (Throwable t) {
                // The synchronous path loads it (and reports any error) exactly as vanilla.
                skipped++;
                continue;
            }

            long bytes = (long)contents.image().getWidth() * contents.image().getHeight() * contents.image().format().components();
            if (this.cachedBytes.get() + bytes > MAX_BYTES) {
                contents.close();
                skipped++;
                continue;
            }

            EntityTexturePrefetch.Entry entry = new EntityTexturePrefetch.Entry(gen, contents, bytes);
            this.cachedBytes.addAndGet(bytes);
            if (this.ready.putIfAbsent(id, entry) != null) {
                this.cachedBytes.addAndGet(-bytes);
                contents.close();
                continue;
            }

            if ((this.generation.get() != gen || this.closed) && this.ready.remove(id, entry)) {
                // invalidate() ran between the check above and the put: this thread owns the entry again.
                this.cachedBytes.addAndGet(-bytes);
                contents.close();
                return;
            }

            decoded++;
        }

        LOGGER.info(
            "Entity texture prefetch: {} textures ({} MB) decoded in {} ms, {} skipped",
            decoded,
            String.format(Locale.ROOT, "%.1f", this.cachedBytes.get() / 1048576.0),
            (System.nanoTime() - start) / 1_000_000L,
            skipped
        );
    }

    // Test seams.
    @VisibleForDebug
    public int size() {
        return this.ready.size();
    }

    @VisibleForDebug
    public long cachedBytes() {
        return this.cachedBytes.get();
    }

    @VisibleForDebug
    public long hits() {
        return this.hits;
    }

    @VisibleForDebug
    public boolean awaitWorker(final long timeoutMs) throws InterruptedException {
        Thread thread = this.worker;
        if (thread == null) {
            return true;
        }

        thread.join(timeoutMs);
        return !thread.isAlive();
    }

    private record Entry(int generation, TextureContents contents, long bytes) {
    }

    private static boolean flag(final String name, final boolean defaultValue) {
        String value = System.getProperty(name);
        return value == null ? defaultValue : !value.equalsIgnoreCase("false") && !value.equals("0");
    }
}
