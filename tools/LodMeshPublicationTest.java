package net.minecraft.client.renderer.chunk;

import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/** CPU-only calls to the actual manager publication method. No manager/GPU constructor is run.
 * This checks payload ownership and the known publish/evict marker race, not mesh-input or GPU correctness. */
public final class LodMeshPublicationTest {
    private static int checks;
    private static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static Object blank(Class<?> type) throws Exception {
        Class<?> u = Class.forName("sun.misc.Unsafe");
        return u.getMethod("allocateInstance", Class.class).invoke(field(u, "theUnsafe").get(null), type);
    }
    private static void set(Object owner, String name, Object value) throws Exception {
        field(owner.getClass(), name).set(owner, value);
    }
    private static final String NODE = "net/minecraft/client/renderer/chunk/LodManager$Node";
    private static boolean markerWrite(CodeElement e) {
        return e instanceof FieldInstruction f && f.opcode() == Opcode.PUTFIELD
            && f.owner().asInternalName().equals(NODE) && f.name().equalsString("meshStamp");
    }
    private static int nextInstruction(List<CodeElement> code, int after) {
        for (int i = after + 1; i < code.size(); i++) if (code.get(i) instanceof Instruction) return i;
        throw new AssertionError("instruction missing");
    }
    /** A later render-thread eviction must not be overwritten by successful producer completion.
     * Follow only the true return from offer; failure/exception paths may reset the marker. */
    private static void markerOrder(Path managerClass) throws Exception {
        var cls = ClassFile.of().parse(managerClass);
        var method = cls.methods().stream().filter(m -> m.methodName().equalsString("publishMesh")).findFirst().orElseThrow();
        var code = method.code().orElseThrow().elementList();
        Map<Label, Integer> labels = new IdentityHashMap<>();
        int offer = -1, marker = -1, markerCount = 0;
        for (int i = 0; i < code.size(); i++) {
            var e = code.get(i);
            if (e instanceof LabelTarget t) labels.put(t.label(), i);
            if (offer < 0 && markerWrite(e)) { marker = i; markerCount++; }
            if (e instanceof InvokeInstruction call && call.owner().asInternalName().endsWith("/LodMeshHandoff")
                && call.name().equalsString("offer")) {
                check(offer < 0, "one packet publication"); offer = i;
            }
        }
        check(offer >= 0 && markerCount == 1 && marker < offer,
            "scheduling marker written before result becomes claimable");
        int i = nextInstruction(code, offer);
        check(code.get(i) instanceof BranchInstruction, "offer result controls rejection branch");
        var resultBranch = (BranchInstruction)code.get(i);
        check(resultBranch.opcode() == Opcode.IFNE || resultBranch.opcode() == Opcode.IFEQ, "boolean offer branch");
        i = resultBranch.opcode() == Opcode.IFNE ? labels.get(resultBranch.target()) : i + 1;
        Set<Integer> visited = new HashSet<>();
        for (; i < code.size(); i++) {
            check(visited.add(i), "success path terminates");
            var e = code.get(i);
            check(!markerWrite(e), "successful publication never overwrites a later eviction marker");
            if (e instanceof ReturnInstruction) return;
            if (e instanceof BranchInstruction b) {
                check(b.opcode() == Opcode.GOTO || b.opcode() == Opcode.GOTO_W, "no unexamined success branch");
                i = labels.get(b.target()) - 1;
            } else if (e instanceof InvokeInstruction) {
                throw new AssertionError("new success-path call needs ownership review");
            }
        }
        throw new AssertionError("missing successful return");
    }
    private static void actualPublication(boolean enabled) throws Exception {
        check(LodManager.ATOMIC_MESH_HANDOFF == enabled, "startup flag matches expected default/opt-in");
        check(Modifier.isFinal(field(LodManager.class, "ATOMIC_MESH_HANDOFF").getModifiers()),
            "handoff ownership mode cannot switch during an upload");
        LodManager manager = (LodManager)blank(LodManager.class);
        LodGpu gpu = (LodGpu)blank(LodGpu.class);
        var legacy = new ConcurrentLinkedQueue<LodManager.Node>();
        var box = new LodMeshHandoff<LodManager.Node, List<LodMesher.Section>>();
        set(manager, "uploads", legacy); set(manager, "meshHandoff", enabled ? box : null);
        set(gpu, "manager", manager);
        LodManager.Node node = new LodManager.Node(2, 3, -4);
        check(gpu.idle(), "empty owner idle");
        legacy.add(node); check(!gpu.idle(), "legacy pending queue prevents idle"); legacy.clear();
        if (!enabled) return;
        Method publish = LodManager.class.getDeclaredMethod("publishMesh", LodManager.Node.class, List.class, long.class);
        publish.setAccessible(true);
        LodMesher.Section section = new LodMesher.Section(16, 32, 48, 2);
        section.vertices[0] = ByteBuffer.wrap(new byte[]{7, 8, 9, 10});
        section.vertices[0].position(1);
        section.vertices[2] = ByteBuffer.wrap(new byte[]{11, 12});
        ArrayList<LodMesher.Section> supplied = new ArrayList<>(List.of(section));
        List<LodMesher.Section> untouchedLegacy = List.of(section);
        node.pending = untouchedLegacy; node.pendingStamp = -900;
        publish.invoke(manager, node, supplied, 73L);
        supplied.clear();
        check(!gpu.idle(), "atomic pending result prevents GPU idle too");
        check(node.meshStamp == 73 && box.hasStamp(node, 73), "scheduler sees published tag");
        check(node.pending == untouchedLegacy && node.pendingStamp == -900 && legacy.isEmpty(),
            "atomic producer never alters legacy payload, stamp or queue");
        var owned = box.poll();
        check(owned.key() == node && owned.payload().size() == 1 && owned.payload().getFirst() == section,
            "publication freezes list membership without duplicating completed section buffers");
        check(owned.requestedStamp() == 73 && owned.bytes() == 5, "actual producer couples stamp and remaining payload bytes");
        check(section.vertices[0].position() == 1 && section.vertices[0].get(1) == 8,
            "publication leaves source buffer position/bytes alone");
        try { owned.payload().clear(); throw new AssertionError("mutable publication container"); }
        catch (UnsupportedOperationException expected) { checks++; }
        publish.invoke(manager, node, List.of(), Long.MIN_VALUE);
        check(owned.requestedStamp() == 73 && owned.bytes() == 5 && owned.payload().size() == 1,
            "new publication cannot relabel or replace already claimed result");
        var empty = box.poll();
        check(empty.key() == node && empty.payload().isEmpty() && empty.bytes() == 0
            && empty.requestedStamp() == Long.MIN_VALUE, "empty replacement is a real upload result");
        publish.invoke(manager, node, List.of(section), 101L);
        publish.invoke(manager, node, List.of(), -22L);
        check(box.stats().pending() == 1 && box.stats().coalesced() == 1,
            "manager publication coalesces only unclaimed same-node work");
        box.close(); publish.invoke(manager, node, List.of(section), 999L);
        check(node.meshStamp == -1 && box.poll() == null && box.stats().rejected() == 1,
            "close rejects worker publication and clears scheduling marker");
        check(owned.payload().getFirst() == section && owned.requestedStamp() == 73,
            "close never reclaims consumer-owned CPU result");
        check(gpu.idle(), "closed empty mailbox idle");
    }
    public static void main(String[] args) throws Exception {
        markerOrder(Path.of(args[0]));
        if (args.length > 1) actualPublication(Boolean.parseBoolean(args[1]));
        System.out.println("LodMeshPublicationTest: " + checks + " CPU checks passed");
    }
}
