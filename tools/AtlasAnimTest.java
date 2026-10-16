import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.metal.MetalBackend;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.jar.JarFile;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.Stitcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

/**
 * R5-D (CONTRACT.md 16.R5-D), -Dmc.fastTextureAnimation: headless parity + cost probe for the texture-animation upload
 * on the real Metal backend. Builds a block atlas the way the game does (Stitcher with mip level 4, padding 16, the jar's
 * real block textures: every animated one, interpolated and not, plus static ones; real mip generation), uploads it with
 * the patched TextureAtlas, then runs animation ticks (TextureAtlas.tick, one submit per tick like the game's frame).
 *
 * <ul>
 *   <li>Parity: prints {@code DIGEST tick level sha256} of every mip level after the initial upload and after each of
 *       {@code atlas.ticks} ticks. tools/atlas-anim-test.sh runs it with the toggle off and on and requires identical
 *       digests (byte-identical atlas contents).</li>
 *   <li>Cost: {@code atlas.costTicks} more ticks without readback; prints the mean Java time of tick() (recording), the
 *       stream bytes it records, and the native submit's records decoded / encoders created / cpuNanos.</li>
 * </ul>
 */
public final class AtlasAnimTest {
    private static int failures;
    /**
     * {@code -Datlas.noInterpolated=true} (r5-int review fix, D): leave out interpolated sprites, so ticks take the
     * BLIT-only pass and, when no sprite changed frame, the "nothing to draw" early return of uploadAnimationFramesFast
     * (the common case for atlases without interpolated sprites). tools/atlas-anim-test.sh runs both configurations.
     */
    private static final boolean NO_INTERPOLATED = Boolean.getBoolean("atlas.noInterpolated");
    private static final int MIN_FRAME_TIME = Integer.getInteger("atlas.minFrameTime", 1);
    private static final int MAX_ANIMATED = Integer.getInteger("atlas.maxAnimated", Integer.MAX_VALUE);

    public static void main(final String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        int ticks = Integer.getInteger("atlas.ticks", 320);
        int costTicks = Integer.getInteger("atlas.costTicks", 2000);
        int staticSprites = Integer.getInteger("atlas.static", 400);
        System.out.println("mc.fastTextureAnimation=" + Boolean.getBoolean("mc.fastTextureAnimation"));
        MetalBackend backend = new MetalBackend();
        backend.loadLibrary();
        GpuDevice device = backend.createDevice(new GpuDebugOptions(0, false, false, true));
        Field deviceField = RenderSystem.class.getDeclaredField("DEVICE");
        deviceField.setAccessible(true);
        deviceField.set(null, device);
        ExecutorService workers = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "atlas-anim-compile");
            t.setDaemon(true);
            return t;
        });
        TextureAtlas atlas = null;
        try {
            RenderSystem.initRenderThread();
            RenderSystem.getSamplerCache().initialize();
            ShaderSource shaders = jarShaderSource();
            PipelineCache cache = new PipelineCache(device, shaders);
            for (RenderPipeline p : List.of(RenderPipelines.ANIMATE_SPRITE_BLIT, RenderPipelines.ANIMATE_SPRITE_INTERPOLATE, RenderPipelines.LIGHTMAP)) {
                CompiledRenderPipeline compiled = device.compilePipeline(p, shaders, workers).join().finishCompile();
                check(compiled != null, "pipeline " + p.getLocation() + " compiles");
                cache.insert(p, compiled);
            }
            RenderSystem.setFallbackPipelineCache(cache);

            Identifier location = Identifier.withDefaultNamespace("textures/atlas/blocks.png");
            atlas = new TextureAtlas(location);
            SpriteLoader.Preparations prep = stitch(location, loadSprites(staticSprites), 4);
            System.out.printf("atlas %dx%d mip %d, %d sprites%n", prep.width(), prep.height(), prep.mipLevel(), prep.regions().size());
            atlas.upload(prep);
            CommandEncoder enc = device.createCommandEncoder();
            int levels = prep.mipLevel() + 1;
            // R6-1: with -Dmc.texture.pingPong the atlas swaps copies at ticks: always read the copy frames would sample.
            // atlas.pingPongFlip=N (N > 0): flip TexturePingPong.enabled every N ticks (runtime switching); =-1: flip at
            // pseudo-random ticks (seeded).
            int flip = Integer.getInteger("atlas.pingPongFlip", 0);
            // R7-2: atlas.computeFlip=-1 flips AtlasCompute.enabled (kernel vs render passes) at pseudo-random ticks.
            int computeFlip = Integer.getInteger("atlas.computeFlip", 0);
            java.util.SplittableRandom flipRandom = new java.util.SplittableRandom(11);
            int flips = 0;
            java.util.Set<GpuTexture> copies = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            copies.add(atlas.getTexture());
            digest(device, enc, atlas.getTexture(), levels, 0);
            for (int t = 1; t <= ticks; t++) {
                if (computeFlip != 0 && flipRandom.nextInt(3) == 0) {
                    net.minecraft.client.renderer.texture.AtlasCompute.enabled = !net.minecraft.client.renderer.texture.AtlasCompute.enabled;
                }
                if (flip > 0 && t % flip == 0 || flip < 0 && flipRandom.nextInt(5) == 0) {
                    net.minecraft.client.renderer.texture.TexturePingPong.enabled = !net.minecraft.client.renderer.texture.TexturePingPong.enabled;
                    flips++;
                }
                atlas.tick();
                copies.add(atlas.getTexture());
                digest(device, enc, atlas.getTexture(), levels, t);
            }
            lightmapFrames(device, enc, flip);
            System.out.println("PINGPONG enabled at end " + net.minecraft.client.renderer.texture.TexturePingPong.enabled + ", flips " + flips
                + ", distinct copies sampled " + copies.size() + ", kernel ticks " + net.minecraft.client.renderer.texture.AtlasCompute.ticks);

            // Cost probe: recording time and stream size of tick(), native decode of the submit that carries it.
            Object metalDevice = field(device, "backend");
            Object metalEncoder = metalDevice.getClass().getMethod("encoder").invoke(metalDevice);
            Object mainStream = field(metalEncoder, "mainStream");
            java.lang.reflect.Method size = mainStream.getClass().getMethod("size");
            size.setAccessible(true);
            long scratch = (long)field(metalEncoder, "submitScratch");
            long result = scratch + 64L; // SUBMIT_INFO_SIZEOF
            long javaNs = 0;
            long streamBytes = 0;
            long records = 0;
            long encoders = 0;
            long draws = 0;
            long nativeNs = 0;
            int measured = 0;
            for (int t = 0; t < costTicks; t++) {
                enc.submit();
                long before = (long)size.invoke(mainStream);
                long t0 = System.nanoTime();
                atlas.tick();
                long t1 = System.nanoTime();
                long after = (long)size.invoke(mainStream);
                enc.submit();
                if (t >= costTicks / 4) { // skip JIT warm-up
                    javaNs += t1 - t0;
                    streamBytes += after - before;
                    records += MemoryUtil.memGetInt(result + 24);
                    draws += MemoryUtil.memGetInt(result + 28);
                    encoders += MemoryUtil.memGetInt(result + 36);
                    nativeNs += MemoryUtil.memGetLong(result + 48);
                    measured++;
                }
            }
            System.out.printf(
                "COST tick(): java %.1f us, stream %.0f B, records %.1f, draws %.1f, encoders %.2f, native submit %.1f us (mean of %d ticks)%n",
                javaNs / 1e3 / measured, (double)streamBytes / measured, (double)records / measured, (double)draws / measured,
                (double)encoders / measured, nativeNs / 1e3 / measured, measured
            );
            List<String> messages = device.getLastDebugMessages();
            for (String m : messages) {
                System.out.println("  device message: " + m);
            }
            check(messages.isEmpty(), "no device messages");
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            check(false, "threw " + t);
        } finally {
            if (atlas != null) {
                atlas.close();
            }
            workers.shutdownNow();
        }
        System.out.println(failures == 0 ? "ATLAS ANIM PASSED" : "ATLAS ANIM FAILED (" + failures + ")");
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * R6-1: the lightmap. 160 frames with random updates (GameRenderer's order: notePendingUpdate at extract, then render),
     * random parameters (seeded, the same in every run) and, with atlas.pingPongFlip != 0, the toggle flipped at random
     * frames. Prints DIGEST L&lt;frame&gt; of the texture getTextureView() names after the frame (what frames sample), and
     * checks that getTextureViewForThisFrame() (read before render, as the F3 lightmap chart does) names that texture.
     */
    private static void lightmapFrames(final GpuDevice device, final CommandEncoder enc, final int flip) throws Exception {
        net.minecraft.client.renderer.Lightmap lightmap = new net.minecraft.client.renderer.Lightmap();
        java.util.SplittableRandom r = new java.util.SplittableRandom(5);
        java.util.SplittableRandom flipRandom = new java.util.SplittableRandom(17);
        net.minecraft.client.renderer.state.LightmapRenderState state = new net.minecraft.client.renderer.state.LightmapRenderState();
        java.util.Set<Object> copies = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        int updates = 0;
        for (int frame = 0; frame < 160; frame++) {
            if (flip != 0 && flipRandom.nextInt(7) == 0) {
                net.minecraft.client.renderer.texture.TexturePingPong.enabled = !net.minecraft.client.renderer.texture.TexturePingPong.enabled;
            }
            state.needsUpdate = r.nextInt(3) != 0;
            state.skyFactor = r.nextFloat();
            state.blockFactor = r.nextFloat();
            state.brightness = r.nextFloat();
            state.darknessEffectScale = r.nextInt(4) == 0 ? r.nextFloat() : 0.0F;
            state.nightVisionEffectIntensity = r.nextInt(4) == 0 ? r.nextFloat() : 0.0F;
            state.bossOverlayWorldDarkening = r.nextInt(4) == 0 ? r.nextFloat() : 0.0F;
            state.blockLightTint = new org.joml.Vector3f(r.nextFloat(), r.nextFloat(), r.nextFloat());
            state.skyLightColor = new org.joml.Vector3f(r.nextFloat(), r.nextFloat(), r.nextFloat());
            lightmap.notePendingUpdate(state.needsUpdate);
            GpuTextureView predicted = lightmap.getTextureViewForThisFrame();
            lightmap.render(state);
            updates += state.needsUpdate ? 1 : 0;
            check(predicted == lightmap.getTextureView(), "lightmap frame " + frame + ": view for this frame == view after the update");
            copies.add(lightmap.getTextureView());
            GpuTexture texture = lightmap.getTextureView().texture();
            allowCopySource(texture);
            GpuBuffer buffer = device.createBuffer(() -> "lightmap readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, 16 * 16 * 4);
            enc.copyTextureToBuffer(texture, buffer, 0, () -> {}, 0);
            GpuFence fence = enc.createFence();
            enc.submit();
            check(fence.awaitCompletion(5_000_000_000L), "lightmap readback fence");
            fence.close();
            byte[] data = new byte[16 * 16 * 4];
            try (GpuBufferSlice.MappedView view = buffer.slice(0, data.length).map(true, false)) {
                view.data().get(data);
            }
            buffer.close();
            System.out.println("DIGEST L" + frame + " 0 " + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)));
        }
        System.out.println("LIGHTMAP updates " + updates + ", distinct copies sampled " + copies.size() + ", kernel updates " + net.minecraft.client.renderer.LightmapCompute.dispatches);
        lightmap.close();
    }

    /**
     * The lightmap textures are created with vanilla's usage 13 (no USAGE_COPY_SRC, a front-end check only: the Metal
     * texture usage is the same as with TEXTURE_BINDING, MetalFormats). For the readback the test adds the flag to the
     * Java object's usage field.
     */
    private static void allowCopySource(final GpuTexture texture) throws ReflectiveOperationException {
        for (Class<?> c = texture.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("usage");
                f.setAccessible(true);
                f.setInt(texture, f.getInt(texture) | GpuTexture.USAGE_COPY_SRC);
                return;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException("usage");
    }

    private static Object field(final Object owner, final String name) throws ReflectiveOperationException {
        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(owner);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void check(final boolean ok, final String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    /** Every animated block texture of the jar plus the first {@code staticCount} static ones (sorted, deterministic). */
    private static List<SpriteContents> loadSprites(final int staticCount) throws IOException {
        String jarPath = TextureAtlas.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        List<String> pngs = new ArrayList<>();
        try (JarFile jar = new JarFile(java.net.URLDecoder.decode(jarPath, StandardCharsets.UTF_8))) {
            jar.stream().map(e -> e.getName()).filter(n -> n.startsWith("assets/minecraft/textures/block/") && n.endsWith(".png")).sorted()
                .forEach(pngs::add);
        }
        List<SpriteContents> out = new ArrayList<>();
        int animated = 0;
        int interpolated = 0;
        int statics = 0;
        for (String png : pngs) {
            String mcmeta = resource(png + ".mcmeta");
            Optional<AnimationMetadataSection> animation = Optional.empty();
            Optional<TextureMetadataSection> texture = Optional.empty();
            if (mcmeta != null) {
                JsonObject json = JsonParser.parseString(mcmeta).getAsJsonObject();
                JsonElement a = json.get("animation");
                if (a != null) {
                    animation = Optional.of(AnimationMetadataSection.CODEC.parse(JsonOps.INSTANCE, a).getOrThrow());
                }
                JsonElement t = json.get("texture");
                if (t != null) {
                    texture = Optional.of(TextureMetadataSection.CODEC.parse(JsonOps.INSTANCE, t).getOrThrow());
                }
            }
            if (animation.isEmpty()) {
                if (statics >= staticCount) {
                    continue;
                }
            } else if (NO_INTERPOLATED && animation.get().interpolatedFrames()) {
                continue;   // -Datlas.noInterpolated: an atlas without interpolated sprites (gui / particle-like)
            } else if (animation.get().defaultFrameTime() < MIN_FRAME_TIME || animated >= MAX_ANIMATED) {
                continue;   // -Datlas.minFrameTime / maxAnimated: few, slow animations, so some ticks change nothing
            }
            NativeImage image;
            try (InputStream in = AtlasAnimTest.class.getClassLoader().getResourceAsStream(png)) {
                image = NativeImage.read(in);
            }
            FrameSize frameSize = animation.isPresent()
                ? animation.get().calculateFrameSize(image.getWidth(), image.getHeight())
                : new FrameSize(image.getWidth(), image.getHeight());
            if (image.getWidth() % frameSize.width() != 0 || image.getHeight() % frameSize.height() != 0
                || Integer.lowestOneBit(Math.min(frameSize.width(), frameSize.height())) < 16) {
                image.close(); // would lower the atlas mip level (the game warns and drops it); keep mip 4
                continue;
            }
            String name = png.substring("assets/minecraft/textures/".length(), png.length() - 4);
            SpriteContents contents = new SpriteContents(Identifier.withDefaultNamespace(name), frameSize, image, animation, List.of(), texture);
            out.add(contents);
            if (contents.isAnimated()) {
                animated++;
                if (animation.get().interpolatedFrames()) {
                    interpolated++;
                }
            } else {
                statics++;
            }
        }
        System.out.printf("sprites: %d animated (%d interpolated), %d static%n", animated, interpolated, statics);
        if (NO_INTERPOLATED) {
            check(animated >= Math.min(20, MAX_ANIMATED) && interpolated == 0, "only non-interpolated animated sprites");
        } else {
            check(animated >= 40 && interpolated >= 15, "enough animated sprites");
        }
        return out;
    }

    private static SpriteLoader.Preparations stitch(final Identifier location, final List<SpriteContents> sprites, final int mipLevel)
        throws ReflectiveOperationException {
        List<SpriteContents> all = new ArrayList<>(sprites);
        all.add(MissingTextureAtlasSprite.create());
        Stitcher<SpriteContents> stitcher = new Stitcher<>(16384, 16384, mipLevel, 0);
        for (SpriteContents s : all) {
            stitcher.registerSprite(s);
        }
        stitcher.stitch();
        int width = stitcher.getWidth();
        int height = stitcher.getHeight();
        Constructor<TextureAtlasSprite> ctor = TextureAtlasSprite.class.getDeclaredConstructor(
            Identifier.class, SpriteContents.class, int.class, int.class, int.class, int.class, int.class
        );
        ctor.setAccessible(true);
        Map<Identifier, TextureAtlasSprite> regions = new HashMap<>();
        List<ReflectiveOperationException> errors = new ArrayList<>();
        stitcher.gatherSprites((contents, x, y, padding) -> {
            try {
                regions.put(contents.name(), ctor.newInstance(location, contents, width, height, x, y, padding));
            } catch (ReflectiveOperationException e) {
                errors.add(e);
            }
        });
        if (!errors.isEmpty()) {
            throw errors.getFirst();
        }
        for (TextureAtlasSprite s : regions.values()) {
            s.contents().increaseMipLevel(mipLevel);
        }
        return new SpriteLoader.Preparations(width, height, mipLevel, regions.get(MissingTextureAtlasSprite.getLocation()), regions,
            CompletableFuture.completedFuture(null));
    }

    private static void digest(final GpuDevice device, final CommandEncoder enc, final GpuTexture texture, final int levels, final int tick)
        throws Exception {
        List<GpuBuffer> buffers = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        for (int level = 0; level < levels; level++) {
            int bytes = texture.getWidth(level) * texture.getHeight(level) * 4;
            GpuBuffer buffer = device.createBuffer(() -> "atlas readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
            enc.copyTextureToBuffer(texture, buffer, 0, () -> {}, level);
            buffers.add(buffer);
            sizes.add(bytes);
        }
        GpuFence fence = enc.createFence();
        enc.submit();
        check(fence.awaitCompletion(5_000_000_000L), "readback fence");
        fence.close();
        StringBuilder line = new StringBuilder();
        for (int level = 0; level < levels; level++) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] data = new byte[sizes.get(level)];
            try (GpuBufferSlice.MappedView view = buffers.get(level).slice(0, sizes.get(level)).map(true, false)) {
                view.data().get(data);
            }
            buffers.get(level).close();
            md.update(data);
            String dump = System.getProperty("atlas.dumpDir");
            if (dump != null && tick == Integer.getInteger("atlas.dumpTick", 1)) {
                java.nio.file.Files.write(java.nio.file.Path.of(dump, "L" + level + ".raw"), data);
            }
            line.append("DIGEST ").append(tick).append(' ').append(level).append(' ').append(HexFormat.of().formatHex(md.digest())).append('\n');
        }
        System.out.print(line);
    }

    private static ShaderSource jarShaderSource() {
        Map<Identifier, ShaderSource.CachedIncludeSource> includes = new ConcurrentHashMap<>();
        return new ShaderSource() {
            @Override
            public String getShader(final Identifier id, final ShaderType type) {
                return resource("assets/" + id.getNamespace() + "/shaders/" + id.getPath() + (type == ShaderType.VERTEX ? ".vsh" : ".fsh"));
            }

            @Override
            public CachedIncludeSource getInclude(final Identifier id) {
                return includes.computeIfAbsent(id, key -> {
                    String source = resource("assets/" + key.getNamespace() + "/shaders/include/" + key.getPath());
                    return source == null ? null : CachedIncludeSource.create(key, source);
                });
            }

            @Override
            public void close() {
                includes.values().forEach(CachedIncludeSource::close);
                includes.clear();
            }
        };
    }

    private static String resource(final String path) {
        try (InputStream in = AtlasAnimTest.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(path, e);
        }
    }
}
