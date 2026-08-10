# Captcha Slider Update Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把上游滑块的关键更新移植到现有 Java Playwright 闭环，使自动和人工拖动都发送正确鼠标增量，并在自动失败后提供一键人工兜底。

**Architecture:** 在现有 `PlaywrightCaptchaBrowserRunner` 内保留任务和页面状态机，新增一个只负责 CDP/Playwright 鼠标发送的包内类。自动轨迹继续由 Runner 调度并轮换三种策略，前端复用现有弹窗和接口，只增加终态兜底按钮与响应式布局。

**Tech Stack:** Java 21、Microsoft Playwright Java 1.61、Chrome DevTools Protocol、Vue 3、TypeScript

---

## 文件结构

- 新建 `src/main/java/com/xianyusmart/service/captcha/CaptchaDragMouse.java`：CDP 鼠标事件与 Playwright 降级。
- 修改 `src/main/java/com/xianyusmart/service/captcha/PlaywrightCaptchaBrowserRunner.java`：指纹、页面状态、三轨迹、重试和人工轨迹。
- 修改 `vue-code/src/views/connection/components/CaptchaGuideDialog.vue`：自动失败后一键转人工和 Cookie 入口。
- 临时创建 `src/test/java/com/xianyusmart/service/captcha/CaptchaDragMouseTempTest.java`、`CaptchaTrajectoryTempTest.java` 和 `CaptchaStateTempTest.java`，验证后删除。

### Task 1: CDP 增量鼠标事件

**Files:**
- Create: `src/main/java/com/xianyusmart/service/captcha/CaptchaDragMouse.java`
- Modify: `src/main/java/com/xianyusmart/service/captcha/PlaywrightCaptchaBrowserRunner.java`
- Test: `src/test/java/com/xianyusmart/service/captcha/CaptchaDragMouseTempTest.java`

- [ ] **Step 1: 编写失败测试**

在本地受控页面监听 `mousemove` 的 `movementX`、`movementY`，调用待实现 CDP 鼠标后断言拖动中至少一个增量非零：

```java
assertThat(events).anyMatch(event -> event.movementX() != 0 || event.movementY() != 0);
```

同时验证 CDP 创建异常时调用 Playwright `Mouse` 降级路径。

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -Dtest=CaptchaDragMouseTempTest test`

Expected: FAIL，`CaptchaDragMouse` 尚不存在。

- [ ] **Step 3: 实现 CDP 鼠标**

包内类通过 `CDPSession.send` 发送：

```java
JsonObject params = new JsonObject();
params.addProperty("type", type);
params.addProperty("x", x);
params.addProperty("y", y);
params.addProperty("deltaX", roundDelta(x - previousX));
params.addProperty("deltaY", roundDelta(y - previousY));
params.addProperty("button", "left");
params.addProperty("buttons", pressed ? 1 : 0);
params.addProperty("modifiers", 0);
params.addProperty("timestamp", 0);
session.send("Input.dispatchMouseEvent", params);
```

`move` 支持线性插值，`close` 分离 CDP 会话。Runner 的自动拖动和 `replayManualDrag` 都使用该类。

- [ ] **Step 4: 运行测试并确认通过**

Run: `mvn -Dtest=CaptchaDragMouseTempTest test`

Expected: PASS。

- [ ] **Step 5: 提交 CDP 鼠标改动**

```powershell
git add src/main/java/com/xianyusmart/service/captcha/CaptchaDragMouse.java src/main/java/com/xianyusmart/service/captcha/PlaywrightCaptchaBrowserRunner.java
git commit -m "fix: send captcha drag through cdp"
```

### Task 2: 三轨迹轮换与边界

**Files:**
- Modify: `src/main/java/com/xianyusmart/service/captcha/PlaywrightCaptchaBrowserRunner.java`
- Test: `src/test/java/com/xianyusmart/service/captcha/CaptchaTrajectoryTempTest.java`

- [ ] **Step 1: 编写失败测试**

固定随机源后验证尝试次数到策略映射以及距离边界：

```java
assertThat(strategyForAttempt(1)).isEqualTo(IN_CONTAINER);
assertThat(strategyForAttempt(2)).isEqualTo(OUT_OF_CONTAINER);
assertThat(strategyForAttempt(3)).isEqualTo(MINIMUM_JERK);
assertThat(minimumJerkPosition(0)).isZero();
assertThat(minimumJerkPosition(1)).isEqualTo(1);
assertThat(calculateDistance(500, 40)).isEqualTo(360);
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -Dtest=CaptchaTrajectoryTempTest test`

Expected: FAIL，三种策略和最小急动度函数尚不存在。

- [ ] **Step 3: 实现三种轨迹**

增加 `DragStrategy`，按 `attempt % 3` 轮换。最小急动度核心函数：

```java
static double minimumJerkPosition(double progress) {
    double t = Math.max(0, Math.min(1, progress));
    return 10 * Math.pow(t, 3) - 15 * Math.pow(t, 4) + 6 * Math.pow(t, 5);
}
```

容器内轨迹使用 45 至 75 个主步骤；容器外轨迹加入 2 至 3 个 Y 偏移拐点；最小急动度轨迹使用 100 至 140 个采样点。三种轨迹都通过 `CaptchaDragMouse` 发送，确保异常时释放鼠标。

- [ ] **Step 4: 增加拖动前有限行为**

拖动前随机移动 2 至 3 次，再移动到滑块附近并等待 800 至 2000 毫秒。所有等待使用现有任务截止时间约束，避免超过五分钟总超时。

- [ ] **Step 5: 运行测试并确认通过**

Run: `mvn -Dtest=CaptchaTrajectoryTempTest test`

Expected: PASS。

- [ ] **Step 6: 提交轨迹改动**

```powershell
git add src/main/java/com/xianyusmart/service/captcha/PlaywrightCaptchaBrowserRunner.java
git commit -m "feat: rotate captcha drag trajectories"
```

### Task 3: 浏览器环境与状态恢复

**Files:**
- Modify: `src/main/java/com/xianyusmart/service/captcha/PlaywrightCaptchaBrowserRunner.java`
- Test: `src/test/java/com/xianyusmart/service/captcha/CaptchaStateTempTest.java`

- [ ] **Step 1: 编写失败测试**

覆盖加载失败、登录页、明确失败和普通滑块容器：

```java
assertThat(isPageLoadFailure("chrome-error://chromewebdata/")).isTrue();
assertThat(isPageLoadFailure("https://www.goofish.com/im")).isFalse();
assertThat(isPunishUrl("https://foo/_____tmd_____/punish")).isTrue();
assertThat(isExplicitRetryText("验证失败，点击框体重试")).isTrue();
assertThat(isExplicitRetryText("请稍后重试其他操作")).isFalse();
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -Dtest=CaptchaStateTempTest test`

Expected: FAIL，新状态函数尚不存在。

- [ ] **Step 3: 补齐指纹一致性**

在现有初始化脚本中只补充缺失属性：`platform`、`vendor`、`appVersion`、`userAgentData` 和 Canvas 微扰动；保持现有 plugins、permissions、WebGL 和硬件信息逻辑。

- [ ] **Step 4: 实现稳定等待和误判防护**

增加页面加载失败判断、punish frame 判断、连续无验证码确认和短失败文案匹配。`.nc-lang-cnt` 只可作为按钮候选，不能作为失败选择器。

- [ ] **Step 5: 实现失败恢复**

明确失败时优先点击重试容器；需要刷新或页面失效时复用现有 `reopenFromHome`。重试前从当前上下文删除以下本轮风险 Cookie，再保留其余登录 Cookie：

```java
Set.of("x5secdata", "x5sec", "x5sectag", "x5pref",
        "bx-cookie-test", "tfstk", "cbc", "sca", "isg")
```

最多五次自动尝试，任何额外页面恢复都不能形成无限循环。

- [ ] **Step 6: 运行测试并确认通过**

Run: `mvn -Dtest=CaptchaStateTempTest test`

Expected: PASS。

- [ ] **Step 7: 提交状态恢复改动**

```powershell
git add src/main/java/com/xianyusmart/service/captcha/PlaywrightCaptchaBrowserRunner.java
git commit -m "fix: harden captcha state recovery"
```

### Task 4: 自动失败后的人工兜底 UI

**Files:**
- Modify: `vue-code/src/views/connection/components/CaptchaGuideDialog.vue`

- [ ] **Step 1: 增加终态派生状态**

```ts
const autoFallbackVisible = computed(() =>
  taskStatus.value?.mode === 'AUTO'
  && ['FAILED', 'TIMEOUT', 'UNSUPPORTED'].includes(taskStatus.value.status))
```

- [ ] **Step 2: 实现一键转人工**

按钮点击后把模式切换为 `MANUAL_BROWSER` 并复用现有 `handleAction` 启动新任务。Cookie Session 过期时保留 Cookie 引导，并将人工按钮降为次要选项。

- [ ] **Step 3: 完善响应式布局**

终态操作区桌面端右对齐，窄屏改为纵向满宽按钮；人工画面保持比例，触控拖动逻辑不变。

- [ ] **Step 4: 执行前端验证**

Run: `npm run type-check`

Expected: PASS。

Run: `npm run build:spring`

Expected: PASS。

- [ ] **Step 5: 提交 UI 改动**

```powershell
git add vue-code/src/views/connection/components/CaptchaGuideDialog.vue src/main/resources/static
git commit -m "feat: add captcha manual fallback"
```

### Task 5: 清理临时测试并整体复验

**Files:**
- Delete: `src/test/java/com/xianyusmart/service/captcha/CaptchaDragMouseTempTest.java`
- Delete: `src/test/java/com/xianyusmart/service/captcha/CaptchaTrajectoryTempTest.java`
- Delete: `src/test/java/com/xianyusmart/service/captcha/CaptchaStateTempTest.java`

- [ ] **Step 1: 删除临时测试产物**

只删除本计划创建的临时测试和本地受控页面，不删除项目原有文件。

- [ ] **Step 2: 运行后端完整验证**

Run: `mvn test`

Expected: BUILD SUCCESS。

Run: `mvn -DskipTests package`

Expected: BUILD SUCCESS。

- [ ] **Step 3: 运行前端完整验证**

Run: `npm run type-check`

Expected: PASS。

Run: `npm run build:spring`

Expected: PASS。

- [ ] **Step 4: 检查资源和敏感信息**

Run: `git diff --check`

Expected: 无输出。

Run: `git status --short`

Expected: 不包含临时测试、截图、Cookie 或验证地址文件。
