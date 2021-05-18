package uk.co.thinkofdeath.vanillacord;

import org.objectweb.asm.*;

import java.util.ArrayList;

public class LoginPacket extends ClassVisitor {

    private final LoginListener loginListener;
    private final ArrayList<Type> loginTypes = new ArrayList<>();
    private final Type serverQuery;

    public LoginPacket(ClassWriter classWriter, LoginListener loginListener, String loginType, String serverQuery) throws ClassNotFoundException {
        super(Opcodes.ASM9, classWriter);
        this.loginListener = loginListener;
        this.serverQuery = Type.getType(Class.forName(serverQuery.substring(0, serverQuery.length() - 6)));
        typeSearch(Class.forName(loginType.substring(0, loginType.length() - 6)), loginTypes);
    }

    private static void typeSearch(Class<?> next, ArrayList<Type> types) {
        types.add(Type.getType(next));
        if (next.getSuperclass() != null) {
            Type t = Type.getType(next.getSuperclass());
            if (!types.contains(t)) types.add(t);
        }
        for (Class<?> c : next.getInterfaces()) {
            Type t = Type.getType(c);
            if (!types.contains(t)) {
                typeSearch(c, types);
            }
        }
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        Type methodArgs = Type.getMethodType(desc);
        if (methodArgs.getArgumentTypes().length == 1
                && loginTypes.contains(methodArgs.getArgumentTypes()[0])
                && methodArgs.getReturnType().equals(Type.VOID_TYPE)
                && (exceptions == null || exceptions.length == 0)) {
            MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
            mv.visitCode();
            mv.visitLabel(new Label());
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitTypeInsn(Opcodes.CHECKCAST, loginListener.thisName);
            mv.visitFieldInsn(Opcodes.GETFIELD, loginListener.thisName, loginListener.fieldName, loginListener.fieldDesc);
            mv.visitVarInsn(Opcodes.ASTORE, 2);

            mv.visitLabel(new Label());
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitLdcInsn(serverQuery);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "uk/co/thinkofdeath/vanillacord/util/VelocityHelper",
                    "initializeTransaction",
                    "(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/Object;)V", false
            );
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(2, 2);
            mv.visitEnd();
            return null;
        } else {
            return super.visitMethod(access, name, desc, signature, exceptions);
        }
    }
}
