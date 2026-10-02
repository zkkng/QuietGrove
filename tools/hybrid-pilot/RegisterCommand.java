package hybrid.build;

import java.nio.file.*;
import java.util.jar.JarFile;
import net.bytebuddy.jar.asm.*;

/** Add only the pilot command to the exact live executor, retaining its statistics advice. */
public final class RegisterCommand {
    public static void main(String[] args) throws Exception {
        try (var jar = new JarFile(args[0])) {
            byte[] original = jar.getInputStream(jar.getJarEntry("client/command/CommandsExecutor.class")).readAllBytes();
            var reader = new ClassReader(original);
            var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] matches = {0};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    var method = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!name.equals("registerLv4Commands") || !descriptor.equals("()V")) return method;
                    return new MethodVisitor(Opcodes.ASM9, method) {
                        @Override public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN) {
                                matches[0]++;
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitLdcInsn("hybrid");
                                super.visitInsn(Opcodes.ICONST_4);
                                super.visitLdcInsn(Type.getObjectType("client/command/commands/gm4/HybridBotCommand"));
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "client/command/CommandsExecutor", "addCommand",
                                        "(Ljava/lang/String;ILjava/lang/Class;)V", false);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            }, 0);
            if (matches[0] != 1) throw new IllegalStateException("Unexpected command registration layout");
            Files.write(Path.of(args[1]), writer.toByteArray());
        }
    }
}
