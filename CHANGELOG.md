# 更新日志

## 1.4.5

### 修复

- **SDL 路径下滚轮无法上下滚动**（26.3+，GL / Vulkan 两条 SDL 窗口路径共用）：`SDL_MouseWheelEvent.set` 的纵向滚动量被误写进了横向字段——`x = wheel`、`y = 0`、`integer_x = wheel`、`integer_y = 0`，游戏收到的 `scrollY` 恒为 0，因此列表、背包等界面的滚轮没有任何反应。
  现在改为 `x = 0`、`y = wheel`、`integer_x = 0`、`integer_y = wheel`，纵向滚动恢复正常，同时不会再误触发横向滚动。
  - 修改：[ColoryrSdl.java](src/main/java/com/coloryr/ColoryrSdl.java)（`injectPoll` 的 `EVENT_MOUSE_WHEEL` 分支）
  - GLFW 与 LWJGL2 路径的参数本来正确，不受影响。

### 说明

- 已用 LWJGL 3.4.3 的 `SDL_MouseWheelEvent` 对修改前后做过反射验证：旧参数为 `x=1, y=0, integer_x=1, integer_y=0`，新参数为 `x=0, y=1, integer_x=0, integer_y=1`。
