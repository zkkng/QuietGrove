package gmevents;

import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Exact live-class hotfix: 80 trial responders were too many for the legacy client. */
public final class PatchWaveTrialLimit {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("old-class new-class");
        byte[] original = Files.readAllBytes(Path.of(args[0]));
        ClassReader reader = new ClassReader(original);
        if (!reader.getClassName().equals("server/events/gm/WaveInvasionService"))
            throw new IllegalArgumentException("unexpected class");
        ClassWriter writer = new ClassWriter(reader, 0);
        int[] changed = new int[3];
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, base) {
                    @Override public void visitIntInsn(int opcode, int operand) {
                        if (opcode == Opcodes.BIPUSH && operand == 81 && name.equals("start")) {
                            changed[0]++;
                            operand = 25; // 24 responders plus the one host
                        } else if (opcode == Opcodes.BIPUSH && operand == 80 && name.equals("tick")) {
                            changed[1]++;
                            operand = 24;
                        }
                        super.visitIntInsn(opcode, operand);
                    }
                    @Override public void visitInvokeDynamicInsn(String dynamicName, String dynamicDescriptor,
                                                                 Handle bootstrap, Object... bootstrapArgs) {
                        Object[] revised = bootstrapArgs.clone();
                        for (int i = 0; i < revised.length; i++) {
                            if (revised[i] instanceof String text && text.contains("active response limit80")) {
                                revised[i] = text.replace("active response limit80", "active response limit24");
                                changed[2]++;
                            }
                        }
                        super.visitInvokeDynamicInsn(dynamicName, dynamicDescriptor, bootstrap, revised);
                    }
                };
            }
        }, 0);
        if (changed[0] != 1 || changed[1] != 1 || changed[2] != 1)
            throw new IllegalStateException("unexpected live bytecode shape: "
                    + changed[0] + "," + changed[1] + "," + changed[2]);
        Files.write(Path.of(args[1]), writer.toByteArray());
        System.out.println("changed_host_limit=1 changed_responder_limit=1 changed_status_text=1");
    }
}
