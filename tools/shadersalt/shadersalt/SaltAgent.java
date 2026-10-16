package shadersalt;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Measurement-only agent for docs/stage2/STAGE2-PLAN.md W8 (tools/shader-warm-bench.sh, results/LAUNCH-SPEED.md).
 * Agent arguments (comma-separated): {@code salt=<s>} (or a bare {@code <s>}) and/or {@code time}.
 *
 * <p>salt: every MSL source the Metal backend compiles starts with the comment {@code // mcmetal salt <s>}, so the OS
 * Metal compiler caches (per user and bundle id, shared with every launch of the same java runtime) miss for a new salt
 * without deleting anything. A comment changes the source text (the front-end cache key) but not the code. Hook:
 * {@code MetalRenderPipeline$ShaderCache.function(int, String msl, String, String)} gets {@code msl = salt(msl)} at its
 * start (every MSL compile goes through it; the native present shader is the only Metal source outside it).
 *
 * <p>time: sums the wall time of every {@code MtlNative.shaderCreate} call (MSL -> MTLFunction, in
 * ShaderCache.createFunction) and every {@code MtlNative.pipelineCreate} call (PSO, in MetalRenderPipeline.createHandle)
 * over all threads, and prints {@code [shadersalt] shaderCreate n=.. ms=..; pipelineCreate n=.. ms=..; span .. ms} at
 * exit; a warm OS cache makes both small. Never used for benchmarks or parity runs.
 */
public final class SaltAgent {
    private static final String CACHE = "com/mojang/renderpearl/backend/metal/MetalRenderPipeline$ShaderCache";
    private static final String PIPELINE = "com/mojang/renderpearl/backend/metal/MetalRenderPipeline";
    private static final String NATIVE = "com/mojang/renderpearl/backend/metal/MtlNative";
    private static final ClassDesc SELF = ClassDesc.of("shadersalt.SaltAgent");
    private static final MethodTypeDesc INT_VOID = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int);
    private static final MethodTypeDesc STRING_STRING = MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String);
    private static volatile String prefix;
    private static volatile boolean timing;
    private static volatile int salted;
    private static final AtomicLong[] TOTAL = {new AtomicLong(), new AtomicLong()};
    private static final AtomicLong[] COUNT = {new AtomicLong(), new AtomicLong()};
    private static final AtomicLong FIRST = new AtomicLong(Long.MAX_VALUE);
    private static final AtomicLong LAST = new AtomicLong();
    private static final ThreadLocal<long[]> START = ThreadLocal.withInitial(() -> new long[2]);

    private SaltAgent() {
    }

    public static String salt(String msl) {
        salted++;
        return prefix + msl;
    }

    public static void enter(int id) {
        long now = System.nanoTime();
        START.get()[id] = now;
        FIRST.accumulateAndGet(now, Math::min);
    }

    public static void exit(int id) {
        long now = System.nanoTime();
        TOTAL[id].addAndGet(now - START.get()[id]);
        COUNT[id].incrementAndGet();
        LAST.accumulateAndGet(now, Math::max);
    }

    public static void premain(String args, Instrumentation inst) {
        for (String a : (args == null ? "" : args).split(",")) {
            if (a.equals("time")) {
                timing = true;
            } else if (!a.isEmpty()) {
                prefix = "// mcmetal salt " + (a.startsWith("salt=") ? a.substring(5) : a) + "\n";
            }
        }
        if (timing) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.printf(
                "[shadersalt] shaderCreate n=%d ms=%.0f; pipelineCreate n=%d ms=%.0f; span %.0f ms; salted %d%n",
                COUNT[0].get(), TOTAL[0].get() / 1e6, COUNT[1].get(), TOTAL[1].get() / 1e6,
                COUNT[0].get() + COUNT[1].get() == 0 ? 0.0 : (LAST.get() - FIRST.get()) / 1e6, salted)));
        }
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> cls, ProtectionDomain pd, byte[] bytes) {
                boolean cache = CACHE.equals(name);
                if (!cache && !(timing && PIPELINE.equals(name))) {
                    return null;
                }
                try {
                    ClassFile cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
                        ClassHierarchyResolver.defaultResolver().orElse(ClassHierarchyResolver.ofResourceParsing(loader))));
                    byte[] out = bytes;
                    if (timing) {   // wrap the native calls: enter(id); MtlNative.xxxCreate(...); exit(id)  (stack-neutral)
                        out = cf.transformClass(cf.parse(out), ClassTransform.transformingMethodBodies(
                            m -> m.methodName().equalsString("createFunction") || m.methodName().equalsString("createHandle"),
                            (CodeBuilder b, CodeElement e) -> {
                                if (e instanceof InvokeInstruction inv && inv.owner().asInternalName().equals(NATIVE)
                                    && (inv.name().equalsString("shaderCreate") || inv.name().equalsString("pipelineCreate"))) {
                                    int id = inv.name().equalsString("shaderCreate") ? 0 : 1;
                                    b.loadConstant(id);
                                    b.invokestatic(SELF, "enter", INT_VOID);
                                    b.with(e);
                                    b.loadConstant(id);
                                    b.invokestatic(SELF, "exit", INT_VOID);
                                } else {
                                    b.with(e);
                                }
                            }));
                    }
                    if (cache && prefix != null) {   // function(stage, msl, entryPoint, label): msl = salt(msl)
                        out = cf.transformClass(cf.parse(out), ClassTransform.transformingMethodBodies(
                            m -> m.methodName().equalsString("function")
                                && m.methodType().equalsString("(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)J"),
                            new CodeTransform() {
                                @Override
                                public void atStart(CodeBuilder b) {
                                    b.aload(2);
                                    b.invokestatic(SELF, "salt", STRING_STRING);
                                    b.astore(2);
                                }

                                @Override
                                public void accept(CodeBuilder b, CodeElement e) {
                                    b.with(e);
                                }
                            }));
                    }
                    System.out.println("[shadersalt] hooked " + name + (cache && prefix != null ? ": salt '" + prefix.trim() + "'" : "")
                        + (timing ? ", timing" : ""));
                    return out;
                } catch (Throwable t) {
                    t.printStackTrace();
                    return null;
                }
            }
        });
    }
}
