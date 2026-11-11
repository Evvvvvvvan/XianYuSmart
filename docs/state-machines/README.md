# Implicit state machines in `xianyuSmart`

Audit of `v2.1.1` — 403 Java sources, 188 Vue sources, 18 Flyway migrations, 14 `@Scheduled` jobs, 3,500+ lines of mapper SQL.

**There is no state-machine framework here** — no `spring-statemachine`, no `squirrel`, no hand-rolled `State`/`Transition` type. Every lifecycle in the app is encoded as a `TINYINT`/`VARCHAR` column with magic values, a `boolean` flag, a `ConcurrentHashMap` entry, a `ScheduledFuture`, or a timestamp compared against `now()`. This document is the catalogue of those lifecycles: what states they really have, which transitions actually exist, who drives them, what guards them, and where they break.

Every claim carries `file:line`. States that are declared or displayed but never produced are separated into [§9](#9-ghost-states-declared-displayed-never-produced).

## Deliverable map

| File | Contents |
|---|---|
| **this README** | master catalogue, cross-cutting analysis, remediation plan |
| [`catalog.yaml`](catalog.yaml) | machine-readable: **69 machines, 167 transitions, 85 defects** as `id / states / transitions{from,to,trigger,guard,driver,evidence} / defects`, usable for a diagram or test generator |
| [`_audit/auth-session.md`](_audit/auth-session.md) | 905 lines — 13 machines: captcha task/phase/drag-retry, token wait flags, QR session, cookie & account credentials, login lockout |
| [`_audit/messaging-delivery.md`](_audit/messaging-delivery.md) | 741 lines — 12 machines: reply record, delayed task, takeover (both), buyer gate, delivery task, delivery message, kami, post-delivery flags, risk guard, synthesized outbound messages, event fan-out phase order |
| [`_audit/schedulers-recovery.md`](_audit/schedulers-recovery.md) | 910 lines — 20 machines: delivery, receipt drip, kami (local+external), merchant task/resource/distribution, notifications, replies, takeover, connection, cursor, credentials, risk guard, self-update, backup, diagnostics ack, automation cursors |
| [`_audit/remaining-services.md`](_audit/remaining-services.md) | 1372 lines — 17 machines: item sync, goods status, config flag matrix, keyword rules, SKU snapshot, publish (+local-orphan), merchant task, kami, external kami, exception ack, platform users/permissions, settings→AI rebuild, buyer gate, delivery-mode exclusivity, rating pool, QR session; plus a 32-endpoint lifecycle table and a 27-row mapper guard inventory |
| [`_audit/frontend.md`](_audit/frontend.md) | 998 lines — 24 machines: auth/route guard, login mode, connection composite + 4 pollers, QR pollers ×3, captcha client, update dialog, goods sync, pull-to-refresh, 2 wizards, auto-delivery editor, reply toggles, orders actions, pending-orders loops, message polling, context dialog, kami dialogs, settings, uploads, operations lock, buyer detail, price comparison, admin editor |

Roughly **90 machine entries** are documented across the five audit files; they collapse to **69 distinct machines** because several lifecycles were audited from two angles (delivery, kami, takeover, connection and QR each appear twice). The tables below list **72 rows** — three frontend presentation-only mappings are folded into their owning machine in `catalog.yaml`. Every row's `Detail` column points at either a section here or a machine id in a `_audit/` file.

---

## 0. Fix status (2026-11-11)

The findings below were produced before the hardening commit. What changed since:

**Fixed in code**

| Area | Fix |
|---|---|
| Delivery task (ORD-1) | `completeTask` / `retryOrFailTask` now require `lease_owner`; `markTaskReviewRequired` cannot demote `COMPLETED`; `deferForRisk` only applies to `PENDING` / `PROCESSING` / `RETRY_WAIT` |
| Delivery message (ORD-2) | `markDeliveryMessageSent` requires `delivery_message_state = 2` |
| Auto-reply (MSG-1) | Success/failure split into guarded statements (`state = 2` + lease owner); terminal states are no longer overwritten; lost lease is logged |
| Delayed reply (MSG-2) | Dedupe by `(account, sId, pnmId)` before cancelling, so replayed messages cannot drop a pending reply |
| Merchant task (OPS-1) | Migration `V19` adds `lease_owner` / `lease_expire_time`; `claim` writes, `complete` / `fail` verify |
| Kami (ORD-4/5) | `releaseReservation` no longer filters `source_type`, so externally provisioned reservations are releasable; retry no longer bumps `exception_revision` |
| Cookie (WS-6) | A `500` frame no longer marks the cookie expired (only 401 / explicit expiry) |
| Connection (WS-1/4/5) | `stop` shares the per-account lock with `start`; restart flag cleared on successful refresh; manual restart resets backoff, auto-reconnect does not |
| Login lockout (WS-11) | Counter is no longer overwritten by `put`, so 5 failures actually lock |
| Account status (WS-10) | `-2` is the documented and rendered value; `-1` kept for legacy rows |
| Receipt drip (ORD-9) | Lost optimistic update skips the round instead of sending twice |
| Goods sync (OPS-8) | Incomplete pagination no longer marks unfetched goods as deleted |
| Frontend (FE-1…FE-24) | Pending-order delivery single-flight; QR poll stops before handling; goods-sync poll resets on error; wizard `maxStep` cannot regress; message `loading`/`silentLoading` separated; stale connection responses dropped; upload progress timer cleared on failure; delivery-config save no longer forces auto-confirm; reply toggles roll back to the previous value |

**Deliberately not changed (needs a product decision, not a patch)**

- `XianyuWebSocketClient:866-868` still returns `true` on send timeout. The correct fix is a third outcome (`SENT_UNCONFIRMED`) that suppresses both retry and the `DeliveryMessageSentEvent` auto-confirm; a boolean cannot express it, and flipping it to `false` would resend the card message.
- `prepareDeliveryMessage` / `activateDeliveryMessage` still write `state=1` + `COMPLETED` before the chat message is confirmed sent. Moving that write changes dashboard semantics and the recovery path, so it stays until the state split above lands.
- Cluster-wide state (risk circuit, takeover cache, WebSocket maps, goods page cursor, scheduler guards) is still per-JVM; single-replica deployments are unaffected, multi-replica ones need a shared lease store.
- `ManualModeService`, `EnhancedMessageSendService`, `OrderStatusEnum` are still present as dead code (removal is safe but out of scope for a bug-fix release).

Regression coverage added with the fixes: `StateMachineGuardContractTest` (reflects over mapper SQL and asserts the guards exist), `AuthServiceImplLockoutTest`, `LocalMapCacheServiceImplTest`.

## 1. What was counted as a state machine

A lifecycle qualified if at least one holds:

1. **Finite typed domain with divergent behaviour** — the value is read back and changes what happens next (not a config switch).
2. **Real topology** — not just `0→1`: `A→B→C`, `A→B` *and* `A→C`, or a cycle (`A→B→A`).
3. **Multiple writers** — request thread, scheduler, WebSocket thread, timer. Concurrency is part of the machine.
4. **Guarded writes** — `WHERE status IN (...)` / `if (!"x".equals(state))`. A guard is the author's mental state model, escaped into SQL.

Pure configuration toggles (`voucher_delivery_enabled`, `alert_enabled`, …) were not counted; they are listed under `configurations:` in the catalog. A `status` column written but never read was still counted, because it is a *declared* state machine — those are the ghosts in §9.

---

## 2. Headline findings

1. **One order row hosts six independent state machines** — `state`, `delivery_status`, `delivery_message_state`, `confirm_state`, `rate_status`, `receipt_follow_up_*`. Nothing enforces consistency between them, and several writers touch exactly one field. The same row is therefore in six states at once, and they can contradict each other by design (§4).
2. **A WebSocket send timeout is recorded as success** — `future.get(10s)` times out ⇒ `return true` (`XianyuWebSocketClient:866-868`). This value is what `markDeliveryMessageSent` trusts, so "sent but unknown" is persisted as "sent"; the resulting `DeliveryMessageSentEvent` is exactly what triggers `executeAutoConfirmShipment` (`AutoDeliveryServiceImpl:689-710`), i.e. **a lost card message can auto-confirm the platform order** while the buyer has received nothing.
3. **Leases exist but are not fenced** — `completeTask`, `retryOrFailTask`, `updateStateAndContent` (reply), `MerchantTaskMapper.complete/fail` all match on `id` alone while `claim` writes `lease_owner`. A worker whose lease expired can overwrite the worker that took over (§4.2, §5.1, §6).
4. **Several terminal states are re-enterable.** `markTaskReviewRequired`, `deferForRisk`, `updateState*` carry no status guard, so a `COMPLETED` order can be demoted to `REVIEW_REQUIRED`/`FAILED`/`RETRY_WAIT` by a second writer in the same pipeline (`XianyuGoodsOrderMapper:138-151,187-201`). `merchant_task`'s `complete`/`fail`/`requeue` are equally unguarded, so a succeeded PUBLISH can be requeued and published again (`MerchantTaskMapper:47-55`).
5. **`delivery_message_state=2` is a 2-minute timer with no owner token**, and `markDeliveryMessageSent` is unguarded (`XianyuGoodsOrderMapper:282-289`). A slow send lets a second node send the same private message — the one carrying the card secret — twice.
6. **Two `delivery_status` vocabularies under one column name.** On orders it is `DeliveryStatus`; on `xianyu_kami_usage_record` it defaults to `'RESERVED'`, which is a `KamiStatus` name absent from `DeliveryStatus`, while every cross-table check hardcodes `= 'DELIVERED'` (`V1:310`; `XianyuGoodsOrderMapper:277,302`).
7. **`xianyu_account.status` has three incompatible definitions.** Javadoc says `-1 = 需要手机号验证`; the UI maps `-1 → 需要验证`; the code writes **`-2`** (`WebSocketTokenServiceImpl:826`, `CookieRefreshServiceImpl:556`). `-2` has no UI branch and is excluded from reconnect, keepalive and token-refresh queries — a needs-verification account becomes invisible and stays that way until a human intervenes.
8. **A transient platform `500` expires the cookie** (`WebSocketMessageRouter:168-175`), and every credential read filters `cookie_status = 1`. One bad response closes every API path for that account until `hasLogin` heals it.
9. **`RESERVED` is a trap for API-sourced card warehouses.** `releaseReservation` only matches `config.source_type='LOCAL'` (`XianyuKamiItemMapper:84-87`), so externally provisioned reservations are never released on any cancel path, and `hasUnsettledSupply` then permanently blocks editing/deleting the warehouse.
10. **`REVIEW_REQUIRED` is a terminal-by-neglect state in two machines**: external kami requests have no code-reachable exit (`claimRetry` excludes it → permanent 409 for that order), and exception acknowledgements only *hide* rows — revision bumps are inconsistent, and ack rows are never pruned.
11. **All scheduling runs on two threads.** `scheduler.setPoolSize(2)` (`AsyncConfig:137`) for 14 `@Scheduled` jobs; business work is handed to `taskExecutor` (4/8/500, `CallerRunsPolicy`), so an overflow runs the job *on the scheduler thread* and stalls everything else. Notifications go to an executor with `DiscardPolicy` (`AsyncConfig:99`) — overload drops notifications with no log and no state.
12. **Almost all lifecycle state is per-JVM**: connection maps, reconnect counters, risk circuit, takeover cache, reply delay tasks, goods-sync cursor, goods page cursor, captcha tasks, QR sessions, stock-alert throttle, `manuallyStoppedAccounts`, and the self-update `synchronized`. Every one of them assumes a single node.
13. **One state machine is dead code** — `ManualModeService`/`ManualModeServiceImpl` (manual takeover) has zero callers and no controller, yet still runs a `@Scheduled` cleanup beside the *other* takeover implementation that is actually used. Same for `EnhancedMessageSendService` (its retry ladder) and `OrderStatusEnum` (zero references).
14. **Frontend: three P0 loops.** The pending-orders auto-deliver interval calls `handleDeliver()` with no re-entrancy guard (overlapping bulk delivery on real orders); the QR dialog's `confirmed` branch awaits the login handler without stopping its 2 s poll (duplicate account creation); and the goods-sync poller leaves `syncing=true` forever on one error, permanently disabling refresh.

---

## 3. Inventory

**Driver** = who moves the state. **Guard** = what the code actually enforces. Severity reflects consequences, not code volume.

### 3.1 Connection & credentials

| ID | Machine | Store | States | Driver | Guard | Sev | Detail |
|---|---|---|---|---|---|---|---|
| WS-1 | Account connection lifecycle | 12 maps in `WebSocketServiceImpl:78-125` | stopped/connecting/connected/stale/reconnect-wait/token-expired/captcha-wait | HTTP, timer, WS callbacks | per-account lock on start only | **high** | §3.1a |
| WS-2 | Socket + handshake | `XianyuWebSocketClient:32,[49]` | closed/open/registering/registered | WS thread | none | med | §3.1a |
| WS-3 | Heartbeat liveness | `lastHeartbeatResponseTimes` | fresh/stale/lost | 1 s timer | timestamp compare | med | §3.1a |
| WS-4 | Token refresh cycle | `lastTokenRefreshTimes`, `captchaRetryCounts` | fresh/refreshing/retry-wait/captcha-backoff | 60 s timer | backoff cap | med | §3.1a |
| WS-5 | Reconnect backoff | `reconnectAttemptCounts/Tasks` | idle/scheduled/executing | timer | check-then-put | med | §3.1a |
| WS-6 | Cookie validity | `xianyu_cookie.cookie_status` | 1 valid / 2 expired / 3 invalid | API, WS router | **none** | **high** | §3.1a |
| WS-7 | QR login session | `QRLoginServiceImpl.sessions:41` | waiting/scanned/success/verification_required/expired/cancelled/error | 2 s poll | lazy expiry | med | §3.1a, auth M6 |
| WS-8 | Captcha task | `CaptchaSolveServiceImpl.tasks:46` | 7 `Status` × 21 `phase` × `attempt` | executor, browser | enum + deadline | med | auth M1–M4 |
| WS-9 | Sync watermark | `websocket_sync_pts` | monotonic pts | WS thread | SQL `pts < ?` ✅ | low | §3.1a |
| WS-10 | Account status | `xianyu_account.status` | 1 normal / -2 written / -1 documented | credential refresh | heal-only from -2 | **high** | §3.1a |
| WS-11 | Login lockout | cache counters | unlocked / locked (locked unreachable) | HTTP | counter (broken by type mismatch) | med | §3.1a |


### 3.1a Notes on the connection machines

The real state set of WS-1 exists only as predicates over twelve maps (there is no enum anywhere):

```
STOPPED_MANUAL  = manuallyStoppedAccounts.contains(id)
CONNECTING      = holding connectionLocks[id]
CONNECTED       = clients.containsKey(id) && isConnectionHealthy(id)
STALE_CONNECTED = clients.containsKey(id) && !isConnectionHealthy(id)
RECONNECT_WAIT  = reconnectTasks[id] not done
TOKEN_EXPIRED   = token cleared, 401 callback fired
CAPTCHA_WAIT    = captchaRetryCounts[id] > 0 && tokenRetryTasks[id] pending
```

- **WS-1 teardown race.** Only `startWebSocketInternal` takes `connectionLocks`; `stopWebSocketInternal` does not (`WebSocketServiceImpl:458`). The heartbeat thread calls it after detecting staleness (`:803-806`), so it can `remove(accountId)` a client that another thread has *just* published and then close the object it removed — the live socket survives unreferenced, with its heartbeat task cancelled.
- **WS-2 two truths.** A non-volatile `isConnected` field (`:32`) and an `isOpen()/isClosed()` method (`:570-573`) both exist; `sendMessage`/`sendHeartbeat` read the field, `sendMessageWithResult`/`isConnectionHealthy` read the method. The handshake has no state variable at all — `onOpen → /reg → sleep(1000) → /r/SyncStatus/ackDiff` is sequenced by `Thread.sleep` (`WebSocketInitializer:154-165`), and success is inferred from `reg-sid` appearing in a later frame.
- **WS-3 false health.** `isConnectionHealthy` returns `true` when no heartbeat response was ever recorded (`:532-540`).
- **WS-4 stale restart flag.** The success path never clears `connectionRestartFlags`, which is only reset by `scheduleReconnect(..., isManualRestart=true)`. The next natural disconnect is therefore read as a "manual restart" and reconnects with **zero delay** (`:679`, `:357-361`, `:830-832`).
- **WS-6 one signal closes everything.** `code == 500` in the WebSocket router writes `cookie_status = 2` (`WebSocketMessageRouter:168-175`); every credential read filters `cookie_status = 1` (`AccountServiceImpl:190-194,260-264,326-330`) and `selectReconnectableAccounts` requires it too, so a transient platform error removes the account from all API paths, keepalive and reconnection at once. The expiry notification is an untransacted read-modify-write edge detection (`AccountServiceImpl:412-449`).
- **WS-7 vocabulary drift.** Eight backend strings map to four frontend ones; `error` falls into the default branch and becomes `pending` (`QRLoginServiceImpl:604-620`), so a failed login looks like "waiting". `cleanupExpiredSessions` has no scheduler, so sessions (holding plaintext cookies) live for the process lifetime.
- **WS-8 three axes.** Seven `Status` values, 21 free-form `phase` strings and an `attempt` counter; `UNSUPPORTED` has no producer, and the phase pipeline is non-monotonic (`STARTING_BROWSER` is published before `CHECKING_ENVIRONMENT`).
- **WS-9 the one good machine.** Monotonic in memory (`WebSocketSyncCursor:16-29`) *and* in SQL (`XianyuAccountMapper:24-28`) — the only guarded state transition in the credential domain.
- **WS-10 absorbing `-2`.** Written by `WebSocketTokenServiceImpl:826` and `CookieRefreshServiceImpl:556`, documented and rendered as `-1`; excluded from reconnect/keepalive/token-refresh queries; only `updateAccountStatusToNormal` (and only from exactly `-2`) heals it.
- **WS-11 lockout never engages.** `recordLoginFailure` stores the counter with `put(key, increment(key), ttl)` (`AuthServiceImpl:211`). `increment` creates an `AtomicLong` entry and returns `1`; the `put` then overwrites it with a plain `Long`, so the next `increment` hits the type-mismatch branch (`LocalMapCacheServiceImpl:86-89`) and resets to `1` on every failure. The counter never exceeds 1 and `isLoginLocked` compares against 5 — the `locked` state is unreachable.

### 3.2 Order fulfilment

| ID | Machine | Store | States | Driver | Guard | Sev | Detail |
|---|---|---|---|---|---|---|---|
| ORD-1 | Delivery task (`delivery_status`) + lease | `xianyu_goods_order` | PENDING/PROCESSING/RETRY_WAIT/FAILED/REVIEW_REQUIRED/COMPLETED/SKIPPED (+2 unreachable) | 1 s scheduler | status guards, **no fencing** | **high** | §4.2 |
| ORD-2 | Delivery private message | same row | 0 ready / 1 sent / 2 in-flight / 3 card-held / 4 fixed-held / 5 committed | event + 15 s timer | strong SQL guards ✅, no owner on 2 | **high** | §4.3 |
| ORD-3 | Fulfilment saga | 5 booleans in `OrderServiceImpl:730-845` | 32 combos, ~6 handled | request thread | `catch` compensation | **high** | §4.4 |
| ORD-4 | Kami item | `xianyu_kami_item.status` | 0 available / 2 reserved / 1 delivered / 3 review | delivery flow | guarded updates ✅ | med | §4.5 |
| ORD-5 | External kami request | `request_status` | PROCESSING/SUCCESS/FAILED/REVIEW_REQUIRED | delivery + 2 min reclaim | **full CAS ✅** | med | §4.5 |
| ORD-6 | Order `state` | `xianyu_goods_order.state` | -1/0/1 | delivery flow | none | **high** | §4.1 |
| ORD-7 | `confirm_state` | same row | 0/1 | 3 writers | **none** | med | §4.6 |
| ORD-8 | `rate_status` | same row | 0/1/-1 | rate scheduler | none | med | §4.6 |
| ORD-9 | Receipt follow-up | `receipt_follow_up_*` | counters + completed | 5 s timer | optimistic count | med | §4.6 |
| ORD-10 | Exception revision + ack | `exception_revision` + ack table | open(vN) / acked(vN) | failure writes, operator | version match ✅ (inconsistent bumps) | med | §4.6 |
| ORD-11 | Platform order mirror | `xianyu_order.order_status` | 1..5 | sync | overwrite | low | §4.6 |

### 3.3 Messaging & auto-reply

| ID | Machine | Store | States | Driver | Guard | Sev | Detail |
|---|---|---|---|---|---|---|---|
| MSG-1 | Auto-reply record | `xianyu_goods_auto_reply_record.state` | 0 pending / 2 claimed / 1 ok / -1 failed / -2 cancelled | WS thread, 1 s timer | claim ✅, **no fencing** | **high** | §5.1 |
| MSG-2 | Delayed reply task | memory + durable row | scheduled/dispatched/cancelled | pool, recovery sweep | `claim()` | med | §5.2 |
| MSG-3 | Human takeover (live) | DB record + cache | active/expired | seller reply | DB + lazy expiry | med | §5.3 |
| MSG-4 | Manual mode (**dead**) | memory | auto/manual | nobody | none | dead | §5.3 |
| MSG-5 | Risk guard | memory + JSON file | NORMAL/RATE_WAIT/CIRCUIT_OPEN/(RECOVERING unreachable) | API wrapper | enum ✅ | med | §5.4 |
| MSG-6 | Buyer automation gate | `automation_blocked` | allowed/blocked | operator | two checkpoints | low | §5.5 |
| MSG-7 | Send retry ladder (**dead**) | stack | SUCCESS/NOT_CONNECTED/RETRY_EXHAUSTED | nobody | cap | dead | §5.6 |
| MSG-8 | Synthesised outbound records | `content_type` 888/887/997/999 + synthetic `pnm_id` | local copy of a sent message | reply/delivery paths | none | med | messaging SM-11 |
| MSG-9 | Event fan-out order | `@Order`/`@Async` annotations | implicit phase order | Spring events | annotation only | med | messaging SM-12 |

### 3.4 Automation, operations, platform

| ID | Machine | Store | States | Driver | Guard | Sev |
|---|---|---|---|---|---|---|
| OPS-1 | Merchant task | `merchant_task.status` | 0/1/2/-1 | 60 s timer, HTTP | claim ✅, **complete/fail/requeue unguarded** | **high** |
| OPS-2 | Merchant resource rule | `merchant_resource.status` + `scheduled_time`/`last_run_time` | 0/1/2 (2 = one-way trap) | 60 s timer | none | med |
| OPS-3 | Distribution + settlement | `status`, `settlement_status` | 0/1 × 0/1 | HTTP | settle CAS ✅, publish unguarded | low |
| OPS-4 | Notification delivery | `send_status` + channel health | 0/1 | event | none; `DiscardPolicy` | med |
| OPS-5 | Scheduler single-flight | 4 `AtomicBoolean` (+2 more) | idle/running | scheduler | CAS ✅ (per-JVM) | low |
| OPS-6 | Daily polish gate | `last_polish_time` | due/not-due | 60 s timer | calendar-date compare | low |
| OPS-7 | Item detail sync | `SyncProgress` (3 booleans) | running/completed/cancelled (unreachable) | `@Async` (broken) | contradictory flags | med |
| OPS-8 | Goods status | `xianyu_goods_info.status` | 0/1/2/-1/-9 (1,2 unwritten) | sync, operations | none | **high** |
| OPS-9 | Self-update | `status.json` + `request.json` + `installing.task` | 10 states + IDLE | external shell agent | file presence + 10 min staleness | med |
| OPS-10 | Backup import | none (request-scoped) | per-module ok/fail | HTTP | none; failed export imports as success | med |
| OPS-11 | AI settings → runtime rebuild | setting keys whitelist | rebuild / stale runtime | HTTP | hardcoded key set | med |
| OPS-12 | Platform user / permission | `sys_user.status`, permission rows | active / default-catalog | registration, admin | `defaultCodes()` = full catalog | **high** |
| OPS-13 | Keyword reply rule | `match_mode` | 1 keyword / 2 fallback / *unvalidated* | HTTP | none | med |
| OPS-14 | SKU snapshot | delete-then-insert "epoch" | present/absent | sync | transaction | low |
| OPS-15 | Rating content pool | `rate_source` AUTO/MANUAL/PLATFORM | pool order | scheduler | none | low |
| OPS-16 | Kami stock alert throttle | `stockOutEmailSentTime` | armed/sent | scheduler | timestamp | low |
| OPS-17 | Login token | `sys_login_token.expire_time` (string) | valid/expired | HTTP | lexical compare | low |

### 3.5 Frontend

| ID | Machine | Store | States | Sev |
|---|---|---|---|---|
| FE-1 | Auth session + route guard | `utils/permission.ts:4` + `router/index.ts:178` + `localStorage` | unknown/loading/loaded/unauth | **high** |
| FE-2 | Login view mode | `login/index.vue:8` | checking/login/register | low |
| FE-3 | Connection snapshot | `useConnectionManager.ts:31` | connected + cookie 1/2/3 + guard 4 | **high** |
| FE-4 | Connection pollers ×4 (1 dead) | 10 s / 5 s intervals | polling/terminal(none) | **high** |
| FE-5 | QR pollers ×3 (1 dead) | 2 s intervals | pending/scanned/confirmed/expired | **high** |
| FE-6 | Captcha client | `CaptchaGuideDialog.vue:65` | 7 server statuses + 21 phases | med |
| FE-7 | Update dialog | `UpdateDialog.vue:22` | 10 statuses | med |
| FE-8 | Goods sync progress | `useGoodsManager.ts:48-50` | idle/refreshing/syncing/stuck | **high** |
| FE-9 | Pull-to-refresh gesture | `goods/index.vue:224` | idle/pulling/ready/refreshing | med |
| FE-10 | Publish wizard | `product-publish/index.vue:12-13` | step 1..4 + maxStep | **high** |
| FE-11 | Opportunities wizard | `opportunities/index.vue:21-22` | step 1..4 + maxStep (regresses) | med |
| FE-12 | Auto-delivery config editor | `useAutoDelivery.ts:78-134` | clean/dirty/saving/error | med |
| FE-13 | Reply toggle cluster | `useAutoReply.ts` | off/on/unset ×5 | **high** |
| FE-14 | Delivery status presentation | `orders/order-status.ts` | 9 statuses → 3 conflicting mappers | med |
| FE-15 | Pending-orders loops | `pending-orders/index.vue:15-23` | manual/auto-refresh/auto-deliver | **high** |
| FE-16 | Message list polling | `useMessageManager.ts:11-12` | loading/silent | med |
| FE-17 | Context dialog paging vs poll | `ContextDialog.vue:30-38` | loading/loadingMore/hasMore | **high** |
| FE-18 | Kami dialog cluster | `kami-config/index.vue:38-69` | 5 independent booleans | med |
| FE-19 | Settings tab + fake progress | `settings/index.vue:34` | 7 panels × 20 flags | med |
| FE-20 | Image upload | `ImageUploader.vue:26` | uploading/progress | med (timer leak) |
| FE-21 | Operations action lock | `operations/index.vue:42` | idle/busy(global) | low |
| FE-22 | Buyer detail/rating | `buyers/index.vue:30-37` | drawer tab + per-order rating | med |
| FE-23 | Price-comparison profiles | `price-comparison/index.vue:24-26` | loading/error/loaded per key | low |
| FE-24 | Admin-user editor | `admin-users/index.vue:19` | `undefined`=closed / `null`=create / user=edit | med |

---

## 4. Order fulfilment: one row, six machines

### 4.1 The state vector

| Column | Domain | Writer(s) | Reader(s) |
|---|---|---|---|
| `state` | -1/0/1 | `AutoDeliveryServiceImpl:736-742`, `OrderServiceImpl:817`, `prepareDeliveryMessage` | `DeliveryTaskScheduler:114-117`, dashboards, UI |
| `delivery_status` | 9 names (7 reachable) | `DeliveryTaskServiceImpl`, `PendingOrderPollService:78`, listener | scheduler, filters, UI |
| `delivery_message_state` | 0..5 | `BuyerMessageService`, mapper | `lockDueTasks`, 15 s retry |
| `confirm_state` | 0/1 | 3 writers | FE `canConfirmShipment` |
| `rate_status` | 0/1/-1 | `GoodsAutomationService:329,360` | UI only (never controls flow) |
| `receipt_follow_up_*` | counters + bit | 5 s timer | same |

Contradictions produced by the write paths themselves:

- `state=1` with `delivery_status='PROCESSING'` — `updateStateAndContent` rewrites `delivery_status` only when `state=1` (`XianyuGoodsOrderMapper:138-151`).
- `confirm_state=1` while `delivery_message_state=3` — `updateConfirmState` is unconditional (`:222-223`).
- `delivery_status='COMPLETED'` while the chat message is still queued — `prepareDeliveryMessage` sets `COMPLETED` **before** sending (`:236-241`).
- `rate_status=1` on a closed order — `updateRateResult` has no guard (`:229-234`).

### 4.2 ORD-1 `delivery_status` (SQL is the spec)

```sql
lockDueTasks   (PENDING|RETRY_WAIT due) OR (PROCESSING lease expired)
               AND delivery_message_state NOT IN (3,4,5)          -- held messages freeze the task
claimTasks     id IN (…) → PROCESSING, lease_owner, +120s, attempt+1
completeTask   id → COMPLETED                                        -- NO guard, NO owner check
retryOrFail    status NOT IN (REVIEW_REQUIRED, COMPLETED) → RETRY_WAIT|FAILED
deferForRisk   id → RETRY_WAIT, attempt_count-1, retryAt
requeueTask    state<>1 AND status IN (FAILED, RETRY_WAIT) → PENDING
```

Defects: (1) **no fencing** — `completeTask`/`retryOrFailTask` ignore `lease_owner`; (2) **terminal states re-entrant** — `markTaskReviewRequired`, `deferForRisk`, `updateState*` can demote `COMPLETED`; (3) `attempt_count` incremented in both SQL and Java (`:170`, `DeliveryTaskServiceImpl:70`); (4) `DELIVERED`/`CONFIRMING` never written yet rendered by the UI; (5) `SKIPPED` is absorbing (`requeue*` exclude it), so enabling auto-delivery later never resurrects the order; (6) no `SKIP LOCKED`.

### 4.3 ORD-2 `delivery_message_state` — the best guards, the worst coupling

Guards are real and numerous: `claimDeliveryMessage` requires `state IN (0,2)` **and** due; `cancelStaleFixedHeldDeliveryMessages` touches only 4; `markStaleCardHeldMessagesReviewRequired` promotes only 3 and only when no `DELIVERED` usage row exists; `selectCommittedHeldDeliveryMessages` re-arms stuck `5` rows.

The risk is not a missing guard, it is what the sending path writes:

```
prepareDeliveryMessage   → delivery_message_state=0, state=1, delivery_status='COMPLETED',
                           delivered_quantity=expected_quantity                      (before send!)
activateDeliveryMessage  → delivery_message_state=0, state=1, delivery_status='COMPLETED'
```

so "voucher accepted" is persisted as "order delivered", and the private message becomes the *last* writer of the order's terminal state. Combined with **timeout-means-success** at the socket layer (`XianyuWebSocketClient:866-868`), an unknown send outcome is recorded as `state=1 / COMPLETED / sent`, and the event it publishes can then auto-confirm the platform order.

The two delivery machines are also coupled through one filter: `lockDueTasks` excludes `delivery_message_state IN (3,4,5)` (`XianyuGoodsOrderMapper:165`), so a stuck private message silently freezes voucher retry, failure handling and review for that order — invisibly, because the dashboards read `delivery_status`, which never changes.

Other cracks: `state=2` is a timer without an owner token (duplicate card message possible); `delivery_message_attempt_count` has no cap and the state set has no terminal failure, so undeliverable messages retry every 5 minutes forever; state `5` is invisible to the API and UI and its only rescue path is the 15 s retry job.

### 4.4 ORD-3 the saga (5 booleans ≈ 32 states, ~6 handled)

```
reserve kami → consign → ok        → commit → state=1 → queue/hold chat message
                       → uncertain → kami review, cancel held msg, state=-1, REVIEW_REQUIRED
                       → failed    → release kami, cancel held msg, return null
catch → compensate according to (cardDelivery, cardReservationCommitted, deliveryMessageHeld,
                                 chatDeliveryEnabled, voucherDeliveryEnabled)
```

Five independent booleans, no assertion about the combinations the code does not handle. Modelled as `fulfilment_step ∈ {NONE, KAMI_RESERVED, CONSIGNED, MESSAGE_HELD, COMMITTED}` + `compensation_pending`, the same guarantees would be crash-safe instead of `catch`-safe.

### 4.5 ORD-4/5 kami — one trap, one exemplar

`KamiStatus` (`0 available / 1 delivered / 2 reserved / 3 review`, documented in `V1:289`).

- **Trap**: `releaseReservation` joins `config.source_type='LOCAL'` (`XianyuKamiItemMapper:84-87`), so reservations created by `ExternalKamiProvisionService` are never released on any cancel path; `resetKamiItem` refuses status 2; `hasUnsettledSupply` then blocks editing/deleting the warehouse forever. Status 3 has no "resolve as delivered" exit either.
- **Exemplar**: `xianyu_kami_external_request` — every update is `WHERE id AND request_status='PROCESSING'` (`:59-74`), stale `PROCESSING` reclaimed after 2 minutes, idempotency is the unique key `(tenant, config, order)`, `exception_revision` bumps on failure. The only flaw: `REVIEW_REQUIRED` has no exit transition (`claimRetry` excludes it → permanent 409), and `claimRetry` bumps the revision on every retry, invalidating operator acks.

### 4.6 ORD-6..11 the trailing flags

- `state` and `delivery_status` are two variables for one fact, aligned only inside mapper `CASE` expressions.
- `confirm_state` is written unconditionally by three callers; one path couples it to `delivery_message_state=5`, the other does not.
- `rate_status` is write-mostly: eligibility is re-derived from the platform every run, so `-1` never triggers a retry by itself.
- Receipt drip ignores the return value of `updateReceiptFollowUpProgress` (`GoodsAutomationService:191-192`), so a lost optimistic update sends the same message twice.
- `exception_revision` is the only versioned, auditable state — and its bump points are inconsistent (some failures bump, some retries bump, some successes do not), which both suppresses real recurrences and invalidates valid acks.

---

## 5. Messaging & auto-reply

### 5.1 MSG-1 magic-number reply machine

`state ∈ {0 pending, 2 claimed, 1 success, -1 failed, -2 cancelled}` — no enum, no constants, no column comment; the entity comment documents only `0/1/-1`.

```sql
findDue  (state=0 AND scheduled<=now AND retry due) OR (state=2 AND lease expired)
claim    → 2, lease_owner, +120s, attempt+1     WHERE state=0 OR (2 expired)
cancel   → -2                                    WHERE state=0            (by session)
cancel   → -2                                    WHERE state IN (0,2)     (by id)
```

Defects: **no lease fencing** in `updateStateAndContent`; the state write is wrapped in a swallowing `catch` (`AutoReplyServiceImpl:280-286`), so a failed success-write re-sends after 120 s; **replay is dropped silently** — `submitDelayTask` cancels every `state=0` row of the session *before* the dedupe INSERT, `persistTask` ignores the affected-row count (`AutoReplyDelayServiceImpl:257`), so `ON DUPLICATE KEY` returns an old terminal row, `claim` refuses it and `dispatchTask` returns after already discarding the in-memory batch: no reply, no log, no record; `next_retry_time` is read but never written and `attempt_count` incremented but never read (no backoff, no cap).

### 5.2 MSG-2 in-flight delay

Memory holds `pendingTasks` + `pendingMessages`; the DB row holds the same merged list in `trigger_context`. Restart recovery is therefore complete (`recoverDueTasks` every 1 s), and the `claim()` in `dispatchTask` prevents the timer and the sweep from double-executing. It is the only machine where the in-memory copy is honestly a cache.

### 5.3 MSG-3/4 takeover — two machines, one live

| | `HumanTakeoverManager` (live) | `ManualModeServiceImpl` (dead) |
|---|---|---|
| Key | `accountId_sId` | `chatId` |
| Storage | DB + cache | memory only |
| Expiry | `end_time` + lazy + 1 min sweep | timeout + 5 min sweep |
| Consumers | `AutoReplyDelayServiceImpl` (2 checkpoints) | **none** |

`takeover()` writes the **cache first (`:80`) then DB (`:88`)** with no transaction, and `isTakenOver` consults the cache first, querying the DB only on a miss (`:120-133`) — so another instance's takeover is invisible for up to 10 minutes, and every takeover appends a new intervention row (no upsert on `(account, s_id)`).

`ManualModeService` has zero references anywhere and still runs `@Scheduled(fixedRate=300000)`. Wiring it up later would gate replies on a *different* key space than the live manager — a silent double-reply.

### 5.4 MSG-5 risk guard

`GuardState {NORMAL, RATE_WAIT, CIRCUIT_OPEN, RECOVERING}` — the one place a state enum is declared, and `RECOVERING` is never produced. State is per-JVM with a whole-file JSON snapshot (`persistState`), so nodes overwrite each other's circuits; one circuit blocks *all* guarded write operations for the account; recovery is timer-only with no probe counter.

### 5.5 MSG-6 gate

`automation_blocked` + `blocked_reason`, checked when messages arrive, when delay tasks expire, and again after a delivery task is claimed (`DeliveryTaskScheduler:110-116`). A gate, not a lifecycle — but it decides whether in-flight automation continues, which is what makes "manual takeover" invisible to already-queued work.

### 5.6 MSG-7/8/9 the leftovers

- `EnhancedMessageSendService`: 4-state retry ladder (`SUCCESS/NOT_CONNECTED/RETRY_EXHAUSTED`) — **zero consumers**, dead.
- Synthesised outbound rows (MSG-8): local copies of sent messages with synthetic `content_type` (888/887/997/999) and synthetic `pnm_id`, written by reply/delivery paths with no state column — "what the app believes it sent", unverifiable.
- Event fan-out (MSG-9): the phase order of delivery (notify → message → follow-up) is expressed only by `@Order`/`@Async` annotations on listeners; nothing checks that a later phase did not run before an earlier one.

---

## 6. Automation, operations, platform

**Substrate first** — every machine below inherits it:

- 14 `@Scheduled` methods on **2 threads** (`AsyncConfig:137`); all `fixedDelay` except one `fixedRate`.
- Business work on `taskExecutor` (4/8/500, `CallerRunsPolicy`) — overflow runs on the scheduler thread and stalls the rest.
- Notifications on `notificationExecutor` (1–2/200, **`DiscardPolicy`**) — silent loss.
- Overlap protection is a per-JVM `AtomicBoolean`; nothing is cluster-wide.
- No job records last-run or last-failure; a hung job is invisible and `fixedDelay` silently skips ticks.

**OPS-1 merchant task** (`0 pending / 1 running / 2 success / -1 failed`, documented in `V3:88`): `claim` guards status and reclaims after 10 minutes by `updated_time`; `complete`/`fail`/`requeue` are unguarded and there is no owner column — a publish exceeding 10 minutes is re-claimed and the stale worker overwrites the result. `requeue` also zeroes `attempt_count`, so operator retries loop indefinitely. Rule-scheduled tasks carry no `request_key`, so the idempotency index does not apply to them.

**OPS-2 merchant resource**: `status=2` ("published") is written once and never reset (`MerchantOperationsService:922`), and both selector queries require `status=1` — so a published resource can never be re-scheduled. `executePromotionRefresh` writes `account.status` (1/-2) into `resource.status` (`:1101`), mixing two value spaces in one column.

**OPS-7 item detail sync**: three booleans for three states (`isRunning`, `isCompleted`, `cancelled`); `cancelSync` is exposed by no endpoint so `cancelled` is unreachable; `@Async` is defeated by self-invocation at `:82`; `accountSyncMap.remove(accountId)` in `finally` can wipe the winner's lock.

**OPS-8 goods status** (`0 on sale / 1 off / 2 sold / -1 deleted / -9 platform-dead`): the domain exists only in a DTO comment and the Vue label map; `1` and `2` are never written by any Java path yet offered as UI filters; `-1` is reverted by the next refresh; and a truncated page fetch marks whole un-fetched pages as deleted (`ItemServiceImpl:195-216,257`).

**OPS-9 self-update**: 10 states in `status.json`, driven by a shell agent; the app *derives* two additional transitions (stale active status ⇒ delete `request.json` and report FAILED; active with no request ⇒ FAILED). The stale timeout can delete the request while the install continues, and reads never validate `taskId`, so a SUCCESS from task A can be shown for task B.

**OPS-10 backup**: no persisted state; `_error` markers inside a module are not detected by the prefix test, so a failed export is imported as a successful restore of zero rows; per-record failures only log; no transaction across modules.

**OPS-12 platform users**: `PermissionCatalog.defaultCodes()` returns the full catalogue, so a "default" user is indistinguishable from a superuser.

**OPS-13 keyword rules**: `updateMatchMode` accepts any integer while only `1`/`2` are ever matched — a rule saved as `3` is permanently dead but visible; blank keywords create catch-all rules.

**OPS-20 publish**: `localSynced=false` (`PlatformPublishService:118-124`) is a platform-orphan state that exists only inside one HTTP response which still reports `success:true` — no row, no recovery.

---

## 7. Frontend

No Pinia store is used (`stores/counter.ts` is unused); all state lives in module refs, composables and `<script setup>` locals. There is **no frontend WebSocket client** — the backend owns the socket and the UI polls.

Three defect shapes dominate:

1. **Polling without re-entrancy or terminal handling.** Pending-orders' delivery interval calls `handleDeliver()` with no guard (`pending-orders/index.vue:64-84`) — overlapping bulk delivery. The QR dialogs' `confirmed` branch awaits the login handler without stopping the 2 s timer (`QRLoginDialog.vue:74-77`), creating an account per re-entry. Goods-sync's poll error leaves `syncing=true` forever and `checkSyncing` is imported but never called (`useGoodsManager.ts:14,76-78`).
2. **The same enum rendered by many mappers.** Cookie status has **7** independent label/colour mappers; delivery status has **4+** (page table, buyer drawer, dialog, hook); merchant status has 3 with different vocabularies for the same number. A new backend value renders as `未知`/`处理中` in each place separately.
3. **Boolean clusters standing in for enums.** `refreshing`+`syncing`, `loading`+`silentLoading` (always cleared together, so each clears the other's spinner), 5 kami dialog booleans, 4 orders row flags, `debt`-style tri-state `editing: undefined|null|user`.

The best-designed frontend machine is the captcha dialog: a single reducer (`handleTaskStatus`) is the only writer, timers are cleared at the top of it, and reopen re-attaches via `resumeActiveTask`. The strongest *enforcement* is in the auto-delivery editor, which pairs three monotonic generation counters with identity re-checks after every `await` — worth copying.

---

## 8. Defect patterns

| # | Pattern | Instances | Consequence |
|---|---|---|---|
| P1 | **Lease without fencing** | delivery task, reply record, merchant task, delivery message (=timer) | stale worker overwrites the new owner; duplicate real-world side effects |
| P2 | **Terminal states re-entrant** | delivery (`REVIEW_REQUIRED`/`FAILED` over `COMPLETED`), merchant task (`-1`/`0` over `2`), distribution publish | a finished record silently un-finishes |
| P3 | **Two vocabularies, one column name** | `delivery_status` on orders vs kami usage (`'RESERVED'` default); `status` with 6 meanings across 12 tables; `1` = "normal" for accounts and "running" for tasks and "on sale" for goods | queries match nothing; UI shows the wrong label |
| P4 | **Timeout means success** | `XianyuWebSocketClient:866-868` → `markDeliveryMessageSent` | unknown outcomes persisted as delivered |
| P5 | **Multi-field non-atomic write** | `state`+`delivery_status`; `confirm_state`+`delivery_message_state`; cookie status+notify; risk circuit+file | half-states survive crashes and no query recognises them |
| P6 | **Silent state-write failure** | `AutoReplyServiceImpl:280-286`; notification `DiscardPolicy`; backup per-record catch | duplicate replies; lost notifications; unreported partial restores |
| P7 | **In-memory only** | 12 WS maps, risk circuit, takeover cache, reply tasks, sync progress, goods page cursor, captcha tasks, QR sessions, stock alert, self-update lock | restart loses state; nodes diverge; "stop" undone by deploy |
| P8 | **Lazy timeout, no reaper** | QR sessions (no scheduler), order leases, kami reservations (no expiry sweep), ack rows (no prune), `-2` replies (no cleanup) | leaks; stuck states with no forced terminal |
| P9 | **Check-then-act on maps** | `reconnectTasks`, `accountSyncMap`, `connectionRestartFlags`, `connectingAccounts`, self-update file check | duplicate schedulers; wrong-entry removal; permanent lock leak |
| P10 | **Unreachable/ghost states** | §9 | UI advertises states the backend cannot produce; operators chase phantoms |
| P11 | **No transition audit** | whole system except `exception_revision` | forensics rely on prose operation logs |
| P12 | **Whole-row rewrite** | `updateById(account)` status setters; `XianyuGoodsConfigMapper.update` (9 switches); `auto_confirm_shipment` forced to 1 on config save | one operator's toggle silently reverts another's |
| P13 | **Side effects in a poll** | cookie `2 → email` edge detection; stock alert throttle; connection status pollers | duplicates or misses under concurrency |
| P14 | **State smuggled through return values** | `"COOKIE_EXPIRED"` as a CDN URL; `localSynced=false`; `success:true` with a broken payload | a lifecycle with no storage, no query and no recovery |

---

## 9. Ghost states (declared, displayed, never produced)

| State | Claimed by | Reality |
|---|---|---|
| `DeliveryStatus.DELIVERED`, `.CONFIRMING` | enum, `order-status.ts:13,15`, dashboards | never written |
| `GuardState.RECOVERING` | enum | never produced |
| `xianyu_account.status = -1` "需要验证" | entity javadoc, `utils/index.ts:56` | code writes `-2`; `-1` matches nothing |
| `xianyu_account.status = -2` | two writers | no UI branch, excluded from every recovery query ⇒ invisible + absorbing |
| `xianyu_goods.status = 1, 2` | UI filters, label map | no Java writer |
| `SyncProgress.cancelled` | `cancelSync()` | no endpoint calls it |
| `OrderStatusEnum` (1..5) | enum with full lookup helpers | **zero references** in the repo |
| login lockout `locked` | `isLoginLocked` compares the counter to 5 | counter resets to 1 on every failure ⇒ unreachable (§3.1a) |
| `ManualModeService` manual/auto | interface + impl + scheduled cleanup | zero callers |
| `EnhancedMessageSendService` retry ladder | impl + result enum | zero consumers |
| `xianyu_kami_usage_record.delivery_status = 'RESERVED'` | column default `V1:310` | not a `DeliveryStatus` value; no query matches it |
| `xianyu_kami_item.status = 3` (review) | reserved→review transition | no exit to `delivered`; only reset to available |
| `request_status = REVIEW_REQUIRED` | written on uncertain supply | no exit transition ⇒ permanent 409 |
| `delivery_message_state = 5` | one query | no API, no UI, no doc |
| `cookie_status = 3` | docs + controller switch | two token-refresh failure paths only |
| `receipt_follow_up_completed` for old rows | `V5:22` sets all rows to 1 | migration-synthesised, no transition event |
| `xianyu_notification_log.http_status` | written | never read |
| `XianyuCookie.expire_time` | written in 6 places | never read ⇒ no expiry enforcement |
| `maxReconnectAttempts` | `WebSocketConfig:97` | never read ⇒ no "gave up" terminal |
| `match_mode ∉ {1,2}` | accepted by the API | always dead |
| QR `error` | set on exception | mapped to `pending`; looks like "waiting" |

---

## 10. What to do (ordered by payoff)

A framework is the wrong first move: these machines are DB-row-owned, multi-process and timer-driven, and the transitions already live in SQL. What is missing is named states, enforced guards, and a log.

### Step 1 — make SQL the single authority (P0)

1. Declare an enum per column (`DeliveryTaskState`, `DeliveryMessageState`, `AutoReplyState`, `KamiItemState`, `MerchantTaskState`), keep the DB types.
2. One mapper method per transition, returning `int`, with `WHERE id = ? AND current_state IN (…) [AND lease_owner = ?]`; the caller asserts `== 1` and otherwise abandons. `requeueFailedTask`, `markDeliveredTask`, `cancelHeldDeliveryMessage`, `settle`, and all of `XianyuKamiExternalRequestMapper` already do this — use them as the template.
3. Fix the four writes that can cost money:
   - `completeTask`: `AND lease_owner = #{workerId}`
   - `retryOrFailTask`: `AND lease_owner = #{workerId}`
   - `updateStateAndContent` (reply): `AND lease_owner = #{workerId}`
   - `markDeliveryMessageSent`: `AND delivery_message_state = 2` **plus** an owner token
4. Remove the forced `state=1 / COMPLETED` from `prepareDeliveryMessage` / `activateDeliveryMessage`, and stop treating a socket timeout as a successful send.

### Step 2 — one transition log (P0/P1)

```sql
CREATE TABLE state_transition_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  machine      VARCHAR(32) NOT NULL,
  entity_id    BIGINT NOT NULL,
  tenant_id    BIGINT NULL,
  from_state   VARCHAR(24) NOT NULL,
  to_state     VARCHAR(24) NOT NULL,
  trigger      VARCHAR(64) NOT NULL,
  actor        VARCHAR(64) NULL,
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_transition_entity (machine, entity_id, created_at)
);
```

Write it in the same transaction as the guarded update. This turns P11 into a query, and lets you regenerate the real transition graph instead of inferring it.

### Step 3 — collapse the connection maps (P1)

One `AccountConnection` object per account (`state`, `client`, `attempt`, `lastHeartbeatAt`, `lastTokenRefreshAt`, `captchaBackoff`, `restartRequested`), all mutations through `transition(id, Event)` under a per-account lock. This removes the teardown race (WS-1), the stale restart flag (WS-4) and the duplicate schedulers (WS-5) at once.

### Step 4 — model the saga (P1)

Replace the 5-boolean tuple with `fulfilment_step ∈ {NONE, KAMI_RESERVED, CONSIGNED, MESSAGE_HELD, COMMITTED}` + `compensation_pending`, persisted at each step. "What must be undone" becomes a `switch` on a stored value instead of on the exception that happened to be caught.

### Step 5 — delete, demote, reconcile (P1/P2)

- Delete `ManualModeServiceImpl`, `EnhancedMessageSendService`, `OrderStatusEnum`.
- Let `cookie_status=2` come only from authoritative signals (401 / hasLogin / explicit API expiry), never a bare 500.
- Pick one account-status encoding (`-2` or `-1`), fix the javadoc, add the UI branch, and surface it in a query — an invisible absorbing state is why accounts "disappear".
- Remove or emit `DELIVERED`/`CONFIRMING`; make the UI enum equal the backend enum.
- Release reservations for API-sourced warehouses, and give `REVIEW_REQUIRED` an operator exit.
- Move per-JVM locks (risk circuit, takeover, connection desired-state, goods page cursor, scheduler guards) to DB leases or a shared store before running more than one replica.

### Step 6 — tests

There is exactly **one** state-related test (`WebSocketServiceImplCaptchaBackoffTest`). Table-driven `allowed(from, trigger)` tests per machine, plus one "lease expires, stale worker completes" test per leased machine, plus one "poll re-entrancy" test per frontend loop, would catch every P0 in this document. `catalog.yaml` is shaped so those tables can be generated.

---

## 11. Reproducing the audit

```bash
# every state write
grep -rn --include=*.java -E "\.set(Status|State)\(" src/main/java | sort

# the real transition tables — guards live in SQL
grep -rn -E "UPDATE .*WHERE" src/main/java/com/xianyusmart/mapper/*.java

# in-memory machines
grep -rn --include=*.java "private final .*ConcurrentHashMap" src/main/java

# what drives transitions
grep -rn "@Scheduled" src/main/java -A2

# ghosts: written but never read
grep -rn "DELIVERED\|CONFIRMING\|RECOVERING\|setStatus(-2)\|setStatus(-1)" src/main/java

# frontend state vocabulary
grep -rn "status" vue-code/src/views/orders/order-status.ts vue-code/src/utils/index.ts

# state-related test surface
find src/test -name "*.java" | xargs grep -ln "status\|state"
```

---

## Appendix A — machines by severity

**High:** WS-1, WS-6, WS-10, ORD-1, ORD-2, ORD-3, ORD-6, MSG-1, OPS-1, OPS-8, OPS-12, FE-1, FE-3, FE-4, FE-5, FE-8, FE-10, FE-13, FE-15, FE-17.

**Dead:** MSG-4 (`ManualModeService`), MSG-7 (`EnhancedMessageSendService`), ORD-11's `OrderStatusEnum`, plus 4 orphaned Vue components and 2 unrouted views.

## Appendix B — configuration values that look like states but are not

`voucher_delivery_enabled`, `chat_delivery_enabled`, `alert_enabled`, `enabled` (channel/resource), `automation_blocked`, `xianyu_auto_rate_on` (`0 off / 1 always / 2 after-buyer`, migrated by `V8`), `xianyu_auto_polish_on`, `receipt_follow_up_interval_seconds`, `delivery_mode` (`1` fixed / `2` card, with a `CHECK` in `V7:127`). They change behaviour but have no topology; enumerated under `configurations:` in `catalog.yaml`.
