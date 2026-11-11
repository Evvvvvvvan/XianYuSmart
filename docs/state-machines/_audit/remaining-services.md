# Implicit State Machine Audit — Services Not Covered by Other Auditors

Scope: `com.xianyusmart.service` (the files listed below), `com.xianyusmart.controller`, `com.xianyusmart.mapper`,
plus the DDL under `src/main/resources/db/migration` and the Vue client where it reveals the intended state set.
Everything below is read-only analysis; no source file was modified.

**Files read in full or in the relevant entirety**

| Area | Files |
|---|---|
| Sync | `service/impl/ItemDetailSyncServiceImpl.java`, `service/ItemDetailSyncService.java`, `controller/dto/SyncProgressRespDTO.java` |
| Goods | `service/ItemService.java`, `service/impl/ItemServiceImpl.java`, `service/GoodsInfoService.java`, `service/impl/GoodsInfoServiceImpl.java`, `service/GoodsSkuService.java`, `service/impl/GoodsSkuServiceImpl.java`, `service/GoodsSkuPropertyService.java`, `service/impl/GoodsSkuPropertyServiceImpl.java` |
| Media | `service/ImageUploadService.java`, `service/impl/ImageUploadServiceImpl.java` |
| Reply | `service/KeywordReplyService.java`, `service/impl/KeywordReplyServiceImpl.java` |
| Publish | `service/PlatformPublishService.java` |
| Opportunities / rating / buyers | `service/OpportunityAnalysisService.java`, `service/OpportunityImageService.java`, `service/RatingContentService.java`, `service/BuyerProfileService.java`, `service/buyer/BuyerProfilePolicy.java` |
| AI | `service/AIService.java`, `service/impl/AIServiceImpl.java` |
| Identity | `service/PlatformUserService.java`, `service/PlatformPermissionService.java`, `service/PermissionCatalog.java` |
| Audit / settings | `service/OperationLogService.java`, `service/impl/OperationLogServiceImpl.java`, `service/SysSettingService.java`, `service/impl/SysSettingServiceImpl.java`, `constants/OperationConstants.java` |
| Delivery / kami | `service/AutoDeliveryConfigService.java`, `service/impl/AutoDeliveryConfigServiceImpl.java`, `service/FixedDeliveryTemplateService.java`, `service/KamiConfigService.java`, `service/impl/KamiConfigServiceImpl.java`, `service/ExternalKamiProvisionService.java`, `enums/KamiStatus.java` |
| Diagnostics | `service/OperationsDiagnosticsService.java`, `service/diagnostics/OperationsHealthEvaluator.java` |
| Controllers | `ItemController`, `MerchantOperationsController`, `KamiConfigController`, `KeywordReplyController`, `OperationsDiagnosticsController`, `PlatformUserController`, `AutoDeliveryConfigController`, `FixedDeliveryTemplateController`, `ImageUploadController`, `GoodsSkuController`, `BuyerProfileController`, `SysSettingController`, `MsgController`, `AIChatController`, `QRLoginController`, `WebSocketController`, `SystemController` |
| Mappers | all of `mapper/*.java` (guard inventory in §19) |
| DDL | `V1__baseline.sql`, `V3`, `V4`, `V6`, `V7`, `V8`, `V9`, `V11`, `V14`, `V17` |

---

## 0. Machine index

| # | Machine | Owner (state writer) | Storage | Discipline |
|---|---|---|---|---|
| SM-01 | Detail-sync progress | `ItemDetailSyncServiceImpl` | in-memory `ConcurrentHashMap` x2 | ad-hoc booleans, **no atomicity, no eviction, cancel unreachable** |
| SM-02 | Goods listing status | `GoodsInfoServiceImpl`, `ItemServiceImpl` | `xianyu_goods.status` | none (writes are side effects of sync) |
| SM-03 | Goods automation flag matrix | `ItemServiceImpl`, `AutoDeliveryService`, `KeywordReplyServiceImpl` | `xianyu_goods_config` (9 flags + 1 timestamp) | partial (per-endpoint validation, whole-row overwrite) |
| SM-04 | Keyword reply rule | `KeywordReplyServiceImpl` | `xianyu_keyword_reply_rule` + `_content` | none (dead `match_mode` values, sentinel keyword) |
| SM-05 | SKU/attribute snapshot | `GoodsSkuServiceImpl` | `xianyu_goods_sku`, `_property`, `xianyu_goods.sku_count` | none (delete+reinsert, no epoch/version) |
| SM-06 | Credential (cookie) status | `AccountServiceImpl` + 4 callers incl. this audit's files | `xianyu_cookie.cookie_status` | none (5 writers, string sentinel smuggling) |
| SM-07 | Platform publish / listing | `PlatformPublishService` | platform only; `localSynced` boolean discarded | none (two-phase delete without compensation) |
| SM-08 | Merchant task queue + publish idempotency | `MerchantOperationsService` | `merchant_task` | guards on entry, **none on completion** |
| SM-09 | Kami inventory item | `KamiConfigServiceImpl`, `ExternalKamiProvisionService` | `xianyu_kami_item.status` | strong (conditional UPDATEs) with two trap states |
| SM-10 | External kami supply request | `ExternalKamiProvisionService` | `xianyu_kami_external_request` | strong (lease + attempt cap + revision) |
| SM-11 | Exception acknowledgement / ops health | `OperationsDiagnosticsService` | `xianyu_exception_acknowledgement` + `exception_revision` | strong (version-bound INSERT IGNORE) |
| SM-12 | Platform user / role / permission | `PlatformUserService` | `sys_user`, `sys_user_permission`, `sys_login_token` | partial (last-admin invariant via row lock) |
| SM-13 | Setting → AI runtime rebuild | `SysSettingServiceImpl` + `DynamicAIChatClientManager` | `xianyu_sys_setting` + JVM caches | hardcoded key whitelist |
| SM-14 | Buyer automation block | `BuyerProfileService` | `xianyu_buyer_profile` | partial (flag + free-text reason, no coupling) |
| SM-15 | Delivery mode exclusivity | `AutoDeliveryConfigServiceImpl` | `xianyu_goods_auto_delivery_config` | strong (mutual exclusion of columns) |
| SM-16 | Rating content pool | `RatingContentService` | `xianyu_goods_config.xianyu_auto_rate_content` (TEXT) | none (format sniffing) |
| SM-17 | QR-login session | `QRLoginServiceImpl` (invoked by controller) | in-memory `ConcurrentHashMap` | partial (TTL, manual cleanup only) |

Counted as machines: **17**. Five further lifecycle endpoints whose state is owned outside this assignment are
recorded in §18 so the owner files can be cross-checked.

---

## SM-01 — Item detail sync progress

**Purpose.** After `POST /api/items/refresh` upserts a page list, a per-account follow-up job fetches each item's
`mtop.taobao.idle.pc.detail`, persists desc/SKU/attributes, and exposes a pollable progress record.

**State set (exact literals).** No string/int state column; the state is the tuple of
`SyncProgress` fields (`ItemDetailSyncServiceImpl.java:47-60`):

- `isRunning` — `true` at construction (`:55`), `false` only at `:132` / `:139`
- `isCompleted` — `false` at construction (`:54`), `true` only at `:131` / `:138`
- `cancelled` — `false` at construction (`:59`), `true` only at `:277`
- `message` — `"同步中..."` (`:57`) → `"同步进度: %d/%d"` (`:128`) → `"同步完成: 成功%d, 失败%d"` (`:134`) /
  `"同步失败: " + e.getMessage()` (`:140`) / `"同步已取消"` (`:99`) / `"正在取消同步..."` (`:278`)
- counters `totalCount/completedCount/successCount/failedCount` (`:50-53`), `currentItemId` (`:56`), `startTime` (`:58`)

**Storage.** Two in-memory maps, both process-local, both lost on restart:
`ConcurrentHashMap<String, SyncProgress> progressMap` (`:44`) and `ConcurrentHashMap<Long, String> accountSyncMap` (`:45`).
Desync risk: the two maps are the only "is this account busy" authority; a restart mid-sync leaves zero rows anywhere
(the DB has no sync/job table at all — `xianyu_goods` only gets the individual item updates).

**Transitions**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| (none) | `startSync` when `isSyncing(accountId)` false | NEW (`isRunning=1,isCompleted=0`) | `:63-78` |
| (none) | `startSync` when `isSyncing` true | returns the *existing* `syncId`, no new task | `:64-68` |
| NEW | `executeSync` enters | RUNNING (same flags) | `:88-96` |
| RUNNING | per item: `cancelled` true | breaks loop | `:98-101` |
| RUNNING | item has null/blank id | counter-only skip (`completedCount++`,`failedCount++`) | `:104-108` |
| RUNNING | item fetched (success/fail) | RUNNING, `currentItemId`/`message` mutated | `:110-128` |
| RUNNING | `InterruptedException` from the 0–500 ms jitter sleep | breaks loop, **falls through to COMPLETED** | `:112-117` |
| RUNNING | loop end | COMPLETED (`isCompleted=1,isRunning=0`) | `:131-134` |
| RUNNING | any `Exception` | COMPLETED-with-failure message | `:136-140` |
| COMPLETED | — (no removal) | stays COMPLETED forever | `:44`, `:141-143` removes only `accountSyncMap` |
| NEW/RUNNING | `cancelSync(syncId)` | sets `cancelled=true`, `message="正在取消同步..."`; *state unchanged* | `:273-281` |

**Enforcement.** None. There is no illegal-transition check: `cancelSync` may be applied to a COMPLETED record (and does
nothing useful, since the worker already exited), `getProgress` has no state machine, and `isRunning`/`isCompleted` are
plain non-volatile fields written by a worker thread and read by HTTP threads (`:254-260`).

**Defects and risks**

1. **Cancel is unreachable dead state.** `cancelSync` is declared (`ItemDetailSyncService.java:10`) and implemented
   (`:273-281`), but *no controller exposes it*: `ItemController` has only `/refresh` (`:49`), `/syncProgress/{syncId}`
   (`:310`), `/syncing/{accountId}` (`:324`). A repo-wide grep for `cancelSync` returns only the declaration, the
   interface and the implementation. Therefore `cancelled` can never become `true` in production, and the messages
   `"正在取消同步..."` / `"同步已取消"` (`:278`, `:99`) are unreachable strings. Even if wired, `SyncProgressRespDTO`
   (`controller/dto/SyncProgressRespDTO.java:6-19`) has **no `cancelled` field**, so the client could not observe it.
2. **`@Async` is not applied on the only call path.** `startSync` calls `executeSync` directly on `this`
   (`:82`), so the proxy is bypassed and the `@Async` at `:88` never takes effect. Despite
   `@EnableAsync` (`XianYuSmartApplication.java:15`, `config/AsyncConfig.java:19`), the whole per-item network loop
   runs on the HTTP request thread — `POST /api/items/refresh` blocks for `items × (0-500 ms + API latency)`. The
   `syncId` is returned only after the sync has already finished, so `getProgress` will typically observe a
   COMPLETED record immediately (`:131-134`).
3. **Check-then-put race on the per-account lock.** `isSyncing` (`:284-291`) is read at `:64` and the maps are written at
   `:77-78` with no atomic claim. Two concurrent refreshes for one account both pass the check, both create a sync; the
   second `accountSyncMap.put` overwrites the first binding, so the first worker's `finally`
   `accountSyncMap.remove(accountId)` (`:142`) deletes the *second* task's lock. Result: `isSyncing` reports false while a
   worker is still running, and two detail-sync jobs hammer the platform in parallel.
4. **Two divergent truth views.** `isSyncing` requires `progress != null && isRunning && !isCompleted` (`:290`), but
   `getProgress` returns whatever the flags say. If `progressMap.get(syncId)` is null in `executeSync` (`:90-94`) the
   method returns *before* the `finally`, leaving `accountSyncMap` populated forever, and `isSyncing` stays `false`
   because the mapped id no longer resolves — an account permanently "not syncing" with a stale lock row.
5. **Unbounded in-memory maps.** `progressMap` is never pruned or TTL'd (`:44`, no `remove` anywhere) → one entry per
   refresh call, for the process lifetime; on a busy deployment this leaks monotonically.
6. **Non-atomic counters and non-visible fields.** `completedCount/successCount/failedCount/message/currentItemId/
   isCompleted/isRunning` are ordinary fields mutated by the worker (`:105-134`) and read by request threads
   (`:250-261`); there is no `volatile`, no `synchronized`. `estimatedRemainingTime` (`:263-268`) can be computed from a
   torn `completedCount`, and can divide by an already-nonzero counter while `totalCount` is stale.
7. **Interrupted and cancelled runs both claim success.** After an interrupt (`:114-117`) or a (theoretical) cancel, the
   code still executes `:131-134` and reports `"同步完成: 成功X, 失败Y"` — a partial run is indistinguishable from a clean
   one; `failedCount` does not include the not-attempted remainder.
8. **Silent skip.** Items with a null/blank id increment `failedCount` but emit no message and leave `currentItemId` at
   the previous item (`:104-107`), so the operator cannot tell which item was dropped.
9. **No timeout.** Nothing bounds the total run; a slow platform call makes the record RUNNING indefinitely, and because
   of (2) the caller simply waits.

---

## SM-02 — Goods listing status (`xianyu_goods.status`)

**Purpose.** Track whether a locally mirrored listing is on sale, off the shelf, sold or deleted, for filtering
(`onlyOnSale`), for the goods table, and for automation selectors.

**State set (exact literals).** `0` = 在售 / on sale, `1` = 已下架 / off shelf, `2` = 已售出 / sold,
`-1` = 已删除 / deleted. The set is *not* defined in Java at all; it is defined by the client:

- `vue-code/src/views/goods/index.vue:410-413` — filter options `0/1/2/-1` = 在售 / 已下架 / 已售出 / 已删除
- `vue-code/src/views/orders/index.vue:359-361` — `status === 0 ? '在售' : status === 1 ? '已下架' : status === -1 ? '已删除' : '已售出'`
- `vue-code/src/views/auto-delivery/useAutoDelivery.ts:272-280` — map `{0:'在售',1:'已下架',2:'已售出'}`, fallback `'未知'` / `'off-shelf'`
- Backend: `controller/dto/ItemDTO.java:33-36` documents only `商品状态（0=在售）`; `entity/XianyuGoodsInfo.java:56` `private Integer status;`
  with no comment; DDL default `TINYINT NOT NULL DEFAULT 0` (`V1__baseline.sql:75`), no CHECK constraint.

**Storage.** `xianyu_goods.status` (row per `(xianyu_account_id, xy_good_id)`, unique key `uk_goods_account_remote`,
`V1__baseline.sql:79`). Written only by whole-row `updateById`/`insert` — never by a guarded `UPDATE ... WHERE status=…`.
Desync risk: the authoritative listing state lives on the *platform*; the local column is refreshed only when the page
list or detail sync happens to run (see `PlatformPublishService.changeListingStatus`, SM-07).

**Transitions**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| (absent) | page-list upsert, platform `itemStatus` | `itemDTO.getItemStatus()` (platform value, normally 0) | `GoodsInfoServiceImpl.java:85` via `:94-101` insert |
| `0/1/2/-1` | page-list upsert of an already-known row | platform `itemStatus` again — **any→any, no guard** | `GoodsInfoServiceImpl.java:87-93` (`updateById` with the new status) |
| `0/1/2/-1` | publish succeeded, local row created | forced `0` | `GoodsInfoServiceImpl.java:133` (`savePublishedGoods`) |
| `0` or `1` | refresh determined the id absent from the fetched remote set | `-1` | `GoodsInfoServiceImpl.java:401-412` (`markOfflineIfNotInRemote`; guard `in(status, 0, 1)` at `:403`; write at `:409`) |
| any | `POST /api/items/delete` | row **hard-deleted** (state disappears) | `ItemServiceImpl.java:1093-1130` → `GoodsInfoServiceImpl.deleteGoodsInfo:346-369` |
| any | platform delete via `PlatformPublishService.delete` | **unchanged locally** | `PlatformPublishService.java:190-213` (no `goodsInfoService` call) |
| `0` | `POST /api/items/changeListingStatus {onSale:false}` (platform downshelf) | **unchanged locally** | `PlatformPublishService.java:221-229` |

**Enforcement.** None. `markOfflineIfNotInRemote` has the only `WHERE`-style guard in the flow (`in(status, 0, 1)`,
`:403`), and it is evaluated by re-reading the row and calling `updateById` (`:407-411`), so it is not atomic either:
two concurrent refreshes can interleave read/modify/write. Any other writer overwrites `status` with whatever the API
returned, with no legality check.

**Defects and risks**

1. **Two states are never written: `1` (已下架) and `2` (已售出).** No Java code sets them (repo-wide grep for
   `setStatus(1)`/`setStatus(2)` on `XianyuGoodsInfo` finds nothing; the only `XianyuGoodsInfo.setStatus` calls are
   `GoodsInfoServiceImpl.java:85`, `:133` (=0) and `:409` (=-1)). The client nevertheless offers them as filters
   (`goods/index.vue:411-412`) and labels `1`, `2` (`orders/index.vue:361`), so those filter choices always return an
   empty page, and “已下架 / 已售出” are unreachable UI states.
2. **`-1` is not terminal.** `saveOrUpdateGoodsInfo` looks the row up by `(account, xy_good_id)` (`:58-61`) and, on the
   next refresh, overwrites `status` back to the platform value (`:87-93`). An item the operator saw as 已删除 silently
   resurrects as 在售 — the "undelete" transition exists implicitly, with no audit and no code that expresses it.
3. **`markOfflineIfNotInRemote` fires on incomplete data.** In `refreshItems` it is called *before* the upsert loop
   (`ItemServiceImpl.java:257`) with a set built only from items that have `detailParams.itemId` (`:249-254`), and it is
   called unconditionally after pagination that may have been **truncated**:
   - `maxPages` break (`:195-198`) → only the first N pages are in `remoteItemIds`; every listing beyond them is
     marked `-1`.
   - page-fetch failure break (`:208-216`) → ditto for page ≥2.
   This turns a partial fetch into mass 已删除 marking on the whole account.
4. **Semantic drift between interface contract, impl log and client.** `GoodsInfoService.java:148` documents the method as
   “标记本地在售但远程不存在的商品为已下架” (off shelf), the impl logs `"标记…为已删除(status=-1)"`
   (`GoodsInfoServiceImpl.java:417`), and the client renders `-1` as 已删除 (`orders/index.vue:361`). Three names for one
   value; the doc and the client also disagree on which is a "listing" state.
5. **Sold items leak.** Because `2` is never written, `in(status, 0, 1)` (`:403`) never excludes anything meaningful; if
   the platform ever returns `itemStatus = 2`, `saveOrUpdateGoodsInfo` will persist it (`:85`) and then
   `markOfflineIfNotInRemote` will never clean it up (guard excludes 2) — a row frozen at 已售出 forever.
6. **Two incompatible delete models.** `/api/items/delete` hard-deletes the row (`ItemServiceImpl:1110-1111`) and drops
   its `xianyu_goods_config`/SKU associations via `ON DELETE CASCADE` (`V1__baseline.sql:82`), while the platform-driven
   path only flips `status=-1`, retaining configs (`GoodsInfoServiceImpl:409`). The same operator action has different
   data-erasure semantics depending on which entry point is used.
7. **`resolveStatusFilter` collapses two flags into one.** `ItemServiceImpl.java:298-303`: explicit `status` wins,
   otherwise `onlyOnSale==true` means `0`. There is no way to express "off shelf" or "sold" through `onlyOnSale`, and
   `onlyOnSale=false` + `status=null` means "everything", so the API cannot distinguish "don't filter" from "filter
   nothing".

---

## SM-03 — Goods automation flag matrix (`xianyu_goods_config`)

**Purpose.** Per-listing switches that every automation worker reads before acting: auto-delivery, auto-reply (and its
context flag), keyword reply, auto-rate mode + content, auto-polish (+ its last-run stamp), human-takeover window.

**State set (exact literals).** Nine independent fields, all `TINYINT`, no combined status:

| Field | Values | Source of truth |
|---|---|---|
| `xianyu_auto_delivery_on` | 0 / 1 | `V1__baseline.sql:115` |
| `xianyu_auto_reply_on` | 0 / 1 | `V1:116` |
| `xianyu_auto_reply_context_on` | 0 / 1 (default **1**) | `V1:117` |
| `xianyu_keyword_reply_on` | 0 / 1 | `V1:118` |
| `human_intervention_on` | 0 / 1 | `V1:119` |
| `human_intervention_minutes` | int (default 10) | `V1:120` |
| `xianyu_auto_rate_on` | 0 OFF / 1 ALWAYS / 2 AFTER_BUYER | `XianyuGoodsConfig.java:12-14`; added `V3:48` |
| `xianyu_auto_rate_content` | JSON array or plain text, ≤10 items ×500 chars | `V4:2`, `V6:1`, `RatingContentService` |
| `xianyu_auto_polish_on` | 0 / 1 | `V3:49` |
| `last_polish_time` | BIGINT epoch ms (null = never) | `V3:50` |

**Storage.** `xianyu_goods_config`, one row per `(xianyu_account_id, xy_goods_id)` (`V1:125`). Desync risk: every writer
does read-modify-write of the *whole* row through `XianyuGoodsConfigMapper.update` (`XianyuGoodsConfigMapper.java:32-42`),
which unconditionally re-sets all nine columns from the in-memory entity, including `fixed_material` (`:41`).

**Transitions (each endpoint is an independent transition function)**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| row absent | `updateAutoDeliveryStatus` | new row: delivery=`dto`, reply `0`, no rate/polish set | `ItemServiceImpl.java:906-913` |
| row present | `updateAutoDeliveryStatus` | delivery=`dto`, **other flags unchanged in memory but all columns rewritten** | `:914-921` |
| row absent | `updateAutoReplyStatus` | reply=`dto`, delivery `0`, context `dto` or `1`, keyword `dto`/`0`, human `dto`/`0`, minutes `dto`/`10` | `:950-978` |
| row present | `updateAutoReplyStatus` | reply + optional context/keyword/human/minutes | `:979-996` |
| any | `updateGoodsAutomationStatus` (≤200 ids, ownership checked) | rate mode ∈{0,1,2} enforced, polish coerced `==1?1:0`, content normalized | `:1019-1090`; validation `:1052-1057`; coercion `:1084` |
| `xianyu_auto_polish_on=1` | polish worker records a polish | `last_polish_time = ?` **guarded by `xianyu_auto_polish_on = 1`** | `XianyuGoodsConfigMapper.java:56-57` |
| any | `DELETE /api/items/delete` (row cascade) | config row gone | `V1:127` `ON DELETE CASCADE` |

**Enforcement.** Partial:
- auto-rate mode is validated (`ItemServiceImpl.java:1052-1057`) — but must be one of `AUTO_RATE_OFF/ALWAYS/AFTER_BUYER`
  and, after migration `V8`, **`1` was renumbered to `2`** (`V8__add_auto_rate_trigger_mode.sql:1-4`), i.e. the same literal
  changed meaning between releases.
- `human_intervention_minutes` is only written when `> 0` (`:974-978`, `:992-994`), so `0` cannot be expressed.
- polish is only reachable while `xianyu_auto_polish_on = 1` (mapper guard `:56`), which keeps `last_polish_time` from
  advancing on a disabled listing.
- everything else: no cross-field invariants, no guard on enabling delivery without delivery content, etc.

**Defects and risks**

1. **Whole-row overwrite → lost updates / flag resurrection.** `XianyuGoodsConfigMapper.update` (`:32-42`) writes every
   column from the caller's entity. Two operators toggling *different* switches on the same listing concurrently
   (e.g. auto-reply via `ItemServiceImpl:981-999`, keyword reply via the keyword controller) will have the loser's field
   silently reverted, because each reads a snapshot and writes the full row. No optimistic version column exists.
2. **Contradictory flag combinations are representable.** `updateAutoReplyStatus` can set
   `xianyu_keyword_reply_on = 1` (`:986-988`) while no `xianyu_keyword_reply_rule` row exists, and
   `isKeywordReplyEnabled` (`KeywordReplyServiceImpl.java:199-210`) will then report "enabled" while `matchKeyword`
   returns empty. Likewise `auto_reply_on = 1` with `auto_reply_context_on = 0` is allowed and means two different
   prompts depending on the caller.
3. **`fixed_material` is collateral damage.** `update` always writes `fixed_material` (`:41`); `ItemServiceImpl` builds
   the entity from `getGoodsConfig` and only sets the switches, so the value is preserved *only if* the read is fresh —
   any concurrent `saveFixedMaterial` (`AIChatController:121-135`) is at risk.
4. **Old rows never migrated for new flags.** Columns added in `V3` (`:48-50`) default to `0`; `updateAutoReplyStatus`'s
   "new row" default for `auto_reply_context_on` is `1` (`:960`) while `ItemServiceImpl.buildItemWithConfig` treats a
   *missing* config as context `1` (`:525`, `:535`) but `getItemsFromDb` treats it as `0` (`:384`, `:403`). The same
   absent row therefore renders as two different effective states in the list vs. detail endpoints.
5. **`last_polish_time` is a "last*" timestamp with no state.** Nothing records "polish failed" or "polish skipped";
   the polish worker only advances the stamp on success, so a permanently failing listing is retried every scheduler
   tick forever with no attempt counter (unbounded implicit retry loop).
6. **Flag set has no reader for two fields in this file set.** `xianyu_auto_delivery_on` is never consulted in
   `ItemServiceImpl`/`GoodsInfoServiceImpl` (delivery is decided in `AutoDeliveryServiceImpl`), so the listing table
   happily reports delivery=1 for a listing with no delivery config; `human_intervention_minutes` is written but not
   read anywhere under audit.

---

## SM-04 — Keyword reply rule lifecycle

**Purpose.** Per-listing keyword rules with a fuzzy/exact mode, a single "fallback" rule, and an ordered content pool;
matched at reply time.

**State set (exact literals).**

- `match_mode` INT: **1 = fuzzy**, **2 = exact** — values live only in SQL:
  `XianyuKeywordReplyRuleMapper.java:21` (`match_mode = 2`), `:24` (`match_mode = 1`), DDL default `1` (`V1:329`)
- `is_fallback` INT: 0 normal, 1 fallback — `:15`, `:18`, `:24`; default `0` (`V1:330`)
- `keyword` sentinel `"__fallback__"` for the fallback row (`KeywordReplyServiceImpl.java:108`, `:117`)
- derived column `unique_keyword = IF(is_fallback = 1, '__FALLBACK__', keyword)` (`V1:331`) with
  `UNIQUE KEY uk_keyword_rule (xianyu_account_id, xy_goods_id, unique_keyword)` (`V1:334`)
- content rows: 1..n `xianyu_keyword_reply_content` (`V1:339-348`), no "empty" marker

**Storage.** Two tables (`xianyu_keyword_reply_rule`, `xianyu_keyword_reply_content`) + the enable flag in
`xianyu_goods_config.xianyu_keyword_reply_on` (SM-03). Desync risk: "rule exists but content list is empty" is a state
no column records.

**Transitions**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| absent | `addRule`, existing `(account,goods,keyword,is_fallback=0)` found | returns the existing rule (idempotent) | `KeywordReplyServiceImpl.java:47-50` |
| absent | `addRule` normal path | `match_mode=1`, `is_fallback=0` (hardcoded) | `:52-58` |
| absent | `ensureFallbackRule`, none exists | `keyword="__fallback__"`, `is_fallback=1`, `match_mode=1` | `:105-111` |
| existing fallback | `ensureFallbackRule` | returns rule + contents | `:99-103` |
| any | `updateMatchMode(ruleId, n)` | `match_mode=n` — **n unvalidated** | `:88-95` |
| any | `updateKeyword(ruleId, kw)` | `keyword=kw` — no uniqueness pre-check, no sentinel rejection | `:78-85` |
| any | `deleteRule` | contents deleted then rule deleted | `:72-75` |
| any | `deleteContent` (last content) | rule survives with **zero contents** | `:152-154` |
| matched | `matchKeyword` exact ∪ fuzzy (dedup by id) | returns matches with contents | `:164-185` |
| no match | fallback exists **and has contents** | returns fallback | `:187-193` (guard `contents != null && !contents.isEmpty()` at `:190`) |
| no match | fallback exists with **no** contents | empty list | `:193-195` |

**Enforcement.** Only the DB: `unique_keyword` + `uk_keyword_rule` prevents two fallbacks and duplicate keywords;
`ON DELETE CASCADE` removes contents (`V1:347`). Java-level: `addRule`'s duplicate check (`:47`) is a read-then-insert
that relies on the unique key to win the race (a losing insert throws a raw duplicate-key exception — the controller
wraps it as `"添加关键词规则失败: …"`, `KeywordReplyController.java:42-45`). `updateMatchMode` has **no** validation.

**Defects and risks**

1. **`match_mode` has orphan values that match nothing.** `updateMatchMode` accepts any `Integer`
   (`KeywordReplyController.java:73-84` → `KeywordReplyServiceImpl.java:88-95`); only `2` is read by `matchExact`
   (`mapper:21`) and only `1` by `matchFuzzy` (`mapper:24`). A rule set to `3` (or `null`) is listed by `getRules`
   (`:33-43`) and looks configured, but is matched by **neither** query — a silent dead rule. `addRule` always hardcodes
   `1` (`:56`), so exact-match rules can only be produced through this unvalidated endpoint.
2. **`1` means different things in two dimensions.** `match_mode=1` = fuzzy and `is_fallback=1` = fallback; both are
   `INT` flags with the same literal, and `XianyuKeywordReplyRuleMapper:24` combines them
   (`match_mode = 1 … AND is_fallback = 0`). Any future "mode 1 = exact" renumbering (cf. `V8` for auto-rate) will
   silently change matching semantics.
3. **Fallback is double-encoded and collides.** Fallback-ness is `is_fallback=1` *and* `keyword='__fallback__'`
   (`:108`, `:117`). Because `unique_keyword` uses `'__FALLBACK__'` upper-case (`V1:331`) under a
   case-insensitive collation (`utf8mb4_unicode_ci`, `V1:337`), a user who types `__fallback__` as a normal keyword
   collides with the fallback row's generated key and gets a duplicate-key failure instead of a friendly validation
   error. Nothing rejects the sentinel in `addRule` (`:47-58`) or `updateKeyword` (`:78-85`).
4. **Empty-keyword catch-all.** `addRule`/`updateKeyword` do not reject blank keywords; `matchFuzzy` uses
   `#{message} LIKE CONCAT('%', keyword, '%')` (`mapper:24`), so `keyword=''` matches **every** message. The client does
   not guard this either (`KeywordReplyController.java:39`). One blank rule silently shadows the fallback for all
   traffic.
5. **Rule with zero contents = invisible disable.** `deleteContent` (`:152-154`) allows deleting the last content; the
   rule still matches (`:180-185` returns it with an empty list) and the fallback branch explicitly refuses empty
   content (`:190`). Result: a listing can have "keyword reply enabled" (SM-03 flag) with rules that produce nothing,
   and the operator sees the rules in the UI.
6. **`ensureFallbackRule` is a create-or-get without a transaction.** It is not `@Transactional`, so two concurrent
   calls race on the unique key; the loser throws and the controller reports failure even though a fallback now exists
   (`:98-111`).
7. **Enable-state is split across tables.** `isKeywordReplyEnabled` reads `xianyu_goods_config` (`:199-210`) and returns
   `false` on **any** exception (`:206-208`), so a DB hiccup silently disables keyword replies repo-wide rather than
   surfacing an error.

---

## SM-05 — SKU / attribute snapshot sync ("epoch" by deletion)

**Purpose.** Mirror the platform's SKU list and property list for a listing and keep `xianyu_goods.sku_count` in step;
SKU identity is used later to key auto-delivery configs.

**State set (exact literals).** No status column. The implicit state is *presence* of rows plus a count:
`xianyu_goods_sku` (delete = absent), `xianyu_goods_sku_property`, and `xianyu_goods.sku_count` (`V1:74`, default 0).
Three distinguishable situations are collapsed into that shape: “platform says no SKUs”, “platform response truncated /
unparseable”, “we never fetched”.

**Storage.** `xianyu_goods_sku` (`V1:350-371`, unique `(tenant, xy_goods_id, sku_key)` after `V3:48`),
`xianyu_goods_sku_property` (`V1:373-389`, unique `(tenant, xy_goods_id, property_id, value_id)` after `V3:49`),
`xianyu_goods.sku_count`.

**Transitions**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| n SKUs | detail sync parsed a non-empty SKU list | delete-all then insert-all, `sku_count=n` | `ItemDetailSyncServiceImpl.java:177-186` → `GoodsSkuServiceImpl.saveSkus:72-84` → `GoodsInfoServiceImpl.updateSkuCount:373-395` |
| n SKUs | detail sync saw an **explicitly empty array** | delete SKUs + delete properties + `sku_count=0` | `ItemDetailSyncServiceImpl.java:187-193` |
| n SKUs | detail sync parsed `skuList` empty but `skuDataNode` not an empty array (truncation/parse failure) | **no change** (deliberate) | `ItemDetailSyncServiceImpl.java:187` comment “避免异常或截断响应误删现有规格” |
| n SKUs | `ItemServiceImpl.fetchDetailFromApi` path | delete-all + insert, `sku_count` updated **only if** `accountId != null` | `ItemServiceImpl.java:689-697` |
| any | properties exist but SKU list empty | properties **never refreshed** | `ItemDetailSyncServiceImpl.java:183-186` (guarded inside `if (!skuList.isEmpty())`) |
| any | `syncSingleItem` (same `fetchAndSaveDetail`) | same transitions, no progress record | `ItemDetailSyncServiceImpl.java:229-241` |

**Enforcement.** None, and worse: `saveSkus` is `@Transactional` (`GoodsSkuServiceImpl.java:71`) but
`updateSkuCount` is a **separate** transaction (`GoodsInfoServiceImpl.java:372`), and both are called from a
non-transactional sync method. A crash between them leaves SKU rows replaced with a stale `sku_count`. There is also no
diffing: identity (`id`) is destroyed on every sync because rows are deleted and re-inserted.

**Defects and risks**

1. **Destructive snapshot with no epoch/version.** `saveSkus` (`:72-84`) deletes every SKU row for the listing and
   re-inserts. Any consumer holding a `sku.id` (the value reflected into `xianyu_goods_sku.id`, `V1:351`) sees it
   vanish; per-SKU delivery configs survive only because they key on the *remote* `sku_id`
   (`XianyuGoodsAutoDeliveryConfigMapper.java:19`), not on the local row id. A partial/truncated platform response that
   still parses to ≥1 SKU silently replaces a complete list with a subset — the truncation guard at
   `ItemDetailSyncServiceImpl.java:187` only protects the *zero*-SKU case.
2. **`sku_count` can lie in both directions.** `updateSkuCount` returns `false` when the goods row is missing
   (`:380-383`), and the caller then aborts the rest of the item's sync (`ItemDetailSyncServiceImpl.java:180-182`,
   `:191-193`), so SKUs may be written while the count is not; conversely `ItemServiceImpl.java:690-697` updates the
   count only when `accountId != null`.
3. **Properties are only synced when SKUs are non-empty.** A listing with properties but no SKU rows can never
   refresh `xianyu_goods_sku_property` (`:183-186`), leaving stale attributes that the delivery/description rendering
   reads.
4. **Two duplicate sync entry points.** `ItemServiceImpl.fetchDetailFromApi` (`:689-697`) and
   `ItemDetailSyncServiceImpl.fetchAndSaveDetail` (`:176-194`) implement *different* rules for the same data (the
   former deletes SKUs only when the parsed list is non-empty but never handles the explicit-empty case; the latter
   does). A refresh followed by a `getItemDetail` call can therefore oscillate the SKU table between two shapes.
5. **No "in progress" state.** Two concurrent syncs for one listing interleave `delete-all`/`insert-all` in separate
   transactions; with no lock or version, the second delete can run between the first's delete and insert, producing a
   duplicate-key failure (`uk_goods_sku_tenant_remote`) that aborts one of the two transactions mid-way.

---

## SM-06 — Credential (cookie) status — writers inside the audited files

**Purpose.** Whether the stored `_m_h5_tk`-bearing cookie jar can be used for platform calls; drives reconnection
(`XianyuAccountMapper.selectReconnectableAccounts`), diagnostics, and re-login prompts.

**State set (exact literals).** `1` = 有效/valid, `2` = 过期/expired, `3` = 失效/invalid (risk-control).
Declared in `AccountService.java:98-111` and `entity/XianyuCookie.java:41` (comment `Cookie状态 1:有效 2:过期 3:失效`),
rendered in `WebSocketController.java:439-453` (`✅ 有效` / `⚠️ 已过期` / `❌ 已失效` / `未知状态(n)`).

**Storage.** `xianyu_cookie.cookie_status`. Desync risk: the *account* is a second, independent machine
(`xianyu_account.status`: `1` normal, `-1` needs phone verification — `entity/XianyuAccount.java` field comment) and the
two are written by different services, so "cookie valid but account blocked" is a normal, non-exceptional combination.

**Transitions (only the writers reachable from this audit's files)**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| any | detail-list API returned a token-expired error | `2` | `ItemServiceImpl.java:145-155` (guard `e.getMessage().contains("令牌已过期")`, write `:149`) |
| any | CDN upload returned the sentinel string `"COOKIE_EXPIRED"` | `2` | `ImageUploadServiceImpl.java:78-81` |
| any | WS token refresh decided the cookie is unusable | `2` (notifying) / `3` (risk) | `WebSocketTokenServiceImpl.java:554`, `:401`, `:472` (out of scope, listed for completeness) |
| `1` | CDN answered `301/302` or HTML login page | `2` via `:79` | `ImageUploadServiceImpl.java:214-216`, `:231-234` |
| `2` | successful re-login / cookie refresh | `1` | `AccountServiceImpl.java:129`, `:138`; `CookieRefreshServiceImpl.java:584` |

**Enforcement.** None at the write site: `AccountServiceImpl.updateCookieStatus` (`:410-450`) always writes the supplied
value (`:427`) and only *reacts* to `2` (mail dedupe at `:434`, `:443`); there is no allowed-transition matrix, so
`3 → 1` and `1 → 1` are both silent, and `2 → 3` requires nothing. The only real gate is downstream:
`XianyuAccountMapper.java:18-22` requires `account.status = 1 AND cookie.cookie_status = 1`.

**Defects and risks**

1. **State smuggled through a return value.** `ImageUploadServiceImpl.uploadToGoofishCDN` returns either a URL or the
   literal `"COOKIE_EXPIRED"` (`:216`, `:233`); `uploadImage` checks that sentinel *after* first treating a null result
   as a generic failure (`:74-81`). Any caller other than `uploadImage` would happily store `"COOKIE_EXPIRED"` as a CDN
   URL. The type is `String`, so the compiler cannot help.
2. **`2` is written by side-effect paths with no retry/verify step.** Both `ItemServiceImpl.java:149` and
   `ImageUploadServiceImpl.java:79` downgrade the credential on a *single* observed error, with no distinction between
   "token rotated" (recoverable by `TokenRefreshServiceImpl`) and "password/session gone". `2` is thus over-produced,
   and the only way back to `1` is a successful refresh/login elsewhere.
3. **No `unknown`/`refreshing` state.** While a refresh is in flight the status stays whatever it was, so concurrent
   API calls keep failing against a cookie that is being replaced; diagnostics (`OperationsDiagnosticsService:42-47`)
   counts `cookie_status <> 1` as an account warning, so this shows up as a fault even when self-healing a second later.
4. **String/boolean corruption is possible via backup restore.** `AccountBackupHandler.java:119` writes
   `cookieStatus` from arbitrary JSON (`map.get("cookieStatus")`), so any integer (e.g. `0`) can enter the column; the
   renderer then shows `未知状态(0)` (`WebSocketController.java:451`) and `selectReconnectableAccounts` treats it as
   non-connectable — an unreachable state produced by data import.
5. **Account status is a second, unsynchronized axis.** `xianyu_account.status` is `1`/`-1` (`entity`, plus writers at
   `AccountServiceImpl.java:129/138`, `CookieRefreshServiceImpl.java:584`, `WebSocketTokenServiceImpl.java:842`) and
   diagnostics counts `status <> 1` *and* `cookie_status <> 1` into one bucket (`OperationsDiagnosticsService:37-47,
   149`), so an operator cannot tell which of the two machines is at fault.

---

## SM-07 — Platform publish / listing (with local-orphan state)

**Purpose.** Create, delist, relist and delete a listing on goofish; mirror the result locally.

**State set (exact literals).**

- Platform: unpublished → published (`itemId`) → 下架 (downshelf) → deleted. There is **no local enumeration** of this.
- Local projection: `localSynced` boolean, computed per publish (`PlatformPublishService.java:118-124`) and returned in
  the response map only (`:131`). Values: `true`, `false`. Never persisted.
- Platform-side idempotency nonce: `uniqueCode = String.valueOf(System.currentTimeMillis())` — regenerated on **every**
  attempt (`:609` in `buildPublishData`, `:438` for category recommendation).
- Local goods row status: `0` forced on success (`GoodsInfoServiceImpl.java:133`) and never updated by
  `changeListingStatus`/`delete` (see SM-02).

**Storage.** Platform state is external; the only local evidence is the presence of the `xianyu_goods` row plus its
`status`. `localSynced=false` exists solely in the HTTP response (`PlatformPublishService.java:125-132`) — an orphaned
platform listing is a state the database cannot express.

**Transitions**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| none | `publish` → validation, category, address, image upload | payload ready | `:72-106` |
| payload ready | risk guard `ITEM_PUBLISH` (`requirePermit`) | permit or `RiskGuardBlockedException` | `:108`, `:252-257` |
| permitted | `mtop.idle.pc.idleitem.publish` success | published on platform | `:109-113` |
| published | response contains numeric `itemId` | remote id known | `:114-117` (blank ⇒ `IllegalStateException`) |
| remote id known | local `savePublishedGoods` ok | `localSynced=true` | `:118-120`, `:146-156` |
| remote id known | local save throws | **`localSynced=false`, return success=true anyway** | `:121-124`, `:125-132` |
| published | `changeListingStatus(onSale=false)` | platform 下架, local untouched | `:221-229` |
| 下架 | `changeListingStatus(onSale=true)` via Playwright | platform 上架, local untouched | `:230-249` |
| published/下架 | `delete` step 1 `downshelf` | platform 下架 | `:196-202` |
| 下架 | `delete` step 2 `com.taobao.idle.item.delete` | platform deleted | `:203-211` |
| 下架 | step 2 fails | **stuck: 下架 but not deleted, exception thrown** | `:209-211` ("商品已下架，但平台删除失败") |

**Enforcement.** Nothing structural. The only preconditions are the risk-guard permit (`:108`, `:196`, `:220`) and
input validation (`:643-666`). Idempotency is *not* enforced here at all — see SM-08, where the only request-level
idempotency lives.

**Defects and risks**

1. **`localSynced=false` is a state with no persistence and no recovery path.** On a local write failure the listing
   exists on the platform with no local row (`:121-124`). The response still reports `"success": true` (`:126`), so the
   client sees a success and will never retry; the orphan is invisible until a later page refresh happens to import it.
   The comment at `GoodsInfoServiceImpl.java:136` ("失败时保留远端商品ID供人工恢复") acknowledges the intent, but the id is
   only in the response body/log.
2. **Two-phase delete with no compensation.** `delete` performs downshelf then delete (`:196-211`); if the second call
   fails the item is left 下架 on the platform with no local record that a delete was attempted, and the thrown message
   is the only trace. Re-running `delete` restarts at downshelf (idempotent) but `changeListingStatus(onSale=false)` and
   `delete` are separate endpoints with no shared state, so a partial `delete` looks identical to a completed
   `changeListingStatus`.
3. **Local status is never updated by listing transitions.** `changeListingStatus` (`:215-250`) and `delete`
   (`:190-213`) never touch `xianyu_goods`; after a successful 下架 the goods table still says 在售 until another
   refresh runs (SM-02), and after a successful platform delete the local row remains (only `/api/items/delete` removes
   it, SM-02 defect 6).
4. **`uniqueCode` is not an idempotency key.** It is `currentTimeMillis()` (`:609`) and is regenerated per call, so a
   client retry after a timeout creates a *second* listing (or a platform-side duplicate rejection) — the platform gets
   no stable "this is the same publish request" signal.
5. **Success detection is shallow.** `publish` accepts any successful mtop response that yields a numeric id
   (`:111-117`); it never verifies the returned listing state, and `ensureLoggedIn` (`:714-720`) is only used on the
   relist path, so an expired cookie surfaces as a generic "平台拒绝发布" rather than a credential-state transition
   (SM-06).
6. **Relist relies on page text.** `changeListingStatus(onSale=true)` navigates a real browser and matches
   `button:has-text("上架")` / `"确定"` (`:237`, `:243`); a UI wording change makes the transition permanently
   unavailable with no fallback, and the code cannot distinguish "already on sale" from "button missing"
   (`:239-241` throws the same error for both).

---

## SM-08 — Merchant task queue and publish idempotency (`merchant_task`)

**Purpose.** Durable queue that executes SELECT / PUBLISH / DELETE / COLLECT / COMPENSATE / REFRESH_PROMOTION /
WORKFLOW / BARGAIN_FREE_SHIPPING / CONFIRM_SHIPMENT jobs with retries, backoff and (for publish) request-level
idempotency.

**State set (exact literals).**

- `status` INT: `0` 待执行/waiting, `1` 执行中/running, `2` 成功/succeeded, `-1` 失败/failed
  (`MerchantOperationsService.java:439`, `:495`, `:649`; mapper guards `MerchantTaskMapper.java:34`, `:37-44`, `:47`,
  `:50`, `:54`, `:57`; client labels `vue-code/src/views/operations/index.vue:60`, `automation/index.vue:47`)
- `attempt_count` INT, `max_attempts` INT (`1` for PUBLISH, `12` for platform order tasks, `3` otherwise —
  `MerchantOperationsService.java:443-445`, `:498`, `:652`)
- `next_retry_time` DATETIME (only meaningful for `-1`), `scheduled_time` DATETIME (only meaningful for `0`)
- `request_key` VARCHAR(64) (nullable) with `uk_task_tenant_type_request (tenant_id, task_type, request_key)`
  (`V14__publish_request_idempotency.sql:1-3`)
- `result_json`, `error_message` (`complete` clears the error, `MerchantTaskMapper.java:47`)

**Storage.** `merchant_task` (durable). Desync risk: the "lease" is the `updated_time <= NOW()-10min` predicate
(`MerchantTaskMapper.java:38-44`) — there is no owner column, so a worker that stalls and later resumes cannot be
distinguished from the worker that took over.

**Transitions**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| (absent) | `createTask` / `batchPublish` / `scheduleDueRules` | `0`, `attempt=0` | `MerchantOperationsService.java:425-450`, `:525-548`, `:641-658` |
| (absent) | `enqueuePlatformOrderTask` (request_key = `accountId:orderId`) | `0`, `attempt=0`, `max=12` | `:468-502` |
| `0`/`-1` | `claim` — guard `status IN (0,-1) OR (status=1 AND updated_time<=NOW-10min)` | `1`, `attempt+1` | `MerchantTaskMapper.java:43-45` |
| `1` (stale >10 min) | `claim` again | `1`, `attempt+1` (second owner) | `:38-44` |
| `1` | `complete` — **no status guard** | `2`, `result_json`, error cleared | `:47-48` |
| `1` | `fail` — **no status guard** | `-1`, `error_message`, `next_retry_time` | `:50-52` |
| `-1` | `requeue` — **no status guard**, resets `attempt_count=0` | `0` | `:54-55`; called from `:634-639` (HTTP) and `:982-983` (compensation) |
| `0` | `defer` (risk guard) — `attempt_count = GREATEST(attempt_count-1,0)`, `scheduled_time=retryAt` | `0` | `:57-60`; caller `MerchantOperationsService.java:695-700` |
| `-1` | due again when `attempt_count < max_attempts AND next_retry_time <= NOW` | claimed | `:37-41` |
| `2` | — (terminal: only manual `requeue` leaves it) | — | `:34` |
| (absent) | HTTP publish with `requestId` already present | returns the existing task's result, no re-execution | `MerchantOperationsService.java:367-372`, `:504-522` |

**Enforcement.** Real but one-sided:

- entry is guarded (`claim` at `:43-45` is a proper compare-and-set; `selectDue` `:37-41` implements the retry gate);
- **completion is not guarded** — `complete`/`fail`/`requeue` update by `id` only (`:47`, `:50`, `:54`), so the lease
  is advisory;
- publish idempotency is enforced only at the HTTP entry (`:368-371`) via `request_key` + the DB unique index.

**Defects and risks**

1. **Lost lease, then double completion.** `selectDue` re-claims a task running longer than 10 minutes (`:39`), and the
   first worker is not fenced: when it finally calls `complete` (`:47`) it overwrites the second worker's `-1` (or the
   second worker's `2`). For PUBLISH this can turn a real success into a recorded failure or vice versa.
2. **`requeue` is an unguarded time machine.** `POST /api/operations/tasks/{id}/requeue`
   (`MerchantOperationsController.java:187-195` → `MerchantOperationsService.java:634-639`) calls `requeue` with no
   status predicate (`MerchantTaskMapper.java:54`), so it can reset a **succeeded** (`2`) PUBLISH task to `0`. The
   `max_attempts=1` PUBLISH guard (`:443`) only limits *automatic* retries; a requeued task runs `executePublish` again
   and creates a duplicate platform listing. The `request_key` idempotency row is reused, so the DB cannot detect it
   either.
3. **The `max_attempts=1` design is a dead end.** With `1`, a publish that fails once (`-1`) can only be retried by
   the manual requeue of (2); the HTTP layer instead refuses forever with
   `"同一发布请求已失败，请在任务中心确认远端结果后手动处理"` (`:504-522`). There is no "verify remote then mark
   succeeded" transition — the `COMPENSATE` task type (`:982-983`, `executeCompensation`) is the only reconciliation and
   it *requeues the original task* rather than adopting the remote id.
4. **Two timers for one phase.** Waiting tasks are due by `scheduled_time` (`:37`), failed tasks by `next_retry_time`
   (`:38`); `defer` writes `scheduled_time` while `fail` writes `next_retry_time` (`:50-60`). Nothing keeps them
   mutually exclusive, so a deferred task that then fails carries a stale `scheduled_time`; `requeue` (`:54`) sets
   `scheduled_time=NOW()` but leaves `next_retry_time` non-null until `complete`.
5. **Risk-guard deferral never consumes attempts.** `defer` decrements `attempt_count` (`GREATEST(attempt_count-1,0)`,
   `:57-58`), so a platform rate-limit loop can defer a task indefinitely; `selectDue` keeps returning it because
   `status=0 AND scheduled_time<=NOW`. `countPendingPlatformActions` (`:62-65`) then reports a permanently non-zero
   backlog.
6. **Backoff uses a stale attempt number.** `executeTask` computes
   `attempt = task.getAttemptCount()+1` from the object loaded *before* `claim` incremented the DB row
   (`MerchantOperationsService.java:704-705` vs `MerchantTaskMapper.java:43`), so the first failure waits
   `min(60, 2*5)=10` minutes instead of 5, and the second 15 instead of 10.
7. **Publish without `requestId` has no idempotency at all.** `requestKey` is nullable (`MerchantOperationsService.java:394`,
   `:436`), `selectByRequestKey` is only consulted for non-blank keys (`:368-369`), and MySQL allows multiple NULLs in
   the unique index (`V14:3`). `batchPublish` never sets a key (`:525-548`), so a double-clicked batch creates duplicate
   PUBLISH tasks — each of which will publish.
8. **Recurring rules duplicate work.** `scheduleDueRules` inserts a *new* task per rule firing (`:641-658`) with no
   check for an already-open task for the same resource, so a rule whose previous PUBLISH task is still `0`/`1`
   accumulates tasks that publish the same material repeatedly.
9. **No cancelled state.** There is no way to express "operator abandoned this task"; the only exits are `2`/`-1`, and
   `-1` is re-claimable (`:43`) — so "cancel" today means "requeue forever" or "delete the row".
10. **Result erasure.** `complete` clears `error_message` (`:47`) and `result_json` is the only evidence of what was
    published; `fail` does not record a redacted request, so post-mortem of a duplicate-publish incident depends on
    logs.

---

## SM-09 — Kami inventory item (`xianyu_kami_item.status`)

**Purpose.** One-time secret inventory (card keys) with reservation/commit semantics for auto-delivery.

**State set (exact literals).** `0` AVAILABLE, `1` DELIVERED, `2` RESERVED, `3` REVIEW_REQUIRED —
`enums/KamiStatus.java:7-10` and DDL comment `COMMENT '0可用 1已交付 2已预占 3待核对'` (`V1__baseline.sql:289`).
A second, independent field carries the order binding: `order_id`, with `reserved_time`/`used_time` stamps (`V1:290-292`).
A third, effectively dead state field: `xianyu_kami_usage_record.delivery_status VARCHAR(24) DEFAULT 'RESERVED'`
(`V1:310`) — the only value Java ever writes is `DELIVERED` (`KamiConfigServiceImpl.java:418`).

**Storage.** DB, durable, plus the denormalized counters `xianyu_kami_config.total_count` / `used_count`
(`V1:275-276`) and the JVM map `stockOutEmailSentTime` (`KamiConfigServiceImpl.java:72`). Desync risk: those counters
are not derived views — they are re-computed only at four call sites (`:210`, `:252`, `:303`, `:426`) and drift during
reserve/release/review transitions.

**Transitions**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| (absent) | manual add / batch import | `0` | `KamiConfigServiceImpl.java:205`, `:247` |
| (absent) | external API provision (source_type=API) | `2` inserted directly | `ExternalKamiProvisionService.java:136-145` |
| `0` | `markUsed(id, orderId)` — guard `status = 0` | `1` + `order_id`,`used_time` | `XianyuKamiItemMapper.java:54-55` |
| `0` | `reserveKami` → `lockAvailable … FOR UPDATE` then `reserve` — guard `status = 0` | `2` + `order_id`,`reserved_time` | `KamiConfigServiceImpl.java:357-393`; mapper `:60-63`, `:76-79` |
| `2` | `commitReservation` — guard `order_id=? AND status = 2` | `1` + `used_time`; usage record `DELIVERED` | mapper `:81-82`; service `:397-430` |
| `2` | `releaseReservation` — guard `status = 2` **AND `config.source_type='LOCAL'`** | `0`, order cleared | mapper `:84-87`; service `:436-440` |
| `2` | `markReservationReviewRequired` — guard `status = 2` | `3` | mapper `:89-90`; service `:444-447` |
| `1` or `3` | `resetKamiItem` → `markUnused` — guard `status IN (1,3)` | `0` (order/used cleared) | `KamiConfigServiceImpl.java:313-325`; mapper `:57-58` |
| any | `deleteKamiItem` — **no status guard** | row gone | `KamiConfigServiceImpl.java:290-311` |
| `1` | `xianyu_kami_usage_record` insert | side effect | `:410-421` |

**Enforcement.** Genuinely good for the local path: every transition is a conditional `UPDATE … WHERE status = <expected>`
and the caller compares the affected rows (`reserve` count check `KamiConfigServiceImpl.java:385-388`,
`commitReservation` `:404-406`, `resetKamiItem` `:316-319`), plus `SELECT … FOR UPDATE` on both the config row
(`XianyuKamiConfigMapper.java:14-15`) and the candidate items (`XianyuKamiItemMapper.java:60-67`).

**Defects and risks**

1. **`RESERVED` is a trap state for API-sourced warehouses.** `releaseReservation`'s `JOIN … AND config.source_type =
   'LOCAL'` (`mapper:84-87`) intentionally skips API items, so a release for an API config leaves items at `2` forever.
   `hasUnsettledSupply` counts `status IN (2,3)` (`mapper:48-49` + `KamiConfigServiceImpl.java:619-622`) which then
   permanently blocks config update and delete (`:87`, `:176`) — and `resetKamiItem` cannot clear it because
   `markUnused` only accepts `1,3` (`mapper:57`). The only escape is deleting the item directly (`:290-311`), which is
   the same operation the guard was protecting against.
2. **`REVIEW_REQUIRED` is writable back to `AVAILABLE` with no resolution record.** `resetKamiItem` accepts `3`
   (`mapper:57`) and clears `order_id`, so an item whose delivery outcome is unknown can be recycled into stock; the
   only trace of "this key may already have been shown to a buyer" is the external-request/ack history, which exists
   only for API warehouses.
3. **`deleteKamiItem` bypasses every guard.** `KamiConfigServiceImpl.java:290-311` deletes any item regardless of
   status, without `hasUnsettledSupply`, and re-computes counters. Deleting a `2` leaves the order's delivery pointing at
   a vanished key; the usage-record FK was relaxed to `ON DELETE SET NULL` (`V17__preserve_kami_usage_history.sql:2-6`),
   so history survives but the key itself does not.
4. **Counters drift.** `refreshConfigCounts` (`:495-503`) sets `total_count = COUNT(*)` and `used_count = COUNT(status=1)`
   and is only called on add/import/commit/delete (`:210`, `:252`, `:303`, `:426`). Reserve/release/review do not
   refresh, so `total_count` includes reserved+review items while `used_count` excludes them; `checkAndSendAlert`
   (`:544-585`) mixes the live `countUnused` with the stale `totalCount` (`:571-575`), so the percentage threshold
   alert (`alert_threshold_type = 2`) can fire or stay silent based on unrelated traffic.
5. **`acquireKami` collapses four states into `null`.** `KamiConfigServiceImpl.java:327-333` catches `BusinessException`
   and returns null: "no stock" (409), "config missing" (404), "reservation conflict" (409) and "quantity mismatch" are
   indistinguishable to the caller.
6. **The 10-minute alert dedupe is a JVM map.** `stockOutEmailSentTime` (`:72`) is `ConcurrentHashMap<Long,Long>`,
   never evicted (leaks per deleted config) and not shared between instances; a restart re-sends immediately, and N
   replicas send N mails per window. `STOCK_OUT_EMAIL_INTERVAL_MS` (`:74`) is hardcoded.
7. **`delivery_status` on the usage record has a dead default.** `'RESERVED'` (`V1:310`) is never written by Java
   (commit inserts `DELIVERED`, `:418`), so the column's declared state set is unachievable — any query that
   filters `delivery_status='RESERVED'` returns nothing, forever.
8. **No expiry sweep for `2`.** Nothing ages out reservations (no scheduler touches `reserved_time`), so an order that
   never commits and never releases pins stock indefinitely; `countUnsettledByConfigId` (`mapper:48`) will keep the
   warehouse "unsettled".
9. **Export silently hides states.** `exportKamiItems` supports only "unused" and "used" (`:481-492` → statuses `0`/`1`),
   with `includeUnused=false, includeUsed=false` returning an empty list — items in `2`/`3` are never exportable, which
   is how the trap states in (1) stay invisible.

---

## SM-10 — External kami supply request (`xianyu_kami_external_request.request_status`)

**Purpose.** Exactly-once-ish reservation against a third-party secret supplier, with retries, an in-flight lease, and a
human review state for ambiguous outcomes.

**State set (exact literals).** `PROCESSING`, `SUCCESS`, `FAILED`, `REVIEW_REQUIRED`
(`XianyuKamiExternalRequestMapper.java:20`, `:37`, `:43`, `:51`, `:59`, `:63`, `:69`; entity field
`XianyuKamiExternalRequest.java:34`; DDL `V9:110`).
Counters/flags: `attempt_count` (start `1`, cap 3 — `mapper:20`, `:49`), `exception_revision` (`V11:7-8`),
`update_time` used as the lease stamp (2-minute staleness — `mapper:52`).

**Storage.** DB. Desync risk: `request_token` (`V9:108`) is the supplier-facing idempotency key, generated fresh on
insert (`ExternalKamiProvisionService.java:158`) and reused on retry because the row is reused — but it is **not**
re-sent on the `claimRetry` path if the insert lost the race (see defect 3).

**Transitions**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| (absent) | `insertIfAbsent` (`INSERT IGNORE`, unique `(tenant,config,order)`) | `PROCESSING`, `attempt=1` | `mapper:14-22`; service `:99` |
| `FAILED` | `claimRetry` — guard `attempt_count < 3 AND (status='FAILED' OR (PROCESSING AND update_time < NOW-2min))` | `PROCESSING`, `attempt+1`, `exception_revision+1`, error cleared | `mapper:41-55`; service `:120-123` |
| `PROCESSING` (stale >2 min) | same `claimRetry` | `PROCESSING`, `attempt+1` | `mapper:52` |
| `PROCESSING` | `markSuccess` — guard `status='PROCESSING'` | `SUCCESS` | `mapper:57-65`; service `:146-148` |
| `PROCESSING` | `markFailure` with `FAILED` (deterministic supplier error) | `FAILED`, `exception_revision+1` | `mapper:67-77`; service `:77-79` |
| `PROCESSING` | `markFailure` with `REVIEW_REQUIRED` (timeout/uncertain/parse error/persist error) | `REVIEW_REQUIRED`, `exception_revision+1` | service `:77`, `:81`, `:84-86` |
| `SUCCESS` | `prepare` sees SUCCESS and `items == quantity` | returns existing items (no call) | service `:108-115` |
| `SUCCESS` | `prepare` sees SUCCESS but counts differ | `409 "外部卡密记录与预占数量不一致，需要人工核对"` — **status unchanged** | service `:114` |
| `REVIEW_REQUIRED` | no automatic transition exists (not claimable: guard requires `FAILED` or stale `PROCESSING`) | stays | `mapper:50-53`, service `:116-118` |

**Enforcement.** The strongest in this codebase: DB-level unique idempotency (`V9:116`), compare-and-set on every
transition, attempt cap in the guard, lease-based recovery, and `exception_revision` bumping so the monitoring layer
(SM-11) can re-open an acknowledged failure.

**Defects and risks**

1. **`REVIEW_REQUIRED` is terminal-by-neglect.** It cannot be retried (`claimRetry` excludes it), cannot be succeeded,
   and the diagnostics ack (SM-11) only hides it. Nothing ever moves it back; the only recovery is deleting the config
   (blocked by `countUnsettledByConfigId`, `mapper:33-39`) or manual SQL. Every uncertain supplier call therefore removes
   an order from automation permanently.
2. **The 2-minute lease is duplicated in three modules.** `mapper:52` (`DATE_SUB(NOW(3), INTERVAL 2 MINUTE)`),
   `OperationsDiagnosticsService.java:106`, `:238`, `:305` and `ExternalKamiProvisionService.java:163-167`
   (`minusMinutes(2)`). Changing the constraint in one place silently desynchronizes provisioning from monitoring — and
   there is no configuration key for it.
3. **`request_token` discontinuity on the retry path.** `createRequest` mints a new UUID token (`:158`) for each
   `prepare` call. When `insertIfAbsent` loses the race (`:99-101`), the method reloads the stored request
   (`:101-102`) — good — but the *gateway* call still receives `request.getRequestToken()` (`:66`), which is the stored
   token only because the row was reloaded. Any future refactor that keeps the freshly created object would break
   supplier-side dedupe.
4. **`markFailure` accepts any status string.** `SET request_status = #{status} WHERE … status='PROCESSING'`
   (`mapper:67-77`) has no whitelist, so a mis-typed caller can write an unknown state that the diagnostics queries
   (`IN ('FAILED','REVIEW_REQUIRED')`, `OperationsDiagnosticsService:105`) will never surface — an invisible stuck row.
5. **Partial-success is recorded as `SUCCESS`.** `saveReservedItems` inserts whatever `contents` the parser returned and
   then marks SUCCESS (`:136-148`) without comparing `contents.size()` to `quantity`; the mismatch is only detected on a
   *later* reservation attempt (`:111-114`), leaving a `SUCCESS` row whose invariant is violated and an order short of
   keys.
6. **Transaction boundary around the supplier call.** The "result is uncertain" window is unavoidable (external call
   between two transactions, `:64-75`), and the design correctly funnels it to `REVIEW_REQUIRED`; but there is no
   outbox/compensation that re-reads the supplier to resolve it — resolution is 100% human.

---

## SM-11 — Exception acknowledgement and operations health

**Purpose.** Give operators a "seen/handled" lifecycle for failures produced by *other* machines (delivery, auto-reply,
external supply) without mutating the source rows, and roll those into a single health verdict.

**State set (exact literals).**

- Source-side states (read-only inputs): `xianyu_goods_order.delivery_status ∈ {FAILED, REVIEW_REQUIRED}`,
  `xianyu_goods_auto_reply_record.state = -1`, `xianyu_kami_external_request.request_status ∈ {FAILED,
  REVIEW_REQUIRED}` or stale `PROCESSING` (`OperationsDiagnosticsService.java:48-115`, `:198-239`).
- Acknowledgement: **presence of a row** in `xianyu_exception_acknowledgement` keyed by
  `(tenant_id, exception_type, source_id, source_version)` (`V11:10-18`); `exception_type ∈ {DELIVERY, AUTO_REPLY,
  EXTERNAL_SUPPLY}` (`:344`).
- Version coupling: `source_version = exception_revision` (`:57`, `:69`, `:81`, `:113`), where `exception_revision` is
  bumped only on failure-ish transitions (`XianyuGoodsOrderMapper.java:140/145/150/181/200/275`,
  `XianyuGoodsAutoReplyRecordMapper.java:28`, `XianyuKamiExternalRequestMapper.java:45/71`).
- Overall verdict: `CRITICAL` / `WARNING` / `HEALTHY` (`OperationsHealthEvaluator.java:11-16`); per-check
  `WARNING` / `HEALTHY` (`OperationsDiagnosticsService.java:358`).

**Storage.** Two places that must stay consistent: `exception_revision` (on 3 source tables, `V11:1-8`) and the ack
table (append-only). Desync risk: if any writer forgets to bump `exception_revision` when re-entering a failure state,
the old ack keeps hiding the new failure.

**Transitions**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| failure state, revision N | ack request | ack row `(type,id,N)` inserted | `:279-311` |
| failure state, revision N | ack when a row already exists | 0 inserted → returns 0 (or throws if `failWhenMissing`) | `:312-322` |
| ack row exists, revision N | source re-enters failure (revision N+1) | state is **unacknowledged again** | logic at `:57` vs bump sites listed above |
| ack row exists, revision N | source recovers (`delivery_status` no longer FAILED) | not listed; ack row remains orphaned | `:48-59` |
| failure state | ack target no longer failing | `IllegalArgumentException("异常不存在或状态已恢复")` | `:318-320` |
| any | `GET /api/diagnostics/overview` | `CRITICAL` if `criticalCount>0`, else `WARNING` if `warningCount>0`, else `HEALTHY` | `OperationsHealthEvaluator.java:11-16`; counts at `:161-163` |

**Enforcement.** Good for the ack write: `INSERT IGNORE … SELECT … WHERE <still failing> AND revision = ?`
(`:280-307`) makes ack both idempotent and version-fenced, and a stale acknowledgement cannot hide a newer failure.

**Defects and risks**

1. **Acknowledgement is an unbounded, never-pruned side table.** There is no `deleteOldLogs`-style cleanup for
   `xianyu_exception_acknowledgement` (compare `OperationLogServiceImpl.deleteOldLogs:120-135`); one row per
   acknowledged (type, source, revision) accumulates forever, including for rows whose source is long gone (the FK-less
   design in `V11:10-18` allows this).
2. **Acknowledging ≠ resolving, and there is no ack expiry.** `acknowledgeException` only inserts (`:310-311`); the
   order stays `REVIEW_REQUIRED`/`FAILED` and disappears from `exceptions()` (`:198-247`) and from the overview counts
   (`:48-115`). If the underlying condition cannot change `exception_revision` (e.g. a `REVIEW_REQUIRED` order that
   nobody retries), the failure is permanently invisible while still unfulfilled — a hidden-latent-failure state.
3. **`exception_revision` is a counter with non-uniform semantics.** Some writers bump it conditionally
   (`IF(#{state} = -1, 1, 0)`, `XianyuGoodsOrderMapper.java:140`), one bumps unconditionally
   (`markTaskReviewRequired`, `:200`), one bumps for `IN ('FAILED','REVIEW_REQUIRED')` (`:181`). A transition that
   should re-open an exception but does not bump (e.g. `retryOrFailTask` back to `FAILED` from `RETRY_WAIT` with a
   status the expression excludes) leaves it acknowledged. The mapping from "state changed" to "revision changed" lives
   in SQL strings, not in one method.
4. **`DELIVERY` and `AUTO_REPLY` are versioned but `NOTIFICATION`/`VERSION` are not.** The check list mixes machines with
   very different lifecycles (`:146-160`): notification failures are a rolling 24-hour count (`:116-121`) with no ack at
   all, while delivery failures need an ack row to disappear. `OVERALL` therefore compares incomparable counters.
5. **`DATABASE` check is fake.** `check("DATABASE", "数据库服务", 0, "检查数据库连接")` (`:147`) hardcodes `count = 0`, so
   the check is always `HEALTHY` when it is rendered at all (and if the DB were down, the whole endpoint fails before
   rendering). Any monitoring built on `checks[].status` inherits a permanently healthy database row.
6. **`CRITICAL` is unreachable for most checks.** `criticalCount` only sums `deliveryFailed + deliveryReview +
   externalReview` (`:161`); `VERSION` and `UPDATE_AGENT` (which are the only "fatal-class" checks) are folded into
   `warningCount` (`:162-163`), so a broken update agent can never produce `CRITICAL`.
7. **Two vocabularies for the same field.** Per-check `status` is `WARNING`/`HEALTHY` (`:358`) while `overallStatus` is
   `CRITICAL`/`WARNING`/`HEALTHY`; a consumer that maps check statuses to colors will not find `CRITICAL` and a
   consumer that maps overall to `FAILED` will not find it either.
8. **`send_status = 0` and `OperationConstants.Status.FAIL = 0` collide.** `NotificationCenterService.java:199`
   writes `1` for success and `0` for failure (`:205`) — the inverse of the `sys_user`/flag convention (1=on) — and the
   same literal `0` means `FAIL` in `OperationConstants.java:80` but `disabled` for `sys_user.status`. Nothing in the
   diagnostics query documents which convention applies.
9. **Overview runs 7–9 aggregate queries per request** (`:37-124` plus per-account loops at `:122-126`), each a full
   `COUNT` over the tenant's rows with correlated `NOT EXISTS` subqueries, and it is called from a GET with no cache.
   The health *model* is stateful only through the ack table, but the cost is per-poll.

---

## SM-12 — Platform user, role and permission

**Purpose.** Admin/tenant accounts for the console: enable/disable, role, explicit permission rows, and login-token
revocation on identity change.

**State set (exact literals).**

- `sys_user.status`: `1` 正常/enabled, `0` 禁用/disabled (`entity/SysUser.java:31-32`; normalized in
  `PlatformUserService.java:60` — anything that is not `0` becomes `1`)
- `sys_user.role`: `ADMIN` / `USER` (`SysUser.java:16-17`; normalized `:157-165`)
- permissions: rows in `sys_user_permission`, code set from `PermissionCatalog` (`:11-36`, `:66-68`);
  **ADMIN has zero rows by design** (`PlatformUserService.java:68-69`) and is granted all codes at read time
  (`PlatformPermissionService.java:59-61`, `:69-71`)
- tokens: rows in `sys_login_token` (hard-deleted, no `revoked` flag — `entity/SysLoginToken.java:13-39`,
  `PlatformUserService.java:184-187`)

**Storage.** Three tables + `Authentication`/interceptor reads. Desync risk: role and permission rows are written in the
same transaction (`:57-74`) but read on every request (`PlatformPermissionService.java:72-73`, deliberately, per its
comment), so there is no cache to desync — instead there is a **token** desync: deleted rows cannot revoke a JWT that a
stateless filter already accepted.

**Transitions**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| (absent) | `save` create | new user with role / status / permissions | `:96-113` |
| any | `save` update | role/status overwritten | `:115-139` |
| `ADMIN` + enabled | update would leave zero enabled admins — guard: `lockActiveAdminIds() FOR UPDATE`, reject if `size() <= 1` | `USER`/disabled rejected | `:120-129`; lock `SysUserMapper.java:17-18` |
| role or status changed | `identityChanged` | all tokens for the user deleted | `:130-138`, `:184-187` |
| `USER` | `save` with no `menu:` code | rejected `"普通用户至少需要一个菜单权限"` | `:150-153` |
| any | `save` where role resolves to `ADMIN` | permission rows **deleted** (`List.of()`), all codes implied | `:68-72`; `PlatformPermissionService.java:76-91` |
| any | `resetPassword` | password replaced, tokens deleted | `:76-90` |
| any | `replacePermissions` | delete-all + re-insert | `PlatformPermissionService.java:84-90` |
| any | `assignDefaultPermissions` | **all** codes (menus + actions) | `:93-95` → `PermissionCatalog.defaultCodes()` returns `CODES` (`:81-83`) |

**Enforcement.** One real invariant (last enabled admin) implemented with a DB row lock and re-check inside the
transaction (`:123-129`), plus a per-request permission read to avoid stale-grant windows
(`PlatformPermissionService.java:72-73`). Everything else is validation, not state.

**Defects and risks**

1. **`defaultCodes()` returns the entire catalog.** `PermissionCatalog.java:81-83` returns `CODES` (all 15 menus + 10
   actions), so "default" == "superuser". Any call site of `assignDefaultPermissions`
   (`PlatformPermissionService.java:93-95`) silently creates a fully privileged USER whose only distinguishing feature
   from ADMIN is the absence of permission rows.
2. **`ADMIN` is a state implemented by absence.** Permissions are cleared on promotion (`PlatformUserService.java:68-69`)
   and synthesized on read (`PlatformPermissionService.java:59-71`); a direct DB grant to an admin is silently ignored,
   and demoting an admin back to USER gives them **zero** permissions unless the request supplied a list — which would
   then fail the "at least one menu" check only if the list is empty (`:141-155`). The demote path can therefore leave a
   USER that can log in and see nothing.
3. **Status normalization is lossy.** `int status = Integer.valueOf(0).equals(request.getStatus()) ? 0 : 1`
   (`:60`) maps `null`, `-1`, `2`, `99` all to `1`; there is no way to express "unverified"/"locked", and a corrupt value
   from import becomes "enabled".
4. **Token revocation is a hard delete, not a state.** `revokeTokens` deletes rows (`:184-187`); there is no
   `revoked_at`, so revocation is unauditable and any component that cached the token elsewhere (JWT is stateless —
   `AuthServiceImpl`) continues to work until expiry. `expireTime` is stored as a **String** (`SysLoginToken.java:31-32`),
   so expiry comparisons are lexical.
5. **Role/status change and permission rewrite are not atomic with respect to readers.** `save` updates the user row
   (`:134`) then rewrites permissions (`:68-72`) inside the same transaction, but `PlatformPermissionService` reads
   committed data per request; a request in flight between commit boundaries can observe the new role with the old
   permission set (or vice versa) — brief, but it is exactly the window the "no cache" comment tries to avoid.
6. **No unique-key race handling for usernames.** `createUser` checks `selectCount` (`:99-103`) then inserts; the unique
   index (if any) or the DB decides the race, and the loser gets a raw exception instead of `"用户名已存在"`.
7. **`PermissionCatalog.defaultCodes()`/`codes()` are the same object**, so any future "default preset" cannot be
   expressed without changing the catalog — the state set and the default policy are conflated.

---

## SM-13 — System setting → AI runtime rebuild

**Purpose.** Persisted key/value settings; a subset of keys must invalidate and rebuild the live AI chat client / vector
store after a change.

**State set (exact literals).**

- setting rows are untyped strings keyed by `setting_key` (`entity/XianyuSysSetting.java:13`; unique
  `(tenant_id, setting_key)` after `V3:49`)
- trigger groups: `AI_RELATED_KEYS = {ai_provider, ai_protocol, ai_custom_name, ai_api_key, ai_base_url, ai_model,
  ai_reasoning_effort, ai_model_reply, ai_model_polish}` (`SysSettingServiceImpl.java:32-34`);
  `EMBEDDING_RELATED_KEYS = {ai_embedding_enabled, ai_embedding_api_key, ai_embedding_base_url, ai_embedding_model}`
  (`:37-38`)
- runtime states: `DynamicAIChatClientManager` keeps per-tenant clients in a map keyed by a cache key and rebuilds when
  the key changes (`config/rag/DynamicAIChatClientManager.java:102-118`), with an explicit `forceRebuild()` (`:254`);
  availability is derived (`enabled` / `available` / `apiKeyConfigured`, exposed at
  `AIChatController.java:78-95` via `getStatusInfo()`)

**Storage.** DB rows + JVM caches (`chatClients`, vector store). Desync risk: the JVM cache is the only place the
"settings have been applied" fact exists; the DB has no version/`updated_time`-driven invalidation, so any write path
that bypasses `forceRebuild` leaves the runtime stale until the cache key happens to change.

**Transitions**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| absent | `saveSetting` | row inserted, `created_time`/`updated_time` set | `SysSettingServiceImpl.java:121-131` |
| present | `saveSetting` | row updated | `:114-120` |
| saved key ∈ AI set | `forceRebuild()` | AI client map invalidated | `:134-137` |
| saved key ∈ EMBEDDING set | `forceRebuild()` on the vector store | vector store invalidated | `:138-141` |
| deleted key ∈ AI set | rebuild | invalidated | `:156-159` |
| deleted key ∈ EMBEDDING set | rebuild | invalidated | `:160-163` |
| saved key ∉ both sets | — | **no rebuild** | fallthrough after `:141` |

**Enforcement.** A hardcoded whitelist is the entire mechanism; there is no listener/observer, no event, and no
version stamp. `getSettingValue` reads through to the DB on every call (`:52-62`) — notably inside the hot RAG path
(`AIServiceImpl.java:131`, `:224`, `:646-659`) — so the *value* is always fresh while the *client* may be stale.

**Defects and risks**

1. **The whitelist is already incomplete.** Settings introduced by other services — `ai_image_enabled`,
   `ai_image_api_key`, `ai_image_base_url`, `ai_image_model` (`OpportunityImageService.java:44-52`),
   `sys_prompt` and `similarity_threshold` (`AIServiceImpl.java:40-49`) — are read through `getSettingValue` but are
   absent from both sets, so they never trigger a rebuild (in fairness they are read per call, but the same class of key
   is treated two different ways). Any new `ai_*` key silently joins the "stale runtime" group.
2. **Key matching is exact and case/whitespace sensitive at the write site.** `saveSetting` trims the key for the DB
   lookup (`:103-112`) but the whitelist check uses `reqBO.getSettingKey().trim()` too (`:134`), while
   `deleteSetting` compares the *raw* `settingKey.trim()` (`:145-163`) — consistent today, but there is no shared
   normalize function, so any future un-trimmed path breaks invalidation silently.
3. **No "settings applied" state is observable.** `AIStatusInfo` (`AIChatController.java:78-95`) reports
   enabled/available/apiKeyConfigured but not "the cached client is older than the stored settings"; an operator who
   saves a bad key sees `available=true` until the next cache miss.
4. **Delete is not a state transition but a state erasure.** `deleteSetting` removes the row (`:145-153`); every reader
   then falls back to a default (`AIServiceImpl.java:132-136`, `:224-227`, `:649-658`), so "not configured" and
   "configured to default" are indistinguishable in the UI — and a rebuild is triggered even though the effective
   configuration may be unchanged.
5. **The rebuild is synchronous inside the request.** `forceRebuild()` (`:136`) runs on the setting-save request thread;
   a slow provider handshake makes `POST /api/setting/save` block, and a rebuild failure is not reflected in the
   response (no try/catch around `:136`/`:140`, and `saveSetting` returns `void`).

---

## SM-14 — Buyer automation block (`automation_blocked` + `blocked_reason` + `last_interaction_time`)

**Purpose.** Per-buyer switch that stops automation from answering, with a human-facing reason and a watermark of the
last interaction used for sorting/aging.

**State set (exact literals).** `automation_blocked TINYINT NOT NULL DEFAULT 0` (`V9:9`) — 0 auto-allowed, 1 blocked;
`blocked_reason VARCHAR(200)` (`V9:10`) — free text; `last_interaction_time DATETIME(3)` (`V9:11`) — a "last*" watermark.
Blocked-state is *derived* at read time: `BuyerProfilePolicy.blockReason(flag, reason)`
(`buyer/BuyerProfilePolicy.java:37-43`) yields `null` or `"买家已停止自动化：" + (reason or "需人工处理")`.

**Storage.** `xianyu_buyer_profile` (`V9:1-18`), unique `(tenant, account, buyerUserId)` (`:14`). Desync risk: the
watermark is written by a `GREATEST(...)` upsert (`XianyuBuyerProfileMapper.java:26-36`) and by a backfill (`V9:26-60`),
but nothing ever resets it, so "buyer inactive" is invisible (there is no `last_automation_blocked_time`).

**Transitions**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| (absent) | `touch` on an inbound/outbound message — `INSERT … ON DUPLICATE KEY UPDATE buyer_user_name/last_interaction_time` with `GREATEST` | row created, watermark = max(existing, new) | `BuyerProfileService.java:110-122`; mapper `:26-36` |
| any | `save` with `automationBlocked=true` | flag 1, reason = request reason (may be null) | `:91-99` |
| any | `save` with `automationBlocked=false`/absent | flag 0, **reason still written if supplied** | `:94-95` |
| blocked | `automationBlockReason` | `"买家已停止自动化：…"` or `null` | `:124-134`; policy `:37-43` |
| any | list/detail read | `readTags` tolerates corrupt JSON | `:197-206` |

**Enforcement.** Only the boolean coercion `Boolean.TRUE.equals(...) ? 1 : 0` (`:94`) and the policy wrapper. Nothing
ties `blocked_reason` to `automation_blocked`.

**Defects and risks**

1. **Contradictory combinations are stored, and one of them is meaningful.** `save` writes `automationBlocked` and
   `blockedReason` independently (`:94-95`), so `blocked=0, reason="骂人"` persists (a reason with no block) and
   `blocked=1, reason=null` persists (block with a default message). Only the first is a *lie* on screen: the list view
   renders the reason (`selectPage` returns it) while automation keeps replying.
2. **The watermark is monotonic and never reset.** `GREATEST(COALESCE(last_interaction_time, VALUES(...)), VALUES(...))`
   (`mapper:30-31`) means out-of-order platform messages can never move it backwards — correct for "last", but it also
   means a *future*-dated message (clock skew, `messageTime` in ms, `BuyerProfileService.java:118-121`) pins the buyer to
   the top of the list forever.
3. **Two writers, two clocks.** `touch` writes `LocalDateTime` derived from `Instant.ofEpochMilli(messageTime)` in the
   JVM default zone (`:118-120`), while the migration backfill uses `FROM_UNIXTIME(...)` (`V9:34`) and orders fall back
   to `orders.create_time` (`:50`). Tenants across time zones get a watermark that is not comparable between rows.
4. **Blocking is not consulted by every automation path.** `automationBlockReason` (`:124-134`) is the only reader; the
   goods-level `human_intervention_on` (SM-03) is a *separate* switch for a similar concept, and nothing reconciles them
   — a buyer can be blocked while a goods-level takeover window is open, or vice versa, and the UI shows both as
   independent toggles.
5. **Detail endpoint tolerates a missing profile with a 400-ish message while list silently returns empty.** `detail`
   throws `IllegalArgumentException("买家资料不存在")` (`:140-142`) but `save` returns `null` if the read-back list is
   empty (`:101-107`) — two different "not found" representations for the same machine.

---

## SM-15 — Delivery mode exclusivity and channel flags (`xianyu_goods_auto_delivery_config`)

**Purpose.** Per listing+SKU delivery configuration: either a fixed-content template or a kami warehouse, delivered via
voucher and/or chat, with an auto-confirm-shipment switch.

**State set (exact literals).**

- `delivery_mode TINYINT DEFAULT 1` (`V1:135`): **1 = fixed content**, **2 = kami** (`AutoDeliveryConfigServiceImpl.java:234-251`)
- exclusive payload columns: `fixed_template_id` (mode 1) vs `kami_config_ids` (mode 2); the other side is nulled on every
  save (`:266-276`), and the legacy `kami_delivery_template` is always nulled (`:93`, `:111`), `auto_delivery_content`
  always nulled (`:275`)
- channel flags `voucher_delivery_enabled` / `chat_delivery_enabled` (`V7`), invariant **at least one = 1**
  (`:238-242`), values coerced to 0/1 (`:254-264`)
- `auto_confirm_shipment TINYINT DEFAULT 0` (`V1:143`) — **forced to 1** on every save (`:101`, `:117`)
- `receipt_follow_up_*`, `rag_delay_seconds` (`V1:144`, `ItemServiceImpl.java:1235-1268`)

**Storage.** `xianyu_goods_auto_delivery_config`, unique `(xianyu_account_id, xy_goods_id, sku_key)` where
`sku_key = COALESCE(sku_id,'')` (`V1:137`, `:148`). Desync risk: "no SKU selected" vs "SKU selected" are two different
rows for the same listing; the fallback logic in `saveOrUpdateConfig` (`:71-82`) can silently create a second row.

**Transitions**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| (absent) | save, mode 1, owned template required | row mode 1 + `fixed_template_id` | `:63-65`, `:243-248` |
| (absent) | save, mode 2, ≥1 kami config id, all owned | row mode 2 + `kami_config_ids` | `:249-251`, `:306-320` |
| existing (by sku) | save | overwrite all delivery fields, `auto_confirm_shipment=1` | `:85-104` |
| existing (no-sku) | save **with** skuId | treated as absent → new row inserted | `:76-82` |
| any | save with both channels 0 | rejected `"发货凭证和买家私聊至少开启一个"` | `:240-242` |
| any | `updateAutoConfirmShipment` | `auto_confirm_shipment` = request | `ItemController.java:127-140` → `AutoDeliveryService` |
| any | `deleteConfig` (all rows for account+goods) | rows gone | `:212-231` |
| any | fixed template deleted while referenced | blocked by `countReferencedConfigs` guard | `FixedDeliveryTemplateService.java:90-92`; `XianyuFixedDeliveryTemplateMapper.java:24-25` |

**Enforcement.** The best cross-field enforcement in the audited set: mode-exclusive columns are nulled on write
(`:266-276`), the required side is validated (`:243-251`), channel flags are range-checked and constrained
(`:238-264`), ownership of account/goods/SKU/kami configs is verified against the DB (`:278-321`), and a template cannot
be deleted while referenced (`FixedDeliveryTemplateService.java:90-92`).

**Defects and risks**

1. **`auto_confirm_shipment` is a flag with two contradictory writers.** `saveOrUpdateConfig` unconditionally writes `1`
   (`:101`, `:117`), while `POST /api/items/updateAutoConfirmShipment` (`ItemController.java:127-140`) writes the
   operator's 0/1 through a different service. Any later edit of the delivery config silently re-enables auto-confirm —
   the operator's off state is not stable, and nothing reports the override.
2. **"No SKU" vs "SKU" rows drift.** `saveOrUpdateConfig` falls back to the no-SKU row and then discards it when a
   `skuId` is supplied (`:76-82`), inserting a new row; the previous generic config remains active for other SKUs. There
   is no single "effective config for this purchase" resolution in one place — `getConfig` (`:134-168`) reproduces the
   same fallback logic, and `getConfigsByGoodsId` (`:171-189`) returns raw rows ordered by `sku_id ASC` (`mapper:39`),
   i.e. the null-SKU row first.
3. **Mode-switch leaves orphan payloads.** Because mode 1 nulls `kami_config_ids` and mode 2 nulls `fixed_template_id`
   (`:266-276`), switching back and forth loses the previous configuration with no history; there is no audit of "this
   listing used to deliver from warehouse X".
4. **`delivery_mode` has no enum and no DB constraint.** `TINYINT DEFAULT 1` (`V1:135`), validated only in
   `validateDeliveryContent` (`:234-237`); a direct DB edit to `3` makes every consumer's `== 1 / == 2` branch fall
   through silently (e.g. `ItemServiceImpl.java:546-548` copies it into the DTO unchecked).
5. **Legacy column kept but never written.** `kami_delivery_template` is nulled on both paths (`:93`, `:111`) and its
   mapper select still reads it (`XianyuGoodsAutoDeliveryConfigMapper.java:15`); any row written before this code keeps a
   non-null value that no writer will ever clear until the row is re-saved — a phantom "third mode".
6. **The SKU-required rule depends on remote data.** `validateResourceOwnership` rejects a no-SKU save when
   `countByXyGoodsId > 0` (`:293-295`); combined with SM-05 (SKU snapshot can be temporarily empty after a failed sync),
   the same save can succeed or fail depending on sync luck.

---

## SM-16 — Rating content pool and auto-rate mode

**Purpose.** Store 1–10 reusable rating texts per listing and pick one deterministically per order; the mode flag
(`xianyu_auto_rate_on`) decides when it fires.

**State set (exact literals).**

- `xianyu_auto_rate_on ∈ {0 OFF, 1 ALWAYS, 2 AFTER_BUYER}` (`XianyuGoodsConfig.java:12-14`), validated at
  `ItemServiceImpl.java:1052-1057`; **renumbered by migration** `V8__add_auto_rate_trigger_mode.sql:1-4`
  (`SET xianyu_auto_rate_on = 2 WHERE xianyu_auto_rate_on = 1`, comment: "历史开启状态只在买家评价后触发")
- content pool: `TEXT NOT NULL DEFAULT '…'` (`V4:2`, widened `V6:1`), max 10 unique items × 500 chars
  (`RatingContentService.java:6-7`, `:80-104`), stored as a JSON array **or** a bare string
- task-side states used by the same feature: `xianyu_goods_order.rate_status TINYINT DEFAULT 0` + `rate_time`
  (`V3:55-57`) — owned by the order/automation services

**Storage.** `xianyu_goods_config.xianyu_auto_rate_content` + `xianyu_auto_rate_on`. Desync risk: the column is
polymorphic (JSON array or plain text), decided by format sniffing at read time.

**Transitions**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| any text | `normalizeForStorage` — parse → dedupe → validate | canonical JSON array | `RatingContentService.java:24-31`, `:62-91` |
| `[...]` text | `readJsonContents` if `content.startsWith("[")` and JSON-parseable | list of items | `:93-102` |
| non-`[` text | treated as **one** item | list of 1 | `:96-99` |
| `[`-prefixed but invalid JSON | parse fails → **treated as one literal item** | list of 1 containing the raw text | `:100-102` |
| pool | `resolve` per order — pick `contents[Math.floorMod(orderId.hashCode(), size)]` | rendered text | `:33-40` |
| rendered | placeholders `{buyerName}`/`{goodsName}`/`{orderId}` substituted, >500 chars rejected | final rating text | `:42-57` |
| any | `updateGoodsAutomationStatus` with `xianyu_auto_rate_content` | normalized + persisted | `ItemServiceImpl.java:1059-1065`, `:1080-1082` |

**Enforcement.** Input validation is solid (count, length, dedupe, non-empty: `:80-91`); the mode enum is validated
(`ItemServiceImpl.java:1052-1057`). But the *format* is not a state — it is inferred.

**Defects and risks**

1. **Format sniffing is a state machine over one character.** `content.startsWith("[")` (`:94`) is the only discriminator
   between "pool" and "single text". A legitimately bracket-leading rating text (`"[秒发] 交易愉快"`) is parsed as JSON,
   fails, and is then stored/rendered as one literal item **including the brackets** — the failure is silent (`:100-102`
   returns the raw string, no error). A JSON array containing a single string normalizes to a *different* representation
   than the bare string, so the column can hold two encodings of the same logical state.
2. **Mode renumbering broke historical semantics.** `V8:3-4` rewrote every `1` to `2`; any client, export, or
   integration that still sends `1` meaning "after buyer rating" now selects **always rate** — the validation at
   `ItemServiceImpl.java:1052-1057` accepts it because `1` is a legal value for a different meaning. This is the classic
   hazard of a magic-number state set without an enum or a stored `schema_version`.
3. **No per-listing "rating already given" state here.** The pool service is stateless; the only guard against double
   rating lives in `rate_status` on the order (`V3:55`). A retry of the same order can therefore re-render a different
   text (different hash bucket if `orderId` differs) with no memory in this service.
4. **Deterministic pick is fragile.** `Math.floorMod(orderId.hashCode(), contents.size())` (`:37`) depends on
   `String.hashCode`, so a *changed* pool size silently remaps already-used orders; and `orderId=""` for a null order
   (`:35`) maps every order-less call to bucket 0.
5. **Defaults live in three places.** `XianyuGoodsConfig.DEFAULT_AUTO_RATE_CONTENT` (`:15`),
   `RatingContentService.parseContents` fallback (`:65-67`) and the SQL default (`V4:2`) can drift; the SQL default is
   already length-limited by `VARCHAR(500)` while the Java default is not re-validated on read.

---

## SM-17 — QR-login session (start → poll → cancel/expire)

**Purpose.** Scan-to-login: generate a QR, poll its state, hand back cookies on success.

**State set (exact literals).** `status` string: `waiting` (`QRLoginSession.java:26`),
`scanned` (`QRLoginServiceImpl.java:433`), `success` (`:503`), `expired` (`:427`, `:452-453`, `:461`, `:557`),
`cancelled` (`:438`), `verification_required` (`:494`), `error` (`:657`, `:693`, `:698`); plus a response-only
`not_found` (`:551`) and a frontend-facing mapping in `convertToFrontendStatus` (`:561-581`).
TTL: `expireTime = 300000` ms (`QRLoginSession.java:20`), `isExpired()` (`:33-35`); poll interval 800 ms and
max wait 5 min (`QRLoginServiceImpl.java:407`, `:442`).

**Storage.** In-memory `Map<String, QRLoginSession> sessions` (`:41`) — never persisted; a restart loses all pending
logins (the QR on the phone becomes useless). Desync risk: the *cookie/account* is written to the DB on success
(`saveCookieToDatabase`, `:645-700`) while the session state stays in memory; a crash between the two loses the login.

**Transitions**

| From | Trigger / guard | To | Evidence |
|---|---|---|---|
| (absent) | `POST /api/qrlogin/generate` succeeds | `waiting`, monitor thread started | `:286-336`; controller `QRLoginController.java:25-31` |
| `waiting` | poll returns `SCANED` | `scanned` (guarded by `"waiting".equals(status)`) | `:432-434` |
| `waiting`/`scanned` | poll returns `CONFIRMED` | login completes → `success` | `:423-430`, `:503` |
| `waiting`/`scanned` | poll returns `EXPIRED` | `expired` | `:427-429` |
| `waiting`/`scanned` | poll returns anything else (user cancelled) | `cancelled` | `:435-440` |
| any non-terminal | 5-minute loop timeout | `expired` (final sweep) | `:451-455` |
| `success` | `POST /cookies/{sessionId}` | cookies returned, session removed | `:625-644` |
| any | `isExpired()` on status query | coerced to `expired` (unless `success`) | `:556-558` |
| any | cookie/account persistence fails | `error` | `:657`, `:693`, `:698` |
| `expired`/`cancelled`/`success` | `POST /cleanup` | session removed | `:635-643`; controller `:49-52` |

**Enforcement.** Guards exist at each transition (the `waiting → scanned` guard at `:432` prevents regression from
`scanned`), and `isExpired()` is checked both in the loop and on read. But nothing prevents `cancelled → scanned` if a
late poll result arrives after the break — the loop simply stops (`:438-440`), so no.

**Defects and risks**

1. **Cleanup is endpoint-only.** `cleanupExpiredSessions` is exposed as `POST /api/qrlogin/cleanup`
   (`QRLoginController.java:49-52`) and is **not** `@Scheduled` (repo-wide `@Scheduled` inventory contains no QR clean-up).
   Sessions are only removed on success (`:644`) or when someone calls the endpoint, so abandoned `waiting`/`expired`
   sessions accumulate in the map for the process lifetime — unbounded growth keyed by user-controlled `sessionId`.
2. **`not_found` is a state that only exists in responses.** `:551` returns `status = "not_found"`, which is not in
   `QRLoginSession`'s documented state list (`:14`) and is not handled by `convertToFrontendStatus` (`:561-581`); clients
   must special-case it.
3. **`error` is a dead-end for the client.** `error` (`:657`, `:693`, `:698`) is set after a successful scan but a failed
   cookie save; it is not in the terminal set checked at `:452`, so the timeout sweep can overwrite it with `expired`
   (`:452-453`) — the operator loses the real reason.
4. **Success is not atomic with persistence.** `success` is set (`:503`) and the cookie is written to the DB afterwards
   (`:645-700`); if the write fails the status flips to `error` (`:657`), but a client that already called
   `/cookies/{id}` between the two gets a cookie/UNB pair for an account that was never saved. `getSessionCookies`
   returns `null` unless status is exactly `success` (`:625-628`), so the window is small but real.
5. **No explicit cancel endpoint.** `cancelled` is only reachable from a *platform* poll answer (`:438`). Unlike the
   captcha task (`WebSocketController.java:538-556`), the QR flow has no server-side cancel, so an operator cannot
   abort a pending login — they must wait 5 minutes for `expired`.
6. **Per-session thread per login.** `taskExecutor.execute(() -> monitorQRStatus(sessionId))` (`:333`) holds a thread
   for up to 5 minutes per generated QR; `generate` has no rate limit in the controller (`QRLoginController.java:25-31`),
   so the thread pool is the de-facto concurrency limit for logins.

---

## 18. Lifecycle endpoints found by the controller scan (state owners outside this assignment)

These are `start / stop / poll / cancel / confirm / retry` endpoints discovered while scanning `controller/**`. They are
listed with their **state owner** so the owning auditor can cross-check; no deep analysis is claimed here.

| Endpoint | File:line | Shape | State owner (to be audited) |
|---|---|---|---|
| `POST /api/items/refresh` → `syncId` | `ItemController.java:49-58` | start | `ItemDetailSyncServiceImpl` (SM-01) |
| `GET /api/items/syncProgress/{syncId}` | `ItemController.java:310-322` | poll | `ItemDetailSyncServiceImpl` (SM-01) |
| `GET /api/items/syncing/{accountId}` | `ItemController.java:324-332` | poll (busy) | `ItemDetailSyncServiceImpl` (SM-01) |
| *(missing)* cancel sync | — | cancel | SM-01 — **endpoint does not exist** |
| `POST /api/items/syncSingle` | `ItemController.java:196-212` | one-shot, no progress | `ItemDetailSyncServiceImpl` |
| `POST /api/items/changeListingStatus` | `ItemController.java:214-225` | transition | `PlatformPublishService` (SM-07) |
| `POST /api/items/updateAutoConfirmShipment` | `ItemController.java:127-140` | flag flip | `AutoDeliveryService` (SM-15 conflict) |
| `POST /api/operations/products/publish` | `MerchantOperationsController.java:158-165` | start (sync!) | `MerchantOperationsService` (SM-08) |
| `POST /api/operations/tasks` | `MerchantOperationsController.java:178-185` | enqueue | `MerchantOperationsService` (SM-08) |
| `POST /api/operations/tasks/{id}/requeue` | `MerchantOperationsController.java:187-195` | retry (unguarded) | `MerchantOperationsService` (SM-08) |
| `POST /api/operations/tasks/batch-publish` | `MerchantOperationsController.java:197-204` | start batch | `MerchantOperationsService` (SM-08) |
| `POST /api/operations/resources/{id}/execute` / `/compensate` | `MerchantOperationsController.java:77-93` | start / compensate | `MerchantOperationsService` (SM-08) |
| `POST /api/diagnostics/exceptions/acknowledge` (`-all`) | `OperationsDiagnosticsController.java:56-71` | confirm | `OperationsDiagnosticsService` (SM-11) |
| `POST /api/kami-config/item/reset` | `KamiConfigController.java:112-120` | reset (state reversal) | `KamiConfigServiceImpl` (SM-09) |
| `POST /api/kami-config/item/delete` | `KamiConfigController.java:102-110` | delete (unguarded) | `KamiConfigServiceImpl` (SM-09) |
| `POST /api/kami-config/delete` | `KamiConfigController.java:52-60` | delete (guarded) | `KamiConfigServiceImpl` (SM-09) |
| `POST /api/keyword-reply/updateMatchMode` | `KeywordReplyController.java:73-84` | transition (unvalidated) | `KeywordReplyServiceImpl` (SM-04) |
| `POST /api/keyword-reply/ensureFallbackRule` | `KeywordReplyController.java:86-97` | create-or-get | `KeywordReplyServiceImpl` (SM-04) |
| `POST /api/admin/users/save` | `PlatformUserController.java:41-48` | transition | `PlatformUserService` (SM-12) |
| `POST /api/admin/users/resetPassword` | `PlatformUserController.java:50-58` | revoke | `PlatformUserService` (SM-12) |
| `POST /api/auto-delivery-config/save` | `AutoDeliveryConfigController.java:23-33` | transition | `AutoDeliveryConfigServiceImpl` (SM-15) |
| `POST /api/fixed-delivery-template/delete` | `FixedDeliveryTemplateController.java:37-40` | delete (guarded) | `FixedDeliveryTemplateService` (SM-15) |
| `POST /api/setting/save` | `SysSettingController.java:94-116` | transition + rebuild | `SysSettingServiceImpl` (SM-13) |
| `POST /api/setting/delete` | `SysSettingController.java:122-137` | erasure + rebuild | `SysSettingServiceImpl` (SM-13) |
| `POST /api/setting/ai/test` | `SysSettingController.java:140-155` | probe (does not persist) | `DynamicAIChatClientManager` (SM-13) |
| `POST /ai/status` | `AIChatController.java:78-95` | poll | `DynamicAIChatClientManager` (SM-13) |
| `POST /api/qrlogin/generate` / `status/{id}` / `cookies/{id}` / `cleanup` | `QRLoginController.java:25-52` | start / poll / collect / sweep | `QRLoginServiceImpl` (SM-17) |
| `POST /websocket/start` / `stop` / `status` | `WebSocketController.java:69`, `186`, `340` | start / stop / poll | `WebSocketServiceImpl` — **not audited here** |
| `POST /websocket/captcha/status` / `cancel` | `WebSocketController.java:512`, `538` | poll / cancel | `CaptchaSolveServiceImpl` — **not audited here** |
| `GET /system/update/status` | `SystemController.java:136` | poll | `SystemUpdateService` — **not audited here** |
| `POST /api/msg/context/sync` | `MsgController.java:63-72` | one-shot sync | `ChatMessageServiceImpl` — **not audited here** |
| `GET /api/items/...` (`/refresh` excluded) | `ItemController.java:66-93` | read | — |

**Adjacent machines worth cross-checking in the owner audits** (they interact with SM-01/SM-08/SM-11):

1. **CAPTCHA task** — `start/poll/cancel` with state owned by `CaptchaSolveService`; the only lifecycle in the app with
   a working server-side cancel (`WebSocketController.java:538-556`).
2. **WebSocket connection** — `start/stop/status` plus a recovery scheduler (`WebSocketRecoveryScheduler.java:35`);
   `OperationsDiagnosticsService` counts `websocketDisconnected` per account (`:122-126`), so its state is part of the
   health model.
3. **Notification channel health** — `enabled` + `last_success_time` + `last_error_message`
   (`XianyuNotificationChannelMapper.java:24-32`): `markSuccess` clears the error, `markFailure` sets it, no status enum
   → last-write-wins health flag with no "unknown/degraded" state; `xianyu_notification_log.send_status` is 1/0
   (`NotificationCenterService.java:199`, `:205`) and feeds the STOCK/NOTIFICATION check.
4. **System update** — `HasUpdate`/agent availability folded into warnings only (see SM-11 defect 6).
5. **Human takeover window** — `xianyu_human_intervention_record.end_time` (`XianyuHumanInterventionRecordMapper.java:11-24`)
   is a pure expiry timer (insert-or-extend, `end_time > NOW()` = active, `DELETE … end_time < NOW()` = sweep); it is a
   parallel mechanism to `BuyerProfileService.automationBlockReason` (SM-14) and to the goods-level
   `human_intervention_on`/`human_intervention_minutes` (SM-03) — three independent "is a human handling this" states.

---

## 19. Mapper transition-guard inventory (the real state machines as expressed in SQL)

Every `UPDATE` below is a state transition whose guards define the legal edge. This is the authoritative list for the
machines above; where the guard is missing, illegal transitions are reachable.

| Mapper | Statement | Guard (WHERE) | Meaning / gap |
|---|---|---|---|
| `MerchantTaskMapper` | `claim` `:43-45` | `status IN (0,-1) OR (status=1 AND updated_time<=NOW-10min)` | CAS on entry; 10-min stale lease |
| | `complete` `:47-48` | `id` only | **no guard** — late finisher overwrites |
| | `fail` `:50-52` | `id` only | **no guard** |
| | `requeue` `:54-55` | `id` only | **no guard** — can revive `2` |
| | `defer` `:57-60` | `id` only | **no guard**; decrements `attempt_count` |
| | `selectDue` `:37-41` | status/scheduled/next_retry/lease predicates | authoritative due-set |
| | `countPendingPlatformActions` `:62-65` | `status IN (0,1) OR (-1 AND attempt<max)` | backlog metric |
| `XianyuKamiItemMapper` | `markUsed` `:54-55` | `status = 0` | 0→1 |
| | `markUnused` `:57-58` | `status IN (1,3)` | 1/3→0; **2 cannot be reset** |
| | `reserve` `:76-79` | `status = 0` | 0→2 |
| | `commitReservation` `:81-82` | `order_id=? AND status = 2` | 2→1 |
| | `releaseReservation` `:84-87` | `status = 2 AND config.source_type='LOCAL'` | 2→0; **API items never released** |
| | `markReservationReviewRequired` `:89-90` | `status = 2` | 2→3 |
| | `lockAvailable` `:60-63` / `lockReservedByOrder` `:65-67` | status filters + `FOR UPDATE` | read-side locks |
| `XianyuKamiExternalRequestMapper` | `insertIfAbsent` `:14-22` | `INSERT IGNORE` + unique `(tenant,config,order)` | idempotent create |
| | `claimRetry` `:41-55` | `attempt_count<3 AND (status='FAILED' OR (PROCESSING AND update_time<NOW-2min))` | bounded lease retry |
| | `markSuccess` `:57-65` | `status='PROCESSING'` | →SUCCESS |
| | `markFailure` `:67-77` | `status='PROCESSING'` | →`#{status}` (**unwhitelisted**) |
| | `countUnsettledByConfigId` `:33-39` | `status IN ('PROCESSING','REVIEW_REQUIRED')` | **excludes `FAILED`** |
| `XianyuGoodsConfigMapper` | `update` `:32-42` | `id` only | whole-row overwrite (SM-03 defect 1) |
| | `updateLastPolishTime` `:56-57` | `id=? AND xianyu_auto_polish_on = 1` | stamp only while enabled |
| | `selectAutoRateEnabled` `:50` / `selectAutoPolishEnabled` `:53` | `auto_rate_on IN (1,2)` / `auto_polish_on = 1` | reader of the flag set |
| `XianyuGoodsInfoMapper` | *(none)* | — | status written only by MyBatis-Plus whole-row updates |
| `XianyuGoodsOrderMapper` | `updateState` `:140-141`, `updateStateAndContent` `:144-146`, `updateStateContentAndFailReason` `:148-151` | `id` only; `delivery_status` derived from `CASE WHEN #{state}…` | `exception_revision + IF(state=-1,1,0)` |
| | `lockDueTasks` `:167-174` | `delivery_status IN ('PENDING','RETRY_WAIT') … OR ('PROCESSING' AND lease_expire<NOW)` + `FOR UPDATE` | due-set |
| | `claimTasks` `:176-181` | `id IN (…)` only | **no status guard** — relies on the preceding `FOR UPDATE` select |
| | `completeTask` `:183-186` | `id` only | **no guard** |
| | `retryOrFailTask` `:188-193` | `delivery_status NOT IN ('REVIEW_REQUIRED','COMPLETED')` | prevents reopening a reviewed/completed delivery |
| | `deferForRisk` `:195-200` | `id` only | **no guard**; decrements `attempt_count` |
| | `markTaskReviewRequired` `:202-206` | `id` only | unconditional `exception_revision+1` |
| | `requeueTask` `:208-211` | `state<>1 AND delivery_status IN ('FAILED','RETRY_WAIT')` | cannot requeue a completed sale |
| | `requeueFailedTask` `:213-221` | `account=? AND state<>1 AND delivery_status IN ('FAILED','RETRY_WAIT','REVIEW_REQUIRED')` | admin requeue incl. review |
| `XianyuGoodsAutoReplyRecordMapper` | `claim` `:57-60` | `state=0 OR (state=2 AND lease_expire_time<NOW)` | lease CAS |
| | `cancelBySession` `:62-64` / `cancelById` `:66` | `state=0` / `state IN (0,2)` | 0/2→-2 |
| | status writer `:26-28` | `id` only | `exception_revision + IF(state=-1,1,0)` |
| `XianyuBuyerProfileMapper` | `touch` `:26-36` | `ON DUPLICATE KEY` + `GREATEST` watermark | monotonic last-seen |
| | `selectPage` `:38-57` | `automation_blocked = #{blocked}` optional | flag read |
| `MerchantDistributionMapper` | settle `:39` | `id=? AND settlement_status = 0` | 0→1 one-way |
| | published `:32-33` | `material_resource_id` (no status guard) | status→1 |
| `MerchantResourceMapper` | `updateLastRun` `:45` | `id` only | `last_run_time` stamp; no state |
| `XianyuAccountMapper` | `advanceWebSocketSyncCursor` `:24-30` | `websocket_sync_pts IS NULL OR websocket_sync_pts < #{pts}` | monotonic cursor CAS (a real `last*` state machine) |
| | `selectReconnectableAccounts` `:18-22` | `account.status=1 AND cookie.cookie_status=1 AND cookie_text<>''` | join of two state machines |
| `XianyuNotificationChannelMapper` | `markSuccess` `:24-26` / `markFailure` `:28-29` | `id` only | last-write-wins health, no status enum |
| `XianyuHumanInterventionRecordMapper` | `upsert` `:11-14` | `ON DUPLICATE KEY UPDATE end_time=VALUES(end_time)` | extend-only window |
| | `deleteExpired` `:24` | `end_time < NOW()` | sweep |
| `XianyuOperationLogMapper` | `deleteByAccountId` `:71` | account only | no status machine; log rows are append-only |
| `SysUserPermissionMapper` | `deleteByUserId` `:21` | user only | replace-all permissions |
| `SysUserMapper` | `lockActiveAdminIds` `:17` | `role='ADMIN' AND status=1 FOR UPDATE` | last-admin invariant |

**Guard-style summary.** Only four mappers use true compare-and-set transitions (`merchant_task.claim`,
`xianyu_kami_item.*`, `xianyu_kami_external_request.*`, `xianyu_goods_auto_reply_record.claim`). Every *completion*
statement in the codebase (`complete`/`fail`/`completeTask`/`markSuccess` variants) either lacks a guard or guards only
on the source state, never on the lease owner — so "the worker that wrote the result may not be the worker that owns the
task" is a systemic pattern, not an isolated bug.

---

## 20. Cross-cutting findings, ranked

1. **SM-01: cancel is a phantom feature.** `cancelSync` exists, is unreachable (no endpoint), and the DTO cannot express
   it. `@Async` is defeated by self-invocation, so refresh blocks; `progressMap`/`accountSyncMap` are unbounded, racy
   (`accountSyncMap.remove` by key), and lost on restart.
2. **SM-02: `xianyu_goods.status` is a four-state machine with two unreachable states and no terminal state.** `1`
   (已下架) and `2` (已售出) are never written by any Java path yet are offered as filters in the UI; `-1` is silently
   reverted by the next refresh; and a **truncated page fetch marks whole pages 已删除** (`ItemServiceImpl.java:195-216`
   + `:257`).
3. **SM-08: `requeue` can resurrect a completed PUBLISH.** `MerchantTaskMapper.requeue:54` has no status guard, so
   `POST /api/operations/tasks/{id}/requeue` re-executes an already-succeeded publish → duplicate platform listings; the
   `max_attempts=1` protection only covers automatic retries, and NULL/absent `request_key` (batch publish) leaves no
   idempotency at all.
4. **SM-08: completion statements are unguarded.** `complete`/`fail` update by id only (`MerchantTaskMapper:47`, `:50`)
   while `claim` re-leases after 10 minutes (`:39`) → the classic double-owner lost update; the same anti-pattern
   appears in `XianyuGoodsOrderMapper.completeTask:183` and `claimTasks:176` (guard lives in the preceding `FOR UPDATE`
   select).
5. **SM-09: `RESERVED` is a trap state for API-sourced warehouses.** `releaseReservation` skips non-LOCAL configs
   (`XianyuKamiItemMapper:84-87`), `resetKamiItem` refuses `2` (`:57`), and `hasUnsettledSupply` then blocks config
   edit/delete forever (`KamiConfigServiceImpl:619`, `:87`, `:176`) — stock is pinned with no sanctioned exit.
6. **SM-10/SM-11: `REVIEW_REQUIRED` is terminal-by-neglect, and acknowledgement hides rather than resolves.** No
   automatic recovery exists for the request state; the diagnostics ack (`OperationsDiagnosticsService:280-307`) only
   removes the row from the exception list and the health counts, and ack rows are never pruned.
7. **SM-03/SM-15: contradictory flags are storable and silently overwritten.** Whole-row rewrites
   (`XianyuGoodsConfigMapper.update:32-42`, `AutoDeliveryConfigServiceImpl` field setters) mean one operator's toggle
   reverts another's, and `auto_confirm_shipment` is forced back to `1` on every delivery-config save
   (`AutoDeliveryConfigServiceImpl:101`, `:117`) against the dedicated toggle endpoint.
8. **SM-04: `match_mode` accepts arbitrary integers and one value (`1`) carries two meanings.** `updateMatchMode` is
   unvalidated (`KeywordReplyController:73-84`), only `1`/`2` are ever matched (`XianyuKeywordReplyRuleMapper:21`, `:24`),
   so a rule saved as `3` is permanently dead but visible; blank keywords create catch-all rules.
9. **SM-06/SM-07: lifecycle states leak through return values and unpersisted booleans.** `"COOKIE_EXPIRED"` is smuggled
   as a CDN URL string (`ImageUploadServiceImpl:216`, `:233`), and `localSynced=false` (`PlatformPublishService:118-124`)
   is a platform-orphan state that exists only in one HTTP response — no row, no recovery, and the response still says
   `success:true`.
10. **SM-13/SM-12: whitelists and defaults are the only state policies.** AI rebuild keys are a hardcoded set that omits
    existing keys (`ai_image_*`, `sys_prompt`, `similarity_threshold`), and `PermissionCatalog.defaultCodes()` returns
    the full catalog (`:81-83`), so "default user" is indistinguishable from a superuser.

### States never written

`xianyu_goods.status = 1` and `= 2` (SM-02); `SyncProgress.cancelled = true` (SM-01, no reachable caller);
`merchant_task` has no cancelled state (SM-08); `xianyu_kami_usage_record.delivery_status = 'RESERVED'` (SM-09, DDL
default never produced); `xianyu_goods_auto_delivery_config.kami_delivery_template` always nulled (SM-15).

### States never read (or only by one branch)

`xianyu_goods.status = 1 / 2` (only by UI filters that cannot match); `match_mode` values ∉ {1,2} (SM-04);
`xianyu_kami_item.status = 3` in exports (SM-09 export supports only 0/1); `xianyu_kami_external_request.request_status`
values outside the four known strings (SM-10 `markFailure` accepts any string);
`xianyu_goods_sku_property` on SKU-less listings (SM-05).

### Unbounded in-memory state (lost on restart)

`ItemDetailSyncServiceImpl.progressMap` (`:44`), `accountSyncMap` (`:45`); `KamiConfigServiceImpl.stockOutEmailSentTime`
(`:72`); `QRLoginServiceImpl.sessions` (`:41`, cleanup endpoint-only); plus the AI client/vector caches in
`DynamicAIChatClientManager` (SM-13), which are rebuilt only on whitelisted key changes.

### "Last*" timestamps acting as hidden state

`xianyu_goods_config.last_polish_time` (`V3:50`, guarded write at `XianyuGoodsConfigMapper:56`);
`xianyu_buyer_profile.last_interaction_time` (`V9:11`, `GREATEST` upsert `XianyuBuyerProfileMapper:30`);
`xianyu_notification_channel.last_success_time` / `last_error_message` (`V9:70-71`, last-write-wins);
`xianyu_account.websocket_sync_pts/seq/timestamp` (`V3`, monotonic CAS `XianyuAccountMapper:24-30`);
`merchant_task.scheduled_time` / `next_retry_time` (dual timers, SM-08 defect 4);
`xianyu_goods_order.lease_expire_time` / `next_retry_time` (worker lease, §19);
`xianyu_kami_item.reserved_time` / `used_time` (no expiry sweep, SM-09 defect 8);
`xianyu_cookie.expire_time` and `sys_login_token.expire_time` (both `String`, lexical comparison — SM-12 defect 4).
