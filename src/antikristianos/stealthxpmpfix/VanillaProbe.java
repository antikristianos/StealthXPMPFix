package antikristianos.stealthxpmpfix;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.LinkedHashSet;
import java.util.Set;
import net.bytebuddy.jar.asm.*;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.iso.IsoMovingObject;

/** Builds read-only queries from the currently resolved game class resources, in memory only.
 * No game class or copied sight formula is shipped or replaced. The query ends BEFORE
 * Vanilla's detection roll, network ownership gate, XP awards and AI decisions. */
public final class VanillaProbe {
    private static final String GENERATED = "zombie/characters/StealthXPEvaluation";
    private final MethodHandle modern;
    private final MethodHandle legacy;

    public VanillaProbe() throws Exception {
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(IsoZombie.class, MethodHandles.lookup());
        MethodHandles.Lookup generated = lookup.defineHiddenClass(generate(), false,
                MethodHandles.Lookup.ClassOption.NESTMATE);
        MethodType type = MethodType.methodType(long.class, IsoZombie.class, IsoMovingObject.class, boolean.class);
        modern = generated.findStatic(generated.lookupClass(), "modern", type);
        legacy = generated.findStatic(generated.lookupClass(), "legacy", type);
    }

    public long sample(IsoZombie zombie, IsoPlayer player, boolean useModern) throws Throwable {
        return (long) (useModern ? modern : legacy).invokeExact(zombie, (IsoMovingObject) player, false);
    }

    public static byte[] classBytes(String internalName) throws IOException {
        try (InputStream in = IsoZombie.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            if (in == null) throw new IOException("Missing Vanilla class " + internalName);
            return in.readAllBytes();
        }
    }

    public static byte[] generate() throws IOException {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, GENERATED, null, "java/lang/Object", null);
        copy(writer, "zombie/characters/IsoZombie", "spottedNew", "modern", "(Lzombie/characters/IsoZombie;Lzombie/iso/IsoMovingObject;Z)J", 0);
        copy(writer, "zombie/characters/IsoZombie", "spottedOld", "legacy", "(Lzombie/characters/IsoZombie;Lzombie/iso/IsoMovingObject;Z)J", 0);
        copy(writer, "zombie/characters/IsoZombie", "updateVisionRadius", "vision", "(Lzombie/characters/IsoZombie;)F", 1);
        copy(writer, "zombie/characters/IsoGameCharacter", "checkIsNearWall", "wall", "(Lzombie/characters/IsoGameCharacter;)F", 2);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void copy(ClassWriter writer, String owner, String sourceName, String name, String desc, int kind) throws IOException {
        int[] matched = {0};
        new ClassReader(classBytes(owner)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                if (!method.equals(sourceName)) return null;
                matched[0]++;
                return new QueryVisitor(writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null), kind);
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (matched[0] != 1) throw new IllegalStateException("Ambiguous Vanilla query source: " + sourceName);
    }

    private static final class QueryVisitor extends MethodVisitor {
        private final int kind;
        private boolean ended;
        private boolean nextViewStore;
        private int viewSlot = -1;
        private int lastFloatStore = -1;
        private final Set<Label> referencedLabels = new LinkedHashSet<>();
        private final Set<Label> emittedLabels = new LinkedHashSet<>();
        QueryVisitor(MethodVisitor target, int kind) { super(Opcodes.ASM9, target); this.kind = kind; }
        @Override public void visitVarInsn(int op, int slot) {
            if (ended) return;
            if (op == Opcodes.FSTORE) {
                lastFloatStore = slot;
                if (nextViewStore) { viewSlot = slot; nextViewStore = false; }
            }
            super.visitVarInsn(op, slot);
        }
        @Override public void visitIntInsn(int op, int value) {
            if (ended) return;
            if (kind == 0 && op == Opcodes.SIPUSH && value == 10000) {
                if (viewSlot < 0 || lastFloatStore != 5) throw new IllegalStateException("Changed Vanilla spotting layout");
                mv.visitVarInsn(Opcodes.FLOAD, lastFloatStore);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "floatToRawIntBits", "(F)I", false);
                mv.visitInsn(Opcodes.I2L); mv.visitIntInsn(Opcodes.BIPUSH, 32); mv.visitInsn(Opcodes.LSHL);
                mv.visitVarInsn(Opcodes.FLOAD, viewSlot);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "floatToRawIntBits", "(F)I", false);
                mv.visitInsn(Opcodes.I2L); mv.visitLdcInsn(0xffffffffL); mv.visitInsn(Opcodes.LAND);
                mv.visitInsn(Opcodes.LOR); mv.visitInsn(Opcodes.LRETURN);
                ended = true;
            } else super.visitIntInsn(op, value);
        }
        @Override public void visitInsn(int op) {
            if (ended) return;
            if (kind == 0 && op == Opcodes.RETURN) { mv.visitLdcInsn(-1L); mv.visitInsn(Opcodes.LRETURN); }
            else super.visitInsn(op);
        }
        @Override public void visitFieldInsn(int op, String owner, String name, String desc) {
            if (ended) return;
            if (op == Opcodes.GETSTATIC && owner.equals("zombie/network/GameClient") && name.equals("client")) {
                mv.visitInsn(Opcodes.ICONST_0); // query is server-only, never runs a remote-client mutation branch
            } else if (op == Opcodes.GETSTATIC && (name.equals("tempo") || name.equals("tempo2")) && desc.equals("Lzombie/iso/Vector2;")) {
                mv.visitTypeInsn(Opcodes.NEW, "zombie/iso/Vector2"); mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "zombie/iso/Vector2", "<init>", "()V", false);
            } else if (op == Opcodes.GETFIELD && name.equals("current") && desc.equals("Lzombie/iso/IsoGridSquare;")) {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoMovingObject", "getCurrentSquare", "()Lzombie/iso/IsoGridSquare;", false);
            } else if (kind == 2 && op == Opcodes.GETFIELD && name.equals("sneaking")) {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "zombie/characters/IsoGameCharacter", "isSneaking", "()Z", false);
            } else if (kind == 0 && op == Opcodes.GETFIELD && name.equals("visionRadiusResult")) {
                mv.visitInsn(Opcodes.POP); mv.visitVarInsn(Opcodes.FLOAD, 80);
            } else if (kind == 1 && op == Opcodes.PUTFIELD && name.equals("visionRadiusResult")) {
                mv.visitInsn(Opcodes.SWAP); mv.visitInsn(Opcodes.POP); mv.visitInsn(Opcodes.FRETURN); ended = true;
            } else if (op == Opcodes.PUTFIELD && (name.equals("spottedLast") || name.equals("indoorZombie")
                    || name.equals("targetedByZombie") || name.equals("lastTargeted"))) {
                mv.visitInsn(Opcodes.POP2);
            } else if (op == Opcodes.PUTFIELD && !owner.equals("zombie/iso/Vector2")) {
                throw new IllegalStateException("Unexpected query mutation " + owner + "." + name);
            } else super.visitFieldInsn(op, owner, name, desc);
        }
        @Override public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
            if (ended) return;
            if (kind == 0 && name.equals("setTarget") && desc.equals("(Lzombie/iso/IsoMovingObject;)V")) {
                mv.visitInsn(Opcodes.POP2);
            } else if (kind == 0 && name.equals("ensureOnTile") && desc.equals("()V")) {
                mv.visitInsn(Opcodes.POP);
            } else if (kind == 0 && name.equals("updateVisionRadius") && desc.equals("()V")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, GENERATED, "vision", "(Lzombie/characters/IsoZombie;)F", false);
                mv.visitVarInsn(Opcodes.FSTORE, 80);
            } else if (kind == 0 && name.equals("checkIsNearWall") && desc.equals("()F")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, GENERATED, "wall", "(Lzombie/characters/IsoGameCharacter;)F", false);
            } else if (kind == 2 && name.equals("setVariable") && desc.equals("(Ljava/lang/String;Z)Lzombie/core/skinnedmodel/advancedanimation/IAnimationVariableSlot;")) {
                mv.visitInsn(Opcodes.POP2); mv.visitInsn(Opcodes.POP); mv.visitInsn(Opcodes.ACONST_NULL);
            } else {
                super.visitMethodInsn(op, owner, name, desc, itf);
                if (kind == 0 && viewSlot < 0 && owner.equals("zombie/GameTime") && name.equals("getViewDist")) nextViewStore = true;
            }
        }
        @Override public void visitJumpInsn(int op, Label label) {
            if (!ended) { referencedLabels.add(label); super.visitJumpInsn(op, label); }
        }
        @Override public void visitLabel(Label label) {
            if (!ended) { emittedLabels.add(label); super.visitLabel(label); }
        }
        @Override public void visitLdcInsn(Object value) { if (!ended) super.visitLdcInsn(value); }
        @Override public void visitTypeInsn(int op, String type) { if (!ended) super.visitTypeInsn(op, type); }
        @Override public void visitIincInsn(int slot, int amount) { if (!ended) super.visitIincInsn(slot, amount); }
        @Override public void visitMaxs(int stack, int locals) {
            if (kind != 2 && !ended) throw new IllegalStateException("Vanilla query boundary not found");
            // Recompiled classes can branch to a return after the detection-roll boundary.
            // Those paths leave the read-only query without reaching a probability result.
            boolean terminal = false;
            for (Label label : referencedLabels) if (!emittedLabels.contains(label)) {
                super.visitLabel(label);
                terminal = true;
            }
            if (terminal) {
                if (kind == 0) { mv.visitLdcInsn(-1L); mv.visitInsn(Opcodes.LRETURN); }
                else { mv.visitInsn(Opcodes.FCONST_0); mv.visitInsn(Opcodes.FRETURN); }
            }
            super.visitMaxs(0, 0);
        }
    }
}
