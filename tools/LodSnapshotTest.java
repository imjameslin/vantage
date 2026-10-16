import bench.BenchAgent;
import bench.LodSnapshot;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.ZipFile;

/** Checks actual frozen bytecode hooks and staged-vs-mapped uniform sources without a game/GPU. */
public final class LodSnapshotTest {
    static int checks;
    static void check(boolean b, String why) { checks++; if (!b) throw new AssertionError(why); }
    static void rejects(Runnable r, String why) { boolean threw = false; try { r.run(); } catch (IllegalStateException expected) { threw = true; } check(threw, why); }
    public static final class Buffer implements GpuBuffer, AutoCloseable {
        final Arena arena = Arena.ofConfined();
        final MemorySegment memory;
        boolean closed;
        Buffer(int n) { memory = arena.allocate(n); }
        public long contents() { return memory.address(); }
        public long size() { return memory.byteSize(); }
        public int usage() { return USAGE_UNIFORM | USAGE_MAP_WRITE | USAGE_COPY_DST; }
        public boolean isClosed() { return closed; }
        public void close() { closed = true; arena.close(); }
        public GpuBufferSlice.MappedView map(long offset, long size, boolean read, boolean write) { throw new UnsupportedOperationException(); }
    }
    public static void main(String[] args) throws Exception {
        try (Buffer b = new Buffer(16)) {
            ByteBuffer data = ByteBuffer.wrap(new byte[]{99, 1, 2, 3, 4, 99}); data.position(1); data.limit(5);
            LodSnapshot.uniformWrite(b.slice(4, 4), data);
            check(data.position() == 1 && data.limit() == 5, "source cursor unchanged");
            check(Arrays.equals(LodSnapshot.shadowBytes(b.slice(4, 4)), new byte[]{1,2,3,4}), "staged bytes override old zero backing");
            LodSnapshot.uniformWrite(b.slice(5, 1), ByteBuffer.wrap(new byte[]{8}));
            check(Arrays.equals(LodSnapshot.shadowBytes(b.slice(4, 4)), new byte[]{1,8,3,4}), "overlapping later write wins");
            rejects(() -> LodSnapshot.shadowBytes(b.slice()), "partial unknown content is rejected");
            LodSnapshot.uniformMap(b, true);
            rejects(() -> LodSnapshot.shadowBytes(b.slice(4,4)), "mixed map/staged writers rejected");
        }
        try (Buffer b = new Buffer(16)) {
            b.memory.asByteBuffer().putInt(0, 0x12345678);
            LodSnapshot.uniformMap(b, true);
            check(Arrays.equals(LodSnapshot.shadowBytes(b.slice(0,4)), new byte[]{0x12,0x34,0x56,0x78}), "CPU-mapped bytes are live");
            LodSnapshot.uniformCopy(b.slice());
            rejects(() -> LodSnapshot.shadowBytes(b.slice()), "GPU copy cannot silently use stale CPU contents");
        }
        try (Buffer b = new Buffer(65537)) {
            LodSnapshot.uniformWrite(b.slice(0,4), ByteBuffer.wrap(new byte[4]));
            rejects(() -> LodSnapshot.shadowBytes(b.slice(0,4)), "unsupported large staged buffer rejected");
        }
        check(LodSnapshot.readbackUsage("Lightmap", 13) == 15, "lightmap readable");
        check(LodSnapshot.readbackUsage("Lightmap (ping-pong)", 13) == 15, "ping-pong lightmap readable");
        check(LodSnapshot.readbackUsage("Other", 13) == 13 && LodSnapshot.readbackUsage(null, 13) == 13, "other texture flags unchanged");
        var expected = Map.of(
            "net/minecraft/client/Minecraft", Map.of("afterFrame", 1),
            "net/minecraft/client/renderer/chunk/ChunkSectionsToRender", Map.of("terrain", 1),
            "com/mojang/renderpearl/backend/metal/MetalCommandEncoder", Map.of("uniformWrite", 1, "uniformCopy", 1),
            "com/mojang/renderpearl/backend/metal/MetalBuffer", Map.of("uniformMap", 1),
            "com/mojang/renderpearl/backend/metal/MetalTexture", Map.of("readbackUsage", 1));
        try (ZipFile jar = new ZipFile(args[0])) {
            for (var entry : expected.entrySet()) {
                byte[] original = jar.getInputStream(jar.getEntry(entry.getKey() + ".class")).readAllBytes();
                byte[] transformed = BenchAgent.snapshotHooks(original, LodSnapshotTest.class.getClassLoader(), entry.getKey());
                var errors = ClassFile.of().verify(transformed);
                check(errors.isEmpty(), entry.getKey() + " verifies: " + errors);
                Map<String,Integer> found = new java.util.HashMap<>();
                for (var m : ClassFile.of().parse(transformed).methods()) if (m.code().isPresent()) {
                    for (var e : m.code().get().elementList()) if (e instanceof InvokeInstruction i
                        && i.owner().asInternalName().equals("bench/LodSnapshot")) found.merge(i.name().stringValue(), 1, Integer::sum);
                }
                for (var e : entry.getValue().entrySet()) check(found.getOrDefault(e.getKey(),0) >= e.getValue(), "actual hook exists: " + entry.getKey() + " " + e.getKey());
                check(found.size() == entry.getValue().size(), "no unexpected hooks");
                rejects(() -> BenchAgent.snapshotHooks(original, LodSnapshotTest.class.getClassLoader(), "wrong/Class"), "unmatched owner rejected");
            }
        }
        var dir = java.nio.file.Files.createTempDirectory("lod-snapshot-existing-");
        var prior = dir.resolve("failure.json"); java.nio.file.Files.writeString(prior, "prior evidence");
        System.setProperty("bench.snapshot.dir", dir.toString());
        LodSnapshot.arm();
        check(LodSnapshot.finished() && !LodSnapshot.succeeded(), "existing directory rejected");
        check(java.nio.file.Files.readString(prior).equals("prior evidence"), "failed arm leaves existing evidence untouched");
        java.nio.file.Files.delete(prior); java.nio.file.Files.delete(dir);
        System.out.println("LodSnapshotTest: " + checks + " checks passed");
    }
}
