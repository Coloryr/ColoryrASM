package com.coloryr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColoryrPointerTest {
    @Test
    void warpEventsStayGamepadUntilMouseMoves() {
        ColoryrPointer.reset();
        assertFalse(ColoryrPointer.isGamepad());

        ColoryrPointer.injected();
        ColoryrPointer.ensureBaseline(100, 200);
        assertTrue(ColoryrPointer.isGamepad());

        // 手柄 warp 自己产生的事件，不能把手柄指针切掉
        ColoryrPointer.realCursor(100, 200);
        assertTrue(ColoryrPointer.isGamepad());

        // 真实鼠标动了，切回系统指针
        ColoryrPointer.realCursor(102, 200);
        assertFalse(ColoryrPointer.isGamepad());

        ColoryrPointer.injected();
        assertTrue(ColoryrPointer.isGamepad());
    }

    @Test
    void newGamepadSessionRebasesAtCurrentPosition() {
        ColoryrPointer.reset();
        ColoryrPointer.warp(50, 60);
        ColoryrPointer.realCursor(57, 61);
        assertFalse(ColoryrPointer.isGamepad());

        // 下一次手柄输入丢掉旧落点，用当前光标位置重新建基线
        ColoryrPointer.injected();
        ColoryrPointer.ensureBaseline(300, 400);
        assertTrue(ColoryrPointer.isGamepad());
        ColoryrPointer.realCursor(300, 400);
        assertTrue(ColoryrPointer.isGamepad());
        ColoryrPointer.realCursor(310, 400);
        assertFalse(ColoryrPointer.isGamepad());
    }
}
