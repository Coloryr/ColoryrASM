package com.coloryr.asms;

import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * SDL 窗口层的注入点
 *
 * 26.3 起游戏窗口换成 SDL（org.lwjgl.sdl），GLFW 的钩子不会再命中。这里只改三处：
 * - SDL_GL_SwapWindow：每帧一次，画软件光标、上报焦点、把队列里的输入翻成 SDL 事件
 * - SDL_SetWindowRelativeMouseMode：游戏切换鼠标抓取时同步状态
 * - SDL_PollEvent：把合成事件直接写进游戏轮询用的 SDL_Event
 *
 * 不在 SDL_PushEvent 里塞事件：游戏的 flushInputEvents 会先 SDL_FlushEvents 把输入队列清空，
 * 塞进去的事件在下一帧就被丢掉了。
 */
public final class SdlAsm implements ClassFileTransformer, Opcodes {
    private static final String OWNER_SDL = "com/coloryr/ColoryrSdl";

    @Override
    public byte[] transform(ClassLoader loader, String owner, Class<?> redefined,
                            ProtectionDomain domain, byte[] bytes) {
        if (!"org/lwjgl/sdl/SDLVideo".equals(owner) && !"org/lwjgl/sdl/SDLMouse".equals(owner)
                && !"org/lwjgl/sdl/SDLEvents".equals(owner)
                && !"org/lwjgl/sdl/SDLVulkan".equals(owner)) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String signature, String[] exceptions) {
                    MethodVisitor method = super.visitMethod(access, name, desc, signature, exceptions);
                    if ((access & (ACC_NATIVE | ACC_ABSTRACT)) != 0) {
                        return method;
                    }
                    boolean swap = owner.equals("org/lwjgl/sdl/SDLVideo")
                            && name.equals("SDL_GL_SwapWindow") && desc.equals("(J)Z");
                    boolean mode = owner.equals("org/lwjgl/sdl/SDLMouse")
                            && name.equals("SDL_SetWindowRelativeMouseMode") && desc.equals("(JZ)Z");
                    boolean poll = owner.equals("org/lwjgl/sdl/SDLEvents")
                            && name.equals("SDL_PollEvent") && desc.equals("(Lorg/lwjgl/sdl/SDL_Event;)Z");
                    boolean vkSurface = owner.equals("org/lwjgl/sdl/SDLVulkan")
                            && name.equals("nSDL_Vulkan_CreateSurface") && desc.equals("(JJJJ)Z");
                    if (!swap && !mode && !poll && !vkSurface) {
                        return method;
                    }
                    if (vkSurface) {
                        // 返回值就是 VkSurfaceKHR 写入成功与否，交给 Vulkan 光标那边登记 surface 与窗口
                        return new MethodVisitor(ASM9, method) {
                            @Override
                            public void visitInsn(int opcode) {
                                if (opcode == IRETURN) {
                                    mv.visitInsn(DUP);
                                    mv.visitLdcInsn(Type.getObjectType(owner));
                                    mv.visitVarInsn(LLOAD, 0);
                                    mv.visitVarInsn(LLOAD, 6);
                                    mv.visitMethodInsn(INVOKESTATIC, OWNER_SDL, "onVulkanSurface",
                                            "(ZLjava/lang/Class;JJ)V", false);
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                    return new MethodVisitor(ASM9, method) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            if (swap) {
                                mv.visitLdcInsn(Type.getObjectType(owner));
                                mv.visitVarInsn(LLOAD, 0);
                                mv.visitMethodInsn(INVOKESTATIC, OWNER_SDL, "onFrame",
                                        "(Ljava/lang/Class;J)V", false);
                            } else if (mode) {
                                mv.visitVarInsn(LLOAD, 0);
                                mv.visitVarInsn(ILOAD, 2);
                                mv.visitMethodInsn(INVOKESTATIC, OWNER_SDL, "onRelativeMode",
                                        "(JZ)V", false);
                            } else {
                                // 先问合成事件：有就直接返回 true，游戏不会再去碰原生事件
                                Label original = new Label();
                                mv.visitLdcInsn(Type.getObjectType(owner));
                                mv.visitVarInsn(ALOAD, 0);
                                mv.visitMethodInsn(INVOKESTATIC, OWNER_SDL, "injectPoll",
                                        "(Ljava/lang/Class;Ljava/lang/Object;)Z", false);
                                mv.visitJumpInsn(IFEQ, original);
                                mv.visitInsn(ICONST_1);
                                mv.visitInsn(IRETURN);
                                mv.visitLabel(original);
                                // 静态方法入参就是 SDL_Event，方法入口处的局部变量表只有一个引用
                                mv.visitFrame(F_NEW, 1,
                                        new Object[]{"org/lwjgl/sdl/SDL_Event"}, 0, new Object[0]);
                            }
                        }
                    };
                }
            }, 0);
            return writer.toByteArray();
        } catch (Throwable e) {
            System.out.println("[ColoryrASM] sdl mixin failed for " + owner + ": " + e);
        }
        return null;
    }
}
