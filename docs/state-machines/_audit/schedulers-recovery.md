# Implicit State Machine Audit — xianyuSmart

**Scope:** background scheduling / recovery / notification / backup / self-update subsystems.
**Mode:** read-only source audit (no production code modified).
**Method:** full reads of the focus services plus their interfaces, entities, mappers, and Flyway migrations (`V1`–`V18`), cross-checked against call sites and the Vue frontend that renders the same states.

An *implicit state machine* here means: state encoded in ad-hoc fields (status ints, phase strings, booleans, `last*` timestamps, volatile flags, in-memory sets) whose transitions are scattered across methods and schedulers instead of being formalized.

---

## Contents

- [0. Scope, method, and the scheduling substrate](#0-scope-method-and-the-scheduling-substrate)
- [1. Summary table](#1-summary-table)
- [A1. Delivery task lifecycle](#a1-delivery-task-lifecycle-delivery_status)
- [A2. Delivery private-message queue](#a2-delivery-private-message-queue-delivery_message_state)
- [A3. Receipt follow-up](#a3-receipt-follow-up-post-confirmation-message-drip)
- [A4. Order terminal mirror](#a4-order-terminal-mirror-state--confirm_state--rate_status--exception_revision)
- [B1. Kami item reservation](#b1-local-kami-item-reservation-xianyu_kami_itemstatus)
- [B2. External kami provisioning request](#b2-external-kami-provisioning-request-request_status)
- [C1. Merchant task](#c1-merchant-task-merchant_taskstatus)
- [C2. Merchant resource rule scheduling](#c2-merchant-resource-rule-scheduling-merchant_resource)
- [C3. Distribution and settlement](#c3-distribution-and-settlement-merchant_distribution)
- [D1. Notification channel health and log](#d1-notification-channel-health-and-delivery-log)
- [E1. Auto-reply delayed task](#e1-auto-reply-delayed-task-xianyu_goods_auto_reply_recordstate)
- [E2. Human takeover / manual mode](#e2-human-takeover--manual-mode)
- [F1. WebSocket connection lifecycle](#f1-websocket-connection-lifecycle-and-self-healing)
- [F2. WebSocket sync cursor](#f2-websocket-sync-cursor-websocket_sync_ptsseqtimestamp)
- [F3. Account and cookie credential state](#f3-account-and-cookie-credential-state)
- [F4. Risk guard circuit](#f4-risk-guard-circuit-riskcontrolservice)
- [G1. Self-update agent](#g1-self-update-agent-file-based-state-machine)
- [H1. Backup export/import](#h1-backup-exportimport-module-partial-state)
- [I1. Exception acknowledgement](#i1-exception-acknowledgement-exception_revision--ack-table)
- [J1. Goods automation cursors](#j1-goods-automation-cursors-rate_status-last_polish_time-in-memory-page-cursor)
- [2. Cross-cutting findings](#2-cross-cutting-findings-ranked)
- [3. Suggested formalization order](#3-suggested-formalization-order-highest-value-first)

**Machine count: 20** (A1–A4, B1–B2, C1–C3, D1, E1–E2, F1–F4, G1, H1, I1, J1).

---

## 0. Scope, method, and the scheduling substrate

Files traced: `DeliveryTaskScheduler`, `DeliveryTaskService` (+ `Impl`), `GoodsAutomationScheduler`, `GoodsAutomationService`, `WebSocketRecoveryScheduler`, `PendingOrderPollService`, `OfflineRecoveryService`, `MerchantOperationsService`, `MerchantOperationsScheduler`, `NotificationCenterService` + `service/notification/*`, `DataBackupServiceImpl` + `backup/**`, `SystemUpdateService`, `OperationsDiagnosticsService` + `diagnostics/*`, `ExternalKamiProvisionService` + `service/kami/*`, plus all mappers, the entities with status/state fields (`XianyuGoodsOrder`, `MerchantTask`, `MerchantDistribution`, `XianyuGoodsAutoReplyRecord`, `XianyuNotificationChannel/Log`, `XianyuKamiExternalRequest`, `XianyuGoodsAutoDeliveryConfig`, `XianyuAccount`), and migrations `V1`–`V18`.

Substrate facts that every machine below inherits:

- **21 `@Scheduled` methods share one 2-thread pool.** `ThreadPoolTaskScheduler.setPoolSize(2)` — `AsyncConfig.java:134-142`; `@EnableScheduling` — `XianYuSmartApplication.java:14`. All jobs are `fixedDelay` except one `fixedRate`, so one slow job silently skips its own ticks and can occupy one of the two threads indefinitely.
- **Business work is handed to `taskExecutor`** (core 4 / max 8 / queue 500 / `CallerRunsPolicy`) — `AsyncConfig.java:22-76`. Overflow therefore runs the work on the scheduler thread and stalls every other scheduler.
- **Notifications go to `notificationExecutor`** (1–2 threads, queue 200, **`DiscardPolicy`**) — `AsyncConfig.java:78-104`. Overload silently drops notifications: no log, no persisted state.
- **Overlap protection is per-JVM `AtomicBoolean`** (`DeliveryTaskScheduler` has none at all; `GoodsAutomationScheduler.java:18-21` has four; `MerchantOperationsScheduler.java:17` one; `WebSocketRecoveryScheduler.java:25` a `Set`). Nothing is cluster-wide.
- **No formal state machine exists anywhere.** Zero transition tables, zero enum-typed DB columns, and exactly **two** DB `CHECK` constraints in the whole schema (`chk_delivery_mode_exclusive`, `chk_delivery_channel_enabled` — `V7__separate_fixed_and_card_delivery.sql:127-130`). The only in-schema state documentation is two SQL comments (`merchant_task.status`, `V3__multi_tenant_merchant_operations.sql:88`; `xianyu_kami_item.status`, `V1__baseline.sql:289`).

### File index (paths relative to repo root)

- `src/main/java/com/xianyusmart/service/DeliveryTaskScheduler.java`
- `src/main/java/com/xianyusmart/service/DeliveryTaskService.java`
- `src/main/java/com/xianyusmart/service/impl/DeliveryTaskServiceImpl.java`
- `src/main/java/com/xianyusmart/service/GoodsAutomationScheduler.java`
- `src/main/java/com/xianyusmart/service/GoodsAutomationService.java`
- `src/main/java/com/xianyusmart/service/WebSocketRecoveryScheduler.java`
- `src/main/java/com/xianyusmart/service/impl/WebSocketServiceImpl.java`
- `src/main/java/com/xianyusmart/service/PendingOrderPollService.java`
- `src/main/java/com/xianyusmart/service/OfflineRecoveryService.java`
- `src/main/java/com/xianyusmart/service/MerchantOperationsService.java`
- `src/main/java/com/xianyusmart/service/MerchantOperationsScheduler.java`
- `src/main/java/com/xianyusmart/service/NotificationCenterService.java`
- `src/main/java/com/xianyusmart/service/notification/PinnedHttpsClient.java`, `WebhookSecurity.java`, `NotificationTemplateRenderer.java`
- `src/main/java/com/xianyusmart/service/impl/DataBackupServiceImpl.java`
- `src/main/java/com/xianyusmart/backup/DataBackupHandler.java`, `src/main/java/com/xianyusmart/backup/handler/*`
- `src/main/java/com/xianyusmart/service/SystemUpdateService.java`
- `src/main/java/com/xianyusmart/service/OperationsDiagnosticsService.java`
- `src/main/java/com/xianyusmart/service/diagnostics/OperationsHealthEvaluator.java`
- `src/main/java/com/xianyusmart/service/ExternalKamiProvisionService.java`
- `src/main/java/com/xianyusmart/service/impl/KamiConfigServiceImpl.java`
- `src/main/java/com/xianyusmart/service/kami/ExternalKamiGateway.java`, `ExternalKamiResponseParser.java`
- `src/main/java/com/xianyusmart/service/impl/AutoDeliveryServiceImpl.java`
- `src/main/java/com/xianyusmart/service/BuyerMessageService.java`
- `src/main/java/com/xianyusmart/service/impl/AutoReplyServiceImpl.java`
- `src/main/java/com/xianyusmart/service/reply/AutoReplyDelayServiceImpl.java`
- `src/main/java/com/xianyusmart/service/reply/HumanTakeoverManager.java`
- `src/main/java/com/xianyusmart/service/impl/ManualModeServiceImpl.java`
- `src/main/java/com/xianyusmart/service/impl/RiskControlServiceImpl.java`
- `src/main/java/com/xianyusmart/service/impl/CookieRefreshServiceImpl.java`
- `src/main/java/com/xianyusmart/service/impl/WebSocketTokenServiceImpl.java`
- `src/main/java/com/xianyusmart/service/impl/AccountServiceImpl.java`
- Mappers: `XianyuGoodsOrderMapper`, `XianyuGoodsAutoReplyRecordMapper`, `MerchantTaskMapper`, `MerchantResourceMapper`, `MerchantDistributionMapper`, `XianyuKamiItemMapper`, `XianyuKamiExternalRequestMapper`, `XianyuNotificationChannelMapper`, `XianyuNotificationLogMapper`, `XianyuAccountMapper`
- Migrations: `src/main/resources/db/migration/V1__baseline.sql` … `V18__widen_goods_sku_remote_id.sql`
- Update agent: `deploy/self-update/xianyusmart-update-agent.sh`, `deploy/self-update/xianyusmart-update.path`
- Frontend renderer of the same states: `vue-code/src/views/orders/components/OrderTable.vue`

---

## 1. Summary table

| # | Machine | Storage | States | Primary risk |
|---|---|---|---|---|
| A1 | Delivery task lifecycle | `xianyu_goods_order.delivery_status` + lease cols | 7 live (2 dead) | Terminal `COMPLETED` can be demoted; `SKIPPED` absorbing |
| A2 | Delivery private-message queue | `delivery_message_state` (+3 cols) | 0,1,2,3,4,5 (undocumented) | No owner token on state 2 → duplicate chat; unlimited retries |
| A3 | Receipt follow-up | `receipt_follow_up_*`, `buyer_confirmed_receipt` | completed 0/1, count 0..5, next_time | Lost optimistic update → duplicate messages |
| A4 | Order terminal mirror | `state`, `confirm_state`, `rate_status`, `exception_revision` | -1/0/1, 0/1, -1/0/1 | Two variables for one concept; `rate_status` display-only |
| B1 | Kami item reservation | `xianyu_kami_item.status` | 0/1/2/3 | API-sourced reservations leak (release is LOCAL-only) |
| B2 | External kami request | `request_status` (VARCHAR) | PROCESSING/SUCCESS/FAILED/REVIEW_REQUIRED | `REVIEW_REQUIRED` has no exit; attempt cap 3 |
| C1 | Merchant task | `merchant_task.status` | 0/1/2/-1 | No lease fencing → duplicate publish; attempts reset to 0 |
| C2 | Merchant resource rule | `merchant_resource.status`, `scheduled_time`, `last_run_time` | 0/1/2 | `status=2` is a one-way "published" trap; status-space collision |
| C3 | Distribution | `status`, `settlement_status` | 0/1, 0/1 | Settle before publish; publish can be overwritten |
| D1 | Notification channel + log | `enabled`, `last_success_time`, `last_error_message`, `send_status` | 0/1 | No retry, no dead-letter, `DiscardPolicy` drops events |
| E1 | Auto-reply delayed task | `state` | 0/1/2/-1/-2 | No backoff, no attempt cap, cancel only affects state 0 |
| E2 | Human takeover / manual mode | DB record + in-memory map (×2) | active-until-expiry | Two parallel mechanisms, one volatile |
| F1 | WebSocket connection lifecycle | in-memory maps/sets | enabled→connecting→connected→backoff | Set leak on reject; per-node only; manual stop lost on restart |
| F2 | WebSocket sync cursor | `websocket_sync_pts/seq/timestamp` | monotonic cursor | Frozen silently if platform regresses |
| F3 | Account / cookie credential | `xianyu_account.status`, `xianyu_cookie.cookie_status` | 1 / -1 / -2 ; 1/2/3 | `-1` documented-never-written, `-2` written-undocumented |
| F4 | Risk guard circuit | in-memory maps + JSON file | NORMAL/RATE_WAIT/CIRCUIT_OPEN/RECOVERING | `RECOVERING` unreachable; multi-node file clobbering |
| G1 | Self-update agent | 3 files: `status.json`, `request.json`, `installing.task` | 10 statuses | Stale-timeout deletes request mid-install; no task-id checking on read |
| H1 | Backup import | none (request-scoped) | per-module success/fail | Failed export imports as success; no transaction |
| I1 | Exception acknowledgement | `exception_revision` + ack table | revision snapshot per source | Revisions bumped inconsistently → ack suppresses or resurfaces wrongly |
| J1 | Auto-rate / polish cursors | in-memory page cursor, `last_polish_time`, `rate_status` | cursor + calendar-day flag | Cursor lost on restart; success inferred from error text |

---

## A1. Delivery task lifecycle (`delivery_status`)

**Purpose.** Durable queue for "this paid order must be delivered", claimed by any node under a time-boxed lease.

**State set.** `PENDING, PROCESSING, RETRY_WAIT, DELIVERED, CONFIRMING, COMPLETED, FAILED, REVIEW_REQUIRED, SKIPPED` — `enums/DeliveryStatus.java:3-13`. Column `VARCHAR(24) NOT NULL DEFAULT 'PENDING'` — `V1__baseline.sql:175`.

**`DELIVERED` and `CONFIRMING` are never written by any code path.** Verified: the only `DeliveryStatus` writes are `PENDING` (`DeliveryTaskServiceImpl.java:37`), `PROCESSING` (`:69`), `SKIPPED` (`PendingOrderPollService.java:78`), and the SQL literals `'PENDING'/'PROCESSING'/'RETRY_WAIT'/'COMPLETED'/'FAILED'/'REVIEW_REQUIRED'` in `XianyuGoodsOrderMapper`. Yet the frontend renders both dead states in its status map (`vue-code/src/views/orders/components/OrderTable.vue`) — operator-visible states that cannot occur.

**Storage.** DB only, plus the parallel pair `lease_owner` / `lease_expire_time` (`V1:180-181`). Desync is possible with `state` (see A4).

**Transition table**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| ∅ | `discover()` (auto-delivery on) | PENDING | `DeliveryTaskServiceImpl.java:36-41`; SQL default when `state`≠1 `XianyuGoodsOrderMapper.java:49` |
| ∅ | auto-delivery off for goods | SKIPPED | `PendingOrderPollService.java:78-80` |
| PENDING / RETRY_WAIT (due) or PROCESSING (lease expired) | `lockDueTasks` (`FOR UPDATE`, `delivery_message_state NOT IN (3,4,5)`) + `claimTasks` | PROCESSING, `attempt_count+1`, lease 120 s | `XianyuGoodsOrderMapper.java:162-173`; `DeliveryTaskServiceImpl.java:58-73` |
| PROCESSING | `complete()` (caller re-reads `state==1` first) | COMPLETED, `delivered_quantity=expected`, lease cleared | `DeliveryTaskServiceImpl.java:76-78`; mapper `:175-177`; caller `DeliveryTaskScheduler.java:113-115` |
| PROCESSING | `retryOrFail()`, `attempts < max-attempts(3)` | RETRY_WAIT, `next_retry_time=now+min(300, 5<<attempts)` | `DeliveryTaskServiceImpl.java:81-92`; mapper `:179-185` |
| PROCESSING | `retryOrFail()`, `attempts >= 3` | FAILED (`next_retry_time=NULL`) | same |
| PROCESSING | `deferForRisk()` (CONSIGN_DEFERRED) | RETRY_WAIT, **`attempt_count-1`**, `last_error_code='RISK_GUARD_WAIT'` | `DeliveryTaskServiceImpl.java:95-99`; mapper `:187-192`; caller `AutoDeliveryServiceImpl.java:503-517` |
| **any state (no guard)** | `markReviewRequired()` | REVIEW_REQUIRED | `XianyuGoodsOrderMapper.java:198-201`; caller `DeliveryTaskScheduler.java:100-102` |
| FAILED / RETRY_WAIT | `requeue()` (manual) | PENDING, `next_retry_time=NOW` | mapper `:203-205` |
| FAILED / RETRY_WAIT / REVIEW_REQUIRED | `requeueFailed()` / `markDelivered()` | PENDING (`state=0`, attempts 0) / COMPLETED (`state=1`) | mapper `:207-212`, `:214-220` |
| any | `updateState` / `updateStateContentAndFailReason` (`state`=±1) | COMPLETED or FAILED, **unconditionally** | mapper `:138-141`, `:148-151`; `AutoDeliveryServiceImpl.java:736-742` |
| any | `prepareDeliveryMessage` | COMPLETED + `state=1` **before the chat message is sent** | mapper `:236-241`; `BuyerMessageService.java:143` |

**Scheduling model.** `dispatchDueTasks`: `fixedDelay=${app.delivery.dispatch-delay-ms:1000}`, `initialDelay=5000`, batch 20, each claimed task submitted to `taskExecutor` — `DeliveryTaskScheduler.java:64-68`. Lease 120 s, max attempts 3, `workerId = host + uuid8` — `DeliveryTaskServiceImpl.java:22-26`, `DeliveryTaskScheduler.java:135-139`. A second scheduler polls the platform for accounts whose WebSocket is down (`fixedDelay=25000`, `initialDelay=60000`) — `DeliveryTaskScheduler.java:70-92`. Restart behavior: correct in principle (lease expiry re-queues `PROCESSING`), except for the held-message exclusion below.

**Enforcement.** DB-conditional `WHERE` clauses exist on 4 of 10 writes (`claimTasks`; `retryOrFailTask` with `NOT IN ('REVIEW_REQUIRED','COMPLETED')` at `:182`; `requeueTask`/`requeueFailedTask`/`markDeliveredTask` with `state <> 1`). No FK, no CHECK, no enum type, no transition table, no state-event log.

**Defects / risks**

1. **Terminal state re-entry.** `markTaskReviewRequired` (`:198-201`), `deferForRisk` (`:187-192`), `completeTask` (`:175-177`) and `updateState*` (`:138-151`) have **no status guard**: a `COMPLETED` order can be demoted to `REVIEW_REQUIRED` / `FAILED` / `RETRY_WAIT`, and `updateStateContentAndFailReason(-1)` flips `delivery_status='FAILED'` for an already-completed order. Both writers run from the same delivery pipeline (`AutoDeliveryServiceImpl.java:607` vs `:633`).
2. **Semantic conflation of the terminal state.** `prepareDeliveryMessage` marks `COMPLETED` + `state=1` *before* the private message is sent (`BuyerMessageService.java:143`), so `COMPLETED` means "voucher accepted", not "delivered". All dashboards (`XianyuGoodsOrderMapper.java:25-28`) and the scheduler (`DeliveryTaskScheduler.java:114`) treat it as delivered.
3. **`SKIPPED` is an absorbing trap.** `requeueTask` accepts only `FAILED/RETRY_WAIT` (`:203-205`); `requeueFailedTask` excludes `SKIPPED` (`:207-212`). `PendingOrderPollService.queueOrder` calls `requeue()` for any existing non-`state=1` row and returns it as if queued (`PendingOrderPollService.java:96-103`) — enabling auto-delivery later never resurrects the order, silently.
4. **Invisible stuck state.** `lockDueTasks` excludes rows with `delivery_message_state IN (3,4,5)` (`:165`). An order whose voucher committed but whose chat message is held (card/fixed hold) stays in `PROCESSING` with a reaped lease for up to 30 min (hold TTL, mapper `:243-248`), and no other query can pick it up; if the 15 s retry job is disabled it is stuck forever.
5. **Attempt accounting is not a count.** `deferForRisk` decrements `attempt_count` (`:188`) while `claimTasks` increments it per execution, so the counter no longer bounds executions — yet `retryOrFail` derives "exhausted" from it (`DeliveryTaskServiceImpl.java:86-88`).
6. **Counter reset bypass.** `requeueFailedTask` sets `attempt_count=0` (`:208`), so operator-driven retries can loop indefinitely, each retry re-running card reservation (see B1).
7. **Multi-tenant scan.** `lockDueTasks`/`claimTasks` have no `tenant_id` predicate and the index `idx_goods_order_task` (`V1:188`) omits it; every node scans all tenants every second.
8. **Claim mismatch aborts the whole tick.** `DeliveryTaskServiceImpl.java:65-67` throws `IllegalStateException` on a partial claim, rolling the transaction back and logging an error every second while the condition persists.
9. **Write-only field.** `delivery_channel` is written once at `discover` (`:40`) and never updated or read by any query; attribution goes stale when a task is requeued through another channel.
10. **Legacy bypass.** `recordAutoDelivery` inserts with `state=1` and no `deliveryStatus`, so the SQL `CASE` derives `COMPLETED` (`AutoDeliveryServiceImpl.java:160-174`; mapper `:49`) — a delivered order that never entered this machine.

---

## A2. Delivery private-message queue (`delivery_message_state`)

**Purpose.** Persist the rendered delivery text so the buyer chat message can be re-sent across crashes, and hold it while the voucher/consignment result is unconfirmed.

**State set (values exist only in code; no SQL comment, no enum):**

| Value | Meaning | Only writer |
|---|---|---|
| `0` | idle / queued for send | `prepareDeliveryMessage` (`XianyuGoodsOrderMapper.java:236-241`), `activateDeliveryMessage` (`:250-255`), `cancelHeldDeliveryMessage` (`:257-260`), `cancelStaleFixedHeldDeliveryMessages` (`:267-270`), `deferDeliveryMessage` (`:291-294`) |
| `1` | sent | `markDeliveryMessageSent` (`:282-283`) |
| `2` | claimed / in flight | `claimDeliveryMessage` (`:285-289`) |
| `3` | held pending card / external confirmation | `holdDeliveryMessage(...,3)` (`:243-248`) via `BuyerMessageService.java:155-157` |
| `4` | held pending fixed-content voucher confirmation | `holdDeliveryMessage(...,4)` (`:243-248`) via `BuyerMessageService.java:159-161` |
| `5` | fixed-content confirmed, committable | `confirmFixedHeldDeliveryMessage` (`:262-265`) |

Column `TINYINT NOT NULL DEFAULT 0` — `V5__delivery_private_messages_and_receipt_follow_up.sql:9-11`. **No documentation exists for any value.**

**Storage.** DB (`delivery_message_content`, `_state`, `_attempt_count`, `_next_retry_time`) **plus** duplicated in-memory fields on the `XianyuGoodsOrder` object (`deliveryMessageState`, `XianyuGoodsOrder.java:53`). Desync is explicit and repaired by hand, e.g. `BuyerMessageService.java:201` sets the in-memory copy after `activateDeliveryMessage` updated the DB.

**Transition table**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| ∅ | `queueDeliveryMessage` → `prepareDeliveryMessage` (also writes `state=1`, `delivery_status=COMPLETED`) | 0, immediate send attempt | mapper `:236-241`; `BuyerMessageService.java:139-150` |
| ∅ | `holdDeliveryMessage(...,3)` (card) / `holdFixedDeliveryMessage` (4) | 3 / 4, `next_retry_time=+30 min` | `BuyerMessageService.java:155-174`; mapper `:243-248` |
| 3 / 5 | `activateDeliveryMessage` (guard `content IS NOT NULL AND state IN (3,5)`) | 0 (**and** `state=1`, `COMPLETED` again) | mapper `:250-255`; `BuyerMessageService.java:176-182` |
| 4 | `confirmFixedHeldDeliveryMessage` (guard `state=4`) | 5, `confirm_state=1` | mapper `:262-265`; `AutoDeliveryServiceImpl.java:562-567` |
| 3/4/5 | `cancelHeldDeliveryMessage` | 0, content cleared | mapper `:257-260`; `BuyerMessageService.java:184-189` |
| 0 / 2 (due) | `claimDeliveryMessage` (guard `state IN (0,2) AND next_retry<=now`) | 2, `next_retry=+2 min` | mapper `:285-289`; `BuyerMessageService.java:250-252` |
| 2 | send success → `markDeliveryMessageSent` (**no guard**) | 1 | mapper `:282-283`; `BuyerMessageService.java:254-259` |
| 0/2 | send failure → `deferDeliveryMessage` | 0, `attempt_count+1`, `next_retry=now+min(300, 15<<min(attempt,4))` | mapper `:291-294`; `BuyerMessageService.java:285-289` |
| 4 | stale (`next_retry<=now`) → `cancelStaleFixedHeldDeliveryMessages(100)` | 0, content cleared | mapper `:267-270`; `BuyerMessageService.java:193` |
| 3 | stale, no `DELIVERED` usage record → `markStaleCardHeldMessagesReviewRequired(100)` | **delivery_status=REVIEW_REQUIRED** (message state untouched, `next_retry` cleared) | mapper `:272-280`; `BuyerMessageService.java:195` |
| 3 (with `DELIVERED` usage) / 5 | `selectCommittedHeldDeliveryMessages` → `activateDeliveryMessage` | 0 | mapper `:301-307`; `BuyerMessageService.java:197-206` |

**Scheduling model.** `GoodsAutomationScheduler.retryDeliveryMessages` — `fixedDelay=${app.automation.delivery-message-retry-delay-ms:15000}`, `initialDelay=60000`, `AtomicBoolean` guard (`GoodsAutomationScheduler.java:71-83`) → `BuyerMessageService.retryPendingDeliveryMessages` (100 + 100 + 100 rows per tick). Immediate send happens inline inside the delivery pipeline. Restart behavior: correct in principle (DB is the truth), but see defect 4.

**Enforcement.** Guarded: claim (`:287-288`), activate (`:254`), cancel (`:259`), confirm (`:264`). Unguarded: `markDeliveryMessageSent`, `deferDeliveryMessage` (any state), `markStaleCardHeldMessagesReviewRequired` (writes `delivery_status`).

**Defects / risks**

1. **State 2 is a timer, not a lease.** There is no owner column and no fencing: after 2 minutes a second node may claim the same row (`:287-288`), and `markDeliveryMessageSent` (`:282-283`) does not require `state=2`, so both senders can succeed → **duplicate private message containing the card secret**. A slow `sendMessageWithResult` (connect + `ensureConnected`, `BuyerMessageService.java:272-277`) easily exceeds 2 minutes.
2. **No attempt cap.** `delivery_message_attempt_count` grows forever, backoff caps at 300 s, and the state set has no terminal "failed" value, so a permanently undeliverable message retries every 5 minutes for the life of the deployment and nothing surfaces it (diagnostics only look at `delivery_status`).
3. **Silent content loss.** State 4 → 0 after 30 min clears `delivery_message_content` (`:267-270`) with no record; a fixed-content order whose voucher confirmation never arrived is silently downgraded to "nothing to send".
4. **Illegal recovery write.** `markStaleCardHeldMessagesReviewRequired` sets `delivery_status='REVIEW_REQUIRED'` with no guard on `delivery_status` and clears the lease (`:276-279`) → demotes `COMPLETED` orders whose card usage record was not written (e.g. partial failure), even though the voucher was already submitted.
5. **Undocumented state `5`.** Written only by `confirmFixedHeldDeliveryMessage`, read only by `selectCommittedHeldDeliveryMessages` (`:305`); no comment, no enum, invisible to the API and UI.
6. **The UI collapses the set.** `getChatMeta` maps everything ≠ 1 to "私聊待重试" (`vue-code/src/views/orders/components/OrderTable.vue:129-131`), so held states 3/4/5 are indistinguishable from transient failures for operators.
7. **Non-deterministic bulk update.** `cancelStaleFixedHeldDeliveryMessages` is `UPDATE ... LIMIT` without `ORDER BY` (`:267-270`).
8. **Cross-state side effect.** `activateDeliveryMessage` re-writes `state=1` and `delivery_status='COMPLETED'` (`:250-255`) — the message activation, not the delivery, is the last writer of the order terminal state.

---

## A3. Receipt follow-up (post-confirmation message drip)

**Purpose.** After the buyer confirms receipt, send up to 5 templated messages at a configurable interval.

**State set.** `receipt_follow_up_completed ∈ {0,1}`, `receipt_follow_up_sent_count ∈ [0,5]`, `receipt_follow_up_next_time`, `buyer_confirmed_receipt ∈ {0,1}` — `V5:12-15`. The 5-message cap and the 5–600 s interval are enforced only in Java (`BuyerMessageService.java:27, 80-124`).

**Storage.** DB only. No `receipt_follow_up_attempt` counter and no per-message log; history is reconstructible only from `xianyu_operation_log`.

**Transition table**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| completed=0, next_time<=now, config has messages | `claimReceiptFollowUp` (guard `sent_count` match, `completed=0`) | next_time=+2 min | `XianyuGoodsOrderMapper.java:321-324`; `GoodsAutomationService.java:122-124`; selector `:309-316` |
| claimed | platform `tradeStatus != 交易成功` | next_time=+1 min | `GoodsAutomationService.java:152-155`; mapper `:334-336` |
| claimed | `buyerRated == true` | completed=1 | `:156-159` |
| claimed | `buyerConfirmedReceipt != 1` | `buyer_confirmed_receipt=1` (separate write) | `:161-165`; mapper `:318-319` |
| claimed | no config / empty messages / `sent >= size` | completed=1 | `:167-178` |
| claimed | send failed | next_time=+30 s | `:180-184` |
| claimed | send ok | sent_count+1, completed if last, next_time=+interval | `:186-192`; mapper `:326-332` |

**Scheduling model.** `GoodsAutomationScheduler.sendReceiptFollowUps` — `fixedDelay=${app.automation.receipt-follow-up-delay-ms:5000}`, `initialDelay=60000`, `AtomicBoolean` (`GoodsAutomationScheduler.java:57-69`); 100 orders per tick, grouped per account, one platform rate-detail call per account.

**Enforcement.** Optimistic concurrency on `sent_count` in both `claim` and `progress`; `completed` is one-way.

**Defects / risks**

1. **Lost optimistic update → duplicate sends.** The return value of `updateReceiptFollowUpProgress` is ignored (`GoodsAutomationService.java:191-192`). If the compare fails, the message was already sent but `sent_count` did not advance, so the same message is re-sent on the next tick (and the 2-minute claim has already been consumed).
2. **Lease shorter than the batch.** The claim is a 2-minute `next_time` on up to 100 rows, then platform API calls plus per-order sends run serially; exceeding 2 minutes lets a second node claim the same rows before progress is written.
3. **Unbounded parking.** The "platform not completed" and "no config" branches only push `next_time` forward (1 min / 1 h) with no attempt counter and no terminal state; the row is re-selected forever as long as it satisfies `selectDueReceiptFollowUps` (which requires config rows to exist with messages).
4. **Second writer on `buyer_confirmed_receipt`.** Also set by `confirmFixedHeldDeliveryMessage` (`:262`) and `markBuyerConfirmedReceipt` (`:318`) — no guard, no audit.
5. **Historical rows forcibly completed.** `V5:20` sets `receipt_follow_up_completed=1` for all existing orders; there is no marker distinguishing "explicitly disabled" from "never applicable".

---

## A4. Order terminal mirror (`state` / `confirm_state` / `rate_status` / `exception_revision`)

**Purpose.** Legacy single-int outcome plus confirmation/rating flags and an exception revision counter used by diagnostics.

**State set.** `state ∈ {-1,0,1}` (`TINYINT DEFAULT 0`, `V1:164`; no CHECK); `confirm_state ∈ {0,1}` (`V1:166`, `V3:55-57`); `rate_status ∈ {0,1,-1}` (`V3:55`, default 0); `exception_revision` counter (`V11:1-2`); `rate_source ∈ {AUTO, MANUAL, PLATFORM}` free text (`V4`).

**Storage.** DB. `state` and `delivery_status` are two variables for one concept and are only kept in sync inside the mapper's `CASE` expressions (`XianyuGoodsOrderMapper.java:139, 144, 149, 238, 251`).

**Transition table**

| From | Trigger | To | Evidence |
|---|---|---|---|
| 0 | order created | 0 | `DeliveryTaskServiceImpl.java:36-41`; `PendingOrderPollService.java:164` |
| 0/-1 | record update with `state=1` | 1, `delivery_status=COMPLETED` | mapper `:138-151`; `AutoDeliveryServiceImpl.java:607` |
| 0/1 | record update with `state=-1` | -1, `delivery_status=FAILED` | mapper `:148-151`; `AutoDeliveryServiceImpl.java:633` |
| -1 | `requeueFailedTask` | 0 | mapper `:207-208` |
| -1/0 | `markDeliveredTask` | 1 | mapper `:214-219` |
| any | `prepareDeliveryMessage` / `activateDeliveryMessage` | 1 | mapper `:238`, `:251` |
| 0 | `confirmFixedHeldDeliveryMessage` / `updateConfirmState` | confirm_state 1 | mapper `:262`, `:222` |
| any | `updateRateResult` | rate_status 1/-1 + rate_time + content + source | mapper `:229-234`; `GoodsAutomationService.java:360` |
| any | `updateState*(state=-1)` | exception_revision+1 | mapper `:140, 145, 150` |

**Scheduling model.** Not scheduled; written by the delivery pipeline and by `GoodsAutomationService` (rating) which runs on a 60 s cadence.

**Enforcement.** None beyond `state <> 1` in three requeue/deliver statements. `state` is never consulted before writing `rate_status` or `confirm_state`.

**Defects / risks**

1. **Two sources of truth.** `DeliveryTaskScheduler` branches on `state` (`:114`); dashboards and revenue aggregate on `state` (`XianyuGoodsOrderMapper.java:25-28, 435, 444`); diagnostics classify on `delivery_status` (`OperationsDiagnosticsService.java:48-71`). Any write that touches only one leaves the row in two different states — e.g. `markTaskReviewRequired` changes `delivery_status` while `state` stays 0.
2. **`rate_status` is effectively write-only.** Written by `rateOrder` (`GoodsAutomationService.java:360`) and only ever *displayed* (`OrderController.java:90`, `AutoDeliveryServiceImpl.java:293`); eligibility is re-derived from the platform on every run (`GoodsAutomationService.java:285`), so the persisted value is a stale hint, and `rate_status=-1` never triggers a retry by itself.
3. **`updateRateStatus` (mapper `:225`) is dead code.**
4. **`exception_revision` is bumped by some writers only** (see I1), so acknowledgement semantics are unreliable.

---

## B1. Local kami item reservation (`xianyu_kami_item.status`)

**Purpose.** Reserve card secrets for an order, commit them on confirmed delivery, release or escalate otherwise.

**State set.** `0` 可用 / `1` 已交付 / `2` 已预占 / `3` 待核对 — **SQL comment** `V1__baseline.sql:289`, mirrored by `enums/KamiStatus.java:6-11`. This is the only machine in the codebase whose value space is documented in the schema.

**Storage.** DB: `xianyu_kami_item.status`, `order_id`, `reserved_time`, `used_time`; plus the append-only `xianyu_kami_usage_record.delivery_status='DELIVERED'`, which is the *proof* other machines test for (`XianyuGoodsOrderMapper.java:277-279, 302-304`).

**Transition table**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| 0 | `lockAvailable ... FOR UPDATE` + `reserve(ids, orderId)` (guard `status=0`) | 2, `order_id`, `reserved_time` | `XianyuKamiItemMapper.java:60-79`; `KamiConfigServiceImpl.java:357-392` |
| 0 | `markUsed` (direct acquire path) | 1, `used_time` | `XianyuKamiItemMapper.java:54-55`; `KamiConfigServiceImpl.java:327` |
| 2 | `commitReservation(orderId)` (guard `status=2`) | 1, `used_time`; usage rows inserted | `XianyuKamiItemMapper.java:81-82`; `KamiConfigServiceImpl.java:397-433` |
| 2 | `releaseReservation` (guard `status=2 AND config.source_type='LOCAL'`) | 0, `order_id`/`reserved_time` cleared | `XianyuKamiItemMapper.java:84-87`; `KamiConfigServiceImpl.java:436-441` |
| 2 | `markReservationReviewRequired` (guard `status=2`) | 3 | `XianyuKamiItemMapper.java:89-90`; `KamiConfigServiceImpl.java:444-449` |
| 1 / 3 | `markUnused` (admin reset) | 0 | `XianyuKamiItemMapper.java:57-58` |

**Scheduling model.** No scheduler. Driven synchronously by the delivery pipeline (`AutoDeliveryServiceImpl.java:487-585`) with `@Transactional` commits.

**Enforcement.** Every transition is a guarded `UPDATE ... WHERE status = X` (correct, monotonic, compare-and-set). `countUnsettledByConfigId` blocks config edits while items are in 2/3 (`KamiConfigServiceImpl.java:620-621`).

**Defects / risks**

1. **API-sourced reservations leak.** `releaseReservation` joins `config.source_type='LOCAL'` (`:86`), so cards reserved by `ExternalKamiProvisionService` (status 2) are **never released** on any cancel path (`AutoDeliveryServiceImpl.java:457-459, 504-506, 549-551`), while `commitReservation` is source-agnostic. Result: permanent reservations, `countUnsettled > 0` forever → the warehouse can never be edited or deleted.
2. **Status 3 has no exit through the product.** Only the admin `markUnused` (1/3→0) returns it; there is no "resolve as delivered" transition from 3, so a `REVIEW_REQUIRED` card can only be reset to available (risking a second sale) or left stuck.
3. **Cross-account order-id scoping.** `lockReservedByOrder` / `findByOrderAndStatus` / `commitReservation` / `releaseReservation` / `markReservationReviewRequired` are keyed by `order_id` alone (`XianyuKamiItemMapper.java:65-67, 75-77, 82, 87, 90`), while `xianyu_kami_usage_record` is unique on `(account, order, index)` (`V1:316`). Two accounts with the same platform order id collide across tenants.
4. **Non-atomic with the chat message.** `commitReservation` commits cards in a DB transaction; sending the buyer message is a later, separate write (`AutoDeliveryServiceImpl.java:573-602`). A crash between them is recoverable only because the usage record exists — the crash-recovery contract is an undocumented side effect of B1, not of A2.
5. **`status=1` is reachable twice** (`markUsed` and `commitReservation`) with different side effects (no usage record in the `markUsed` path).

---

## B2. External kami provisioning request (`request_status`)

**Purpose.** Idempotent outbound card-supply call per `(config, order)` with retry.

**State set.** `PROCESSING`, `SUCCESS`, `FAILED`, `REVIEW_REQUIRED` — string literals only (insert `'PROCESSING'` `XianyuKamiExternalRequestMapper.java:18`; writes in `ExternalKamiProvisionService.java:77, 81, 84`; no SQL comment, `V9:110` has none). `attempt_count` is initialized to 1 (meaning attempt *number*, not count) — `V9:111`, mapper `:19`.

**Storage.** DB (`xianyu_kami_external_request`), unique key `(tenant, config, order)` (`V9:116`). `update_time` doubles as a lease. Diagnostics duplicates the literal set in SQL (`OperationsDiagnosticsService.java:104-107`).

**Transition table**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| ∅ | `insertIfAbsent` (`INSERT IGNORE`) | PROCESSING, attempt=1 | mapper `:11-22` |
| PROCESSING | `markSuccess` (guard `status='PROCESSING'`) | SUCCESS | mapper `:57-65`; service `:146` |
| PROCESSING | gateway / runtime failure | FAILED (definite) or REVIEW_REQUIRED (uncertain) | service `:76-87`; mapper `:67-76` |
| FAILED, or PROCESSING older than 2 min | `claimRetry` (guard `attempt_count < 3`) | PROCESSING, attempt+1, `exception_revision+1` | mapper `:41-55`; service `:116-122`, staleness `:163-167` |
| REVIEW_REQUIRED | **no transition exists** | — | `claimRetry` accepts only FAILED / stale PROCESSING; `prepare` returns 409 for anything else (`:116-119`) |

**Scheduling model.** No scheduler. Synchronous, request-driven, with a short `TransactionTemplate` for prepare (`:53-54`) and save (`:70-71`).

**Enforcement.** Compare-and-set on status in all three updates; `INSERT IGNORE` for idempotency; the 2-minute `update_time` window is the only recovery for a crashed caller.

**Defects / risks**

1. **`REVIEW_REQUIRED` is a dead end.** Once written, the row can never be transitioned by code — every subsequent `reserve()` for that order throws 409 "正在获取或等待人工核对" forever (`:116-119`), the config cannot be edited (`KamiConfigServiceImpl.java:620-621`) and cannot be deleted while unsettled. Only manual DB surgery recovers.
2. **Silent attempt exhaustion.** After the third attempt the request is permanently `FAILED` with no escalation to `REVIEW_REQUIRED` and no notification; `countUnsettledByConfigId` counts only `PROCESSING`/`REVIEW_REQUIRED` (`mapper:33-38`), so an exhausted `FAILED` row does not block config edits while still blocking all retries.
3. **Crash between supply and persist.** Items are inserted and `markSuccess` runs in one transaction (`:70-75, 133-150`) — good — but the *gateway call* happened before it; if the process dies after the provider returned and before the transaction commits, the provider has issued cards the app does not know about. `REVIEW_REQUIRED` on the retry path is the only guard, and it is unreachable for this crash shape.
4. **`update_time` is auto-updated by any write** (`V9:115 ON UPDATE CURRENT_TIMESTAMP`), including `markFailure`; using it as a lease is only safe because failures are terminal.
5. **Literal duplication.** The four status strings are re-typed in diagnostics SQL, in the acknowledgement SQL (`OperationsDiagnosticsService.java:296-307`) and in the mapper; no enum ties them together.

---

## C1. Merchant task (`merchant_task.status`)

**Purpose.** Execute merchant operations (collect/select/publish/delete/compensate/workflow/bargain/confirm-shipment) with retry.

**State set.** `0` 待执行 / `1` 执行中 / `2` 成功 / `-1` 失败 — SQL comment `V3:88`. `attempt_count` / `max_attempts` / `next_retry_time` (`V3:90-92`). `max_attempts` is set per task type at creation: PUBLISH=1, platform-order tasks=12, others=3 (`MerchantOperationsService.java:442-444`), 12 for the enqueue path (`:498`), 1/3 for rule-scheduled tasks (`:652`).

**Storage.** DB only; `request_key` provides idempotency for PUBLISH (`V14:1-4`, unique `(tenant, task_type, request_key)`).

**Transition table**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| ∅ | `createTask` / `enqueuePlatformOrderTask` / `scheduleDueRules` | 0 | `MerchantOperationsService.java:432-447`, `:489-501`, `:643-654` |
| 0, -1 (due), 1 (stale > 10 min) | `selectDue` (limit 20) then `claim` (guard status / reclaim) | 1, `attempt_count+1` | `MerchantTaskMapper.java:37-45`; service `:660-666` |
| 1 | `complete` (**no guard**) | 2 | mapper `:47-48`; service `:690` |
| 1 | `fail` (**no guard**) | -1, `next_retry=now+min(60, attempt*5)` min | mapper `:50-52`; service `:703-705` |
| 1 | `defer` (risk) | 0, `attempt_count-1`, `scheduled_time=retryAt` | mapper `:57-60`; service `:695-702` |
| 0/1/-1 | `requeue` (**no guard**) | 0, `attempt_count=0`, `scheduled_time=NOW` | mapper `:54-55`; service `:634-638`, `:480-486`, `:982` |

**Scheduling model.** `MerchantOperationsScheduler.dispatch` — `fixedDelay=${app.operations.dispatch-delay-ms:60000}`, `initialDelay=${app.automation.initial-delay-ms:60000}`, single `AtomicBoolean` around **both** `scheduleDueRules()` and `processDueTasks()` (`MerchantOperationsScheduler.java:23-36`). `processDueTasks` executes inline on the scheduler thread (one of two), 20 tasks per tick, serially. Restart behavior: orphaned `status=1` rows are recoverable only via the `updated_time <= NOW-10 min` heuristic (`MerchantTaskMapper.java:38-39, 44`).

**Enforcement.** Only `claim` has guards. `complete`, `fail`, `requeue`, `defer` are unconditional, and there is no owner/fence column.

**Defects / risks**

1. **No fencing → duplicate publish.** A publish that exceeds 10 minutes (human-like delays, platform retries) is re-claimed by the same or another node while still running; the stale worker then calls `complete`/`fail` unconditionally, overwriting the newer result. `max_attempts=1` prevents *retry*, not *concurrent re-execution*.
2. **Terminal `2` is not terminal.** `fail` (`:50-52`) and `requeue` (`:54-55`) have no state predicate: a preempted worker can flip `2 → -1`, and for platform-order tasks (max 12) that re-runs the side effect.
3. **Attempt budget resets.** `requeue` zeroes `attempt_count` (`:54`), reachable from the `/requeue` endpoint, from `executeCompensation` (`:982`) and from `enqueuePlatformOrderTask` for exhausted rows (`:480-486`) → unbounded retries.
4. **Stale attempt math.** `fail` computes the backoff from the *in-memory* pre-claim `attemptCount` (`:704-705`), not the DB value `claim` just incremented.
5. **`scheduleDueRules` is two writes.** Insert task, then `updateNextRun` (`:654-656`). A crash between them makes the rule fire again next tick with no idempotency key (`request_key` is NULL for rule tasks, so the unique index does not help). Two nodes both calling `scheduleDueRules` (guarded only per-JVM) duplicate every rule.
6. **Scheduler starvation.** Publishing runs inline in the 2-thread `taskScheduler`, so one slow publish blocks the other 20 scheduled jobs; nothing records last-run time, so the skip is silent.
7. **`status=1` has no heartbeat/expiry column**; the 10-minute heuristic is also refreshed by unrelated writes (`updated_time` is `ON UPDATE CURRENT_TIMESTAMP`, `V3:97`).

---

## C2. Merchant resource rule scheduling (`merchant_resource`)

**Purpose.** Periodic rule resources (selection/publish/delete rules) whose next run is encoded in `scheduled_time`, with `last_run_time` as the only history.

**State set.** `status ∈ {0,1,2}` — `1` enabled (`V3:64` default 1; `selectDueRules`/`selectEnabledByTenantAndType` require 1), `0` disabled, `2` "published" (only written by `executePublish`, `MerchantOperationsService.java:922`). **No comment, no enum, three different meanings per resource type.**

**Storage.** DB: `status`, `scheduled_time`, `last_run_time`, `xy_goods_id` (`V3:59-79`). No history table.

**Transition table**

| From | Trigger | To | Evidence |
|---|---|---|---|
| 1 | `selectDueRules` (status=1, `scheduled_time<=now`) | task created, `scheduled_time=now+interval(>=5 min)`, `last_run_time=NOW` | `MerchantResourceMapper.java:28-30, 45-46`; service `:641-658` |
| 0/1/2 | `saveResource` (client-supplied `status`, no validation) | any | service `:168` |
| 1 | `executePublish` success | **2** (+ `xy_goods_id`) | service `:917-925` |
| any | `executePromotionRefresh` | copied from `xianyu_account.status` | service `:1101` |

**Scheduling model.** Same 60 s scheduler tick as C1; 50 rules per pass; `intervalMinutes` read from `dataJson` (default 1440), floored at 5.

**Enforcement.** None on `status`; `updateNextRun` is unguarded.

**Defects / risks**

1. **`status=2` is a one-way trap.** Once a MATERIAL is published, `selectEnabledByTenantAndType` (`:33`) and `selectDueRules` (`:28`) no longer see it — it can never be re-published, re-selected, or scheduled again, and nothing resets it.
2. **State-space collision.** `executePromotionRefresh` writes `account.status` (1 / -2 semantics, see F3) into `resource.status` (`:1101`) — two unrelated value spaces in one column; a `-2` account marks the promotion resource `-2`, which `selectDueRules` treats as disabled.
3. **Client-controlled transitions.** `saveResource` accepts any `status` from the request (`:168`) with no transition rules, so `2 → 1 → 2` and arbitrary jumps are possible via the API.
4. **Crash window.** Task insert + `updateNextRun` are not atomic (C1 defect 5).

---

## C3. Distribution and settlement (`merchant_distribution`)

**Purpose.** Track supply→material relations, publication, commission and settlement.

**State set.** `status ∈ {0,1}` — 0 relation created, 1 published (written by `updatePublishedByMaterial`, `MerchantDistributionMapper.java:32-37`); `settlement_status ∈ {0,1}` with `settlement_time` (`V3:113-116`). No comment, no enum; `commission_amount` is mutable with no version.

**Storage.** DB, unique relation `(tenant, supply, material)` (`V3:121`).

**Transition table**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| ∅ | `ensureDistribution` / `saveDistribution` | status=0, settlement=0 | service `:1144-1157`, `:593-615` |
| 0 | `updatePublishedByMaterial` (**no guard**, keys only on tenant+material) | status=1, `xy_goods_id`, `xianyu_account_id` | mapper `:32-37`; service `:1159-1162`, `:924` |
| 0 | `settle` (guard `settlement_status=0`) | settlement=1 + time | mapper `:39-40`; service `:628-632` |

**Scheduling model.** None; driven by publish/compensation requests and a manual settle endpoint.

**Enforcement.** `settle` is a proper compare-and-set. `updatePublishedByMaterial` is not.

**Defects / risks**

1. **Settle before publish.** Nothing requires `status=1` before `settlement_status=1`; an unpublished relation can be settled.
2. **Publish attribution overwrite.** `updatePublishedByMaterial` matches on `(tenant_id, material_resource_id)` only (`:33`) and is called for every publish, so publishing the same material to a second account silently rewrites `xianyu_account_id` / `xy_goods_id` of the existing relation (lost update), and `status=1` is never re-evaluated.
3. **No reversal path.** There is no `settlement_status=2` (refund/void); the only correction is a DB edit.
4. **Client-supplied `status`** at creation (`:609`) lets a caller create a pre-"published" relation with no goods id.

---

## D1. Notification channel health and delivery log

**Purpose.** Fan out business events (`ORDER_CREATED`, `DELIVERY_SUCCESS`, `DELIVERY_EXCEPTION`, `ACCOUNT_OFFLINE`, `CREDENTIAL_EXPIRED`, `KAMI_STOCK_LOW` — `NotificationCenterService.java:51-54`) to tenant channels; record each attempt.

**State set.** Channel: `enabled ∈ {0,1}` (user-controlled only), `last_success_time`, `last_error_message` — `V9:69-71`, writes in `XianyuNotificationChannelMapper.java:20-29`. Log: `send_status ∈ {0,1}`, `http_status`, `error_message` — `V9:84-86`. There is **no** pending/retry/failed-terminal distinction.

**Storage.** DB. Health is a *derived* pair of columns with no invariant (a channel can have `last_error_message` set and a newer `last_success_time`; `markSuccess` clears the error, `markFailure` leaves success untouched).

**Transition table**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| ∅ | `dispatch(event)` on `notificationExecutor`, unknown events rejected | log row `send_status=1` + channel success | `NotificationCenterService.java:136-165`, `:181-202` |
| any | HTTP non-2xx, provider body failure, exception | log row `send_status=0` + `last_error_message` | `:203-210`; `requireProviderSuccess` `:375-392` |
| any | `testChannel` (sync) | log row (event `TEST`) | `:122-130` |
| any | user edit | `enabled` 0/1 | `:107` |

**Scheduling model.** `@Async("notificationExecutor")` (1–2 threads, queue 200, **`DiscardPolicy`**) — `AsyncConfig.java:78-104`. Tenant context is re-derived from `accountId` when missing (`:167-179`) and cleared in `finally` only if it was set here. No scheduler, no retry queue, no backoff.

**Enforcement.** `EVENT_TYPES` whitelist (`:51-54, 139-142, 493-507`); URL safety (`WebhookSecurity.requireSafeUrl` at save time, `:100`); no delivery-side state guard.

**Defects / risks**

1. **At-most-once with silent loss.** `DiscardPolicy` drops the task with no log and no persisted state; a saturation event loses notifications permanently. A transient provider error also has no retry — `send_status=0` is terminal.
2. **No dead-letter / no failed-channel circuit.** A permanently broken channel is retried on every event forever; nothing auto-disables it or counts consecutive failures. `last_error_message` is the only signal and it is overwritten by the next failure.
3. **Diagnostics counts 24 h of failures with no acknowledgement** (`OperationsDiagnosticsService.java:116-121`) → a single external outage yields a "warning" on every page load for 24 h with no way to clear it (unlike delivery, which has the ack table in I1).
4. **Field semantics are contradictory.** A provider HTTP 200 with `errcode != 0` records `http_status=200` and `send_status=0` (`:194-199` → `:203-208`), so `http_status` cannot distinguish transport from business failure.
5. **`http_status` is never read** by any code (verified: the only read is `testChannel`'s own response map, `:129`).
6. **Crash window.** The log row is inserted *after* the HTTP call (`:192-201`); a crash in between loses the record. The catch path also re-inserts the same object (`:208`), so an insert failure inside the success branch can still yield a `send_status=0` row for a successful send.
7. **`TEST` is outside `EVENT_TYPES`** (`:51-54` vs `:127`), so the test event is only reachable through `testChannel`; any code path calling `dispatch("TEST", …)` would be logged as "unknown event" and dropped.
8. **No deduplication.** Every `DELIVERY_SUCCESS` / `DELIVERY_EXCEPTION` dispatch (`AutoDeliveryServiceImpl.java:608`) is delivered once per enabled channel with no idempotency key, so a retried delivery pipeline can notify twice.

---

## E1. Auto-reply delayed task (`xianyu_goods_auto_reply_record.state`)

**Purpose.** Debounce buyer messages, then run the reply strategy; retry after crashes.

**State set.** `0` pending, `2` claimed/processing, `1` success, `-1` failed, `-2` cancelled. **The entity comment documents only "0-待回复, 1-成功, -1-失败"** (`XianyuGoodsAutoReplyRecord.java`, state field) — `2` and `-2` exist only in SQL. Column `TINYINT DEFAULT 0` (`V1:209`), lease columns (`V1:211-216`), `exception_revision` (`V11:4-5`).

**Storage.** DB **plus** two in-memory maps in `AutoReplyDelayServiceImpl` (`pendingTasks`, `pendingMessages`, `:94-103`) that hold the actual merged message list; the DB row holds a serialized copy in `trigger_context`.

**Transition table**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| ∅ | `submitDelayTask` → `persistTask` | 0, `scheduled_time=now+delay` | `AutoReplyDelayServiceImpl.java:243-259`; insert `XianyuGoodsAutoReplyRecordMapper.java:17-23` |
| ∅ | new message for same session | previous rows 0 → -2 | `cancelDelayTask` `:174-182`; mapper `:62-64` |
| 0 (due) or 2 (lease expired) | `findDue(20)` → `claim(id, workerId, 120)` | 2, `attempt_count+1`, lease +120 s | mapper `:52-60`; service `:229-241`, `:277-280` |
| 2 | `executeAutoReply` success | 1 | `AutoReplyServiceImpl.java:215-217`; mapper `:26-29` |
| 2 | strategy empty / send failed / exception | -1 | `AutoReplyServiceImpl.java:165-169, 219-222`; `AutoReplyDelayServiceImpl.java:308-313` |
| 0/2 | takeover or buyer automation blocked | -2 | `AutoReplyDelayServiceImpl.java:294-305`; mapper `:65-67` |
| 2 | workflow finished but state still 2 (early `return` inside the strategy path) | **-1** | `AutoReplyDelayServiceImpl.java:307-310` |

**Scheduling model.** Recovery `@Scheduled(fixedDelay=1000, initialDelay=5000)` limit 20 (`:229-241`); primary path via `ScheduledExecutorService autoReplyScheduler` (pool 2, `AsyncConfig.java:129-132`) + `taskExecutor` for execution (`:280`). Lease 120 s. Restart: in-memory `pendingTasks` lost (`@PreDestroy :105-117` cancels futures), recovery falls back to `findDue` and the persisted `trigger_context`.

**Enforcement.** `claim` is a proper compare-and-set (state 0 or expired 2); `updateStateAndContent` clears the lease but does not verify ownership; `cancelById` guards `state IN (0,2)`.

**Defects / risks**

1. **No backoff at all.** `next_retry_time` is filtered in `findDue` (`:52-54`) but **no code ever writes it** — only `claim` / `updateStateAndContent` clear it. A record that fails is immediately eligible again; the only throttle is the 120 s lease.
2. **No attempt cap.** `claim` increments `attempt_count` (`:57`) and nothing reads it. An AI/websocket outage produces an infinite 1-per-2-min retry loop.
3. **Lease vs. slow AI.** 120 s is shorter than a slow AI + image upload sequence; the expired-lease branch re-claims and re-sends, so duplicate replies are possible (no fencing on the send).
4. **Cancel misses in-flight work.** `cancelPendingBySession` only touches `state=0` (`:62-64`); a message arriving while the previous one is claimed (state 2) creates a **second** record (unique key is per `pnm_id`, `V1:219`) and both can reply → two answers per conversation.
5. **`submitDelayTask` is not per-session synchronized.** `cancelDelayTask` → `pendingMessages.compute` → `persistTask` → `scheduler.schedule` (`:153-170`) can interleave between two message threads; one thread's row can be cancelled after the other's insert, losing a reply.
6. **Skipped ≠ failed.** Any early `return` in `AutoReplyServiceImpl` (no goods config `:114-117`, no strategy `:121-124`) leaves the row at 2, and `executeClaimedTask:308-310` then marks it **-1**, so `AUTO_REPLY` failures in diagnostics (`OperationsDiagnosticsService.java:72-83`) include benign skips.
7. **`-2` is unmanaged.** Nothing deletes or ages cancelled rows; diagnostics ignore them; `countPending` counts only 0/2 (`:69`).
8. **Restart loses merged context.** After a restart the retry uses the serialized `trigger_context` only; messages merged during the delay window that were not yet persisted are dropped.

---

## E2. Human takeover / manual mode

**Purpose.** Suppress automation for a conversation after the seller replies manually.

**State set.** Two independent mechanisms:

- `HumanTakeoverManager`: in-memory `takeoverMap<key, expireMillis>` **plus** a DB row (`xianyu_human_intervention_record.end_time`, unique `(account, s_id)` — `V1:391-402`). Logical states: active-until-expiry, expired.
- `ManualModeServiceImpl`: in-memory `manualModeConversations<chatId, lastEnteredSeconds>` with a timeout from config — **no DB, no scheduled cleanup state**.

**Transition table**

| From | Trigger | To | Evidence |
|---|---|---|---|
| inactive | seller manual reply (intervention enabled) | active until `end_time` | `AutoReplyDelayServiceImpl.java:200-223`; `HumanTakeoverManager.java:86-105` |
| active | expiry check on read | inactive (cache entry removed) | `HumanTakeoverManager.java:120-145` |
| active | `@Scheduled(fixedDelay=60000)` cleanup | cache + DB rows removed | `HumanTakeoverManager.java:142-162` |
| inactive/active | `enterManualMode` / `exitManualMode` (separate map) | set / removed | `ManualModeServiceImpl.java:62-76` |
| volatile | `@Scheduled(fixedRate=300000)` cleanup by timeout | removed | `ManualModeServiceImpl.java:101-107` |

**Scheduling model.** Two separate scheduled cleaners (60 s `fixedDelay` and 300 s `fixedRate` — the only `fixedRate` job in the codebase, so it can overlap its own previous run).

**Enforcement.** None; the DB unique key on `(account, s_id)` exists (`V1:399`) but `takeover()` inserts unconditionally every time (`HumanTakeoverManager.java:96-99`), so repeated takeovers must be tolerated by `findActiveByAccountAndSId`.

**Defects / risks**

1. **Duplicate state machines.** `HumanTakeoverManager` (persisted) and `ManualModeServiceImpl` (volatile) both mean "human is handling this chat" with different lifetimes; no code reconciles them, and only the first is consulted by `AutoReplyDelayServiceImpl` (`:142, :294`).
2. **Volatile state loss.** `manualModeConversations` and the takeover cache both vanish on restart (the DB row survives for `HumanTakeoverManager` only).
3. **Duplicate rows.** Every `takeover()` inserts a new intervention row; no upsert on `(account, s_id)`, so history grows and `findActiveByAccountAndSId` picks an arbitrary row.

---

## F1. WebSocket connection lifecycle and self-healing

**Purpose.** Keep one live session per account so order events arrive in real time; reconnect with backoff.

**State set.** Desired state = `account.status=1` ∧ valid cookie (`XianyuAccountMapper.selectReconnectableAccounts`, `:16-21`) ∧ not in `manuallyStoppedAccounts`. Actual state = `webSocketClients` map + heartbeat timestamps. In-flight = `connectingAccounts` (scheduler-local `Set`, `WebSocketRecoveryScheduler.java:25`). Backoff = `reconnectAttemptCounts` (per-account `AtomicInteger`). Additional transient maps: `connectionRestartFlags`, `reconnectTasks`, `heartbeatTasks`, `tokenRefreshTasks`, `tokenRetryTasks`, `lastHeartbeat{Send,Response}Times`, `lastTokenRefreshTimes`, `captchaRetryCounts`, `lastDisconnectNotifyTimes` (`WebSocketServiceImpl.java:78-125`).

**Storage.** **In-memory only** — all of it. No DB column records "connection state"; recovery depends entirely on periodic re-derivation and on restart.

**Transition table**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| disconnected | `maintainConnections` finds a reconnectable account, `!isConnected`, `connectingAccounts.add` | connecting (task submitted to `taskExecutor`) | `WebSocketRecoveryScheduler.java:35-45` |
| connecting | `ensureConnected` under per-account `synchronized(connectionLocks)` | connected + heartbeat + `offlineRecoveryService.recover` | `WebSocketServiceImpl.java:146-160`, `:373-401`, `:527-537` |
| connected | `onConnectionClosed` (unintentional) | reconnect scheduled with delay | `:365-372`, `:825-920` |
| connected | `stopWebSocket` (manual) | stopped + `manuallyStoppedAccounts.add` | `:479-520` |
| stopped | `startWebSocket` | manual flag removed | `:138-141` |
| any | `restartAfterCredentialUpdate` | stop → token clear → start, attempt counter reset | `:523-540` |

**Scheduling model.** `fixedDelay=${websocket.recovery-check-interval-ms:10000}`, `initialDelay=3000` (`WebSocketRecoveryScheduler.java:35`). No cap on how many accounts are dispatched per tick; each `connect` occupies a `taskExecutor` thread for up to 10 s (`connectBlocking`, `WebSocketServiceImpl.java:381`). Reconnect backoff is scheduled on a 4-thread `webSocketScheduler` (`AsyncConfig.java:118-121`).

**Enforcement.** Per-account lock prevents concurrent connects *within a node*; `isConnectionHealthy` (heartbeat age, `:537+`) is the only liveness check; the mail notification has a threshold of 3 and a 10-minute debounce (`:127-131`).

**Defects / risks**

1. **Set leak blocks recovery forever.** `connectingAccounts.add` happens before `taskExecutor.execute` (`WebSocketRecoveryScheduler.java:39-43`); removal is only in `connect`'s `finally` (`:55`). If `execute` throws (executor shutting down / rejected) or the JVM is killed after `add`, the account is never retried until restart — and there is no TTL or size bound on the set.
2. **Single-node assumption.** `manuallyStoppedAccounts` and `connectingAccounts` are per-JVM: in a cluster every node tries to connect every account, and an operator's manual stop on one node is ignored by the others.
3. **Manual stop is volatile.** A restart clears `manuallyStoppedAccounts`, so all accounts reconnect — an intentional operational stop is undone by a deploy.
4. **Business paths race recovery.** `ensureConnected` is also called from the delivery/reply paths (`BuyerMessageService.java:238, 272-274`); the lock makes it safe, but a delivery can open a socket the operator just closed unless `stop` happened first.
5. **No persistent connection state** → diagnostics cannot distinguish "never connected" from "connected then crashed"; `OperationsDiagnosticsService.java:125-126` recomputes disconnection by iterating account ids against the in-memory map on every request.

---

## F2. WebSocket sync cursor (`websocket_sync_pts/seq/timestamp`)

**Purpose.** Resume event consumption from the last acknowledged position instead of replaying everything.

**State set.** Three monotonic counters per account (`V10:1-4`), read once at connect (`WebSocketServiceImpl.java:387-394`), written on every ack (`:373-374`) and after init (`:396-399`).

**Storage.** DB, single row per account. Writes are guarded by `websocket_sync_pts < #{pts}` (`XianyuAccountMapper.java:24-28`) — the only monotonicity guard in the codebase.

**Transition table**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| NULL | first connect | cursor = initializer result | `WebSocketServiceImpl.java:396-399` |
| any | ack with higher `pts` | advanced | `:373-374`; mapper `:24-28` |
| any | ack with lower/equal `pts` | **silently ignored** | mapper `:26` |

**Scheduling model.** Event-driven at connect time and per ack; no scheduled validation.

**Enforcement.** Strictly monotonic `UPDATE ... WHERE pts < ?`.

**Defects / risks**

1. **Silent freeze.** If the platform ever returns a lower cursor (session reset, server-side rollback, clock skew), every write is discarded with no log and no metric; the account silently resumes from a stale position or misses the reconnect window. There is no alerting path and no "cursor age" check.
2. **Cross-node races are invisible.** Two nodes racing to advance the cursor is "solved" by the guard, but the loser's in-memory cursor stays behind, so its next ack is also ignored — progress stalls on that node.
3. **`seq`/`timestamp` are derived.** When `pts` is absent the timestamp is computed as `pts/1000` (`WebSocketServiceImpl.java:392-393`) — a fabricated value that then gets persisted.
4. **No gap/duplicate detection** downstream; the offline compensation (F1 → `OfflineRecoveryService`) polls orders on every reconnect regardless of the cursor, so the cursor's actual contribution is unaudited.

---

## F3. Account and cookie credential state

**Purpose.** Gate every automated subsystem on "account usable + credentials valid".

**State set.**

- `xianyu_account.status`: `1` normal; `-1` "需要手机号验证" (**documented in the entity**, `XianyuAccount.java:45`, **never written anywhere**); `-2` "需要验证 / 异常待处理" (**written, undocumented** — `CookieRefreshServiceImpl.java:556-559`, `WebSocketTokenServiceImpl.java:822-830`).
- `xianyu_cookie.cookie_status`: `1` 有效 / `2` 已过期 / `3` 已失效 (`AccountService.java:98`, rendered in `WebSocketController.java:443-452`).

**Storage.** DB. No transition log; the `operation_log` entries are prose.

**Transition table**

| From | Trigger | To | Evidence |
|---|---|---|---|
| any | cookie saved/updated | cookie_status 1 | `AccountServiceImpl.java:155-167, 296-305` |
| any | `updateCookieStatus(2)` (expiry detected) | 2 (+ mail if first transition) | `AccountServiceImpl.java:404-450` |
| -2 | cookie refresh success (hasLogin or browser fallback) | 1 | `CookieRefreshServiceImpl.java:580-600` |
| any | captcha detected in token flow | -2 | `WebSocketTokenServiceImpl.java:822-830` |
| any | hasLogin **and** browser fallback both failed | -2 | `CookieRefreshServiceImpl.java:375, 392, 556-559` |
| 1 | account manually disabled | 0 (API-level, no dedicated transition) | account controller / UI |

**Scheduling model.** `TokenRefreshServiceImpl`: cookie keep-alive `@Scheduled(fixedDelay=60 s)` with a randomized next-run gate (`:400-410`), websocket token refresh `fixedDelay=60 s` (`:486`), Playwright temp cleanup `fixedDelay=60 min` (`:585`). No overlap guards on any of these.

**Enforcement.** Only the `if status == -2` check before restoring to 1 (`CookieRefreshServiceImpl.java:583`, `WebSocketTokenServiceImpl.java:840`). Nothing prevents `-2 → -2`, and nothing prevents a *captcha-required* account from being flipped to 1 by the cookie path while the captcha is still unsolved.

**Defects / risks**

1. **Documented state never written (`-1`)**, **undocumented state written (`-2`)** — the entity comment and the code disagree; any tool reading the comment mislabels `-2` as "phone verification required".
2. **Asymmetric recovery.** `-2 → 1` happens in two services with different preconditions (`CookieRefreshServiceImpl` restores on cookie success; `WebSocketTokenServiceImpl` restores only if `status == -2`); no `-2` reason is stored, so diagnostics cannot distinguish captcha from cookie failure (`OperationsDiagnosticsService.java:37-47` counts `status <> 1` as one bucket).
3. **Two independent flags for one gate.** `selectReconnectableAccounts` requires `account.status=1` **and** `cookie.cookie_status=1` **and** non-empty cookie text (`XianyuAccountMapper.java:16-21`); the delivery/merchant paths only check the account row, so an account with `cookie_status=2` still accepts queued work that will fail.
4. **No transition audit.** The only record of `-2` is a free-text `operation_log` message (`CookieRefreshServiceImpl.java:556-559`).
5. **`updateById` full-row writes.** Both status setters read-modify-write the whole account row (`account.setStatus(...); updateById(account)`), so a concurrent writer (device id, note) can revert the status (lost update).

---

## F4. Risk guard circuit (`RiskControlService`)

**Purpose.** Per-account write-operation gate: rate window plus a 10-minute circuit after a platform risk signal. Gates delivery, publishing, polishing, rating and platform-order tasks.

**State set.** `NORMAL`, `RATE_WAIT`, `CIRCUIT_OPEN`, `RECOVERING` — `RiskControlService.java` `GuardState`. **`RECOVERING` is declared and never constructed** (no producer anywhere).

**Storage.** In-memory `HashMap rateLimits` + `HashMap circuits` (`RiskControlServiceImpl.java:66-68`), persisted wholesale to `${app.risk-guard.state-file}` as JSON (`persistState` `:355-374`, `loadState` `:336-352`); expiry 60 s rate window / 600 s circuit (`:31-32`).

**Transition table**

| From | Trigger | To | Evidence |
|---|---|---|---|
| NORMAL | `tryAcquire` allowed | NORMAL, `rateLimits[key]=now` + persist | `:186-200` |
| NORMAL | second acquire inside 60 s | RATE_WAIT until `last+60 s` | `:192-196` |
| NORMAL/RATE_WAIT | `recordResponse` detects RGV587 / USER_VALIDATE | CIRCUIT_OPEN until `now+600 s` (max with existing) | `:211-226` |
| CIRCUIT_OPEN | `retryAt <= now` on next check | NORMAL (entry removed) | `:276-284` |
| CIRCUIT_OPEN | `clearCircuit` (full credential update) | NORMAL | `:228-232`; caller `restartAfterCredentialUpdate` |
| RATE_WAIT | window elapses | NORMAL | derived, `:192-196` |

**Scheduling model.** No scheduler — evaluated on each write attempt. Consumers convert `CIRCUIT_OPEN` into a delayed retry: delivery defers to `retryAt` (`AutoDeliveryServiceImpl.java:503-517, 744-750`), merchant tasks `defer` (`MerchantOperationsService.java:695-702`), rating/polish skip the round (`GoodsAutomationService.java:351-356, 586-591`).

**Enforcement.** `synchronized` methods give per-JVM atomicity; `persistState` uses atomic rename.

**Defects / risks**

1. **`RECOVERING` is dead** — the state set claims a state the system cannot enter; any client switching on it has an unreachable branch.
2. **Multi-node divergence.** `rateLimits`/`circuits` are JVM-local and only *written* to the file; `loadState` runs once at construction (`:84`). Node B never sees node A's circuit, and the last writer's whole-map snapshot erases the other node's entries (file clobbering, no merge).
3. **No clock/leeway on restart.** A restart within the 600 s window reloads the circuit (good), but a circuit created on another node is only picked up if that node wrote the file *before* this node started.
4. **Recovery is timer-only.** Nothing verifies the platform actually recovered; the first write after expiry is the probe, and if it fails the circuit re-opens for another 10 min (`:217-222` takes the max, so repeated failures extend indefinitely with no counter and no user-visible signal beyond `operation_log`).
5. **Coarse granularity.** One circuit per account blocks *all* guarded write operations, including ones unrelated to the risk trigger (`GUARDED_WRITE_APIS`, `:30-38`).
6. **Rate-limit state is never cleaned** except by `loadState` filtering at boot (`:341-343`); the in-memory map grows with `(account × operation)` forever.

---

## G1. Self-update agent (file-based state machine)

**Purpose.** Request, download, verify, install and health-check a new JAR through a systemd path unit, with rollback.

**State set.** `REQUESTED, CHECKING, DOWNLOADING, VERIFYING, INSTALLING, RESTARTING, HEALTH_CHECKING, SUCCESS, FAILED`, plus implicit `IDLE` when no file exists. The active set is hardcoded in Java: `SystemUpdateService.java:34-36`. **No enum is shared with the shell agent**; the agent writes bare strings (`xianyusmart-update-agent.sh:58-60, 83, 179, 213, 220-233`).

**Storage.** **Three files, no DB**: `status.json` (state + progress + taskId), `request.json` (existence = "a request is pending"), `installing.task` (crash marker, `:13, 222-223, 231`). Plus the JAR pair `app.jar` / `app.jar.previous`.

**Transition table**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| IDLE | `requestUpdate` (no request file, agent ready, newer version) writes **status first, then request** | REQUESTED | `SystemUpdateService.java:89-127` |
| REQUESTED | agent acquires `flock`, removes stale `installing.task` | CHECKING | agent `:166-179` |
| CHECKING | release JSON fetched + version matches | DOWNLOADING | agent `:181-209` |
| DOWNLOADING | size == total (5 attempts) | VERIFYING | agent `:71-109, 213` |
| VERIFYING | SHA256 matches | INSTALLING | agent `:214-220` |
| INSTALLING | backup written, marker written, JAR replaced | RESTARTING | agent `:221-226` |
| RESTARTING | `compose up -d` issued | HEALTH_CHECKING | agent `:226-229` |
| HEALTH_CHECKING | container healthy + `/actuator/health` UP | SUCCESS, marker removed, request removed | agent `:111-120, 230-233` |
| any active | trap cleanup with `ROLLBACK_REQUIRED=true` | FAILED (+ rollback attempt) | agent `:122-141` |
| any active + no progress ≥10 min | app-side stale timeout deletes `request.json` | FAILED ("已中断") | `SystemUpdateService.java:155-168` |

**Scheduling model.** No `@Scheduled`. Triggered by the systemd path unit on `request.json` creation (`deploy/self-update/xianyusmart-update.path:5`), serialized by `flock` (`agent:166-167`); app-side state is polled by the UI through `updateAgentStatus()`.

**Enforcement.** `flock` in the agent; `synchronized requestUpdate` in the app; atomic file replacement on both sides (`SystemUpdateService.java:208-216`, `agent:60-63`); SHA256 verification; `installing.task` enables crash rollback.

**Defects / risks**

1. **State split across three files without a protocol.** `request.json` presence is a second status variable; `installing.task` a third. The app writes status then request (`:121-122`) — a crash in between leaves `REQUESTED` with no request, which the app must *infer* as FAILED (`:165-168`).
2. **Stale timeout races the installer.** After 10 minutes without an `updatedAt` bump the app deletes `request.json` (`:157-164`). The agent's `status()` bails out when `task_is_current` fails (`agent:37-40, 54`), so the install continues with no progress reporting; the next request can then start while a JAR swap/rollback is still in flight (the agent's `flock` is the only real mutex, and it is acquired only by the agent).
3. **Un-clearable ACTIVE state.** `isStale` returns `false` when `updatedAt` is missing or unparseable (`:179-190`), so a malformed `status.json` with a request present keeps `active=true` and `requestPending=true` forever — `requestUpdate` then always returns the current status without doing anything.
4. **Status is not scoped to a task id on read.** `updateAgentStatus` returns whatever `status.json` contains (`:137-149`) without comparing `taskId` to `request.json`; a SUCCESS from task A can be displayed for task B while B is REQUESTED (two writers, last-write-wins).
5. **Single-node assumption.** `synchronized requestUpdate` (`:89`) is JVM-local; two replicas sharing the update volume can both pass the `Files.exists(request.json)` check and both write `request.json` (atomic replace, one task silently lost).
6. **Version parsing is loose.** `normalizeVersion` strips everything after `-` and non-numeric suffixes (`:232-245`), so pre-release and malformed tags silently compare as 0 — a newer build can be ignored, or a downgrade offered.
7. **No history.** Only the latest status survives; a failed attempt is unobservable after the next request.

---

## H1. Backup export/import (module partial state)

**Purpose.** Export selected modules to JSON; import them back with FK remapping.

**State set.** There is **no persisted state machine at all** — the import is a synchronous loop with per-module and per-record `try/catch`. The only state artifacts are: `version:"1.0"` in the payload (`DataBackupServiceImpl.java:46`, **written and never validated**), an `_error` marker per module on export failure (`:58`), and an in-memory `importContext` map (`:99`) used to remap account ids between modules.

**Storage.** None. `BackupImportRespBO{totalCount, successCount, failedModules}` is computed per request (`:72-131`).

**Transition table**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| — | `exportData(modules)` | per-module payload, or `{_error}` | `:41-70` |
| — | `importData` sorts modules by fixed priority account(0) / kami(1) / autoDelivery(2) / others(3) | sequential import | `:101-140` |
| per module | handler throws | module added to `failedModules`, `successCount` unchanged | `:121-124` |
| per module | handler returns | `successCount++` **regardless of how many records were skipped** | `:118-119, 125-129` |

**Scheduling model.** Request-driven, synchronous, on an HTTP thread; no lock against the running schedulers.

**Enforcement.** Only the module priority ordering and the `_`-prefixed key skip (`:105`). No transaction, no version check, no dry-run, no checksum.

**Defects / risks**

1. **Failed export imports as success.** The `_error` marker is stored *inside* the module map (`:58`), but the skip test is on the top-level key prefix (`:105`) — the handler receives `{_error: "..."}`, finds no entities, does nothing, and `successCount++` (`:119`). A broken export therefore reports a successful restore of zero rows.
2. **Per-record swallowing.** Handlers catch per record (`KamiBackupHandler.java:200-230`) and only log; the module still counts as success, so `failedModules` can be empty while the restore is 95 % incomplete.
3. **No transaction.** The import is a sequence of independent writes across modules; a crash between `account` and `autoDelivery` leaves configs pointing at ids from `importContext` that no longer exist. Cross-module references are rebuilt by natural keys (`unb`, `aliasName` — `KamiBackupHandler.java:100-160`), so renamed entities silently break references instead of failing.
4. **Live system during import.** The delivery scheduler, poller and receipt drip keep running while modules are being replaced; new orders can be created against half-restored configs (no pause flag, no advisory lock).
5. **No idempotency/version contract.** `version` is never checked, so an old export imported into a newer schema fails record-by-record and is still reported as a success.

---

## I1. Exception acknowledgement (`exception_revision` + ack table)

**Purpose.** Let operators acknowledge delivery / auto-reply / external-supply exceptions so they leave the diagnostics list.

**State set.** Derived projection: `(status ∈ {FAILED, REVIEW_REQUIRED, stale PROCESSING})` × `ack exists for (tenant, type, source_id, source_version)` — `V11:10-19`; classifications `CRITICAL/WARNING/HEALTHY` (`OperationsHealthEvaluator.java`). The only persisted "state" is `exception_revision` on three tables (`V11:1-8`).

**Storage.** DB: revision counters + `xianyu_exception_acknowledgement`. The acknowledgement is a *snapshot of a counter*, not of a state.

**Transition table**

| From | Trigger | To | Evidence |
|---|---|---|---|
| unacked exception | revision bumped by a writer | still unacked (new version) | see writer list below |
| unacked | operator `acknowledgeException` (`INSERT ... SELECT` requires revision match **and** current status) | acked | `OperationsDiagnosticsService.java:277-322` |
| acked | any writer bumps revision | **resurfaces** | by design |
| acked | state recovers without bumping | remains acked (invisible, correct) | — |

**Revision writers (inconsistent set).** Delivery: `retryOrFailTask` bumps only when the resulting status is `FAILED`/`REVIEW_REQUIRED` (`XianyuGoodsOrderMapper.java:181`); `markTaskReviewRequired` always (`:200`); `markStaleCardHeldMessagesReviewRequired` always (`:275`); `updateState*` only for `state=-1` (`:140, 145, 150`). Auto-reply: `updateStateAndContent` only for `-1` (`XianyuGoodsAutoReplyRecordMapper.java:28`). External supply: `claimRetry` **and** `markFailure` bump, `markSuccess` does **not** (`XianyuKamiExternalRequestMapper.java:41-76`).

**Scheduling model.** Computed on every diagnostics request (`OperationsDiagnosticsService.overview/exceptions`), which also calls GitHub (`checkUpdate`) and the in-memory WebSocket map when `includePlatformChecks` is true (`:131-144`) — a page load can block on two external systems.

**Enforcement.** `INSERT IGNORE` + revision equality + status re-check inside the same statement (correct, atomic).

**Defects / risks**

1. **Ack can suppress a real recurrence.** Writers that do not bump the revision (delivery requeue→fail cycles, external `markSuccess`) leave the acknowledgement valid while the entity is in a different failure episode.
2. **Ack can be invalidated without a real change.** Conversely, a bump from an unrelated write (`claimRetry` bumps on a *successful* retry start, `XianyuKamiExternalRequestMapper.java:48`) resurfaces an acknowledged row that is actually recovered.
3. **No pruning.** `xianyu_exception_acknowledgement` grows without bound; no retention job exists.
4. **Silent no-op.** `acknowledgeException` returns 0 for an already-acked or already-recovered row only in batch mode; the single-item API throws "异常不存在或状态已恢复" (`:318-320`), indistinguishable from a genuine bug to the caller.
5. **Diagnostics couples to external calls.** `includePlatformChecks` performs a GitHub HTTP call on the request thread with a 15 s timeout (`SystemUpdateService.java:65-72`), so the diagnostics endpoint is not a read-only state projection.

---

## J1. Goods automation cursors (`rate_status`, `last_polish_time`, in-memory page cursor)

**Purpose.** Drive automatic rating and "polish" (listing refresh) per goods, plus the per-account scan position for pending ratings.

**State set.**

- `XianyuGoodsConfig.xianyu_auto_rate_on ∈ {0, AUTO_RATE_ALWAYS, AUTO_RATE_AFTER_BUYER}` — `V8:1-6` (1 = "always", 2 = "after buyer" after the V8 migration).
- `rateScanStartPages: Map<accountId, int>` — **in-memory**, `GoodsAutomationService.java:55, 232, 269`.
- `last_polish_time BIGINT NULL` (`V3:50`) with a **calendar-day** due check (`GoodsAutomationService.java:633-639`).
- `rate_status ∈ {0,1,-1}` on the order (see A4).

**Storage.** Mixed: one flag set in DB, one cursor in memory, one "last run" as epoch millis, one result int that does not gate anything.

**Transition table**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| page P | scan up to 5 pages; page failure at page > 1 | cursor = failing page | `:234-267` |
| page P | page shorter than 100 | cursor = 1 | `:262-265` |
| cursor | end of loop | cursor = next page | `:266-269` |
| due | `isPolishDue` (null or older calendar day) + guard permit | polish call; on success / `already polished` → `last_polish_time=now` | `:114-118, 579-608` |
| due | guard `RATE_WAIT`/`CIRCUIT_OPEN` | unchanged (retry next minute) | `:351-356, 586-591` |
| pending | platform says can rate (+ buyer-rated when mode 2) | `rate_status=1` (success) / `-1` (fail) | `:285-306, 360` |

**Scheduling model.** Two independent `@Scheduled(fixedDelay=60000)` jobs with `initialDelay=${app.automation.initial-delay-ms:60000}` and separate `AtomicBoolean`s (`GoodsAutomationScheduler.java:29-55`); each iterates all accounts sequentially, up to 20 ratings per account with `Thread.sleep(1000)` between calls (`:673-679`) — a single account with 20 ratings occupies a scheduler thread for ≥ 20 s, and the whole pass can exceed the 60 s period.

**Enforcement.** Eligibility is recomputed from the platform every run (`:285`, `:520-528`); the persisted `rate_status` is not consulted as a guard. Polish success is inferred from an error-string match (`:641-647`).

**Defects / risks**

1. **Cursor is volatile and unbounded.** `rateScanStartPages` is lost on restart (rating restarts from page 1 — safe but wasteful) and grows one entry per account forever; in a cluster each node has its own cursor and re-scans the same pages.
2. **No attempt/backoff for failures.** A failing rating or polish is retried on the next 60 s tick with no counter and no per-item backoff; `isCredentialOrRiskFailure` breaks the whole account loop (`:302-304`) but a per-order failure just continues.
3. **Success inferred from text.** `isAlreadyPolished` matches "已经擦亮" / "一天只能擦亮一次" / `POLISH_*` (`:641-647`) — a localized message change silently turns "already done" into "failed", causing an infinite retry loop every minute.
4. **`last_polish_time` is a millis `BIGINT`** while every other timestamp is `DATETIME(3)` (`V3:50` vs the rest of the schema) — type inconsistency for anything that reads it as a timestamp.
5. **Two schedulers, two locks.** `rateEnabledGoods` and `polishEnabledGoods` can run concurrently on the two scheduler threads and both hit the same account's guard; the guard serializes writes but both loops also call `pauseBetweenRequests()` on the shared scheduler thread.
6. **`rate_status` desync.** After a manual rate (`:312-340`) the status is written via `updateRateResult` unconditionally, so `-1` from an old failure can be overwritten by `1`, and vice versa.

---

## 2. Cross-cutting findings (ranked)

### 2.1 Ways the same state is encoded twice

| Concept | Encoding A | Encoding B | Reconciled? |
|---|---|---|---|
| Order delivered | `xianyu_goods_order.state=1` | `delivery_status='COMPLETED'` | only inside mapper `CASE` (`XianyuGoodsOrderMapper.java:139-151`) |
| Delivery text sent | `delivery_message_state=1` | `content` column | implicit (`:238, 251`) — `content` is overwritten by `prepareDeliveryMessage` |
| Card committed | `xianyu_kami_item.status=1` | `xianyu_kami_usage_record.delivery_status='DELIVERED'` | only by crash recovery (`:272-280, 301-307`) |
| Human handling chat | `HumanTakeoverManager` (memory + DB) | `ManualModeServiceImpl` (memory) | no |
| Connection desired state | `manuallyStoppedAccounts` (memory) | `account.status` + `cookie_status` (DB) | no — restart resets A, DB cannot express "stopped by operator" |
| Rule "published" | `merchant_resource.status=2` | `merchant_distribution.status=1` | no — two independent writers |
| Update in flight | `status.json` status | `request.json` existence (+ `installing.task`) | inferred heuristically (`SystemUpdateService.java:157-174`) |
| Exception open | live status column | ack row for `exception_revision` | only if every writer bumps (it does not) |

### 2.2 Terminal states that can be re-entered (unguarded writes)

- `delivery_status='COMPLETED'` → `REVIEW_REQUIRED` (`XianyuGoodsOrderMapper.java:198-201`), → `FAILED` (`:138-151`), → `RETRY_WAIT` (`:187-192`), → held-timeout `REVIEW_REQUIRED` (`:272-280`).
- `merchant_task.status=2` → `-1` (`MerchantTaskMapper.java:50-52`), → `0` (`:54-55`).
- `merchant_distribution.status=1` → overwritten with a new account/goods (`MerchantDistributionMapper.java:32-37`).
- `xianyu_goods_auto_reply_record.state=1` → guarded (`:65-67` requires `IN (0,2)`), safe.
- `delivery_message_state=1` → guarded (`:267-270` requires 4), safe.
- `xianyu_notification_channel` has no terminal state at all.

### 2.3 Crash windows (two writes, no transaction)

1. Voucher written → chat message state written: two `@Transactional` units (`AutoDeliveryServiceImpl.java:499-602`); recovery depends on `delivery_message_state` 3/5 (A2.4).
2. Card committed → chat message activated (`:573-602`); recovery depends on the usage record (B1.4).
3. `updateRecordState(state=1)` → `markTaskReviewRequired` (`:528-529`): a crash between them leaves `state=1` with `delivery_status=REVIEW_REQUIRED`, or the reverse.
4. `scheduleDueRules`: insert task → `updateNextRun` (`MerchantOperationsService.java:654-656`).
5. Update agent: `status.json` → `request.json` (`SystemUpdateService.java:121-122`).
6. Backup import: module N → module N+1 with an in-memory FK map (`DataBackupServiceImpl.java:99-125`).
7. `takeover()`: memory put → DB insert (`HumanTakeoverManager.java:90-99`).
8. Notification: HTTP call → log insert → channel update (`NotificationCenterService.java:192-208`).

### 2.4 Retry counters and their caps

| Counter | Cap? | Reset by |
|---|---|---|
| `xianyu_goods_order.attempt_count` | yes (3), but decremented on risk | `requeueFailedTask` → 0 (operator retry) |
| `xianyu_goods_order.delivery_message_attempt_count` | **no cap, infinite retry** | `holdDeliveryMessage`, `prepareDeliveryMessage` |
| `receipt_follow_up_sent_count` | capped by message count; no failure counter | never |
| `xianyu_goods_auto_reply_record.attempt_count` | **no cap** | — |
| `xianyu_kami_external_request.attempt_count` | 3 (hard, then permanent FAILED) | — |
| `merchant_task.attempt_count` | `max_attempts` per type, **reset to 0 by `requeue`**, decremented by `defer` | operator / compensation |
| rating / polish | **no counter** | — |
| notification | **no retry at all** | — |

### 2.5 Schedulers that assume a single node

`DeliveryTaskScheduler` (the lease is DB-based and mostly correct, but the `FOR UPDATE` claim and the "claim conflict" exception assume low contention), `GoodsAutomationScheduler` (4 `AtomicBoolean`s + in-memory page cursor), `MerchantOperationsScheduler` (`AtomicBoolean`; rule creation is not idempotent), `WebSocketRecoveryScheduler` (`connectingAccounts`, `manuallyStoppedAccounts`), `AutoReplyDelayServiceImpl` (`pendingTasks`, `pendingMessages`), `RiskControlServiceImpl` (whole-file overwrite), `SystemUpdateService` (`synchronized` + file existence), `KamiConfigServiceImpl.stockOutEmailSentTime` (in-memory alert debounce, `:72, 451-457`), `CookieRefreshServiceImpl.lastBrowserRefreshTime` (in-memory cooldown, `:461`), `HumanTakeoverManager`, `ManualModeServiceImpl`.

### 2.6 Scheduling-model gaps

- **Nothing records last-run time or failure** for any `@Scheduled` job; `fixedDelay` silently skips ticks, and a hung job is invisible.
- **Two threads for 21 jobs**: the 60 s merchant tick, the 1 s delivery tick, the 1 s reply recovery tick and the 5 s receipt tick compete; a slow publish (~minutes) blocks all of them.
- **No watchdog** for `PROCESSING` delivery with a held message (A1.4), for stale `merchant_task.status=1` beyond the 10-minute heuristic, for `REVIEW_REQUIRED` external supply (B2.1), for notification failures (D1.3), or for `account.status=-2` accounts (F3).
- **No cleanup jobs** for `-2` reply records, acknowledgement rows, notification logs, `rateLimits` map entries, or `delivery_message_attempt_count`.

### 2.7 States written but never read (verified)

- `DeliveryStatus.DELIVERED`, `DeliveryStatus.CONFIRMING` — never written at all, only rendered by the UI.
- `xianyu_account.status = -1` — documented, never written.
- `xianyu_goods_order.delivery_channel` — written once, never read.
- `xianyu_notification_log.http_status` — written, never read.
- `xianyu_goods_order.rate_status` — written and displayed, never used for control flow.
- `xianyu_goods_auto_reply_record.next_retry_time` — read in `findDue`, never written (E1.1).
- `xianyu_goods_auto_reply_record.attempt_count` — incremented, never read.
- `xianyu_kami_usage_record.delivery_status` — written `'DELIVERED'`, read only inside two delivery-recovery queries.
- Backup payload `"version"` — written, never validated.
- `GuardState.RECOVERING` — declared, never produced.
- `delivery_message_state = 5` — one query reads it (`XianyuGoodsOrderMapper.java:305`); no UI, no API, no documentation.

---

## 3. Suggested formalization order (highest value first)

1. **A1 / A2** — give `xianyu_goods_order` a single delivery state (fold `state` in), add guards to every `UPDATE` (`WHERE delivery_status IN (...)`), and replace the message-queue `state=2` timer with an owner + expiry pair (`message_lease_owner`, `message_lease_expire`) checked in `markDeliveryMessageSent`.
2. **C1** — add `lease_owner` / `lease_expire_time` to `merchant_task` and guard `complete` / `fail` / `defer` on them; stop resetting `attempt_count` in `requeue`.
3. **E1** — write `next_retry_time` and read `attempt_count`; make cancel affect state 2; add a terminal `-3` for exhausted.
4. **B2** — allow `REVIEW_REQUIRED → PROCESSING` via an explicit operator action, or escalate to `FAILED` after a timeout; stop excluding `FAILED` from `countUnsettled`.
5. **G1** — collapse the three files into one `state.json` with one writer, validate `taskId` on read, and use a heartbeat the stale check can trust.
6. **F4 / F1 / E2** — persist cluster-shared state (risk guard in DB or via lease, connection desired-state in DB, takeover in a single mechanism).
7. **I1** — bump `exception_revision` in exactly one place (a `BEFORE UPDATE` trigger on the three tables) so acknowledgements are reliable by construction.

---

*End of report. 20 implicit state machines documented; no production source files were modified by this audit.*
