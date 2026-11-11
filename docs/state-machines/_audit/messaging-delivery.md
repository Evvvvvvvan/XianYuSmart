# 消息 → 自动回复 → 履约 管线的隐式状态机审计

**Implicit State Machines in the Chat → Auto-Reply → Delivery Pipeline**

| | |
|---|---|
| 审计对象 / Target | `xianyuSmart` Spring Boot 后端，聊天消息 → 自动回复 → 自动发货 链路 |
| 审计日期 / Date | 2026-11-11 |
| 审计方式 / Method | 逐文件通读 + mapper SQL 逐条比对 + 迁移脚本核对 + 前端消费点核对；**本轮未修改任何既有文件**，仅新增本文档 |
| 机器数量 / Machines | **12 台管线内状态机**（SM-1 … SM-12）+ **1 台相邻状态机**（`merchant_task`，见 §3）+ **3 处死代码/死枚举**（见 §4） |
| 上游清单 / Upstream | [`../README.md`](../README.md) 是全仓清单（WS- / ORD- / MSG- 编号）；本文是**管线深挖**，每条机器给出完整迁移表与逐条行号，并在 §7 标注与上游清单的差异 |

**一句话结论**：这条链路里没有一台状态机是被“设计”出来的——它们都是从 SQL `WHERE` 条件里长出来的。真正定义状态机的地方不是枚举，而是 40 多条 update 语句的 guard；凡是没有 guard 的那几个写入点（`updateStateAndContent`、`updateStateContentAndFailReason`、`confirm_state`、`prepareDeliveryMessage`），就是全部重复发货 / 假成功 / 卡死状态的来源。

---

## 0. 判定标准与证据等级 / Method

**本文认定“隐式状态机”的标准**：某个状态集合存在**多于一个写入点**，且这些写入点分散在不同方法 / 监听器 / 定时任务 / WebSocket handler 中，状态迁移只由 SQL `WHERE` 条件或 Java `if` 隐式约束，没有集中定义。

**证据标记**：
- `文件:行号` —— 直接读到。
- “无写入点 / 无调用方” —— 全仓 `grep` 确认（复现命令见附录 B）。
- “反推” —— 值本身没有注释或枚举，由 SQL 字面量与调用点逆推。

**本文使用的行号均以当前工作区文件为准**（未格式化、未改动）。

---

## 1. 清单 / Inventory

| # | 机器 | 编码 | 存储 | 机器主键 | 上游编号 | 形式化程度 |
|---|---|---|---|---|---|---|
| SM-1 | 自动回复记录生命周期 | `state` ∈ {0, 2, 1, −1, −2} | DB | `(account, s_id, pnm_id)` | MSG-1 | 注释只覆盖 3/5 |
| SM-2 | 在飞延时回复任务 | 两个 `ConcurrentHashMap` + `ScheduledFuture` | **仅内存** | `accountId_sId` | MSG-2 | 无 |
| SM-3 | 会话人工接管 | `end_time` 时间窗 + 内存过期缓存 | DB + 内存 | `(account, s_id)` | MSG-3 | 时间窗，非枚举 |
| SM-4 | “人工模式”（第二套实现） | `Map<chatId, epochSecond>` | **仅内存** | `chatId` | MSG-4 | 死代码 |
| SM-5 | 买家自动化门禁 | `automation_blocked` 0/1 + `blocked_reason` | DB | `(account, buyerUserId)` | MSG-6 | 无 |
| SM-6 | 履约任务 | `delivery_status`（9 值字符串）+ `state` ∈ {0,1,−1} | DB | `(account, pnm_id)` / `(account, order_id)` | ORD-1（+ ORD-6 的 `state`） | 有枚举但被绕过 |
| SM-7 | 发货私聊 | `delivery_message_state` ∈ {0..5} | DB | 同一订单行 | ORD-2 | **完全无枚举** |
| SM-8 | 卡密预占 / 外部供货 | `kami_item.status` {0,1,2,3} + `usage_record.delivery_status` + `external_request.request_status` | DB | `(config, content_hash)`、`(account, order_id, delivery_index)` | ORD-4 + ORD-5 | 有枚举 + 强守卫 |
| SM-9 | 发货后四联标志 | `confirm_state` / `buyer_confirmed_receipt` / `receipt_follow_up_*` / `rate_status` | DB | 同一订单行 | ORD-7 + ORD-8 + ORD-9 | 无 |
| SM-10 | 平台风控护栏 | `GuardState` {NORMAL, RATE_WAIT, CIRCUIT_OPEN, RECOVERING} + 两个 `HashMap` | 内存 + 整文件 JSON | `accountId` / `accountId:OP` | MSG-5 | 有枚举，1/4 不可达 |
| SM-11 | 本地补写的外发消息 | 合成 `content_type` {888,887,997,999} + 合成 `pnm_id` | DB | `(account, pnm_id)` | — | 无 |
| SM-12 | 事件扇出“阶段序” | `@Order` / `@Async` 注解位置 | 无 | 事件实例 | — | 无 |
| 附属 | `merchant_task` 重试队列 | `status` {0,1,2,−1} + `attempt_count`/`max_attempts` | DB | `(tenant, task_type, request_key)` | OPS-1 | 无（见 §3） |
| ✗ | `OrderStatusEnum`（1..5） | 平台订单码 | 无 | — | ORD-11 | 全仓零引用 |
| ✗ | `EnhancedMessageSendService` + 3 个计数器 | `SUCCESS/FAILED/NOT_CONNECTED/RETRY_EXHAUSTED` | 内存 | `accountId` | MSG-7 | 全仓零引用 |
| ✗ | `ManualModeService` | manual/auto | 内存 | `chatId` | MSG-4 | 全仓零引用 |

---

## 2. 逐台机器 / Machine-by-machine

### SM-1 自动回复记录生命周期

**用途**：一次自动回复尝试一行；同时充当**持久化队列**、**租约表**与**审计流水**。

**状态集（精确值）**

| 值 | 含义 | 文档出处 |
|---|---|---|
| `0` | 待发送 / 排队（SQL 无 `COMMENT`，列定义 `state TINYINT NOT NULL DEFAULT 0`，`V1__baseline.sql:209`） | 实体注释“0-待回复” `XianyuGoodsAutoReplyRecord.java:83-85` |
| `2` | **已领取 / 执行中** —— 实体与 SQL 注释里**都没有**，只存在于 mapper SQL | `XianyuGoodsAutoReplyRecordMapper.java:52-60` |
| `1` | 成功 | 实体注释 |
| `-1` | 失败 | 实体注释 |
| `-2` | **已取消**（被同会话新消息取代，或被人工接管取消） —— **未文档化** | `XianyuGoodsAutoReplyRecordMapper.java:62-67` |

**存储**：仅 DB 一列五值，其中三个有文档。该列同时被当作租约使用（`lease_owner` / `lease_expire_time`，`XianyuGoodsAutoReplyRecordMapper.java:57`）——**一个列同时编码业务结果与 worker 归属**。

**迁移表**

| 从 | 触发 / 守卫 | 到 | 证据 |
|---|---|---|---|
| ∅ | 买家消息通过全部门禁；`INSERT state=0` | `0` | `AutoReplyServiceImpl.java:150-153`；`AutoReplyDelayServiceImpl.java:255-257` |
| ∅（命中唯一键） | `ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)` | 复用旧 id，**state 不变** | `XianyuGoodsAutoReplyRecordMapper.java:17-21` |
| `0` | `claim(id, workerId, 120)` | `2`（`lease_expire=NOW+120s`，`attempt_count+1`） | `XianyuGoodsAutoReplyRecordMapper.java:57-60`，调用点 `AutoReplyDelayServiceImpl.java:277` |
| `2`（租约过期） | 同一 `claim` 谓词 `state=2 AND lease_expire_time < NOW(3)` | `2`（再次领取，次数 +1） | `XianyuGoodsAutoReplyRecordMapper.java:52-60` |
| `2` | 发送循环全部成功 | `1` | `AutoReplyServiceImpl.java:240-246` → `XianyuGoodsAutoReplyRecordMapper.java:26` |
| `2` | 发送失败 / 策略无内容 / 异常 | `-1`（`exception_revision+1`） | `AutoReplyServiceImpl.java:165-169`、`247-250`；`AutoReplyDelayServiceImpl.java:238`、`311-313` |
| `2` | 事后看门狗：`executeAutoReply` 返回后仍是 `2` | `-1` | `AutoReplyDelayServiceImpl.java:307-310` |
| `0` | 同会话来了新消息（`cancelPendingBySession`） | `-2` | `AutoReplyDelayServiceImpl.java:154` → `XianyuGoodsAutoReplyRecordMapper.java:62-64` |
| `0`,`2` | worker 内发现人工接管 / 买家被拦截 | `-2` | `AutoReplyDelayServiceImpl.java:294-305` → `XianyuGoodsAutoReplyRecordMapper.java:66-67` |
| `1`,`-1`,`-2` | **无任何迁移** —— 终态 | — | 该表没有 requeue / reset 语句 |

**强制手段（Enforcement）**：只有 `claim` 一个真互斥（`WHERE id=? AND (state=0 OR (state=2 AND lease_expire_time < NOW(3)))`）。`cancelPendingBySession` 只碰 `state=0`，`cancelById` 只碰 `(0,2)`。而 `updateStateAndContent`（`XianyuGoodsAutoReplyRecordMapper.java:26-29`）**没有任何状态守卫**——被抢走租约的旧 worker 可以把 `-2` 覆盖成 `1`/`-1`，也可以覆盖已完成行。无 `CHECK`，无 FSM，无断言 `1`/`-1` 为终态。

**缺陷 / 风险**

1. **重放消息被静默丢弃。** `persistTask` 忽略影响行数（`AutoReplyDelayServiceImpl.java:257`）；重放的 `pnm_id` 走 `ON DUPLICATE` 拿回旧行 id，其 state 可能是 `-2` 或 `1`；`claim` 匹配不到，`dispatchTask` 直接 `return`（`269-281`），而此时 `pendingMessages` 已在 `:276` 被移除。买家消息既没回复、也没日志、也没记录。
2. **同一列里塞了两种 JSON Schema。** `trigger_context` 由 `persistTask` 写入时是 `List<ChatMessageData>`（`:254`），由直连路径写入时是 `AutoReplyTriggerContext`（`AutoReplyServiceImpl.java:202-206`）。`recoverDueTasks` 只按前者反序列化（`233-235`），失败即把该行判为 `-1`（`:238`）——**schema 不匹配被记成“回复失败”**。
3. **软跳过被记成硬失败。** `executeAutoReply` 的早返回（开关未开、商品不存在、无可用策略）**不写状态**（`AutoReplyServiceImpl.java:103-124`），看门狗随后把仍是 `2` 的行改成 `-1`（`:307-310`）。用户关掉自动回复 → 系统批量制造“自动回复失败”，每次 `exception_revision+1`，全部进入运维异常看板（`OperationsDiagnosticsService.java:185-205`）。
4. **`state=1` 不代表“已送达”。** 成功路径用的是 `webSocketService.sendMessage`（`AutoReplyServiceImpl.java:231`），该方法在把帧写进 socket 后立即返回 `true`——`XianyuWebSocketClient.sendMessage` 返回 `void`，只在异常时打日志（`XianyuWebSocketClient.java:583-655`），**不等 ack**。
5. **租约 120s × 无上限重试**。`claim(...,120)` 对上 AI/RAG 延迟 + 人工延迟 sleep + 逐条图片上传（`AutoReplyServiceImpl.java:218-236`）；`recoverDueTasks` 每 1s 扫一遍（`:229`），会在第一个 worker 还在发的时候重新领取 → **买家收到两条回复**。`claim` 与 `findDue` 都不看 `attempt_count`，崩溃循环可以无限重放。
6. **行数随消息数线性增长。** 每条新消息都新建一行并把上一行置 `-2`（`:154-170`），话多的买家每句话留一行死记录。
7. **`reply_type` 超出词表。** 代码取值 `1` 关键词、`2` AI/RAG、`3` 关键词+AI润色（`KeywordWithAIPolishStrategy.java:25-26`、`KeywordReplyStrategy.java:17`）；实体注释与列注释只知道 1、2，且 INSERT 语句把 `NULL` 强转为 `1`（`XianyuGoodsAutoReplyRecordMapper.java:18`）。

---

### SM-2 在飞延时回复任务（纯内存）

**用途**：把买家连续消息做 N 秒防抖，按会话收集一批消息。

**状态集**：`不存在` → `已排程`（future 在 `pendingTasks`）→ `已派发`（两 Map 移除、尝试 `claim`）。并行的 `pendingMessages` 负责累积消息体。

**存储**：仅内存 `ConcurrentHashMap`，键 `accountId_sId`（`AutoReplyDelayServiceImpl.java:94`、`103`）。**失步风险**：DB 里 `state=0` 的行是唯一持久痕迹，**消息列表本身只存在于内存**（`:157-163`）。

**迁移表**

| 从 | 触发 / 守卫 | 到 | 证据 |
|---|---|---|---|
| 不存在 | 买家消息、未被接管 | 已排程（+ 新 DB 行 `state=0`） | `:141-170` |
| 已排程 | 同会话新消息 → `cancel(false)` + `cancelPendingBySession` | 已排程（新 future、新行） | `:154`、`:174-182` |
| 已排程 | 定时器到期 → `dispatchTask` 移除两 Map、`claim` | 已派发 | `:269-281` |
| 已排程 | 卖家手动回复，**且 `humanInterventionOn=1`** | 不存在（行置 `-2`） | `:207-220` |
| 任意 | JVM 停机 | future 取消、Map 清空，**DB 行留在 `0`** | `:105-117` |

**强制手段**：除 `claim` 外没有。两个驱动者抢同一行：内存 future 与每秒 `recoverDueTasks` 扫描（`:229-241`），只靠 SQL `claim` 调和。

**缺陷 / 风险**

- **两个并发 `submitDelayTask` 之间的丢回复竞态。** 线程 A 持久化 rowA；线程 B 的 `cancelDelayTask` 把 rowA 置 `-2`；A 的 future 之后仍会触发，`claim` 失败 → **A 那批消息静默丢失**（无日志、无重试）。
- **崩溃丢失已收集消息**，但 DB 行存活；恢复时用 `trigger_context` 里最后一次 `persistTask` 的内容——即一个更旧的子集。
- `getPendingTaskCount()` 读的是 DB `countPending()`（`:185-187`），不是内存 Map，两个数字可以任意背离。
- 停机用 `cancel(false)`，不中断已在 `taskExecutor` 里执行的回复。

---

### SM-3 会话人工接管（真正接入链路的那一套）

**用途**：卖家手动回复后，在 N 分钟内压制该会话的 AI 回复。

**状态集**：`未接管` / `接管至 end_time`。以**绝对时间戳**表达，不是一个状态位。

**存储**：双份——DB `xianyu_human_intervention_record(end_time)`，唯一键 `(xianyu_account_id, s_id)`（`V1__baseline.sql:391-402`）；**加上**内存缓存 `takeoverMap<accountId_sId, epochMillis>`（`HumanTakeoverManager.java:57`）。类注释明确缓存只是热点缓存（`HumanTakeoverManager.java:16-19`）。

**迁移表**

| 从 | 触发 / 守卫 | 到 | 证据 |
|---|---|---|---|
| 未接管 | 卖家手动回复，**守卫 `humanInterventionOn==1`** | 接管至 now+N 分 | `AutoReplyDelayServiceImpl.java:207-216` → `HumanTakeoverManager.java:76-92` |
| 未接管 | 卖家从 Web 后台发消息（`/sendMessage`、`/sendImageMessage`） | 同上 | `WebSocketController.java:254`、`321` |
| 未接管 | 平台推送卖家自己的消息（`contentType=1` 且 sender==own） | 同上 | `ChatMessageEventHumanInterventionListener.java:64-87` |
| 接管中 | `now > end_time`（读时惰性判定） | 未接管（清缓存，**保留 DB 行**） | `HumanTakeoverManager.java:126-130` |
| 接管中 | `cleanup()` 每 60s：清缓存 + `DELETE ... end_time < NOW(3)` | 行被物理删除 | `HumanTakeoverManager.java:142-157`、`XianyuHumanInterventionRecordMapper.java:26-28` |
| 接管中 | 卖家再次回复 | 续期（`ON DUPLICATE ... end_time=VALUES(end_time)`） | `XianyuHumanInterventionRecordMapper.java:9-14` |

**强制手段**：三道读取检查（提交时 `AutoReplyDelayServiceImpl.java:142-148`、worker 到期复查 `:294-297`、监听器 `ChatMessageEventAutoReplyListener.java:111-115`）。三道都调用同一个 `isTakenOver`，因此**共享同一份陈旧性**。

**缺陷 / 风险**

1. **缓存优先会掩盖更新的 DB 状态。** 缓存命中时直接返回、不回查 DB（`HumanTakeoverManager.java:110-132`）；另一实例的续期、或人工 DELETE，在过期前都不可见。
2. **写入顺序是 缓存→DB，且无事务**（`:80-88`）。中途崩溃 → 内存已接管、DB 无记录 → 重启后自动化立刻恢复，而卖家以为仍处于静默期。
3. **接管只在 `humanInterventionOn=1` 时存在。** 开关关闭时 `recordSellerManualReply` 在 `:210-213` 直接返回，什么都不记：卖家在后台打字回复，而 15s 的 AI 定时器照常触发——这就是“人工接管对在飞自动化不可见”的具体事故路径。
4. **接管不影响发货。** `DeliveryTaskScheduler.executeTask` 只查买家门禁（`:97-103`），完全不看接管状态；已在队列里的自动发货照发。
5. **粒度不一致。** 记录里存了 `xy_goods_id`，但唯一键与所有查询都是 `(account, s_id)`（`XianyuHumanInterventionRecordMapper.java:16-19`）；商品列是只写不读。
6. **审计被删除而非归档**（`:153`）——“谁在什么时候让自动化闭嘴”不可追溯。
7. `countActive()` / `getActiveCount` 统计**全部租户**（`XianyuHumanInterventionRecordMapper.java:21-24`），跨租户指标泄漏。

---

### SM-4 “人工模式”：第二套并行实现（死代码）

**用途（声明）**：按 `chatId` 在 manual/auto 之间切换，1 小时超时。

**状态集**：`manual`（Map 中存在）/ `auto`（不存在）。时间戳单位为**秒**（`ManualModeServiceImpl.java:64`）。

**存储**：仅内存 `Map<String chatId, Long epochSecond>`（`:25`）；超时来自 `websocket.manual-mode-timeout: 3600`（`application.yaml:95`、`WebSocketConfig.java:74`）。

**迁移表**：不存在 → manual（`enterManualMode` / `toggleManualMode`，`:52-67`）；manual → 不存在（`exitManualMode` `:70-73`；读时超时 `:42-46`；`@Scheduled(fixedRate=300000)` 清理 `:104-107`）。

**强制手段**：不适用——**没有任何调用方**。全仓搜 `ManualModeService|isManualMode|toggleManualMode|enterManualMode|exitManualMode`，命中全部落在接口与自身实现内。

**缺陷 / 风险**

- **“人工模式”存在两个互相竞争的定义。** `ManualModeService` 只按 `chatId` 索引（无账号、无 DB、重启即丢、1h 超时）；`HumanTakeoverManager` 按 `(account, s_id)`、有 DB、且是唯一接入链路的。若有人把它接上，两者会静默打架，且 1h 会覆盖商品级 10min 的干预窗口。
- `isManualMode` 是**会改状态的 getter**（`:44`），被 `toggleManualMode` 调用后读到的是自己刚写出来的 `false`。
- `getAllManualModeConversations` 返回快照（`:77`），调用方无法关联到账号。

---

### SM-5 买家自动化门禁

**用途**：按买家维度的自动化退出开关，同时压制回复与发货。

**状态集**：`automation_blocked` ∈ {0,1}；`blocked_reason` 是人读原因（`BuyerProfileService.java:124-134`）。

**存储**：DB `xianyu_buyer_profile`，无过期列、无历史。

**迁移表**

| 从 | 触发 / 守卫 | 到 | 证据 |
|---|---|---|---|
| 0 | 后台保存 profile 且 `automationBlocked=true` | 1（+原因，≤200 字） | `BuyerProfileService.java:88-100` |
| 1 | 后台再次保存为 `false` | 0（原因保留） | 同上 |
| 任意 | 时间流逝 / 订单完成 | **无自动迁移** | 全仓没有第二处 `automation_blocked` 写入 |

**读取点（三处，全是手写）**：回复监听器 `ChatMessageEventAutoReplyListener.java:118-124`；延时 worker 复查 `AutoReplyDelayServiceImpl.java:299-305`；发货 worker `DeliveryTaskScheduler.java:97-103`。**订单发现不被门禁拦截**——被拦截买家的订单照样落库，只是标记 `REVIEW_REQUIRED`（`ChatMessageEventAutoDeliveryListener.java:119-126`）。

**缺陷**：迁移本身无守卫（任何 `save` 覆盖，整 profile 读改写、后写者胜）；门禁在四个硬编码位置被查询，任何新增自动化路径都会静默绕过；无来源、无过期，临时拦截变成永久拦截直到人工解除。

---

### SM-6 履约任务（`delivery_status` + `state`）

**用途**：已付款订单的持久化发货队列，带租约、重试与转人工逃生口。

**状态集（精确值）**：`DeliveryStatus`：`PENDING, PROCESSING, RETRY_WAIT, DELIVERED, CONFIRMING, COMPLETED, FAILED, REVIEW_REQUIRED, SKIPPED`（`DeliveryStatus.java:3-13`）。列为 `delivery_status VARCHAR(24) NOT NULL DEFAULT 'PENDING'`（`V1__baseline.sql:175`），**无 `COMMENT`、无 `CHECK`**。同一行还有**第二个正交列** `state TINYINT NOT NULL DEFAULT 0`（`:164`），取值 `0 / 1 / -1`，且**与 `OrderStatusEnum` 完全不是一套词表**。

**存储**：DB 两列。唯一键 `(xianyu_account_id, pnm_id)` 与 `(xianyu_account_id, order_id)`（`:186-187`）。**失步风险：高**——两列由不同语句写，且有若干语句改写一列而强制另一列。

**迁移表（delivery_status）**

| 从 | 触发 / 守卫 | 到 | 证据 |
|---|---|---|---|
| ∅ | WS 付款消息发现新订单 | `PENDING`（买家被拦则 `REVIEW_REQUIRED`） | `DeliveryTaskServiceImpl.java:36-40`；`ChatMessageEventAutoDeliveryListener.java:262-267` |
| ∅ | API 轮询发现新订单、自动发货关闭 | `SKIPPED` | `PendingOrderPollService.java:78-80` |
| `PENDING`,`RETRY_WAIT` | 到期（`next_retry_time<=now`）**且 `delivery_message_state NOT IN (3,4,5)`**，`SELECT ... FOR UPDATE` | `PROCESSING`（租约 120s，`attempt_count+1`） | `XianyuGoodsOrderMapper.java:162-173` |
| `PROCESSING` 租约过期 | 同一轮扫描 | `PROCESSING`（再次领取） | 同上 |
| `PROCESSING` | 成功（执行后 `state==1`） | `COMPLETED` + `delivered_quantity=expected` | `DeliveryTaskScheduler.java:113-115` → `XianyuGoodsOrderMapper.java:175-177` |
| `PROCESSING` | 失败且 `attempt_count < max_attempts(3)` | `RETRY_WAIT`（`next=+5..300s`） | `DeliveryTaskServiceImpl.java:81-92` → `XianyuGoodsOrderMapper.java:179-185` |
| `PROCESSING` | 失败且次数耗尽 | `FAILED` | 同上 |
| `PROCESSING` | 平台风控（`CONSIGN_DEFERRED`） | `RETRY_WAIT`、`attempt_count-1`、`last_error_code=RISK_GUARD_WAIT` | `XianyuGoodsOrderMapper.java:187-192`，调用点 `AutoDeliveryServiceImpl.java:503-517` |
| `PROCESSING` | 凭证结果不确定 / 已存在凭证 / 卡密暂存超时 | `REVIEW_REQUIRED` | `XianyuGoodsOrderMapper.java:198-201`、`272-280` |
| `FAILED`,`RETRY_WAIT` | API 轮询发现订单仍未发货（`requeue`） | `PENDING` | `PendingOrderPollService.java:96-101` → `XianyuGoodsOrderMapper.java:203-205` |
| `FAILED`,`RETRY_WAIT`,`REVIEW_REQUIRED` | 运维“重试”接口 | `PENDING`、`state=0`、`attempt_count=0` | `OrderController.java:191` → `XianyuGoodsOrderMapper.java:207-212` |
| `FAILED`,`RETRY_WAIT`,`REVIEW_REQUIRED` | 运维“标记已发货” | `COMPLETED`、`state=1`、清空待发私聊 | `OrderController.java:211` → `XianyuGoodsOrderMapper.java:214-220` |
| **任意** | `updateState` / `updateStateAndContent` / `updateStateContentAndFailReason` 写 `state` | `state=1 → COMPLETED`，`state=-1 → FAILED`，否则不变 | `XianyuGoodsOrderMapper.java:138-151` |
| **任意** | `prepareDeliveryMessage`（把私聊入队） | **`COMPLETED` + `state=1`，而私聊还没发** | `XianyuGoodsOrderMapper.java:236-241` |
| **任意** | `activateDeliveryMessage`（释放暂存私聊） | `COMPLETED` + `state=1` | `XianyuGoodsOrderMapper.java:250-255` |
| — | `DELIVERED`、`CONFIRMING` | **无任何写入点** | 仅枚举与前端筛选 |

**强制手段**：真实但零散。`retryOrFailTask` 拒绝触碰 `REVIEW_REQUIRED`/`COMPLETED`（`:182`）；`requeueTask` / `requeueFailedTask` / `markDeliveredTask` 都要求 `state <> 1`（`:204`、`210`、`219`）；`claimDueTasks` 校验领取行数等于锁定行数，不等则抛异常（`DeliveryTaskServiceImpl.java:65-67`）。无 `CHECK`、无 FSM，且由 `state` 驱动的 `CASE` 写入可以把以上全部推翻。

**缺陷 / 风险**

1. **同一列有两个写者，其中一个“瞎写”。** 调度器在重读之后才决定终态（`DeliveryTaskScheduler.java:113-126`），但发货体已经用 `state` 驱动的 `CASE`（`XianyuGoodsOrderMapper.java:143-146`）和 `prepareDeliveryMessage` 把 `delivery_status` 改成 `COMPLETED` 了。于是 `state==1 → complete()` 成了同义反复；而“凭证失败但 `state=1`”会被判成 `COMPLETED`。
2. **`prepareDeliveryMessage` 在买家收到消息之前就宣布履约完成。** `delivery_status='COMPLETED'`、`state=1`、`delivered_quantity=expected` 全部在 `:236-241` 写死，而私聊此时才进入 `delivery_message_state=0`。营收与首页统计（`XianyuGoodsOrderMapper.java:26-31`）立刻把它计入。
3. **两台机器通过一个过滤条件耦合。** `lockDueTasks` 排除 `delivery_message_state ∈ (3,4,5)` 的行（`:165`）。一条卡住的**私聊**因此会冻结**凭证**状态机：不重试、不失败、不转人工，在 `delivery_status` 看板上完全不可见。
4. **`DELIVERED` / `CONFIRMING` 不可达**，而前端把它们当正常路径提供筛选（`order-status.ts:1-11`）——运维可以筛到一个永远不会出现的状态。
5. **`SKIPPED` 是终态死胡同。** 在 `PendingOrderPollService.java:78` 写入且 `state=0`；没有任何 requeue 条件接受 `SKIPPED`（`:204`、`:211`），首页待办统计也不含它（`:32`）。事后打开自动发货不会复活该订单。
6. **租约（120s）< 工作时长。** `lockDueTasks`+`claimTasks` 的 `lease-seconds: 120`（`application.yaml:22`）对上人工延迟、图片上传与每次调用最多 3 次重试。同一订单被重新领取后重跑 `consignDummyDelivery`，平台返回 `ORDER_ALREADY_DELIVERY` → `REVIEW_REQUIRED`（`OrderServiceImpl.java:210-213`、`AutoDeliveryServiceImpl.java:533-547`）。**自己制造的重复被执行员当成需要人工核对的事故。**
7. **重试计数混了成因。** `claimTasks` 每次 `attempt_count+1`（`:170`）；`deferForRisk` 又减 1（`:188`）；`retryOrFail` 拿 `max_attempts` 比（`DeliveryTaskServiceImpl.java:86-89`）。这个计数器既是“领取次数”又是“失败次数”，所以 3 次上限既不成立也不可控。
8. **同一次购买可能产生两行。** WS 发现用消息 `pnm_id` 与**可能为空的 `order_id`**（`ChatMessageEventAutoDeliveryListener.java:256-260`）；API 发现合成 `pnm_id = "api_"+orderId`（`PendingOrderPollService.java:158`）。MySQL 允许多个 NULL，因此当 WS 消息没带 `orderId` 且重放时 `pnmId` 变了，两个唯一键同时不命中 → 为同一订单新建第二个发货任务。
9. **重复发现被当成新订单播报。** `discover` 在 `ON DUPLICATE` 后返回的是**已存在**的行（`DeliveryTaskServiceImpl.java:43-53`），监听器却照样发 `ORDER_CREATED` 通知（`:127-135`）——重放会刷通知。
10. **`OrderStatusEnum`（1..5）是陷阱。** 它被定义（`OrderStatusEnum.java:14-34`）却**全仓零引用**；而 `orderStatus` 过滤以裸 `Integer` 暴露（`OrderQueryReqDTO.java:23`）并被直接编译成 `WHERE r.state = #{orderStatus}`（`XianyuGoodsOrderMapper.java:367-369`）。传枚举里的 `1`（待付款）会筛出 `state=1`（发货成功）的订单。

---

### SM-7 发货私聊子机（`delivery_message_state`）

**用途**：把“买家用私聊被告知买了什么”与“平台被告知已发货”分开追踪。

**状态集（从 SQL 反推；无枚举、无常量、无列注释）**

| 值 | 含义 | 证据 |
|---|---|---|
| `0` | 就绪 / 待发送（默认值，`V5:9`） | `XianyuGoodsOrderMapper.java:236`、`285-294` |
| `1` | 已发送 | `XianyuGoodsOrderMapper.java:282-283` |
| `2` | 已领取 / 发送中（2 分钟租约） | `XianyuGoodsOrderMapper.java:285-289` |
| `3` | **卡密**私聊暂存：凭证尚未提交 | `BuyerMessageService.java:155-157` |
| `4` | **固定内容**私聊暂存：等待确认发货 | `BuyerMessageService.java:159-161` |
| `5` | 固定暂存已确认 / 已激活 | `XianyuGoodsOrderMapper.java:262-265` |

**存储**：DB，与 SM-6 同一行。**失步风险：全仓最高**——该列与 `state`/`delivery_status` 在同一业务步骤里被**不同语句**分别改写，且是有意为之（`OrderServiceImpl.java:799-819`）。

**迁移表**

| 从 | 触发 / 守卫 | 到 | 证据 |
|---|---|---|---|
| ∅ | `prepareDeliveryMessage`：渲染内容、入队，**同时强制 `state=1`/`COMPLETED`** | `0` + `next_retry=NOW` | `XianyuGoodsOrderMapper.java:236-241`，经 `BuyerMessageService.java:139-150` |
| ∅ | `holdDeliveryMessage(3)`（卡密）/ `holdDeliveryMessage(4)`（固定） | `3` 或 `4`，`next_retry=NOW+30min` | `XianyuGoodsOrderMapper.java:243-248` |
| `0`,`2` 且到期 | `claimDeliveryMessage`（2 分钟租约） | `2` | `XianyuGoodsOrderMapper.java:285-289` |
| `2` | 发送返回成功 | `1` + 发 `DeliveryMessageSentEvent` | `BuyerMessageService.java:253-259`、`XianyuGoodsOrderMapper.java:282` |
| `2` | 发送返回失败 | `0`、`attempt_count+1`、退避 `15s<<min(attempts,4)` 上限 300s | `BuyerMessageService.java:261-262`、`285-289` |
| `3`,`5` | 凭证已提交 → `activateDeliveryMessage` | `0` + 强制 `state=1`/`COMPLETED` | `XianyuGoodsOrderMapper.java:250-255`，经 `BuyerMessageService.java:176-182` |
| `4` | 固定内容凭证提交成功 → `confirmFixedHeldDeliveryMessage` | `5` **且 `confirm_state=1`** | `XianyuGoodsOrderMapper.java:262-265` |
| `3`,`4`,`5` | 业务失败路径（`cancelHeldDeliveryMessage`） | `0` + 内容置 `NULL` | `XianyuGoodsOrderMapper.java:257-260` |
| `4` 且 `next_retry<=now` | 15s 清理：`cancelStaleFixedHeldDeliveryMessages(100)` | `0` + 内容置 `NULL` | `XianyuGoodsOrderMapper.java:267-270` |
| `3` 且 `next_retry<=now` 且无 `DELIVERED` 使用记录 | 升级人工 | `delivery_status='REVIEW_REQUIRED'` | `XianyuGoodsOrderMapper.java:272-280` |
| `3` 且存在 `DELIVERED` 使用记录 | `selectCommittedHeldDeliveryMessages` → `activateDeliveryMessage` | `0` → 进入常规发送 | `XianyuGoodsOrderMapper.java:301-307` |

**强制手段**：每条写入都是带 `WHERE delivery_message_state IN (...)` 的条件 UPDATE，调用侧再判 `!= 1 → throw`（`BuyerMessageService.java:143-145`、`167-169`、`250-252`）。**这是整条管线守卫最好的机器**。但仍然没有枚举、没有 `CHECK`，3/4/5 是散落的魔法数字（`OrderServiceImpl.java:803`、`AutoDeliveryServiceImpl.java:567`、`BuyerMessageService.java:156`、`161`）。

**缺陷 / 风险**

1. **没有“已发送但结果未定”态。** `claim` 写 `2` 并把 `next_retry` 设为 `NOW+2min`；若 JVM 在“写 WebSocket”与 `markDeliveryMessageSent` 之间崩溃，该行留在 `2`，2 分钟后可再次领取 → **买家第二次收到发货私聊**（含卡密文本）。唯一缓解是每次发送的 `pnm_id` 都不同（`SentMessageSaveServiceImpl.java:133-136`），也就是说**这条重复在消息时间线里根本看不出来**。
2. **超时被当成成功。** `XianyuWebSocketClient.java:866-868`：`catch (TimeoutException) { log.warn(...视为发送成功); return true; }`。而 `BuyerMessageService` 用的正是 `sendMessageWithResult`（`:276`），于是 10s 超时会把 `delivery_message_state` 置 `1`（已发送）并发布 `DeliveryMessageSentEvent`，进而可能触发自动确认发货（`AutoDeliveryServiceImpl.java:689-710`）——**一条根本没送达的消息会把平台订单标成已发货**。
3. **没有重试上限。** `deferDeliveryMessage`（`:285-289`）只增计数，从不与上限比较；WebSocket 长期不通时该订单每 300s 重试一次，永不停止，UI 徽章永远是“私聊待重试”（`OrderTable.vue:129-131`）。
4. **退避读的是过期实体。** 退避用 `order.getDeliveryMessageAttemptCount()`（`:286`），是发送前加载的内存对象；DB 列已被其它路径的 `claim`/`defer` 改过，因此任何并发重试后退避都算错。
5. **11 个状态值在前端被压成 2 个。** `getChatMeta` 把 `1` 显示为“私聊已发送”，**其余全部**显示为“私聊待重试”（`OrderTable.vue:129-131`）——`3`（卡密暂存）、`4`（待确认）、`5`（已确认）全部被显示成重试失败。
6. **`3` 只能借外表的字符串释放。** `selectCommittedHeldDeliveryMessages` 要求 `xianyu_kami_usage_record.delivery_status='DELIVERED'`（`:301-307`）——一条跨表的字符串隐式外键进入 SM-8。若卡密已提交而使用记录插入失败（`KamiConfigServiceImpl.java:405-423`），私聊永久挂起，30 分钟后被升级为 `REVIEW_REQUIRED`。
7. **每轮恢复只处理 100 行。** 三条 `LIMIT 100` 扫描每 15s 一次（`XianyuGoodsOrderMapper.java:269`、`:279`、`BuyerMessageService.java:193-207`）；积压时升级计时（`next_retry+30min`）会先于扫描到达而误判。

---

### SM-8 卡密预占与外部供货

**用途**：渲染时预占卡密，只有平台凭证成功后才提交，绝不让同一张卡进入两个订单。

**状态集**：`KamiStatus`：`AVAILABLE(0)`、`DELIVERED(1)`、`RESERVED(2)`、`REVIEW_REQUIRED(3)`（`KamiStatus.java:6-10`）——**全库唯一带中文 `COMMENT` 的状态列**：`'0可用 1已交付 2已预占 3待核对'`（`V1__baseline.sql:289`）。使用记录 `delivery_status VARCHAR(24) DEFAULT 'RESERVED'`（`:310`），但代码只写 `'DELIVERED'`。外部请求：`PROCESSING → SUCCESS | FAILED | REVIEW_REQUIRED`。

**存储**：DB 三表。真正的强制手段是唯一键：`uk_kami_config_content (kami_config_id, content_hash)`（`:296`）、`uk_usage_order_index (account, order_id, delivery_index)`、`uk_usage_item_order (kami_item_id, order_id)`（`:316-317`）。

**迁移表**

| 从 | 触发 / 守卫 | 到 | 证据 |
|---|---|---|---|
| `0` | `lockAvailable ... FOR UPDATE` + `reserve(ids, orderId)` | `2` | `XianyuKamiItemMapper.java:60-79`、`KamiConfigServiceImpl.java:378-392` |
| `2` | 凭证成功后 `commitReservation` | `1`（+ 使用记录 `DELIVERED`） | `XianyuKamiItemMapper.java:81-82`、`KamiConfigServiceImpl.java:397-432` |
| `2` | 凭证未发 / 可重试失败 | `0`（**仅 `source_type='LOCAL'`**） | `XianyuKamiItemMapper.java:84-87` |
| `2` | 凭证结果不确定 / 已存在凭证 | `3` | `XianyuKamiItemMapper.java:89-90` |
| `1`,`3` | 运维重置卡片 | `0` | `XianyuKamiItemMapper.java:57-58`，经 `KamiConfigServiceImpl.java:311-324` |
| `3` | 系统 | **无迁移**，终态直到人工重置 | — |
| ext ∅ | `insertIfAbsent` | `PROCESSING` | `XianyuKamiExternalRequestMapper.java:14-22` |
| ext `PROCESSING` | 响应解析并落库 | `SUCCESS` | `XianyuKamiExternalRequestMapper.java:57-65`、`ExternalKamiProvisionService.java:133-150` |
| ext `PROCESSING` | 网关错误 / 解析错误 | `FAILED` 或 `REVIEW_REQUIRED` | `ExternalKamiProvisionService.java:76-87` |
| ext `FAILED` 或陈旧 `PROCESSING`（>2min） | `claimRetry` 守卫 `attempt_count<3` | `PROCESSING` | `XianyuKamiExternalRequestMapper.java:41-55` |
| ext `REVIEW_REQUIRED` | **无迁移**，需人工 | — | `ExternalKamiProvisionService.java:116-119` |

**强制手段**：强且显式——配置与卡密集都加 `FOR UPDATE`、`allMatch(RESERVED)` 复用规则、`commitReservation` 行数校验、唯一键、每条迁移都有 `NOT IN` 守卫。**这是本仓库最接近真实 FSM 的一台。**

**缺陷 / 风险**

- **API 来源的 `2 → 0` 被静默跳过**（`JOIN ... source_type='LOCAL'`，`:86`）。任何对 API 卡密调用 `releaseReservation` 的失败路径都会把它永久留在 `2`——`countUnused`（status 0）看不到，`countUnsettledByConfigId`（2,3）才统计（`:48-49`），**库存看起来永久蒸发**。
- **`3` 没有自动出口**，`releaseReservation` 也不接受 `3`（`:87`）；只能人工重置，且没有针对 `3` 的过期扫描。
- 死操作：`markUsed`（`:54-55`）、`findNextUnused`（`:15-16`）、`findRandomUnused`（`:18-19`）**零调用**；`kami_usage_record.delivery_status` 的默认值 `'RESERVED'` 从未写入。
- **跨机器字符串依赖**：SM-7 的 `3` 释放依赖本表 `'DELIVERED'`（`XianyuGoodsOrderMapper.java:301-307`）；`delivery_index` 由列表顺序决定（`KamiConfigServiceImpl.java:409-418`），没有任何东西把 `delivery_index` 绑定到真正渲染进消息的那张卡。

---

### SM-9 发货后四联标志

**用途**：四个彼此独立的事实，UI 以四个徽章展示在同一订单上。

**状态集**
- `confirm_state`：`0` 未确认 → `1` 已确认（`TINYINT DEFAULT 0`，`V1:166`）。
- `buyer_confirmed_receipt`：`0/1`（`V5:12`）。
- `receipt_follow_up_sent_count`（0..5）、`receipt_follow_up_completed`（0/1）、`receipt_follow_up_next_time`（`V5:13-15`）。
- `rate_status`：`0` 未评价、`1` 成功、`-1` 失败；配合 `rate_time`/`rate_content`/`rate_source`（`AUTO`/`MANUAL`/`PLATFORM`）（`XianyuGoodsOrder.java:43-49`、`V3:55-57`）。

**迁移表**

| 机器 | 从 → 到 | 触发 / 守卫 | 证据 |
|---|---|---|---|
| confirm | `0 → 1` | 运维接口 / 私聊后自动确认 / 固定暂存确认，三条路径 | `OrderController.java:139`；`AutoDeliveryServiceImpl.java:726`；`XianyuGoodsOrderMapper.java:262-265` |
| buyer receipt | `0 → 1` | 追评扫描时观察到平台 `交易成功` | `GoodsAutomationService.java:161-165` → `XianyuGoodsOrderMapper.java:318-319` |
| follow-up | `sent_count → +1` | 对 `(completed=0, sent_count=expected)` 做 CAS 后发送 | `XianyuGoodsOrderMapper.java:321-332`、`GoodsAutomationService.java:147-192` |
| follow-up | `completed=1` | 消息发完，或买家已评价 | `GoodsAutomationService.java:156-159`、`174-178` |
| rate | `0 → 1 / -1` | 平台调用之后按结果写 | `GoodsAutomationService.java:360` → `XianyuGoodsOrderMapper.java:229-234` |
| rate | `0 → 1` | **什么都没发**：平台已显示卖家评价 | `GoodsAutomationService.java:326-330` |

**强制手段**：弱且不均。`confirm_state` 的写入**完全没有守卫**——`UPDATE ... WHERE xianyu_account_id=? AND order_id=?`（`XianyuGoodsOrderMapper.java:222-223`），所以 `FAILED` / `REVIEW_REQUIRED` 的订单也能被确认，前端只是把按钮藏了（`order-status.ts:56-58`）。`rate_status` 没有重置路径，`updateRateStatus`（`:225-227`）是死代码。只有追评进度做了正确的 CAS。

**缺陷 / 风险**

- **`confirm_state` 被两次写入、两个含义。** `confirmFixedHeldDeliveryMessage` 把它当作“释放暂存消息”的一部分（`OrderServiceImpl.java:799-807` 对比 `else` 分支 `:805`）——同一列既是“凭证已接受”又是“已确认发货”。
- **自动确认依赖一个“超时即成功”的事件**（见 SM-7 缺陷 2）：平台订单可能仅仅因为**私聊**超时就被标为已发货。
- **`buyer_confirmed_receipt` 与 `receipt_follow_up_next_time` 语义重复**，且只由追评扫描写入；若未配置话术，该标志对已完成交易永远是 `0`（`XianyuGoodsOrderMapper.java:309-316` 要求配置存在且话术非空）。
- **`rate_status` 允许从一次“读”写入**（`GoodsAutomationService.java:329`）——写完立刻抛异常，于是“评价失败”的请求实际记为成功。
- **四个布尔编码一个生命周期**，UI 渲染四个独立徽章（`OrderTable.vue:171-191`），没有不变式，所以 `state=1, delivery_status=FAILED, confirm_state=1` 这种自相矛盾的组合可被表示、也会被展示。

---

### SM-10 平台风控护栏

**用途**：对平台写操作限流与熔断，覆盖 `consign`/`rate`/`publish`/`polish`/`delete`。

**状态集**：`GuardState { NORMAL, RATE_WAIT, CIRCUIT_OPEN, RECOVERING }`（`RiskControlService.java:91-96`）。**`RECOVERING` 从未被产生也从未被判断**（唯一的比较是 `CIRCUIT_OPEN`，`MerchantOperationsService.java:790`；实现里只构造 `NORMAL`/`RATE_WAIT`/`CIRCUIT_OPEN`）。

**存储**：内存（`rateLimits` 按 `accountId:OPERATION`，`circuits` 按 `accountId`，`RiskControlServiceImpl.java:70-71`），以**整文件 JSON 重写**持久化（`:357-376`），路径 `app.risk-guard.state-file`（`application.yaml:25`）。**失步风险**：单 JVM；多实例互覆盖文件（后写者胜）；加载时会丢弃过期项，而保存路径跨进程非原子。

**迁移表**

| 从 | 触发 / 守卫 | 到 | 证据 |
|---|---|---|---|
| NORMAL | `tryAcquire(account, op)` 距上次放行 < 60s | RATE_WAIT（`retryAt=last+60s`） | `:177-200` |
| NORMAL/RATE_WAIT | 响应出现风控特征（`RGV587`、`fail_sys_user_validate`、`wua_is_machine`、`punish`、`captcha`、`被挤爆`、`哎哟喂`） | CIRCUIT_OPEN 10 分钟 | `:211-227`、`:286-320` |
| CIRCUIT_OPEN | `retryAt <= now`（读时惰性） | NORMAL | `:269-284` |
| CIRCUIT_OPEN | 凭证更新成功后 `clearCircuit` | NORMAL | `:230-238` |
| — | `RECOVERING` | **不可达** | — |

**强制手段**：`tryAcquire` / `recordResponse` / `getStatus` / `clearCircuit` 上的 Java `synchronized`（`:177`、`:203`、`:211`、`:230`、`:241`）。**没有跨进程强制，也没有 DB**。

**缺陷 / 风险**

- **一把锁管所有账号，且在锁内做文件 I/O**（`persistState` 在 `synchronized` 内调用，`:357`）——磁盘一慢，全租户的发货与评价一起卡住。
- **`checkApiWrite` 忽略 operation**（`:203-208`）：任何一次被限流的操作会让该账号**所有**受保护 API 一起熔断，一次擦亮可以卡住发货。
- **持久化尽力而为且静默丢失**（`:372-375` 只打日志）：熔断期间重启 → 写操作静默恢复。
- **护栏状态不落到订单行**，只通过 `deferForRisk` 写 `last_error_code='RISK_GUARD_WAIT'`（`XianyuGoodsOrderMapper.java:187-192`）；节点本地的护栏可以去推迟一条共享 DB 行，于是实例 B 继续重试而实例 A 已被限流。
- `GuardStatus` 只报“最近一个被限流的操作”（`:248-265`），运维看不到逐操作的配额。

---

### SM-11 本地补写的外发消息（合成 `content_type` + 合成 `pnm_id`）

**用途**：平台不会推送卖家自己的发送同步包，因此后端伪造一条聊天记录，让会话视图看起来完整。

**状态集**：`content_type` ∈ {`999` 手动回复、`888` AI 助手、`997` 图片回复、`887` AI 图片回复}（`SentMessageSaveServiceImpl.java:30-40`）——远离平台词表（`1` 用户、`26` 付款、`32` 系统）。`pnm_id` 合成为 `毫秒_随机8位.PNM`（`:133-136`）；`lwp` 写成外发路径（`:81`）；`sender_user_id` 写成卖家自己（`:96`）。

**存储**：DB `xianyu_chat_message`，唯一键 `(xianyu_account_id, pnm_id)`（`V1:103`）。**`complete_msg` 是 `NOT NULL`**，因此 JSON 手工拼接并手工转义（`:106-111`）——回复里的换行/控制字符会被原样嵌入。

**迁移表**：`∅ → row(content_type=888/887/997/999)`，由四个 `@Async` 写入方法产生（`:42-67`）。之后没有迁移；行不可变，也从不与平台自己的回声对账。

**强制手段**：无；`@Async` + 异常吞掉（`:124-126`）。

**缺陷 / 风险**

- **合成 `pnm_id` 让跨源去重失效。** 平台之后用**真实的 `pnm_id`** 回声卖家这条消息时，唯一键不冲突 → **存成第二行**：会话里出现重复气泡，且 `content_type` 不在领域词表内。
- **`saveManualReply` 在 `@Async` 方法里读请求作用域的 `UserContext`**（`:46`）——请求可能已结束，发送者名退化为“手动回复”。
- **历史重同步会造出新 id。** `syncContextMessages` 把解析出的历史逐条重插（`ChatMessageServiceImpl.java:180-199`）；`PlatformHistoryMessageParser` 从载荷字节派生 `pnm_id`，缺失时用 `UUID.nameUUIDFromBytes` 兜底（`PlatformHistoryMessageParser.java:40-46`）——同一条消息只要载荷变了（例如已读标记），就得到**不同的 id → 重复行**。整个过程无事务、无逐行去重（`ChatMessageServiceImpl.java:194-197`）。
- **没有设置 `tenant_id`**：列默认 `1`（`V3:24`），于是非默认租户的这些消息全部落进租户 1 的时间线。

---

### SM-12 事件扇出当作隐式“阶段序”

**用途**：仅靠 `@Order` / `@Async` 注解位置来排列管线阶段（接管判定 → 买家资料 → 自动回复 → 自动发货 → 落库）。

**状态集**：无——“状态”是“对同一事件来说哪些监听器已经跑过”。

**存储**：仅内存，在一次 `ApplicationEventPublisher.publishEvent` 调用内（`SyncMessageHandler.java:317-318`）。

**迁移表**

| 阶段 | 监听器 | 同步/异步 | 顺序 | 证据 |
|---|---|---|---|---|
| 1 | 人工干预（接管） | **同步** | `@Order(1)` | `ChatMessageEventHumanInterventionListener.java:49-50` |
| 1' | 买家资料 touch | `@Async` | `@Order(1)` | `ChatMessageBuyerProfileListener.java:27-29` |
| 2 | 自动发货 | `@Async` | **无** | `ChatMessageEventAutoDeliveryListener.java:70-72` |
| 3 | 自动回复 | `@Async` | `@Order(10)` | `ChatMessageEventAutoReplyListener.java:64-66` |
| — | 消息落库 | **同步** | 无 | `ChatMessageEventSaveListener.java:38-39` |

**强制手段**：只有同步监听器有顺序保证。`@Async` 监听器按注解顺序提交但并发执行，因此“order 10 在发货监听器之后跑”是假的；阶段 1 之所以先于阶段 3，**只因为接管监听器是同步的**，会在异步任务提交前跑完。

**缺陷 / 风险**

- **文档与代码相反。** `event/chatMessageEvent/README.md` 声称所有监听器都 `@Async`、发布者是 `ChatMessageServiceImpl`、落库后推进同步游标；实际落库监听器是同步的、发布者是 `SyncMessageHandler`、游标推进由 client 在 router 返回后完成（`XianyuWebSocketClient.java:350`、`:365-381`）。
- **管线没有“消息已处理”的概念。** 在异步提交与回复记录插入之间崩溃，消息就彻底丢了：聊天行已存（同步监听器），却没有回复记录，而没有任何扫描去找“没有回复的消息”——`recoverDueTasks` 只看已存在的回复行。
- **同一同步包内逐条发布事件**（`SyncMessageHandler.java:98-131`）：5 个监听器 × N 条消息，全部挤在共享 `taskExecutor`（core 4 / max 8 / queue 500 / `CallerRunsPolicy`，`AsyncConfig.java:22-66`）。满载时 `CallerRunsPolicy` 会让 **WebSocket 读线程**就地执行 AI 回复工作。

---

## 3. 相邻机器：`merchant_task`（发货恢复队列，上游 OPS-1）

**用途**：把必须熬过风控冷却期的平台写动作持久化：`BARGAIN_FREE_SHIPPING`、`CONFIRM_SHIPMENT`（`MerchantOperationsService.java:62`、`:450-465`）。

**状态集**：`0` 待执行、`1` 已领取/执行中、`2` 完成、`-1` 失败；配合 `attempt_count` / `max_attempts`。

**存储**：DB `merchant_task`，幂等键 `(tenant_id, task_type, request_key)`。

**迁移**：`0|-1 → 1` 由 `claim`（并可回收超过 10 分钟的 `1`）（`MerchantTaskMapper.java:42-46`）；`1 → 2` `complete`；`1 → -1` `fail`，退避 `min(60, attempt*5)` 分钟；`-1 → 0` `requeue`（清零次数）；`0 → 0` `defer` 且 `attempt_count-1`（`:48-70`）。

**强制手段**：带次数/年龄守卫的条件领取；`countPendingPlatformActions` 读 `status IN (0,1)` 或可重试的 `-1`（`:73-78`）。

**缺陷**：这里用的**整数词表与别处不同**——`1` 表示“执行中”，而在 SM-1 和 `xianyu_goods_order.state` 里 `1` 表示“成功”。`executeConfirmShipment` 只要结果非空就调用 `updateConfirmState`（`MerchantOperationsService.java:780-786`），于是队列重试会把 `ORDER_ALREADY_DELIVERY`（别处已发）也当成本地已确认。

---

## 4. 死物：写而不读 / 读而不写

| 类别 | 具体 | 证据 |
|---|---|---|
| 定义但零引用 | `OrderStatusEnum`（1..5） | 全仓 grep 仅命中自身 |
| 定义但零引用 | `EnhancedMessageSendService` + 三个 `ConcurrentHashMap` 计数器 | 全仓零调用；计数器既无自增也无读取 |
| 定义但零引用 | `ManualModeService` / `ManualModeServiceImpl`（含 `@Scheduled`） | 全仓零注入 |
| 死状态 | `DeliveryStatus.DELIVERED`、`DELIVERED`/`CONFIRMING` 无写入点 | 见 SM-6 |
| 死状态 | `GuardState.RECOVERING` 不可达 | 见 SM-10 |
| 只写不读 | `xianyu_human_intervention_record.xy_goods_id` | 见 SM-3 |
| 只写不读 | `delivery_message_attempt_count`（增但从不作上限） | 见 SM-7 |
| 只写不读 | `kami_usage_record.delivery_status` 的默认值 `'RESERVED'` | 见 SM-8 |
| 死 SQL | `markUsed`、`findNextUnused`、`findRandomUnused`、`updateRateStatus` | 见 SM-8 / SM-9 |
| 终态死胡同 | `delivery_status='SKIPPED'` 无出口 | 见 SM-6 |

---

## 5. 八类横切缺陷

### 5.1 WebSocket 线程与发货调度器的竞态

- Router 跑在 WS 线程上并同步发布事件（`WebSocketMessageRouter.java:97-130`、`AbstractLwpHandler.java:47-72`）。`taskExecutor` 打满时，`CallerRunsPolicy` 让 AI 回复工作**在 WS 线程上就地执行**，阻塞读取与心跳（`AsyncConfig.java:65-66`）。
- 订单发现（WS）与 25s 一次的 API 轮询都会插入同一逻辑订单；互斥只靠两个唯一键，其中一个（`order_id`）可能为 `NULL`（`PendingOrderPollService.java:158` 对比 `ChatMessageEventAutoDeliveryListener.java:260`）。
- `DeliveryTaskScheduler` 只对 `!isConnected` 的账号轮询 API（`:77-79`）；**半开连接**（WS 在、同步断）两条路都不走，也没有对账扫描。
- `dispatchDueTasks`（1s）、`recoverDueTasks`（1s）、`retryDeliveryMessages`（15s）三个调度器从不同线程读改写同一批订单行，没有共享锁；唯一的顺序保证来自逐语句的 `WHERE`。

### 5.2 重复回复 / 重复发货的时间窗

| 时间窗 | 机制 | 后果 |
|---|---|---|
| AI 调用超过 120s 租约 | `claim` 允许按过期重领 + `recoverDueTasks` 每秒扫（`AutoReplyDelayServiceImpl.java:229-241`、`277`） | 一条买家消息得到两条回复 |
| 两个并发 `submitDelayTask` | 后者 `cancelPendingBySession` 杀掉前者刚持久化的行（`:154`、`:164`） | 要么发一次、要么一次都不发，不可预测 |
| 私聊发送后、`markDeliveryMessageSent` 前崩溃 | 留在 `2`，2 分钟后可重领（`XianyuGoodsOrderMapper.java:285-289`） | 发货私聊（含卡密）发两遍 |
| 发货任务租约过期于慢 consign 期间 | 重领 → 第二次 `consignDummyDelivery`（`DeliveryTaskScheduler.java:64-68`） | 平台返回 `ORDER_ALREADY_DELIVERY` → 被推入 `REVIEW_REQUIRED` |
| WS 发送超时被当成功 | `XianyuWebSocketClient.java:866-868` | 不重试、买家没收到、状态显示已发 |
| `@Async` 本地补写与平台回声竞争 | SM-11，合成 `pnm_id` | 会话出现重复条目 |

### 5.3 幂等键清单

| 键 | 位置 | 覆盖范围 | 缺口 |
|---|---|---|---|
| `uk_reply_account_session_message (account, s_id, pnm_id)` | `V1:219` | 每条触发消息一条回复记录 | `pnm_id` 可空；批量回复只按**最后一条**消息取键（`AutoReplyServiceImpl.java:146`） |
| 回复记录 `claim` 租约 | `XianyuGoodsAutoReplyRecordMapper.java:57-60` | 并发 worker | 过期即重开窗口；无次数上限 |
| `uk_goods_order_account_message`、`uk_goods_order_account_order` | `V1:186-187` | 重复订单发现 | `order_id` 可能为 `NULL` |
| `claimTasks` + `FOR UPDATE` | `XianyuGoodsOrderMapper.java:162-173` | 并发发货 worker | 仅租约 |
| `claimDeliveryMessage` | `XianyuGoodsOrderMapper.java:285-289` | 并发私聊发送者 | 仅租约 |
| `uk_kami_config_content`、`uk_usage_*` | `V1:296`、`316-317` | 重复发卡 | 无实质缺口 |
| `(kami_config_id, order_id)` 外部请求 | `XianyuKamiExternalRequestMapper.java:14-22` | 重复供货请求 | 3 次上限 |
| `(tenant, task_type, request_key)` merchant task | `MerchantOperationsService.java:450-465` | 重复平台写重试 | 无实质缺口 |
| **`(account, pnm_id)` 聊天消息** | `V1:103` | 重复落库 | 被合成 `pnm_id` 与重同步打败 |
| `claimReceiptFollowUp` 对 `sent_count` 的 CAS | `XianyuGoodsOrderMapper.java:321-324` | 重复追评 | 无实质缺口 |
| **外发消息本身没有任何幂等键** | — | — | 不持久化 `uuid`/`client_msg_id`，重发与新消息不可区分 |

### 5.4 已写但不读 / 已读但不写

- **写了不读**：`delivery_channel`（写在 `DeliveryTaskServiceImpl.java:40`，仅前端筛选用）、`rate_time`（除展示外无用）、`delivery_message_attempt_count`（只增不判）。
- **读了不写**：前端筛选列表里的 `DELIVERED`/`CONFIRMING`、`throwPlatformWait` 的 `RECOVERING` 分支、`OrderStatusEnum` 的 1..5。
- **不可达**：`delivery_status='DELIVERED'`。
- **死类**：见 §4。

### 5.5 人工接管对在飞自动化不可见

- 接管被 `humanInterventionOn` 门禁挡住（`AutoReplyDelayServiceImpl.java:207-213`）；开关关闭时卖家手动回复**不记录任何东西**，已上膛的 15s 定时器照常触发。
- 发货路径完全不看接管（`DeliveryTaskScheduler.java:94-133`）；已处于 `PROCESSING` 的订单继续发。
- 缓存先读（`HumanTakeoverManager.java:110-132`）、先于 DB 写且无事务（`:80-88`）。
- 还存在第二套未被使用的实现（SM-4），键与超时都不同。
- `pendingMessages` 只在接管路径被清（`:220`），其它路径保留。

### 5.6 重试上限

| 路径 | 上限 | 落实位置 |
|---|---|---|
| 发货任务 | 3（`app.delivery.max-attempts`） | `DeliveryTaskServiceImpl.java:86-89` |
| 回复记录 | **无** | `claim` 从不看 `attempt_count`（`XianyuGoodsAutoReplyRecordMapper.java:57-60`） |
| 私聊 | **无** | `deferDeliveryMessage` 无上限（`XianyuGoodsOrderMapper.java:291-294`） |
| 追评 | 由话术条数（≤5）隐含 | `BuyerMessageService.java:27` |
| 外部卡密 | 3 | `XianyuKamiExternalRequestMapper.java:49` |
| 平台调用 | 3（`callApiWithRetry`）+ 60s 护栏窗口 | `RiskControlServiceImpl.java:191-195` |

### 5.7 消息顺序假设

- `messageList.get(size-1)` 被视为权威的“最后一条消息”，用于取 account/goods/session/order（`AutoReplyServiceImpl.java:87-91`、`AutoReplyDelayServiceImpl.java:244`、`ReplyStrategyResolver.java:34`）——即**假设到达顺序 == `messageTime` 顺序**。全线没有按 `messageTime` 排序；批次按到达顺序追加（`AutoReplyDelayServiceImpl.java:157-161`）再用 `\n` 拼接（`AutoReplyServiceImpl.java:93-96`）。同步包乱序重放 → 喂给 AI 的是一段乱序对话。
- 消息归并靠 `pnm_id` 与 `message_time`（`XianyuChatMessageMapper.java:69-75`），而回复记录按 `s_id` + **最后一条** `pnm_id` 取键——跨两个商品的批次只会回复到最后一个商品的会话里。
- 发货监听器假设每个订单只有一条付款消息（`claimBargainStage`，10 分钟 TTL，内存）（`ChatMessageEventAutoDeliveryListener.java:68`、`:209-224`）；重启即失忆，重放会重跑免拼，只靠平台的 `ORDER_ALREADY_DELIVERY` 兜底（`OrderServiceImpl.java:115-118`）。
- 消息列表按 `message_time DESC` 排序，而该列可空（`V1:100`）；合成行总有值，平台行可能没有。

### 5.8 按消息 id 去重与重连重放

- 游标在 router 返回**之后**推进（`XianyuWebSocketClient.java:350`、`:365-381`）；落库由同步监听器在 router 内完成；DB 更新是单调的（`AND websocket_sync_pts < #{pts}`，`XianyuAccountMapper.java:26-30`）。因此崩溃会重放整个同步包——**这正好是下面这些弱点发作的时刻**。
- 重放去重等于 `(account, pnm_id)`，只有 `pnm_id` 稳定时才可靠。两条路径会破坏稳定性：本地补写的合成 id（SM-11）与历史重解析 id（`PlatformHistoryMessageParser.java:40-46`）。
- 重放时回复记录可能是 `-2` 或 `1`，`claim` 拒绝，消息被静默丢弃（SM-1 缺陷 1）。没有任何“收到但未回复”的补偿扫描。
- `OfflineRecoveryService` 重连时只补**订单**（`:27-43`），不补**未回复消息**：断线期间的买家消息没有任何补偿路径。
- 游标单调只在 DB 写入层成立；`WebSocketSyncCursor.fromSyncPackage(pkg, null)` 永远传 `previous=null`（`:377-379`），所以内存游标对象可以倒退，只是持久化值不会。

---

## 6. 守卫矩阵

| 机器 | DB CHECK | 条件 UPDATE 守卫 | 租约 | 次数上限 | 唯一键 | 形式 FSM | 结论 |
|---|---|---|---|---|---|---|---|
| SM-1 回复记录 | ✗ | 部分（仅 cancel/claim） | ✓ 120s | ✗ | ✓ | ✗ | **弱** |
| SM-2 在飞任务 | n/a | n/a | ✗ | ✗ | n/a | ✗ | **无** |
| SM-3 人工接管 | ✗ | ✗ | 时间窗 | n/a | ✓ | ✗ | **中** |
| SM-4 人工模式 | n/a | n/a | 时间窗 | n/a | n/a | ✗ | **死** |
| SM-5 买家门禁 | ✗ | ✗ | ✗ | n/a | ✓ | ✗ | **弱** |
| SM-6 履约任务 | ✗ | ✓（3 条语句） | ✓ 120s | ✓ 3 | ✓ | 有枚举但被绕过 | **中** |
| SM-7 发货私聊 | ✗ | ✓（每条语句） | ✓ 2min | ✗ | ✗ | ✗ | **中** |
| SM-8 卡密 | ✗ | ✓✓（行锁） | ✓ `FOR UPDATE` | ✓ 3（外部） | ✓✓ | ✓ | **强** |
| SM-9 后置四联 | ✗ | 部分（仅追评） | ✗ | ✓ 5 条 | n/a | ✗ | **弱** |
| SM-10 风控护栏 | n/a | n/a | 时间窗 | n/a | n/a | 4 态中 3 态 | **中（仅单 JVM）** |
| SM-11 本地消息 | ✗ | ✗ | ✗ | ✗ | ✓（被打败） | ✗ | **弱** |
| SM-12 事件阶段 | n/a | n/a | n/a | n/a | n/a | ✗ | **无** |
| merchant_task | ✗ | ✓ | 10min 回收 | ✓ | ✓ | ✗ | **中** |

**三个结构性根因**

1. **把租约当状态用。** 回复与发货两台机器都用短租约列作为唯一互斥，却没有“尝试中、结果未知”态。每一次崩溃都等价于“从未发送过”，唯一的恢复手段就是重发。
2. **一行多写者。** `xianyu_goods_order` 同时承载 `state` / `delivery_status` / `delivery_message_state` / `confirm_state` / `buyer_confirmed_receipt` / 四个追评列，由三个 service 加七条 SQL 改写，其中若干语句在计算一列时强制改写另一列。没有任何不变式把它们绑在一起。
3. **没有对账。** 所有恢复机制都只向前（领取到期行、再发一次）。没有任何东西去扫这个设计必然产生的反向异常：`-2` 但消息未发的回复行、有 `delivery_message_content` 却没有 `state=1` 的订单、`state=1` 却 `delivery_message_state=0`、`SKIPPED` 订单、`kami_item.status=2` 孤儿。

---

## 7. 与上游清单 `../README.md` 的差异

本文与 `../README.md` **不冲突**，编号可直接对照（见 §1 的“上游编号”列）；差异在于深度与新增项。上游清单是“全仓普查”，本文是“管线深挖 + 逐条行号 + 迁移表”。

**新增（上游清单未覆盖或未展开）**
- SM-7 的**超时即成功**（`XianyuWebSocketClient.java:866-868`）及其连带效应：一条没送达的私聊会通过 `DeliveryMessageSentEvent` 触发平台自动确认发货。
- SM-7 的**没有“已发送但结果未定”态**：崩溃/超时后 2 分钟可重领，买家第二次收到含卡密文本的私聊，且因 `pnm_id` 每次不同，这条重复在消息时间线里不可见。
- SM-7 **没有次数上限**（`deferDeliveryMessage` 只增不判）。
- SM-11 整台机器（合成 `content_type` / `pnm_id`；`syncContextMessages` 重解析导致的重复行；`tenant_id` 缺省为 1）。
- SM-12 事件扇出的 `@Async`/`@Order` 语义，以及 `event/chatMessageEvent/README.md` 与代码相反（该 README 说全部 `@Async`、发布者是 `ChatMessageServiceImpl`、落库后推进游标——三条都不成立）。
- SM-9 的 `confirm_state` **完全无守卫**、`rate_status` 允许由一次“读”写入（`GoodsAutomationService.java:329` 写完即抛异常）。
- SM-6 的 `SKIPPED` 终态死胡同；重复发现仍播报 `ORDER_CREATED`。
- SM-1 的 `trigger_context` 一列两种 JSON Schema；软跳过被看门狗记成硬失败。
- `orderStatus` 裸 Integer 过滤器与 `OrderStatusEnum` 的错配陷阱（传 `1` 会筛出 `state=1` 的成功订单）。
- `prepareDeliveryMessage` / `activateDeliveryMessage` 在私聊发送前就强制 `state=1` + `COMPLETED`。
- `merchant_task` 的第三套整数词表（`1`=执行中）与 `executeConfirmShipment` 的 `confirm_state` 误写。
- `CallerRunsPolicy` 让 WebSocket 读线程执行 AI 回复。

**细化（上游只给结论，本文给出完整迁移表与逐条行号）**
- SM-1 / SM-2 / SM-3 / SM-5 / SM-6 / SM-7 / SM-8 / SM-9 / SM-10 的迁移表（§2）。
- 租约无 fencing 的具体后果：不只是“旧 worker 能写回”，而是“崩溃 ≡ 重发 ≡ 买方可感知的重复”。
- 幂等键清单（§5.3）：逐个键标注“覆盖什么 / 缺口在哪”，其中“外发消息本身没有任何幂等键”一条上游未列出。

**修正上游两处**
- 上游 §5.3 / ORD-2 把该机器的风险定位在“守卫是否完整”，本文认为守卫确实完整，**主要风险来自守卫之外的强制写入**：`prepareDeliveryMessage`（`XianyuGoodsOrderMapper.java:236-241`）与 `activateDeliveryMessage`（`:250-255`）同时强制 `state=1` 与 `COMPLETED`。
- 上游 §6.3 / MSG-3 写 `takeover()` “writes DB then cache”，实际顺序相反：**先写缓存（`HumanTakeoverManager.java:80`）再 insert DB（`:88`）**，无事务；因此崩溃窗口的后果是“内存已接管、DB 无记录”，而不是“DB 已接管、内存无记录”。

---

## 8. 形式化建议（按收益排序，本次未实施）

1. 把 `delivery_message_state TINYINT` 换成具名枚举 + `CHECK`，区分 `SENDING` 与 `SENT_UNCONFIRMED`，并持久化一个 `client_message_id` 以便服务端识别重发。
2. 给两台租约机器在 `claim` 里加 `attempt_count < N` 谓词与一个 `EXPIRED` 终态，让崩溃循环暴露为异常而不是无限重发。
3. 把 `delivery_status` 与 `state` 合并为一列（`delivery_status` 改为派生），加 `CHECK`；把 `prepareDeliveryMessage` / `activateDeliveryMessage` 里的“强制 `COMPLETED`”移出去。
4. 删除 `ManualModeService`、`EnhancedMessageSendService`、`OrderStatusEnum`，或让它们通过 `HumanTakeoverManager` 接入——目前它们是同三个概念的第三套词表。
5. 用显式 Saga 步骤表替代五个 `@Order` 监听器，并增加 `chat_message.reply_state`（或 `messages_without_reply` 视图），让“丢失的消息”成为可检测实体而不是缺失。
6. 把 `isTakenOver` 改为 DB 优先（缓存次之），并让 `DeliveryTaskScheduler.executeTask` 也查接管状态，使一次接管决策覆盖所有自动化路径。

---

## 附录 A：文件索引

**核心服务**
[`AutoReplyServiceImpl.java`](../../../src/main/java/com/xianyusmart/service/impl/AutoReplyServiceImpl.java) ·
[`AutoReplyService.java`](../../../src/main/java/com/xianyusmart/service/AutoReplyService.java) ·
[`AutoReplyDelayService.java`](../../../src/main/java/com/xianyusmart/service/AutoReplyDelayService.java) ·
[`AutoReplyDelayServiceImpl.java`](../../../src/main/java/com/xianyusmart/service/reply/AutoReplyDelayServiceImpl.java) ·
[`HumanTakeoverManager.java`](../../../src/main/java/com/xianyusmart/service/reply/HumanTakeoverManager.java) ·
[`ReplyConfigProvider.java`](../../../src/main/java/com/xianyusmart/service/reply/ReplyConfigProvider.java) ·
[`ReplyStrategyResolver.java`](../../../src/main/java/com/xianyusmart/service/reply/ReplyStrategyResolver.java) ·
[`KeywordWithAIPolishStrategy.java`](../../../src/main/java/com/xianyusmart/service/reply/KeywordWithAIPolishStrategy.java) ·
[`KeywordReplyStrategy.java`](../../../src/main/java/com/xianyusmart/service/reply/KeywordReplyStrategy.java) ·
[`ManualModeService.java`](../../../src/main/java/com/xianyusmart/service/ManualModeService.java) ·
[`ManualModeServiceImpl.java`](../../../src/main/java/com/xianyusmart/service/impl/ManualModeServiceImpl.java) ·
[`RiskControlService.java`](../../../src/main/java/com/xianyusmart/service/RiskControlService.java) ·
[`RiskControlServiceImpl.java`](../../../src/main/java/com/xianyusmart/service/impl/RiskControlServiceImpl.java) ·
[`EnhancedMessageSendService.java`](../../../src/main/java/com/xianyusmart/service/EnhancedMessageSendService.java) ·
[`EnhancedMessageSendServiceImpl.java`](../../../src/main/java/com/xianyusmart/service/impl/EnhancedMessageSendServiceImpl.java) ·
[`SentMessageSaveServiceImpl.java`](../../../src/main/java/com/xianyusmart/service/impl/SentMessageSaveServiceImpl.java) ·
[`ChatMessageServiceImpl.java`](../../../src/main/java/com/xianyusmart/service/impl/ChatMessageServiceImpl.java) ·
[`BuyerMessageService.java`](../../../src/main/java/com/xianyusmart/service/BuyerMessageService.java) ·
[`BuyerProfileService.java`](../../../src/main/java/com/xianyusmart/service/BuyerProfileService.java) ·
[`OrderServiceImpl.java`](../../../src/main/java/com/xianyusmart/service/impl/OrderServiceImpl.java) ·
[`OrderService.java`](../../../src/main/java/com/xianyusmart/service/OrderService.java) ·
[`AutoDeliveryServiceImpl.java`](../../../src/main/java/com/xianyusmart/service/impl/AutoDeliveryServiceImpl.java) ·
[`DeliveryTaskServiceImpl.java`](../../../src/main/java/com/xianyusmart/service/impl/DeliveryTaskServiceImpl.java) ·
[`DeliveryTaskScheduler.java`](../../../src/main/java/com/xianyusmart/service/DeliveryTaskScheduler.java) ·
[`PendingOrderPollService.java`](../../../src/main/java/com/xianyusmart/service/PendingOrderPollService.java) ·
[`OfflineRecoveryService.java`](../../../src/main/java/com/xianyusmart/service/OfflineRecoveryService.java) ·
[`WebSocketRecoveryScheduler.java`](../../../src/main/java/com/xianyusmart/service/WebSocketRecoveryScheduler.java) ·
[`GoodsAutomationService.java`](../../../src/main/java/com/xianyusmart/service/GoodsAutomationService.java) ·
[`GoodsAutomationScheduler.java`](../../../src/main/java/com/xianyusmart/service/GoodsAutomationScheduler.java) ·
[`MerchantOperationsScheduler.java`](../../../src/main/java/com/xianyusmart/service/MerchantOperationsScheduler.java) ·
[`MerchantOperationsService.java`](../../../src/main/java/com/xianyusmart/service/MerchantOperationsService.java) ·
[`ExternalKamiProvisionService.java`](../../../src/main/java/com/xianyusmart/service/ExternalKamiProvisionService.java) ·
[`KamiConfigServiceImpl.java`](../../../src/main/java/com/xianyusmart/service/impl/KamiConfigServiceImpl.java) ·
[`OperationsDiagnosticsService.java`](../../../src/main/java/com/xianyusmart/service/OperationsDiagnosticsService.java) ·
[`PlatformHistoryMessageParser.java`](../../../src/main/java/com/xianyusmart/service/PlatformHistoryMessageParser.java)

**WebSocket**
[`WebSocketMessageRouter.java`](../../../src/main/java/com/xianyusmart/websocket/WebSocketMessageRouter.java) ·
[`DefaultWebSocketMessageHandler.java`](../../../src/main/java/com/xianyusmart/websocket/DefaultWebSocketMessageHandler.java) ·
[`AbstractLwpHandler.java`](../../../src/main/java/com/xianyusmart/websocket/handler/AbstractLwpHandler.java) ·
[`SyncMessageHandler.java`](../../../src/main/java/com/xianyusmart/websocket/handler/SyncMessageHandler.java) ·
[`WebSocketSyncCursor.java`](../../../src/main/java/com/xianyusmart/websocket/WebSocketSyncCursor.java) ·
[`XianyuWebSocketClient.java`](../../../src/main/java/com/xianyusmart/websocket/XianyuWebSocketClient.java)

**事件与监听器**
[`ChatMessageData.java`](../../../src/main/java/com/xianyusmart/event/chatMessageEvent/ChatMessageData.java) ·
[`ChatMessageReceivedEvent.java`](../../../src/main/java/com/xianyusmart/event/chatMessageEvent/ChatMessageReceivedEvent.java) ·
[`README.md`](../../../src/main/java/com/xianyusmart/event/chatMessageEvent/README.md) ·
[`ChatMessageEventSaveListener.java`](../../../src/main/java/com/xianyusmart/event/chatMessageEvent/lister/ChatMessageEventSaveListener.java) ·
[`ChatMessageEventAutoReplyListener.java`](../../../src/main/java/com/xianyusmart/event/chatMessageEvent/lister/ChatMessageEventAutoReplyListener.java) ·
[`ChatMessageEventAutoDeliveryListener.java`](../../../src/main/java/com/xianyusmart/event/chatMessageEvent/lister/ChatMessageEventAutoDeliveryListener.java) ·
[`ChatMessageEventHumanInterventionListener.java`](../../../src/main/java/com/xianyusmart/event/chatMessageEvent/lister/ChatMessageEventHumanInterventionListener.java) ·
[`ChatMessageBuyerProfileListener.java`](../../../src/main/java/com/xianyusmart/event/chatMessageEvent/lister/ChatMessageBuyerProfileListener.java)

**实体 / 枚举**
[`XianyuGoodsOrder.java`](../../../src/main/java/com/xianyusmart/entity/XianyuGoodsOrder.java) ·
[`XianyuGoodsAutoReplyRecord.java`](../../../src/main/java/com/xianyusmart/entity/XianyuGoodsAutoReplyRecord.java) ·
[`XianyuChatMessage.java`](../../../src/main/java/com/xianyusmart/entity/XianyuChatMessage.java) ·
[`XianyuGoodsAutoDeliveryConfig.java`](../../../src/main/java/com/xianyusmart/entity/XianyuGoodsAutoDeliveryConfig.java) ·
[`XianyuGoodsConfig.java`](../../../src/main/java/com/xianyusmart/entity/XianyuGoodsConfig.java) ·
[`XianyuHumanInterventionRecord.java`](../../../src/main/java/com/xianyusmart/entity/XianyuHumanInterventionRecord.java) ·
[`XianyuKamiItem.java`](../../../src/main/java/com/xianyusmart/entity/XianyuKamiItem.java) ·
[`XianyuKamiUsageRecord.java`](../../../src/main/java/com/xianyusmart/entity/XianyuKamiUsageRecord.java) ·
[`MerchantTask.java`](../../../src/main/java/com/xianyusmart/entity/MerchantTask.java) ·
[`DeliveryStatus.java`](../../../src/main/java/com/xianyusmart/enums/DeliveryStatus.java) ·
[`OrderStatusEnum.java`](../../../src/main/java/com/xianyusmart/enums/OrderStatusEnum.java) ·
[`KamiStatus.java`](../../../src/main/java/com/xianyusmart/enums/KamiStatus.java) ·
[`DeliveryChannel.java`](../../../src/main/java/com/xianyusmart/enums/DeliveryChannel.java)

**Mapper**
[`XianyuGoodsOrderMapper.java`](../../../src/main/java/com/xianyusmart/mapper/XianyuGoodsOrderMapper.java) ·
[`XianyuGoodsAutoReplyRecordMapper.java`](../../../src/main/java/com/xianyusmart/mapper/XianyuGoodsAutoReplyRecordMapper.java) ·
[`XianyuChatMessageMapper.java`](../../../src/main/java/com/xianyusmart/mapper/XianyuChatMessageMapper.java) ·
[`XianyuKamiItemMapper.java`](../../../src/main/java/com/xianyusmart/mapper/XianyuKamiItemMapper.java) ·
[`XianyuKamiExternalRequestMapper.java`](../../../src/main/java/com/xianyusmart/mapper/XianyuKamiExternalRequestMapper.java) ·
[`XianyuHumanInterventionRecordMapper.java`](../../../src/main/java/com/xianyusmart/mapper/XianyuHumanInterventionRecordMapper.java) ·
[`MerchantTaskMapper.java`](../../../src/main/java/com/xianyusmart/mapper/MerchantTaskMapper.java) ·
[`XianyuAccountMapper.java`](../../../src/main/java/com/xianyusmart/mapper/XianyuAccountMapper.java)

**SQL 迁移**
[`V1__baseline.sql`](../../../src/main/resources/db/migration/V1__baseline.sql) ·
[`V3__multi_tenant_merchant_operations.sql`](../../../src/main/resources/db/migration/V3__multi_tenant_merchant_operations.sql) ·
[`V5__delivery_private_messages_and_receipt_follow_up.sql`](../../../src/main/resources/db/migration/V5__delivery_private_messages_and_receipt_follow_up.sql) ·
[`V7__separate_fixed_and_card_delivery.sql`](../../../src/main/resources/db/migration/V7__separate_fixed_and_card_delivery.sql) ·
[`V8__add_auto_rate_trigger_mode.sql`](../../../src/main/resources/db/migration/V8__add_auto_rate_trigger_mode.sql) ·
[`V10__websocket_recovery_and_notification_providers.sql`](../../../src/main/resources/db/migration/V10__websocket_recovery_and_notification_providers.sql) ·
[`V11__exception_acknowledgement.sql`](../../../src/main/resources/db/migration/V11__exception_acknowledgement.sql)

**配置与前端**
[`AsyncConfig.java`](../../../src/main/java/com/xianyusmart/config/AsyncConfig.java) ·
[`application.yaml`](../../../src/main/resources/application.yaml) ·
[`order-status.ts`](../../../vue-code/src/views/orders/order-status.ts) ·
[`OrderTable.vue`](../../../vue-code/src/views/orders/components/OrderTable.vue)

---

## 附录 B：复现方式

```bash
# 全部状态写入点（迁移的真实定义在 WHERE 里）
grep -rn "delivery_status = \|delivery_message_state = \|state = \|status = " \
  src/main/java/com/xianyusmart/mapper | sed 's/^/  /'

# 死状态：谁写了 DELIVERED / CONFIRMING / RECOVERING
grep -rn "DeliveryStatus.DELIVERED\|DeliveryStatus.CONFIRMING\|GuardState.RECOVERING" src/main/java

# 死代码：单一实现的引用面
grep -rn "ManualModeService\|EnhancedMessageSendService\|OrderStatusEnum" src/main/java

# 台面上的超时即成功
grep -n -A3 "TimeoutException" src/main/java/com/xianyusmart/websocket/XianyuWebSocketClient.java

# 定时驱动源（谁在扫状态）
grep -rn "@Scheduled" src/main/java | sed 's/^/  /'

# 内存态
grep -rn "ConcurrentHashMap\|HashMap<Long, \|Map<String, Long>" src/main/java/com/xianyusmart/service | sed 's/^/  /'

# 前端状态词汇（与后端枚举对账）
grep -rn "statusMeta\|getChatMeta" vue-code/src/views/orders
```
