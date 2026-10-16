import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.metal.MetalBackend;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.renderer.CloudFaceFilter;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.FaceInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.TerrainCompact;
import net.minecraft.client.renderer.chunk.TerrainDrawList;
import net.minecraft.client.renderer.chunk.TerrainFrustum;
import net.minecraft.client.renderer.chunk.TerrainMeshInfo;
import net.minecraft.client.renderer.chunk.TerrainRuns;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * GPU parity test of the stage-2 terrain patches on the real Metal backend (docs/stage2/TERRAIN-PLAN.md S1, S1b, S2,
 * S7), headless and offscreen, with vanilla pipelines compiled from the jar's shaders:
 *
 * <ul>
 *   <li>Terrain ({@code SOLID_TERRAIN_MULTIDRAW}, indirect records, instanced ChunkPosition): several sections of unit
 *       cubes (all six faces, so coplanar opposite faces exist) and 45-degree plants, drawn (A) in emission order as one
 *       record per section with a 32-bit quad index buffer (vanilla), and (B) reordered into facing buckets with
 *       records split at 16383 quads over the dedicated 16-bit buffer, (C, D) additionally with only the buckets that
 *       can face the eye (bridging 32 / 0), (E) additionally without the sections outside the exact frustum. Several
 *       camera poses: outside, inside a tunnel, at a section boundary, with view bob and with the nausea skew. Colour and
 *       depth must be bit-identical to (A).</li>
 *   <li>Compact vertex (S5, {@link TerrainCompact}): random exactly-encodable boxes (1/16 and 1/2048 grids, sub-texel UVs
 *       over a random mip-mapped atlas, per-vertex light over a random lightmap, fog) near the origin and 30 000 blocks out,
 *       drawn with the 28-byte BLOCK vertex and the vanilla pipelines and with the 16-byte vertex and the compact pipelines
 *       (SOLID and CUTOUT multidraw, SOLID per-section UBO), nearest and RGSS sampling, 48 poses. Bit-identical. Snap
 *       mode: non-overlapping boxes with positions one or two ulps off the grid (block-state rotation noise), 28-byte noisy
 *       floats vs snapped 16-byte vertices, 24 poses: at most 1 in 10^4 drawn pixels may differ by more than 4/255 (edge
 *       pixels whose centre lies within the snap distance of an edge); 1-LSB rounding differences of the interpolated
 *       colour are reported, not limited.</li>
 *   <li>Cutout ({@code CUTOUT_TERRAIN_MULTIDRAW}, alpha-tested leaf sprite): canopy sections with S6 front-to-back facing
 *       buckets and with the records sorted front-to-back (and back-to-front) by section distance, nearest and RGSS
 *       sampling, five poses. Bit-identical to emission order.</li>
 *   <li>Clouds ({@code CLOUDS}: blended, depth-writing, back-face culled): the full ring-ordered face list against the
 *       {@link CloudFaceFilter} subset, for frames the filter's reference covers. Bit-identical.</li>
 * </ul>
 *
 * Run with {@code MTL_DEBUG_LAYER=1} through tools/terrain-e2e-test.sh (needs build/client.jar).
 */
public final class TerrainE2ETest {
    private static final int W = 320;
    private static final int H = 200;
    private static final int RT_USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST
        | GpuTexture.USAGE_TEXTURE_BINDING;
    private static final int STRIDE = 28;
    private static int checks;
    private static int failures;

    public static void main(final String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        MetalBackend backend = new MetalBackend();
        backend.loadLibrary();
        GpuDevice device = backend.createDevice(new GpuDebugOptions(0, false, false, true));
        Field field = RenderSystem.class.getDeclaredField("DEVICE");
        field.setAccessible(true);
        field.set(null, device);
        try {
            Rig rig = new Rig(device);
            try {
                rig.terrain();
                rig.compact();
                rig.cutout();
                rig.clouds();
                List<String> messages = device.getLastDebugMessages();
                for (String m : messages) {
                    System.out.println("  device message: " + m);
                }
                check(messages.isEmpty(), "no device messages");
            } finally {
                rig.close();
                device.close();
            }
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            check(false, "threw " + t);
        }
        System.out.printf("TerrainE2ETest: %d checks, %d failures%n", checks, failures);
        System.out.println(failures == 0 ? "TERRAIN E2E PASSED" : "TERRAIN E2E FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(final boolean ok, final String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL: " + what);
        }
    }

    // ================================================================================================================

    /** One section of synthetic terrain: quads in emission order plus the bucketed and the S6 (front-to-back) copies. */
    private static final class Section {
        final int ox;
        final int oy;
        final int oz;
        final ByteBuffer original;
        final ByteBuffer bucketed;
        final ByteBuffer frontToBack;
        final int quads;
        final TerrainMeshInfo info;
        final TerrainMeshInfo infoF2B;
        int baseVertex;

        Section(final int ox, final int oy, final int oz, final List<float[]> quads, final List<Integer> colors) {
            this(ox, oy, oz, quads, colors, false);
        }

        /** {@code textured}: each quad maps the whole 16x16 atlas (corner UVs) instead of sampling its centre. */
        Section(final int ox, final int oy, final int oz, final List<float[]> quads, final List<Integer> colors, final boolean textured) {
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.quads = quads.size();
            this.original = ByteBuffer.allocateDirect(this.quads * 4 * STRIDE).order(ByteOrder.nativeOrder());
            float[] us = {0, 0, 1, 1};
            float[] vs = {0, 1, 1, 0};
            for (int q = 0; q < this.quads; q++) {
                float[] p = quads.get(q);
                int rgba = colors.get(q);
                for (int v = 0; v < 4; v++) {
                    this.original.putFloat(p[3 * v]).putFloat(p[3 * v + 1]).putFloat(p[3 * v + 2]);
                    this.original.put((byte)(rgba >>> 24)).put((byte)(rgba >>> 16)).put((byte)(rgba >>> 8)).put((byte)rgba);
                    this.original.putFloat(textured ? us[v] : 0.5f).putFloat(textured ? vs[v] : 0.5f).putShort((short)240).putShort((short)240);
                }
            }
            this.original.flip();
            this.bucketed = ByteBuffer.allocateDirect(this.original.capacity()).order(ByteOrder.nativeOrder());
            this.bucketed.put(this.original.duplicate()).flip();
            this.info = TerrainMeshInfo.analyze(this.bucketed, this.quads * 4, STRIDE, 0, true);
            this.frontToBack = ByteBuffer.allocateDirect(this.original.capacity()).order(ByteOrder.nativeOrder());
            this.frontToBack.put(this.original.duplicate()).flip();
            this.infoF2B = TerrainMeshInfo.analyze(this.frontToBack, this.quads * 4, STRIDE, 0, true, true);
        }
    }

    private static final class Rig implements AutoCloseable {
        final GpuDevice device;
        final CommandEncoder enc;
        final ExecutorService workers = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "terrain-e2e-compile");
            t.setDaemon(true);
            return t;
        });
        final List<AutoCloseable> owned = new ArrayList<>();
        CompiledRenderPipeline terrainPipeline;
        CompiledRenderPipeline cloudPipeline;
        CompiledRenderPipeline cutoutPipeline;
        GpuSampler nearest;
        GpuSampler linear;
        GpuTextureView atlas;
        GpuTextureView cutoutAtlas;
        GpuTextureView lightmap;

        Rig(final GpuDevice device) {
            this.device = device;
            this.enc = device.createCommandEncoder();
            ShaderSource shaders = jarShaderSource();
            List<RenderPipeline> all = List.of(RenderPipelines.SOLID_TERRAIN_MULTIDRAW, RenderPipelines.CLOUDS, RenderPipelines.CUTOUT_TERRAIN_MULTIDRAW);
            List<CompiledRenderPipeline> compiled = all.stream().map(p -> device.compilePipeline(p, shaders, this.workers)).toList().stream()
                .map(f -> f.join().finishCompile()).toList();
            check(!compiled.contains(null), "pipelines compile");
            compiled.forEach(this.owned::add);
            this.terrainPipeline = compiled.get(0);
            this.cloudPipeline = compiled.get(1);
            this.cutoutPipeline = compiled.get(2);
            // Leaf-like sprite: random colours, ~45% of the texels fully transparent (discarded by ALPHA_CUTOUT 0.5).
            GpuTexture leafTex = this.own(device.createTexture((String)"terrain e2e cutout atlas", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, 16, 16, 1, 1));
            ByteBuffer leaf = direct(16 * 16 * 4);
            Random leafRnd = new Random(5);
            for (int i = 0; i < 256; i++) {
                leaf.put((byte)(40 + leafRnd.nextInt(80))).put((byte)(120 + leafRnd.nextInt(120))).put((byte)(30 + leafRnd.nextInt(60)))
                    .put((byte)(leafRnd.nextInt(100) < 45 ? 0 : 255));
            }
            this.enc.writeToTexture(leafTex, leaf.flip(), 0, 0, 0, 0, 16, 16);
            this.cutoutAtlas = this.own(device.createTextureView(leafTex));
            this.nearest = this.own(device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, 1,
                OptionalDouble.empty()));
            this.linear = this.own(device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, 1,
                OptionalDouble.empty()));
            GpuTexture atlasTex = this.own(device.createTexture((String)"terrain e2e atlas", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, 16, 16, 1, 1));
            ByteBuffer white = direct(16 * 16 * 4);
            for (int i = 0; i < 256; i++) {
                white.putInt(-1);
            }
            this.enc.writeToTexture(atlasTex, white.flip(), 0, 0, 0, 0, 16, 16);
            this.atlas = this.own(device.createTextureView(atlasTex));
            GpuTexture lightTex = this.own(device.createTexture((String)"terrain e2e lightmap", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, 16, 16, 1, 1));
            this.enc.writeToTexture(lightTex, white.rewind(), 0, 0, 0, 0, 16, 16);
            this.lightmap = this.own(device.createTextureView(lightTex));
        }

        <T> T own(final T resource) {
            if (resource instanceof AutoCloseable c) {
                this.owned.add(c);
            }
            return resource;
        }

        @Override
        public void close() {
            for (int i = this.owned.size() - 1; i >= 0; i--) {
                try {
                    this.owned.get(i).close();
                } catch (Exception ignored) {
                }
            }
            this.workers.shutdownNow();
        }

        GpuBufferSlice ubo(final ByteBuffer data) {
            return this.enc.transientMemory().uploadGpu(data, this.device.getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM);
        }

        GpuBuffer quadIndexBuffer(final int quads, final boolean shorts) {
            ByteBuffer b = direct(quads * 6 * (shorts ? 2 : 4));
            for (int q = 0; q < quads; q++) {
                int v = q * 4;
                for (int i : new int[]{v, v + 1, v + 2, v + 2, v + 3, v}) {
                    if (shorts) {
                        b.putShort((short)i);
                    } else {
                        b.putInt(i);
                    }
                }
            }
            return this.own(this.device.createBuffer(() -> "terrain e2e quad indices", GpuBuffer.USAGE_INDEX, b.flip()));
        }

        byte[] readback(final GpuTexture texture) {
            int bytes = W * H * 4;
            GpuBuffer buffer = this.device.createBuffer(() -> "terrain e2e readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
            this.enc.copyTextureToBuffer(texture, buffer, 0, () -> {}, 0);
            GpuFence fence = this.enc.createFence();
            this.enc.submit();
            check(fence.awaitCompletion(5_000_000_000L), "readback fence");
            fence.close();
            byte[] out = new byte[bytes];
            try (GpuBufferSlice.MappedView view = buffer.slice(0, bytes).map(true, false)) {
                view.data().get(out);
            }
            buffer.close();
            return out;
        }

        // ------------------------------------------------------------------------------------------------------------
        // terrain

        static float[] face(final Direction d, final float x, final float y, final float z) {
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

        /** Section-relative cubes on a 16^3 grid with a tunnel through y, z in [6, 10) and an empty pocket. */
        static Section section(final Random rnd, final int ox, final int oy, final int oz, final double density) {
            List<float[]> quads = new ArrayList<>();
            List<Integer> colors = new ArrayList<>();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        boolean tunnel = y >= 6 && y < 10 && z >= 6 && z < 10;
                        if (tunnel) {
                            if (rnd.nextInt(10) == 0 && y == 6) {
                                // 45-degree plant standing on the tunnel floor, lifted off it (no shared line with the floor)
                                float b = 0.01f;
                                float[] a1 = {x + 0.1464f, y + b, z + 0.1464f, x + 0.1464f, y + 1, z + 0.1464f, x + 0.8536f, y + 1, z + 0.8536f, x + 0.8536f, y + b, z + 0.8536f};
                                float[] a2 = {x + 0.8536f, y + b, z + 0.1464f, x + 0.8536f, y + 1, z + 0.1464f, x + 0.1464f, y + 1, z + 0.8536f, x + 0.1464f, y + b, z + 0.8536f};
                                quads.add(a1);
                                colors.add(0x20C040FF);
                                quads.add(a2);
                                colors.add(0x30D050FF);
                            }
                            continue;
                        }
                        if (rnd.nextDouble() >= density) {
                            continue;
                        }
                        int base = rnd.nextInt(0x1000000);
                        for (Direction d : Direction.values()) {
                            quads.add(face(d, x, y, z));
                            colors.add((base * 7 + d.ordinal() * 0x2F3B1D) << 8 & 0xFFFFFF00 | 0xFF);
                        }
                    }
                }
            }
            return new Section(ox, oy, oz, quads, colors);
        }

        record Pose(String name, double x, double y, double z, float yaw, float pitch, Matrix4f extra) {
        }

        void terrain() {
            Random rnd = new Random(42);
            List<Section> sections = new ArrayList<>();
            sections.add(section(rnd, 16, 32, 48, 0.20));
            sections.add(section(rnd, 32, 32, 48, 0.75));   // > 16383 quads: S1b split
            sections.add(section(rnd, 48, 32, 48, 0.10));
            sections.add(section(rnd, 16, 48, 48, 0.15));
            sections.add(section(rnd, 32, 32, 16, 0.10));
            sections.add(section(rnd, -32, 32, 48, 0.25));  // behind the camera for most poses: S2
            int totalQuads = 0;
            int maxQuads = 0;
            for (Section s : sections) {
                check(s.info != null && s.info.bucketed(), "section metadata");
                s.baseVertex = totalQuads * 4;
                totalQuads += s.quads;
                maxQuads = Math.max(maxQuads, s.quads);
            }
            check(maxQuads > TerrainRuns.SHORT_INDEX_QUADS, "one section needs a split record (" + maxQuads + " quads)");
            ByteBuffer heapA = direct(totalQuads * 4 * STRIDE);
            ByteBuffer heapB = direct(totalQuads * 4 * STRIDE);
            for (Section s : sections) {
                heapA.put(s.original.duplicate());
                heapB.put(s.bucketed.duplicate());
            }
            GpuBuffer vertsA = this.own(this.device.createBuffer(() -> "terrain e2e heap A", GpuBuffer.USAGE_VERTEX, heapA.flip()));
            GpuBuffer vertsB = this.own(this.device.createBuffer(() -> "terrain e2e heap B", GpuBuffer.USAGE_VERTEX, heapB.flip()));
            ByteBuffer inst = direct(16 * sections.size());
            for (Section s : sections) {
                inst.putInt(s.ox).putInt(s.oy).putInt(s.oz).putFloat(1.0f);
            }
            GpuBuffer instances = this.own(this.device.createBuffer(() -> "terrain e2e chunk data", GpuBuffer.USAGE_VERTEX, inst.flip()));
            GpuBuffer indices32 = this.quadIndexBuffer(maxQuads, false);
            GpuBuffer indices16 = this.quadIndexBuffer(TerrainRuns.SHORT_INDEX_QUADS, true);

            float walk = 0.37f;
            float bob = 0.1f;
            Matrix4f bobMat = new Matrix4f()
                .translate((float)Math.sin(walk * Math.PI) * bob * 0.5f, -Math.abs((float)Math.cos(walk * Math.PI) * bob), 0)
                .rotateZ((float)Math.toRadians(Math.sin(walk * Math.PI) * bob * 3))
                .rotateX((float)Math.toRadians(Math.abs(Math.cos(walk * Math.PI - 0.2) * bob) * 5));
            Vector3f axis = new Vector3f(0, (float)Math.sqrt(2) / 2, (float)Math.sqrt(2) / 2);
            Matrix4f nausea = new Matrix4f().rotate(0.9f, axis).scale(1 / 0.55f, 1, 1).rotate(-0.9f, axis);
            List<Pose> poses = List.of(
                new Pose("above, looking down", 40.3, 70.2, 60.7, 30, 50, new Matrix4f()),
                new Pose("far outside, level", 90.5, 40.1, 95.2, 135, 5, new Matrix4f()),
                new Pose("inside the tunnel", 24.5, 39.5, 56.5, 270, 3, new Matrix4f()),
                new Pose("tunnel, facing back", 24.5, 39.5, 56.5, 90, -10, new Matrix4f()),
                new Pose("section boundary x = 32 + 0.03", 32.03, 39.2, 56.8, 250, 20, new Matrix4f()),
                new Pose("section boundary x = 32 - 0.03, view bob", 31.97, 39.2, 56.8, 280, -15, bobMat),
                new Pose("tunnel, nausea skew", 40.5, 39.5, 57.5, 300, 0, nausea),
                new Pose("below, looking up", 41.0, 20.0, 60.0, 200, -60, new Matrix4f()));
            for (Pose pose : poses) {
                this.terrainPose(pose, sections, vertsA, vertsB, instances, indices32, indices16);
            }
        }

        void terrainPose(
            final Pose pose, final List<Section> sections, final GpuBuffer vertsA, final GpuBuffer vertsB, final GpuBuffer instances,
            final GpuBuffer indices32, final GpuBuffer indices16
        ) {
            Matrix4f projection = new Matrix4f().setPerspective((float)Math.toRadians(77), (float)W / H, 1024.0f, 0.05f, true).mul(pose.extra());
            Matrix4f view = new Matrix4f().rotationX((float)Math.toRadians(pose.pitch())).rotateY((float)Math.toRadians(pose.yaw() + 180));
            TerrainFrustum frustum = TerrainFrustum.of(projection, view);
            check(frustum.hasEye(), pose.name() + ": eye");
            double eyeX = pose.x() + frustum.eyeX();
            double eyeY = pose.y() + frustum.eyeY();
            double eyeZ = pose.z() + frustum.eyeZ();
            int[] scratch = new int[8];
            List<DynamicGpuData.IndexedDraw> a = new ArrayList<>();
            List<DynamicGpuData.IndexedDraw> b = new ArrayList<>();
            List<DynamicGpuData.IndexedDraw> c = new ArrayList<>();
            List<DynamicGpuData.IndexedDraw> d = new ArrayList<>();
            List<DynamicGpuData.IndexedDraw> e = new ArrayList<>();
            int quadsA = 0;
            int quadsC = 0;
            int quadsE = 0;
            int culledSections = 0;
            double m = 1.0 / 64.0;
            for (int i = 0; i < sections.size(); i++) {
                Section s = sections.get(i);
                a.add(new DynamicGpuData.IndexedDraw(6 * s.quads, 1, 0, s.baseVertex, i));
                quadsA += s.quads;
                TerrainRuns.emitWhole(s.quads, s.baseVertex, i, TerrainRuns.SHORT_INDEX_QUADS, 1, b);
                int mask = s.info.visibleMask(eyeX - s.ox, eyeY - s.oy, eyeZ - s.oz, TerrainRuns.EPS);
                quadsC += TerrainRuns.emit(s.info, mask, 32, s.baseVertex, i, TerrainRuns.SHORT_INDEX_QUADS, 1, scratch, c);
                TerrainRuns.emit(s.info, mask, 0, s.baseVertex, i, TerrainRuns.SHORT_INDEX_QUADS, 1, scratch, d);
                boolean visible = frustum.isBoxVisible(
                    s.ox + (s.info.minX - m) - pose.x(), s.oy + (s.info.minY - m) - pose.y(), s.oz + (s.info.minZ - m) - pose.z(),
                    s.ox + (s.info.maxX + m) - pose.x(), s.oy + (s.info.maxY + m) - pose.y(), s.oz + (s.info.maxZ + m) - pose.z());
                if (visible) {
                    quadsE += TerrainRuns.emit(s.info, mask, 32, s.baseVertex, i, TerrainRuns.SHORT_INDEX_QUADS, 1, scratch, e);
                } else {
                    culledSections++;
                }
            }
            GpuTexture[] refTargets = this.renderTerrain(this.terrainPipeline, this.atlas, pose, projection, view, vertsA, indices32, IndexType.INT, instances, a);
            byte[] refColor = this.readback(refTargets[0]);
            byte[] refDepth = this.readback(refTargets[1]);
            int drawn = 0;
            for (int i = 0; i < W * H; i++) {
                int px = (refColor[4 * i] & 255) << 16 | (refColor[4 * i + 1] & 255) << 8 | refColor[4 * i + 2] & 255;
                if (px != 0x1A334D) {
                    drawn++;
                }
            }
            check(drawn > W * H / 20, pose.name() + ": reference image shows terrain (" + drawn + " px)");
            // Cross-run parity (terrain ICB, CONTRACT.md 16.5): the digest must not change between MCMTL_TERRAIN_ICB=0 and
            // MCMTL_TERRAIN_ICB=1 MCMTL_TERRAIN_ICB_SYNC=1 runs (tools/terrain-e2e-test.sh --icb-parity compares them).
            long digest = 0xcbf29ce484222325L;
            for (byte[] bytes : new byte[][] {refColor, refDepth}) {
                for (byte v : bytes) {
                    digest = (digest ^ (v & 0xFF)) * 0x100000001b3L;
                }
            }
            System.out.printf("  DIGEST %s %016x%n", pose.name(), digest);
            String[] names = {"B reordered + split (S1b)", "C buckets, bridge 32 (S1)", "D buckets, bridge 0", "E buckets + exact frustum (S2)"};
            List<List<DynamicGpuData.IndexedDraw>> lists = List.of(b, c, d, e);
            for (int k = 0; k < 4; k++) {
                GpuTexture[] t = this.renderTerrain(this.terrainPipeline, this.atlas, pose, projection, view, vertsB, indices16, IndexType.SHORT, instances, lists.get(k));
                byte[] color = this.readback(t[0]);
                byte[] depth = this.readback(t[1]);
                int diff = 0;
                for (int i = 0; i < color.length; i += 4) {
                    if (color[i] != refColor[i] || color[i + 1] != refColor[i + 1] || color[i + 2] != refColor[i + 2] || color[i + 3] != refColor[i + 3]
                        || depth[i] != refDepth[i] || depth[i + 1] != refDepth[i + 1] || depth[i + 2] != refDepth[i + 2] || depth[i + 3] != refDepth[i + 3]) {
                        diff++;
                    }
                }
                check(diff == 0, pose.name() + ": " + names[k] + " differs from vanilla in " + diff + " pixels");
            }
            System.out.printf("  %-44s %6d px drawn; quads: vanilla %6d, buckets %6d (-%2.0f%%), + frustum %6d; records %d -> %d; %d section(s) culled%n",
                pose.name(), drawn, quadsA, quadsC, 100.0 * (quadsA - quadsC) / quadsA, quadsE, a.size(), c.size(), culledSections);
        }

        // ------------------------------------------------------------------------------------------------------------
        // cutout (S6 front-to-back buckets, front-to-back section order)

        /**
         * Leaf-canopy sections ({@code CUTOUT_TERRAIN_MULTIDRAW}, an atlas with ~45% transparent texels): unit cubes with all six
         * faces (fancy leaves draw the faces between leaf blocks) and 45-degree plants in empty cells (not touching anything).
         * Drawn (A) in emission order, vanilla section order, one record per section, 32-bit indices; (B) bucketed with the
         * visible-bucket runs (today's path); (C) with S6 front-to-back buckets; (D) S6 plus records sorted front-to-back by
         * section distance ({@link TerrainDrawList#sortBySectionDistance}); (E) S6 with the sections back-to-front (the worst
         * order). Nearest and RGSS sampling. Colour and depth must be bit-identical to (A).
         */
        void cutout() {
            Random rnd = new Random(2024);
            List<Section> sections = new ArrayList<>();
            for (int sx = 0; sx < 3; sx++) {
                for (int sz = 0; sz < 3; sz++) {
                    sections.add(this.canopy(rnd, 16 * sx, 48, 16 * sz, 0.28 + 0.1 * rnd.nextDouble()));
                }
            }
            sections.add(this.canopy(rnd, 16, 64, 16, 0.2));
            int totalQuads = 0;
            int maxQuads = 0;
            for (Section s : sections) {
                check(s.info != null && s.infoF2B != null && s.info.bucketed(), "cutout section metadata");
                s.baseVertex = totalQuads * 4;
                totalQuads += s.quads;
                maxQuads = Math.max(maxQuads, s.quads);
            }
            ByteBuffer heapA = direct(totalQuads * 4 * STRIDE);
            ByteBuffer heapB = direct(totalQuads * 4 * STRIDE);
            ByteBuffer heapC = direct(totalQuads * 4 * STRIDE);
            for (Section s : sections) {
                heapA.put(s.original.duplicate());
                heapB.put(s.bucketed.duplicate());
                heapC.put(s.frontToBack.duplicate());
            }
            GpuBuffer vertsA = this.own(this.device.createBuffer(() -> "cutout e2e heap A", GpuBuffer.USAGE_VERTEX, heapA.flip()));
            GpuBuffer vertsB = this.own(this.device.createBuffer(() -> "cutout e2e heap B", GpuBuffer.USAGE_VERTEX, heapB.flip()));
            GpuBuffer vertsC = this.own(this.device.createBuffer(() -> "cutout e2e heap C", GpuBuffer.USAGE_VERTEX, heapC.flip()));
            ByteBuffer inst = direct(16 * sections.size());
            List<DynamicGpuData.ChunkSectionInfo> infos = new ArrayList<>();
            for (Section s : sections) {
                inst.putInt(s.ox).putInt(s.oy).putInt(s.oz).putFloat(1.0f);
                infos.add(new DynamicGpuData.ChunkSectionInfo(s.ox, s.oy, s.oz, 1.0f));
            }
            GpuBuffer instances = this.own(this.device.createBuffer(() -> "cutout e2e chunk data", GpuBuffer.USAGE_VERTEX, inst.flip()));
            GpuBuffer indices32 = this.quadIndexBuffer(maxQuads, false);
            GpuBuffer indices16 = this.quadIndexBuffer(TerrainRuns.SHORT_INDEX_QUADS, true);
            List<Pose> poses = List.of(
                new Pose("canopy from above", 70.3, 90.2, 70.7, 135, 40, new Matrix4f()),
                new Pose("canopy, level from outside", -20.5, 58.1, 60.2, 230, 4, new Matrix4f()),
                new Pose("inside the canopy", 23.5, 56.5, 22.5, 20, 10, new Matrix4f()),
                new Pose("inside, looking up", 25.2, 52.3, 26.9, 300, -55, new Matrix4f()),
                new Pose("below the canopy", 30.1, 40.2, 8.3, 160, -35, new Matrix4f()));
            int[] scratch = new int[8];
            for (Pose pose : poses) {
                Matrix4f projection = new Matrix4f().setPerspective((float)Math.toRadians(77), (float)W / H, 1024.0f, 0.05f, true);
                Matrix4f view = new Matrix4f().rotationX((float)Math.toRadians(pose.pitch())).rotateY((float)Math.toRadians(pose.yaw() + 180));
                TerrainFrustum frustum = TerrainFrustum.of(projection, view);
                double eyeX = pose.x() + frustum.eyeX();
                double eyeY = pose.y() + frustum.eyeY();
                double eyeZ = pose.z() + frustum.eyeZ();
                List<DynamicGpuData.IndexedDraw> a = new ArrayList<>();
                List<DynamicGpuData.IndexedDraw> b = new ArrayList<>();
                List<DynamicGpuData.IndexedDraw> c = new ArrayList<>();
                for (int i = 0; i < sections.size(); i++) {
                    Section s = sections.get(i);
                    a.add(new DynamicGpuData.IndexedDraw(6 * s.quads, 1, 0, s.baseVertex, i));
                    int mask = s.info.visibleMask(eyeX - s.ox, eyeY - s.oy, eyeZ - s.oz, TerrainRuns.EPS);
                    TerrainRuns.emit(s.info, mask, 32, s.baseVertex, i, TerrainRuns.SHORT_INDEX_QUADS, 1, scratch, b);
                    int maskF2B = s.infoF2B.visibleMask(eyeX - s.ox, eyeY - s.oy, eyeZ - s.oz, TerrainRuns.EPS);
                    check(maskF2B == mask, pose.name() + ": S6 keeps the bucket planes");
                    TerrainRuns.emit(s.infoF2B, maskF2B, 32, s.baseVertex, i, TerrainRuns.SHORT_INDEX_QUADS, 1, scratch, c);
                }
                List<DynamicGpuData.IndexedDraw> d = new ArrayList<>(c);
                TerrainDrawList.sortBySectionDistance(d, infos, pose.x(), pose.y(), pose.z());
                List<DynamicGpuData.IndexedDraw> e = new ArrayList<>(d).reversed();
                check(!d.equals(c), pose.name() + ": the section sort changes the record order");
                for (int rgss = 0; rgss <= 1; rgss++) {
                    GpuTexture[] ref = this.renderTerrain(this.cutoutPipeline, this.cutoutAtlas, rgss, pose, projection, view, vertsA, indices32, IndexType.INT,
                        instances, a);
                    byte[] refColor = this.readback(ref[0]);
                    byte[] refDepth = this.readback(ref[1]);
                    int drawn = 0;
                    for (int i = 0; i < W * H; i++) {
                        int px = (refColor[4 * i] & 255) << 16 | (refColor[4 * i + 1] & 255) << 8 | refColor[4 * i + 2] & 255;
                        if (px != 0x1A334D) {
                            drawn++;
                        }
                    }
                    check(drawn > W * H / 10, pose.name() + ": reference image shows cutout terrain (" + drawn + " px)");
                    String[] names = {"B buckets (S1)", "C S6 front-to-back buckets", "D S6 + section order", "E S6, sections back-to-front"};
                    List<List<DynamicGpuData.IndexedDraw>> lists = List.of(b, c, d, e);
                    GpuBuffer[] heaps = {vertsB, vertsC, vertsC, vertsC};
                    StringBuilder diffs = new StringBuilder();
                    for (int k = 0; k < 4; k++) {
                        GpuTexture[] t = this.renderTerrain(this.cutoutPipeline, this.cutoutAtlas, rgss, pose, projection, view, heaps[k], indices16,
                            IndexType.SHORT, instances, lists.get(k));
                        byte[] color = this.readback(t[0]);
                        byte[] depth = this.readback(t[1]);
                        int diff = 0;
                        for (int i = 0; i < color.length; i += 4) {
                            for (int j = 0; j < 4; j++) {
                                if (color[i + j] != refColor[i + j] || depth[i + j] != refDepth[i + j]) {
                                    diff++;
                                    break;
                                }
                            }
                        }
                        check(diff == 0, pose.name() + (rgss == 1 ? " (RGSS)" : "") + ": cutout " + names[k] + " differs from vanilla in " + diff + " pixels");
                        diffs.append(' ').append(diff);
                    }
                    System.out.printf("  cutout %-30s %s %6d px drawn; %d quads, records %d / %d; diff px B..E:%s%n", pose.name(), rgss == 1 ? "rgss   " : "nearest",
                        drawn, totalQuads, a.size(), c.size(), diffs);
                }
            }
        }

        /** Canopy-like CUTOUT section: leaf cubes (all faces) at {@code density}, plants in some empty cells. */
        Section canopy(final Random rnd, final int ox, final int oy, final int oz, final double density) {
            List<float[]> quads = new ArrayList<>();
            List<Integer> colors = new ArrayList<>();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (rnd.nextDouble() < density) {
                            int base = 0x8090A0 + rnd.nextInt(0x3F3F3F);
                            for (Direction d : Direction.values()) {
                                quads.add(face(d, x, y, z));
                                colors.add((base + d.ordinal() * 0x050403) << 8 | 0xFF);
                            }
                        } else if (rnd.nextInt(12) == 0) {
                            // inside its own empty cell, lifted off the floor and below the ceiling: touches no other quad
                            float b0 = 0.01f;
                            float b1 = 0.99f;
                            quads.add(new float[]{x + 0.1464f, y + b0, z + 0.1464f, x + 0.1464f, y + b1, z + 0.1464f, x + 0.8536f, y + b1, z + 0.8536f,
                                x + 0.8536f, y + b0, z + 0.8536f});
                            colors.add(0xE0F0E0FF);
                            quads.add(new float[]{x + 0.8536f, y + b0, z + 0.1464f, x + 0.8536f, y + b1, z + 0.1464f, x + 0.1464f, y + b1, z + 0.8536f,
                                x + 0.1464f, y + b0, z + 0.8536f});
                            colors.add(0xF0E0F0FF);
                        }
                    }
                }
            }
            return new Section(ox, oy, oz, quads, colors, true);
        }

        GpuTexture[] renderTerrain(
            final CompiledRenderPipeline pipeline, final GpuTextureView sprite, final Pose pose, final Matrix4f projection, final Matrix4f view,
            final GpuBuffer vertices, final GpuBuffer indices, final IndexType indexType, final GpuBuffer instances,
            final List<DynamicGpuData.IndexedDraw> draws
        ) {
            return this.renderTerrain(pipeline, sprite, 0, pose, projection, view, vertices, indices, indexType, instances, draws);
        }

        GpuTexture[] renderTerrain(
            final CompiledRenderPipeline pipeline, final GpuTextureView sprite, final int rgss, final Pose pose, final Matrix4f projection,
            final Matrix4f view, final GpuBuffer vertices, final GpuBuffer indices, final IndexType indexType, final GpuBuffer instances,
            final List<DynamicGpuData.IndexedDraw> draws
        ) {
            GpuTexture color = this.own(this.device.createTexture((String)"terrain e2e color", RT_USAGE, GpuFormat.RGBA8_UNORM, W, H, 1, 1));
            GpuTexture depth = this.own(this.device.createTexture((String)"terrain e2e depth", RT_USAGE, GpuFormat.D32_FLOAT, W, H, 1, 1));
            int bx = (int)Math.floor(pose.x());
            int by = (int)Math.floor(pose.y());
            int bz = (int)Math.floor(pose.z());
            ByteBuffer globals = direct(48);
            globals.putInt(0, bx).putInt(4, by).putInt(8, bz).putFloat(12, 1.0f);
            globals.putFloat(16, (float)(bx - pose.x())).putFloat(20, (float)(by - pose.y())).putFloat(24, (float)(bz - pose.z())).putFloat(28, 0);
            globals.putFloat(32, W).putFloat(36, H).putInt(40, rgss).putInt(44, 0);
            ByteBuffer fog = direct(48);
            fog.putFloat(0, 1).putFloat(4, 0).putFloat(8, 1).putFloat(12, 1);
            fog.putFloat(16, 1e6f).putFloat(20, 2e6f).putFloat(24, 1e6f).putFloat(28, 2e6f).putFloat(32, 1e6f).putFloat(36, 1e6f);
            ByteBuffer terrain = direct(80);
            view.get(0, terrain);
            terrain.putInt(64, 16).putInt(68, 16);
            ByteBuffer proj = direct(64);
            projection.get(0, proj);
            ByteBuffer commands = direct(Math.max(20, 20 * draws.size()));
            for (DynamicGpuData.IndexedDraw draw : draws) {
                commands.putInt(draw.indexCount()).putInt(draw.instanceCount()).putInt(draw.firstIndex()).putInt(draw.baseVertex()).putInt(draw.baseInstance());
            }
            GpuBuffer indirect = this.own(this.device.createBuffer(() -> "terrain e2e records", GpuBuffer.USAGE_INDIRECT_PARAMETERS, commands.flip()));
            try (RenderPass p = this.enc.createRenderPass(() -> "terrain e2e", this.own(this.device.createTextureView(color)),
                Optional.of(new Vector4f(0.1f, 0.2f, 0.3f, 1.0f)), this.own(this.device.createTextureView(depth)), OptionalDouble.of(0.0))) {
                p.setPipeline(pipeline);
                p.setUniform("Projection", this.ubo(proj));
                p.setUniform("Globals", this.ubo(globals));
                p.setUniform("Fog", this.ubo(fog));
                p.setUniform("TerrainUniform", this.ubo(terrain));
                p.setUniform("Sampler0", sprite, this.nearest);
                p.setUniform("Sampler2", this.lightmap, this.linear);
                p.setVertexBuffer(0, vertices.slice());
                p.setVertexBuffer(1, instances.slice());
                p.setIndexBuffer(indices, indexType);
                if (!draws.isEmpty()) {
                    p.drawIndexedIndirect(indirect.slice(0, 20L * draws.size()), draws.size());
                }
            }
            return new GpuTexture[]{color, depth};
        }

        // ------------------------------------------------------------------------------------------------------------
        // S5: compact 16-byte terrain vertex (TerrainCompact) against the 28-byte BLOCK vertex

        /** One synthetic section for the compact test: 28-byte BLOCK vertices of exactly encodable boxes. */
        record CompactSection(int ox, int oy, int oz, ByteBuffer block, int quads) {
        }

        static final int ATLAS = 256;

        /**
         * Random boxes on a 1/16 grid (some on the 1/2048 grid, some reaching outside the section), every face with its
         * own sprite of a 256x256 atlas (sub-texel UVs on a 2^-15 grid, sprites up to the atlas edge u = 1), per-vertex
         * light in [0, 255] and colour; all exactly encodable.
         */
        static CompactSection compactSection(final Random rnd, final int ox, final int oy, final int oz, final int boxes) {
            return compactSection(rnd, ox, oy, oz, boxes, false);
        }

        /**
         * {@code lattice}: one box per 4-block cell of the section (at most 64), none overlapping, so no two faces are
         * coplanar and overlapping (z-fights would change with any sub-ulp vertex move and hide what the snap test checks).
         */
        static CompactSection compactSection(final Random rnd, final int ox, final int oy, final int oz, final int boxes, final boolean lattice) {
            List<float[]> verts = new ArrayList<>();
            for (int b = 0; b < boxes; b++) {
                float[] lo = new float[3];
                float[] hi = new float[3];
                for (int a = 0; a < 3; a++) {
                    if (lattice) {
                        lo[a] = 4 * (b >> 2 * a & 3) + rnd.nextInt(8) / 16.0f;
                        hi[a] = lo[a] + (8 + rnd.nextInt(40)) / 16.0f;
                        continue;
                    }
                    float base = rnd.nextInt(18) - 1;
                    float size = (1 + rnd.nextInt(16)) / 16.0f;
                    float off = rnd.nextInt(4) == 0 ? rnd.nextInt(2048) / 2048.0f : rnd.nextInt(16) / 16.0f;
                    lo[a] = base + off;
                    hi[a] = lo[a] + size;
                }
                for (Direction d : Direction.values()) {
                    FaceInfo info = FaceInfo.fromFacing(d);
                    int sprite = rnd.nextInt((ATLAS / 16) * (ATLAS / 16));
                    float u0 = (sprite % (ATLAS / 16)) * 16.0f / ATLAS;
                    float v0 = (sprite / (ATLAS / 16)) * 16.0f / ATLAS;
                    float su = (1 + rnd.nextInt(32 * 16)) / (32.0f * ATLAS);   // up to 16 texels, 1/32-texel steps
                    float sv = (1 + rnd.nextInt(32 * 16)) / (32.0f * ATLAS);
                    int color = 0xFF000000 | rnd.nextInt(0x1000000);
                    for (int v = 0; v < 4; v++) {
                        Vector3f p = info.getVertexInfo(v).select(new Vector3f(lo[0], lo[1], lo[2]), new Vector3f(hi[0], hi[1], hi[2]));
                        float u = (v == 0 || v == 1) ? u0 : Math.min(1.0f, u0 + su);
                        float vv = (v == 0 || v == 3) ? v0 : Math.min(1.0f, v0 + sv);
                        int light = rnd.nextInt(256) | rnd.nextInt(256) << 16;
                        verts.add(new float[]{p.x(), p.y(), p.z(), Float.intBitsToFloat(color), u, vv, Float.intBitsToFloat(light)});
                    }
                }
            }
            ByteBuffer block = ByteBuffer.allocateDirect(verts.size() * STRIDE).order(ByteOrder.nativeOrder());
            for (float[] v : verts) {
                block.putFloat(v[0]).putFloat(v[1]).putFloat(v[2]).putInt(Float.floatToRawIntBits(v[3]));
                block.putFloat(v[4]).putFloat(v[5]).putInt(Float.floatToRawIntBits(v[6]));
            }
            block.flip();
            return new CompactSection(ox, oy, oz, block, verts.size() / 4);
        }

        void compact() {
            List<RenderPipeline> all = List.of(
                RenderPipelines.SOLID_TERRAIN_MULTIDRAW, RenderPipelines.SOLID_TERRAIN_COMPACT_MULTIDRAW,
                RenderPipelines.CUTOUT_TERRAIN_MULTIDRAW, RenderPipelines.CUTOUT_TERRAIN_COMPACT_MULTIDRAW,
                RenderPipelines.SOLID_TERRAIN, RenderPipelines.SOLID_TERRAIN_COMPACT);
            ShaderSource shaders = jarShaderSource();
            List<CompiledRenderPipeline> compiled = all.stream().map(p -> this.device.compilePipeline(p, shaders, this.workers)).toList().stream()
                .map(f -> f.join().finishCompile()).toList();
            check(!compiled.contains(null), "compact pipelines compile");
            compiled.forEach(this.owned::add);
            check(RenderPipelines.compactTerrainVariant(RenderPipelines.SOLID_TERRAIN_MULTIDRAW) == RenderPipelines.SOLID_TERRAIN_COMPACT_MULTIDRAW
                && RenderPipelines.compactTerrainVariant(RenderPipelines.CUTOUT_TERRAIN) == RenderPipelines.CUTOUT_TERRAIN_COMPACT
                && RenderPipelines.compactTerrainVariant(RenderPipelines.TRANSLUCENT_TERRAIN_MULTIDRAW) == null, "compact variants");

            Random rnd = new Random(1234);
            // Atlas with random colours and alphas (cutout), mip-mapped like the block atlas; random lightmap.
            int mips = 5;
            GpuTexture atlasTex = this.own(this.device.createTexture((String)"compact e2e atlas", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, ATLAS, ATLAS, 1, mips));
            for (int level = 0; level < mips; level++) {
                int size = ATLAS >> level;
                ByteBuffer texels = direct(size * size * 4);
                for (int i = 0; i < size * size; i++) {
                    texels.putInt(rnd.nextInt());
                }
                this.enc.writeToTexture(atlasTex, texels.flip(), level, 0, 0, 0, size, size);
            }
            GpuTextureView atlasView = this.own(this.device.createTextureView(atlasTex));
            GpuTexture lightTex = this.own(this.device.createTexture((String)"compact e2e lightmap", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, 16, 16, 1, 1));
            ByteBuffer light = direct(16 * 16 * 4);
            for (int i = 0; i < 256; i++) {
                light.putInt(rnd.nextInt() | 0xFF000000);
            }
            this.enc.writeToTexture(lightTex, light.flip(), 0, 0, 0, 0, 16, 16);
            GpuTextureView lightView = this.own(this.device.createTextureView(lightTex));
            GpuSampler mipNearest = this.own(this.device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, 1,
                OptionalDouble.of(mips - 1)));

            // Sections around two anchors: near the origin and far out (large CameraBlockPos, small offsets).
            List<CompactSection> sections = new ArrayList<>();
            int[][] anchors = {{0, 64, 0}, {29_984, -48, -30_000}};
            for (int[] a : anchors) {
                for (int i = 0; i < 4; i++) {
                    sections.add(compactSection(rnd, a[0] + 16 * (i % 2), a[1] + 16 * (i / 2 % 2), a[2] + 16 * (i / 2), 400));
                }
                sections.add(compactSection(rnd, a[0] + 160, a[1], a[2] - 96, 400));   // ~10 sections away
            }

            int totalQuads = 0;
            int maxQuads = 0;
            for (CompactSection s : sections) {
                totalQuads += s.quads();
                maxQuads = Math.max(maxQuads, s.quads());
            }
            ByteBuffer heapBlock = direct(totalQuads * 4 * STRIDE);
            ByteBuffer heapCompact = direct(totalQuads * 4 * TerrainCompact.STRIDE);
            int[] baseVertex = new int[sections.size()];
            int vertices = 0;
            boolean allExact = true;
            for (int i = 0; i < sections.size(); i++) {
                CompactSection s = sections.get(i);
                baseVertex[i] = vertices;
                vertices += 4 * s.quads();
                heapBlock.put(s.block().duplicate());
                ByteBuffer copy = ByteBuffer.allocateDirect(s.block().remaining()).order(ByteOrder.nativeOrder());
                copy.put(s.block().duplicate()).flip();
                allExact &= TerrainCompact.check(copy, 0, 4 * s.quads()) == TerrainCompact.EXACT;
                heapCompact.put(TerrainCompact.transcodeInPlace(copy, 0, 4 * s.quads()));
            }
            check(allExact, "compact e2e geometry is exactly encodable");
            GpuBuffer vertsBlock = this.own(this.device.createBuffer(() -> "compact e2e block heap", GpuBuffer.USAGE_VERTEX, heapBlock.flip()));
            GpuBuffer vertsCompact = this.own(this.device.createBuffer(() -> "compact e2e compact heap", GpuBuffer.USAGE_VERTEX, heapCompact.flip()));
            ByteBuffer inst = direct(16 * sections.size());
            for (CompactSection s : sections) {
                inst.putInt(s.ox()).putInt(s.oy()).putInt(s.oz()).putFloat(1.0f);
            }
            GpuBuffer instances = this.own(this.device.createBuffer(() -> "compact e2e chunk data", GpuBuffer.USAGE_VERTEX, inst.flip()));
            GpuBuffer indices = this.quadIndexBuffer(maxQuads, false);
            List<DynamicGpuData.IndexedDraw> draws = new ArrayList<>();
            for (int i = 0; i < sections.size(); i++) {
                draws.add(new DynamicGpuData.IndexedDraw(6 * sections.get(i).quads(), 1, 0, baseVertex[i], i));
            }

            int poses = 0;
            int identical = 0;
            for (int k = 0; k < 48; k++) {
                int[] a = anchors[k % 2];
                double x = a[0] + 16 + (rnd.nextDouble() - 0.5) * 44;
                double y = a[1] + 16 + (rnd.nextDouble() - 0.5) * 40;
                double z = a[2] + 16 + (rnd.nextDouble() - 0.5) * 44;
                // aim roughly at the anchor's sections
                double dx = a[0] + 16 - x;
                double dy = a[1] + 16 - y;
                double dz = a[2] + 16 - z;
                float yaw = (float)Math.toDegrees(Math.atan2(-dx, dz)) + (rnd.nextFloat() - 0.5f) * 40;
                float pitch = (float)Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))) + (rnd.nextFloat() - 0.5f) * 30;
                boolean rgss = k % 3 == 2;
                Pose pose = new Pose("compact pose " + k, x, y, z, yaw, pitch, new Matrix4f());
                for (int c = 0; c < 3; c++) {
                    CompiledRenderPipeline ref = compiled.get(2 * c);
                    CompiledRenderPipeline cmp = compiled.get(2 * c + 1);
                    boolean multidraw = c < 2;
                    GpuTexture[] a1 = this.renderCompactCase(pose, rgss, ref, vertsBlock, instances, indices, draws, sections, multidraw, atlasView, lightView, mipNearest);
                    GpuTexture[] b1 = this.renderCompactCase(pose, rgss, cmp, vertsCompact, instances, indices, draws, sections, multidraw, atlasView, lightView, mipNearest);
                    byte[] colorA = this.readback(a1[0]);
                    byte[] depthA = this.readback(a1[1]);
                    byte[] colorB = this.readback(b1[0]);
                    byte[] depthB = this.readback(b1[1]);
                    int diff = 0;
                    int drawn = 0;
                    for (int i = 0; i < colorA.length; i += 4) {
                        boolean same = true;
                        for (int j = 0; j < 4; j++) {
                            same &= colorA[i + j] == colorB[i + j] && depthA[i + j] == depthB[i + j];
                        }
                        if (!same) {
                            diff++;
                        }
                        if (depthA[i] != 0 || depthA[i + 1] != 0 || depthA[i + 2] != 0 || depthA[i + 3] != 0) {
                            drawn++;
                        }
                    }
                    String what = pose.name() + (rgss ? " (RGSS)" : "") + " " + all.get(2 * c + 1).getLocation().getPath();
                    check(diff == 0, what + ": compact differs from BLOCK in " + diff + " pixels");
                    check(drawn > W * H / 100, what + ": terrain visible (" + drawn + " px)");
                    poses++;
                    if (diff == 0) {
                        identical++;
                    }
                    if (k < 3 || diff != 0) {
                        System.out.printf("  %-60s %6d px drawn, %d px differ%n", what, drawn, diff);
                    }
                }
            }
            System.out.printf("  compact vertex: %d of %d renders bit-identical to BLOCK (%d sections, %d quads, %d -> %d bytes)%n", identical, poses,
                sections.size(), totalQuads, totalQuads * 4 * STRIDE, totalQuads * 4 * TerrainCompact.STRIDE);

            // Snap mode (-Dmc.terrain.compactSnap): the same geometry with block-state rotation noise (positions one or two
            // ulps off the grid, as FaceBakery's float rotation matrices leave them) drawn as vanilla would (28-byte noisy
            // floats) and snapped to the grid in 16 bytes. Not bit-identical by design: the image may differ only where a
            // pixel centre lies within ~1e-6 block of an edge, i.e. in a handful of pixels. Non-overlapping lattice boxes.
            List<CompactSection> snapSections = new ArrayList<>();
            List<DynamicGpuData.IndexedDraw> snapDraws = new ArrayList<>();
            int snapQuads = 0;
            for (CompactSection s : sections) {
                CompactSection lattice = compactSection(rnd, s.ox(), s.oy(), s.oz(), 64, true);
                snapDraws.add(new DynamicGpuData.IndexedDraw(6 * lattice.quads(), 1, 0, 4 * snapQuads, snapSections.size()));
                snapSections.add(lattice);
                snapQuads += lattice.quads();
            }
            ByteBuffer heapNoisy = direct(snapQuads * 4 * STRIDE);
            ByteBuffer heapSnapped = direct(snapQuads * 4 * TerrainCompact.STRIDE);
            double[] snapError = new double[2];
            double maxSnap = 0.0;
            boolean allAccepted = true;
            for (CompactSection s : snapSections) {
                ByteBuffer noisy = ByteBuffer.allocateDirect(s.block().remaining()).order(ByteOrder.nativeOrder());
                noisy.put(s.block().duplicate()).flip();
                for (int at = 0; at < noisy.limit(); at += STRIDE) {
                    for (int a = 0; a < 3; a++) {
                        // Noise as a function of the exact value (equal model coordinates get equal noise, as in FaceBakery):
                        // independent noise would turn the random boxes' coplanar overlaps into new z-fights.
                        float p = noisy.getFloat(at + 4 * a);
                        int r = Math.floorMod(Integer.rotateLeft(Float.floatToRawIntBits(p) * 0x9E3779B9, 7) * 0x85EBCA6B, 5);
                        noisy.putFloat(at + 4 * a, r == 0 ? Math.nextUp(p) : r == 1 ? Math.nextDown(p) : r == 2 ? Math.nextUp(Math.nextUp(p)) : p);
                    }
                }
                heapNoisy.put(noisy.duplicate());
                allAccepted &= TerrainCompact.check(noisy, 0, 4 * s.quads(), TerrainCompact.SNAP_POSITION_TOLERANCE, TerrainCompact.SNAP_UV_TOLERANCE, snapError)
                    == TerrainCompact.EXACT;
                maxSnap = Math.max(maxSnap, snapError[0]);
                heapSnapped.put(TerrainCompact.transcodeInPlace(noisy, 0, 4 * s.quads(), TerrainCompact.SNAP_POSITION_TOLERANCE, TerrainCompact.SNAP_UV_TOLERANCE));
            }
            check(allAccepted && maxSnap > 0.0 && maxSnap <= TerrainCompact.SNAP_POSITION_TOLERANCE, "snap: noisy geometry accepted, max snap " + maxSnap);
            GpuBuffer vertsNoisy = this.own(this.device.createBuffer(() -> "compact e2e noisy heap", GpuBuffer.USAGE_VERTEX, heapNoisy.flip()));
            GpuBuffer vertsSnapped = this.own(this.device.createBuffer(() -> "compact e2e snapped heap", GpuBuffer.USAGE_VERTEX, heapSnapped.flip()));
            long snapDrawn = 0;
            long snapDiff = 0;
            int snapRenders = 0;
            int worstDiff = 0;
            long[] snapHistogram = new long[4];   // differing pixels by largest channel delta: 1, 2-4, 5-16, > 16
            for (int k = 0; k < 24; k++) {
                int[] a = anchors[k % 2];
                double x = a[0] + 16 + (rnd.nextDouble() - 0.5) * 44;
                double y = a[1] + 16 + (rnd.nextDouble() - 0.5) * 40;
                double z = a[2] + 16 + (rnd.nextDouble() - 0.5) * 44;
                double dx = a[0] + 16 - x;
                double dy = a[1] + 16 - y;
                double dz = a[2] + 16 - z;
                float yaw = (float)Math.toDegrees(Math.atan2(-dx, dz)) + (rnd.nextFloat() - 0.5f) * 40;
                float pitch = (float)Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))) + (rnd.nextFloat() - 0.5f) * 30;
                boolean rgss = k % 3 == 2;
                Pose pose = new Pose("snap pose " + k, x, y, z, yaw, pitch, new Matrix4f());
                for (int c = 0; c < 2; c++) {
                    GpuTexture[] a1 = this.renderCompactCase(pose, rgss, compiled.get(2 * c), vertsNoisy, instances, indices, snapDraws, snapSections, true, atlasView, lightView, mipNearest);
                    GpuTexture[] b1 = this.renderCompactCase(pose, rgss, compiled.get(2 * c + 1), vertsSnapped, instances, indices, snapDraws, snapSections, true, atlasView, lightView, mipNearest);
                    byte[] colorA = this.readback(a1[0]);
                    byte[] depthA = this.readback(a1[1]);
                    byte[] colorB = this.readback(b1[0]);
                    int diff = 0;
                    int drawn = 0;
                    for (int i = 0; i < colorA.length; i += 4) {
                        int delta = 0;
                        for (int j = 0; j < 4; j++) {
                            delta = Math.max(delta, Math.abs((colorA[i + j] & 255) - (colorB[i + j] & 255)));
                        }
                        if (delta > 0) {
                            snapHistogram[delta <= 1 ? 0 : delta <= 4 ? 1 : delta <= 16 ? 2 : 3]++;
                        }
                        if (delta > 4) {
                            diff++;
                        }
                        if (depthA[i] != 0 || depthA[i + 1] != 0 || depthA[i + 2] != 0 || depthA[i + 3] != 0) {
                            drawn++;
                        }
                    }
                    snapDrawn += drawn;
                    snapDiff += diff;
                    snapRenders++;
                    worstDiff = Math.max(worstDiff, diff);
                }
            }
            // A pixel can only change visibly where its centre lies within the snap distance of a triangle edge: far below
            // 1 in 10^4 (measured: 28 of 1.33M). Interpolated colours may round 1 LSB differently (measured: ~500 of 1.33M).
            check(snapDiff * 10_000L <= snapDrawn, "snap: " + snapDiff + " of " + snapDrawn + " drawn pixels differ by more than 4/255 (limit 1e-4)");
            System.out.printf("  compact snap: %d renders, %d of %d drawn pixels differ by more than 4/255 from the noisy 28-byte floats (worst render %d px); differing pixels by largest channel delta 1: %d, 2-4: %d, 5-16: %d, >16: %d; max snap %.3g block%n",
                snapRenders, snapDiff, snapDrawn, worstDiff, snapHistogram[0], snapHistogram[1], snapHistogram[2], snapHistogram[3], maxSnap);
        }

        GpuTexture[] renderCompactCase(
            final Pose pose, final boolean rgss, final CompiledRenderPipeline pipeline, final GpuBuffer vertices, final GpuBuffer instances,
            final GpuBuffer indices, final List<DynamicGpuData.IndexedDraw> draws, final List<CompactSection> sections, final boolean multidraw,
            final GpuTextureView atlasView, final GpuTextureView lightView, final GpuSampler atlasSampler
        ) {
            GpuTexture color = this.own(this.device.createTexture((String)"compact e2e color", RT_USAGE, GpuFormat.RGBA8_UNORM, W, H, 1, 1));
            GpuTexture depth = this.own(this.device.createTexture((String)"compact e2e depth", RT_USAGE, GpuFormat.D32_FLOAT, W, H, 1, 1));
            Matrix4f projection = new Matrix4f().setPerspective((float)Math.toRadians(77), (float)W / H, 1024.0f, 0.05f, true);
            Matrix4f view = new Matrix4f().rotationX((float)Math.toRadians(pose.pitch())).rotateY((float)Math.toRadians(pose.yaw() + 180));
            int bx = (int)Math.floor(pose.x());
            int by = (int)Math.floor(pose.y());
            int bz = (int)Math.floor(pose.z());
            ByteBuffer globals = direct(48);
            globals.putInt(0, bx).putInt(4, by).putInt(8, bz).putFloat(12, 1.0f);
            globals.putFloat(16, (float)(bx - pose.x())).putFloat(20, (float)(by - pose.y())).putFloat(24, (float)(bz - pose.z())).putFloat(28, 0);
            globals.putFloat(32, W).putFloat(36, H).putInt(40, 0).putInt(44, rgss ? 1 : 0);
            ByteBuffer fog = direct(48);
            fog.putFloat(0, 0.6f).putFloat(4, 0.7f).putFloat(8, 0.9f).putFloat(12, 1);
            fog.putFloat(16, 5f).putFloat(20, 90f).putFloat(24, 20f).putFloat(28, 70f).putFloat(32, 100f).putFloat(36, 100f);
            ByteBuffer terrain = direct(80);
            view.get(0, terrain);
            terrain.putInt(64, ATLAS).putInt(68, ATLAS);
            ByteBuffer proj = direct(64);
            projection.get(0, proj);
            ByteBuffer commands = direct(Math.max(20, 20 * draws.size()));
            for (DynamicGpuData.IndexedDraw draw : draws) {
                commands.putInt(draw.indexCount()).putInt(draw.instanceCount()).putInt(draw.firstIndex()).putInt(draw.baseVertex()).putInt(draw.baseInstance());
            }
            GpuBuffer indirect = this.own(this.device.createBuffer(() -> "compact e2e records", GpuBuffer.USAGE_INDIRECT_PARAMETERS, commands.flip()));
            try (RenderPass p = this.enc.createRenderPass(() -> "compact e2e", this.own(this.device.createTextureView(color)),
                Optional.of(new Vector4f(0.1f, 0.2f, 0.3f, 1.0f)), this.own(this.device.createTextureView(depth)), OptionalDouble.of(0.0))) {
                p.setPipeline(pipeline);
                p.setUniform("Projection", this.ubo(proj));
                p.setUniform("Globals", this.ubo(globals));
                p.setUniform("Fog", this.ubo(fog));
                p.setUniform("TerrainUniform", this.ubo(terrain));
                p.setUniform("Sampler0", atlasView, atlasSampler);
                p.setUniform("Sampler2", lightView, this.linear);
                p.setVertexBuffer(0, vertices.slice());
                p.setIndexBuffer(indices, IndexType.INT);
                if (multidraw) {
                    p.setVertexBuffer(1, instances.slice());
                    p.drawIndexedIndirect(indirect.slice(0, 20L * draws.size()), draws.size());
                } else {
                    for (int i = 0; i < draws.size(); i++) {
                        CompactSection s = sections.get(i);
                        ByteBuffer info = direct(16);
                        info.putInt(0, s.ox()).putInt(4, s.oy()).putInt(8, s.oz()).putFloat(12, 1.0f);
                        p.setUniform("ChunkSection", this.ubo(info));
                        DynamicGpuData.IndexedDraw d = draws.get(i);
                        p.drawIndexed(d.indexCount(), 1, d.firstIndex(), d.baseVertex(), 0);
                    }
                }
            }
            return new GpuTexture[]{color, depth};
        }

        // ------------------------------------------------------------------------------------------------------------
        // clouds

        void clouds() {
            Random rnd = new Random(77);
            int radius = 20;
            boolean[][] cell = new boolean[64][64];
            for (int i = 0; i < 64; i++) {
                for (int j = 0; j < 64; j++) {
                    cell[i][j] = rnd.nextInt(5) < 2;
                }
            }
            int[] relHeights = {60, -70, 1};   // below, above, inside the cloud layer
            for (int h : relHeights) {
                ByteBuffer faces = ByteBuffer.allocate(3 * 12 * (2 * radius + 1) * (2 * radius + 1));
                // CloudRenderer.buildMesh: rings of increasing distance, extruded cells (vanilla face rules)
                for (int ring = 0; ring <= 2 * radius; ring++) {
                    for (int x = -ring; x <= ring; x++) {
                        int z = ring - Math.abs(x);
                        if (z >= 0 && z <= radius && x * x + z * z <= radius * radius) {
                            if (z != 0) {
                                this.cloudCell(faces, cell, x, -z, h);
                            }
                            this.cloudCell(faces, cell, x, z, h);
                        }
                    }
                }
                int count = faces.position() / 3;
                byte[] all = new byte[faces.position()];
                faces.flip().get(all);
                float relBottom = h + 0.37f;
                double ox = -5.25;
                double oz = -7.75;
                for (int k = 0; k < 6; k++) {
                    float yaw = rnd.nextFloat() * 360;
                    float pitch = (h > 0 ? -45 : 30) + rnd.nextFloat() * 30;
                    Matrix4f projection = new Matrix4f().setPerspective((float)Math.toRadians(77), (float)W / H, 1024.0f, 0.05f, true);
                    CloudFaceFilter filter = new CloudFaceFilter();
                    filter.reset(TerrainFrustum.of(projection, view(yaw, pitch)), ox, relBottom, oz);
                    ByteBuffer kept = ByteBuffer.allocate(all.length);
                    int keptCount = filter.filter(all, count, kept);
                    byte[] subset = new byte[kept.position()];
                    kept.flip().get(subset);
                    // a few frames the reference covers: turned a little, cloud offset drifted, view bob
                    for (int f = 0; f < 3; f++) {
                        float fyaw = yaw + (rnd.nextFloat() - 0.5f) * 12;
                        float fpitch = pitch + (rnd.nextFloat() - 0.5f) * 12;
                        float dx = (rnd.nextFloat() - 0.5f) * 6;
                        float dy = (rnd.nextFloat() - 0.5f) * 6;
                        Matrix4f fp = new Matrix4f(projection).translate(0.04f, -0.08f, 0).rotateZ((float)Math.toRadians(2 * f - 2));
                        TerrainFrustum ff = TerrainFrustum.of(fp, view(fyaw, fpitch));
                        if (!filter.covers(ff, ox + dx, relBottom + dy, oz)) {
                            continue;
                        }
                        float[] offset = {(float)(ox + dx), relBottom + dy, (float)oz};
                        byte[] full = this.renderClouds(fp, view(fyaw, fpitch), offset, all, count);
                        byte[] part = this.renderClouds(fp, view(fyaw, fpitch), offset, subset, keptCount);
                        int diff = 0;
                        int drawn = 0;
                        for (int i = 0; i < full.length; i += 4) {
                            if (full[i] != part[i] || full[i + 1] != part[i + 1] || full[i + 2] != part[i + 2] || full[i + 3] != part[i + 3]) {
                                diff++;
                            }
                            if (full[i + 3] != 0) {
                                drawn++;
                            }
                        }
                        check(diff == 0, "clouds (height " + h + ", view " + k + ", frame " + f + "): filtered differs in " + diff + " pixels");
                        check(drawn > 0 || h == 1, "clouds visible (height " + h + ")");
                        System.out.printf("  clouds height %4d view %d frame %d: %5d of %5d faces kept, %6d px drawn, %d px differ%n", h, k, f, keptCount, count,
                            drawn, diff);
                    }
                }
            }
        }

        void cloudCell(final ByteBuffer faces, final boolean[][] cells, final int x, final int z, final int relBottom) {
            if (!cells[Math.floorMod(x, 64)][Math.floorMod(z, 64)]) {
                return;
            }
            boolean below = relBottom > 0;
            boolean above = relBottom + 4 < 0;
            if (!below) {
                encodeFace(faces, x, z, 1, 0);
            }
            if (!above) {
                encodeFace(faces, x, z, 0, 0);
            }
            if (!cells[Math.floorMod(x, 64)][Math.floorMod(z - 1, 64)] && z > 0) {
                encodeFace(faces, x, z, 2, 0);
            }
            if (!cells[Math.floorMod(x, 64)][Math.floorMod(z + 1, 64)] && z < 0) {
                encodeFace(faces, x, z, 3, 0);
            }
            if (!cells[Math.floorMod(x - 1, 64)][Math.floorMod(z, 64)] && x > 0) {
                encodeFace(faces, x, z, 4, 0);
            }
            if (!cells[Math.floorMod(x + 1, 64)][Math.floorMod(z, 64)] && x < 0) {
                encodeFace(faces, x, z, 5, 0);
            }
            if (Math.abs(x) <= 1 && Math.abs(z) <= 1) {
                for (int d = 0; d < 6; d++) {
                    encodeFace(faces, x, z, d, 16);
                }
            }
        }

        static void encodeFace(final ByteBuffer b, final int x, final int z, final int dir, final int flags) {
            int dirAndFlags = dir | flags | (x & 1) << 7 | (z & 1) << 6;
            b.put((byte)(x >> 1)).put((byte)(z >> 1)).put((byte)dirAndFlags);
        }

        byte[] renderClouds(final Matrix4f projection, final Matrix4f view, final float[] offset, final byte[] faces, final int count) {
            GpuTexture color = this.own(this.device.createTexture((String)"cloud e2e color", RT_USAGE, GpuFormat.RGBA8_UNORM, W, H, 1, 1));
            GpuTexture depth = this.own(this.device.createTexture((String)"cloud e2e depth", RT_USAGE, GpuFormat.D32_FLOAT, W, H, 1, 1));
            GpuBuffer info = this.own(this.device.createBuffer(() -> "cloud e2e info", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, 48));
            try (GpuBufferSlice.MappedView v = info.map(false, true)) {
                ByteBuffer b = v.data().order(ByteOrder.LITTLE_ENDIAN);
                b.putFloat(0, 0.9f).putFloat(4, 0.95f).putFloat(8, 1.0f).putFloat(12, 0.8f);
                b.putFloat(16, offset[0]).putFloat(20, offset[1]).putFloat(24, offset[2]);
                b.putFloat(32, 12.0f).putFloat(36, 4.0f).putFloat(40, 12.0f);
            }
            GpuBuffer faceBuffer = this.own(this.device.createBuffer(() -> "cloud e2e faces", GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_MAP_WRITE,
                Math.max(16, faces.length)));
            try (GpuBufferSlice.MappedView v = faceBuffer.map(false, true)) {
                v.data().put(faces, 0, 3 * count);
            }
            GpuBuffer indices = this.quadIndexBuffer(Math.max(1, count), false);
            ByteBuffer fog = direct(48);
            fog.putFloat(0, 1).putFloat(4, 0).putFloat(8, 1).putFloat(12, 1);
            fog.putFloat(16, 1e6f).putFloat(20, 2e6f).putFloat(24, 1e6f).putFloat(28, 2e6f).putFloat(32, 1e6f).putFloat(36, 1e6f);
            ByteBuffer transforms = direct(160);
            view.get(0, transforms);
            new Matrix4f().get(64, transforms);
            transforms.putFloat(128, 1).putFloat(132, 1).putFloat(136, 1).putFloat(140, 1);
            ByteBuffer proj = direct(64);
            projection.get(0, proj);
            try (RenderPass p = this.enc.createRenderPass(() -> "cloud e2e", this.own(this.device.createTextureView(color)),
                Optional.of(new Vector4f(0, 0, 0, 0)), this.own(this.device.createTextureView(depth)), OptionalDouble.of(0.0))) {
                p.setPipeline(this.cloudPipeline);
                p.setUniform("Projection", this.ubo(proj));
                p.setUniform("DynamicTransforms", this.ubo(transforms));
                p.setUniform("Fog", this.ubo(fog));
                p.setUniform("CloudInfo", info);
                p.setUniform("CloudFaces", faceBuffer);
                p.setIndexBuffer(indices, IndexType.INT);
                if (count > 0) {
                    p.drawIndexed(6 * count, 1, 0, 0, 0);
                }
            }
            return this.readback(color);
        }
    }

    static Matrix4f view(final float yawDeg, final float pitchDeg) {
        return new Matrix4f().rotationX((float)Math.toRadians(pitchDeg)).rotateY((float)Math.toRadians(yawDeg + 180));
    }

    static ByteBuffer direct(final int size) {
        return ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
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
        try (InputStream in = TerrainE2ETest.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(path, e);
        }
    }
}
