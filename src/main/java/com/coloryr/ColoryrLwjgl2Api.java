package com.coloryr;

import java.nio.IntBuffer;

/** Object cursor handles keep LWJGL 2 types out of the agent's own class loader. */
public interface ColoryrLwjgl2Api {
    boolean isCreated();
    boolean isGrabbed();
    boolean isInsideWindow();
    int getWidth();
    int getHeight();
    int getX();
    int getY();
    int minCursorSize();
    Object getNativeCursor();
    /** LWJGL 2 的 setNativeCursor 返回原光标；返回类型与它不兼容时这条绑定会被判为不可用 */
    Object setNativeCursor(Object cursor);
    Object createCursor(int width, int height, int x, int y, int count, IntBuffer pixels, IntBuffer delays);
    void destroyCursor(Object cursor);
}
