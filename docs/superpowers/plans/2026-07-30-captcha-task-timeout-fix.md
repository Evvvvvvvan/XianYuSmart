# Captcha Task Timeout Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复生产滑块任务永久 `RUNNING`，提供可中断硬超时、主动取消和不间断的真实阶段进度。

**Architecture:** 保留现有 Java Playwright 执行器和轮询接口。修复临时目录清理后，在 `CaptchaSolveServiceImpl` 中用 `FutureTask`、现有调度器和任务标识控制生命周期；Playwright 进度通过回调写入任务状态，独立心跳在浏览器阻塞时继续更新时间和原因。前端每 2 秒同步服务端状态、每秒刷新时间，并提供取消入口。

**Tech Stack:** Java 21、Spring Boot 3.5、Playwright Java 1.40、JUnit 5、Mockito、Vue 3、TypeScript、Vite。

---

## 文件结构

- Modify: `src/main/java/com/xianyusmart/config/PlaywrightManager.java`
  - 防止活跃或未过期 Playwright 临时目录被清理。
- Modify: `src/main/java/com/xianyusmart/service/CaptchaSolveService.java`
  - 增加 `CANCELLED`、进度字段和取消方法。
- Modify: `src/main/java/com/xianyusmart/service/captcha/CaptchaBrowserRunner.java`
  - 定义安全的阶段进度回调。
- Modify: `src/main/java/com/xianyusmart/service/captcha/PlaywrightCaptchaBrowserRunner.java`
  - 在真实浏览器阶段和每次拖动时上报进度。
- Modify: `src/main/java/com/xianyusmart/service/impl/CaptchaSolveServiceImpl.java`
  - 保存执行句柄、硬超时、心跳、取消和任务写回保护。
- Modify: `src/main/java/com/xianyusmart/controller/WebSocketController.java`
  - 增加租户归属校验后的取消接口。
- Modify: `vue-code/src/api/websocket.ts`
  - 对齐进度字段、`CANCELLED` 和取消 API。
- Modify: `vue-code/src/views/connection/components/CaptchaGuideDialog.vue`
  - 恢复活动任务、实时显示阶段/时间/原因并支持取消。
- Temporary test: `src/test/java/com/xianyusmart/config/PlaywrightManagerCleanupTest.java`
- Temporary test: `src/test/java/com/xianyusmart/service/impl/CaptchaSolveServiceImplLifecycleTest.java`
- Temporary test: `src/test/java/com/xianyusmart/service/captcha/PlaywrightCaptchaBrowserRunnerProgressTest.java`
- Temporary test: `src/test/java/com/xianyusmart/controller/WebSocketControllerCaptchaCancelTest.java`

### Task 1: 修复 Playwright 临时目录清理

- [ ] **Step 1: 写未过期目录和活动浏览器保护的失败测试**

```java
@Test
void keepsRecentPlaywrightDirectory() throws Exception {
    Path directory = Files.createDirectory(tempDir.resolve("playwright-java-active"));
    String previous = System.getProperty("java.io.tmpdir");
    System.setProperty("java.io.tmpdir", tempDir.toString());
    try {
        new PlaywrightManager().cleanTempFiles();
        assertTrue(Files.exists(directory));
    } finally {
        System.setProperty("java.io.tmpdir", previous);
    }
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```powershell
$env:JAVA_HOME='E:\java\jdk21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -Dtest=PlaywrightManagerCleanupTest test
```

Expected: FAIL，当前实现删除 `playwright-java-active`。

- [ ] **Step 3: 对目录和文件统一应用过期判断，并在共享浏览器活动时跳过**

```java
public void cleanTempFiles() {
    if (isInitialized()) {
        log.debug("Playwright浏览器运行中，跳过临时文件清理");
        return;
    }
    // 路径达到过期阈值后才允许删除，避免破坏运行中的驱动通信。
    long fileAge = now - Files.getLastModifiedTime(path).toMillis();
    if (fileAge <= thresholdMs) {
        return;
    }
    if (file.isDirectory()) {
        deleteDirectory(file);
    } else {
        file.delete();
    }
}
```

- [ ] **Step 4: 运行测试并确认 GREEN**

Run: `.\mvnw.cmd -Dtest=PlaywrightManagerCleanupTest test`

Expected: PASS。

### Task 2: 增加任务硬超时、取消和写回保护

- [ ] **Step 1: 写阻塞任务超时、取消、模式冲突和旧任务写回保护的失败测试**

```java
@Test
void cancelInterruptsBlockedRunnerAndKeepsCancelledStatus() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean();
    when(runner.run(anyLong(), any(), anyString(), anyString(), any())).thenAnswer(invocation -> {
        entered.countDown();
        try {
            Thread.sleep(TimeUnit.MINUTES.toMillis(10));
        } catch (InterruptedException e) {
            interrupted.set(true);
            Thread.currentThread().interrupt();
        }
        return new RunResult(Outcome.FAILED, null, "浏览器滑块验证失败");
    });

    service.start(1L, Mode.AUTO);
    assertTrue(entered.await(3, TimeUnit.SECONDS));
    TaskView cancelled = service.cancel(1L);

    assertEquals(Status.CANCELLED, cancelled.status());
    for (int index = 0; index < 30 && !interrupted.get(); index++) {
        Thread.sleep(100);
    }
    assertTrue(interrupted.get());
    assertEquals(Status.CANCELLED, service.getStatus(1L).status());
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run: `.\mvnw.cmd -Dtest=CaptchaSolveServiceImplLifecycleTest test`

Expected: 编译失败或行为失败，因为没有取消、执行句柄和写回保护。

- [ ] **Step 3: 扩展任务状态**

```java
enum Status {
    PENDING, RUNNING, SUCCEEDED, FAILED, TIMEOUT, UNSUPPORTED, CANCELLED
}

record TaskView(Long xianyuAccountId, Mode mode, Status status,
                String message, String phase, int attempt, int maxAttempts,
                long startedAt, long updatedAt, long deadlineAt, Long finishedAt) {
}

TaskView cancel(Long accountId);
```

- [ ] **Step 4: 用 FutureTask 和现有调度器管理任务**

```java
FutureTask<Void> future = new FutureTask<>(() -> {
    runTask(pending, captchaUrl, control);
    return null;
});
control.future = future;
control.timeoutFuture = scheduler.schedule(
        () -> timeout(control), TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
control.heartbeatFuture = scheduler.scheduleAtFixedRate(
        () -> heartbeat(control), 1, 1, TimeUnit.SECONDS);
taskExecutor.execute(future);
```

超时和取消必须先写入终态，再执行 `future.cancel(true)`；`finishIfCurrent` 只允许任务标识匹配且状态仍为活动态时写回。

- [ ] **Step 5: 运行生命周期测试并确认 GREEN**

Run: `.\mvnw.cmd -Dtest=CaptchaSolveServiceImplLifecycleTest test`

Expected: PASS，阻塞线程被中断，状态保持 `TIMEOUT` 或 `CANCELLED`，不同模式返回明确冲突。

### Task 3: 上报真实 Playwright 阶段

- [ ] **Step 1: 写阶段顺序和人工不支持的失败测试**

```java
@Test
void reportsBrowserAndSliderStages() {
    List<ProgressUpdate> updates = new CopyOnWriteArrayList<>();
    try (Playwright playwright = Playwright.create();
         Browser browser = playwright.chromium().launch();
         BrowserContext context = browser.newContext()) {
        Page page = context.newPage();
        page.setContent("""
                <style>
                  .nc_scale { position: relative; width: 300px; height: 40px; }
                  .btn_slide { position: absolute; width: 40px; height: 40px; }
                </style>
                <div class="nc_scale"><button class="btn_slide">拖动</button></div>
                <script>
                  document.addEventListener('mouseup',
                    () => document.querySelector('.nc_scale')?.remove());
                </script>
                """);
        RunResult result = runner.runAutomatic(
                page, System.currentTimeMillis() + 5_000, updates::add);
        assertEquals(Outcome.SOLVED, result.outcome());
    }
    assertTrue(updates.stream().anyMatch(update -> "FINDING_SLIDER".equals(update.phase())));
    assertTrue(updates.stream().anyMatch(update -> "DRAGGING".equals(update.phase())));
    assertTrue(updates.stream().anyMatch(update -> "WAITING_RESULT".equals(update.phase())));
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run: `.\mvnw.cmd -Dtest=PlaywrightCaptchaBrowserRunnerProgressTest test`

Expected: 编译失败，因为执行器尚无进度回调。

- [ ] **Step 3: 增加进度回调并在真实阶段调用**

```java
record ProgressUpdate(String phase, String message, int attempt, int maxAttempts) {
}

RunResult run(Long accountId, Mode mode, String captchaUrl,
              String cookieText, Consumer<ProgressUpdate> progress);
```

关键阶段依次上报 `STARTING_BROWSER`、`OPENING_PAGE`、`FINDING_SLIDER`、`DRAGGING`、`WAITING_RESULT` 和 `WAITING_MANUAL`。所有文本禁止包含 URL、Cookie、Token 和异常原文。

为真实 HTML 滑块测试保留包级 `runAutomatic(Page, long, Consumer<ProgressUpdate>)`，不增加仅供测试使用的状态或分支。

- [ ] **Step 4: 运行测试并确认 GREEN**

Run: `.\mvnw.cmd -Dtest=PlaywrightCaptchaBrowserRunnerProgressTest test`

Expected: PASS。

### Task 4: 增加取消接口和实时前端状态

- [ ] **Step 1: 写取消接口归属校验失败测试**

```java
@Test
void cancelRejectsForeignAccount() {
    when(accountMapper.selectById(9L)).thenReturn(null);
    assertThrows(IllegalArgumentException.class,
            () -> controller.cancelCaptcha(Map.of("xianyuAccountId", 9L)));
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run: `.\mvnw.cmd -Dtest=WebSocketControllerCaptchaCancelTest test`

Expected: 编译失败，因为取消接口不存在。

- [ ] **Step 3: 增加取消 API**

```java
@PostMapping("/captcha/cancel")
public Result<CaptchaSolveService.TaskView> cancelCaptcha(@RequestBody Map<String, Object> request) {
    Long accountId = requireOwnedAccountId(request);
    return Result.success(captchaSolveService.cancel(accountId));
}
```

- [ ] **Step 4: 更新前端类型和轮询**

```ts
export type CaptchaSolveStatus =
  | 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  | 'TIMEOUT' | 'UNSUPPORTED' | 'CANCELLED';

export function cancelCaptcha(accountId: number) {
  return request<CaptchaTaskStatus>({
    url: '/websocket/captcha/cancel',
    method: 'POST',
    data: { xianyuAccountId: accountId }
  });
}
```

弹窗打开时先调用 `getCaptchaStatus` 恢复活动任务；服务端每 2 秒同步，本地每秒更新已运行和剩余时间；轮询失败保留最后状态并继续有限重试；运行时显示取消按钮。

- [ ] **Step 5: 运行后端测试、前端类型检查和生产构建**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task test
cd vue-code
npm.cmd run type-check
npm.cmd run build:spring
```

Expected: Java 21 `BUILD SUCCESS`，`vue-tsc` 和 Vite 构建成功。

### Task 5: 清理测试产物并完成本地闭环

- [ ] **Step 1: 删除本次临时测试类和 fixture**

仅删除本计划列出的临时测试产物，不删除项目原有测试。

- [ ] **Step 2: 删除构建产生的本地静态哈希文件并恢复构建前状态**

只处理 `src/main/resources/static` 下本次构建生成的路径；保留 `README.md` 和 `DISCLAIMER.md` 既有改动。

- [ ] **Step 3: 重新运行正式验证**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task test
cd vue-code
npm.cmd run type-check
git diff --check
```

Expected: 全部退出码为 0，Git 仅包含正式修复文件和既有两处文档改动。

- [ ] **Step 4: 审核敏感信息和范围**

Run:

```powershell
rg -n "cookieText|captchaUrl|x5secdata|Token" src/main/java/com/xianyusmart/service/captcha src/main/java/com/xianyusmart/service/impl/CaptchaSolveServiceImpl.java
git diff --stat
git diff --check
```

Expected: 代码变量可存在，新增日志、状态消息和接口返回不得拼接敏感值；无数据库、Docker、Compose 和履约链路变更。

### Task 6: 提交、推送、部署和生产闭环

- [ ] **Step 1: 提交正式修复**

```powershell
git add -- `
  src/main/java/com/xianyusmart/config/PlaywrightManager.java `
  src/main/java/com/xianyusmart/service/CaptchaSolveService.java `
  src/main/java/com/xianyusmart/service/captcha/CaptchaBrowserRunner.java `
  src/main/java/com/xianyusmart/service/captcha/PlaywrightCaptchaBrowserRunner.java `
  src/main/java/com/xianyusmart/service/impl/CaptchaSolveServiceImpl.java `
  src/main/java/com/xianyusmart/controller/WebSocketController.java `
  vue-code/src/api/websocket.ts `
  vue-code/src/views/connection/components/CaptchaGuideDialog.vue
git commit -m "fix: make captcha tasks observable and interruptible"
```

- [ ] **Step 2: 安全整合远端并推送**

```powershell
git fetch origin main
git merge --no-edit origin/main
git push origin main
```

禁止强制推送。

- [ ] **Step 3: 使用更新技能预检并部署**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\updating-xianyusmart\scripts\deploy.ps1 -WhatIf
powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\updating-xianyusmart\scripts\deploy.ps1
```

只重建 Compose `app` 服务，不修改 MySQL、Nginx 和其他服务。

- [ ] **Step 4: 生产过期 Cookie 闭环**

通过正常认证接口使用账号 1：

1. 启动连接并确认生成滑块待处理任务。
2. 启动自动模式并观察阶段、尝试次数、心跳、已运行时间和剩余时间。
3. 验证成功则确认 Cookie 更新及 WebSocket 恢复；未成功则确认 5 分钟内进入明确失败或超时。
4. 启动后取消，确认任务 `CANCELLED` 且 Playwright/Chromium 进程退出。
5. 选择人工模式，确认生产容器立即返回 `UNSUPPORTED`。

- [ ] **Step 5: 验证服务健康和工作区**

```powershell
Invoke-WebRequest -UseBasicParsing http://101.200.3.45/actuator/health
git status --short
git rev-parse HEAD
git rev-parse origin/main
```

Expected: `{"status":"UP"}`，本地与远端提交一致，只保留原有 `README.md`、`DISCLAIMER.md` 改动。
