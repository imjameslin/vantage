package net.minecraft.client.renderer.chunk;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/** CPU-only policy for the opt-in work-buffer prototype. Never limits draw counts or required growth. */
final class LodGpuBufferCapacity {
    static final boolean ENABLED = Boolean.getBoolean("mc.lod.compactWorkBuffers");
    static final int SHRINK_STABLE_FRAMES = 60;
    static final int INACTIVE_GRACE_FRAMES = 120;
    // Coprime with the four-slot ring: sustained maintenance cannot favor just one slot.
    static final int SHRINK_INTERVAL_FRAMES = 13;
    static final long MAX_REPLACEMENT_BYTES = 32L << 20;
    static final long MAX_RETIRED_QUAD_BYTES = 128L << 20;
    static final int MAX_INACTIVE_RETIREMENTS = 2;
    private static final long MIN_QUADS = 65536L;
    private static final long MIN_SHRINK_SAVING_BYTES = 4L << 20;

    private LodGpuBufferCapacity() { }

    record Key(int arenaIndex, int layer, boolean fade, int diagnosticLevel, int band) {
        Key(final int arenaIndex, final int layer, final boolean fade, final int diagnosticLevel) {
            this(arenaIndex, layer, fade, diagnosticLevel, 0);
        }
    }

    static long capacity(final long required) {
        if (required < 0 || required > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid quad bound");
        // 4096 entries: page-friendly for both existing 4-byte and 8-byte formats.
        return Math.max(MIN_QUADS, (required + required / 8L + 4095L) & ~4095L);
    }

    static class Use {
        long lastUsedFrame = -1L;
        long requiredQuads;
        private long underusedSince = -1L;

        final void observe(final long frame, final long required, final long allocated, final int stride, final int ring) {
            long target = capacity(required);
            // Require >=25% excess over the replacement AND >=4 MiB reclaimed.
            boolean underused = allocated - target >= Math.max(target / 4L, MIN_SHRINK_SAVING_BYTES / stride);
            if (!underused || lastUsedFrame < frame - ring) this.underusedSince = -1L;
            if (underused && this.underusedSince < 0L) this.underusedSince = frame;
            this.lastUsedFrame = frame;
            this.requiredQuads = required;
        }

        final boolean shrinkReady(final long frame) {
            return this.underusedSince >= 0L && frame - this.underusedSince >= SHRINK_STABLE_FRAMES;
        }

        final void resized() { this.underusedSince = -1L; }

        final boolean current(final long frame, final int ring) {
            return this.lastUsedFrame >= 0L && frame - this.lastUsedFrame < ring;
        }

        final boolean expired(final long frame) {
            return this.lastUsedFrame >= 0L && frame - this.lastUsedFrame >= INACTIVE_GRACE_FRAMES;
        }
    }

    /** One render-thread budget shared by ALL ring slots. Required growth never consults it. */
    static final class Maintenance {
        private long nextShrinkFrame;
        private long retireFrame = -1L;
        private int retirements;

        boolean shrink(final long frame, final long oldBytes, final long newBytes, final long retiredBytes) {
            if (frame < this.nextShrinkFrame || newBytes > MAX_REPLACEMENT_BYTES || newBytes >= oldBytes
                || oldBytes > MAX_RETIRED_QUAD_BYTES - retiredBytes) return false;
            this.nextShrinkFrame = frame + SHRINK_INTERVAL_FRAMES;
            return true;
        }

        boolean retire(final long frame, final long bytes, final long retiredBytes) {
            if (this.retireFrame != frame) { this.retireFrame = frame; this.retirements = 0; }
            if (this.retirements >= MAX_INACTIVE_RETIREMENTS) return false;
            // One oversized inactive allocation can drain alone; it needs no replacement allocation.
            if (retiredBytes != 0L && bytes > MAX_RETIRED_QUAD_BYTES - retiredBytes) return false;
            this.retirements++;
            return true;
        }
    }

    /** Persistent identity is separate from the caller's current draw-order list. */
    static final class Jobs<T extends Use> {
        private final LinkedHashMap<Key, T> entries = new LinkedHashMap<>();

        T get(final Key key, final Supplier<T> create) {
            return this.entries.computeIfAbsent(key, ignored -> create.get());
        }

        Collection<T> values() { return this.entries.values(); }

        void retireInactive(final long frame, final Maintenance maintenance, final LongSupplier retiredBytes,
                            final ToLongFunction<T> quadBytes, final Consumer<T> retire) {
            var it = this.entries.values().iterator();
            while (it.hasNext()) {
                T job = it.next();
                if (job.expired(frame) && maintenance.retire(frame, quadBytes.applyAsLong(job), retiredBytes.getAsLong())) {
                    retire.accept(job);
                    it.remove();
                }
            }
        }
    }
}
