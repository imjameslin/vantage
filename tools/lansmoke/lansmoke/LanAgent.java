package lansmoke;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
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
 */
public final class LanAgent {
    public static void premain(String args, Instrumentation inst) {
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> cls, ProtectionDomain pd, byte[] bytes) {
                if (!"net/minecraft/client/Minecraft".equals(name)) {
                    return null;
                }
                try {
                    ClassFile cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
                        ClassHierarchyResolver.defaultResolver().orElse(ClassHierarchyResolver.ofResourceParsing(loader))));
                    return cf.transformClass(cf.parse(bytes), ClassTransform.transformingMethodBodies(
                        m -> m.methodName().equalsString("renderFrame") && m.methodType().equalsString("(Z)V"),
                        new CodeTransform() {
                            @Override
                            public void atStart(CodeBuilder b) {
                                b.invokestatic(ClassDesc.of("lansmoke.Lan"), "onFrame", MethodTypeDesc.of(ConstantDescs.CD_void));
                            }

                            @Override
                            public void accept(CodeBuilder b, CodeElement e) {
                                b.with(e);
                            }
                        }));
                } catch (Throwable t) {
                    t.printStackTrace();
                    return null;
                }
            }
        });
    }
}
