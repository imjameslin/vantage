import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.metal.MetalBackend;
import com.mojang.renderpearl.backend.metal.MetalBuffer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import net.minecraft.TracingExecutor;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.FaceInfo;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.TerrainCompact;
import net.minecraft.client.renderer.chunk.TerrainFrustum;
import net.minecraft.client.renderer.chunk.TerrainMeshInfo;
import net.minecraft.client.renderer.chunk.TerrainToggles;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

/**
 * Headless micro-benchmark and equivalence check of the terrain draw-list build on the render thread
 * ({@code LevelRenderer.prepareChunkRendersIndirect}: the vanilla-shaped {@code extractSectionDrawGroups} path vs the S4
 * {@code TerrainDrawList} path of TerrainToggles.fastDrawList), on the real Metal device with real vanilla objects:
 * a synthetic heightmap world (caves, trees with fancy leaves, plants, water) is meshed with the real BufferBuilder,
 * analysed/reordered by TerrainMeshInfo exactly like SectionCompiler, wrapped in CompiledSectionMesh and uploaded through
 * the real RenderSection/UberGpuBuffer/StagingBuffer path; a LevelRenderer (allocated without its constructor, only the
 * fields the draw-list code reads are set) then builds one frame per call with a moving, rotating camera.
 *
 * <ul>
 *   <li>{@code bench}: ns and bytes allocated per {@code prepareChunkRendersIndirect} call. On a jar with
 *       TerrainToggles.fastDrawList the two paths alternate in blocks inside one JVM (same JIT and heap state).</li>
 *   <li>{@code check}: every frame is built twice on the same state, by the vanilla-shaped path and by the fast path,
 *       each into its own DynamicGpuData (same ring history), and the two outputs are compared: groups per layer with
 *       heap identities in first-use order, index types, draw counts, ring offsets, every indirect record read back
 *       from the mapped ring, section infos read back from the staging buffer, largest index count, and the S0
 *       counters when enabled. The camera path has churn: recompiles (new meshes replace old ones through the upload
 *       callbacks), translucent resorts (index allocations replaced), section resets, uploads left pending over a
 *       frame, small uber-buffer heaps (many heaps, interleaved groups, translucent order across heaps).
 *       {@code -Dbench.perturb=<TerrainToggles flag>} negates a flag for the fast build only (the check must fail).</li>
 * </ul>
 *
 * Usage (tools/terrain-drawlist-bench.sh): {@code [bench|check] [frames] [key=value...]}; keys: {@code heapKb} (uber
 * heap size, default 2048 for check, vanilla for bench), {@code order} (respectTranslucentOrder, default true),
 * {@code radius} (sections, default 12), {@code block} (bench A/B block length, default 250), {@code dense} (more caves),
 * {@code wireframe}, {@code captured} (frustum capture), {@code visible} ({@code near} = nearest first, default;
 * {@code far} = farthest first; {@code octree} = blocks of 4x4x4 sections, near block first, so the front-to-back
 * section sort of TerrainToggles.sortSolidSections / sortCutoutSections has work to do). JVM properties: {@code -Dbench.thrashMb=N} (touch N MiB between
 * frames: cold caches, closer to a game frame), {@code -Dbench.twice} (warm caches), {@code -Dbench.only=fast|vanilla},
 * {@code -Dbench.empty} (fixed per-call cost), {@code -Dbench.trace}.
 */
public final class TerrainDrawListBench {
    private static final int STRIDE = 28;
    private static final int SEA = 62;

    private static GpuDevice device;
    private static LevelRenderer levelRenderer;
    private static SectionRenderDispatcher dispatcher;
    private static Method addBuffers;
    private static final List<Sec> sections = new ArrayList<>();
    private static final Map<Long, Sec> byNode = new HashMap<>();
    private static ObjectArrayList<SectionRenderDispatcher.RenderSection> visible;
    private static ByteBufferBuilder[] layerBuilders;
    private static ByteBufferBuilder indexBuilder;
    private static boolean dense;
    private static int radius = 12;
    private static Vec3 camera = new Vec3(0.5, 90.0, 0.5);
    private static String visibleOrder = "near";

    /** One section slot: its RenderSection and generation seed. */
    private static final class Sec {
        final int sx;
        final int sy;
        final int sz;
        final SectionRenderDispatcher.RenderSection section;
        int version;

        Sec(final int sx, final int sy, final int sz, final SectionRenderDispatcher.RenderSection section) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.section = section;
        }
    }

    public static void main(final String[] args) throws Throwable {
        Locale.setDefault(Locale.ROOT);
        String mode = args.length > 0 ? args[0] : "bench";
        int frames = args.length > 1 ? Integer.parseInt(args[1]) : (mode.equals("check") ? 600 : 6000);
        Map<String, String> opts = new HashMap<>();
        for (int i = 2; i < args.length; i++) {
            String[] kv = args[i].split("=", 2);
            opts.put(kv[0], kv.length > 1 ? kv[1] : "true");
        }
        if (mode.equals("clouds")) {
            clouds(frames);
            System.exit(0);
        }
        radius = Integer.parseInt(opts.getOrDefault("radius", "12"));
        dense = Boolean.parseBoolean(opts.getOrDefault("dense", "false"));
        visibleOrder = opts.getOrDefault("visible", "near");
        boolean order = Boolean.parseBoolean(opts.getOrDefault("order", "true"));
        int heapKb = Integer.parseInt(opts.getOrDefault("heapKb", mode.equals("check") ? "2048" : "0"));

        MetalBackend backend = new MetalBackend();
        backend.loadLibrary();
        device = backend.createDevice(new GpuDebugOptions(0, false, false, true));
        setStatic(RenderSystem.class, "DEVICE", device);
        setStatic(RenderSystem.class, "renderThread", Thread.currentThread());
        setStatic(RenderSystem.class, "dynamicGpuData", new DynamicGpuData());

        Field fast = null;
        try {
            fast = Class.forName("net.minecraft.client.renderer.chunk.TerrainToggles").getField("fastDrawList");
        } catch (NoSuchFieldException absent) {
            // baseline jar: vanilla-shaped path only
        }
        System.out.printf("jar: %s, fastDrawList toggle: %s%n", LevelRenderer.class.getProtectionDomain().getCodeSource().getLocation(),
            fast == null ? "absent" : fast.get(null));

        buildDispatcher(heapKb);
        buildLevelRenderer();
        LevelRenderState renderState = (LevelRenderState)get(levelRenderer, "levelRenderState");
        renderState.renderWireframeTerrain = Boolean.parseBoolean(opts.getOrDefault("wireframe", "false"));
        renderState.cameraRenderState.isFrustumCaptured = Boolean.parseBoolean(opts.getOrDefault("captured", "false"));
        long t0 = System.nanoTime();
        buildWorld();
        System.out.printf("world: %d sections (%d with geometry) in %.1f s%n", sections.size(),
            sections.stream().filter(s -> s.section.getSectionMesh() instanceof CompiledSectionMesh).count(), (System.nanoTime() - t0) / 1e9);

        if (mode.equals("check")) {
            check(frames, order, fast);
        } else {
            bench(frames, order, fast, Integer.parseInt(opts.getOrDefault("block", "250")));
        }

        List<String> messages = device.getLastDebugMessages();
        for (String m : messages) {
            System.out.println("  device message: " + m);
        }
        System.exit(0);
    }

    // ================================================================================================================
    // Clouds: CloudFaceFilter.filter, per-face puts vs run copies (TerrainToggles.cloudRunCopy)

    /**
     * {@code clouds [calls]}: ns per {@link net.minecraft.client.renderer.CloudFaceFilter#filter} call over a ring-ordered
     * face list the size of the bench scene's (~37k faces, 3 bytes each) into a direct buffer, alternating the toggle;
     * the bytes written are compared too. No device needed.
     */
    private static void clouds(final int calls) throws Throwable {
        int cellRadius = 40;
        ByteBuffer all = ByteBuffer.allocate(3 * 8 * (2 * cellRadius + 1) * (2 * cellRadius + 1));
        for (int ring = 0; ring <= 2 * cellRadius; ring++) {
            for (int x = -ring; x <= ring; x++) {
                int z = ring - Math.abs(x);
                for (int zz : z == 0 ? new int[]{0} : new int[]{-z, z}) {
                    if (Math.abs(zz) <= cellRadius && Math.abs(x) <= cellRadius && Math.floorMod(x * 7 + zz * 13, 5) < 3) {
                        int faces = 4 + Math.floorMod(x * 31 + zz * 17, 3);
                        for (int f = 0; f < faces; f++) {
                            int dirAndFlags = f | (x & 1) << 7 | (zz & 1) << 6;
                            all.put((byte)(x >> 1)).put((byte)(zz >> 1)).put((byte)dirAndFlags);
                        }
                    }
                }
            }
        }
        int faceCount = all.position() / 3;
        byte[] faces = Arrays.copyOf(all.array(), all.position());
        Field runCopy = Class.forName("net.minecraft.client.renderer.chunk.TerrainToggles").getField("cloudRunCopy");
        net.minecraft.client.renderer.CloudFaceFilter filter = new net.minecraft.client.renderer.CloudFaceFilter();
        Matrix4f proj = new Matrix4f().setPerspective((float)Math.toRadians(70.0), 3600.0F / 2260.0F, 1024.0F, 0.05F, true);
        ByteBuffer outA = ByteBuffer.allocateDirect(faces.length);
        ByteBuffer outB = ByteBuffer.allocateDirect(faces.length);
        long[][] ns = new long[2][calls];
        int kept = 0;
        boolean same = true;
        for (int i = 0; i < 2 * calls + 2000; i++) {
            int m = i & 1;
            runCopy.setBoolean(null, m == 1);
            float yaw = (i / 2 % 36) * 10.0F;
            TerrainFrustum f = TerrainFrustum.of(proj, new Matrix4f().rotationX((float)Math.toRadians(-10.0)).rotateY((float)Math.toRadians(yaw)));
            filter.reset(f, -5.0, -40.0, -7.0);
            ByteBuffer out = m == 1 ? outB : outA;
            out.clear();
            long t0 = System.nanoTime();
            int k = filter.filter(faces, faceCount, out);
            long t1 = System.nanoTime();
            if (m == 1) {
                same &= out.position() == outA.position() && out.flip().equals(outA.flip());
                kept = k;
            }
            int n = (i - 2000) / 2;
            if (i >= 2000 && n < calls) {
                ns[m][n] = t1 - t0;
            }
        }
        System.out.printf("clouds: %d faces, %d kept (last), outputs identical: %s%n", faceCount, kept, same);
        for (int m = 0; m < 2; m++) {
            long[] t = ns[m].clone();
            Arrays.sort(t);
            System.out.printf("cloudRunCopy=%-5s median %8.1f us  p90 %8.1f us per filter() call%n", m == 1, t[t.length / 2] / 1e3, t[t.length * 9 / 10] / 1e3);
        }
    }

    // ================================================================================================================
    // Setup

    private static sun.misc.Unsafe unsafe() throws ReflectiveOperationException {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe)f.get(null);
    }

    private static Field field(final Class<?> owner, final String name) throws NoSuchFieldException {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(owner.getName() + "." + name);
    }

    private static void setStatic(final Class<?> owner, final String name, final Object value) throws ReflectiveOperationException {
        field(owner, name).set(null, value);
    }

    private static void set(final Object target, final String name, final Object value) throws ReflectiveOperationException {
        field(target.getClass(), name).set(target, value);
    }

    private static Object get(final Object target, final String name) throws ReflectiveOperationException {
        return field(target.getClass(), name).get(target);
    }

    private static void buildDispatcher(final int heapKb) throws Throwable {
        RenderBuffers renderBuffers = (RenderBuffers)unsafe().allocateInstance(RenderBuffers.class);
        dispatcher = new SectionRenderDispatcher(new TracingExecutor(Executors.newSingleThreadExecutor()), renderBuffers, null, s -> {
        });
        if (heapKb > 0) {
            // Small heaps: many vertex/index heaps per layer, so draw groups interleave (grouping and translucent order).
            Map<?, ?> uber = (Map<?, ?>)get(dispatcher, "chunkUberBuffers");
            for (Object buffers : uber.values()) {
                for (String kind : new String[]{"vertexBuffer", "indexBuffer"}) {
                    UberGpuBuffer<?> ub = (UberGpuBuffer<?>)field(buffers.getClass(), kind).get(buffers);
                    field(UberGpuBuffer.class, "heapSize").setInt(ub, kind.equals("vertexBuffer") ? heapKb * 1024 : heapKb * 256);
                }
            }
        }
        addBuffers = SectionRenderDispatcher.RenderSection.class.getDeclaredMethod(
            "addSectionBuffersToUberBuffer", ChunkSectionLayer.class, CompiledSectionMesh.class, ByteBuffer.class, ByteBuffer.class
        );
        addBuffers.setAccessible(true);
        layerBuilders = new ByteBufferBuilder[]{new ByteBufferBuilder(1 << 20), new ByteBufferBuilder(1 << 20), new ByteBufferBuilder(1 << 20)};
        indexBuilder = new ByteBufferBuilder(1 << 18);
    }

    private static void buildLevelRenderer() throws Throwable {
        LevelRenderer lr = (LevelRenderer)unsafe().allocateInstance(LevelRenderer.class);
        GpuTexture atlasTexture = device.createTexture("bench atlas", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM,
            1024, 512, 1, 1);
        AbstractTexture atlas = (AbstractTexture)unsafe().allocateInstance(SimpleTexture.class);
        set(atlas, "texture", atlasTexture);
        set(atlas, "textureView", device.createTextureView(atlasTexture));
        TextureManager textures = (TextureManager)unsafe().allocateInstance(TextureManager.class);
        Map<Object, Object> byPath = new HashMap<>();
        byPath.put(TextureAtlas.LOCATION_BLOCKS, atlas);
        set(textures, "byPath", byPath);
        set(lr, "textureManager", textures);
        set(lr, "sectionRenderDispatcher", dispatcher);
        set(lr, "levelRenderState", new LevelRenderState());
        OptionsRenderState options = new OptionsRenderState();
        options.chunkSectionFadeInTime = 0.0;
        options.renderDistance = radius;
        set(lr, "optionsRenderState", options);
        visible = new ObjectArrayList<>(10000);
        set(lr, "visibleSections", visible);
        set(lr, "terrainRunScratch", new int[8]);
        set(lr, "frameProjection", new Matrix4f());
        set(lr, "usingMultiDrawIndirectForTerrain", true);
        levelRenderer = lr;
    }

    // ================================================================================================================
    // World

    private static int height(final int x, final int z) {
        return 64 + (int)Math.floor(6.0 * Math.sin(x / 23.0) + 5.0 * Math.cos(z / 17.0) + 3.0 * Math.sin((x + z) / 9.0));
    }

    private static boolean cave(final int x, final int y, final int z) {
        double n = Math.sin(x * 0.21 + y * 0.13) * Math.cos(z * 0.17 - y * 0.11) + Math.sin((x + z) * 0.07 + y * 0.29) * 0.6;
        return n > (dense ? 0.55 : 0.95);
    }

    private static boolean tree(final int x, final int z) {
        int hx = Math.floorMod(x * 73856093 ^ z * 19349663, 97);
        return hx == 0 && height(x, z) > SEA;
    }

    // Precomputed block volume (one block of margin around the sections).
    private static int x0;
    private static int y0;
    private static int z0;
    private static int nx;
    private static int ny;
    private static int nz;
    private static boolean[] solidVol;
    private static boolean[] leafVol;
    private static int[] heights;

    private static int vol(final int x, final int y, final int z) {
        int ix = x - x0;
        int iy = y - y0;
        int iz = z - z0;
        return ix < 0 || iy < 0 || iz < 0 || ix >= nx || iy >= ny || iz >= nz ? -1 : (ix * ny + iy) * nz + iz;
    }

    private static boolean solid(final int x, final int y, final int z) {
        int i = vol(x, y, z);
        return i >= 0 && solidVol[i];
    }

    /** Fancy leaves: a 5x5x3 blob above every tree column (all faces drawn, like fancy leaves). */
    private static boolean leaf(final int x, final int y, final int z) {
        int i = vol(x, y, z);
        return i >= 0 && leafVol[i];
    }

    private static int columnHeight(final int x, final int z) {
        return heights[(x - x0) * nz + (z - z0)];
    }

    private static void buildVolume() {
        x0 = -(radius + 1) * 16;
        z0 = x0;
        y0 = 2 * 16 - 1;
        nx = (2 * radius + 3) * 16;
        nz = nx;
        ny = 5 * 16 + 2;
        solidVol = new boolean[nx * ny * nz];
        leafVol = new boolean[nx * ny * nz];
        heights = new int[nx * nz];
        for (int ix = 0; ix < nx; ix++) {
            for (int iz = 0; iz < nz; iz++) {
                int x = x0 + ix;
                int z = z0 + iz;
                int h = height(x, z);
                heights[ix * nz + iz] = h;
                for (int iy = 0; iy < ny; iy++) {
                    int y = y0 + iy;
                    solidVol[(ix * ny + iy) * nz + iz] = y < h && !(y < h - 3 && cave(x, y, z));
                }
            }
        }
        for (int ix = 0; ix < nx; ix++) {
            for (int iz = 0; iz < nz; iz++) {
                int x = x0 + ix;
                int z = z0 + iz;
                if (!tree(x, z)) {
                    continue;
                }
                int top = heights[ix * nz + iz] + 4;
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        for (int y = top; y < top + 3; y++) {
                            int i = vol(x + dx, y, z + dz);
                            if (i >= 0 && !solidVol[i]) {
                                leafVol[i] = true;
                            }
                        }
                    }
                }
            }
        }
    }

    private static float[] face(final Direction d, final float x, final float y, final float z) {
        FaceInfo info = FaceInfo.fromFacing(d);
        Vector3f lo = new Vector3f(0, 0, 0);
        Vector3f hi = new Vector3f(1, 1, 1);
        float[] p = new float[12];
        for (int v = 0; v < 4; v++) {
            Vector3f pos = info.getVertexInfo(v).select(lo, hi);
            p[3 * v] = pos.x() + x;
            p[3 * v + 1] = pos.y() + y;
            p[3 * v + 2] = pos.z() + z;
        }
        return p;
    }

    private static float[] reversed(final float[] p) {
        float[] r = new float[12];
        for (int v = 0; v < 4; v++) {
            System.arraycopy(p, 3 * (3 - v), r, 3 * v, 3);
        }
        return r;
    }

    private static void put(final BufferBuilder b, final float[] p, final int color) {
        for (int v = 0; v < 4; v++) {
            b.addVertex(p[3 * v], p[3 * v + 1], p[3 * v + 2], color, 0.5F, 0.5F, 0, 15728880, 0.0F, 1.0F, 0.0F);
        }
    }

    /** Meshes one section (section-relative positions) into per-layer builders; returns null when empty. */
    private static SectionCompiler.Results mesh(final Sec s, final MeshData[] out) {
        BufferBuilder[] builders = new BufferBuilder[3];
        Random rnd = new Random(s.sx * 341873128712L + s.sy * 132897987541L + s.sz * 7L + s.version * 9973L);
        int bx = s.sx * 16;
        int by = s.sy * 16;
        int bz = s.sz * 16;
        for (int ly = 0; ly < 16; ly++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int x = bx + lx;
                    int y = by + ly;
                    int z = bz + lz;
                    if (solid(x, y, z)) {
                        for (Direction d : Direction.values()) {
                            if (!solid(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ())) {
                                put(builder(builders, 0), face(d, lx, ly, lz), 0x808080FF ^ rnd.nextInt(0x100) << 8);
                            }
                        }
                    } else if (leaf(x, y, z)) {
                        for (Direction d : Direction.values()) {
                            put(builder(builders, 1), face(d, lx, ly, lz), 0x30A030FF);
                        }
                    } else if (y < SEA && y == SEA - 1) {
                        float[] top = face(Direction.UP, lx, ly, lz);
                        for (int v = 0; v < 4; v++) {
                            top[3 * v + 1] = ly + 0.875F;
                        }
                        put(builder(builders, 2), top, 0x3040C0A0);
                        put(builder(builders, 2), reversed(top), 0x3040C0A0);
                    } else if (y == columnHeight(x, z) && solid(x, y - 1, z) && rnd.nextInt(8) == 0) {
                        float b = 0.01F;
                        float[] a1 = {lx + 0.1464F, ly + b, lz + 0.1464F, lx + 0.1464F, ly + 1, lz + 0.1464F, lx + 0.8536F, ly + 1, lz + 0.8536F, lx + 0.8536F, ly + b, lz + 0.8536F};
                        float[] a2 = {lx + 0.8536F, ly + b, lz + 0.1464F, lx + 0.8536F, ly + 1, lz + 0.1464F, lx + 0.1464F, ly + 1, lz + 0.8536F, lx + 0.1464F, ly + b, lz + 0.8536F};
                        put(builder(builders, 1), a1, 0x20C040FF);
                        put(builder(builders, 1), a2, 0x20C040FF);
                    }
                }
            }
        }

        SectionCompiler.Results results = new SectionCompiler.Results();
        boolean any = false;
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            int l = layer.ordinal();
            BufferBuilder b = builders[l];
            MeshData m = b == null ? null : b.build();
            out[l] = m;
            if (m == null) {
                continue;
            }
            any = true;
            TerrainMeshInfo info = TerrainMeshInfo.analyze(m.vertexBuffer(), m.drawState().vertexCount(), STRIDE, 0, !layer.translucent());
            if (info != null) {
                results.terrainInfo.put(layer, info);
            }
            if (TerrainToggles.COMPACT_VERTICES && !layer.translucent()) {
                // S5, as SectionCompiler: leaves-only CUTOUT and every SOLID layer are exact, layers with plants fall back.
                ByteBuffer compact = TerrainCompact.tryTranscode(layer, m.vertexBuffer(), m.drawState().vertexCount(), true);
                if (compact != null) {
                    results.compactVertices.put(layer, compact);
                }
            }
            if (layer == ChunkSectionLayer.TRANSLUCENT) {
                results.transparencyState = m.sortQuads(indexBuilder, sorting(s));
            }
            results.renderedLayers.put(layer, m);
        }
        return any ? results : null;
    }

    private static VertexSorting sorting(final Sec s) {
        return VertexSorting.byDistance((float)(camera.x - s.sx * 16), (float)(camera.y - s.sy * 16), (float)(camera.z - s.sz * 16));
    }

    private static BufferBuilder builder(final BufferBuilder[] builders, final int l) {
        if (builders[l] == null) {
            builders[l] = new BufferBuilder(layerBuilders[l], PrimitiveTopology.QUADS, DefaultVertexFormat.BLOCK);
        }
        return builders[l];
    }

    /** Compiles {@code s} at its current version and stages its buffers (the CompileTask path); uploads happen later. */
    private static void compile(final Sec s) throws Throwable {
        MeshData[] meshes = new MeshData[3];
        SectionCompiler.Results results = mesh(s, meshes);
        long node = SectionPos.asLong(s.sx, s.sy, s.sz);
        if (results == null) {
            // empty section: the EMPTY mesh is set directly (no uploads), as CompileTask does
            Method setMesh = SectionRenderDispatcher.RenderSection.class.getDeclaredMethod("setSectionMesh", SectionMesh.class);
            setMesh.setAccessible(true);
            dispatcher.lock();
            try {
                SectionMesh old = (SectionMesh)setMesh.invoke(s.section, CompiledSectionMesh.EMPTY);
                Method release = SectionRenderDispatcher.RenderSection.class.getDeclaredMethod("releaseSectionMesh", SectionMesh.class);
                release.setAccessible(true);
                release.invoke(s.section, old);
            } finally {
                dispatcher.unlock();
            }
            return;
        }
        CompiledSectionMesh mesh = new CompiledSectionMesh(TranslucencyPointOfView.of(camera, node), results, 0L);
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            MeshData m = meshes[layer.ordinal()];
            if (m == null) {
                continue;
            }
            ByteBuffer vertexBytes = results.compactVertices.getOrDefault(layer, m.vertexBuffer());
            while (!(boolean)addBuffers.invoke(s.section, layer, mesh, vertexBytes, m.indexBuffer())) {
                // staging full: addSectionBuffersToUberBuffer uploaded on this (render) thread; retry
            }
            m.close();
        }
    }

    /** Re-sorts the translucent layer of {@code s} for the current camera (the ResortTransparencyTask path). */
    private static void resort(final Sec s) throws Throwable {
        if (!(s.section.getSectionMesh() instanceof CompiledSectionMesh mesh) || mesh.getTransparencyState() == null
            || mesh.isEmpty(ChunkSectionLayer.TRANSLUCENT)) {
            return;
        }
        ByteBufferBuilder.Result indices = mesh.getTransparencyState().buildSortedIndexBuffer(indexBuilder, sorting(s));
        if (indices == null) {
            return;
        }
        while (!(boolean)addBuffers.invoke(s.section, ChunkSectionLayer.TRANSLUCENT, mesh, null, indices.byteBuffer())) {
        }
        indices.close();
    }

    private static void upload() {
        dispatcher.lock();
        try {
            // A heap creation ends a round early (vanilla); the game calls this every frame, the harness a few times.
            for (int i = 0; i < 6; i++) {
                dispatcher.uploadTerrainBuffersToGpu();
            }
        } finally {
            dispatcher.unlock();
        }
    }

    private static void buildWorld() throws Throwable {
        buildVolume();
        int index = 0;
        for (int sx = -radius; sx <= radius; sx++) {
            for (int sz = -radius; sz <= radius; sz++) {
                if (sx * sx + sz * sz > radius * radius + 1) {
                    continue;
                }
                for (int sy = 2; sy <= 6; sy++) {
                    long node = SectionPos.asLong(sx, sy, sz);
                    Sec s = new Sec(sx, sy, sz, dispatcher.new RenderSection(index++, node));
                    sections.add(s);
                    byNode.put(node, s);
                }
            }
        }
        for (Sec s : sections) {
            compile(s);
        }
        upload();
    }

    // ================================================================================================================
    // Frames

    private static final Matrix4f projection = new Matrix4f();
    private static final Matrix4f view = new Matrix4f();
    private static final Method[] cullingMethod = new Method[1];

    /** Camera pose of frame {@code f}: slow walk plus a full turn every 720 frames, pitched down. */
    private static void pose(final int f) throws Throwable {
        double t = f / 720.0 * Math.PI * 2.0;
        camera = new Vec3(12.0 * Math.sin(t * 0.5) + 0.37, 86.0 + 4.0 * Math.sin(t * 0.25), 9.0 * Math.cos(t * 0.5) + 0.61);
        float yaw = (float)t;
        float pitch = (float)Math.toRadians(-18.0 + 10.0 * Math.sin(t * 1.3));
        projection.setPerspective((float)Math.toRadians(70.0), 3600.0F / 2260.0F, 0.05F, 768.0F);
        view.rotationX(-pitch).rotateY(yaw);
        LevelRenderState state = (LevelRenderState)get(levelRenderer, "levelRenderState");
        state.cameraRenderState.pos = camera;
        state.cameraRenderState.projectionMatrix = new Matrix4f(projection);
        state.cameraRenderState.viewRotationMatrix = new Matrix4f(view);
        levelRenderer.setFrameProjection(projection);
        if (cullingMethod[0] == null) {
            cullingMethod[0] = LevelRenderer.class.getDeclaredMethod("prepareTerrainCulling", org.joml.Matrix4fc.class);
            cullingMethod[0].setAccessible(true);
        }
        cullingMethod[0].invoke(levelRenderer, new Matrix4f(view));
        // visibleSections: coarse culling with a widened frustum (like the octree visit), nearest first.
        Matrix4f wide = new Matrix4f().setPerspective((float)Math.toRadians(100.0), 3600.0F / 2260.0F, 0.05F, 768.0F);
        TerrainFrustum coarse = TerrainFrustum.of(wide, view);
        visible.clear();
        List<Sec> picked = new ArrayList<>();
        for (Sec s : sections) {
            double x0 = s.sx * 16 - camera.x;
            double y0 = s.sy * 16 - camera.y;
            double z0 = s.sz * 16 - camera.z;
            if (coarse.isBoxVisible(x0, y0, z0, x0 + 16, y0 + 16, z0 + 16)) {
                picked.add(s);
            }
        }
        picked.sort(Comparator.comparingDouble((Sec s) -> dist2(s)).thenComparingInt(s -> s.section.index));
        if (visibleOrder.equals("far")) {
            Collections.reverse(picked);
        } else if (visibleOrder.equals("octree")) {
            // Hierarchical order like the octree walk: 4x4x4-section blocks nearest first, inside a block x, y, z order.
            int cx = (int)Math.floor(camera.x / 64.0);
            int cy = (int)Math.floor(camera.y / 64.0);
            int cz = (int)Math.floor(camera.z / 64.0);
            picked.sort(Comparator.comparingInt((Sec s) -> Math.abs(Math.floorDiv(s.sx, 4) - cx) + Math.abs(Math.floorDiv(s.sy, 4) - cy)
                    + Math.abs(Math.floorDiv(s.sz, 4) - cz))
                .thenComparingInt(s -> Math.floorDiv(s.sx, 4)).thenComparingInt(s -> Math.floorDiv(s.sy, 4)).thenComparingInt(s -> Math.floorDiv(s.sz, 4))
                .thenComparingInt(s -> s.sx).thenComparingInt(s -> s.sy).thenComparingInt(s -> s.sz));
        }
        for (Sec s : picked) {
            visible.add(s.section);
        }
        if (System.getProperty("bench.empty") != null) {
            visible.clear();   // fixed per-call overhead only
        }
    }

    private static double dist2(final Sec s) {
        double dx = s.sx * 16 + 8 - camera.x;
        double dy = s.sy * 16 + 8 - camera.y;
        double dz = s.sz * 16 + 8 - camera.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static ChunkSectionsToRender frame(final boolean order) {
        return levelRenderer.prepareChunkRendersIndirect(new Matrix4f(view), order);
    }

    private static void endFrame() {
        device.createCommandEncoder().submit();
        RenderSystem.getDynamicUniforms().reset();
    }

    private static void bench(final int frames, final boolean order, final Field fast, final int block) throws Throwable {
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        int modes = fast == null ? 1 : 2;
        long[][] ns = new long[modes][frames];
        long[][] bytes = new long[modes][frames];
        int[] n = new int[modes];
        int warmup = Math.min(3000, frames);
        int[] records = new int[1];
        // -Dbench.only=fast|vanilla: one path only (for profiling it)
        String only = System.getProperty("bench.only");
        // -Dbench.thrashMb=N: touch N MiB between frames (cold caches, closer to a real frame)
        int thrashMb = Integer.getInteger("bench.thrashMb", 0);
        byte[] thrash = thrashMb > 0 ? new byte[thrashMb << 20] : null;
        for (int f = 0; f < warmup + frames * modes; f++) {
            boolean measuring = f >= warmup;
            int m = modes == 1 ? 0 : only != null ? (only.equals("fast") ? 1 : 0) : ((f / block) & 1);
            if (fast != null) {
                fast.setBoolean(null, m == 1);
            }
            pose(f % 1440);
            if (System.getProperty("bench.twice") != null) {
                frame(order);   // warm the caches: the timed call below then measures instructions, not misses
            }
            if (thrash != null) {
                // the rest of a game frame (entities, GUI, decoder) evicts the caches between two draw-list builds
                for (int i = 0; i < thrash.length; i += 64) {
                    thrash[i]++;
                }
            }
            long a0 = threads.getCurrentThreadAllocatedBytes();
            long t0 = System.nanoTime();
            ChunkSectionsToRender out = frame(order);
            long t1 = System.nanoTime();
            long a1 = threads.getCurrentThreadAllocatedBytes();
            if (measuring && n[m] < frames) {
                ns[m][n[m]] = t1 - t0;
                bytes[m][n[m]] = a1 - a0;
                n[m]++;
            }
            if (f == warmup) {
                records[0] = countRecords(out);
            }
            endFrame();
        }
        System.out.printf("visible sections/frame (last): %d, records (frame %d): %d%n", visible.size(), warmup, records[0]);
        for (int m = 0; m < modes; m++) {
            if (n[m] == 0) {
                continue;
            }
            long[] t = Arrays.copyOf(ns[m], n[m]);
            long[] b = Arrays.copyOf(bytes[m], n[m]);
            Arrays.sort(t);
            Arrays.sort(b);
            double mean = Arrays.stream(t).average().orElse(0);
            String name = fast == null ? "vanilla-shaped (baseline jar)" : (m == 1 ? "fastDrawList=true" : "fastDrawList=false");
            System.out.printf("%-30s frames %5d  median %7.1f us  mean %7.1f us  p10 %7.1f us  p90 %7.1f us  alloc median %8d B/frame%n", name, t.length,
                t[t.length / 2] / 1e3, mean / 1e3, t[t.length / 10] / 1e3, t[t.length * 9 / 10] / 1e3, b[b.length / 2]);
        }
    }

    // ================================================================================================================
    // Check

    private static int countRecords(final ChunkSectionsToRender out) throws ReflectiveOperationException {
        @SuppressWarnings("unchecked")
        EnumMap<ChunkSectionLayer, List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect>> groups =
            (EnumMap<ChunkSectionLayer, List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect>>)get(out, "drawGroupsPerLayer");
        int n = 0;
        for (List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect> list : groups.values()) {
            for (ChunkSectionsToRender.GpuMultiDrawIndexedIndirect g : list) {
                n += g.drawCount();
            }
        }
        return n;
    }

    /**
     * Digest state of one output stream: buffer identities are numbered in first-use order, so two streams that use
     * different ring buffers (one DynamicGpuData each) but the same shared heaps compare equal when they match.
     * Per-frame component hashes: [0] structure (groups, heaps, index types, counts), [1] ring/UBO offsets,
     * [2] indirect records, [3] section infos; {@link #total} chains every frame.
     */
    private static final class Digest {
        final IdentityHashMap<Object, Integer> ids = new IdentityHashMap<>();
        final long[] part = new long[4];
        long total = 0xcbf29ce484222325L;

        int id(final Object o) {
            return o == null ? -1 : this.ids.computeIfAbsent(o, k -> this.ids.size());
        }

        void mix(final int part, final long v) {
            long h = this.part[part];
            for (int i = 0; i < 8; i++) {
                h ^= (v >>> (8 * i)) & 0xFF;
                h *= 0x100000001b3L;
            }
            this.part[part] = h;
        }

        void beginFrame() {
            Arrays.fill(this.part, 0xcbf29ce484222325L);
        }

        void endFrame() {
            for (long p : this.part) {
                for (int i = 0; i < 8; i++) {
                    this.total ^= (p >>> (8 * i)) & 0xFF;
                    this.total *= 0x100000001b3L;
                }
            }
        }

        String parts() {
            return String.format("struct %016x ring %016x records %016x infos %016x", this.part[0], this.part[1], this.part[2], this.part[3]);
        }
    }

    /** Digests one frame's output into {@code d}; returns {groups, records, sections}. */
    private static int[] digest(final ChunkSectionsToRender out, final Digest d, final DynamicGpuData dynamic) throws ReflectiveOperationException {
        d.beginFrame();
        int[] counts = new int[3];
        @SuppressWarnings("unchecked")
        EnumMap<ChunkSectionLayer, List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect>> groups =
            (EnumMap<ChunkSectionLayer, List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect>>)get(out, "drawGroupsPerLayer");
        d.mix(0, field(ChunkSectionsToRender.class, "maxIndicesRequired").getInt(out));
        d.mix(0, d.id(field(ChunkSectionsToRender.class, "terrainQuadIndexBuffer").get(out)));
        GpuBufferSlice ubo = (GpuBufferSlice)field(ChunkSectionsToRender.class, "terrainTransformUBO").get(out);
        d.mix(1, d.id(ubo.buffer()));
        d.mix(1, ubo.offset());
        d.mix(1, ubo.length());
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect> list = groups.get(layer);
            d.mix(0, list.size());
            for (ChunkSectionsToRender.GpuMultiDrawIndexedIndirect g : list) {
                counts[0]++;
                counts[1] += g.drawCount();
                d.mix(0, d.id(g.vertexBuffer().buffer()));
                d.mix(0, g.vertexBuffer().offset());
                d.mix(0, g.vertexBuffer().length());
                d.mix(0, g.indexBuffer() == null ? -2 : d.id(g.indexBuffer().buffer()));
                d.mix(0, g.indexType() == null ? -2 : g.indexType().ordinal());
                d.mix(0, g.drawCount());
                if (g.compact()) {
                    d.mix(0, 0xC0);   // S5 format (mixed only when compact: digests of 28-byte-only runs are unchanged)
                }
                GpuBufferSlice cmd = g.indirectCommandBuffer();
                d.mix(1, d.id(cmd.buffer()));
                d.mix(1, cmd.offset());
                d.mix(1, cmd.length());
                long base = ((MetalBuffer)cmd.buffer()).contents() + cmd.offset();
                for (int i = 0; i < g.drawCount() * 5; i++) {
                    d.mix(2, MemoryUtil.memGetInt(base + 4L * i));
                }
            }
        }
        GpuBufferSlice infos = (GpuBufferSlice)get(out, "chunkSectionInfos");
        d.mix(1, d.id(infos.buffer()));
        d.mix(1, infos.offset());
        d.mix(1, infos.length());
        if (infos.buffer() != null) {
            Object storage = get(dynamic, "chunkSections");
            ByteBuffer cpu = (ByteBuffer)get(storage, "cpuBuffer");
            for (long i = 0; i < infos.length(); i += 4) {
                d.mix(3, MemoryUtil.memGetInt(MemoryUtil.memAddress0(cpu) + infos.offset() + i));
            }
            counts[2] = (int)(infos.length() / 16);
        }
        d.endFrame();
        return counts;
    }

    /** S0 counters added since the previous call (TerrainStats.endFrame + total), empty when the counters are off. */
    private static long[] statsDelta() throws ReflectiveOperationException {
        Class<?> stats = Class.forName("net.minecraft.client.renderer.chunk.TerrainStats");
        if (!stats.getField("ENABLED").getBoolean(null)) {
            return new long[0];
        }
        long[] total = (long[])stats.getField("total").get(null);
        long[] before = total.clone();
        stats.getMethod("endFrame").invoke(null);
        long[] delta = new long[total.length];
        for (int i = 0; i < total.length; i++) {
            delta[i] = total[i] - before[i];
        }
        return delta;
    }

    /**
     * Equivalence check. With the toggle present, every frame is built twice on the same world state: the vanilla-shaped
     * path into one DynamicGpuData and the fast path into a second one (same ring history each), and the two digests must
     * match frame by frame. Without the toggle (baseline jar) only the digest of the vanilla-shaped path is printed (it is
     * not reproducible across JVMs: upload order follows identity hash codes).
     */
    private static void check(final int frames, final boolean order, final Field fast) throws Throwable {
        Random rnd = new Random(1234);
        long groups = 0;
        long records = 0;
        long secs = 0;
        int maxGroups = 0;
        int mismatches = 0;
        DynamicGpuData dynamicA = RenderSystem.getDynamicUniforms();
        DynamicGpuData dynamicB = fast != null ? new DynamicGpuData() : null;
        Digest a = new Digest();
        Digest b = new Digest();
        for (int f = 0; f < frames; f++) {
            pose(f * 3);
            // Churn (deterministic): recompiles, translucent resorts, resets; uploads as the game does after the frame.
            if (f % 7 == 3) {
                for (int i = 0; i < 4; i++) {
                    Sec s = sections.get(rnd.nextInt(sections.size()));
                    s.version++;
                    compile(s);
                }
            }
            if (f % 5 == 1) {
                for (int i = 0; i < 6; i++) {
                    resort(sections.get(rnd.nextInt(sections.size())));
                }
            }
            if (f % 11 == 5) {
                sections.get(rnd.nextInt(sections.size())).section.reset();
            }
            if (f % 13 == 0) {
                Sec s = sections.get(rnd.nextInt(sections.size()));
                s.version++;
                compile(s);   // staged but not uploaded until after the next frame
            } else {
                upload();
            }
            if (fast != null) {
                fast.setBoolean(null, false);
            }
            setStatic(RenderSystem.class, "dynamicGpuData", dynamicA);
            long[] statsA = statsDelta();
            int[] c = digest(frame(order), a, dynamicA);
            statsA = statsDelta();
            if (fast != null) {
                fast.setBoolean(null, true);
                setStatic(RenderSystem.class, "dynamicGpuData", dynamicB);
                // -Dbench.perturb=<TerrainToggles boolean>: negate it for the fast build only (proves the check detects a difference)
                String perturb = System.getProperty("bench.perturb");
                Field perturbed = perturb == null ? null : fast.getDeclaringClass().getField(perturb);
                if (perturbed != null) {
                    perturbed.setBoolean(null, !perturbed.getBoolean(null));
                }
                int[] cb = digest(frame(order), b, dynamicB);
                long[] statsB = statsDelta();
                if (perturbed != null) {
                    perturbed.setBoolean(null, !perturbed.getBoolean(null));
                }
                if (!Arrays.equals(statsA, statsB) && mismatches++ < 10) {
                    System.out.printf("MISMATCH frame %d: TerrainStats%n  vanilla %s%n  fast    %s%n", f, Arrays.toString(statsA), Arrays.toString(statsB));
                }
                if (!Arrays.equals(a.part, b.part) || !Arrays.equals(c, cb)) {
                    if (mismatches++ < 10) {
                        System.out.printf("MISMATCH frame %d:%n  vanilla %s (%s)%n  fast    %s (%s)%n", f, a.parts(), Arrays.toString(c), b.parts(),
                            Arrays.toString(cb));
                    }
                }
            }
            if (System.getProperty("bench.trace") != null) {
                System.out.printf("frame %d: %s groups %d records %d sections %d%n", f, a.parts(), c[0], c[1], c[2]);
            }
            groups += c[0];
            records += c[1];
            secs += c[2];
            maxGroups = Math.max(maxGroups, c[0]);
            device.createCommandEncoder().submit();
            dynamicA.reset();
            if (dynamicB != null) {
                dynamicB.reset();
            }
        }
        System.out.printf("check: %d frames, mean groups %.1f (max %d), mean records %.1f, mean sections %.1f, buffers seen %d%n",
            frames, groups / (double)frames, maxGroups, records / (double)frames, secs / (double)frames, a.ids.size());
        System.out.printf("DIGEST vanilla-shaped %016x%n", a.total);
        if (fast != null) {
            System.out.printf("DIGEST fastDrawList   %016x%n", b.total);
            System.out.println(mismatches == 0 && a.total == b.total ? "CHECK PASSED: identical output on every frame" : "CHECK FAILED: " + mismatches + " frames differ");
        }
    }
}
