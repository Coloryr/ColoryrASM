# 更新日志

## 1.4.7

### 改进

- **手柄指针与系统指针动态切换**：只有最近一次输入来自手柄（启动器下发的鼠标 / 按键 / 滚轮）
  时才隐藏系统光标并绘制软件指针；真实鼠标移动后立刻把系统光标放回来，不再进入鼠标模式后
  一直隐藏系统光标。手柄移动光标产生的 warp 事件会按落点过滤，不会把手柄模式立刻切掉；
  退出抓取模式时按当前光标位置重新建立基线。

## 1.4.6

### 修复

- **关闭游戏后进程不退出**：Netty 的 `NioEventLoopGroup` 使用非 daemon 线程且从未关闭，
  `main` 返回后 JVM 一直停在 `DestroyJavaVM`，26.3 的退出 watchdog 会因此误报
  「Client shutdown from post-main」崩溃。现在事件循环改用 daemon 线程，并在退出时通过
  shutdown hook 关闭连接与线程池。

## 1.4.5

### 修复

- **SDL 路径下滚轮无法上下滚动**（26.3+，GL / Vulkan 两条 SDL 窗口路径共用）：`SDL_MouseWheelEvent.set` 的纵向滚动量被误写进了横向字段——`x = wheel`、`y = 0`、`integer_x = wheel`、`integer_y = 0`，游戏收到的 `scrollY` 恒为 0，因此列表、背包等界面的滚轮没有任何反应。
  现在改为 `x = 0`、`y = wheel`、`integer_x = 0`、`integer_y = wheel`，纵向滚动恢复正常，同时不会再误触发横向滚动。
  - 修改：[ColoryrSdl.java](src/main/java/com/coloryr/ColoryrSdl.java)（`injectPoll` 的 `EVENT_MOUSE_WHEEL` 分支）
  - GLFW 与 LWJGL2 路径的参数本来正确，不受影响。

### 说明

- 已用 LWJGL 3.4.3 的 `SDL_MouseWheelEvent` 对修改前后做过反射验证：旧参数为 `x=1, y=0, integer_x=1, integer_y=0`，新参数为 `x=0, y=1, integer_x=0, integer_y=1`。
