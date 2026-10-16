package bench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryUtil;

/**
 * Diagnostic only (bench.mode=lodsnapshot): lossless selected LOD payload, cluster lists,
 * actual bound uniforms and textures. No FPS sample includes these hooks/readbacks.
 * Metadata integers in LDS1/LDG1 are big endian; GPU byte payloads remain little endian.
 * No GPU call occurs in terrain(): afterFrame() runs with all render passes closed.
 */
public final class LodSnapshot {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final IdentityHashMap<GpuBuffer, Shadow> SHADOWS = new IdentityHashMap<>();
    private static boolean armed, captured, done, success, ownsDirectory;
    private static Object gpu;
    private static long capturedFrame, selection, deadline;
    private static Path directory;
    private static GpuTextureView atlas, lightmap;
    private static List<?> draws;
    private static final Map<String, Object> manifest = new LinkedHashMap<>();
    private static final Map<String, Object> files = new LinkedHashMap<>();
    private static final Map<String, byte[]> uniforms = new LinkedHashMap<>();
    private static String hookError;
    private static final long MAX_RAW = 8L << 30;
    private static long rawBytes;

    /** The lightmap is sampled already; COPY_SRC permits diagnostics without changing its Metal usage bits. */
    public static int readbackUsage(String label, int usage) {
        return label != null && label.startsWith("Lightmap") ? usage | GpuTexture.USAGE_COPY_SRC : usage;
    }

    private static final class Shadow {
        byte[] bytes;
        BitSet known;
        boolean mapped, wrote, copied;
    }

    private static synchronized Shadow shadow(GpuBuffer b) {
        if ((b.usage() & GpuBuffer.USAGE_UNIFORM) == 0) return null;
        Shadow s = SHADOWS.get(b);
        if (s == null) {
            SHADOWS.keySet().removeIf(GpuBuffer::isClosed);
            if (SHADOWS.size() >= 512) throw new IllegalStateException("Uniform tracking cap exceeded");
            s = new Shadow();
            SHADOWS.put(b, s);
        }
        return s;
    }

    /** CPU source of even a staged/renamed write; never change data.position(). */
    public static synchronized void uniformWrite(GpuBufferSlice dst, ByteBuffer data) {
        try {
            Shadow s = shadow(dst.buffer());
            if (s == null || !data.hasRemaining()) return;
            s.wrote = true;
            if (dst.buffer().size() > 65536) return; // A requested large staged uniform is rejected, never read stale.
            int n = data.remaining(), off = Math.toIntExact(dst.offset());
            if (n > dst.length() || off < 0 || n > dst.buffer().size() - off) throw new IllegalArgumentException("Uniform write range");
            if (s.bytes == null) { s.bytes = new byte[(int)dst.buffer().size()]; s.known = new BitSet(s.bytes.length); }
            data.duplicate().get(s.bytes, off, n);
            s.known.set(off, off + n);
        } catch (Throwable t) { hookError = t.toString(); }
    }

    public static synchronized void uniformMap(GpuBuffer b, boolean write) {
        if (!write) return;
        try { Shadow s = shadow(b); if (s != null) s.mapped = true; }
        catch (Throwable t) { hookError = t.toString(); }
    }

    public static synchronized void uniformCopy(GpuBufferSlice dst) {
        try { Shadow s = shadow(dst.buffer()); if (s != null) s.copied = true; }
        catch (Throwable t) { hookError = t.toString(); }
    }

    /** Strictly reject mixed CPU-map/staged/copy writers rather than guess their ordering. */
    public static synchronized byte[] shadowBytes(GpuBufferSlice slice) {
        if (slice == null || slice.length() <= 0 || slice.length() > 65536) throw new IllegalStateException("Invalid uniform slice");
        if (hookError != null) throw new IllegalStateException(hookError);
        Shadow s = SHADOWS.get(slice.buffer());
        if (s != null && (s.copied || s.wrote && s.mapped)) throw new IllegalStateException("Unsupported mixed uniform writes");
        int n = Math.toIntExact(slice.length()), off = Math.toIntExact(slice.offset());
        if (s != null && s.wrote) {
            if (s.bytes == null || s.known.nextClearBit(off) < off + n) throw new IllegalStateException("Incomplete staged uniform shadow");
            return java.util.Arrays.copyOfRange(s.bytes, off, off + n);
        }
        if ((slice.buffer().usage() & GpuBuffer.USAGE_MAP_WRITE) == 0) throw new IllegalStateException("Untracked non-mapped uniform");
        return memory(contents(slice.buffer()) + slice.offset(), n);
    }

    public static void arm() {
        try { armChecked(); } catch (Throwable t) { fail(t); }
    }

    private static void armChecked() throws Exception {
        if (armed || done) throw new IllegalStateException("Snapshot already requested");
        directory = Path.of(System.getProperty("bench.snapshot.dir")).toAbsolutePath();
        // Never overwrite an earlier evidence set, including an incomplete capture.
        Files.createDirectory(directory);
        ownsDirectory = true;
        deadline = System.nanoTime() + 120_000_000_000L;
        armed = true;
        manifest.put("schema", "farsight-scene-1");
        manifest.put("scope", "selected LOD source geometry, all layers; excludes near terrain/entities; final-frame color/depth are context, not isolated LOD references");
        manifest.put("timing", "capture only; no FPS measurement");
    }

    public static void terrain(Object sections, GpuTextureView atlasView, GpuTextureView lightmapView, GpuSampler sampler) {
        if (!armed || captured || done) return;
        try {
            Class<?> lod = Class.forName("net.minecraft.client.renderer.chunk.Lod");
            Object manager = field(lod, null, "manager");
            require(manager != null && LodHook.active() && LodHook.idle(), "LOD is not settled");
            gpu = field(manager, "gpu");
            require(gpu != null && !(boolean)field(gpu, "failed"), "LOD GPU unavailable");
            require(((Map<?, ?>)field(gpu, "fades")).isEmpty(), "LOD fades still active");
            require(!(boolean)field(gpu, "itemsDirty"), "LOD groups not prepared");
            capturedFrame = (long)field(gpu, "frame");
            selection = (long)field(gpu, "builtSelection");
            uniforms.put("projection.bin", shadowBytes(RenderSystem.getProjectionMatrixBuffer()));
            uniforms.put("globals.bin", shadowBytes(RenderSystem.getGlobalSettingsUniform().slice()));
            uniforms.put("fog.bin", shadowBytes(RenderSystem.getShaderFog()));
            uniforms.put("terrain.bin", shadowBytes((GpuBufferSlice)field(sections, "terrainTransformUBO")));
            uniforms.put("cull-view.bin", memory((long)field(gpu, "view"), 128));
            atlas = atlasView; lightmap = lightmapView;
            draws = List.copyOf((List<?>)field(sections, "lodDraws"));
            require(!draws.isEmpty(), "No LOD draws on captured terrain pass");
            manifest.put("atlasSampler", sampler(sampler));
            manifest.put("lightmapSampler", "CLAMP_TO_EDGE, LINEAR (ChunkSectionsToRender.renderLayers)");
            manifest.put("lod", JsonParser.parseString(LodHook.json()));
            manifest.put("config", JsonParser.parseString(ConfigBlock.json(Minecraft.getInstance(), 0)));
            manifest.put("resourcePacks", Minecraft.getInstance().getResourcePackRepository().getSelectedIds());
            manifest.put("frame", capturedFrame);
            manifest.put("selection", selection);
            manifest.put("cullBase", List.of(field(gpu, "c0x"), field(gpu, "c0y"), field(gpu, "c0z")));
            captured = true;
        } catch (Throwable t) { fail(t); }
    }

    public static void afterFrame() {
        if (!captured || done) return;
        try {
            require((long)field(gpu, "frame") == capturedFrame && (long)field(gpu, "builtSelection") == selection,
                "LOD changed between render and capture");
            for (var e : uniforms.entrySet()) bytes(e.getKey(), e.getValue());
            texture("atlas", atlas.texture(), atlas.baseMipLevel(), atlas.mipLevels());
            texture("lightmap", lightmap.texture(), lightmap.baseMipLevel(), lightmap.mipLevels());
            var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            texture("frame-color", target.getColorTexture(), 0, 1);
            texture("frame-depth", target.getDepthTexture(), 0, 1);
            visible();
            geometry();
            manifest.put("files", files);
            manifest.put("complete", true);
            Files.writeString(directory.resolve("complete.json"), JSON.toJson(manifest) + "\n");
            success = true; done = true;
            System.out.println("[bench] LOD snapshot complete: " + directory + " raw bytes=" + rawBytes);
        } catch (Throwable t) { fail(t); }
    }

    /** The texture fences above complete this frame's cull; preserve exactly what the raster draws consumed. */
    private static void visible() throws Exception {
        require((long)field(gpu, "frame") == capturedFrame, "Frame changed during readbacks");
        List<?> groups = ((List<?>)field(gpu, "groups")).stream().filter(g -> {
            try { return (int)field(g, "count") != 0; } catch (Exception e) { throw new IllegalStateException(e); }
        }).toList();
        require(groups.size() == draws.size(), "Draw/group count mismatch");
        long total = 0;
        try (Blob b = new Blob("visible.ldv.gz")) {
            b.out.writeInt(0x4c445631); b.out.writeInt(draws.size());
            for (int i = 0; i < draws.size(); i++) {
                Object draw = draws.get(i), group = groups.get(i);
                GpuBufferSlice args = (GpuBufferSlice)field(draw, "args"), quads = (GpuBufferSlice)field(draw, "quads"), items = (GpuBufferSlice)field(draw, "items");
                byte[] ab = memory(contents(args.buffer()) + args.offset(), 20);
                var a = ByteBuffer.wrap(ab).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                long indexCount = Integer.toUnsignedLong(a.getInt(0));
                require(indexCount % 6 == 0 && a.getInt(4) == 1 && a.getInt(8) == 0 && a.getInt(12) == 0 && a.getInt(16) == 0, "Unsupported indirect draw");
                int n = Math.toIntExact(indexCount / 6), used = 0;
                require(n <= (long)field(group, "quads") && 4L*n <= quads.length(), "Visible list outside allocation");
                long qp = contents(quads.buffer()) + quads.offset();
                int cap = (int)field(group, "count");
                BitSet live = new BitSet(cap);
                for (int j = 0; j < n; j++) {
                    int cluster = MemoryUtil.memGetInt(qp + j*4L) >>> 6;
                    require(cluster < cap, "Visible cluster outside group"); used = Math.max(used, cluster + 1); live.set(cluster);
                }
                require(8L*used <= items.length(), "Visible items outside allocation");
                int code = (int)field(draw, "layer");
                b.out.writeInt(i); b.out.writeInt(code); b.out.writeInt(n); b.out.writeInt(used); b.out.write(ab);
                b.memory(qp, 4L*n);
                // The GPU writes only live cluster entries. Do not serialize undefined holes in its allocation.
                long ip = contents(items.buffer()) + items.offset();
                ByteBuffer block = ByteBuffer.wrap(b.scratch).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                for (int j = 0; j < used;) {
                    checkTime(); int count = Math.min(b.scratch.length / 8, used - j); block.clear();
                    for (int k = 0; k < count; k++) block.putLong(live.get(j+k) ? MemoryUtil.memGetLong(ip + 8L*(j+k)) : 0);
                    b.out.write(b.scratch, 0, count*8); j += count;
                }
                total += n;
            }
        }
        manifest.put("visible", Map.of("draws", draws.size(), "quads", total, "file", "visible.ldv.gz",
            "scope", "Exact current-frame compacted draw lists after GPU visibility culling; unreferenced item holes are zeroed. Culling cost must still be measured."));
    }

    private static void geometry() throws Exception {
        List<?> arenas = (List<?>)field(gpu, "arenas");
        List<?> groups = (List<?>)field(gpu, "groups");
        TreeMap<Integer, Object> bySlot = new TreeMap<>();
        for (Object s : (List<?>)field(gpu, "resident")) bySlot.put((int)field(s, "slot"), s);
        BitSet needed = new BitSet();
        long quads = 0, clusters = 0;
        int activeGroups = 0;
        for (Object g : groups) {
            int count = (int)field(g, "count");
            if (count == 0) continue;
            int[] items = (int[])field(g, "items");
            require(count > 0 && count <= items.length / 4, "Group count out of range");
            activeGroups++;
            long groupQuads = 0;
            for (int i = 0; i < count; i++) {
                int slot = items[4 * i + 1] & 0xfffff;
                Object s = bySlot.get(slot);
                require(s != null, "Missing selected section");
                int layer = (int)field(g, "layer");
                int arena = ((int[])field(s, "arena"))[layer];
                require(arena >= 0 && arenas.get(arena) == field(g, "arena"), "Group arena mismatch");
                int first = ((int[])field(s, "firstQuad"))[layer], n = ((int[])field(s, "quads"))[layer];
                int q = (items[4 * i + 1] >>> 20 & 63) + 1, start = items[4 * i] / 4;
                require((items[4 * i] & 3) == 0 && start >= first && (long)start + q <= (long)first + n, "Cluster outside source section");
                groupQuads += q; needed.set(slot);
            }
            require(groupQuads == (long)field(g, "quads"), "Group quad count mismatch");
            quads += groupQuads; clusters += count;
        }
        require(quads == (long)field(gpu, "drawnQuads") && clusters == (int)field(gpu, "drawnClusters"), "Prepared draw counts differ");
        require(needed.cardinality() > 0 && needed.cardinality() <= (1 << 18), "Selected section cap");
        Map<?, ?> origins = (Map<?, ?>)field(gpu, "origins");
        long info = contents((GpuBuffer)field(gpu, "sectionInfos")), table = contents((GpuBuffer)field(gpu, "sectionTable"));
        long[] payloadQuads = new long[3], byLevel = new long[8];
        try (Blob b = new Blob("sections.lds.gz")) {
            b.out.writeInt(0x4c445331); b.out.writeInt(needed.cardinality());
            for (int slot = needed.nextSetBit(0); slot >= 0; slot = needed.nextSetBit(slot + 1)) {
                checkTime();
                Object s = bySlot.get(slot);
                int[] origin = (int[])origins.get(s);
                require(origin != null && origin.length == 4, "Missing section origin");
                b.out.writeInt(slot);
                for (int x : origin) b.out.writeInt(x);
                b.out.write(memory(info + slot * 16L, 16)); b.out.write(memory(table + slot * 32L, 32));
                int[] aa = (int[])field(s, "arena"), first = (int[])field(s, "firstQuad"), nn = (int[])field(s, "quads");
                for (int layer = 0; layer < 3; layer++) {
                    int stride = aa[layer] < 0 ? 0 : (int)field(arenas.get(aa[layer]), "quadBytes");
                    int n = aa[layer] < 0 ? 0 : nn[layer];
                    require(n >= 0 && (stride == 0 || stride == 48 || stride == 112), "Unsupported source stride/count");
                    long size = (long)n * stride;
                    b.out.writeInt(aa[layer]); b.out.writeInt(first[layer]); b.out.writeInt(n); b.out.writeInt(stride); b.out.writeLong(size);
                    if (aa[layer] >= 0) {
                        Object a = arenas.get(aa[layer]);
                        GpuBuffer buffer = (GpuBuffer)field(a, "buffer");
                        long offset = (long)first[layer] * stride;
                        require(offset >= 0 && size <= buffer.size() - offset, "Arena source range");
                        b.memory((long)field(a, "base") + offset, size);
                    }
                    payloadQuads[layer] += n; byLevel[Math.min(7, origin[3])] += n;
                }
            }
        }
        try (Blob b = new Blob("groups.ldg.gz")) {
            b.out.writeInt(0x4c444731); b.out.writeInt(activeGroups);
            for (Object g : groups) {
                int n = (int)field(g, "count"); if (n == 0) continue;
                Object a = field(g, "arena");
                b.out.writeInt((int)field(g, "layer")); b.out.writeInt((int)field(a, "index"));
                b.out.writeInt((int)field(a, "quadBytes")); b.out.writeInt((boolean)field(g, "fade") ? 1 : 0);
                b.out.writeInt((int)field(g, "level")); b.out.writeInt(n); b.out.writeLong((long)field(g, "quads"));
                int[] items = (int[])field(g, "items");
                for (int i = 0; i < 4 * n; i++) b.out.writeInt(items[i]);
            }
        }
        manifest.put("geometry", Map.of("selectedSections", needed.cardinality(), "groups", activeGroups,
            "submittedClusters", clusters, "submittedQuads", quads, "payloadQuadsByLayer", payloadQuads, "payloadQuadsByLevel", byLevel,
            "note", "All source layers in referenced sections; group records specify the submitted subset before GPU visibility culling."));
    }

    private static void texture(String label, GpuTexture t, int baseMip, int levels) throws Exception {
        require(t != null && t.getDepthOrLayers() == 1, "Unsupported texture " + label);
        List<Object> mips = new ArrayList<>();
        for (int mip = baseMip; mip < baseMip + levels; mip++) {
            checkTime();
            long size = (long)t.getWidth(mip) * t.getHeight(mip) * t.getFormat().blockSize();
            require(size > 0 && size <= (128L << 20), "Texture readback cap: " + label);
            var device = RenderSystem.getDevice();
            try (GpuBuffer readback = device.createBuffer(() -> "bench snapshot " + label, GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, size)) {
                var encoder = device.createCommandEncoder();
                encoder.copyTextureToBuffer(t, readback, 0, () -> {}, mip);
                try (var fence = encoder.createFence()) { require(fence.awaitCompletion(10_000_000_000L), "Texture fence timeout"); }
                String file = label + "-mip" + mip + ".bin.gz";
                try (var mapped = readback.map(true, false); Blob b = new Blob(file)) { b.buffer(mapped.data()); }
                mips.add(Map.of("level", mip, "width", t.getWidth(mip), "height", t.getHeight(mip), "file", file));
            }
        }
        manifest.put(label, Map.of("format", t.getFormat().toString(), "baseMip", baseMip, "mips", mips,
            "rowOrder", "native GPU rows, no image flip", "sourceLabel", t.getLabel()));
    }

    private static void bytes(String name, byte[] data) throws Exception {
        try (Blob b = new Blob(name + ".gz")) { b.out.write(data); }
    }

    /** Streaming compressed output: one MiB scratch, never a full arena-sized Java array. */
    private static final class Blob implements AutoCloseable {
        final String name;
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        final CountingStream counted;
        final DataOutputStream out;
        final byte[] scratch = new byte[1 << 20];
        Blob(String name) throws Exception {
            this.name = name;
            GZIPOutputStream gzip = new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(directory.resolve(name))), 1 << 20) {
                { def.setLevel(1); }
            };
            counted = new CountingStream(new DigestOutputStream(gzip, digest));
            out = new DataOutputStream(new BufferedOutputStream(counted, 1 << 20));
        }
        void memory(long address, long length) throws Exception {
            for (long at = 0; at < length;) {
                checkTime(); int n = (int)Math.min(scratch.length, length - at);
                MemoryUtil.memByteBuffer(address + at, n).get(scratch, 0, n);
                out.write(scratch, 0, n); at += n;
            }
        }
        void buffer(ByteBuffer buffer) throws Exception {
            ByteBuffer data = buffer.duplicate();
            while (data.hasRemaining()) { checkTime(); int n = Math.min(scratch.length, data.remaining()); data.get(scratch, 0, n); out.write(scratch, 0, n); }
        }
        @Override public void close() throws Exception {
            out.close();
            files.put(name, Map.of("rawBytes", counted.count, "gzipBytes", Files.size(directory.resolve(name)),
                "rawSha256", HexFormat.of().formatHex(digest.digest())));
        }
    }
    private static final class CountingStream extends OutputStream {
        final OutputStream delegate; long count;
        CountingStream(OutputStream delegate) { this.delegate = delegate; }
        @Override public void write(int x) throws java.io.IOException { write(new byte[]{(byte)x}, 0, 1); }
        @Override public void write(byte[] b, int off, int len) throws java.io.IOException {
            if (rawBytes > MAX_RAW - len) throw new java.io.IOException("Snapshot raw-byte cap");
            rawBytes += len; count += len; delegate.write(b, off, len);
        }
        @Override public void close() throws java.io.IOException { delegate.close(); }
    }

    private static Map<String, Object> sampler(GpuSampler s) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("addressU", s.getAddressModeU().toString()); r.put("addressV", s.getAddressModeV().toString());
        r.put("min", s.getMinFilter().toString()); r.put("mag", s.getMagFilter().toString());
        r.put("anisotropy", s.getMaxAnisotropy()); r.put("maxLod", s.getMaxLod().isPresent() ? s.getMaxLod().getAsDouble() : null);
        return r;
    }
    private static Object field(Object owner, String name) throws Exception { return field(owner.getClass(), owner, name); }
    private static Object field(Class<?> cls, Object owner, String name) throws Exception {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(cls.getName() + "." + name);
    }
    private static long contents(GpuBuffer b) {
        try { long p = (long)b.getClass().getMethod("contents").invoke(b); require(p != 0, "No shared CPU pointer"); return p; }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    private static byte[] memory(long p, int n) { require(p != 0 && n >= 0, "Invalid native range"); byte[] b = new byte[n]; MemoryUtil.memByteBuffer(p, n).get(b); return b; }
    private static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
    private static void checkTime() { require(System.nanoTime() < deadline, "Snapshot exceeded 120 seconds"); }
    private static void fail(Throwable t) {
        t.printStackTrace(); done = true; success = false;
        manifest.put("complete", false); manifest.put("failure", t.toString());
        try { if (ownsDirectory) Files.writeString(directory.resolve("failure.json"), JSON.toJson(manifest)); }
        catch (Exception ignored) { }
    }
    public static boolean finished() { return done; }
    public static boolean succeeded() { return success; }
    public static String resultJson() { return JSON.toJson(manifest); }
}
