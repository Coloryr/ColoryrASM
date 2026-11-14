package com.coloryr;

/**
 * 最近一次指针输入来自手柄还是真实鼠标
 *
 * 启动器下发的输入（手柄映射）都算手柄；手柄移动光标时要 warp 系统光标，
 * warp 自己产生的那条光标事件不能当成鼠标，否则会立刻把手柄指针切掉。
 * 进入手柄模式时先记下当前光标位置作为基线，之后坐标与基线一致就认为还是手柄，
 * 位置发生变化则说明用户动了鼠标，切回系统指针。
 */
final class ColoryrPointer {
    /** 基线坐标的允许误差，单位像素 */
    private static final double EPSILON = 1.0D;

    private static volatile boolean gamepad;
    private static boolean hasWarp;
    private static double warpX;
    private static double warpY;

    private ColoryrPointer() {
    }

    /** 收到启动器下发的输入（手柄 / 映射）时调用 */
    static synchronized void injected() {
        if (!gamepad) {
            // 新手柄会话从当前光标位置重新建立基线
            hasWarp = false;
        }
        gamepad = true;
    }

    /** 手柄把系统光标移动到目标位置前调用，记录这次 warp 的落点 */
    static synchronized void warp(double x, double y) {
        warpX = x;
        warpY = y;
        hasWarp = true;
        gamepad = true;
    }

    /** 退出抓取模式时丢掉旧基线，下一帧按当前光标位置重新建立 */
    static synchronized void rebase() {
        hasWarp = false;
    }

    /** 手柄模式下第一次取到光标位置时建立基线 */
    static synchronized void ensureBaseline(double x, double y) {
        if (!hasWarp) {
            warpX = x;
            warpY = y;
            hasWarp = true;
        }
    }

    /** 是否应当显示手柄指针 */
    static boolean isGamepad() {
        return gamepad;
    }

    /**
     * 真实光标事件
     *
     * 与基线（自己 warp 的落点）一致时忽略，否则认为用户动了鼠标，切回系统指针。
     */
    static synchronized void realCursor(double x, double y) {
        if (!hasWarp) {
            return;
        }
        if (Math.abs(x - warpX) <= EPSILON && Math.abs(y - warpY) <= EPSILON) {
            return;
        }
        gamepad = false;
    }

    /** 仅供测试复位状态 */
    static synchronized void reset() {
        gamepad = false;
        hasWarp = false;
        warpX = 0;
        warpY = 0;
    }
}
