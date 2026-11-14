package com.coloryr;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * SDL 窗口（26.3 起）的输入与软件光标
 *
 * 游戏从 26.3 开始用 SDL 代替 GLFW：窗口创建、事件轮询、鼠标抓取都走 org.lwjgl.sdl。
 * 注入点见 {@link com.coloryr.asms.SdlAsm}：每帧交换前把队列里的输入翻成 SDL 事件塞进
 * poll 结果，光标模式下移动系统光标并画自己的指针。
 *
 * 输入事件不在 netty 线程直接塞 SDL 队列：游戏的 flushInputEvents 每帧先
 * SDL_FlushEvents 清队列，塞进去的事件下一帧就没了。
 */
public final class ColoryrSdl {
    /** SDL3 的 SDL_WINDOW_INPUT_FOCUS */
    private static final int WINDOW_INPUT_FOCUS = 512;
    /** SDL3 事件号 */
    private static final int EVENT_KEY_DOWN = 0x300;
    private static final int EVENT_KEY_UP = 0x301;
    private static final int EVENT_MOUSE_MOTION = 0x400;
    private static final int EVENT_MOUSE_BUTTON_DOWN = 0x401;
    private static final int EVENT_MOUSE_BUTTON_UP = 0x402;
    private static final int EVENT_MOUSE_WHEEL = 0x403;

    /** 一条待注入的 SDL 事件，字段含义随 type 决定 */
    private static final class Pending {
        int type;
        int keycode;
        int scancode;
        boolean down;
        int button;
        float x;
        float y;
        float xrel;
        float yrel;
        float wheel;
    }

    private static final Queue<Pending> PENDING = new ArrayDeque<>();
    private static final FloatBuffer MOUSE_X = floats(1);
    private static final FloatBuffer MOUSE_Y = floats(1);
    private static final IntBuffer WIN_W = ints(1);
    private static final IntBuffer WIN_H = ints(1);
    private static final IntBuffer FB_W = ints(1);
    private static final IntBuffer FB_H = ints(1);

    private static ClassLoader loader;
    private static long window;
    private static int windowId;
    private static boolean ready;
    private static boolean failed;
    private static boolean vulkan;
    private static boolean grabbed;
    private static boolean cursorHidden;
    private static Boolean lastFocus;
    private static double pendingX;
    private static double pendingY;
    private static float lastX;
    private static float lastY;
    private static ColoryrCursorRenderer renderer;
    private static boolean errorPrinted;

    private static Method getWindowId;
    private static Method getWindowFlags;
    private static Method getWindowSize;
    private static Method getWindowSizeInPixels;
    private static Method getMouseState;
    private static Method warpMouse;
    private static Method hideCursor;
    private static Method showCursor;
    private static Method setWindowTitle;
    private static Method eventType;
    private static Method eventKey;
    private static Method eventMotion;
    private static Method eventButton;
    private static Method eventWheel;
    private static Method keySet;
    private static Method motionSet;
    private static Method buttonSet;
    private static Method wheelSet;

    private ColoryrSdl() {
    }

    /** 软件光标是否已经能接管这个窗口 */
    public static boolean isActive() {
        return ready && window != 0L;
    }

    /** 是否已经由 SDL 接管窗口（Vulkan 侧的激活 / 恢复据此选实现） */
    public static boolean isAttached() {
        return ready && window != 0L;
    }

    /**
     * 每帧交换前调用（SDL_GL_SwapWindow 入口）
     *
     * @param sdlVideo 游戏加载的 org.lwjgl.sdl.SDLVideo
     * @param handle   SDL_Window 句柄
     */
    public static void onFrame(Class<?> sdlVideo, long handle) {
        if (failed || handle == 0L) {
            return;
        }
        try {
            if (!ready) {
                init(sdlVideo);
            }
            if (window != handle) {
                window = handle;
                windowId = (Integer) getWindowId.invoke(null, handle);
                // 建 IPC 连接（GLFW 路径是在 addWindow 里做的同一件事）
                ColoryrASM.addWindow(handle);
            }
            if (!ColoryrASM.run) {
                return;
            }
            checkFocus(handle);
            dispatchInput();
            updateCursor(handle, true);
        } catch (Throwable e) {
            failed = true;
            report(e);
        }
    }

    /**
     * 由 SDL_Vulkan_CreateSurface 调进来：登记窗口与 surface
     *
     * @param ok         SDL 是否创建成功
     * @param sdlVulkan  游戏加载的 org.lwjgl.sdl.SDLVulkan
     * @param handle     SDL_Window 句柄
     * @param surfaceOut 输出 VkSurfaceKHR 的指针
     */
    public static void onVulkanSurface(boolean ok, Class<?> sdlVulkan, long handle, long surfaceOut) {
        if (!ok || handle == 0L) {
            return;
        }
        try {
            if (!ready) {
                init(sdlVulkan);
            }
            vulkan = true;
            if (window != handle) {
                window = handle;
                windowId = (Integer) getWindowId.invoke(null, handle);
                ColoryrASM.addWindow(handle);
            }
            // Vulkan 覆盖层要靠这张表把 swapchain 对应回窗口
            ColoryrVulkanCursor.surfaceCreated(0, sdlVulkan, handle, surfaceOut);
        } catch (Throwable e) {
            failed = true;
            report(e);
        }
    }

    /** Vulkan 每帧 present 前调用：输入、焦点、光标位置；画由 Vulkan 覆盖层负责 */
    public static void onVulkanFrame() {
        if (failed || !vulkan || window == 0L || !ColoryrASM.run) {
            return;
        }
        try {
            checkFocus(window);
            dispatchInput();
            updateCursor(window, false);
        } catch (Throwable e) {
            failed = true;
            report(e);
        }
    }

    /**
     * Vulkan 覆盖层画光标前取位置与尺寸
     *
     * @param width  交换链宽度，单位像素
     * @param height 交换链高度，单位像素
     * @return 依次为 x、y、宽、高；不在光标模式或位置在窗口外时返回 null
     */
    public static double[] vulkanFrame(int width, int height) {
        if (failed || !ready || window == 0L || grabbed || !ColoryrCursor.ENABLED) {
            return null;
        }
        try {
            getWindowSize.invoke(null, window, WIN_W, WIN_H);
            int ww = WIN_W.get(0);
            int wh = WIN_H.get(0);
            if (ww <= 0 || wh <= 0 || width <= 0 || height <= 0) {
                return null;
            }
            if (lastX < 0 || lastY < 0 || lastX >= ww || lastY >= wh) {
                return null;
            }
            return new double[]{
                    lastX * width / ww, lastY * height / wh,
                    ColoryrCursor.SIZE * width / ww, ColoryrCursor.SIZE * height / wh,
            };
        } catch (Throwable e) {
            report(e);
            return null;
        }
    }

    /** Vulkan 覆盖层接管窗口时隐藏系统光标 */
    public static void activate(long handle) {
        if (!ready) {
            return;
        }
        try {
            hideCursor.invoke(null);
            cursorHidden = true;
        } catch (Throwable e) {
            report(e);
        }
    }

    /** 退出覆盖层时把系统光标放回去 */
    public static void restore(long handle) {
        if (!ready) {
            return;
        }
        try {
            showCursor.invoke(null);
            cursorHidden = false;
        } catch (Throwable e) {
            report(e);
        }
    }

    /** 游戏切换鼠标抓取时同步状态（SDL_SetWindowRelativeMouseMode 入口） */
    public static void onRelativeMode(long handle, boolean relative) {
        if (handle == 0L) {
            return;
        }
        grabbed = relative;
        pendingX = 0;
        pendingY = 0;
        ColoryrPackBuilder.setGrabbed(relative);
    }

    /**
     * 把队列里最早的一条合成事件写进游戏正在轮询的 SDL_Event
     *
     * @param owner 游戏加载的 org.lwjgl.sdl.SDLEvents
     * @param event SDL_Event 指针包装
     * @return 有事件并已写入时返回 true
     */
    public static boolean injectPoll(Class<?> owner, Object event) {
        if (!ready || event == null) {
            return false;
        }
        Pending pending;
        synchronized (PENDING) {
            pending = PENDING.poll();
        }
        if (pending == null) {
            return false;
        }
        try {
            eventType.invoke(event, pending.type);
            switch (pending.type) {
                case EVENT_KEY_DOWN:
                case EVENT_KEY_UP:
                    keySet.invoke(eventKey.invoke(event), pending.type, 0L, windowId, 0,
                            pending.scancode, pending.keycode, (short) 0, (short) 0,
                            pending.down, false);
                    break;
                case EVENT_MOUSE_MOTION:
                    motionSet.invoke(eventMotion.invoke(event), pending.type, 0L, windowId, 0, 0,
                            pending.x, pending.y, pending.xrel, pending.yrel);
                    break;
                case EVENT_MOUSE_BUTTON_DOWN:
                case EVENT_MOUSE_BUTTON_UP:
                    buttonSet.invoke(eventButton.invoke(event), pending.type, 0L, windowId, 0,
                            (byte) pending.button, pending.down, (byte) 1, pending.x, pending.y);
                    break;
                case EVENT_MOUSE_WHEEL:
                    // set(type, timestamp, windowID, which, x, y, direction,
                    //     mouseX, mouseY, integerX, integerY)
                    // 上下滚动写在 y / integer_y，x 是横向滚动，写反了游戏只会看到横向输入
                    wheelSet.invoke(eventWheel.invoke(event), pending.type, 0L, windowId, 0,
                            0.0F, pending.wheel, 0, pending.x, pending.y,
                            0, (int) pending.wheel);
                    break;
                default:
                    return false;
            }
            return true;
        } catch (Throwable e) {
            report(e);
            return false;
        }
    }

    /**
     * 改窗口标题
     *
     * @param title 新标题
     * @return 已经由 SDL 处理时返回 true；没接管窗口时返回 false，调用方回落到 GLFW / LWJGL2
     */
    public static boolean setTitle(String title) {
        if (!ready || window == 0L) {
            return false;
        }
        try {
            setWindowTitle.invoke(null, window, title);
            return true;
        } catch (Throwable e) {
            report(e);
            return false;
        }
    }

    private static void init(Class<?> sdlClass) throws Exception {
        loader = sdlClass.getClassLoader();
        // 传进来的可能是 SDLVideo（GL 路径）或 SDLVulkan（Vulkan 路径），统一按名字取 SDLVideo
        Class<?> video = Class.forName("org.lwjgl.sdl.SDLVideo", false, loader);
        Class<?> mouse = Class.forName("org.lwjgl.sdl.SDLMouse", false, loader);
        Class<?> event = Class.forName("org.lwjgl.sdl.SDL_Event", false, loader);
        Class<?> key = Class.forName("org.lwjgl.sdl.SDL_KeyboardEvent", false, loader);
        Class<?> motion = Class.forName("org.lwjgl.sdl.SDL_MouseMotionEvent", false, loader);
        Class<?> button = Class.forName("org.lwjgl.sdl.SDL_MouseButtonEvent", false, loader);
        Class<?> wheel = Class.forName("org.lwjgl.sdl.SDL_MouseWheelEvent", false, loader);

        getWindowId = video.getMethod("SDL_GetWindowID", long.class);
        getWindowFlags = video.getMethod("SDL_GetWindowFlags", long.class);
        getWindowSize = video.getMethod("SDL_GetWindowSize", long.class,
                IntBuffer.class, IntBuffer.class);
        getWindowSizeInPixels = video.getMethod("SDL_GetWindowSizeInPixels", long.class,
                IntBuffer.class, IntBuffer.class);
        setWindowTitle = video.getMethod("SDL_SetWindowTitle", long.class, CharSequence.class);

        getMouseState = mouse.getMethod("SDL_GetMouseState", FloatBuffer.class, FloatBuffer.class);
        warpMouse = mouse.getMethod("SDL_WarpMouseInWindow", long.class, float.class, float.class);
        hideCursor = mouse.getMethod("SDL_HideCursor");
        showCursor = mouse.getMethod("SDL_ShowCursor");

        eventType = event.getMethod("type", int.class);
        eventKey = event.getMethod("key");
        eventMotion = event.getMethod("motion");
        eventButton = event.getMethod("button");
        eventWheel = event.getMethod("wheel");
        keySet = key.getMethod("set", int.class, long.class, int.class, int.class, int.class,
                int.class, short.class, short.class, boolean.class, boolean.class);
        motionSet = motion.getMethod("set", int.class, long.class, int.class, int.class, int.class,
                float.class, float.class, float.class, float.class);
        buttonSet = button.getMethod("set", int.class, long.class, int.class, int.class, byte.class,
                boolean.class, byte.class, float.class, float.class);
        wheelSet = wheel.getMethod("set", int.class, long.class, int.class, int.class, float.class,
                float.class, int.class, float.class, float.class, int.class, int.class);
        ready = true;
    }

    private static void checkFocus(long handle) throws Exception {
        long flags = (Long) getWindowFlags.invoke(null, handle);
        boolean focused = (flags & WINDOW_INPUT_FOCUS) != 0;
        if (lastFocus != null && lastFocus == focused) {
            return;
        }
        lastFocus = focused;
        ColoryrPackBuilder.setFocus(focused);
    }

    /** 把 ColoryrInput 队列里的输入翻成 SDL 事件 */
    private static void dispatchInput() {
        ColoryrInput.InputEvent event;
        while ((event = ColoryrInput.pollKey()) != null) {
            int[] mapped = sdlKey(event.key);
            if (mapped == null) {
                continue;
            }
            Pending pending = new Pending();
            pending.type = event.down ? EVENT_KEY_DOWN : EVENT_KEY_UP;
            pending.keycode = mapped[0];
            pending.scancode = mapped[1];
            pending.down = event.down;
            add(pending);
        }

        while ((event = ColoryrInput.pollMouse()) != null) {
            switch (event.type) {
                case ColoryrInput.EVENT_MOUSE_BUTTON: {
                    if (grabbed) {
                        // 抓取时按键照样要送，位置用最后一次读到的
                    }
                    Pending pending = new Pending();
                    pending.type = event.down ? EVENT_MOUSE_BUTTON_DOWN : EVENT_MOUSE_BUTTON_UP;
                    pending.button = sdlButton(event.key);
                    pending.down = event.down;
                    pending.x = lastX;
                    pending.y = lastY;
                    add(pending);
                    break;
                }
                case ColoryrInput.EVENT_SCROLL: {
                    Pending pending = new Pending();
                    pending.type = EVENT_MOUSE_WHEEL;
                    pending.wheel = event.amount;
                    pending.x = lastX;
                    pending.y = lastY;
                    add(pending);
                    break;
                }
                case ColoryrInput.EVENT_MOUSE_MOVE:
                    if (grabbed) {
                        Pending pending = new Pending();
                        pending.type = EVENT_MOUSE_MOTION;
                        pending.x = lastX;
                        pending.y = lastY;
                        pending.xrel = (float) event.x;
                        pending.yrel = (float) event.y;
                        add(pending);
                    } else {
                        // 光标模式：攒起来，一帧只移动一次系统光标
                        pendingX += event.x;
                        pendingY += event.y;
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private static void add(Pending pending) {
        synchronized (PENDING) {
            // 输入再快也不会积压太多；超出上限丢最旧的，避免卡帧后一次灌爆
            if (PENDING.size() > 256) {
                PENDING.poll();
            }
            PENDING.add(pending);
        }
    }

    private static void updateCursor(long handle, boolean draw) throws Exception {
        getWindowSize.invoke(null, handle, WIN_W, WIN_H);
        getWindowSizeInPixels.invoke(null, handle, FB_W, FB_H);
        int ww = WIN_W.get(0);
        int wh = WIN_H.get(0);
        int pw = FB_W.get(0);
        int ph = FB_H.get(0);
        if (ww <= 0 || wh <= 0 || pw <= 0 || ph <= 0) {
            return;
        }
        ColoryrPackBuilder.setWindowSize(pw, ph);

        if (grabbed) {
            if (cursorHidden) {
                showCursor.invoke(null);
                cursorHidden = false;
            }
            return;
        }

        if (pendingX != 0 || pendingY != 0) {
            getMouseState.invoke(null, MOUSE_X, MOUSE_Y);
            float x = MOUSE_X.get(0) + (float) pendingX;
            float y = MOUSE_Y.get(0) + (float) pendingY;
            pendingX = 0;
            pendingY = 0;
            x = Math.max(0.0F, Math.min(ww - 1.0F, x));
            y = Math.max(0.0F, Math.min(wh - 1.0F, y));
            warpMouse.invoke(null, handle, x, y);
        }

        getMouseState.invoke(null, MOUSE_X, MOUSE_Y);
        lastX = MOUSE_X.get(0);
        lastY = MOUSE_Y.get(0);
        if (!cursorHidden) {
            hideCursor.invoke(null);
            cursorHidden = true;
        }
        if (!draw || !ColoryrCursor.ENABLED) {
            return;
        }
        if (lastX < 0 || lastY < 0 || lastX >= ww || lastY >= wh) {
            return;
        }
        if (renderer == null) {
            renderer = new ColoryrCursorRenderer(loader, true);
        }
        renderer.draw(pw, ph, lastX * pw / ww, lastY * ph / wh,
                ColoryrCursor.SIZE * pw / ww, ColoryrCursor.SIZE * ph / wh);
    }

    /** GLFW 的鼠标按键号转 SDL：0 左、1 右、2 中 */
    private static int sdlButton(int button) {
        if (button == 0) {
            return 1;
        }
        if (button == 1) {
            return 3;
        }
        if (button == 2) {
            return 2;
        }
        return button + 1;
    }

    /**
     * GLFW 键码转 SDL 的 keycode 与 scancode
     *
     * @param key GLFW 键码
     * @return 依次为 keycode、scancode；没有对应项时为 null
     */
    private static int[] sdlKey(int key) {
        if (key >= 'A' && key <= 'Z') {
            int index = key - 'A';
            return new int[]{'a' + index, 4 + index};
        }
        if (key >= '0' && key <= '9') {
            int index = key - '0';
            return new int[]{'0' + index, index == 0 ? 39 : 29 + index};
        }
        if (key >= 320 && key <= 329) {
            int index = key - 320;
            return new int[]{1073741922 - (index == 0 ? 0 : 9 - index), 98 - (index == 0 ? 0 : 9 - index)};
        }
        if (key >= 290 && key <= 301) {
            int index = key - 290;
            return new int[]{1073741882 + index, 58 + index};
        }
        switch (key) {
            case 32: return new int[]{32, 44};                       // SPACE
            case 39: return new int[]{39, 52};                       // APOSTROPHE
            case 44: return new int[]{44, 54};                       // COMMA
            case 45: return new int[]{45, 45};                       // MINUS
            case 46: return new int[]{46, 55};                       // PERIOD
            case 47: return new int[]{47, 56};                       // SLASH
            case 59: return new int[]{59, 51};                       // SEMICOLON
            case 61: return new int[]{61, 46};                       // EQUAL
            case 91: return new int[]{91, 47};                       // LEFT_BRACKET
            case 92: return new int[]{92, 49};                       // BACKSLASH
            case 93: return new int[]{93, 48};                       // RIGHT_BRACKET
            case 96: return new int[]{96, 53};                       // GRAVE
            case 256: return new int[]{27, 41};                      // ESCAPE
            case 257: return new int[]{13, 40};                      // ENTER
            case 258: return new int[]{9, 43};                       // TAB
            case 259: return new int[]{8, 42};                       // BACKSPACE
            case 260: return new int[]{1073741897, 73};              // INSERT
            case 261: return new int[]{127, 76};                     // DELETE
            case 262: return new int[]{1073741903, 79};              // RIGHT
            case 263: return new int[]{1073741904, 80};              // LEFT
            case 264: return new int[]{1073741905, 81};              // DOWN
            case 265: return new int[]{1073741906, 82};              // UP
            case 266: return new int[]{1073741899, 75};              // PAGE_UP
            case 267: return new int[]{1073741902, 78};              // PAGE_DOWN
            case 268: return new int[]{1073741898, 74};              // HOME
            case 269: return new int[]{1073741901, 77};              // END
            case 280: return new int[]{1073741881, 57};              // CAPS_LOCK
            case 281: return new int[]{1073741895, 71};              // SCROLL_LOCK
            case 282: return new int[]{1073741907, 83};              // NUM_LOCK
            case 283: return new int[]{1073741894, 70};              // PRINT_SCREEN
            case 284: return new int[]{1073741896, 72};              // PAUSE
            case 330: return new int[]{1073742044, 220};             // KP_DECIMAL
            case 331: return new int[]{1073741908, 84};              // KP_DIVIDE
            case 332: return new int[]{1073741909, 85};              // KP_MULTIPLY
            case 333: return new int[]{1073741910, 86};              // KP_SUBTRACT
            case 334: return new int[]{1073741911, 87};              // KP_ADD
            case 335: return new int[]{1073741912, 88};              // KP_ENTER
            case 336: return new int[]{1073741927, 103};             // KP_EQUAL
            case 340: return new int[]{1073742049, 225};             // LEFT_SHIFT
            case 341: return new int[]{1073742048, 224};             // LEFT_CONTROL
            case 342: return new int[]{1073742050, 226};             // LEFT_ALT
            case 343: return new int[]{1073742051, 227};             // LEFT_SUPER
            case 344: return new int[]{1073742053, 229};             // RIGHT_SHIFT
            case 345: return new int[]{1073742052, 228};             // RIGHT_CONTROL
            case 346: return new int[]{1073742054, 230};             // RIGHT_ALT
            case 347: return new int[]{1073742055, 231};             // RIGHT_SUPER
            default: return null;
        }
    }

    private static void report(Throwable e) {
        if (errorPrinted) {
            return;
        }
        errorPrinted = true;
        System.out.println("[ColoryrASM] sdl input failure: " + e);
    }

    private static FloatBuffer floats(int size) {
        return ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private static IntBuffer ints(int size) {
        return ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
    }
}
