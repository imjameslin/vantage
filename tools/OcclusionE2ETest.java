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
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * End-to-end check of the decoder's terrain occlusion culling (MCMTL_OCCLUSION, docs/metal/CONTRACT.md 16.6) with the
 * real Java backend and vanilla pipelines compiled from the jar's shaders (SOLID / CUTOUT / TRANSLUCENT
 * *_TERRAIN_MULTIDRAW): a wall of dense sections in front of sections it hides, rendered for 180 frames (static, a slow
 * and a faster turn, static again) with indirect records like the game's, one FRAME_END submit per frame. Every frame's
 * colour and depth are hashed into the output file. tools/occlusion-e2e-test.sh runs it with MCMTL_OCCLUSION=0 and =1 and
 * requires identical hashes and, from the ON run's decoder statistics, culled sections.
 *
 * Usage: OcclusionE2ETest <hash output file>
 */
public final class OcclusionE2ETest {
    private static final int W = 640;
    private static final int H = 400;
    private static final int STRIDE = 28;

    public static void main(final String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        MetalBackend backend = new MetalBackend();
        backend.loadLibrary();
        GpuDevice device = backend.createDevice(new GpuDebugOptions(0, false, false, true));
        Field field = RenderSystem.class.getDeclaredField("DEVICE");
        field.setAccessible(true);
        field.set(null, device);
        int status = 0;
        try (PrintStream out = new PrintStream(Files.newOutputStream(Path.of(args[0])), true, StandardCharsets.UTF_8)) {
            Rig rig = new Rig(device);
            try {
                rig.run(out);
                List<String> messages = device.getLastDebugMessages();
                for (String m : messages) {
                    System.out.println("  device message: " + m);
                }
                if (!messages.isEmpty()) {
                    status = 1;
                }
            } finally {
                rig.close();
                device.close();
            }
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            status = 1;
        }
        System.out.println(status == 0 ? "OCCLUSION E2E FRAMES WRITTEN" : "OCCLUSION E2E FAILED");
        System.exit(status);
    }

    private static final class Rig implements AutoCloseable {
        final GpuDevice device;
        final CommandEncoder enc;
        final ExecutorService workers = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "occlusion-e2e-compile");
            t.setDaemon(true);
            return t;
        });
        final List<AutoCloseable> owned = new ArrayList<>();
        final CompiledRenderPipeline[] pipelines = new CompiledRenderPipeline[3];
        GpuSampler nearest;
        GpuSampler linear;
        GpuTextureView atlas;
        GpuTextureView lightmap;

        Rig(final GpuDevice device) {
            this.device = device;
            this.enc = device.createCommandEncoder();
            ShaderSource shaders = jarShaderSource();
            List<RenderPipeline> all = List.of(RenderPipelines.SOLID_TERRAIN_MULTIDRAW, RenderPipelines.CUTOUT_TERRAIN_MULTIDRAW,
                RenderPipelines.TRANSLUCENT_TERRAIN_MULTIDRAW);
            List<CompiledRenderPipeline> compiled = all.stream().map(p -> device.compilePipeline(p, shaders, this.workers)).toList().stream()
                .map(f -> f.join().finishCompile()).toList();
            if (compiled.contains(null)) {
                throw new IllegalStateException("pipelines did not compile");
            }
            for (int i = 0; i < 3; i++) {
                this.pipelines[i] = compiled.get(i);
                this.owned.add(compiled.get(i));
            }
            this.nearest = this.own(device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, 1,
                OptionalDouble.empty()));
            this.linear = this.own(device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, 1,
                OptionalDouble.empty()));
            // 16x16 atlas: left half opaque, right half with a checkerboard of transparent texels (cutout holes)
            GpuTexture atlasTex = this.own(device.createTexture((String)"occlusion e2e atlas", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, 16, 16, 1, 1));
            ByteBuffer texels = direct(16 * 16 * 4);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    boolean hole = x >= 8 && ((x + y) & 1) == 0;
                    int v = 160 + ((x * 7 + y * 13) & 63);
                    texels.put((byte)v).put((byte)v).put((byte)v).put((byte)(hole ? 0 : 255));
                }
            }
            this.enc.writeToTexture(atlasTex, texels.flip(), 0, 0, 0, 0, 16, 16);
            this.atlas = this.own(device.createTextureView(atlasTex));
            GpuTexture lightTex = this.own(device.createTexture((String)"occlusion e2e lightmap", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, 16, 16, 1, 1));
            ByteBuffer white = direct(16 * 16 * 4);
            for (int i = 0; i < 256; i++) {
                white.putInt(-1);
            }
            this.enc.writeToTexture(lightTex, white.flip(), 0, 0, 0, 0, 16, 16);
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

        /** One section: unit cubes (all six faces) where `fill` says so; layer 0 solid, 1 cutout (uv in the holed half), 2 translucent. */
        record Mesh(int ox, int oy, int oz, ByteBuffer[] layers, int[] quads) {
        }

        static void cube(final ByteBuffer b, final float x, final float y, final float z, final int rgba, final float u0) {
            // faces counter-clockwise from outside (vanilla FaceInfo winding)
            float[][][] faces = {
                {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}}, {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
                {{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}}, {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},
                {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}}, {{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}}};
            float[][] uv = {{0, 1}, {0, 0}, {1, 0}, {1, 1}};
            for (int f = 0; f < 6; f++) {
                for (int v = 0; v < 4; v++) {
                    float[] p = faces[f][v];
                    b.putFloat(x + p[0]).putFloat(y + p[1]).putFloat(z + p[2]);
                    b.put((byte)(rgba >>> 24)).put((byte)(rgba >>> 16)).put((byte)(rgba >>> 8)).put((byte)rgba);
                    b.putFloat((u0 + uv[v][0] * 8) / 16.0f).putFloat(uv[v][1]).putShort((short)240).putShort((short)240);
                }
            }
        }

        static Mesh section(final Random rnd, final int ox, final int oy, final int oz, final double density, final boolean shell) {
            List<float[]> solid = new ArrayList<>();
            List<float[]> cutout = new ArrayList<>();
            List<float[]> water = new ArrayList<>();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        boolean edge = x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15;
                        if (shell ? edge : rnd.nextDouble() < density) {
                            (rnd.nextInt(5) == 0 && !shell ? cutout : solid).add(new float[]{x, y, z, rnd.nextInt(0x1000000)});
                        } else if (!shell && y == 3 && rnd.nextInt(6) == 0) {
                            water.add(new float[]{x, y, z, 0});
                        }
                    }
                }
            }
            ByteBuffer[] layers = new ByteBuffer[3];
            int[] quads = new int[3];
            List<List<float[]>> all = List.of(solid, cutout, water);
            for (int l = 0; l < 3; l++) {
                List<float[]> cubes = all.get(l);
                quads[l] = cubes.size() * 6;
                layers[l] = ByteBuffer.allocateDirect(Math.max(1, quads[l]) * 4 * STRIDE).order(ByteOrder.nativeOrder());
                for (float[] c : cubes) {
                    int rgba = l == 2 ? 0x3050E0A0 : ((int)c[3] << 8 | 0xFF);
                    cube(layers[l], c[0], c[1], c[2], rgba, l == 1 ? 8 : 0);
                }
                layers[l].flip();
            }
            return new Mesh(ox, oy, oz, layers, quads);
        }

        void run(final PrintStream out) throws Exception {
            Random rnd = new Random(1234);
            List<Mesh> sections = new ArrayList<>();
            // A wall of hollow-shell sections (16x16 faces of cubes on every side: opaque from outside) at z in [16, 32)
            for (int x = -64; x <= 48; x += 16) {
                for (int y = 0; y <= 48; y += 16) {
                    sections.add(section(rnd, x, y, 16, 0, true));
                }
            }
            // Behind it: random sections, hidden from a camera in front of the wall
            for (int x = -32; x <= 16; x += 16) {
                for (int y = 0; y <= 32; y += 16) {
                    for (int z = 64; z <= 96; z += 32) {
                        sections.add(section(rnd, x, y, z, 0.12, false));
                    }
                }
            }
            // In front of the wall, visible: a few sparse sections
            sections.add(section(rnd, -16, 0, -32, 0.05, false));
            sections.add(section(rnd, 0, 16, -32, 0.05, false));
            int n = sections.size();
            int[][] base = new int[3][n];
            int[] total = new int[3];
            for (int l = 0; l < 3; l++) {
                for (int i = 0; i < n; i++) {
                    base[l][i] = total[l];
                    total[l] += sections.get(i).quads()[l];
                }
            }
            GpuBuffer[] heaps = new GpuBuffer[3];
            for (int l = 0; l < 3; l++) {
                ByteBuffer heap = direct(Math.max(1, total[l]) * 4 * STRIDE);
                for (Mesh m : sections) {
                    heap.put(m.layers()[l].duplicate());
                }
                final int layer = l;
                heaps[l] = this.own(this.device.createBuffer(() -> "occlusion e2e heap " + layer, GpuBuffer.USAGE_VERTEX, heap.position(0).limit(Math.max(1, total[l]) * 4 * STRIDE)));
            }
            ByteBuffer inst = direct(16 * n);
            for (Mesh m : sections) {
                inst.putInt(m.ox()).putInt(m.oy()).putInt(m.oz()).putFloat(1.0f);
            }
            GpuBuffer instances = this.own(this.device.createBuffer(() -> "occlusion e2e chunk data", GpuBuffer.USAGE_VERTEX, inst.flip()));
            ByteBuffer idx = direct(16383 * 12);
            for (int q = 0; q < 16383; q++) {
                int v = q * 4;
                for (int i : new int[]{v, v + 1, v + 2, v + 2, v + 3, v}) {
                    idx.putShort((short)i);
                }
            }
            GpuBuffer indices = this.own(this.device.createBuffer(() -> "occlusion e2e quad indices", GpuBuffer.USAGE_INDEX, idx.flip()));
            GpuTexture color = this.own(this.device.createTexture((String)"occlusion e2e color",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, W, H, 1, 1));
            GpuTexture depth = this.own(this.device.createTexture((String)"occlusion e2e depth",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.D32_FLOAT, W, H, 1, 1));
            GpuTextureView colorView = this.own(this.device.createTextureView(color));
            GpuTextureView depthView = this.own(this.device.createTextureView(depth));
            GpuBuffer readback = this.own(this.device.createBuffer(() -> "occlusion e2e readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, 2 * W * H * 4));
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] frame = new byte[2 * W * H * 4];

            double camX = 0.5;
            double camY = 30.2;
            double camZ = -6.5;
            float yaw = 0;
            for (int f = 0; f < 180; f++) {
                if (f >= 40 && f < 120) {
                    yaw += 0.25f;     // slow turn
                } else if (f >= 120 && f < 150) {
                    yaw -= 1.5f;      // faster turn back
                }
                Matrix4f projection = new Matrix4f().setPerspective((float)Math.toRadians(70), (float)W / H, 1024.0f, 0.05f, true);
                Matrix4f view = new Matrix4f().rotationX((float)Math.toRadians(4)).rotateY((float)Math.toRadians(yaw + 180));
                // records: per layer, one per section (near to far for solid / cutout, far to near for translucent)
                ByteBuffer[] commands = new ByteBuffer[3];
                int[] counts = new int[3];
                for (int l = 0; l < 3; l++) {
                    commands[l] = direct(20 * n);
                    for (int k = 0; k < n; k++) {
                        int i = l == 2 ? n - 1 - k : k;
                        int quads = sections.get(i).quads()[l];
                        if (quads == 0) {
                            continue;
                        }
                        commands[l].putInt(6 * quads).putInt(1).putInt(0).putInt(4 * base[l][i]).putInt(i);
                        counts[l]++;
                    }
                    commands[l].flip();
                }
                int bx = (int)Math.floor(camX);
                int by = (int)Math.floor(camY);
                int bz = (int)Math.floor(camZ);
                ByteBuffer globals = direct(48);
                globals.putInt(0, bx).putInt(4, by).putInt(8, bz).putFloat(12, 1.0f);
                globals.putFloat(16, (float)(bx - camX)).putFloat(20, (float)(by - camY)).putFloat(24, (float)(bz - camZ)).putFloat(28, f);
                globals.putFloat(32, W).putFloat(36, H).putInt(40, 0).putInt(44, 0);
                ByteBuffer fog = direct(48);
                fog.putFloat(0, 0.6f).putFloat(4, 0.7f).putFloat(8, 0.9f).putFloat(12, 1);
                fog.putFloat(16, 1e6f).putFloat(20, 2e6f).putFloat(24, 150).putFloat(28, 170).putFloat(32, 1e6f).putFloat(36, 1e6f);
                ByteBuffer terrain = direct(80);
                view.get(0, terrain);
                terrain.putInt(64, 16).putInt(68, 16);
                ByteBuffer proj = direct(64);
                projection.get(0, proj);
                GpuBuffer[] indirect = new GpuBuffer[3];
                for (int l = 0; l < 3; l++) {
                    final int layer = l;
                    indirect[l] = counts[l] == 0 ? null : this.device.createBuffer(() -> "occlusion e2e records " + layer, GpuBuffer.USAGE_INDIRECT_PARAMETERS, commands[l]);
                }
                try (RenderPass p = this.enc.createRenderPass(() -> "occlusion e2e", colorView, Optional.of(new Vector4f(0.6f, 0.7f, 0.9f, 1.0f)), depthView,
                    OptionalDouble.of(0.0))) {
                    GpuBufferSlice projUbo = this.ubo(proj);
                    GpuBufferSlice globalsUbo = this.ubo(globals);
                    GpuBufferSlice fogUbo = this.ubo(fog);
                    GpuBufferSlice terrainUbo = this.ubo(terrain);
                    for (int l = 0; l < 3; l++) {
                        p.setPipeline(this.pipelines[l]);
                        p.setUniform("Projection", projUbo);
                        p.setUniform("Globals", globalsUbo);
                        p.setUniform("Fog", fogUbo);
                        p.setUniform("TerrainUniform", terrainUbo);
                        p.setUniform("Sampler0", this.atlas, this.nearest);
                        p.setUniform("Sampler2", this.lightmap, this.linear);
                        p.setVertexBuffer(0, heaps[l].slice());
                        p.setVertexBuffer(1, instances.slice());
                        p.setIndexBuffer(indices, IndexType.SHORT);
                        if (counts[l] > 0) {
                            p.drawIndexedIndirect(indirect[l].slice(0, 20L * counts[l]), counts[l]);
                        }
                    }
                }
                this.enc.copyTextureToBuffer(color, readback, 0, () -> {}, 0);
                this.enc.copyTextureToBuffer(depth, readback, (long)W * H * 4, () -> {}, 0);
                GpuFence fence = this.enc.createFence();
                this.enc.submit();
                if (!fence.awaitCompletion(5_000_000_000L)) {
                    throw new IllegalStateException("frame " + f + " did not complete");
                }
                fence.close();
                for (GpuBuffer b : indirect) {
                    if (b != null) {
                        b.close();
                    }
                }
                try (GpuBufferSlice.MappedView v = readback.slice(0, frame.length).map(true, false)) {
                    v.data().get(frame);
                }
                out.println(f + " " + HexFormat.of().formatHex(sha.digest(frame)));
            }
        }
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
        try (InputStream in = OcclusionE2ETest.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(path, e);
        }
    }
}
