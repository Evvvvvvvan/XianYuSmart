# 滑块全自动闭环与服务器人工拖动 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让生产 Docker 中的全自动滑块形成平台复验闭环，并让人工模式直接在管理后台显示服务器浏览器画面和提交拖动轨迹。

**Architecture:** 继续复用现有短生命周期 Java Playwright 任务。浏览器工作线程生成内存 JPEG 快照并消费有界拖动命令队列，HTTP 线程只读取不可变快照和入队归一化轨迹，避免跨线程调用 Playwright；自动模式补齐加载态、刮刮乐类型、失败原因和平台凭证复验。

**Tech Stack:** Java 21、Spring Boot 3.5、Playwright Java、Vue 3、TypeScript、现有 Maven/前端构建脚本。

---

## 文件结构

- Modify: `src/main/java/com/xianyusmart/service/CaptchaSolveService.java`
  - 定义人工画面、轨迹点和服务方法。
- Modify: `src/main/java/com/xianyusmart/service/captcha/CaptchaBrowserRunner.java`
  - 定义浏览器执行器的人工画面读取和轨迹提交契约。
- Modify: `src/main/java/com/xianyusmart/service/captcha/PlaywrightCaptchaBrowserRunner.java`
  - 实现无桌面人工模式、内存截图、轨迹队列、自动加载态和刮刮乐识别。
- Modify: `src/main/java/com/xianyusmart/service/impl/CaptchaSolveServiceImpl.java`
  - 校验人工任务状态，转发画面和轨迹，细化凭证复验进度及失败原因。
- Modify: `src/main/java/com/xianyusmart/controller/WebSocketController.java`
  - 增加人工画面和轨迹接口并复用租户归属校验。
- Modify: `vue-code/src/api/websocket.ts`
  - 增加人工画面、轨迹类型和 API。
- Modify: `vue-code/src/views/connection/components/CaptchaGuideDialog.vue`
  - 显示服务器浏览器画面，记录 Pointer Events 轨迹并提交。
- Temporary Test: `src/test/java/com/xianyusmart/service/captcha/PlaywrightCaptchaManualSessionTempTest.java`
  - 只在 TDD 阶段存在，验证轨迹和画面会话规则，完成后删除。
- Temporary Test: `src/test/java/com/xianyusmart/service/impl/CaptchaSolveManualTempTest.java`
  - 只在 TDD 阶段存在，验证任务状态和复验原因，完成后删除。

### Task 1: 定义人工交互契约

**Files:**
- Modify: `src/main/java/com/xianyusmart/service/CaptchaSolveService.java`
- Modify: `src/main/java/com/xianyusmart/service/captcha/CaptchaBrowserRunner.java`
- Test: `src/test/java/com/xianyusmart/service/impl/CaptchaSolveManualTempTest.java`

- [ ] **Step 1: 写失败测试**

测试构造 `MANUAL_BROWSER/RUNNING/WAITING_MANUAL` 任务，断言服务能够读取画面并接受轨迹；自动任务和终态任务必须抛出 `IllegalStateException`。

```java
@Test
void manualInteractionRequiresActiveManualTask() {
    CaptchaSolveService.ManualDrag drag = new CaptchaSolveService.ManualDrag(
            1L,
            List.of(
                    new CaptchaSolveService.DragPoint(0.1, 0.5, 0),
                    new CaptchaSolveService.DragPoint(0.8, 0.5, 600)));
    assertThrows(IllegalStateException.class,
            () -> service.submitManualDrag(1L, drag));
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
& '.\.agents\skills\operating-xianyusmart\scripts\project.ps1' -Task test
```

Expected: 编译失败，提示 `ManualDrag`、`DragPoint` 或 `submitManualDrag` 尚不存在。

- [ ] **Step 3: 增加最小接口**

在 `CaptchaSolveService` 增加：

```java
record ManualFrame(long version, int width, int height,
                   long updatedAt, String imageBase64) {
}

record DragPoint(double x, double y, long elapsedMs) {
}

record ManualDrag(long frameVersion, java.util.List<DragPoint> points) {
}

ManualFrame getManualFrame(Long accountId);

TaskView submitManualDrag(Long accountId, ManualDrag drag);
```

在 `CaptchaBrowserRunner` 增加默认契约：

```java
default CaptchaSolveService.ManualFrame getManualFrame(Long accountId) {
    return null;
}

default void submitManualDrag(Long accountId, CaptchaSolveService.ManualDrag drag) {
    throw new IllegalStateException("人工浏览器尚未准备完成");
}
```

- [ ] **Step 4: 运行测试确认接口可编译**

Run:

```powershell
& '.\.agents\skills\operating-xianyusmart\scripts\project.ps1' -Task test
```

Expected: 接口编译通过；测试继续因服务尚未实现人工任务校验而失败。

### Task 2: 实现浏览器内存画面和人工轨迹队列

**Files:**
- Modify: `src/main/java/com/xianyusmart/service/captcha/PlaywrightCaptchaBrowserRunner.java`
- Test: `src/test/java/com/xianyusmart/service/captcha/PlaywrightCaptchaManualSessionTempTest.java`

- [ ] **Step 1: 写失败测试**

测试覆盖：

```java
@Test
void validatesNormalizedManualDrag() {
    assertThrows(IllegalArgumentException.class, () -> runner.validateManualDrag(
            new CaptchaSolveService.ManualDrag(1L, List.of(
                    new CaptchaSolveService.DragPoint(-0.1, 0.5, 0),
                    new CaptchaSolveService.DragPoint(0.8, 0.5, 300)))));
}

@Test
void scratchCaptchaUsesPartialTrackDistance() {
    double distance = PlaywrightCaptchaBrowserRunner.calculateDistance(300, 40, true, 0.3);
    assertEquals(78, distance, 0.001);
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
& '.\.agents\skills\operating-xianyusmart\scripts\project.ps1' -Task test
```

Expected: 编译失败，提示人工轨迹校验和刮刮乐距离方法不存在。

- [ ] **Step 3: 扩展浏览器会话**

`BrowserProcessSession` 增加有界队列和不可变画面：

```java
private final java.util.concurrent.ArrayBlockingQueue<CaptchaSolveService.ManualDrag>
        manualDrags = new java.util.concurrent.ArrayBlockingQueue<>(1);
private final java.util.concurrent.atomic.AtomicLong frameVersion =
        new java.util.concurrent.atomic.AtomicLong();
private volatile CaptchaSolveService.ManualFrame manualFrame;
```

实现：

```java
@Override
public CaptchaSolveService.ManualFrame getManualFrame(Long accountId) {
    BrowserProcessSession session = activeBrowserSessions.get(accountId);
    return session == null ? null : session.manualFrame;
}

@Override
public void submitManualDrag(Long accountId, CaptchaSolveService.ManualDrag drag) {
    validateManualDrag(drag);
    BrowserProcessSession session = activeBrowserSessions.get(accountId);
    if (session == null || session.manualFrame == null) {
        throw new IllegalStateException("人工浏览器尚未准备完成");
    }
    if (drag.frameVersion() > session.manualFrame.version()
            || session.manualFrame.version() - drag.frameVersion() > 5) {
        throw new IllegalStateException("浏览器画面已更新，请重新拖动");
    }
    if (!session.manualDrags.offer(drag)) {
        throw new IllegalStateException("上一次拖动正在执行");
    }
}
```

- [ ] **Step 4: 让人工模式在无桌面环境启动**

删除人工模式的 `UNSUPPORTED` 前置返回。浏览器启动方式改为：

```java
boolean automatic = mode == CaptchaSolveService.Mode.AUTO;
boolean headless = automatic || !hasInteractiveDesktop();
Browser browser = browserType.launch(browserLaunchOptions(browserType, headless));
if (automatic || headless) {
    applyFingerprint(context);
}
```

`browserLaunchOptions` 参数语义改成 `headless`，只负责传递实际启动模式。

- [ ] **Step 5: 在浏览器线程生成画面并重放轨迹**

人工等待循环每 700 毫秒截图一次：

```java
byte[] image = page.screenshot(new Page.ScreenshotOptions()
        .setType(com.microsoft.playwright.options.ScreenshotType.JPEG)
        .setQuality(60));
long version = session.frameVersion.incrementAndGet();
session.manualFrame = new CaptchaSolveService.ManualFrame(
        version, VIEWPORT_WIDTH, VIEWPORT_HEIGHT,
        System.currentTimeMillis(),
        java.util.Base64.getEncoder().encodeToString(image));
```

只由浏览器线程消费队列并重放：

```java
CaptchaSolveService.ManualDrag drag = session.manualDrags.poll();
if (drag != null) {
    replayManualDrag(page, drag, VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
    if (waitForCaptchaGone(page,
            Math.min(deadline, System.currentTimeMillis() + 10_000))) {
        return new RunResult(Outcome.SOLVED, null, "滑块验证完成");
    }
}
```

`replayManualDrag` 把归一化坐标乘以视口宽高，按 `elapsedMs` 差值执行 `move/down/move/up`，单点等待上限 120 毫秒，总轨迹上限 10 秒。

- [ ] **Step 6: 运行测试确认通过**

Run:

```powershell
& '.\.agents\skills\operating-xianyusmart\scripts\project.ps1' -Task test
```

Expected: 人工轨迹校验和刮刮乐距离测试通过。

### Task 3: 补齐自动识别与平台复验原因

**Files:**
- Modify: `src/main/java/com/xianyusmart/service/captcha/PlaywrightCaptchaBrowserRunner.java`
- Modify: `src/main/java/com/xianyusmart/service/impl/CaptchaSolveServiceImpl.java`
- Test: `src/test/java/com/xianyusmart/service/impl/CaptchaSolveManualTempTest.java`

- [ ] **Step 1: 写失败测试**

增加测试：

```java
@Test
void platformStillRequiresCaptchaIsNotReportedAsReconnectFailure() {
    when(webSocketService.restartAfterCredentialUpdate(1L)).thenReturn(false);
    when(tokenService.getPendingCaptchaUrl(1L)).thenReturn("https://h5api.m.goofish.com/punish");
    service.runCompletedBrowserResultForTest(1L, solvedResult);
    assertEquals("平台仍要求滑块验证", service.getStatus(1L).message());
}
```

临时测试通过包内可见辅助入口驱动既有私有流程；实现完成后辅助入口随测试一起删除。

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
& '.\.agents\skills\operating-xianyusmart\scripts\project.ps1' -Task test
```

Expected: 当前实现只返回“凭证更新或重新连接失败”。

- [ ] **Step 3: 增加验证码容器和刮刮乐选择器**

```java
private static final List<String> CAPTCHA_CONTAINER_SELECTORS = List.of(
        "#nc_1_wrapper", "#nc_1", "#nocaptcha", ".nc-container",
        ".nc_wrapper", "#baxia-dialog", ".J_MIDDLEWARE_FRAME",
        "#scratch-captcha", ".scratch-captcha-slider",
        "[class*='captcha']", "[class*='slider']");
```

按钮增加 `#scratch-captcha-btn`、`.scratch-captcha-slider .button`，轨道增加 `#nc_1_n1t`、`[class*='scale']`。检测到页面包含 `scratch-captcha` 或对应英文提示时，只拖动轨道有效距离的 25% 至 35%。

- [ ] **Step 4: 区分容器加载和滑块缺失**

`waitForSlider` 检测到容器但按钮未出现时报告：

```java
reportProgress(progress, "WAITING_SLIDER",
        "第" + attempt + "次：验证组件已出现，正在等待滑块加载", attempt);
```

等待结束后返回：

```java
return new RunResult(Outcome.FAILED, null,
        "验证组件已出现，但滑块按钮未加载");
```

- [ ] **Step 5: 增加平台凭证复验阶段**

Cookie 更新后、重连前更新阶段：

```java
updateProgress(control, new CaptchaBrowserRunner.ProgressUpdate(
        "VALIDATING_CREDENTIAL", "正在确认平台已放行新凭证",
        control.attempt, control.maxAttempts));
```

`restartAfterCredentialUpdate` 返回 `false` 后读取 `tokenService.getPendingCaptchaUrl(accountId)`：

```java
String pendingCaptchaUrl = tokenService.getPendingCaptchaUrl(task.xianyuAccountId());
complete(control, Status.FAILED,
        pendingCaptchaUrl == null || pendingCaptchaUrl.isBlank()
                ? "凭证已更新，但WebSocket重新连接失败"
                : "滑块操作完成，但平台仍要求验证");
```

- [ ] **Step 6: 运行测试确认通过**

Run:

```powershell
& '.\.agents\skills\operating-xianyusmart\scripts\project.ps1' -Task test
```

Expected: 加载态、刮刮乐距离和平台复验原因测试通过。

### Task 4: 增加租户受控的人工交互接口

**Files:**
- Modify: `src/main/java/com/xianyusmart/service/impl/CaptchaSolveServiceImpl.java`
- Modify: `src/main/java/com/xianyusmart/controller/WebSocketController.java`

- [ ] **Step 1: 实现服务状态校验**

```java
private TaskView requireManualTask(Long accountId) {
    TaskView task = tasks.get(accountId);
    if (!isActive(task) || task.mode() != Mode.MANUAL_BROWSER) {
        throw new IllegalStateException("当前没有运行中的人工滑块任务");
    }
    return task;
}
```

`getManualFrame` 和 `submitManualDrag` 先调用该方法，再转发给 `captchaBrowserRunner`。画面为空时返回“人工浏览器画面正在生成”。

- [ ] **Step 2: 增加请求 DTO**

在 `WebSocketController` 现有 DTO 区增加：

```java
@Data
public static class CaptchaManualDragReqDTO {
    private Long xianyuAccountId;
    private Long frameVersion;
    private List<CaptchaManualPointDTO> points;
}

@Data
public static class CaptchaManualPointDTO {
    private Double x;
    private Double y;
    private Long elapsedMs;
}
```

- [ ] **Step 3: 增加两个接口**

两个接口都复用：

```java
if (xianyuAccountMapper.selectById(accountId) == null) {
    return ResultObject.failed("账号不存在");
}
```

路径：

```java
@PostMapping("/captcha/manual/frame")
public ResultObject<CaptchaSolveService.ManualFrame> getCaptchaManualFrame(...)

@PostMapping("/captcha/manual/drag")
public ResultObject<CaptchaSolveService.TaskView> submitCaptchaManualDrag(...)
```

轨迹 DTO 映射为 `CaptchaSolveService.DragPoint`，不接受客户端传入 URL、Cookie 或页面尺寸。

- [ ] **Step 4: 运行 Java 测试**

Run:

```powershell
& '.\.agents\skills\operating-xianyusmart\scripts\project.ps1' -Task test
```

Expected: Java 21 测试通过。

### Task 5: 在当前弹窗实现服务器人工拖动

**Files:**
- Modify: `vue-code/src/api/websocket.ts`
- Modify: `vue-code/src/views/connection/components/CaptchaGuideDialog.vue`

- [ ] **Step 1: 增加前端类型和 API**

```typescript
export interface CaptchaManualFrame {
  version: number;
  width: number;
  height: number;
  updatedAt: number;
  imageBase64: string;
}

export interface CaptchaDragPoint {
  x: number;
  y: number;
  elapsedMs: number;
}

export function getCaptchaManualFrame(accountId: number) {
  return request<CaptchaManualFrame>({
    url: '/websocket/captcha/manual/frame',
    method: 'POST',
    data: { xianyuAccountId: accountId },
    silent: true
  });
}

export function submitCaptchaManualDrag(
  accountId: number,
  frameVersion: number,
  points: CaptchaDragPoint[]
) {
  return request<CaptchaTaskStatus>({
    url: '/websocket/captcha/manual/drag',
    method: 'POST',
    data: { xianyuAccountId: accountId, frameVersion, points }
  });
}
```

- [ ] **Step 2: 增加人工画面状态**

```typescript
const manualFrame = ref<CaptchaManualFrame | null>(null);
const dragPoints = ref<CaptchaDragPoint[]>([]);
const dragStartedAt = ref(0);
const dragging = ref(false);
let frameTimer: ReturnType<typeof setTimeout> | null = null;
```

仅在 `MANUAL_BROWSER` 且任务运行时轮询画面；弹窗关闭、任务结束和组件卸载时清理定时器。

- [ ] **Step 3: 记录归一化 Pointer Events**

```typescript
function normalizedPoint(event: PointerEvent, element: HTMLElement): CaptchaDragPoint {
  const rect = element.getBoundingClientRect();
  return {
    x: Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width)),
    y: Math.min(1, Math.max(0, (event.clientY - rect.top) / rect.height)),
    elapsedMs: Date.now() - dragStartedAt.value
  };
}
```

`pointerdown` 捕获指针并写入首点，`pointermove` 最多每 16 毫秒记录一个点，`pointerup` 写入终点后一次提交；不足两个点不提交。

- [ ] **Step 4: 显示人工画面**

```vue
<div
  v-if="taskStatus?.mode === 'MANUAL_BROWSER' && taskRunning"
  ref="manualViewport"
  class="manual-browser"
  @pointerdown="handlePointerDown"
  @pointermove="handlePointerMove"
  @pointerup="handlePointerUp"
  @pointercancel="resetDrag"
>
  <img
    v-if="manualFrame"
    :src="`data:image/jpeg;base64,${manualFrame.imageBase64}`"
    alt="服务器滑块验证页面"
    draggable="false"
  >
  <span v-else>正在加载服务器验证画面…</span>
</div>
```

容器设置 `touch-action: none`、固定最大高度、保持图片比例；人工说明改成“生产服务器可直接在下方画面拖动”。

- [ ] **Step 5: 增加阶段名称**

```typescript
WAITING_CAPTCHA: '等待验证组件',
WAITING_SLIDER: '等待滑块加载',
CLICKING_RETRY: '重置验证组件',
CAPTURING_MANUAL_FRAME: '同步人工画面',
REPLAYING_MANUAL_DRAG: '执行人工拖动',
VALIDATING_CREDENTIAL: '确认平台凭证'
```

- [ ] **Step 6: 运行前端验证**

Run:

```powershell
& '.\.agents\skills\operating-xianyusmart\scripts\project.ps1' -Task frontend
```

Expected: TypeScript 检查与生产构建通过。

### Task 6: 删除临时测试并执行正式验证

**Files:**
- Delete: `src/test/java/com/xianyusmart/service/captcha/PlaywrightCaptchaManualSessionTempTest.java`
- Delete: `src/test/java/com/xianyusmart/service/impl/CaptchaSolveManualTempTest.java`

- [ ] **Step 1: 删除本次临时测试**

使用 `apply_patch` 删除两份临时测试，保留项目原有测试不变。

- [ ] **Step 2: 重新运行正式 Java 验证**

Run:

```powershell
& '.\.agents\skills\operating-xianyusmart\scripts\project.ps1' -Task test
```

Expected: Java 21 测试通过。

- [ ] **Step 3: 重新运行前端正式验证**

Run:

```powershell
& '.\.agents\skills\operating-xianyusmart\scripts\project.ps1' -Task frontend
```

Expected: TypeScript 检查和生产构建通过。

- [ ] **Step 4: 审核差异和敏感信息**

Run:

```powershell
git diff --check
git diff --stat
rg -n "t=[0-9a-f]{20,}|cookie2=|_m_h5_tk=|x5sec=" src vue-code docs/superpowers/plans/2026-07-31-captcha-auto-and-remote-manual.md
git status --short
```

Expected: 无空白错误、无凭据、无临时测试，只包含本次文件和用户原有 `README.md`、`DISCLAIMER.md` 修改。

- [ ] **Step 5: 打包一次**

Run:

```powershell
& '.\.agents\skills\operating-xianyusmart\scripts\project.ps1' -Task package
```

Expected: Java 21 正式 JAR 构建成功，后续部署复用同一产物。

### Task 7: 提交、推送、部署和生产闭环

**Files:**
- Commit only the files listed in this plan.

- [ ] **Step 1: 提交并推送**

```powershell
git add -- src/main/java/com/xianyusmart/service/CaptchaSolveService.java src/main/java/com/xianyusmart/service/captcha/CaptchaBrowserRunner.java src/main/java/com/xianyusmart/service/captcha/PlaywrightCaptchaBrowserRunner.java src/main/java/com/xianyusmart/service/impl/CaptchaSolveServiceImpl.java src/main/java/com/xianyusmart/controller/WebSocketController.java vue-code/src/api/websocket.ts vue-code/src/views/connection/components/CaptchaGuideDialog.vue docs/superpowers/plans/2026-07-31-captcha-auto-and-remote-manual.md
git commit -m "feat: close captcha verification loop"
git push origin main
```

Expected: 不提交 `README.md` 和 `DISCLAIMER.md`。

- [ ] **Step 2: 使用部署 Skill 复用已验证 JAR**

部署前完整读取 `updating-xianyusmart`，使用其 `-SkipBuild` 路径上传本次已验证产物、备份旧 JAR、滚动重启并检查健康状态。

- [ ] **Step 3: 生产全自动验收**

通过正常连接入口生成新挑战，确认：

- 阶段持续更新且不永久停留在 `RUNNING`。
- 自动成功时 Cookie、Token 和 WebSocket 全部恢复。
- 平台未接受时在超时前进入明确失败终态。

- [ ] **Step 4: 生产人工验收**

选择人工模式，确认：

- 当前弹窗显示服务器验证页面。
- 鼠标或触摸拖动能提交到服务器浏览器。
- 完成后自动回收 Cookie、复验 Token 和恢复 WebSocket。

- [ ] **Step 5: 运行状态验收**

确认容器健康、重启次数未增加、无 OOM、内存符合现有限制、公网页面 HTTP 200。自动和人工任一入口没有形成真实平台放行闭环时继续修复，不报告完成。
