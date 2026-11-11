# Auth / Session / Captcha / QR-Login / Credential-Refresh — Implicit State Machine Audit

> 审计对象：`xianyuSmart` 2.1.1（`pom.xml`：`xianyusmart` / `2.1.1`）
> 审计范围：认证、会话、滑块验证、扫码登录、凭证刷新
> 审计方式：全文件精读（非抽样）+ 字段级写入点/读取点全量反查 + SQL 迁移与前端映射表比对
> 与主清单 `docs/state-machines/README.md` 的关系：本文件是该清单 §2/§7 中 WS 系列（WS-1…WS-9）的**完整展开**，并把认证域（平台登录、启动期凭据供给）单独补齐成独立机器。

本文件只记录**从代码里实际读到**的状态和迁移，每条都带 `文件:行号`。凡代码没有写、只有注释或前端文案声称存在的状态，一律标为「悬空状态」并单列证据。路径均为仓库相对路径。

---

## 0. 判定标准与证据约定

进入本清单的条件（任一即可）：

1. 某字段取值集合有限，且不同取值让**后续分支不同**；
2. 状态之间存在**有向迁移**（A→B→C，或同一目标由多个来源进入）；
3. 迁移由**多个入口**驱动（请求线程、调度器、WebSocket 线程、浏览器回调），即存在并发写同一状态的可能；
4. 存在**守卫**（`WHERE status = ?`、`if (status == -2)`、CAS、`tryLock`）——守卫本身说明作者在脑中维护状态机。

不计入：纯配置开关（如 `maxReconnectAttempts`，虽然它恰好是死配置，记在 §14.4）。

**证据约定：**

- `File.java:123` = 该行；`File.java:123-130` = 该区间。
- 「写入点」= 任何让字段离开当前取值的语句；「读取点」= 任何依据该字段分支的语句。状态机的真实定义在**读取点**，不在写入点。
- 「守卫」区分三种：**CAS**（比较后写）、**check-then-act**（先查后写，非原子）、**无**。
- 浏览器/前端代码仅在其构成**第三套词汇表**或**消费缺失**时列出。

**工具方法（可复现）：**

```bash
# 状态写入点
grep -rn --include=*.java -E "setStatus\(|setCookieStatus\(|setPhase\(" src/main/java | sort
# 状态读取点（真正的状态机定义）
grep -rn --include=*.java -E "getStatus\(\) *==|getCookieStatus\(\)|isActive\(|isCurrentActive\(" src/main/java
# 内存态
grep -rn --include=*.java -E "private final .*(ConcurrentHashMap|AtomicInteger|AtomicBoolean|Semaphore)" src/main/java
# 定时驱动源
grep -rn --include=*.java "@Scheduled" src/main/java -A2
# 前端词汇表
grep -rn "phaseLabels\|statusMap\|getAccountStatusText" vue-code/src
```

---

## 1. 全景清单

**共 13 台隐式状态机。** 其中 M1/M2 是同一实体的两个正交状态空间（枚举 × 自由字符串），M7/M8 各有 3–10 个写入点且无统一入口，M13 是 M5/M7 的驱动侧。

| # | 状态机 | 状态载体 | 状态集 | 驱动者 | 守卫强度 |
|---|---|---|---|---|---|
| M1 | 滑块验证任务状态 | 内存 `Map<Long,TaskView>` | `PENDING,RUNNING,SUCCEEDED,FAILED,TIMEOUT,UNSUPPORTED,CANCELLED` | 请求线程、`taskExecutor`、`captchaTaskScheduler` | 强（CAS via `tasks.compute` + 控制块身份） |
| M2 | 滑块验证 `phase` | 内存 `TaskControl.phase` / `TaskView.phase` | 21 个自由字符串 | 执行线程、心跳线程 | **无** |
| M3 | 浏览器会话 / 人工拖动会话 | 内存 `Map<Long,BrowserProcessSession>` | 存在/`cancelled`/`manualFrame` 版本/拖动队列 | 执行线程、Playwright 回调、HTTP | 中（`putIfAbsent` + `synchronized`） |
| M4 | 自动拖动重试循环 | 方法内 `int attempt` + 6 个布尔 | 1..5 + 隐式标志组合 | 执行线程 | 仅循环边界 |
| M5 | 待验证等待标志 | 两张内存 Map | 缺失 / armed(url+ts) / 过期 / 半装填 | 请求线程、调度器 | 弱（仅读时惰性过期） |
| M6 | WS accessToken 缓存 | DB `websocket_token` + `token_expire_time` | fresh / expired / cleared(ts=0) / 未签发(NULL) | 请求、调度器、WS 回调 | 中（读时比时间戳） |
| M7 | Cookie 凭证状态 | DB `cookie_status` | `1` 有效 / `2` 过期 / `3` 失效 | 请求 + 4 个调度器 + WS 路由 | 弱（仅 4 个读点过滤，写点无守卫） |
| M8 | 账号状态 | DB `xianyu_account.status` | `1` 正常 / `-2` 需验证或异常 / `-1` 仅文档 | 3 个服务 + 备份导入 | 无（全为盲写） |
| M9 | 扫码登录会话 | 内存 `Map<String,QRLoginSession>` + 300s 时钟 | 后端 7 + 前端 4 + 平台 4 | 轮询线程、请求线程、用户 | 无 |
| M10 | 平台登录会话 | DB `sys_login_token` 行 | 存在 / 已删 / 已过期 | 请求线程、启动器、管理接口 | 中（读时删过期） |
| M11 | 登录失败限流 | 缓存 `login_attempt:<ip>` | 0..5 计数 + 10 分钟 TTL | 请求线程 | 弱（计数比较） |
| M12 | 启动期凭据供给 | DB `sys_user` + 环境变量 | 无管理员 / 已有管理员 / 请求重置 | `ApplicationRunner` @Order(10)/(20) | 启动期一次性校验 |
| M13 | WS 重连与滑块退避 | 6 张内存 Map + 3 个 `AtomicInteger` | 8 个隐式连接态（无终态） | `webSocketScheduler`、WS 回调、心跳 | 弱（部分去重） |

**四台机器存在「双实现」或「多实现」：** M7（3 套写入实现）、M8（3 个服务 + 备份导入）、M9（3 套词汇表）、M1+M2（枚举与字符串两套状态空间）。

---

## M1 — 滑块验证任务状态（Captcha Task Status）

**一句话目的：** 每账号一个浏览器滑块求解任务，从创建到终态可被 UI 轮询观测。

### 状态集（精确值）

`CaptchaSolveService.Status`（`src/main/java/com/xianyusmart/service/CaptchaSolveService.java:13-21`）：

```
PENDING, RUNNING, SUCCEEDED, FAILED, TIMEOUT, UNSUPPORTED, CANCELLED
```

正交的 `Mode`（`.java:8-11`）：`AUTO, MANUAL_BROWSER`。
`TaskView` 记录（`.java:23-26`）：`(xianyuAccountId, mode, status, message, phase, attempt, maxAttempts, startedAt, updatedAt, deadlineAt, finishedAt)`——注意 `status` 与 `phase` 同时存在，这是 M1/M2 并存的根因。

### 状态存放位置

| 载体 | 位置 | 生命周期 |
|---|---|---|
| `Map<Long, TaskView> tasks` | `CaptchaSolveServiceImpl.java:46` | 终态后保留 10 分钟（`:36`, `:539-547`） |
| `Map<Long, TaskControl> taskControls` | `:47` | `cleanupControl` 移除（`:519-532`） |
| `Semaphore browserPermits(2)` | `:48` | 全局并发浏览器上限 |
| `TaskControl.phase/message/attempt/maxAttempts/lastProgressAt` | `:560-564`（volatile） | 与 control 同寿命 |
| `AtomicBoolean started / permitReleased` | `:554-555` | 防重复清理 |

**零持久化**：进程重启后所有任务消失，且 `browserPermits` 归零（无孤儿浏览器句柄回收逻辑）。

### 迁移表

| 从 | 触发 / 守卫 | 到 | 证据 |
|---|---|---|---|
| ∅ | `start()`；无活跃任务、无残留 control、能取到 captchaUrl、拿到浏览器许可 | `PENDING` | `CaptchaSolveServiceImpl.java:91-101` |
| ∅ | `refreshCaptchaOnDemand()` 刷新凭证成功且 WS 重连成功 | `SUCCEEDED`（**从未创建 control**） | `:136-148` |
| ∅ | 同上但 WS 重连失败 | `FAILED`，`phase="RECONNECTING"` | `:143-144` |
| `PENDING` | `runTask` 首次 `ProgressUpdate` | `RUNNING` | `:249-250`, `:421-429` |
| `RUNNING` | 每次进度更新（重复写 `RUNNING`） | `RUNNING` | `:411-430` |
| `RUNNING` | `Outcome.SOLVED` + `updateAccountCookie` 成功 + `restartAfterCredentialUpdate` 为真 | `SUCCEEDED` | `:356-358` |
| `RUNNING` | `Outcome.SOLVED` 但重连失败 | `FAILED` | `:359-361` |
| `RUNNING` | `Outcome ≠ SOLVED` | `TIMEOUT`/`FAILED`/`UNSUPPORTED`/`SUCCEEDED`（`mapStatus`） | `:314-317`, `:379-386` |
| `RUNNING` | Cookie 或 UNB 缺失 / 浏览器无结果 / 更新失败 | `FAILED` | `:289-292`, `:310-312`, `:327-329`, `:341-343` |
| `RUNNING` | `runTask` 抛异常且仍是 current | `FAILED` | `:366-370` |
| `PENDING`/`RUNNING` | `cancel()` → `complete(CANCELLED)` | `CANCELLED` | `:191` |
| `PENDING`/`RUNNING` | `timeoutTask`（5 分钟，`:35`） | `TIMEOUT` | `:456-461` |
| `PENDING` | 发布前调度器抛异常 | `FAILED` | `:118-119` |
| 任意终态 | 任意 `start`/`getStatus` 且超过 10 分钟保留期 | 条目被删除 | `:539-547` |
| 任意终态 | 再次 `start()` | 新 `PENDING`（旧视图被覆盖） | `:72-78`, `:116` |

**同账号同模式的特例**：`start()` 遇到活跃任务时，若 `mode` 相同则**直接返回现有视图**（`:73-77`），不同则抛 `IllegalStateException("当前已有其他方式的验证任务...")`（`:74-76`）；若 `taskControls` 仍有残留则抛「上一个验证任务正在退出」（`:79-81`）。

### 驱动者

| 驱动者 | 线程 | 参与的迁移 |
|---|---|---|
| HTTP | `taskExecutor`/Tomcat 线程 | `start`、`cancel`、`getStatus`、`getManualFrame`、`submitManualDrag` |
| `taskExecutor` | `xys-business-*` | `PENDING→RUNNING`、→`SUCCEEDED`/`FAILED` |
| `captchaTaskScheduler` | `xys-captcha-timeout-`（**单线程**，`AsyncConfig.java:123-127`） | `→TIMEOUT`、`phase=WAITING_BROWSER` |
| `WebSocketService` | 被 `runTask` 调用 | 间接决定 `SUCCEEDED`/`FAILED` |

### 守卫与执行强度

唯一的强守卫是手写 CAS：

```java
private boolean isCurrentActive(TaskView task, TaskControl control) {
    return taskControls.get(control.accountId) == control
            && task != null
            && task.startedAt() == control.startedAt
            && isActive(task);
}
```
（`CaptchaSolveServiceImpl.java:512-517`）

所有状态写入经 `tasks.compute`（`:421`, `:437`, `:500`），配合 `TaskControl` **对象身份** + `startedAt` 版本号，能正确拒绝来自已作废 control 的迟到写入。许可释放用 `AtomicBoolean permitReleased` 保证恰好一次（`:529-531`）。`isActive()` 是唯一的「非终态」定义（`:534-537`）。

**没有**枚举级迁移校验（如 `RUNNING → PENDING` 不会被拒绝，只是当前无调用者）。

### 缺陷与风险

1. **`UNSUPPORTED` 不可达（写入装配在、生产者不存在）。** `mapStatus` 处理 `Outcome.UNSUPPORTED`（`:382`），但全仓无任何代码 `return new RunResult(Outcome.UNSUPPORTED, ...)`；`grep -rn "Outcome.UNSUPPORTED"` 只命中接口/枚举声明（`CaptchaBrowserRunner.java:16`、`CaptchaSolveService.java:19`）。7 个状态的枚举实际只有 6 个可达。

2. **`PENDING` 几乎不可观测。** `runTask` 首动作即上报进度（`:249`），因此首个可观测状态基本总是 `RUNNING`；`PENDING` 只在执行器饱和时延续，而那时它会被 5 分钟定时器直接推到 `TIMEOUT`——一个用户永远看不到「排队中」的排队态。

3. **终态 + 非终态 phase。** `complete()` 对 `FAILED/TIMEOUT/UNSUPPORTED` **保留** `control.phase`（`:495-499`），因此存在 `status=FAILED, phase="DRAGGING_SLIDER"` 的组合；而 `SUCCEEDED`/`CANCELLED` 强制 `phase = status.name()`，产生前端词表里不存在的 phase 值（见 M2 缺陷 2）。

4. **中断逃逸 / 异常路径可留下永久 `RUNNING`。** `runTask` 在被中断时**不写终态**直接返回（`:301-304`）；若取消/超时没有先写入终态（例如 `Error` 越过 `catch (Exception)`，`:366`），`finally` 会 `cleanupControl` 移除 control（`:519-520`），但视图仍是 `RUNNING`。此后 `isActive()` 恒真 → `start()` 永远返回同一僵尸视图（`:73-77`），`cancel()` 因 `taskControls.get()==null` 直接返回（`:184-186`）→ **该账号的滑块功能直到重启都不可用**。

5. **发布非原子。** `pending` 与 `control` 分两次写入两张 Map（`:115-116`）；`refreshCaptchaOnDemand` 的「恢复视图」把 `startedAt == updatedAt == finishedAt == now` 而 `deadlineAt = now + 5min`（`:140-146`）——一个**已终止**的任务对外宣称**未来**的截止时间。

6. **`cancel()` 不使用自己的守卫结果。** `cancel()` 求值 `sideEffectLock.tryLock()`，但结果仅用于决定是否 `unlock`（`:188-206`）；`complete(CANCELLED)` 无条件执行。后果：`runTask` 在临界区内（`:334-347`、`:351-365`）的浏览器/凭证副作用仍会继续完成，之后它的 `complete(SUCCEEDED)` 静默失败。即「用户点了取消，`WebSocketService.restartAfterCredentialUpdate` 仍把账号重新连上」。

7. **全局监视器横跨网络 I/O。** `start()` 在 `synchronized (tasks)`（`:71`）内调用 `refreshCaptchaOnDemand` → `tokenService.refreshToken`（`:136`，OkHttp readTimeout 60s）与 `websocketService.restartAfterCredentialUpdate`（`:138`，内部 `connectBlocking(10s)`）。**其他所有账号**的 `start`/`cancel`/`updateProgress`/`heartbeat`/`complete` 都在这个监视器外排队。

8. **同一张 Map 上并存两套锁纪律。** 部分写者持对象监视器（`:71`, `:145`, `:116`），部分只持 `ConcurrentHashMap` 桶锁（`compute`，`:421`, `:437`, `:500`）。二者互不排序：持监视器的线程与持桶锁的线程可对同一账号并发交错。

9. **`CallerRunsPolicy` + 内联监视器 = 最长 5 分钟的全站停顿。** `AsyncConfig.java:58` 使用 `CallerRunsPolicy`；池满时 `taskExecutor.execute(control.future)`（`:117`）会**在持有 `synchronized(tasks)` 的 HTTP 线程上**执行整个浏览器任务（`:104-107` 的 `FutureTask`）。

10. **两套必须手工同步的常量。** `MAX_AUTO_ATTEMPTS = 5` 同时定义在 `CaptchaSolveServiceImpl.java:34` 与 `PlaywrightCaptchaBrowserRunner.java:47`；`TASK_TIMEOUT_MS = 5min` 同时在 `:35` 与 `:46`。服务侧只用于展示/初始值，runner 侧才驱动重试——任何一侧改动都会静默漂移。

11. **两套彼此独立的截止时间。** 服务的 deadline 从 `start()` 起算（`:101`）；runner 从页面打开后起算自己的 5 分钟（`PlaywrightCaptchaBrowserRunner.java:496`）。服务总是先到，因此 runner 的后半段重试**结构上不可达**，最终被归因为 `TIMEOUT` 而非「重试耗尽」。

12. **读路径写状态。** `getStatus()` 调用 `cleanupFinishedTasks()`（`:170`）。

---

## M2 — 滑块验证 `phase` 字符串状态空间

**一句话目的：** 与 M1 同一任务的「人读进度条」，以自由字符串叠在枚举之上，两套状态空间无共享词表。

### 状态集（精确值：21 个字符串字面量）

**服务侧发出**（`CaptchaSolveServiceImpl.java`）：
`QUEUED`（`:100`, `:561`）、`STARTING_BROWSER`（`:250`）、`PAUSING_RECONNECT`（`:254`）、`REFRESHING_CHALLENGE`（`:259`）、`RECONNECTING`（`:267`, `:143`）、`UPDATING_COOKIE`（`:323`）、`VALIDATING_CREDENTIAL`（`:349`）、`WAITING_BROWSER`（仅心跳，`:442`）、`SUCCEEDED` / `CANCELLED`（由 `Status.name()` 派生，`:499`）。

**Runner 侧发出**（`PlaywrightCaptchaBrowserRunner.java`）：
`CHECKING_ENVIRONMENT`（`:443`）、`STARTING_BROWSER`（`:458`）、`OPENING_PAGE`（`:480`）、`COLLECTING_COOKIE`（`:506`）、`FINDING_SLIDER`（`:951`）、`WAITING_SLIDER`（`:1189`）、`RETRYING_SLIDER`（`:968`, `:1019`）、`RESETTING_SESSION`（`:984`, `:1021`）、`DRAGGING_SLIDER`（`:995`）、`WAITING_RESULT`（`:1001`）、`CAPTURING_MANUAL_FRAME`（`:1040`, `:1102`）、`WAITING_LOGIN`（`:1061`）、`WAITING_MANUAL`（`:1085`）、`REPLAYING_MANUAL_DRAG`（`:1091`）。

**前端词表**（`vue-code/src/views/connection/components/CaptchaGuideDialog.vue:38-59`）：18 个标签，含上述绝大多数字符串，但**不含** `SUCCEEDED` / `CANCELLED`（因为这两个是枚举名冒充 phase）。

### 状态存放位置

`TaskControl.phase`（`volatile String`，`CaptchaSolveServiceImpl.java:561`）→ 每次进度更新复制进不可变 `TaskView.phase`（`:417`, `:426`）。`TaskView.phase` 在 record 里就是无约束的 `String`（`CaptchaSolveService.java:24`）。不持久化、不进日志表。

### 迁移表（按实际观察顺序）

| 从 | 触发 | 到 | 证据 |
|---|---|---|---|
| 初始化 | 任务创建 | `QUEUED` | `CaptchaSolveServiceImpl.java:100`, `:561` |
| `QUEUED` | `runTask` 开始 | `STARTING_BROWSER` | `:249-250` |
| `STARTING_BROWSER` | `mode == AUTO` | `PAUSING_RECONNECT` | `:253-255` |
| `PAUSING_RECONNECT` | `stopWebSocket` 之后 | `REFRESHING_CHALLENGE` | `:258-260` |
| `REFRESHING_CHALLENGE` | `refreshToken` 返回非空 | `RECONNECTING` | `:266-268` |
| `REFRESHING_CHALLENGE` | 无 token / 抛 `CaptchaRequiredException` | 继续进入浏览器阶段 | `:278-281` |
| 任意 RUNNING | runner `run()` 入口 | `CHECKING_ENVIRONMENT` | runner `:443` |
| `CHECKING_ENVIRONMENT` | URL 合法 | `STARTING_BROWSER` | runner `:458` |
| `STARTING_BROWSER` | context 就绪 | `OPENING_PAGE` | runner `:480` |
| `OPENING_PAGE` | AUTO | `FINDING_SLIDER` | runner `:951` |
| `OPENING_PAGE` | MANUAL | `CAPTURING_MANUAL_FRAME` | runner `:1040` |
| `FINDING_SLIDER` | 尚未识别到句柄 | `WAITING_SLIDER`（1s 节流） | runner `:1189` |
| `FINDING_SLIDER` | 识别到句柄 | `DRAGGING_SLIDER` | runner `:995` |
| `DRAGGING_SLIDER` | 拖动完成 | `WAITING_RESULT` | runner `:1001` |
| `WAITING_RESULT` | 未通过且 attempt<5 | `RETRYING_SLIDER` → `RESETTING_SESSION` → `FINDING_SLIDER` | runner `:1019-1027` |
| `FINDING_SLIDER` | 容器在、句柄未渲染 | `RETRYING_SLIDER` → `RESETTING_SESSION` | runner `:968-981` |
| `FINDING_SLIDER` | 检测到刷新弹窗 | `RESETTING_SESSION` | runner `:984-991` |
| `CAPTURING_MANUAL_FRAME` | 每 700ms（`:50`） | `WAITING_MANUAL` | runner `:1085` |
| `WAITING_MANUAL` | 检测到登录页 | `WAITING_LOGIN` | runner `:1061` |
| `WAITING_MANUAL` | 队列中有拖动 | `REPLAYING_MANUAL_DRAG` | runner `:1091` |
| `REPLAYING_MANUAL_DRAG` | 未放行 | `CAPTURING_MANUAL_FRAME` | runner `:1102` |
| 任意 RUNNING | 5 秒无进度（正交注入） | `WAITING_BROWSER` | `CaptchaSolveServiceImpl.java:441-445` |
| 任意 RUNNING | 服务侧开始校验 | `UPDATING_COOKIE` → `VALIDATING_CREDENTIAL` | `:322-324`, `:348-350` |
| 任意 RUNNING | SOLVED | `COLLECTING_COOKIE` | runner `:506` |
| 终态 | `complete()` | `SUCCEEDED`/`CANCELLED`（枚举名）；`FAILED`/`TIMEOUT`/`UNSUPPORTED` **保留最后 phase** | `:495-499` |

### 驱动者

执行线程（runner 回调 → `updateProgress`）、`captchaTaskScheduler` 心跳（注入 `WAITING_BROWSER`）、服务自身跨浏览器阶段的推进。

### 守卫

**无。** 没有枚举、没有允许集、没有顺序检查、没有单调性。`updateProgress` 接受任意字符串（`:417`）。`WAITING_BROWSER` 只存在于 `TaskView`，从不写回 `control.phase`（`:442` 用局部变量），因此它是「下一次真实更新即消失」的派生态。

### 缺陷与风险

1. **流水线非单调。** 服务先发 `STARTING_BROWSER`（`:250`），随后 runner 发 `CHECKING_ENVIRONMENT`（runner `:443`）再发 `STARTING_BROWSER`（runner `:458`）。同一标签由两个文件赋予两种含义，且「检查环境」排在「启动浏览器」之后。

2. **phase 单向吸收枚举名。** `SUCCEEDED`/`CANCELLED` 只通过 `status.name()` 产生（`:499`），前端 `phaseLabels` 无此键（`CaptchaGuideDialog.vue:38-59`）→ UI 回落为直接显示原始枚举名 `SUCCEEDED`。

3. **`FAILED` 状态配 `RECONNECTING` phase**（`:143-144`）——一个读起来「进行中」的阶段挂在终态任务上。

4. **`attempt` 语义借 phase 泄漏。** `RETRYING_SLIDER` 携带的是**失败的那一次**编号（runner `:968`, `:1019`），`updateProgress` 原样复制（`:419-420`），因此 UI 显示「第 N/5 次」时 N 已经失败。

5. **人工模式 `attempt` 永不推进。** 人工路径所有 `reportProgress` 都传 `0`（runner `:1040-1102`），于是 `MANUAL_BROWSER` 下 `TaskView.attempt` 恒为 `0/5`；后续 `UPDATING_COOKIE` 也报 `control.attempt`（0）。「第 x/5 次」组件在半个产品里是死的。

6. **启动失败没有专属 phase。** `:119` 写入 `FAILED`，phase 仍是 `QUEUED`。

---

## M3 — 浏览器会话与人工拖动会话

**一句话目的：** 保证每账号至多一个 Playwright 会话、至多一条在途人工拖动，并用版本号拒绝过期截图上的拖动。

### 状态集

`BrowserProcessSession`（`PlaywrightCaptchaBrowserRunner.java:1948-2000`）：
`cancelled` 布尔（`:1954`）、`driverProcess`（`:1955`）、`manualFrame`（`:1953`，volatile）、`frameVersion` `AtomicLong`（`:1952`）、`manualDrags` `ArrayBlockingQueue(1)`（`:1950-1951`）。
注册表：`Map<Long, BrowserProcessSession> activeBrowserSessions`（`:55`）。

隐式状态：*缺失* → *已附着（浏览器存活）* → *cancelled* → *已移除*；人工侧另有 *无画面* → *画面 v* → *画面 v+1*，以及 *拖动已入队* / *拖动执行中*。

### 迁移表

| 从 | 触发 / 守卫 | 到 | 证据 |
|---|---|---|---|
| 缺失 | `run()` 入口 | 已注册（`putIfAbsent`） | `:451-453` |
| 缺失 | 已有会话 | 返回 `FAILED "该账号已有浏览器验证任务"` | `:451-452` |
| 已注册 | 入口处线程已中断 | 移除 + `FAILED "滑块验证已取消"` | `:454-457` |
| 已注册、无画面 | `captureManualFrame` | `manualFrame` v1..n | `:1132-1141` |
| 已注册、画面 v | `submitManualDrag` 且 `frameVersion ∈ [v-5, v]` 且队列空 | 拖动入队 | `:602-614` |
| 已入队 | `waitForManual` peek → replay → `poll` | 队列空 | `:1094-1107` |
| 已注册 | `cancel(accountId)` → `terminate()` | `cancelled=true`，进程树被杀 | `:588-593`, `:1965-1971` |
| 已注册 | `run()` 的 `finally` | 移除 | `:518-528` |
| `cancelled` 且尚未 attach | `attach()` 二次检查 | `CancellationException` | `:1957-1963` |

### 驱动者

执行线程（创建、截帧、排空队列）、HTTP 线程（`cancel`/`getManualFrame`/`submitManualDrag`）、Playwright 驱动进程终止。

### 守卫

`putIfAbsent` 保证单会话；`ArrayBlockingQueue(1)` 保证单在途拖动；`attach`/`terminate` 上的 `synchronized`；帧版本窗口 `[v-5, v]`（`:609-611`）拒绝过期拖动。

### 缺陷与风险

1. **`cancelled` 未在每个副作用前复查。** `run()` 只在入口（`:454`）和 `attach()` 内检查中断；`captureManualFrame`（`:1132`）与 `buildCookieText`（`:506`）不再检查——取消发生在页面加载期间时，仍会产出一帧画面和一个 Cookie 结果。

2. **帧版本窗口不对称。** 拒绝**未来**帧（`drag.frameVersion() > currentVersion`），却容忍**5 个版本**的旧帧（`:609-611`）。基于 5 帧之前截图算出的轨迹仍会被回放。

3. **`activeBrowserSessions` 无超时回收。** 只在 `run()` 的 `finally` 移除（`:527`）。一旦 JVM 级卡死（如 `destroyForcibly` 竞态），该账号槽位永久占用；而服务层 `browserPermits` 与这张 Map **各自独立释放**，二者可失步——许可已归还但 `putIfAbsent` 仍被占，症状是永久 `FAILED "该账号已有浏览器验证任务"`。

4. **`null` 哨兵被翻译成瞬时态文案。** `getManualFrame` 返回 `null`（`:596-599`），服务层翻译为 `IllegalStateException("人工浏览器画面正在生成")`（`CaptchaSolveServiceImpl.java:214-221`）——对「会话根本不存在」给出「正在生成」的误导。

5. **驱动进程只靠反射取得。** `extractDriverProcess`（`:1972-1988`）失败即抛 `IllegalStateException`，浏览器泄漏；即一个「已取消」的会话可以「取消成功但没被杀掉」。

---

## M4 — 自动拖动重试循环

**一句话目的：** 最多 5 次尝试过滑块，失败之间重建验证会话。

### 状态集

数值 `attempt ∈ 1..5`（`MAX_AUTO_ATTEMPTS`，runner `:47`），外加 6 个构成真实状态的隐式布尔：`captchaSeen`、`loginPageSeen`、`loginPageVisible`、`SliderWaitResult.captchaContainerSeen`（`:2009`）、`sliderWait.scratchCaptcha()`、`hasRefreshDialog`。

### 状态存放位置

`runAutomatic`（`:942-1036`）与 `waitForManual`（`:1038-1108`）的方法局部变量；不持久化，仅通过 `ProgressUpdate.attempt` 外泄到 `TaskView.attempt`。

### 迁移表

| 从 | 触发 / 守卫 | 到 | 证据 |
|---|---|---|---|
| 循环头 | `attempt <= 5 && now < deadline` | 尝试体 | `:944-946` |
| 尝试 | `isLoginPage` | `FAILED(COOKIE_EXPIRED)` 终态 | `:947-950` |
| 尝试 | `waitForSlider`（12s）识别到句柄 | 拖动 | `:952-957`, `:995` |
| 尝试 | 句柄缺失、容器已在、attempt<5 | `RETRYING_SLIDER` → `RESETTING_SESSION` → 下次尝试 | `:970-981` |
| 尝试 | 句柄缺失、容器未见 | `FAILED` 终态 | `:978-979` |
| 尝试 | 刷新弹窗且 attempt<5 | `RESETTING_SESSION` → 下次尝试 | `:983-991` |
| 尝试 | `dragSlider` 返回 false | sleep 500ms → 下次尝试（**不重开会话**） | `:996-999` |
| 尝试 | `waitForCaptchaGone`（10s） | `SOLVED` | `:1001-1010` |
| 尝试 | 未消失且 attempt<5 | `RETRYING_SLIDER` + `RESETTING_SESSION` → 下次尝试 | `:1016-1028` |
| 循环退出 | 超过 deadline | `TIMEOUT` | `:1033-1034` |
| 循环退出 | 尝试耗尽 | `FAILED "自动拖动未通过验证"` | `:1035` |

### 守卫

仅循环边界。`captchaSeen` 在每次重开时重置为 `false`（`:980`, `:990`, `:1029`），因此 `waitForCaptchaGone` 必须在重新看见滑块之后才可能成功——这是一个真正有效的隐式守卫。

### 缺陷与风险

1. **重试计数纯展示。** 不持久化；服务层 `TIMEOUT`（M1 缺陷 11）会在中途截断循环，计数直接丢失。尝试间除固定 500ms 外无退避（`:997`）。
2. **`Outcome.UNSUPPORTED` 同样未被使用**；「句柄缺失」一律产出 `FAILED` 文案（`:978`），即使页面形态确实不受支持。
3. **两类失败共用一条循环但恢复策略不同**：拖动失败**不**重开会话（`:996-998`），验证未通过**则**重开（`:1027`）——该差异无注释、无文档。
4. `hasRefreshDialog`/`hasDownloadFailure` 只在固定点轮询（`:983`, `:1005`），`waitForCaptchaGone` 期间出现的弹窗只有在 `:1005` 被检查，否则会被当成成功。

---

## M5 — 待验证等待标志（Token 服务）

**一句话目的：** 人工过滑块期间（5 分钟）停止狂刷 token API，并把 `punish` 地址传给 UI。

### 状态集

两张 Map 联合编码**一个**状态：`pendingCaptchaAccounts: Map<Long,String>`（`WebSocketTokenServiceImpl.java:84`）+ `captchaTimestamps: Map<Long,Long>`（`:90`），`CAPTCHA_TIMEOUT = 5min`（`:95`）。
隐式状态：**缺失** / **armed(url+ts)** / **过期（ts 早于 5 分钟）** / **半装填（有 url 无 ts）**。

### 迁移表

| 从 | 触发 / 守卫 | 到 | 证据 |
|---|---|---|---|
| 缺失 | token API 返回 `FAIL_SYS_USER_VALIDATE` 且 `data.url` 存在 | armed（`:381` 写 url，`:382` 写 ts）+ 账号状态 → `-2`（`:384`）+ 抛 `CaptchaRequiredException`（`:390`） | `:370-390` |
| 缺失 | `handleTokenFailure` 命中 `RGV587_ERROR`/`被挤爆啦` | **不** armed；`cookie_status → 3`（`:401`/`:472`）后抛出 | `:462-495` |
| armed | 任意 `getAccessToken`，ts 新鲜 | 重抛 `CaptchaRequiredException`（短路） | `:182-187` |
| armed | 任意 `getAccessToken`，ts 过期 | 缺失（两 Map 均删） | `:188-192` |
| armed | DB 中 token 仍有效 | 缺失（成功路径顺带清除） | `:215-216` |
| armed | `clearCaptchaWait` | 缺失 | `:766-772` |
| armed | `getPendingCaptchaUrl` 且已超时 | 缺失 | `:774-783` |
| **半装填** | 任意 `getAccessToken` | **缺失**（ts 为 null → 走 else 分支删两者） | `:182-193` |
| armed | `restartAfterCredentialUpdate` | 缺失（内部调 `clearCaptchaWait`） | `WebSocketServiceImpl.java:504` |

### 驱动者

请求线程/调度器（`getAccessToken`、`refreshToken`）；`CaptchaSolveServiceImpl`（`clearCaptchaWait` `:262`；`getPendingCaptchaUrl` `:83`, `:159`, `:397`）；`WebSocketServiceImpl.restartAfterCredentialUpdate`（`:504`）。

### 守卫

仅**惰性 TTL**：超时只在被读时判定（`:184`, `:777`）。无调度器、无 CAS。

### 缺陷与风险

1. **双 Map 状态非原子。** `:381-382` 先写 url 再写 ts；并发读者若落在两步之间，看到 `containsKey(url)==true` 而 `ts==null`，走「过期」分支把**刚写入的 URL** 删掉（`:188-192`，「验证超时，清除等待状态」）。用户刚拿到的验证地址被静默丢弃，下一次调用会重新触发平台挑战。

2. **状态与时间戳分家。** 账号状态 `-2` 在 armed **之后**写（`:384`），而 `clearCaptchaWait`（`:766-772`）**只清 Map**，不恢复 `xianyu_account.status`。除非后续 token 获取成功（`:838-849`）或 Cookie 刷新成功（`CookieRefreshServiceImpl.java:580-607`），否则该账号永久停在 `-2` 且没有任何待验证地址——一个孤立状态。

3. **空闲账号无回收。** 某账号 armed 后不再被轮询，其条目一直存活到下一次 `getAccessToken`/`getPendingCaptchaUrl`。

4. **超时窗口与任务窗口打架。** 这里 5 分钟、浏览器任务也 5 分钟（M1）、而求解器自己的 deadline 起算更晚（M1 缺陷 11）。人工拖动完成时等待标志很可能已过期，`pendingCaptchaUrl` 返回 null，恰好是 `credentialFailureMessage` 的入参（`CaptchaSolveServiceImpl.java:405-409`），于是失败文案从「平台仍要求验证」翻转为「WebSocket重新连接失败」。**失败文案取决于时序而非状态。**

---

## M6 — WS accessToken 缓存

**一句话目的：** 把 20 小时有效的 `accessToken` 存进 Cookie 行，并支持强制失效重取。

### 状态集

`xianyu_cookie.websocket_token`（TEXT，`V1__baseline.sql:53`）+ `token_expire_time`（BIGINT ms，`:54`）。
状态：**fresh**（`expire > now`）、**expired**（`expire <= now`）、**cleared**（`expire = 0`）、**未签发**（两者 `NULL`）。
TTL 常量：`TOKEN_VALID_DURATION = 20h`（`WebSocketTokenServiceImpl.java:78`）。

### 迁移表

| 从 | 触发 / 守卫 | 到 | 证据 |
|---|---|---|---|
| 未签发/已过期 | token API SUCCESS | fresh（`token` + `now+20h` 单条 UPDATE） | `:340-347`, `:901-910` |
| fresh | `getAccessToken` | fresh（DB 直取，并清 pending captcha） | `:208-221` |
| fresh/expired | `clearToken`（刷新、重连、凭证更新） | cleared（`token_expire_time = 0`，**token 文本保留**） | `:750-760` |
| cleared/expired | `needsRefresh` | 判定需要刷新 | `TokenRefreshServiceImpl.java:361-370` |
| fresh | WS 注册成功回调 | fresh（重新保存，**重新给 20h**） | `WebSocketServiceImpl.java:320-324`, `:745-747` |
| fresh | 手动 `saveToken` 接口 | fresh | `WebSocketController.java:810` |

### 驱动者

`getAccessTokenWithRetry`（请求线程，按账号加锁，`:120`, `:135-144`）、`refreshToken`（`:789-817`）、`WebSocketServiceImpl` 每 60s 的 token 刷新循环（`:650-690`）、`XianyuWebSocketClient.onRegistrationSuccess`。

### 守卫与执行强度

只有 `:208-221` 的时间戳比较。`saveTokenToDatabase` 的单条 `LambdaUpdateWrapper` 使 token 与 expiry **原子**更新（这是本域少见的正确点）；`clearToken` 故意只把时间戳归零。

### 缺陷与风险

1. **「未签发」与「无需刷新」不可区分。** `token_expire_time` 为 `NULL` 时 `needsRefresh` 直接走到 `:375` 返回 `false`（`:361-370` 跳过）。经备份导入得到无 `tokenExpireTime` 的行（`AccountBackupHandler.java:122`）因此**永远**不会被 `scheduledRefreshWebSocketToken` 刷新，每分钟的检查对它静默失效。

2. **`clearToken` 保留 token 文本**（`:754-758`）。任何跳过过期检查的读者会拿到「存在但已作废」的 token；`WebSocketController:162-166` 检查正确，但 `:388` 的「Token ✅」显示会把 cleared 状态的 token 渲染成有效。

3. **同一行存在两个无 CAS 的写者**：`updateCookiesFromResponse` 一条 UPDATE 写 cookie 文本 + `cookie_status=1`（`:668-674`），**第二条** UPDATE 写 `m_h5_tk`（`:677-683`）；`saveTokenToDatabase` 是第三条（`:905-910`）。其间该行短暂不一致（新 cookie 文本 + 旧 `_m_h5_tk`）——正是并发 `getAccessToken` 用错 `_m_h5_tk` 签名的窗口。

4. **`updateCookiesFromResponse` 悄悄把 `cookie_status` 修回 1**（`:673`），绕过 M7 的迁移，与「只有 hasLogin/浏览器兜底才能恢复有效性」的设计相矛盾。

---

## M7 — Cookie 凭证状态

**一句话目的：** 标记存储的 Cookie 能否用于平台调用。

### 状态集

`xianyu_cookie.cookie_status` TINYINT：`1` 有效 / `2` 过期 / `3` 失效（风控）。
文档化位置：实体 `XianyuCookie.java:41`（`1:有效 2:过期 3:失效`）、接口 `AccountService.java:98`、控制器文案 `WebSocketController.java:439-452`。
**SQL 无注释**（`V1__baseline.sql:51` 是裸 `TINYINT NOT NULL DEFAULT 1`），**无枚举**。

### 状态存放位置

DB 列，一账号一行（`uk_cookie_account`，`V1:58`）。**三套写入实现：**

| 实现 | 位置 | 通知语义 |
|---|---|---|
| `AccountServiceImpl.updateCookieStatus`（公开，带 notify） | `AccountServiceImpl.java:410-453` | `status==2 && old!=2 && sendNotify` → 邮件 |
| `WebSocketTokenServiceImpl.updateCookieStatus`（私有重复实现） | `WebSocketTokenServiceImpl.java:856-896` | 同上，但自带 select 与日志 |
| 内联 `LambdaUpdateWrapper` | `CookieRefreshServiceImpl.java:199-203,251-258,271-276,497-503`；`TokenRefreshServiceImpl.java:228-232,265-269` | **无通知、无 `updatedTime`** |

### 迁移表

| 从 | 触发 / 守卫 | 到 | 证据 |
|---|---|---|---|
| 任意 | hasLogin 成功**且有 Cookie 变化** | `1`（+`updatedTime` +`m_h5_tk`） | `CookieRefreshServiceImpl.java:251-258` |
| 任意（≠1） | hasLogin 成功但 Cookie **无变化** | `1` | `:268-278` |
| 任意 | 浏览器兜底刷新成功 | `1`（+`m_h5_tk`） | `:497-503` |
| 任意 | 扫码登录 / 手动添加 / 完整凭证更新 | `1`（+30 天 `expire_time`） | `AccountServiceImpl.java:155,167,296,539,550` |
| 任意 | token 请求中的 Set-Cookie 合并 | `1` | `WebSocketTokenServiceImpl.java:669-674` |
| 任意 | token API 命中 `RGV587_ERROR`/`被挤爆啦` | `3` | `WebSocketTokenServiceImpl.java:401`, `:472` |
| 任意 | hasLogin 响应命中风控 | `3` | `CookieRefreshServiceImpl.java:199-203` |
| 任意 | hasLogin 重试耗尽 | `2`（+邮件，仅一次） | `WebSocketTokenServiceImpl.java:551-554` |
| 任意 | hasLogin 后 `m_h5_tk` 仍失败 | `2` | `TokenRefreshServiceImpl.java:228-232` |
| 任意 | hasLogin 重试耗尽（`m_h5_tk` 路径） | `2` | `TokenRefreshServiceImpl.java:265-269` |
| 任意 | WS 消息 `code == 401` | `2` | `WebSocketMessageRouter.java:168-171` |
| 任意 | WS 消息 `code == 500` | `2` | `WebSocketMessageRouter.java:172-174` |
| 任意 | 商品/AI 接口 `TokenError` | `2` | `ItemServiceImpl.java:149`、`ImageUploadServiceImpl.java:79` |
| 任意 | 备份导入 | **原值照写，可为 NULL** | `AccountBackupHandler.java:116` |

### 驱动者

请求线程（API 调用）、`TokenRefreshServiceImpl`（2 个调度器）、`WebSocketServiceImpl`（重连/Cookie 兜底）、WS 消息线程（`WebSocketMessageRouter`）、备份导入器。

### 守卫与执行强度

**只有读侧闸门，且只在部分读者上生效。**

`cookie_status = 1` 过滤点：
- `AccountServiceImpl.getCookieByAccountId`（`:193`）
- `getCookieByUnb`（`:228`）
- `getCookieByAccountNote`（`:263`）
- `getMh5tkByAccountId`（`:329`）
- `XianyuAccountMapper.selectReconnectableAccounts`（`XianyuAccountMapper.java:19-23`）

**不过滤、仍可对 2/3 状态 Cookie 采取行动**的读者：`getLatestCookieFromDb`（`:149-164`）、`doCheckLoginStatus`（`CookieRefreshServiceImpl.java:128-133`）、`refreshMh5tkTokenWithRetry`（`TokenRefreshServiceImpl.java:131-134`），以及所有直接 `cookieMapper` 更新。
**写侧零守卫**：任何调用方可以写任何值。

### 缺陷与风险

1. **瞬时服务端错误被提升为终态凭证状态。** `code == 500` → `cookie_status = 2`（`WebSocketMessageRouter.java:172-174`）。这个 2 会连锁：(a) 所有 API 路径的 `getCookieByAccountId` 返回 null；(b) 账号从 `selectReconnectableAccounts` 消失；(c) 后续每次 `startWebSocket` 都以 `CookieNotFoundException` 失败（`WebSocketServiceImpl.java:176-179`）。

2. **401 自锁竞态。** 401 时路由写 `cookie_status = 2`，同时 `XianyuWebSocketClient` 触发 `onTokenExpired` → `startWebSocket`（`:326-357`）。若状态写入先落地，`getCookieByAccountId` 返回 null，重连死在 `CookieNotFoundException`——**自动恢复被自己的状态更新打败**。

3. **三套实现、三种行为。** 私有重复实现（`WebSocketTokenServiceImpl:856-896`）的邮件逻辑与 `AccountServiceImpl` 相同但不在同一个事务里；`CookieRefreshServiceImpl`/`TokenRefreshServiceImpl` 的内联写法既不发通知也不写 `updatedTime`。公开方法 `updateCookieStatus(Long,Integer)`（`:402-406`）及其 notify 变体只被 `WebSocketMessageRouter`、`ItemServiceImpl`、`ImageUploadServiceImpl` 使用。

4. **`2` 与 `3` 语义不同、行为完全相同。** 每个读闸门都只是「隐藏该 Cookie」；没有任何地方区分「过期、可自动续期」与「风控、必须人工」。但邮件逻辑却按 `2` 与 `3` 分叉（`status==2` 才发），因此 `3`（风控）从这条路径**永远**不会触发过期邮件。

5. **多字段非原子。** `AccountServiceImpl.updateCookieStatus` 用先前读出的实体 `updateById` 写 status + `updatedTime`（`:427-429`）——整行盲写，若期间有刷新落地，会把旧的 `cookie_text`/`websocket_token` 复活。

6. **备份导入穿透一切**，包括 NULL（`AccountBackupHandler.java:116`）。`cookie_status = NULL` 在三种谓词下的表现互相矛盾：过滤 `=1` 的读者排除它；`CookieRefreshServiceImpl:268`（`== null || != 1`）把它当无效；而 `Objects.equals(status, 2)` 的通知逻辑把它当「不是过期」。状态机无法命名的行。

---

## M8 — 账号状态（本域缺陷最密集的一台）

**一句话目的：** 决定该账号是否有资格参与自动连接与自动恢复。

### 状态集

`xianyu_account.status` TINYINT（`V1__baseline.sql:38`，默认 1，`KEY idx_account_status` `:43`）。
实际读写只用 `1` 与 `-2`。
**实体文档写 `-1`**（`XianyuAccount.java:44`：`1:正常 -1:需要手机号验证`）；
**前端只映射 `-1`**（`vue-code/src/utils/index.ts:54-58`：`'-1': {text:'需要验证'}`）。
无枚举、无常量、SQL 无注释。

### 迁移表

| 从 | 触发 / 守卫 | 到 | 证据 |
|---|---|---|---|
| 任意 | 扫码登录 / 手动添加 / 重新登录 | `1` | `AccountServiceImpl.java:129`, `:138` |
| 任意 | 完整凭证更新 `updateAccountCookie` | **不变**（只写 unb + cookie） | `AccountServiceImpl.java:511-566` |
| 任意 | token API 返回 captcha URL | `-2` | `WebSocketTokenServiceImpl.java:822-833`（被 `:384` 调用） |
| `-2` | token 获取成功 | `1`（守卫 `== -2`） | `WebSocketTokenServiceImpl.java:838-849` |
| 任意 | Cookie 刷新失败（hasLogin 与浏览器兜底均失败） | `-2` | `CookieRefreshServiceImpl.java:530-578`（调用点 `:375,392,425,440,482,517`） |
| `-2` | Cookie 刷新成功 | `1` | `CookieRefreshServiceImpl.java:580-607`（调用点 `:362`） |
| `-2` | 滑块求解成功 | `1`（**仅经由** `restartAfterCredentialUpdate` → `getAccessToken` → `:841`） | `CaptchaSolveServiceImpl.java:356-358` |
| 任意 | 备份导入 | 原值照写（可为 NULL） | `AccountBackupHandler.java:78`, `:92` |

### 谁在读（读取点才是真正的状态机定义）

| 读取点 | 谓词 | 不满足时的后果 | 证据 |
|---|---|---|---|
| `selectReconnectableAccounts` | `status = 1 AND cookie_status = 1` | 账号永不自愈 | `XianyuAccountMapper.java:19-23` |
| `WebSocketRecoveryScheduler.maintainConnections` | 依赖上一条 | 不发起重连 | `WebSocketRecoveryScheduler.java:37-45` |
| `TokenRefreshServiceImpl.scheduledCookieKeepAlive` | `status == 1` | 不做 hasLogin、不刷 `m_h5_tk` | `TokenRefreshServiceImpl.java:415` |
| `scheduledRefreshWebSocketToken` | `status == 1` | 不刷 WS token | `:495` |
| `refreshAllAccountsTokens` | `status == 1` | （仅手动接口） | `:534` |
| 商家运营资源 | `status` 直接透传 | 资源视图陈旧 | `MerchantOperationsService.java:1101` |
| 前端 `getAccountStatusText` | `-1` | `-2` 渲染成「未知」 | `vue-code/src/utils/index.ts:54-58` |

### 守卫与执行强度

**没有任何形式化守卫。** 每个写入者都是「读整行 → 改字段 → `updateById`」：

- `WebSocketTokenServiceImpl.updateAccountStatusToNormal`（`:838-849`）：`select → if (status == -2) setStatus(1) → updateById`
- `CookieRefreshServiceImpl.updateAccountStatusToNormal`（`:580-607`）：同型
- `markAccountAsCookieRefreshAbnormal`（`:530-578`）：`select → if (Objects.equals(status,-2)) return → setStatus(-2) → updateById`
- `AccountServiceImpl.saveAccountAndCookie`（`:125-132`）与 `updateAccountCookie`（`:516-524`）：同型

### 缺陷与风险

1. **`-2` 是需要人工介入的粘性吸收态。** 它由两个服务写入，而所有自动恢复循环都先用 `status == 1` 过滤（见上表）。(a) 调度器直接排除该账号；(b) `scheduledCookieKeepAlive` 也跳过 `status != 1`；(c) 唯一自动出路 `updateAccountStatusToNormal` 只在 token 获取成功时才被调用，而获取 token 的入口又被 (a) 挡住。净效果：**`-2` 意味着「在有人动它之前一直离线」**，但没有任何地方这样声明。

2. **幽灵值 `-1`。** 实体文档写了、UI 读了、**后端从不写**（全仓无账号 `setStatus(-1)`；`-1` 的命中全是商品/工作流状态）。结果：所有真正「需要验证」的账号在 UI 上显示为「未知」，而状态机里叫 `-2`。

3. **一个码，两种含义。** `-2` =「平台要滑块」（`WebSocketTokenServiceImpl:828`）**且** `-2` =「Cookie 刷新失败，异常待处理」（`CookieRefreshServiceImpl:558`）。日志/文案不同（「需要验证」vs「异常待处理」），字段相同——任何消费者都无法区分。

4. **空值拆箱 NPE 吞掉迁移。** `WebSocketTokenServiceImpl:841` 用 `account.getStatus() == -2`（Integer→int 拆箱）→ status 为 null 时抛 NPE，被外层 `catch (Exception)`（`:846-848`）吞掉并记为「更新账号状态失败」。账号静默地永不回到 `1`。同一迁移在 `CookieRefreshServiceImpl` 里用 `Objects.equals`（`:544`, `:583`）——两种惯用法，一种安全一种不安全。

5. **同一个 NPE 会中断整轮调度。** `TokenRefreshServiceImpl:415`、`:495`、`:534` 都是 `account.getStatus() == 1`。一行 NULL 状态（可经备份导入产生）会抛出 `for` 循环，落入外层 `catch`（`:473-475`, `:514-516`, `:550-552`），**中断该轮所有剩余账号**的保活/token 刷新。

6. **丢失更新竞态。** 因为每次迁移都是 `select → mutate → updateById`，并发的 `-2` 写入可被过期的「写 1」覆盖（反之亦然），且 `unb`、`device_id`、`websocket_sync_*` 会被预读值覆盖。`advanceWebSocketSyncCursor`（`XianyuAccountMapper.java:25-30`）证明本代码库会写带守卫的 UPDATE——状态迁移没有用它。

7. **`updateAccountCookie` 故意不写状态**（`:511-566`）：滑块求解写入了全新的有效 Cookie，账号却仍是 `-2`。它之所以能恢复，只是因为 `restartAfterCredentialUpdate` 随后取 token 命中 `:841`——两个服务之间的隐式二阶依赖。

8. 状态变化没有时间戳、没有操作者、没有 `last_error`，`-2` 既无原因、无次数、无过期。

---

## M9 — 扫码登录会话

**一句话目的：** 下发二维码，跟随 扫描→确认，落库凭证。

### 状态集：三套词汇表

**后端**（`QRLoginSession.status`，纯 `String`，`QRLoginSession.java:13`，注释列出 `waiting, scanned, success, expired, cancelled, verification_required`）：实际还写 `error`（`QRLoginServiceImpl.java:657, :693, :698`），**注释未列**。

**前端**（`convertToFrontendStatus`，`:606-620`）：`waiting→pending`、`success→confirmed`、`scanned/expired/cancelled/verification_required→同名`、default→`pending`；另加缺失会话时响应专用的 `not_found`（`:551`）。

**平台**（`qrCodeStatus`，`:489-541`）：`CONFIRMED`、`NEW`、`SCANED`、`EXPIRED`、其他。

过期：`createdTime + expireTime(300000)`，`isExpired()`（`QRLoginSession.java:26-31`）。

### 状态存放位置

内存 `Map<String,QRLoginSession> sessions`（`ConcurrentHashMap`，`QRLoginServiceImpl.java:41`），键为 UUID。
`status` 是 `@Data` POJO 上的**非 volatile** 字段，由轮询线程写、HTTP 线程读；Map 内**明文保存完整 Cookie**。

### 迁移表

| 从 | 触发 / 守卫 | 到 | 证据 |
|---|---|---|---|
| ∅ | `generateQRCode` 且平台成功 | `waiting` 并入 Map | `:327-330` |
| `waiting` | 平台 `SCANED` | `scanned`（仅当当前是 `waiting`） | `:430-435` |
| `waiting`/`scanned` | 平台 `CONFIRMED` 且无 `iframeRedirect` | `success` + Cookie 落库 | `:491-503`, `:531` |
| `waiting`/`scanned` | `CONFIRMED` 且 `iframeRedirect` | `verification_required`（+url） | `:493-499` |
| 任意 | 平台 `EXPIRED` | `expired` | `:425-429` |
| 任意 | 平台返回**其他任何**值 | `cancelled` | `:436-440` |
| 任意 | 5 分钟到时 / 轮询异常 | `expired`（除非在保留名单内） | `:452-455`, `:457-463` |
| 任意 | `getSessionStatus` 发现 `isExpired()` 且 status≠`success` | `expired`（**读路径写状态**） | `:556-558` |
| `success` | UNB 为空 / 保存失败 / 异常 | `error` | `:657`, `:693`, `:698` |
| `error` | 收尾超时扫描 | `expired`（错误态被抹除） | `:452-455` |
| 任意 | `cleanupExpiredSessions` | 移除 | `:635-647` |

### 驱动者

每会话一个 `taskExecutor` 线程（轮询循环，`:333`, `:397-464`）、HTTP 线程（`generateQRCode`/`getSessionStatus`/`getSessionCookies`）、平台。

### 守卫

仅两处：`waiting→scanned` 的条件（`:432`）与收尾保留名单（`:452`）。无 CAS；`sessions.put` 只在创建时（`:330`），`sessions.remove` 只在清理时（`:644`）。

### 缺陷与风险

1. **`error` 不可见。** `:566-596` 的 `switch` 没有它的 case（落到 `default: "未知状态"`），`convertToFrontendStatus` 把未知映射成 `pending`（`:617-618`）。凭证落库失败因此对客户端表现为「等待扫码」，轮询继续跑。**写而不读。**

2. **`error` 随后被改写成 `expired`**（`:452-455`）：一旦 5 分钟窗口收尾，一个可诊断的失败变成与「没人扫码」完全相同的状态。

3. **未知平台状态 → `cancelled`**（`:436-440`）。任何新增/异常的 `qrCodeStatus` 都被报成「用户取消登录」，且轮询循环随之退出。

4. **网络/解析失败 → `NEW`**（`:541`）。`pollQRCodeStatus` 在 HTTP 失败或 JSON 缺失时返回 `"NEW"`，传输错误与「还没扫码」不可区分；端点持续故障时，会话在 5 分钟内一直停在 `waiting`，而不会暴露错误。

5. **会话永不自动回收。** `cleanupExpiredSessions`（`:635-647`）**没有 `@Scheduled`**，只由 `POST /api/qrlogin/cleanup`（`QRLoginController.java:50-51`）触发；而前端 API 封装 `cleanupQRSessions`（`vue-code/src/api/qrlogin.ts:29-33`）**从未被调用**。成功与废弃的会话连同其完整 Cookie 一直留在内存里直到进程结束。

6. **会话内容数据竞态。** `status` 是普通字段；`cookies` 是普通 `HashMap`，由轮询线程写（`:513`, `:523`, `:527`），同时 `getSessionStatus` 读（`:576-578`）、`getSessionCookies` 序列化（`:627`）。无同步 → 跨线程可见性问题 + `HashMap` 结构竞态，而这个对象承载的正是凭证。

7. **读路径写状态**（`:556-558`）：一次轮询 GET 就能把会话推进到 `expired`。

8. **二维码时钟先于工作启动。** `createdTime` 在构造函数里写（`:22`），早于 `getMh5tk` + `getLoginParams` + 出图（合计可达 ~90s 网络时间），因此实际可扫时间 < 5 分钟，而对外仍宣称 5 分钟。

9. **前端词表缺口。** `QRLoginDialog.vue` 只处理 `pending/scanned/confirmed/expired`（`vue-code/src/views/accounts/components/QRLoginDialog.vue:67-81`）；`cancelled`、`verification_required`、`not_found` 直接落空——既不更新文案也**不 `stopPolling()`**，风控登录会永久轮询，且后端给出的 `verificationUrl` 永不展示。

10. `success` 会话在导出 Cookie 后也不移除，重复登录会累积多条持有凭证的 `success` 条目。

---

## M10 — 平台登录会话（单设备 JWT 登记表）

**一句话目的：** 让「已登录」等价于「`sys_login_token` 里有一行」，于是第二次登录会顶掉第一次。

### 状态集

无状态列，状态是位置性的：**行在且 `expire_time` 未到** = 有效；**行在且已过期** = 已死未收；**行不存在** = 已登出/被顶/从未登录。表定义：`V1__baseline.sql:17-31`，`UNIQUE (token)`，`user_id` 上只有**非唯一**索引 `idx_login_token_user`（`:28`）。

### 迁移表

| 从 | 触发 / 守卫 | 到 | 证据 |
|---|---|---|---|
| ∅ | `login()` 成功 | 存在（先删该用户全部，再插一条） | `AuthServiceImpl.java:133-147` |
| 存在 | 同用户第二次登录 | 旧行删除，一条新行 | `:134-136` |
| 存在 | `logout(token)` | 移除 | `:250-263` |
| 存在 | `changePassword` | 该用户全部行移除 | `:244-246` |
| 存在 | 管理员密码重置 | 该用户全部行移除 | `AdminCredentialRecovery.java:103-104` |
| 存在 | 平台用户改角色/状态 | 该用户全部行移除 | `PlatformUserService.java:108-113`, `:136-139` |
| 存在且过期 | 任意 `isTokenValid` | 移除（惰性 GC） | `AuthServiceImpl.java:174-179` |
| 存在 | 用户 `status` 变为 0 | 行保留，直到下一次请求被拒 | `AuthInterceptor.java:77` |
| ∅ | 任意请求 | 401「账号已在其他设备登录」 | `AuthInterceptor.java:68-70` |

### 驱动者

HTTP 线程（`POST /api/login/login|logout`）、启动期 `ApplicationRunner`、平台用户管理接口。

### 守卫与执行强度

`AuthInterceptor` 链：JWT 签名/过期（`:62`）→ DB 行存在 + `expire_time`（`:68`）→ 用户 `status == 1`（`:77`）。`isTokenValid` 删除过期行是唯一 GC。

### 缺陷与风险

1. **单设备不变量不由 DB 保证。** `login()` 先 `DELETE ... WHERE user_id` 再 `INSERT`（`:134-147`），**无事务**且 `user_id` 上无唯一键（`V1:28` 是普通索引）。两个并发登录会各自删、各自插 → 一个用户两条有效 token，「挤下线」静默失效。

2. **两套不同的 status 谓词。** `login()` 只拒绝 `status == 0`（`:117-119`），而拦截器只接受 `status == 1`（`:77`）。状态为 `NULL` 或任何其他值的行可以**成功登录并拿到 token**，但此后每个请求都被判为「账号已被禁用」——一旦行离开 {0,1}，就进入「登录成功但完全不可用」的可达状态。

3. **过期行 GC  opportunistic**，因此死行累积；没有调度器，也没有查询用到 `idx_login_token_expire`（`V1:29` 是死索引）。

4. **撤销与过期不可区分。** `logout`/`changePassword` 是硬删除，因此拦截器那句「账号已在其他设备登录」对「你自己登出了」和「你的密码变了」都是错的。

5. **解析失败 = 永久有效。** `expire_time` 解析异常被吞并返回 `true`（`:180-184`）；此时只剩 JWT 自身的过期仍生效。

6. **`changePassword` 无事务**（`:232-248`）：改密码与删 token 是两条独立语句；中间失败会让旧 token 在新密码下继续有效（`AdminCredentialRecovery` 有同样问题，而它把「旧登录态已清除」写成了保证，`:102-106`）。

---

## M11 — 登录失败限流

**一句话目的：** 每 IP 每 10 分钟最多 5 次失败。

### 状态集

缓存键 `login_attempt:<ip>`（`AuthServiceImpl.java:44`, `:189`），值为 `AtomicLong` 包装的计数，TTL 10 分钟（`:41`），阈值 5（`:40`）。
状态：**缺失** / **1..4（放行）** / **≥5（锁定）** / **因过期而缺失**。

### 迁移表

| 从 | 触发 | 到 | 证据 |
|---|---|---|---|
| 缺失 | 首次失败 | 计数 **2**，TTL 10 分钟 | `:211` |
| 1..4 | 失败 | +1（仅在无 TTL 时补 TTL） | `:213-217` |
| ≥5 | 任意登录尝试 | 拒绝（「请10分钟后再试」） | `LoginController.java:99-101`；`AuthServiceImpl.java:195-196` |
| 任意 | 登录成功 | 移除 | `LoginController.java:125`；`AuthServiceImpl.java:221-225` |
| 任意 | TTL 到期 | 缺失 | `LocalMapCacheServiceImpl.java:63-68` |

### 驱动者

仅 HTTP 线程（`LoginController.java:99`, `:125`, `:135`）。

### 守卫

`checkLoginAttempt` 在**任何凭据校验之前**调用一次（`:99`）；失败记录写在 `login()` 的 `catch` 块（`:135`）。

### 缺陷与风险

1. **首次计数翻倍（off-by-one）。** `cacheService.put(key, cacheService.increment(key), ...)`（`:211`）把 `increment` 求值了两次：第一次创建计数为 1，第二次推进到 2，而存进去的是 2。首次失败因此花掉 2 次配额 → **第 4 次**失败即锁定，而非文档宣称的 5 次。
2. **把所有异常都算作失败。** `recordLoginFailure` 对 `login()` 抛出的任何异常执行（`:135`），包括仓储层错误与 `RuntimeException("账号已被禁用")`——基础设施故障与被禁账号探测都会锁掉该 IP。
3. **只看 IP。** 没有 per-username 维度；分布式攻击可绕过；未开启 `app.security.trust-proxy` 时所有客户端共享 `getRemoteAddr()`。
4. **首次写入非原子**（`containsKey` → `increment` → `put`，`:209-218`）：两个并发失败都走首分支，各写一个新计数，丢失一次自增。
5. 窗口从**首次失败**固定起算而非滑动（仅在 TTL 缺失时修复，`:215-217`），慢速攻击可低成本重置。

---

## M12 — 启动期凭据供给

**一句话目的：** 启动时保证有一个可用的平台管理员，并支持环境变量一次性重置。

### 状态集

`sys_user` 上两台独立启动机器：

- **Bootstrap**（`PlatformAdminBootstrap.java:43-76`）：*无启用管理员* →（环境变量齐备且合法且用户名未被占）*创建*；否则*告警并保持原状*；*已有管理员* → *no-op*。
- **Recovery**（`AdminCredentialRecovery.java:56-108`）：*存在重置变量* →（校验通过）*改密 + 撤 token*；随后**总是** *输出自检日志*。
- 外加 SQL 迁移 `V12`（`V12__platform_user_roles.sql:6-11`）把「启用用户中 `MIN(id)`」提升为 `ADMIN`——同一状态的**第三个**一次性写入者。

### 迁移表

| 从 | 触发 / 守卫 | 到 | 证据 |
|---|---|---|---|
| 无启用 ADMIN | `@Order(10)` 执行，变量为空或 `change-me-*` | 不变（告警） | `PlatformAdminBootstrap.java:50-54` |
| 无启用 ADMIN | 凭据长度不合规 | **抛异常**（应用启动失败） | `:56-59` |
| 无启用 ADMIN | 用户名已被占用（例如被 USER 占用） | 不变（告警） | `:60-65` |
| 无启用 ADMIN | 全部守卫通过 | 插入 ADMIN，status=1 | `:67-76` |
| 任意 | `ADMIN_RESET_*` 均设置且合法且恰好一行 | 改密 + role→ADMIN + status=1，**删除全部 token** | `AdminCredentialRecovery.java:63-106` |
| 任意 | 变量不全 / 不合法 / 0 行 / >1 行 | 不变（告警） | `:66-97` |
| 任意 | 启动后仍无启用 ADMIN | 仅告警 | `:110-118` |
| 启用用户 | 升级后 V12 一次性执行 | `MIN(id)` 的启用用户变成 ADMIN | `V12__platform_user_roles.sql:6-11` |

### 驱动者

仅启动期 Spring `ApplicationRunner`。无运行时迁移。

### 守卫

每条路径的启动守卫是穷尽的（长度/格式/重复/占位密码）。Bootstrap 有 `@Transactional`（`:42-43`）；**Recovery 没有**。

### 缺陷与风险

1. **Bootstrap 是一次性闩锁且不会重新武装。** `adminCount > 0` 直接短路（`:47-49`）。若禁用最后一个管理员（`PlatformUserService.updateUser` 只阻止移除最后一个**启用**管理员，`:118-130`），下次启动时：有启用管理员就继续短路；一旦**零**启用管理员且 `BOOTSTRAP_ADMIN_*` 未设置，唯一出路是重置变量。没有「没有就建、有就校验」的自愈逻辑。

2. **Recovery 静默提权。** 重置无条件 `role = ADMIN`（`:98`），且没有审计目标（只有 `log.info`）。任何能设置环境变量的人都可以把普通租户提升为平台管理员。

3. **非原子：** 改密（`:96`）与撤 token（`:103-104`）不在事务里；日志断言「旧登录态已清除」（`:106`）无论删除是否成功。

4. **三个写入者、没有仲裁者：** V12 只看 `MIN(id)`、不看密码是否合法；bootstrap 在有管理员时拒绝介入；recovery 直接覆盖 role/status。经 V12 迁移且密码非 BCrypt 的库只会被**报告**（`AdminCredentialRecovery.java:124-131`），不会被修复。

5. **`describePasswordHash` 会打印哈希前 4 字符与长度**（`:155-171`）——日志里的轻度哈希泄露。

6. 启动顺序意味着 Recovery 在 Bootstrap 可能刚建好管理员**之后**执行；若重置用户名恰好等于新建的管理员，重置会静默覆盖启动密码。

---

## M13 — WS 重连与滑块退避（M5/M7 的驱动侧）

**一句话目的：** 让所有凭证有效的账号保持连接；平台要求滑块时退避。

### 状态集（全部在内存，8 个并行 Map 构成一台机器）

`WebSocketServiceImpl.java`：
`manuallyStoppedAccounts: Set<Long>`（`:84`）、`connectionRestartFlags: Map<Long,Boolean>`（`:110`）、`reconnectTasks: Map<Long,Future>`（`:113`）、`reconnectAttemptCounts: Map<Long,AtomicInteger>`（`:120`）、`captchaRetryCounts: Map<Long,AtomicInteger>`（`:122`）、`lastDisconnectNotifyTimes: Map<Long,Long>`（`:135`）、`heartbeatTasks`/`tokenRefreshTasks`/`tokenRetryTasks`、`lastHeartbeatResponseTimes`/`lastHeartbeatSendTimes`/`lastTokenRefreshTimes`（`:92-108`），外加 `webSocketClients`（真实连接）与 DB 上的 M7/M8。

隐式连接态：**人工停机** / **连接中** / **已连且健康** / **已连但心跳过期** / **断开（重连第 n 次）** / **滑块阻塞（退避第 n 次）** / **放弃**——最后一个**不存在**。

### 迁移表

| 从 | 触发 / 守卫 | 到 | 证据 |
|---|---|---|---|
| 任意 | `stopWebSocket`（人工） | 人工停机 + 关闭 client | `:454-495`, `:462` |
| 人工停机 | `startWebSocket` | 清除标志 | `:140-143` |
| 人工停机 | `ensureConnected` | **拒绝** | `:146-149` |
| 人工停机 | `restartAfterCredentialUpdate` → `startWebSocket` | **标志被清除（不变量被破坏）** | `:500-511` |
| 任意 | `onConnectionClosed` 且 `connectionRestartFlags == true` | 0 延迟重连 | `:365-373`, `:332` |
| 任意 | `onConnectionClosed` 无标志 | `reconnectDelay` 后重连 | `:370-373` |
| 已连 | 心跳超时（`interval+timeout`） | 停止 + 排重连 | `:596-608`, `:803-810` |
| 断开 | `scheduleReconnect` | attempt+1，`min(delay*2^min(n-1,4), 60)` | `:823-847` |
| 重连中 | `startWebSocket` 成功 | attempt 归零 | `:876-881` |
| 重连中 | 失败 | 达阈值发通知 + 重新调度 | `:884-916` |
| 任意 | token 刷新抛 `CaptchaRequiredException` | `captchaRetryCounts+1`，延迟 `base*2^n` 封顶 | `:744-756`, `:768-775` |
| 滑块阻塞 | 刷新成功 | `captchaRetryCounts.remove` | `:740` |
| 滑块阻塞 | 手动停/启 | **计数保留**（`stopHeartbeat` 不清） | `:949-985` |
| 任意 | `stopAllWebSockets` | 清标志 | `:982` |

### 守卫

按账号 `connectionLocks` 串行化启动（`:152-158`）；重连（`:825-831`）与 token 重试（`:785-795` 用 `compute`）各保证一账号一任务；指数退避有封顶。

### 缺陷与风险

1. **人工停机被凭证更新无视。** `restartAfterCredentialUpdate` 调 `startWebSocket`（`:509`），后者清除 `manuallyStoppedAccounts`（`:141`）。过完滑块，用户显式按下的「停止」被静默撤销。
2. **`captchaRetryCounts` 跨手动启停存活**（`stopHeartbeat` 不清，`:949-985`；`startWebSocket` 也不清），因此 5 次失败后手动重启仍被限流，最长 `captchaRetryMaxSeconds` = 3600s（`WebSocketConfig.java:45-50`）。
3. **`reconnectAttemptCounts` 只在重连成功时归零**（`:876-881`）和 `restartAfterCredentialUpdate`（`:513-517`）——手动启动不清，退避状态（最长 60s）跨用户重启延续。
4. **没有终态。** `maxReconnectAttempts = 10`（`WebSocketConfig.java:97`）**从未被读取**（`grep getMaxReconnectAttempts` 无命中）；重连无界，符合设计注释（`:826-828`）但与配置矛盾。
5. **两套彼此独立的退避机器**（5s 重连 vs 300s 滑块），没有共享的「该账号已被阻塞」概念；滑块阻塞期间心跳计划仍活到 stop 为止。
6. `connectionRestartFlags` 基本是「写多读一」的布尔：`:332` 与 `:667` 置 true，`:366` 读一次，`:833`/`:982` 清除——一个把调用栈信息重复编码到 Map 的标志。

---

## 14. 贯穿本域的缺陷模式

### 14.1 多字段更新普遍非原子

状态 + 过期 + 错误 + `m_h5_tk` 在五台机器里都是分语句/分实体写入：

- `AccountServiceImpl.updateAccountCookie`（`:511-566`）：先写账号 UNB，再写 cookie 文本/`m_h5_tk`/status/过期，最后 `riskControlService.clearCircuit`——跨三个子系统，虽有 `@Transactional`，但**内存侧**的任务/连接状态在事务外更新。
- `WebSocketTokenServiceImpl.updateCookiesFromResponse`（`:668-683`）：cookie 文本 + status 一条 UPDATE，`m_h5_tk` 另一条。
- `TokenRefreshServiceImpl` 写 `cookie_status = 2`（`:228-232`）却不动 `websocket_token`/`token_expire_time`/账号状态——描述同一次失败的三个字段只更新了一个。
- `AuthServiceImpl.login`（`:133-147`）：删 token → 插 token → 更新最后登录，无事务。
- `AdminCredentialRecovery`（`:96-104`）：改密 → 撤 token，无事务。

### 14.2 CAS 与盲写的反差

账号/Cookie 的每一次状态迁移都是 `select → mutate → updateById`（M8 缺陷 6）；本域唯一带守卫的 UPDATE 是 `advanceWebSocketSyncCursor`（`XianyuAccountMapper.java:25-30`）。**唯一具备真正 CAS 的机器是 M1**（`tasks.compute` + 控制块身份）。

### 14.3 重试/退避计数器复位错误

- `captchaRetryCounts`：手动启停不清（`:949-985`）。
- `reconnectAttemptCounts`：手动启动不清（`:140-143`）。
- 浏览器兜底冷却（`CookieRefreshServiceImpl.java:66-67`, `:410-415`, `:461`）：**在尝试之前**就开始计时，且成功完成一次完整凭证更新后**从不**清除。
- 登录失败计数：首次翻倍（`AuthServiceImpl:211`）。
- `_m_h5_tk` 重试计数（`TokenRefreshServiceImpl:208`, `:261`）：每次 hasLogin 成功后归零（`:304`），只靠一个 `boolean hasLoginAttempted` 阻止递归——用一个布尔守卫着两个方法之间的无界互递归（`refreshMh5tkTokenWithRetry` ↔ `refreshMh5tkViaHasLogin`）。

### 14.4 写而不读 / 读而不写

| 字段 | 写入点 | 读取点 | 结论 |
|---|---|---|---|
| `xianyu_cookie.expire_time` | `AccountServiceImpl:156,168,297,306,540,551` | **无** | 30 天过期纯装饰 |
| `xianyu_account.status = -1` | 无 | 实体注释、前端映射 | 幽灵状态 |
| `CaptchaSolveService.Status.UNSUPPORTED` | 无（无生产者） | 仅 `mapStatus` | 不可达 |
| `QRLoginSession.status = "error"` | 3 处 | 无 switch case、无前端 case | 不可见 |
| `WebSocketConfig.maxReconnectAttempts` | 配置 | **无** | 死配置 |
| `TokenRefreshService.refreshAllAccountsTokens` | 已实现 | 无调用者 | 死入口 |
| `cookie_status` 闸门 | 10 个写入点 | 4 个业务读者 + 1 条 mapper | 半途执行 |
| `TaskView.finishedAt` | `complete()` | 仅保留期判定 | 无 UI/接口区分 |

### 14.5 单一监视器下的全局状态

M1 缺陷 7：`synchronized (tasks)` 横跨 token 刷新 + WS 重连。叠加 `CallerRunsPolicy`，一次慢速凭证刷新可冻结**所有租户**的滑块处理。

### 14.6 陈旧读取

- `WebSocketTokenServiceImpl.getAccessToken` 每次调用读三次 DB（cookie 文本 `:151`、token 行 `:203`、Set-Cookie 后再读），可能返回一个已被 `updateCookiesFromResponse` 作废的 token。
- `CaptchaSolveServiceImpl.runTask` 在浏览器会话开始**之前**读 `currentCookie`（`:289`），几分钟后才应用结果（`:339`）且不重读——期间的手动 Cookie 更新被静默覆盖。

### 14.7 无状态可观测性

没有任何机器暴露迁移日志或指标。诊断读的是**派生视图**（`WebSocketController.java:377-406`），因此只能报当前组合、无法报如何到达——这正是 phase/status 与 -1/-2 两类不一致能长期存在的原因。

---

## 15. 魔法数字/字符串分支清单（分支集暗示了未被枚举捕获的状态机）

| 分支位置 | 取值集 | 暗示的机器 | 缺失的抽象 |
|---|---|---|---|
| `WebSocketTokenServiceImpl:401,472,554`；`CookieRefreshServiceImpl:199,251,268,497`；`TokenRefreshServiceImpl:228,265`；`AccountServiceImpl:427`；`WebSocketController:445-450` | `cookie_status ∈ {1,2,3}` | M7 | 无枚举；3 套实现 |
| `WebSocketTokenServiceImpl:826,841`；`CookieRefreshServiceImpl:544,556,583`；`TokenRefreshServiceImpl:415,495,534`；`XianyuAccountMapper:19`；`XianyuAccount.java:44`；`utils/index.ts:56` | `account.status ∈ {1,-2}`（+幽灵 `-1`） | M8 | 无枚举；`-1`/`-2` 未对齐 |
| `WebSocketController:148-156` | `cookie_status == 2` / `== 3` → 用户文案 | M7 | 文案映射重复 |
| `AuthServiceImpl:117`、`AuthInterceptor:77`、`PlatformUserService:128-129` | `sys_user.status ∈ {0,1}` | M10/M12 | 同一列两套谓词 |
| `QRLoginServiceImpl:419-441`, `:566-596` | `waiting/scanned/success/expired/cancelled/verification_required/error` | M9 | 3 套词汇 + `error` 孤儿 |
| `QRLoginServiceImpl:489-541` | `CONFIRMED/NEW/SCANED/EXPIRED/else`（`SCANED` 是平台拼写） | M9 | 平台词表未建模 |
| `WebSocketMessageRouter:168-176` | WS `code ∈ {200,401,500,else}` → Cookie 状态 | M7 | 传输码直接映射成凭证状态 |
| `PlaywrightCaptchaBrowserRunner`（21 字面量）+ `CaptchaSolveServiceImpl`（7 字面量）+ `CaptchaGuideDialog.vue:38-59`（18 标签） | phase 字符串 | M2 | 无枚举、无顺序、无共享词表 |
| `WebSocketServiceImpl:751-756`, `:768-775` | `attempt → base*2^n` 封顶 | M13 | 两套退避、无共享状态 |
| `PlaywrightCaptchaBrowserRunner:609` | `currentVersion - drag.frameVersion() > 5` | M3 | 陈旧窗口魔法数 |
| `AuthServiceImpl:211` | 尝试次数/10 分钟/5 | M11 | 计数语义未文档化且差一 |
| `V12__platform_user_roles.sql:6-11` | `MIN(id)` 且 `status=1` | M12 | 提升规则只存在于 SQL 里 |

---

## 16. 修复优先级

| P | 缺陷 | 位置 |
|---|---|---|
| 0 | WS **500** 写 `cookie_status=2`，把账号踢出所有 API 路径 | `WebSocketMessageRouter.java:172-174` |
| 0 | 写 `-2`、显示 `-1`，且 `-2` 被所有恢复循环排除 → 粘性离线 | `WebSocketTokenServiceImpl.java:826`；`vue-code/src/utils/index.ts:56` |
| 0 | `synchronized(tasks)` 横跨 token 刷新 + WS 重连；`CallerRunsPolicy` 内联执行 5 分钟任务 | `CaptchaSolveServiceImpl.java:71,136-138`；`AsyncConfig.java:58` |
| 1 | `pendingCaptchaAccounts`/`captchaTimestamps` 双写非原子，会丢失验证地址 | `WebSocketTokenServiceImpl.java:381-382,188-192` |
| 1 | `clearCaptchaWait` 不清 `account.status = -2` | `WebSocketTokenServiceImpl.java:766-772` |
| 1 | 状态迁移全为盲 `updateById`（丢失更新、覆盖无关列） | `AccountServiceImpl:427,524`；`CookieRefreshServiceImpl:557,585`；`WebSocketTokenServiceImpl:827,843` |
| 1 | QR `error` 状态不可见 → 前端永久「等待扫码」 | `QRLoginServiceImpl:657,566-596,617` |
| 1 | QR 会话永不回收（凭证常驻堆内） | `QRLoginServiceImpl:635-647`；`vue-code/src/api/qrlogin.ts:29` |
| 2 | `UNSUPPORTED` 不可达；phase 流水线非单调 | `CaptchaSolveServiceImpl:382,250`；runner `:443` |
| 2 | `captchaRetryCounts`/`reconnectAttemptCounts` 手动启停不复位 | `WebSocketServiceImpl:949-985,140-143` |
| 2 | `restartAfterCredentialUpdate` 清除人工停机标志 | `WebSocketServiceImpl:500-511` |
| 2 | 登录失败计数翻倍 + 仅按 IP + 统计所有异常 | `AuthServiceImpl:211,207-219`；`LoginController:135` |
| 2 | `sys_login_token` 删+插无事务、`user_id` 无唯一键 | `AuthServiceImpl:133-147`；`V1:28` |
| 3 | `cookie.expire_time` 写而不执行 | `AccountServiceImpl:156…551` |
| 3 | `status` 为 null 时拆箱 NPE 中断整轮调度 | `TokenRefreshServiceImpl:415,495,534` |
| 3 | 备份导入写入任意状态/NULL | `AccountBackupHandler:78,92,116` |
| 3 | `maxReconnectAttempts` 死配置；无「放弃」终态 | `WebSocketConfig.java:97` |
| 3 | `cancel()` 无视自身 `sideEffectLock` 守卫 → 取消后仍有副作用 | `CaptchaSolveServiceImpl.java:188-206` |

### 建议的收敛形态

1. **`enum AccountState { NORMAL, NEEDS_CAPTCHA, CREDENTIAL_INVALID }`** + **`enum CookieState { VALID, EXPIRED, RISK_BLOCKED }`**，所有写入收敛到一个 transition 服务，SQL 一律 `UPDATE ... WHERE status = <from>`（照抄 `advanceWebSocketSyncCursor` 的写法）。
2. **`enum CaptchaPhase`** + 有序流水线定义，由 service、runner、前端共用一份词表。
3. **`PendingCaptcha` 值对象**放进单张 Map，消除双写窗口。
4. **QR 会话与过期 login token 的定时回收器**，并把 `error` 纳入词汇与 UI。
5. 在 `-2` 上补 `last_error`/`changed_at` 两列，让「需要人工」变成一个可观测、可解释的状态。

在上表落地之前，**`-2`/`-1` 不一致**与 **WS-500 → `cookie_status=2`** 这两个缺陷最可能被报成「账号莫名其妙掉线且再也回不来」。
