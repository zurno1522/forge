package forge.ai;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Label;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.Type;
import jdk.internal.org.objectweb.asm.commons.AdviceAdapter;
import jdk.internal.org.objectweb.asm.commons.Method;

/** Development-only instrumentation; never installed by Forge or its test runner. */
public final class InputContextPhaseAgent {
    static Instrumentation instrumentation;
    static int transformed;

    public static void premain(String ignored, Instrumentation value) {
        instrumentation = value;
        InputContextPhaseMetrics.initialize();
        if (!Boolean.getBoolean("forge.context.profile")) { return; }
        value.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                    ProtectionDomain domain, byte[] bytes) {
                if (name == null) { return null; }
                if (!name.equals("forge/ai/AiInputContext") && !name.equals("forge/ai/AiInputContext$Capture")
                        && !name.equals("forge/ai/AiDecisionEvent") && !name.equals("forge/game/Game")
                        && !(Boolean.getBoolean("forge.context.pipeline") && name.equals("forge/view/SimulationTraceWriter"))) { return null; }
                ClassReader reader = new ClassReader(bytes);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM8, writer) {
                    @Override public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] errors) {
                        MethodVisitor visitor = super.visitMethod(access, method, desc, signature, errors);
                        boolean freeze = name.equals("forge/ai/AiDecisionEvent") && method.equals("freezeMap");
                        int phase = name.equals("forge/ai/AiInputContext") && method.equals("capture") ? 0
                                : name.equals("forge/game/Game") && method.equals("fireEvent") ? 4
                                : name.equals("forge/view/SimulationTraceWriter") && (method.equals("write") || method.equals("flush")) ? 5 : -1;
                        boolean sort = name.equals("forge/ai/AiInputContext$Capture") && method.equals("build");
                        if (!freeze && phase < 0 && !sort) { return visitor; }
                        return new AdviceAdapter(Opcodes.ASM8, visitor, access, method, desc) {
                            private final Type metrics = Type.getType(InputContextPhaseMetrics.class);
                            private final Label protectedStart = new Label();
                            @Override protected void onMethodEnter() {
                                if (freeze) { loadArg(0); invokeStatic(metrics, new Method("freezeEnter", "(Ljava/lang/Object;)V")); }
                                else if (phase >= 0) { push(phase); invokeStatic(metrics, new Method("enter", "(I)V")); }
                                if (freeze || phase >= 0) { mark(protectedStart); }
                            }
                            @Override protected void onMethodExit(int opcode) {
                                if (freeze && opcode == ARETURN) {
                                    dup(); invokeStatic(metrics, new Method("freezeExit", "(Ljava/lang/Object;)V"));
                                } else if (phase >= 0 && opcode != ATHROW) { push(phase); invokeStatic(metrics, new Method("exit", "(I)V")); }
                            }
                            @Override public void visitMaxs(int maxStack, int maxLocals) {
                                if (freeze || phase >= 0) {
                                    final Label end = new Label(), handler = new Label();
                                    mark(end);
                                    visitTryCatchBlock(protectedStart, end, handler, "java/lang/Throwable");
                                    mark(handler);
                                    // The handler uses only the thrown object and static metrics: no method locals.
                                    visitFrame(F_NEW, 0, new Object[0], 1, new Object[]{"java/lang/Throwable"});
                                    if (freeze) { invokeStatic(metrics, new Method("freezeFailed", "()V")); }
                                    else { push(phase); invokeStatic(metrics, new Method("exit", "(I)V")); }
                                    throwException();
                                }
                                super.visitMaxs(maxStack, maxLocals);
                            }
                            @Override public void visitMethodInsn(int opcode, String owner, String target, String descriptor, boolean itf) {
                                boolean order = sort && owner.equals("java/util/List") && target.equals("sort");
                                if (order) {
                                    invokeStatic(metrics, new Method("sort", "(Ljava/util/List;Ljava/util/Comparator;)V"));
                                } else { super.visitMethodInsn(opcode, owner, target, descriptor, itf); }
                            }
                        };
                    }
                }, ClassReader.EXPAND_FRAMES);
                transformed++;
                return writer.toByteArray();
            }
        });
    }
}
