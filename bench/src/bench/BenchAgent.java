package bench;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * Java agent that inserts a single {@code Bench.onFrame()} call at the top of
 * {@code Minecraft.renderFrame(boolean)}. Uses only the JDK's built-in ClassFile
 * API, so the game itself stays byte-for-byte vanilla apart from this hook.
 *
 * With -Dbench.mode=zoomseq or reuseseq it also hooks {@code DeltaTracker.Timer.advanceGameTime(long)} for the
 * lockstep capture clock (see {@link #lockstepTimer}); timing modes keep the vanilla clock.
 *
 * With -Dbench.mode=scenes (and only then) it also makes {@code LevelRenderer.submitEntities} start with
 * {@code EntityCapture.onSubmit(levelRenderState)} (see {@link #entityCaptureHook}): the zoom entity scenes read which entities
 * the captured frame really drew, because LevelRenderer clears the entity render states right after submitting them.
 *
 * With -Dbench.frameTrace=true (and only then) {@link #traceHooks} adds the {@link FrameProbe} timing probes to
 * {@code Minecraft.runTick(boolean)} and {@code renderFrame(boolean)}, and sets {@code -Dmcmetal.frameTrace=true} (unless
 * given) so the Metal backend keeps its per-frame counters (docs/metal/CONTRACT.md 16.R5-B).
 */
public final class BenchAgent {
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String TIMER = "net/minecraft/client/DeltaTracker$Timer";
    private static final String WINDOW = "com/mojang/blaze3d/platform/Window";
    private static final String LEVEL_RENDERER = "net/minecraft/client/renderer/LevelRenderer";
    private static final ClassDesc LEVEL_RENDER_STATE = ClassDesc.ofInternalName("net/minecraft/client/renderer/state/level/LevelRenderState");
    private static final ClassDesc TIMER_DESC = ClassDesc.ofInternalName(TIMER);
    private static final ClassDesc LOCKSTEP = ClassDesc.of("bench.Lockstep");
    private static final ClassDesc PROBE = ClassDesc.of("bench.FrameProbe");
    private static final MethodTypeDesc INT_VOID = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int);
    private static final MethodTypeDesc INT_INT = MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_int);
    private static final MethodTypeDesc VOID = MethodTypeDesc.of(ConstantDescs.CD_void);

    /** One probed call site: inside {@code method(Z)V} of Minecraft, every call of owner.name desc. */
    record Site(String method, String owner, String name, String desc, int id) {
    }

    /**
     * The patched client's late-throttle tick bundle (docs/metal/CONTRACT.md 16.R5-A, -Dmcmetal.lateThrottle): runTick
     * calls it instead of its own packets / tasks / texture tick / tick() sequence, so the "runTick" sites are probed in
     * it as well (one of the two runs per runTick call). Absent on the vanilla jar.
     */
    public static final String LATE_BUNDLE = "runTickBundleLateThrottle";

    static final Site[] SITES = {
        new Site("runTick", "net/minecraft/network/PacketProcessor", "processQueuedPackets", "()V", FrameProbe.PACKETS),
        new Site("runTick", MINECRAFT, "runAllTasks", "()V", FrameProbe.TASKS),
        new Site("runTick", "net/minecraft/client/renderer/texture/TextureManager", "tick", "()V", FrameProbe.TEX_TICK),
        new Site("runTick", MINECRAFT, "tick", "()V", FrameProbe.TICK),
        new Site("renderFrame", "net/minecraft/client/multiplayer/ClientLevel", "update", "()V", FrameProbe.LEVEL_UPDATE),
        new Site("renderFrame", "net/minecraft/client/renderer/GameRenderer", "extract", "(Lnet/minecraft/client/DeltaTracker;Z)V", FrameProbe.EXTRACT),
        new Site("renderFrame", "net/minecraft/client/renderer/GameRenderer", "render", "()V", FrameProbe.RENDER),
        new Site("renderFrame", "com/mojang/renderpearl/api/commands/CommandEncoder", "submit", "()V", FrameProbe.SUBMIT),
        new Site("renderFrame", "com/mojang/renderpearl/api/device/GpuSurface", "present", "()V", FrameProbe.PRESENT),
        new Site("renderFrame", "net/minecraft/client/FramerateLimiter", "limitDisplayFPS", "(I)V", FrameProbe.LIMITER),
    };

    /** What {@link #traceHooks} inserted (written once at class load; read by FrameTrace for the result). */
    private static volatile String traceReport = "not installed";

    /** "packets=1, tasks=1, ..., ticks=1, bundleEnd=1": insertions of the last {@link #traceHooks} call. */
    public static String traceReport() {
        return traceReport;
    }

    public static void premain(String args, Instrumentation inst) {
        boolean lockstep = "zoomseq".equals(System.getProperty("bench.mode")) || "reuseseq".equals(System.getProperty("bench.mode"));
        boolean scenes = "scenes".equals(System.getProperty("bench.mode"));
        boolean snapshot = "lodsnapshot".equals(System.getProperty("bench.mode"));
        boolean frameTrace = Boolean.getBoolean("bench.frameTrace");
        // mode=abab reads the GPU busy time of every frame from the Metal trace ring (Abab; off: bench.abab.gpu=false).
        boolean ababGpu = "abab".equals(System.getProperty("bench.mode")) && !"false".equals(System.getProperty("bench.abab.gpu"));
        if ((frameTrace || ababGpu) && System.getProperty("mcmetal.frameTrace") == null) {
            System.setProperty("mcmetal.frameTrace", "true");
        }
        if (frameTrace && ababGpu && System.getProperty("mcmetal.frameTrace.ringLog2") == null) {
            // FrameTrace copies the ring once, after the run: an abab run is longer than the default 2^17 values.
            System.setProperty("mcmetal.frameTrace.ringLog2", "19");
        }
        // Stage-2 W0b: chunk-pipeline counters in the patched client (ChunkStats) and render-thread timers (ChunkProbe).
        boolean chunkStats = "join".equals(System.getProperty("bench.mode")) || Boolean.getBoolean("bench.chunkStats");
        if (chunkStats && System.getProperty("mc.chunkStats") == null) {
            System.setProperty("mc.chunkStats", "true");
        }
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> cls, ProtectionDomain pd, byte[] bytes) {
                try {
                    if (chunkStats && !MINECRAFT.equals(name)) {
                        for (String[] target : ChunkProbe.TARGETS) {
                            if (target[0].equals(name)) {
                                return chunkProbes(bytes, loader, name);
                            }
                        }
                    }
                    if (MINECRAFT.equals(name)) {
                        byte[] out = frameTrace ? traceHooks(bytes, loader) : renderFrameHook(bytes, loader);
                        return snapshot ? snapshotHooks(out, loader, name) : out;
                    }
                    if (snapshot && (name.equals("net/minecraft/client/renderer/chunk/ChunkSectionsToRender")
                        || name.equals("com/mojang/renderpearl/backend/metal/MetalCommandEncoder")
                        || name.equals("com/mojang/renderpearl/backend/metal/MetalBuffer")
                        || name.equals("com/mojang/renderpearl/backend/metal/MetalTexture"))) {
                        return snapshotHooks(bytes, loader, name);
                    }
                    if (lockstep && TIMER.equals(name)) {
                        return lockstepTimer(bytes, loader);
                    }
                    if (scenes && LEVEL_RENDERER.equals(name)) {
                        byte[] out = entityCaptureHook(bytes, loader);
                        System.out.println("[bench] entity capture hook: " + entityCaptureReport);
                        return out;
                    }
                    return null;
                } catch (Throwable t) {
                    t.printStackTrace();
                    return null;
                }
            }
        });
    }

    private static ClassFile classFile(ClassLoader loader) {
        return ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
            ClassHierarchyResolver.defaultResolver().orElse(ClassHierarchyResolver.ofResourceParsing(loader))));
    }

    /** Snapshot-only hooks: no GPU calls inside a render pass; readbacks occur after renderFrame returns. */
    public static byte[] snapshotHooks(byte[] bytes, ClassLoader loader, String owner) {
        ClassFile cf = classFile(loader);
        ClassDesc target = ClassDesc.of("bench.LodSnapshot");
        ClassDesc slice = ClassDesc.of("com.mojang.renderpearl.api.buffers.GpuBufferSlice");
        ClassDesc buffer = ClassDesc.of("com.mojang.renderpearl.api.buffers.GpuBuffer");
        ClassDesc view = ClassDesc.of("com.mojang.renderpearl.api.textures.GpuTextureView");
        ClassDesc sampler = ClassDesc.of("com.mojang.renderpearl.api.textures.GpuSampler");
        int[] inserted = {0};
        byte[] out = cf.transformClass(cf.parse(bytes), (cb, element) -> {
            if (!(element instanceof MethodModel m)) { cb.with(element); return; }
            String n = m.methodName().stringValue(), d = m.methodType().stringValue();
            int kind = owner.equals(MINECRAFT) && n.equals("renderFrame") && d.equals("(Z)V") ? 1
                : owner.endsWith("/ChunkSectionsToRender") && n.equals("renderLayers") && d.equals(
                    "([Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V") ? 2
                : owner.endsWith("/MetalCommandEncoder") && n.equals("writeToBuffer") && d.equals(
                    "(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Ljava/nio/ByteBuffer;)V") ? 3
                : owner.endsWith("/MetalBuffer") && n.equals("map") && d.equals(
                    "(JJZZ)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice$MappedView;") ? 4
                : owner.endsWith("/MetalCommandEncoder") && n.equals("copyToBuffer") && d.equals(
                    "(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;)V") ? 5
                : owner.endsWith("/MetalTexture") && n.equals("create") && d.equals(
                    "(Lcom/mojang/renderpearl/backend/metal/MetalDevice;Ljava/lang/String;ILcom/mojang/renderpearl/api/GpuFormat;IIII)Lcom/mojang/renderpearl/backend/metal/MetalTexture;") ? 6 : 0;
            if (kind == 0) { cb.with(element); return; }
            cb.transformMethod(m, MethodTransform.transformingCode(new CodeTransform() {
                @Override public void atStart(CodeBuilder b) {
                    if (kind == 2) b.aload(0).aload(4).aload(5).aload(2).invokestatic(target, "terrain",
                        MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object, view, view, sampler));
                    if (kind == 3) b.aload(1).aload(2).invokestatic(target, "uniformWrite",
                        MethodTypeDesc.of(ConstantDescs.CD_void, slice, ClassDesc.of("java.nio.ByteBuffer")));
                    if (kind == 4) b.aload(0).iload(6).invokestatic(target, "uniformMap",
                        MethodTypeDesc.of(ConstantDescs.CD_void, buffer, ConstantDescs.CD_boolean));
                    if (kind == 5) b.aload(2).invokestatic(target, "uniformCopy", MethodTypeDesc.of(ConstantDescs.CD_void, slice));
                    if (kind == 6) b.aload(1).iload(2).invokestatic(target, "readbackUsage",
                        MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_String, ConstantDescs.CD_int)).istore(2);
                    if (kind != 1) inserted[0]++;
                }
                @Override public void accept(CodeBuilder b, CodeElement e) {
                    if (kind == 1 && e instanceof Instruction i && i.opcode() == java.lang.classfile.Opcode.RETURN) {
                        b.invokestatic(target, "afterFrame", VOID); inserted[0]++;
                    }
                    b.with(e);
                }
            }));
        });
        int expected = owner.endsWith("/MetalCommandEncoder") ? 2 : 1;
        if (inserted[0] < expected) throw new IllegalStateException("Missing snapshot hook: " + owner + ": " + inserted[0]);
        System.out.println("[bench] snapshot hooks " + owner + ": " + inserted[0]);
        return out;
    }

    private static byte[] renderFrameHook(byte[] bytes, ClassLoader loader) {
        ClassFile cf = classFile(loader);
        return cf.transformClass(cf.parse(bytes), ClassTransform.transformingMethodBodies(
            m -> m.methodName().equalsString("renderFrame") && m.methodType().equalsString("(Z)V"),
            new CodeTransform() {
                @Override
                public void atStart(CodeBuilder b) {
                    b.invokestatic(ClassDesc.of("bench.Bench"), "onFrame", MethodTypeDesc.of(ConstantDescs.CD_void));
                }

                @Override
                public void accept(CodeBuilder b, CodeElement e) {
                    b.with(e);
                }
            }));
    }

    /**
     * {@link #renderFrameHook} plus the {@link FrameProbe} probes (-Dbench.frameTrace=true):
     * <pre>
     *   runTick(Z)V:      x = FrameProbe.ticks(timer.advanceGameTime(ms));         // bundle start, value unchanged
     *                     FrameProbe.bundleEnd(); window.setErrorSection("Render"); // bundle end
     *   both methods:     FrameProbe.enter(id); site(..); FrameProbe.exit(id);     // every call of each {@link #SITES} entry
     * </pre>
     * Stack-neutral insertions only (the {@code ticks} call consumes and returns the int on the stack). Public for
     * tools/BenchHookTest.java; {@link #traceReport} lists the insertion counts.
     */
    public static byte[] traceHooks(byte[] bytes, ClassLoader loader) {
        ClassFile cf = classFile(loader);
        int[] counts = new int[FrameProbe.COUNT + 2];   // per probe id, then ticks, bundleEnd
        ClassTransform transform = (builder, element) -> {
            if (element instanceof MethodModel method && method.methodType().equalsString("(Z)V")
                && (method.methodName().equalsString("runTick") || method.methodName().equalsString("renderFrame"))) {
                builder.transformMethod(method, MethodTransform.transformingCode(probes(method.methodName().stringValue(), counts)));
            } else if (element instanceof MethodModel method && method.methodName().equalsString(LATE_BUNDLE)) {
                // r5-int: the late-throttle bundle's call sites count as runTick's (no ticks / bundleEnd insertion there:
                // advanceGameTime and setErrorSection("Render") stay in runTick, around the call).
                builder.transformMethod(method, MethodTransform.transformingCode(probes("runTick", counts)));
            } else {
                builder.with(element);
            }
        };
        byte[] out = cf.transformClass(cf.parse(bytes), transform);
        StringBuilder report = new StringBuilder();
        for (int i = 0; i < FrameProbe.COUNT; i++) {
            report.append(i == 0 ? "" : ", ").append(FrameProbe.NAMES[i]).append('=').append(counts[i]);
        }
        report.append(", ticks=").append(counts[FrameProbe.COUNT]).append(", bundleEnd=").append(counts[FrameProbe.COUNT + 1]);
        traceReport = report.toString();
        System.out.println("[bench] frameTrace probes: " + traceReport);
        return out;
    }

    private static CodeTransform probes(String method, int[] counts) {
        boolean render = method.equals("renderFrame");
        return new CodeTransform() {
            private Object lastConstant;

            @Override
            public void atStart(CodeBuilder b) {
                if (render) {
                    b.invokestatic(ClassDesc.of("bench.Bench"), "onFrame", VOID);
                }
            }

            @Override
            public void accept(CodeBuilder b, CodeElement e) {
                if (e instanceof InvokeInstruction invoke) {
                    String owner = invoke.owner().asInternalName();
                    String name = invoke.name().stringValue();
                    String desc = invoke.type().stringValue();
                    Object constant = this.lastConstant;
                    this.lastConstant = null;
                    if (!render && TIMER.equals(owner) && "advanceGameTime".equals(name) && "(J)I".equals(desc)) {
                        b.with(e);
                        b.invokestatic(PROBE, "ticks", INT_INT);
                        counts[FrameProbe.COUNT]++;
                        return;
                    }
                    if (!render && WINDOW.equals(owner) && "setErrorSection".equals(name) && "Render".equals(constant)) {
                        b.invokestatic(PROBE, "bundleEnd", VOID);
                        b.with(e);
                        counts[FrameProbe.COUNT + 1]++;
                        return;
                    }
                    for (Site site : SITES) {
                        if (site.method().equals(method) && site.owner().equals(owner) && site.name().equals(name) && site.desc().equals(desc)) {
                            b.loadConstant(site.id());
                            b.invokestatic(PROBE, "enter", INT_VOID);
                            b.with(e);
                            b.loadConstant(site.id());
                            b.invokestatic(PROBE, "exit", INT_VOID);
                            counts[site.id()]++;
                            return;
                        }
                    }
                    b.with(e);
                    return;
                }
                if (e instanceof ConstantInstruction constant) {
                    this.lastConstant = constant.constantValue();
                } else if (e instanceof Instruction) {
                    this.lastConstant = null;
                }
                b.with(e);
            }
        };
    }

    /**
     * Wraps every {@link ChunkProbe#TARGETS} method of class {@code owner}: {@code long t = ChunkProbe.enter();} at the
     * start, {@code ChunkProbe.exit(id, t);} before every return (an exception skips the exit: not timed). Public for
     * tools/BenchHookTest.java.
     */
    public static byte[] chunkProbes(byte[] bytes, ClassLoader loader, String owner) {
        ClassFile cf = classFile(loader);
        ClassDesc probe = ClassDesc.of("bench.ChunkProbe");
        ClassTransform transform = (builder, element) -> {
            if (element instanceof MethodModel method) {
                for (int id = 0; id < ChunkProbe.COUNT; id++) {
                    String[] t = ChunkProbe.TARGETS[id];
                    if (t[0].equals(owner) && method.methodName().equalsString(t[1]) && method.methodType().equalsString(t[2])) {
                        final int probeId = id;
                        ChunkProbe.inserted[id]++;
                        builder.transformMethod(method, MethodTransform.transformingCode(new CodeTransform() {
                            private int slot = -1;

                            @Override
                            public void atStart(CodeBuilder b) {
                                this.slot = b.allocateLocal(java.lang.classfile.TypeKind.LONG);
                                b.invokestatic(probe, "enter", MethodTypeDesc.of(ConstantDescs.CD_long));
                                b.lstore(this.slot);
                            }

                            @Override
                            public void accept(CodeBuilder b, CodeElement e) {
                                if (e instanceof java.lang.classfile.instruction.ReturnInstruction) {
                                    b.loadConstant(probeId);
                                    b.lload(this.slot);
                                    b.invokestatic(probe, "exit", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int, ConstantDescs.CD_long));
                                }
                                b.with(e);
                            }
                        }));
                        return;
                    }
                }
            }
            builder.with(element);
        };
        byte[] out = cf.transformClass(cf.parse(bytes), transform);
        System.out.println("[bench] chunk probes in " + owner + ": " + java.util.Arrays.toString(ChunkProbe.inserted));
        return out;
    }

    /** Insertions made by the last {@link #entityCaptureHook} call ("submitEntities=1" when installed). */
    public static volatile String entityCaptureReport = "not installed";

    /**
     * Prepends {@code EntityCapture.onSubmit(levelRenderState)} to every instance method {@code submitEntities} of
     * LevelRenderer that takes a LevelRenderState (vanilla and patched: {@code (PoseStack, LevelRenderState,
     * SubmitNodeCollector)V}); the argument slot is computed from the descriptor. Nothing else changes. Public for
     * tools/BenchHookTest.java.
     */
    public static byte[] entityCaptureHook(byte[] bytes, ClassLoader loader) {
        ClassFile cf = classFile(loader);
        int[] count = {0};
        ClassTransform transform = (builder, element) -> {
            if (element instanceof MethodModel method && method.methodName().equalsString("submitEntities")
                && (method.flags().flagsMask() & ClassFile.ACC_STATIC) == 0) {
                MethodTypeDesc type = method.methodTypeSymbol();
                int slot = 1, found = -1;
                for (ClassDesc param : type.parameterList()) {
                    if (param.equals(LEVEL_RENDER_STATE)) {
                        found = slot;
                        break;
                    }
                    slot += param.equals(ConstantDescs.CD_long) || param.equals(ConstantDescs.CD_double) ? 2 : 1;
                }
                if (found >= 0) {
                    final int stateSlot = found;
                    count[0]++;
                    builder.transformMethod(method, MethodTransform.transformingCode(new CodeTransform() {
                        @Override
                        public void atStart(CodeBuilder b) {
                            b.aload(stateSlot);
                            b.invokestatic(ClassDesc.of("bench.EntityCapture"), "onSubmit", MethodTypeDesc.of(ConstantDescs.CD_void, LEVEL_RENDER_STATE));
                        }

                        @Override
                        public void accept(CodeBuilder b, CodeElement e) {
                            b.with(e);
                        }
                    }));
                    return;
                }
            }
            builder.with(element);
        };
        byte[] out = cf.transformClass(cf.parse(bytes), transform);
        entityCaptureReport = "submitEntities=" + count[0];
        return out;
    }

    /**
     * Prepends to {@code DeltaTracker.Timer.advanceGameTime(long currentMs)}:
     * <pre>
     *   if (Lockstep.active()) {
     *       this.lastMs = currentMs;                      // vanilla timing resumes cleanly after Lockstep.stop()
     *       this.deltaTicks = Lockstep.delta();
     *       int ticks = Lockstep.step();
     *       this.deltaTickResidual = Lockstep.partial();  // the partial tick the next render uses
     *       return ticks;
     *   }
     * </pre>
     * Public for tools/BenchHookTest.java, which verifies the transformed class headlessly.
     */
    public static byte[] lockstepTimer(byte[] bytes, ClassLoader loader) {
        ClassFile cf = classFile(loader);
        return cf.transformClass(cf.parse(bytes), ClassTransform.transformingMethodBodies(
            m -> m.methodName().equalsString("advanceGameTime") && m.methodType().equalsString("(J)I"),
            new CodeTransform() {
                @Override
                public void atStart(CodeBuilder b) {
                    Label vanilla = b.newLabel();
                    b.invokestatic(LOCKSTEP, "active", MethodTypeDesc.of(ConstantDescs.CD_boolean));
                    b.ifeq(vanilla);
                    b.aload(0);
                    b.lload(1);
                    b.putfield(TIMER_DESC, "lastMs", ConstantDescs.CD_long);
                    b.aload(0);
                    b.invokestatic(LOCKSTEP, "delta", MethodTypeDesc.of(ConstantDescs.CD_float));
                    b.putfield(TIMER_DESC, "deltaTicks", ConstantDescs.CD_float);
                    b.invokestatic(LOCKSTEP, "step", MethodTypeDesc.of(ConstantDescs.CD_int));
                    b.aload(0);
                    b.invokestatic(LOCKSTEP, "partial", MethodTypeDesc.of(ConstantDescs.CD_float));
                    b.putfield(TIMER_DESC, "deltaTickResidual", ConstantDescs.CD_float);
                    b.ireturn();
                    b.labelBinding(vanilla);
                }

                @Override
                public void accept(CodeBuilder b, CodeElement e) {
                    b.with(e);
                }
            }));
    }
}
