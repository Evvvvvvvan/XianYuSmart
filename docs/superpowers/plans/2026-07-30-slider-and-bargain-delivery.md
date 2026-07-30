# Slider And Bargain Delivery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在连接管理中提供 Java Playwright 全自动滑块、可视化人工拖动、粘贴 Cookie 三种选择，并让“小刀待刀成”订单先免拼发货、“小刀成功”后进入现有自动发货链路。

**Architecture:** 新增进程内滑块任务服务，服务端从现有 Token 等待状态读取验证地址，使用独立 Playwright 实例执行自动或人工模式，成功后复用账号 Cookie 更新与 WebSocket 重连。小刀流程扩展现有聊天事件监听器：等待阶段调用免拼接口，成功阶段复用订单发现和统一发货调度，不新增数据表、不旁路卡密事务。

**Tech Stack:** Java 21、Spring Boot 3.5、Java Playwright 1.40、JUnit 5、Mockito、Vue 3、TypeScript、Vite

---

## 实施约束

- 仅修改本计划列出的文件；保留 `README.md`、`DISCLAIMER.md` 及其他现有未提交改动。
- Java 命令统一使用 `.agents\skills\operating-xianyusmart\scripts\project.ps1`，确保 JDK 21。
- 不修改 `pom.xml`：项目已有 `com.microsoft.playwright:playwright:1.40.0` 和 `spring-boot-starter-test`。
- 滑块任务不保存 Cookie、Token、完整验证地址或指纹脚本到状态响应和日志。
- 前端不得提交验证地址；后端只使用 `WebSocketTokenService` 已保存的地址。
- 仅允许 HTTPS 且域名属于 `goofish.com` 或 `taobao.com` 的验证地址及跳转地址。
- 每个账号最多一个运行中任务，全局最多两个浏览器任务；任务最长五分钟。
- 小刀等待阶段不得创建发货任务、预占卡密或发送发货内容。
- 小刀成功阶段必须进入现有 `DeliveryTaskService.discover`，继续由统一调度器执行固定内容、卡密、发货凭证和私聊渠道。
- 本计划新增的测试代码和本地 HTML 仅用于本次验证，最终必须删除，再重新执行正式构建。

### Task 1: 建立滑块任务状态与并发控制

**Files:**

- Create: `src/main/java/com/xianyusmart/service/CaptchaSolveService.java`
- Create: `src/main/java/com/xianyusmart/service/captcha/CaptchaBrowserRunner.java`
- Create: `src/main/java/com/xianyusmart/service/impl/CaptchaSolveServiceImpl.java`
- Modify: `src/main/java/com/xianyusmart/service/WebSocketTokenService.java`
- Modify: `src/main/java/com/xianyusmart/service/impl/WebSocketTokenServiceImpl.java`
- Test temporarily: `src/test/java/com/xianyusmart/service/impl/CaptchaSolveServiceImplTest.java`

- [ ] **Step 1: 编写失败测试**

临时测试覆盖：

```java
@Test
void rejectsStartWhenCaptchaUrlIsMissing() {}

@Test
void reusesRunningTaskForSameAccount() {}

@Test
void rejectsThirdConcurrentBrowserTask() {}

@Test
void mapsRunnerTimeoutAndUnsupportedWithoutExposingCookie() {}

@Test
void updatesCookieAndReconnectsOnlyAfterSolvedResult() {}
```

测试使用可控的 `CaptchaBrowserRunner`、`Executor` 和 Mockito，不启动真实浏览器。断言 `TaskView` 只包含账号、模式、状态、提示和时间，不包含 Cookie、Token 或 URL。

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task test
```

Expected: Maven 使用 Java 21，因 `CaptchaSolveService`、`CaptchaBrowserRunner` 和实现类尚不存在而编译失败。

- [ ] **Step 3: 定义最小任务接口**

`CaptchaSolveService` 使用内嵌枚举和不可变视图，避免散落 DTO：

```java
public interface CaptchaSolveService {

    enum Mode {
        AUTO,
        MANUAL_BROWSER
    }

    enum Status {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED,
        TIMEOUT,
        UNSUPPORTED
    }

    record TaskView(Long xianyuAccountId, Mode mode, Status status,
                    String message, long startedAt, Long finishedAt) {
    }

    TaskView start(Long accountId, Mode mode);

    TaskView getStatus(Long accountId);
}
```

`CaptchaBrowserRunner` 只把 Cookie 交回任务服务内部处理：

```java
public interface CaptchaBrowserRunner {

    enum Outcome {
        SOLVED,
        FAILED,
        TIMEOUT,
        UNSUPPORTED
    }

    record RunResult(Outcome outcome, String cookieText, String message) {
    }

    RunResult run(Long accountId, CaptchaSolveService.Mode mode,
                  String captchaUrl, String cookieText);
}
```

- [ ] **Step 4: 暴露服务器保存的待验证地址**

在 `WebSocketTokenService` 增加：

```java
String getPendingCaptchaUrl(Long accountId);
```

在 `WebSocketTokenServiceImpl` 中读取 `pendingCaptchaAccounts` 和 `captchaTimestamps`；超过现有 `CAPTCHA_TIMEOUT` 时同时清理两个 Map 并返回 `null`。保留 `clearCaptchaWait` 原行为。

同时将以下日志改成只记录账号和状态：

```java
// Before
log.warn("【账号{}】检测到滑块验证，URL: {}", accountId, captchaUrl);

// After
log.warn("【账号{}】检测到滑块验证，已保存待处理地址", accountId);
```

- [ ] **Step 5: 实现任务编排**

`CaptchaSolveServiceImpl` 使用：

```java
private static final int MAX_BROWSER_TASKS = 2;
private static final long TASK_RETENTION_MS = TimeUnit.MINUTES.toMillis(10);
private final Map<Long, TaskView> tasks = new ConcurrentHashMap<>();
private final Semaphore browserPermits = new Semaphore(MAX_BROWSER_TASKS);
```

关键顺序：

1. 校验 `accountId`、`mode` 和待验证地址。
2. 若同账号已有 `PENDING` 或 `RUNNING` 任务，直接返回原任务。
3. 使用 `tryAcquire()` 限制两个任务；无空位时返回业务错误，不排队占用线程。
4. 保存 `PENDING`，交给现有 `@Qualifier("taskExecutor") Executor`。
5. 执行前切换 `RUNNING`，调用 `CaptchaBrowserRunner`。
6. 只有 `SOLVED` 且 Cookie 包含 `unb` 时调用：

```java
boolean updated = accountService.updateAccountCookie(accountId, unb, cookieText);
boolean connected = updated && webSocketService.restartAfterCredentialUpdate(accountId);
```

7. 只有 `updated && connected` 时标记 `SUCCEEDED`；其余结果分别映射为 `FAILED`、`TIMEOUT`、`UNSUPPORTED`。
8. `finally` 必须释放 Semaphore；状态查询和新任务启动时清理十分钟前已结束的状态。

- [ ] **Step 6: 运行测试确认通过**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task test
```

Expected: Java 21；`CaptchaSolveServiceImplTest` 全部通过。

### Task 2: 实现 Java Playwright 自动拖动、指纹处理和人工浏览器

**Files:**

- Create: `src/main/java/com/xianyusmart/service/captcha/PlaywrightCaptchaBrowserRunner.java`
- Test temporarily: `src/test/java/com/xianyusmart/service/captcha/PlaywrightCaptchaBrowserRunnerTest.java`
- Test temporarily: `src/test/resources/captcha/main-frame-slider.html`
- Test temporarily: `src/test/resources/captcha/nested-frame-slider.html`
- Test temporarily: `src/test/resources/captcha/slider-frame.html`

- [ ] **Step 1: 编写失败测试和本地页面**

本地 HTML 用固定尺寸轨道和按钮模拟 Baxia 结构，拖动到阈值后移除验证容器。测试覆盖：

```java
@Test
void allowsOnlyHttpsGoofishAndTaobaoUrls() {}

@Test
void calculatesClampedDistanceFromTrackAndHandle() {}

@Test
void findsSliderInMainFrame() {}

@Test
void findsSliderInNestedFrame() {}

@Test
void initScriptMasksAutomaticBrowserSignals() {}
```

本地 `file:` 页面只允许测试辅助入口使用；正式 `run` 仍执行 HTTPS 域名白名单。

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task test
```

Expected: 因 `PlaywrightCaptchaBrowserRunner` 尚不存在而编译失败。

- [ ] **Step 3: 实现独立浏览器生命周期**

每个任务独立创建并关闭：

```java
try (Playwright playwright = Playwright.create()) {
    Browser browser = playwright.chromium().launch(
            new BrowserType.LaunchOptions()
                    .setHeadless(mode == CaptchaSolveService.Mode.AUTO)
                    .setArgs(List.of(
                            "--disable-blink-features=AutomationControlled",
                            "--disable-infobars",
                            "--disable-dev-shm-usage")));
    try (browser; BrowserContext context = browser.newContext(contextOptions())) {
        // 注入 Cookie、执行验证、导出更新后的 Cookie
    }
}
```

使用 `XianyuSignUtils.parseCookies` 和 `XianyuSignUtils.formatCookies`，分别向 `.goofish.com` 与 `.taobao.com` 注入同名 Cookie；导出仅覆盖 goofish、passport、h5api 和 taobao 固定地址。

- [ ] **Step 4: 实现自动模式指纹脚本**

只在 `AUTO` 上调用 `context.addInitScript`，覆盖：

- `navigator.webdriver`
- `window.chrome.runtime`
- `navigator.plugins`
- `navigator.languages`
- `navigator.permissions.query`
- WebGL `UNMASKED_VENDOR_WEBGL` 与 `UNMASKED_RENDERER_WEBGL`
- `navigator.hardwareConcurrency`
- `navigator.deviceMemory`
- Playwright/CDP 常见全局注入痕迹

关键注释：

```java
// 自动模式在页面脚本执行前统一浏览器指纹，避免同一上下文暴露互相矛盾的特征。
```

脚本不得包含账号、Cookie、Token 或验证地址。

- [ ] **Step 5: 实现 Baxia 检测与轨迹**

遍历 `page.frames()`，优先匹配上游 Baxia 选择器，再使用“可见轨道 + 可见拖动按钮”的结构回退。轨道距离使用：

```java
double distance = Math.max(180, Math.min(360, track.width - handle.width));
```

鼠标轨迹按加速、减速、纵向抖动、短暂停顿、轻微回拉、少量越界后回正生成；每次拖动通过 `page.mouse()` 执行，不使用瞬移式单步拖动。最多五次，整体不超过五分钟。

成功必须同时满足：

1. Baxia 验证容器或 iframe 消失；
2. 页面无成功后的二次验证；
3. 浏览器上下文能导出非空 Cookie。

自动模式失败时返回 `FAILED` 或 `TIMEOUT`，不返回伪造成功。

- [ ] **Step 6: 实现人工模式**

`MANUAL_BROWSER` 使用有界面 Chromium：

1. Windows 交互桌面直接支持；
2. Linux 仅在存在 `DISPLAY` 或 `WAYLAND_DISPLAY` 时支持；
3. 无图形环境返回 `UNSUPPORTED`；
4. 打开服务器保存的验证地址，等待验证容器消失；
5. 自动导出 Cookie 并交回任务服务。

不记录完整当前页面 URL；浏览器启动或显示失败时返回明确提示，允许前端改用粘贴 Cookie。

- [ ] **Step 7: 运行测试确认通过**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task test
```

Expected: 主页面、嵌套 iframe、距离、域名和指纹测试全部通过；无外网请求。

### Task 3: 增加滑块任务接口并复用租户归属校验

**Files:**

- Modify: `src/main/java/com/xianyusmart/controller/WebSocketController.java`
- Test temporarily: `src/test/java/com/xianyusmart/controller/WebSocketControllerCaptchaTest.java`

- [ ] **Step 1: 编写失败测试**

临时 MVC/单元测试覆盖：

```java
@Test
void rejectsCaptchaTaskForMissingAccount() {}

@Test
void startsCaptchaTaskForOwnedAccount() {}

@Test
void returnsSanitizedCaptchaStatus() {}
```

Mock `XianyuAccountMapper.selectById` 模拟当前租户可见和不可见账号；断言响应没有 `cookieText`、`token`、`captchaUrl`。

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task test
```

Expected: 新接口尚不存在，测试失败。

- [ ] **Step 3: 增加接口**

在 `WebSocketController` 注入 `CaptchaSolveService` 和 `XianyuAccountMapper`，新增：

```java
@PostMapping("/captcha/solve")
public ResultObject<CaptchaSolveService.TaskView> solveCaptcha(
        @RequestBody CaptchaSolveReqDTO reqDTO) {
    // 先校验当前租户下的账号归属，再启动服务端任务。
}

@PostMapping("/captcha/status")
public ResultObject<CaptchaSolveService.TaskView> getCaptchaStatus(
        @RequestBody CaptchaStatusReqDTO reqDTO) {
    // 状态只返回进度和提示，不返回验证凭证。
}
```

DTO：

```java
@Data
public static class CaptchaSolveReqDTO {
    private Long xianyuAccountId;
    private CaptchaSolveService.Mode mode;
}

@Data
public static class CaptchaStatusReqDTO {
    private Long xianyuAccountId;
}
```

同时调整现有滑块异常响应：

```java
// Before
log.warn("⚠️ 需要滑块验证: accountId={}, url={}", accountId, captchaUrl);
captchaInfo.setCaptchaUrl(captchaUrl);

// After
log.warn("⚠️ 账号需要滑块验证: accountId={}", accountId);
```

保留 `CaptchaInfoDTO.captchaUrl` 字段以兼容旧前端，但不再赋值或记录完整地址。

- [ ] **Step 4: 运行测试确认通过**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task test
```

Expected: 控制器账号归属、启动和脱敏状态测试全部通过。

### Task 4: 将三种滑块方式接入连接管理

**Files:**

- Modify: `vue-code/src/api/websocket.ts`
- Modify: `vue-code/src/views/connection/components/CaptchaGuideDialog.vue`
- Modify: `vue-code/src/views/connection/ConnectionDetail.vue`
- Modify: `vue-code/src/views/connection/components/ConnectionDetail.vue`
- Modify: `vue-code/src/views/connection/components/ConnectionDetailDialog.vue`
- Modify: `vue-code/src/views/automation/index.vue`

- [ ] **Step 1: 扩展前端 API 类型**

在 `websocket.ts` 增加：

```ts
export type CaptchaSolveMode = 'AUTO' | 'MANUAL_BROWSER'
export type CaptchaSolveStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'TIMEOUT'
  | 'UNSUPPORTED'

export interface CaptchaTaskStatus {
  xianyuAccountId: number
  mode: CaptchaSolveMode
  status: CaptchaSolveStatus
  message: string
  startedAt: number
  finishedAt?: number
}

export function solveCaptcha(accountId: number, mode: CaptchaSolveMode) {}
export function getCaptchaStatus(accountId: number) {}
```

两个函数分别 POST `/websocket/captcha/solve` 和 `/websocket/captcha/status`，请求体只带账号和模式。

- [ ] **Step 2: 改造选择对话框**

`CaptchaGuideDialog.vue` 增加 `accountId` 属性和事件：

```ts
interface Props {
  modelValue: boolean
  accountId: number
}

interface Emits {
  (e: 'update:modelValue', value: boolean): void
  (e: 'cookie'): void
  (e: 'success'): void
}
```

界面提供三个明确选项：

- 全自动拖动：启动 `AUTO`，显示自动识别、拖动、Cookie 回收和重连进度。
- 人工拖动：启动 `MANUAL_BROWSER`，提示本机将打开可视浏览器。
- 粘贴 Cookie：触发 `cookie`，继续打开现有手动 Cookie 对话框。

自动和人工模式每两秒调用一次状态接口。`SUCCEEDED` 时停止轮询、关闭对话框并触发 `success`；`FAILED`、`TIMEOUT`、`UNSUPPORTED` 时停止轮询并保留对话框，显示后端提示和 Cookie 备用入口。关闭或卸载组件时必须清理定时器。

- [ ] **Step 3: 更新三个调用点**

三个调用点统一改为：

```vue
<CaptchaGuideDialog
  v-model="showCaptchaGuideDialog"
  :account-id="accountId || 0"
  @cookie="showManualUpdateCookieDialog = true"
  @success="handleCaptchaSolveSuccess"
/>
```

`handleCaptchaSolveSuccess` 只复用现有状态刷新和操作日志刷新，不再调用 `window.open`。保留现有手动 Cookie 对话框及其 `updateCookie -> restartAfterCredentialUpdate` 流程。

- [ ] **Step 4: 更新自动化页说明**

仅替换与新功能冲突的句子：

```text
Before: 系统会立即刷新凭证并重连，不会自动模拟滑块或绕过平台检测。
After: 可选择全自动拖动、本机人工拖动或粘贴 Cookie，验证完成后系统会刷新凭证并重连。
```

- [ ] **Step 5: 执行前端类型检查和正式构建**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task frontend
```

Expected: `vue-tsc --build` 和 `vite build` 均成功，不新增锁文件。

### Task 5: 增加小刀订单免拼发货 API

**Files:**

- Modify: `src/main/java/com/xianyusmart/service/OrderService.java`
- Modify: `src/main/java/com/xianyusmart/service/impl/OrderServiceImpl.java`
- Test temporarily: `src/test/java/com/xianyusmart/service/impl/OrderServiceImplBargainTest.java`

- [ ] **Step 1: 编写失败测试**

测试覆盖：

```java
@Test
void sendsFreeShippingPayloadWithNumericItemAndBuyerIds() {}

@Test
void treatsOrderAlreadyDeliveredAsIdempotentSuccess() {}

@Test
void treatsExplicitDeliveredMessageAsIdempotentSuccess() {}

@Test
void doesNotReportSuccessForTokenOrUnknownFailure() {}
```

使用 `ArgumentCaptor<Map<String, Object>>` 断言：

```java
assertEquals(orderId, payload.get("bizOrderId"));
assertEquals(itemId, payload.get("itemId"));
assertEquals(buyerId, payload.get("buyerId"));
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task test
```

Expected: `OrderService.freeShippingBargain` 尚不存在，测试编译失败。

- [ ] **Step 3: 增加服务方法**

在 `OrderService` 增加：

```java
boolean freeShippingBargain(Long accountId, String orderId, Long itemId, Long buyerId);
```

在 `OrderServiceImpl` 中复用 `accountService.getCookieByAccountId` 和 `xianyuApiCallUtils.callApiWithRetry`：

```java
Map<String, Object> dataMap = new HashMap<>();
dataMap.put("bizOrderId", orderId);
dataMap.put("itemId", itemId);
dataMap.put("buyerId", buyerId);

XianyuApiCallUtils.ApiCallResult result = xianyuApiCallUtils.callApiWithRetry(
        accountId,
        "mtop.idle.groupon.activity.seller.freeshipping",
        dataMap,
        cookieStr
);
```

返回规则：

- API 成功：`true`
- `ORDER_ALREADY_DELIVERY`：`true`
- 错误或响应明确包含“已发货成功”：`true`
- Token 过期、空 Cookie、未知失败或异常：`false`

日志只记录账号和订单号，不记录 Cookie 和完整响应。

- [ ] **Step 4: 运行测试确认通过**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task test
```

Expected: 小刀 API 名称、参数类型、幂等成功和失败分支测试全部通过。

### Task 6: 接入小刀等待与成功消息

**Files:**

- Modify: `src/main/java/com/xianyusmart/event/chatMessageEvent/lister/ChatMessageEventAutoDeliveryListener.java`
- Test temporarily: `src/test/java/com/xianyusmart/event/chatMessageEvent/lister/ChatMessageEventAutoDeliveryListenerBargainTest.java`

- [ ] **Step 1: 编写失败测试**

测试覆盖：

```java
@Test
void waitingMessageCallsFreeShippingWithoutCreatingDeliveryTask() {}

@Test
void duplicateWaitingMessageIsIgnoredWithinTtl() {}

@Test
void successMessageCreatesExistingDeliveryTaskWithoutCallingFreeShipping() {}

@Test
void disabledOrForeignGoodsDoesNothing() {}

@Test
void existingPaymentMessageStillCreatesDeliveryTask() {}
```

等待消息示例使用 `contentType=32`、`msgContent="小刀订单，待刀成"`；成功消息使用 `msgContent="小刀成功"` 和 `"我已成功小刀"`。

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task test
```

Expected: 监听器尚未识别小刀消息，测试失败。

- [ ] **Step 3: 增加消息分类与短期去重**

新增：

```java
private static final long BARGAIN_MESSAGE_TTL_MS = TimeUnit.MINUTES.toMillis(10);
private final Map<String, Long> bargainMessageStages = new ConcurrentHashMap<>();

private boolean isBargainWaitingMessage(ChatMessageData message) {
    String content = message.getMsgContent();
    return content != null && content.contains("小刀") && content.contains("待刀成");
}

private boolean isBargainSuccessMessage(ChatMessageData message) {
    String content = message.getMsgContent();
    return content != null
            && (content.contains("小刀成功") || content.contains("我已成功小刀"));
}
```

去重键为 `accountId + pnmId/订单消息特征 + stage`，只用于进程内短期重复事件；超过十分钟清理。订单级最终幂等继续由现有数据库唯一约束和 `DeliveryTaskService.discover` 保证。

- [ ] **Step 4: 接入等待阶段**

处理顺序放在现有付款消息过滤之前：

1. 要求 `orderId`、`xyGoodsId`、`senderUserId` 都非空且数字字段可转为 `Long`。
2. 调用现有 `resolveXianyuGoodsId(accountId, xyGoodsId)` 验证商品归属。
3. 读取 `goodsConfigMapper.selectByAccountAndGoodsId`，仅自动发货开启时继续。
4. 调用：

```java
orderService.freeShippingBargain(
        accountId,
        message.getOrderId(),
        Long.valueOf(message.getXyGoodsId()),
        Long.valueOf(message.getSenderUserId()));
```

5. 无论免拼调用成功或失败都立即返回，不执行 `createOrderRecord`，不调用 `DeliveryTaskService.discover`。

- [ ] **Step 5: 接入成功阶段**

将现有入口条件从：

```java
if (!isPaymentMessage(message)) {
    return;
}
```

改为：

```java
boolean bargainSuccess = isBargainSuccessMessage(message);
if (!isPaymentMessage(message) && !bargainSuccess) {
    return;
}
```

小刀成功消息继续执行现有商品归属、自动发货开关、买家风控、订单记录创建、通知和 `deliveryTaskService.discover(record, DeliveryChannel.WEBSOCKET)`，不复制 `AutoDeliveryService` 的发货实现。

- [ ] **Step 6: 运行测试确认通过**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task test
```

Expected: 等待、成功、去重、归属、开关和原付款消息回归测试全部通过。

### Task 7: 删除临时测试、完成正式验证并提交

**Files:**

- Delete temporary: `src/test/java/com/xianyusmart/service/impl/CaptchaSolveServiceImplTest.java`
- Delete temporary: `src/test/java/com/xianyusmart/service/captcha/PlaywrightCaptchaBrowserRunnerTest.java`
- Delete temporary: `src/test/java/com/xianyusmart/controller/WebSocketControllerCaptchaTest.java`
- Delete temporary: `src/test/java/com/xianyusmart/service/impl/OrderServiceImplBargainTest.java`
- Delete temporary: `src/test/java/com/xianyusmart/event/chatMessageEvent/lister/ChatMessageEventAutoDeliveryListenerBargainTest.java`
- Delete temporary: `src/test/resources/captcha/main-frame-slider.html`
- Delete temporary: `src/test/resources/captcha/nested-frame-slider.html`
- Delete temporary: `src/test/resources/captcha/slider-frame.html`

- [ ] **Step 1: 审核临时测试结果**

确认 Task 1 至 Task 6 的全部临时测试已通过，保留终端输出作为验证证据。

- [ ] **Step 2: 使用 `apply_patch` 删除本次临时测试和 HTML**

不得删除项目原有测试；删除后执行：

```powershell
rg --files src/test | rg "CaptchaSolveServiceImplTest|PlaywrightCaptchaBrowserRunnerTest|WebSocketControllerCaptchaTest|OrderServiceImplBargainTest|ChatMessageEventAutoDeliveryListenerBargainTest|resources/captcha"
```

Expected: 无输出。

- [ ] **Step 3: 执行后端正式验证**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task test
```

Expected: Java 21；Maven `BUILD SUCCESS`；正式代码不依赖临时测试资源。

- [ ] **Step 4: 执行前端正式验证**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task frontend
```

Expected: TypeScript 类型检查和 Vite 正式构建成功。

- [ ] **Step 5: 执行差异和敏感信息审计**

Run:

```powershell
git diff --check
git status --short
git diff -- src/main/java vue-code/src
rg -n "captchaUrl=|Cookie:|cookieText=|websocketToken=" src/main/java/com/xianyusmart/service/captcha src/main/java/com/xianyusmart/service/impl/CaptchaSolveServiceImpl.java src/main/java/com/xianyusmart/controller/WebSocketController.java
```

Expected:

- `git diff --check` 无输出；
- `README.md`、`DISCLAIMER.md` 仍保持原有未提交状态，内容未被本任务改动；
- 敏感信息搜索不出现新日志；
- 无数据库迁移、无新依赖、无临时测试文件。

- [ ] **Step 6: 只暂存本次实现文件**

使用明确路径执行 `git add`，不得使用 `git add .`，不得暂存 `README.md`、`DISCLAIMER.md` 或 `.agents`：

```powershell
git add docs/superpowers/plans/2026-07-30-slider-and-bargain-delivery.md
git add src/main/java/com/xianyusmart/service/CaptchaSolveService.java
git add src/main/java/com/xianyusmart/service/captcha/CaptchaBrowserRunner.java
git add src/main/java/com/xianyusmart/service/captcha/PlaywrightCaptchaBrowserRunner.java
git add src/main/java/com/xianyusmart/service/impl/CaptchaSolveServiceImpl.java
git add src/main/java/com/xianyusmart/service/WebSocketTokenService.java
git add src/main/java/com/xianyusmart/service/impl/WebSocketTokenServiceImpl.java
git add src/main/java/com/xianyusmart/controller/WebSocketController.java
git add src/main/java/com/xianyusmart/service/OrderService.java
git add src/main/java/com/xianyusmart/service/impl/OrderServiceImpl.java
git add src/main/java/com/xianyusmart/event/chatMessageEvent/lister/ChatMessageEventAutoDeliveryListener.java
git add vue-code/src/api/websocket.ts
git add vue-code/src/views/connection/components/CaptchaGuideDialog.vue
git add vue-code/src/views/connection/ConnectionDetail.vue
git add vue-code/src/views/connection/components/ConnectionDetail.vue
git add vue-code/src/views/connection/components/ConnectionDetailDialog.vue
git add vue-code/src/views/automation/index.vue
```

Run:

```powershell
git diff --cached --check
git diff --cached --stat
git status --short
```

Expected: 暂存区只含计划和本次实现文件；`README.md`、`DISCLAIMER.md` 仍为未暂存。

- [ ] **Step 7: 提交并推送**

Run:

```powershell
git commit -m "feat: add captcha solving and bargain delivery"
git push origin main
```

Expected: 提交成功并推送到 `origin/main`；不创建 Release、不部署生产环境。

## 最终验收

- [ ] 连接触发滑块时显示“全自动拖动 / 人工拖动 / 粘贴 Cookie”三种选择。
- [ ] 全自动模式使用 Java Playwright 完成 Baxia 检测、拟人轨迹、指纹处理、Cookie 回收和重连。
- [ ] 人工模式在本地可视环境打开浏览器，人工完成后自动回收 Cookie 和重连。
- [ ] 无图形环境明确提示不支持，并可回退到粘贴 Cookie。
- [ ] 粘贴 Cookie 继续复用原 `/websocket/updateCookie` 和自动重连。
- [ ] 小刀待刀成消息只调用免拼接口，不创建正式发货任务。
- [ ] 小刀成功消息只通过现有订单发现和发货调度链路执行一次。
- [ ] 未开启自动发货、商品不归属当前账号、字段不完整时不调用免拼或发货。
- [ ] 固定内容、卡密、发货凭证、私聊和人工复核逻辑没有旁路或重复实现。
- [ ] 无 Cookie、Token、完整验证地址和指纹数据日志。
- [ ] 临时测试和本地 HTML 已删除，Java 21 后端测试、前端类型检查和正式构建全部通过。
