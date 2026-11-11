# Implicit State Machine Audit — `vue-code/src` (XianYuSmart Vue 3 frontend)

Scope: `vue-code/src` (stores, views, components, api, utils, router). 41,733 LOC across 27 routed views, 5 composables (`use*Manager.ts`), 1 store.
Method: full read of every store/composable/router/api module; grep sweep for `setInterval|setTimeout|clearInterval|clearTimeout`, `status ===`, status-literal maps, `ref(`/`reactive(` flag clusters; every timer cross-checked against its `onUnmounted`/`onBeforeUnmount`/watch-close path; state literal sets cross-checked against the Java backend (`src/main/java`) where the enum lives server-side. **No files were modified.**

Machine count: **24 fully documented machines + 1 minor (workflow node editor) = 25**.

---

## 0. Executive summary

**There is no Pinia state machine.** `stores/` contains exactly one file, an unused tutorial store:

- `stores/counter.ts:4-11` — `defineStore('counter')` with `count`, `doubleCount`, `increment`. Never imported anywhere. `main.ts:2,11` installs Pinia, but no application state lives in it.

All application state machines live in three unformalized places: **module-level refs** (`utils/permission.ts`), **composables** (`use*Manager.ts`), and **`<script setup>` locals**. 24 real state machines + 6 sub-machines follow.

Ranking by risk (leaks/desync/wrong terminal assumption):

| ID | Machine | Kind | Severity |
|----|---------|------|----------|
| M01 | Auth/session + route guard | module ref + router guard | **High** (silent redirect loops, duplicated auth truth) |
| M02 | Login view `mode` | 3-state enum | Low |
| M03 | WS connection composite status | boolean+string+number composite | **High** (7 duplicate mappers, untyped `status`) |
| M04 | Connection status polling (4 copies) | setInterval loops | **High** (races, stale overwrite, dead copy) |
| M05 | QR-login poll (4 copies) | pending/scanned/confirmed/expired | **High** (re-entrant `confirmed` → duplicate account create) |
| M06 | Captcha solve task + manual frame | 7-status server FSM | Medium (best-designed; no client deadline) |
| M07 | System update task | 10-status server FSM | Medium (unhandled status renders blank) |
| M08 | Goods sync progress | refreshing/syncing pair | **High** (unrecoverable "syncing" stuck state) |
| M09 | Goods pull-to-refresh | 4-state touch FSM | Medium (`refreshing` collapses instantly) |
| M10 | Product-publish wizard | step/maxStep | **High** (guard bypass) |
| M11 | Opportunities wizard | step/maxStep (duplicate of M10) | Medium (`maxStep` regresses) |
| M12 | Auto-delivery config editor | dirty-snapshot + generation tokens | Medium |
| M13 | Auto-reply toggle cluster | 5× tri-state switches | **High** (partial PUT → field clobber/desync) |
| M14 | Orders delivery status + row actions | 9-status set + 4 per-row flags | Medium |
| M15 | Pending-orders auto-refresh/auto-deliver | 2 interval loops | **High** (no re-entrancy guard on money path) |
| M16 | Message list silent polling | loading/silentLoading pair | Medium |
| M17 | Context dialog paging vs 1 s poll | hasMore/loadingMore + poll | **High** (poll destroys pagination) |
| M18 | Kami dialog cluster + status | 5 booleans, status 0/1 | Medium |
| M19 | Settings tab + async flags + fake progress | string tab + 20 booleans | Medium (progress is decorative) |
| M20 | Image upload progress | fake progress interval | Medium (timer leak on error) |
| M21 | Operations action lock + status maps | `pendingAction` string | Low |
| M22 | Buyers detail/rating | tab + generation id | Medium (loading race) |
| M23 | Price-comparison seller profile | per-key loading/error sets | Low |
| M24 | Admin-users editor tri-state | `null`/`undefined`/user | Medium (tri-state misuse) |
| — | Workflow connect/drag | `connectingFrom`/`dragState` | Low |

Systemic patterns found in ≥3 places each:

1. **A `status` value is mapped to text/color in 7 different files** (cookieStatus), and `state` (0/1/-1) is mapped twice with *different* vocabularies (M14).
2. **No frontend WebSocket client exists** — `api/websocket.ts` is a set of REST wrappers; there is **no `new WebSocket`, no reconnect, no backoff** anywhere in `vue-code/src`. Backend owns the socket.
3. **No state machine has an explicit `INIT`/`ERROR` distinction**; errors are either a string ref (`skuLoadError`, `configLoadError`, `pollError`) or a swallowed `catch {}`.
4. **Generation counters (`requestVersion`, `detailRequestId`, `rateSyncSequence`) are the only real enforcement mechanism** in the codebase, used in 5 places; everything else is "guard by early-return on a boolean".
5. **Timer hygiene is inconsistent**: 17 intervals, 4 of which have no reliable teardown on one code path (M04 dead copy, M08, M20, M19).

---

# Group A — Auth & routing

## M01. Authenticated-session state machine (module singleton + router guard)

**Purpose**: decide whether a route request is allowed to render, and which menu/permission surface applies.

**Files/state**

| What | File:line |
|---|---|
| `permissionState = ref<CurrentUser>()` (module-level singleton, acts as an ad-hoc store) | `utils/permission.ts:4` |
| in-flight dedupe promise `loadingPromise` | `utils/permission.ts:6` |
| loader `loadCurrentUser(force)` | `utils/permission.ts:8-21` |
| derived `isPlatformAdmin` | `utils/permission.ts:23` |
| `hasPermission`, `firstAccessiblePath` (menu fallback table) | `utils/permission.ts:25-27, 51-54` |
| guard | `router/index.ts:178-201` |
| token truth (localStorage), `isLoggedIn`, 401 forced logout | `utils/request.ts:10-38, 70-78, 99-106` |
| second, independent copy of "am I admin" | `components/layout/AppLayout.vue:27, 30-40` (`isAdmin` local ref from a *second* `getCurrentUser()` call) |
| menu layout duplicated into this state | `utils/permission.ts:29-31`, `views/settings/index.vue:221, 251-260` |

**State set**: `permissionState` ∈ {`undefined` (never loaded), `CurrentUser{ role: 'ADMIN'|'USER', permissions: string[], menuLayout?: string }`}; plus orthogonal `isLoggedIn()` ∈ {true, false} from `localStorage['xianyu_auth_token']`.

**Transitions (guard)**

| From | Trigger (`file:line`) | To | Guard |
|---|---|---|---|
| any | `router.beforeEach` (`router/index.ts:178`) | `next()` (public) | `to.meta.public` (`router/index.ts:179`) — only `/login` (`router/index.ts:54`) |
| any | `beforeEach` | `/login` | `!isLoggedIn()` (`router/index.ts:183`) |
| `permissionState=undefined` | `await loadCurrentUser()` (`router/index.ts:188`) | loaded | dedupe via `loadingPromise` (`utils/permission.ts:10-19`) |
| loaded non-admin | `to.meta.adminOnly` (`router/index.ts:189`) | `firstAccessiblePath()` | `isPlatformAdmin` |
| loaded | `!hasPermission(to.meta.permission)` (`router/index.ts:193`) | `firstAccessiblePath()` | permission string per route (`router/index.ts:12-172`) |
| any | `loadCurrentUser()` throws (`router/index.ts:198`) | `/login` | any network error ⇒ logged out |

**Enforcement**: single point (the guard). No enforcement for permission *changes* after mount: `permissionState` is only re-fetched on `force=true`, and `AppLayout` fetches the user separately into `isAdmin` — the two truths can disagree.

**Defects**

1. **Duplicated auth truth**: `permissionState` (`utils/permission.ts:4`) vs `AppLayout.isAdmin` (`AppLayout.vue:27,33`) vs `localStorage` token (`utils/request.ts:14`). An admin granted/revoked mid-session updates only one of them; the update badge, update-dialog entry and readonly banner (`AppLayout.vue:115-117, 172`) use `isAdmin`, while routing uses `permissionState`.
2. **Every failure is treated as "logged out"** (`router/index.ts:198-200`): a transient 500 from `/system/currentUser` forces `/login` → `/login` is `public`, so the user lands on a login form while still holding a valid token. There is no `ERROR` state distinct from `UNAUTHENTICATED`.
3. **No illegal-transition protection on `firstAccessiblePath()`**: it returns `/login` when no menu permission matches (`utils/permission.ts:53`); `/login` then hard-redirects (`views/login/index.vue:39-42`) to `/dashboard`, which the guard bounces again → ping-pong between `/login` and `firstAccessiblePath` for a user with a valid token and zero menu permissions.
4. `permissionState` is never reset on logout (`views/settings/index.vue:538-560` clears only the token via `clearAuthToken()`), so a subsequent login as a different tenant reuses the stale cached permissions until a hard reload — `loadCurrentUser()` returns the cached object immediately (`utils/permission.ts:9`).
5. `menuLayout` exists in three places at once (`permissionState.menuLayout`, local `menuLayout` in settings `views/settings/index.vue:221`, and the server setting `menu_layout`); `updateMenuLayout()` (`utils/permission.ts:29-31`) only patches the first.

---

## M02. Login view mode machine

**Purpose**: choose between "probing backend", "login form", "registration form".

**State**: `views/login/index.vue:8` `mode = ref<'checking'|'login'|'register'>('checking')`; `loading` (`:9`); `showPassword`, `showConfirmPassword` (`:15-16`); derived `canRegister` (`:22`).

**Transitions**

| From | Trigger | To | Guard |
|---|---|---|---|
| `checking` | mount → `checkUserExists().data.exists === true` (`login/index.vue:45-48`) | `login` | none |
| `checking` | `exists === false` (`:48`) | `register` | none |
| `checking` | `res.code !== 200` or throw (`:49-54`) | `login` | none |
| `login` | `switchMode('register')` (`:31-35`, button `:162`) | `register` | `!loading` (button disabled) |
| `register` | `switchMode('login')` | `login` | `!loading` |
| any | `isLoggedIn()` at mount (`:39-42`) | hard navigation to `/dashboard` | none |
| `login`/`register` | success (`:63-65`, `:85-87`) | `window.location.href='/dashboard'` | token present |

**Enforcement**: submit buttons disabled while `loading`; `switchMode` clears `password`/`confirmPassword` (`:31-35`) preventing credential carry-over.

**Defects**

1. **No `ERROR` / `UNREACHABLE` state**: probe failure silently maps to `login` (`:50-53`). On a fresh install whose backend is briefly down, the user sees "登录" instead of "创建管理员账号" and cannot register.
2. `mode` never returns to `checking`; there is no retry of the probe without a full page reload.
3. `loading` is shared by both forms and both submit handlers (`:60`, `:78`) — while one is in flight the other form's button is also disabled, and `switchMode` is blocked, even though it belongs to the other branch.
4. `switchMode` does **not** clear `username` (`:31-35`), so a half-typed registration username leaks into login.
5. Invalid response body (`res.code===200` but `!res.data.token`, `:66-68`) logs to console and **leaves the machine in the same state with no user feedback** — a silent dead-end state.

---

# Group B — Connection / WebSocket (backend-owned socket)

## M03. WebSocket connection composite status

**Purpose**: represent "is this Xianyu account connected, are its credentials valid, is the platform rate-guarding writes".

**Files/state**

| What | File:line |
|---|---|
| `WebSocketStatus` interface (`connected: boolean` + `status: string` + `cookieStatus?: number` + `tokenExpireTime?` + `riskGuard` + `deferredPlatformActions`) | `api/websocket.ts:13-25` |
| `RiskGuardStatus.state` union | `api/websocket.ts:4-10` |
| local re-declarations of the same shape (3 partial types) | `views/connection/useConnectionManager.ts:9-19`, `views/connection/ConnectionDetail.vue:29-43`, `views/connection/components/ConnectionDetail.vue:23-37`, `views/connection/components/ConnectionDetailDialog.vue` (~:25-38), `views/connection/components/CredentialModal.vue:8-18` |
| `connectionStatus` ref | `useConnectionManager.ts:31`, `ConnectionDetail.vue:79`, `components/ConnectionDetail.vue:46`, `ConnectionDetailDialog.vue:~30` |
| derived capability flags | `ConnectionDetail.vue:243-245` (`canSyncGoods`, `canAutoReply`), `:255-276` (`riskGuardNormal`, `riskGuardDescription`), mirrored in `components/ConnectionDetail.vue:228-253` |

**State set (exact literals)**

- `connected`: `true | false`
- `status`: **free-form Chinese display string** — backend sets `"已连接"` / `"未连接"` (`src/main/java/com/xianyusmart/controller/WebSocketController.java:355`). It carries no machine meaning.
- `cookieStatus`: `1 = 有效 | 2 = 过期 | 3 = 失效 | undefined/other = 未知` (backend switch: `WebSocketController.java:443-451`; entity field `entity/XianyuCookie.java:41`)
- `riskGuard.state`: `'NORMAL' | 'RATE_WAIT' | 'CIRCUIT_OPEN' | 'RECOVERING'` (mirrors `service/RiskControlService.java:93-99`)
- `tokenExpireTime`: derived binary `now > ts` (`useConnectionManager.ts:252-255`)
- `autoDeliveryOn` / `autoReplyOn`: booleans rendered as 已开启/未开启 (`ConnectionDetail.vue:439-461`)

**Transitions** — pure server snapshot, no client transitions. Client only re-reads (`POST /websocket/status`, `api/websocket.ts:28-34`) via M04. The only client-initiated transitions are `startConnection` (`:37-43`) / `stopConnection` (`:46-52`) / `updateCookie` (`:55-61`) / `refreshToken` (`:188-194`) / `updateToken` (`:197-203`) → server then flips `connected`.

**Enforcement**: `response.code === 0 || 200` checks only. No client-side state transition is validated; `startConnection`'s `1001 + needCaptcha` branch (`useConnectionManager.ts:150`, `ConnectionDetail.vue:138`, `components/ConnectionDetail.vue:107`) is the only conditional transition, and it is **duplicated three times with three different continuations** (browser popup + confirm dialog / open captcha dialog / open captcha dialog).

**Defects**

1. **`status` is untapped duplicated state.** It is rendered raw (`components/ConnectionDetailDialog.vue:316`) and copied into `connectionMap` (`views/connection/index.vue:50`) but never used in any condition, while `connected` drives every gate (`ConnectionDetail.vue:243-245`). Two fields describe one fact; no code keeps them consistent client-side.
2. **7 copies of the cookieStatus→label/color mapping** with the same literals but different fallbacks: `useConnectionManager.ts:232-242` (`neutral`), `views/connection/ConnectionDetail.vue:201-212` (`#30D158/#FF9F0A/#FF453A` + `'未知'`), `components/ConnectionDetail.vue:167-178`, `components/ConnectionCard.vue:49-68` (CSS vars), `components/CredentialModal.vue:36-48`, `components/MobileCredentialModal.vue:39-49`, `components/ConnectionDetailDialog.vue:166-186`. Any new backend value (e.g. `4 = 被平台踢下线`) renders as `未知`/neutral in seven places instead of one.
3. **Field-name desync**: `mH5Tk` vs `mh5Tk` — the frontend reads both spellings (`ConnectionDetail.vue:236`, `components/ConnectionDetail.vue:214`, `CredentialModal.vue:34`) because the backend field name is not settled. `getMH5TkStatusText` (`ConnectionDetail.vue:239-241`) always returns `有效` when the field is present — a token with no expiry info is reported as unconditionally valid.
4. `isTokenExpired(undefined)` returns `false` (`useConnectionManager.ts:252-255`) ⇒ absent expiry renders as `未设置` in the label but `有效`/success color in the badge (`getTokenStatusText` vs `getTokenStatusType`, `:257-265`) — label and color disagree for the same value.
5. `riskGuardNormal` treats a missing `riskGuard` **and** missing `deferredPlatformActions` as healthy (`ConnectionDetail.vue:253-256`) — a null snapshot renders as "正常".
6. **No terminal/unreachable handling for `status`**: being a string, `'未连接'` is indistinguishable from any future value; `components/ConnectionDetailDialog.vue:316` prints the raw string to end users.

---

## M04. Connection-status polling loops (4 implementations, 1 dead)

**Purpose**: keep the composite status (M03) fresh without server push.

**Loops**

| # | File:line (start) | Interval | Teardown | Target state |
|---|---|---|---|---|
| P1 | `views/connection/useConnectionManager.ts:126-128` | 10000 ms | `stopAutoRefresh` `:132-137`, `onUnmounted` `:271-273` | `allConnectionStatuses` (+`connectionStatus` if selected) |
| P2 | `views/connection/components/ConnectionDetail.vue:266-271` (inside `watch(props.accountId)`, `:261`) | 10000 ms | `:275-278` (null branch), `onBeforeUnmount:282-285` | `connectionStatus`, `operationLogs` |
| P3 | `views/connection/ConnectionDetail.vue:298-304` (inside `watch(accountId)`, `:293`) | 10000 ms | `:307-311`, `onBeforeUnmount:314-316` | same as P2 (mobile page) |
| P4 (dead) | `views/connection/components/ConnectionDetailDialog.vue:268-283` | **5000 ms** | `handleClose:259-266`, `onBeforeUnmount:289-294` | same |

Plus two 1000 ms **clock** intervals that exist only to re-render countdowns: `views/connection/ConnectionDetail.vue:70-72` (+teardown `:74-77`) and `components/ConnectionDetail.vue:255-259` (+`:282-285`).

**Polled payload/terminal conditions**: there is **no terminal status** — polling never stops based on `connected`, `cookieStatus`, or `riskGuard`; it stops only on unmount/account change. Each tick re-issues a full `POST /websocket/status` (plus, in P2/P3, a 20-row operation-log query).

**Enforcement**: `if (statusInterval) clearInterval(statusInterval)` before re-creating (P2 `:265-266`, P3 `:297-298`) → idempotent single timer per component. In-flight de-duplication: **none**.

**Defects**

1. **Stale-response overwrite (P1)**: `selectAccount()` (`useConnectionManager.ts:116-120`) fires `loadConnectionStatus(accountId)`; the awaited handler assigns `connectionStatus.value = response.data` unconditionally (`:95`) without re-checking `selectedAccountId`. Select account A then B quickly → A's slower response wins and the detail pane shows A's credentials under B's name. `loadAllConnectionStatuses` *does* guard (`:77`), so the two writers to the same ref disagree about staleness rules.
2. **`statusLoading` cleared by silent polls**: `loadConnectionStatus(silent=true)` still runs `finally { statusLoading.value = false }` (`useConnectionManager.ts:110-112`, `components/ConnectionDetail.vue:70-72`, `ConnectionDetail.vue:101-103`). A user-triggered refresh's spinner disappears up to 10 s early, so the "refresh" affordance lies.
3. **No in-flight guard**: if `loadAllConnectionStatuses` (sequential, one request per account, `:68-85`) takes > 10 s with N accounts, ticks queue and pile up; N accounts × 1 req/10 s grows unbounded for large N. Same for the operation-log query in P2/P3.
4. **Dead polling machine**: `views/connection/components/ConnectionDetailDialog.vue` is imported by **no file** (`grep` over all `.ts/.vue`: zero importers). It starts a 5 s poller whose only protection against double-start is `watch(modelValue)` firing `true→false→true`; the watch has no `immediate` (`:268`), so if it were mounted with a truthy `modelValue` the interval would start and `handleClose` would be the only teardown. Dead code, but it is the copy that also owns `ManualUpdateTokenDialog` (itself dead).
5. **Duplicate mounts on desktop**: `views/connection/ConnectionDetail.vue` renders `<DesktopDetail v-if="!isMobile">` **and** its own `ManualUpdateCookieModal`/`QRUpdateDialog`/`CaptchaGuideDialog` outside the `v-if/v-else` (`:511-520`), so on desktop the dialogs exist twice (once inside `components/ConnectionDetail.vue:414-425`, once in the page) — two independent dialog state machines for the same intent.
6. The countdown interval in `components/ConnectionDetail.vue:255-259` is created in `onMounted` but its `now` value is only meaningful when `riskGuard.retryAt` exists; with no risk guard it re-renders the whole detail panel once per second forever.

---

## M05. QR-login polling state machine (4 copies — 3 live, 1 dead)

**Purpose**: drive a scanned-login session from QR generation to credential capture.

**State set** (`types/index.ts:73-77`): `status ∈ {'pending','scanned','confirmed','expired'}` + `statusText: string` (display text kept as *separate* state — duplicated truth) + `sessionId` + `qrCodeUrl`.

**Copies**

| Copy | File:line | Poll period | Re-entrancy guard | Stops before handling? |
|---|---|---|---|---|
| A. accounts QR add | `views/accounts/components/QRLoginDialog.vue:57-88` | 2000 ms | **none** | **no** |
| B. QR update cookie | `views/connection/components/QRUpdateDialog.vue:52-90` | 2000 ms | `pollRequestPending` (`:26,58,87`) | yes (`:75`) |
| C. cookie refresh (dead) | `views/connection/components/RefreshCookieDialog.vue:53-82` | 2000 ms | **none** | **no** |
| D. dead | `views/connection/components/RefreshCookieDialog.vue` superseded by B | | | |

**Transitions (all copies, with per-copy deltas)**

| From | Trigger | To | Where |
|---|---|---|---|
| (closed dialog) | `modelValue → true` → `generateQR()` | `pending`, QR shown, poll starts | A `:31-40,42-55,57`; B `:28-34,36-50,52`; C `:30-36,38-51,53` |
| `pending` | poll returns `pending` | `pending` (text 等待扫码...) | A `:68-69`; B `:67-68`; C `:62-63` |
| `pending` | `scanned` | `scanned` | A `:71-72`; B `:70-71`; C `:65-66` |
| `scanned` | `confirmed` | `confirmed` → credential write → close | A `:74-77`; B `:73-77`; C `:68-71` |
| any | `expired` | `expired`, **stop polling** | A `:78-81`; B `:78-81`; C `:72-75` |
| `confirmed` | `getQRCodeCookies` / `updateCookie` failure | **close dialog** (no error state kept) | A `:100-105,111-115,129-135`; B `:102-107,110-115,122-127`; C `:94-99,106-110,120-125` |
| `confirmed` | network throw | close + toast | A `:136-140`; B `:135-139`; C `:134-138` |

**Enforcement**: `stopPolling()` on dialog close (A `:90-95`, `:143-146`; B `:92-97`, `:142-145`; C `:84-89`, `:141-144`) and on `modelValue→false` watchers. **Only copy B** also (a) sets `pollRequestPending` to block overlapping ticks and (b) calls `stopPolling()` *before* `await handleLoginSuccess()`.

**Defects**

1. **Re-entrant `confirmed` (A, High)**: `case 'confirmed'` calls `await handleLoginSuccess()` without stopping the timer (A `:74-77`). `handleLoginSuccess` performs 2–3 network calls (A: `getQRCodeCookies` → `addAccount`; C: `getQRCodeCookies` → `updateCookie`) which routinely exceed the 2 s tick. Each tick re-enters and **creates a second Xianyu account** (A `:119-123`) or **re-writes the cookie twice** (C `:115-118`). B is protected only because of `:75` + `:26/58/87`.
2. **`status` and `statusText` are two states for one fact** (A `:27-28`, B `:23-24`, C `:26-27`). `status` is only used for a CSS class (`B:164`, `C:166`); `statusText` is what the user reads. On reopen, `status` is not reset to `pending` (A `:42-55`, B `:36-50`, C `:38-51` set neither), so a reopened dialog can render the previous session's `is-success` tag while the new QR is still `pending`.
3. **No `expired → regenerate` transition.** `expired` is terminal for the session (A `:80`, B `:80`, C `:74`) with no "刷新二维码" action in any of the three templates; the user must close and reopen (which does not reset `status`, see #2). Unreachable-to-recover state.
4. **`code` checks are inconsistent**: C requires `code === 200` only (`:42`, `:57`, `:95`, `:120`), while A/B accept `0 || 200` (`:45,63`, `:39,62`). A backend returning `0` makes copy C *silently do nothing*: QR never appears, no error toast (`generateQR` has no `else` branch, `C:41-46`).
5. **Every failure collapses to "close"** (A `:129-135`, B `:122-127`, C `:120-132`): there is no `FAILED` state, so a transient `updateCookie` failure discards the (possibly valid) scanned credential with only a toast.
6. **Copy C's account-mismatch branch is a state lie**: when `scannedUnb !== props.currentUnb` it toasts "…已刷新或新增账号" and `emit('success')` (`C:127-132`) although it issued **no** write — the parent then reloads status (a no-op) and the user believes a refresh happened.
7. A has no `pollRequestPending` **and** a 2 s period with a 3-call chain ⇒ also multi-start of the poll if `generateQR` is re-entered while open.
8. Dead copies: `RefreshCookieDialog.vue` and `MobileCredentialModal.vue` are imported by nothing; `ConnectionDetailDialog.vue` (the only consumer of `ManualUpdateTokenDialog.vue`) is also orphaned.

---

## M06. Captcha solve task machine (+ manual-frame sub-machine)

**Purpose**: run/monitor a server-side slider-captcha solve (auto or manual-browser) until a terminal status.

**Files/state**: `views/connection/components/CaptchaGuideDialog.vue`; types in `api/websocket.ts:90-127`.

| State | Line |
|---|---|
| `selectedMode: 'AUTO'|'MANUAL_BROWSER'|'COOKIE'` | `:64` (union at `:27`) |
| `taskStatus: CaptchaTaskStatus | null` | `:65` |
| `loading` (start in flight), `cancelling` | `:66-67` |
| `pollError`, `manualFrameError` | `:68, :71` |
| `now` (clock for elapsed/remaining) | `:69` |
| `manualFrame`, `dragging`, `dragSubmitting`, `dragPoints`, `dragFrameVersion` | `:70-77` |
| timers `pollTimer` (timeout-chain), `clockTimer` (interval), `frameTimer` (timeout-chain), `frameRequesting` | `:78-81` |
| derived `taskRunning`, `running`, `manualTaskRunning` | `:83-90` |

**State set (server enum, verified identical to backend `service/CaptchaSolveService.java:14-22`)**: `PENDING | RUNNING | SUCCEEDED | FAILED | TIMEOUT | UNSUPPORTED | CANCELLED`; `mode ∈ {AUTO, MANUAL_BROWSER}`; `phase` — 21 literals, all present in both the label map (`:38-60`) and backend `reportProgress(...)` calls (verified: `QUEUED, CHECKING_ENVIRONMENT, STARTING_BROWSER, PAUSING_RECONNECT, REFRESHING_CHALLENGE, RECONNECTING, OPENING_PAGE, FINDING_SLIDER, DRAGGING_SLIDER, WAITING_RESULT, RETRYING_SLIDER, RESETTING_SESSION, WAITING_BROWSER, WAITING_MANUAL, WAITING_LOGIN, WAITING_SLIDER, CAPTURING_MANUAL_FRAME, REPLAYING_MANUAL_DRAG, COLLECTING_COOKIE, UPDATING_COOKIE, VALIDATING_CREDENTIAL`).

**Transitions**

| From | Trigger (`file:line`) | To | Guard |
|---|---|---|---|
| closed | `modelValue→true` (`:126-144`) | `taskStatus=null`, mode=AUTO, `startClock()`, `resumeActiveTask()` | none |
| `null` | `handleAction` → `solveCaptcha` returns status (`:153-179`, `:173`) | server status | `selectedMode !== 'COOKIE'`, `accountId` |
| `null` | `resumeActiveTask` finds PENDING/RUNNING (`:370-396`) | that status | server `status ∈ {PENDING,RUNNING}` (`:376-377`) |
| `PENDING/RUNNING` | poll returns next status (`pollStatus :190-212` → `handleTaskStatus :214-243`) | any | — |
| `PENDING/RUNNING` | `SUCCEEDED` (`:218-224`) | `SUCCEEDED` → `emit('success')`, `handleClose()` | clears frame polling |
| `PENDING/RUNNING` | `FAILED|TIMEOUT|UNSUPPORTED` (`:225-231`) | terminal, **polling cleared, dialog stays open** | — |
| `PENDING/RUNNING` | `CANCELLED` (`:232-236`) | terminal, dialog stays open | — |
| `PENDING/RUNNING` | user `handleCancel` (`:350-368`) | server returns CANCELLED | `taskRunning && !cancelling` |
| RUNNING + MANUAL_BROWSER | `scheduleFramePolling` (`:411-415`, chain `pollManualFrame :245-268`) | frame refreshes every ≥700 ms | `manualTaskRunning && modelValue && !dragging` |
| RUNNING | drag submit `handlePointerUp` (`:283-315`) | `handleTaskStatus(response.data)` | `points.length ≥ 2`, `!dragSubmitting` |
| any | clock 1 s (`:425-431`) | re-render only | — |

**Enforcement**: a single `handleTaskStatus` reducer (`:214-243`) is the *only* writer of `taskStatus` — the best-designed machine in the codebase. `clearPolling()` is called at the top of the reducer (`:217`) so a terminal status cannot leave a live timer; `schedulePolling` re-checks `taskRunning` (`:397-401`); `resumeActiveTask` re-attaches after reopen (`:370-396`); `onBeforeUnmount` clears all three timers (`:440-444`).

**Defects**

1. **No client-side deadline.** `deadlineAt` is only used for a countdown (`:98-101`, clamps to 0); when the server task hangs in `RUNNING` past its own deadline the UI keeps polling every 1 s (plus frames every 700 ms) indefinitely. No `TIMEOUT`-by-client terminal and no attempt cap — `maxAttempts` is display-only (`:108-111`).
2. **`FAILED/TIMEOUT/UNSUPPORTED/CANCELLED` leave the dialog open with `taskStatus` set** (`:225-236`). `taskRunning` is then false and `response.data.mode` overwrote `selectedMode` (`:216`), so the radio group is re-enabled — but the terminal status block (`:247-260`) keeps showing "验证失败" while the user starts a new task; the *displayed* status is stale until the next `solveCaptcha` response.
3. **`resumeActiveTask` gives up silently**: on `'未找到滑块验证任务'` it returns without scheduling any retry (`:390-393`) — if the server has not yet registered the task (race right after `solveCaptcha`) or dropped it after a restart, the dialog sits at `taskStatus=null` forever with no poll.
4. **`'COOKIE'` is a client-only pseudo-mode** (`:27`) that shares the `selectedMode` ref with a server-driven enum. `handleTaskStatus` writes `selectedMode = status.mode` (`:216`), so a server status silently *teleports* the user's radio selection — the COOKIE branch's `cookieGuideVisible` UI (`:154-157, :214-222`) can be replaced mid-flow.
5. **Retry chains are not session-scoped**: `resumeActiveTask`'s retry (`:392`) is guarded only by `props.modelValue && !taskRunning`, not by a dialog-session id, so a reopen starts a **second** retry chain while the old one is still pending on the network (no `dialogSession` counter, unlike `UpdateDialog`).
6. `elapsedSeconds` uses `finishedAt || now` (`:92-96`) — after a cancel the clock keeps ticking in `now` yet is masked; two different notions of "ended" coexist.

---

# Group C — System update

## M07. Server-driven update task machine

**Purpose**: display and drive `IDLE → … → SUCCESS|FAILED` for self-update.

**Files/state**: `components/layout/UpdateDialog.vue`.

| State | Line |
|---|---|
| `visible`, `loading`, `submitting`, `localError` | `:18-21` |
| `updateTask: SystemUpdateStatus | null` | `:22` |
| `updateInfo` (version/availability) | `:23-30` |
| `updateTask.status` union (10 literals) | `api/system.ts:20-21` |
| `stageLabels` map | `UpdateDialog.vue:49-60` |
| `pollTimer` (2 s interval) + `dialogSession` counter | `:89-90` |
| derived `isUpdateRunning` (`active`), `progressPercent`, `stageLabel` | `:42-64` |

**State set**: `IDLE | REQUESTED | CHECKING | DOWNLOADING | VERIFYING | INSTALLING | RESTARTING | HEALTH_CHECKING | SUCCESS | FAILED`, plus side-band booleans `available`, `requestPending`, `active`, `canRetry` (`api/system.ts:13-28`).

**Transitions**

| From | Trigger (`file:line`) | To | Guard |
|---|---|---|---|
| (closed) | `open()` (`:131-172`) | `visible=true, loading=true`, fetch version+status | `dialogSession` snapshot (`:132`) |
| any | `updateInfo` present && `updateTask.active` (`:158-162`) | polling started | — |
| any | `updateTask.active === false` (`:117-119`) | polling stopped | — |
| any | `refreshTask` succeeds (`:114-120`) | `updateTask = server` | `visible` (`:109`) |
| `SUCCESS` && version ≠ app | `reloadAfterSuccessfulUpdate` (`:99-106`) | `window.location.reload()` | re-reads `/system/version` (`:102-104`) |
| `IDLE/FAILED` | `startUpdate()` (`:180-204`) | `submitting=true`, then polling | `!submitting && !isUpdateRunning && hasUpdate` (`:181`) |
| any | `close()` (`:174-178`) | `visible=false`, `dialogSession++`, stop polling | — |

**Enforcement**: `dialogSession` (`:132,142,164-170,182,187-190`) correctly discards responses from a closed/reopened dialog; `startPolling` is idempotent (`:127`); polling survives request errors by design (`:121-123`, documented as "service is restarting").

**Defects**

1. **Unhandled status renders blank**: `stageLabel` indexes the map with a runtime string (`:62-64`). `SystemUpdateStatus['status']` is compile-time only; a backend value added later (e.g. `ROLLBACK`) yields `undefined` → empty label while the progress bar still animates.
2. **`FAILED` is not terminal for polling.** `stopPolling()` is tied to `active` (`:117`), not to status. If the backend keeps `active === true` after `FAILED` (retryable per `canRetry`, `api/system.ts:17`), the dialog polls forever and the button label flips between 重新尝试/更新进行中 from two different sources (`:99` vs `:107`).
3. **Two polls possible**: `startPolling` guards on `pollTimer !== undefined` (`:127`), but `refreshTask` also calls `stopPolling()` (`:110,118`) while an interval callback is in flight.
4. `appVersion` falls back to a **hard-coded literal** `'2.1.1'` (`:16`) when the build-time define is missing, so the "已是最新版本" comparison (`:101`) can be wrong and suppress the reload after a real update.
5. `updateInfo` is synthesised from `updateTask` when check-update fails (`:145-154`) with `hasUpdate: status !== 'SUCCESS'` — i.e. `FAILED` becomes "发现新版本", enabling the 立即更新 button for a task that just failed.

---

# Group D — Goods

## M08. Goods list refresh / detail-sync progress machine

**Purpose**: run a bulk platform re-sync and reflect progress until completion.

**Files/state**: `views/goods/useGoodsManager.ts` (`:25-26` `loading`/`refreshing`, `:48-50` `syncProgress`/`syncing`/`syncProgressTimer`), consumed at `views/goods/index.vue:23-25, 337-338, 424-425, 436-445`.

**State set**: `refreshing ∈ {true,false}`, `syncing ∈ {true,false}` (both **orthogonal** though they describe one operation), `syncProgress: SyncProgressResponse | null` with `isCompleted`, `isRunning`, `completedCount/totalCount`, `successCount/failedCount` (`api/goods.ts:71-84`).

**Transitions**

| From | Trigger (`file:line`) | To | Guard |
|---|---|---|---|
| idle | `handleRefresh()` (`:150-176`) | `refreshing=true` | `selectedAccountId` |
| `refreshing` | response `success && syncId` (`:161-162`) → `startSyncPolling` (`:81-87`) | `refreshing=true, syncing=true`, 1 s poll | none |
| `refreshing` | `success && !syncId` (`:163-166`) | `refreshing=false` | — |
| `refreshing` | `!success` (`:167-170`) / throw (`:172-175`) | `refreshing=false` | — |
| `syncing` | poll: `isCompleted || !isRunning` (`:65-73`) | `syncing=false, refreshing=false`, toast, `loadGoods()` | — |
| `syncing` | poll throws (`:76-78`) | **stays `syncing=true`**, timer keeps running | — |
| `syncing` | component unmount (`:89-91`) | timer cleared, **flags stay true on the abandoned instance** | — |

**Enforcement**: `stopSyncPolling()` before every restart (`:82`); terminal detection is `isCompleted || !isRunning` (`:65`) — tolerating both server flags.

**Defects**

1. **Unrecoverable stuck "syncing"**: `pollSyncProgress`'s catch (`:76-78`) neither stops the timer nor resets flags. One 5xx (or session expiry) after a sync starts leaves `syncing=true` forever ⇒ the refresh button is permanently disabled (`index.vue:425`) and the progress banner stays at its last percentage (`index.vue:436-445`). No attempt cap and no `syncId` staleness check.
2. **`checkSyncing` is imported but never called** (`useGoodsManager.ts:14`; API defined at `api/goods.ts:296-301`). Consequence: navigating away mid-sync and back yields `syncing=false` while the backend is still syncing — the UI shows an idle machine in a busy backend, and a second `refreshGoods` can be issued concurrently. This is a **missing `INIT → RUNNING` rehydration transition** (the server literally exposes it).
3. **Two flags, one operation**: `refreshing` is set by `handleRefresh` (`:155`) and cleared only in the poll terminal (`:68`) or the non-sync branches; a sync started from another code path would leave `refreshing` true. `index.vue` renders both as one label (`:337-338`, `:424-425`) so the duplication is invisible until they disagree.
4. **No in-flight guard on the 1 s poll** (`:84-86`): a slow `getSyncProgress` overlaps the next tick; two responses can arrive out of order and the older one wins (`syncProgress.value = response.data`, `:64`).
5. `totalCount` is used as a divisor in the template (`index.vue:443`, `completedCount / totalCount`) with no zero guard — a `totalCount = 0` payload yields `Infinity%`/`NaN` width.

## M09. Goods pull-to-refresh touch machine (true 4-state FSM)

**Purpose**: mobile pull gesture → refresh.

**State**: `views/goods/index.vue:224` `pullRefreshState = ref<'idle'|'pulling'|'ready'|'refreshing'>('idle')`; companions `pullDistance` (`:225`), `startY` (`:227`).

**Transitions**

| From | Trigger (`file:line`) | To | Guard |
|---|---|---|---|
| any | `touchstart` at scrollTop 0 (`:242-251`) | `idle`, `startY` set | `isMobile && tableWrapRef && scrollTop===0` |
| `idle` | `touchmove`, distance 1–59 px (`:254-269`) | `pulling` | same |
| `idle|pulling` | distance ≥ 60 px (`:267-269`) | `ready` | same |
| `ready` | `touchend` (`:275-291`) | `refreshing` → `await handleRefresh()` → `idle` | `pullDistance ≥ 60` |
| `pulling|ready` | `touchend` < 60 px (`:284-288`) | `idle` | — |

**Enforcement**: distance threshold double-checked (`:278`); back-navigation is unconditional to `idle`.

**Defects**

1. **`refreshing` is not a real state**: it is entered (`:279`) and left (`:283`) inside the same `await`, but `handleRefresh` returns as soon as the *sync job is registered* (`useGoodsManager.ts:161-162`) — so the visible `refreshing` state lasts a few hundred ms while `syncing` (M08) runs for minutes. The pull-to-refresh class (`index.vue:460`) therefore never reflects actual progress.
2. **Gesture can hijack the state**: `handleTouchStart` unconditionally writes `idle` (`:249`) — a second finger tap during `refreshing` erases the state that `handleTouchEnd` will later branch on.
3. `startY === 0` is used as the "no gesture" sentinel (`:255`), which is also a legal touch coordinate — the machine can refuse to start near the top edge of a small viewport.
4. No `ERROR` state; a thrown `handleRefresh` (swallowed internally, `useGoodsManager.ts:172-175`) leaves the gesture machine in `idle` with no feedback.

## M10. Product-publish wizard (4 steps)

**Purpose**: create a publish plan then publish.

**State**: `views/product-publish/index.vue:12-13` `step = ref(1)`, `maxStep = ref(1)`; `:14-15` `loading`/`uploading`; `:16-17` `publishRequestId`/`publishFingerprint`; step body selected by `v-if/v-else-if` on the number (`:155-189`).

**State set**: `step ∈ {1,2,3,4}`, `maxStep ∈ {1..4}`, invariant `maxStep ≥ step` (unenforced).

**Transitions**

| From | Trigger (`file:line`) | To | Guard |
|---|---|---|---|
| 1 | `next()` (`:94-100`) | 2 | `name && description` (`:95`) |
| 2 | `next()` | 3 | `amount && images.length` (`:96`) |
| 3 | `next()` | 4 | `accountId && divisionId && gps` (`:97`) |
| 4 | `next()` | 4 (clamped) | `Math.min(4, …)` (`:98`) |
| 2..4 | `goStep(target)` tab click (`:102-104`, template `:151`) | `target` | `target <= maxStep` |
| 2..4 | `@click="step--"` back button (`:191`) | `step-1` | rendered only if `step > 1` |

**Enforcement**: `maxStep` as a monotone high-water mark; `publishRequestId` regenerated whenever the serialized form changes (`:106-115`) → idempotency token for the backend.

**Defects**

1. **Guard bypass / reachable invalid state**: validation runs **only** inside `next()` (`:95-97`). After reaching step 4, the user can click the step-1 tab (`goStep`, allowed since `maxStep === 4`), clear the title, and click the step-4 tab — `goStep(4)` performs **no validation** → the summary renders empty and `submit()` (`:117-140`) re-validates nothing.
2. **`step` and `maxStep` are plain numbers** with no named enum; `step--` (inline in the template, `:191`) bypasses the only writer function and can never lower `maxStep`, so a completed wizard cannot be reset (`maxStep` stays 4 forever, including after a successful publish).
3. **No `PUBLISHED` terminal state**: after success (`:134-136`) the wizard stays on step 4 with the form intact; a second click reuses `publishRequestId` (fingerprint unchanged) — depending on backend semantics this either dedupes silently (no feedback) or double-publishes.
4. `submit(true)` (dry-run) and `submit(false)` share one `loading` (`:118,138`) — no way to tell which operation the spinner belongs to; `dryRun` also does not consume the request id (`:124`).
5. `createPublishPlan` responses are checked with `response.data?.valid === false` (`:126`) and `localSynced === false` (`:131`) but the request wrapper already rejects non-0/200 codes (`utils/request.ts:86-93`), so a `valid:false` payload arrives via the *success* path and returns `toast.error` while `loading` is still set (released in `finally`, `:137-139`) — no state records "plan invalid".
6. `uploading` (`:15`) is a second in-flight flag that does not disable the step-next buttons (`:192`), so `next()` can be pressed mid-upload and the step-2 guard (`:96`) sees a partially populated `images`.

## M11. Opportunities wizard (near-duplicate of M10)

**Purpose**: capture → rewrite → configure → publish an opportunity.

**State**: `views/opportunities/index.vue:21-26` `step`, `maxStep`, `searched`, `pageNumber`, `hasMore`, `total`; `:13` `sourceMode: 'keyword'|'shop'` (a 2-state source switch that **resets the whole result set** via `resetResults()` on `@change`, `:209`).

**Transitions**

| From | Trigger (`file:line`) | To | Guard |
|---|---|---|---|
| 1 | `capture()` success (`:112-127`) | 2, `maxStep=2` | `selectedCandidates.length` (`:113`) |
| 2 | `next()` | 3 | `name && description` (`:130`) |
| 3 | `next()` | 4 | `amount && images && divisionId && gps` (`:131`) |
| any ≤maxStep | `goStep` (`:136-138`) | target | `target <= maxStep` |
| 1 | `search(append)` (`:74-103`) | `searched=true` | keyword/url/account non-empty (`:75-77`) |
| results | scroll `search(true)` | append page | `hasMore` (template) |

**Enforcement**: same high-water-mark as M10; `results` deduped by `itemId` on append (`:89`).

**Defects**

1. **`maxStep` regresses**: `capture()` assigns `maxStep.value = 2` (`:126`) instead of `Math.max`. Capturing a second candidate after having reached step 4 **locks the user back to step 2** (later tabs become `disabled`, `:200`), while `step` may still be 4 → `step > maxStep` violated; the tab bar then shows no active tab.
2. **Same guard bypass as M10**: `goStep` never validates; the only checks live in `next()` (`:130-131`).
3. **`loading` shared by 4 unrelated operations** (`search :79`, `polish :159`, `generateImage :175`, `publish :142`) — a long AI image generation blocks/blurs search feedback and vice-versa; no per-operation state.
4. `search()` never resets `step`/`draft`, so replacing the result set keeps a draft derived from the *previous* candidate (source of accidental re-publish of stale content).
5. `capture()` uses only `selectedCandidates.value[0]` (`:115`) while `selectedIds` is a multi-select — the wizard silently drops all but the first selection.
6. `searched` is only ever set `true` (`:94`) and by `resetResults` (`:68`) — there is no loading-more vs loaded-empty distinction.

---

# Group E — Automation configuration

## M12. Auto-delivery config editor (dirty-snapshot + generation counters)

**Purpose**: for one (account, goods, sku) tuple: load config, edit, save, and prevent switching away from unsaved edits.

**Files/state**: `views/auto-delivery/useAutoDelivery.ts`

| State | Line |
|---|---|
| `selectedAccountId` / `activeAccountId` (committed vs pending) | `:64, :77` |
| `selectedGoods`, `selectedSkuId`, `currentConfig` | `:66, :70, :67` |
| `skuList`, `skuConfigs: Map` | `:69, :71` |
| `skuLoading`, `skuSyncing`, `skuLoadError` | `:72-74` |
| `configLoading`, `configLoadError` | `:75-76` |
| `configForm` (10-field object) + `resetDeliveryConfigFields` | `:91-113` |
| `savedConfigSnapshot` (serialized form) + `hasUnsavedChanges` | `:114, :128-129` |
| `confirmDiscardChanges` / `markConfigSaved` | `:130-132` |
| generation counters `skuRequestVersion`, `skuConfigRequestVersion`, `configRequestVersion` | `:78-80` |
| `saving`, `loading` (shared with trigger-delivery) | `:61-62` |
| `confirmDialog` (visible + title/message/onConfirm) | `:210-217` |

**State set**: `deliveryMode ∈ {1 = 固定内容, 2 = 卡密}`; `voucherDeliveryEnabled/chatDeliveryEnabled ∈ {0,1}` (must not both be 0, enforced at `:696-699`); `autoConfirmShipment ∈ {0,1}`; dirty ∈ {clean, dirty}; per-sku configured ∈ {configured, unconfigured} (`configuredSkuCount`, `:145-146`).

**Transitions (selection axis)**

| From | Trigger (`file:line`) | To | Guard |
|---|---|---|---|
| any goods | `handleAccountChange` (`:393-415`) | cleared selection, bumped all 3 versions | `confirmDiscardChanges()` (else reverts `selectedAccountId` to `activeAccountId`, `:394-397`) |
| A | `selectGoods(B)` (`:479-510`) | B, `skuConfigs={}`, form reset | `confirmDiscardChanges()` (`:481`); no-op if same id (`:480`) |
| sku S1 | `handleSkuChange(S2)` (`:512-518`) | S2, `configLoading` | `!saving && !configLoading && S2!==S1` (`:513`), `confirmDiscardChanges()` (`:514`) |
| any | `toggleOnlyOnSale` (`:1022-1029`) | goods list reset | `confirmDiscardChanges()` |
| dirty | beforeunload (`:259-263`) | browser prompt | `hasUnsavedChanges` |

**Transitions (save axis)**

| From | Trigger | To | Guard |
|---|---|---|---|
| clean/dirty | `saveConfig` (`:672-773`) | `saving=true` → `markConfigSaved()` | 11 pre-conditions (`:673-717`): selection, `!skuLoading && !configLoading`, `!skuLoadError && !configLoadError`, sku chosen, skuCount>1 → sku list synced, fixed template chosen, kami bound, at least one delivery channel, template contains `{deliveryContent}`, ≤500 chars, interval 5–600 |
| saving | response ok (`:757-766`) | clean | — |
| saving | response error (`:768-771`) | dirty preserved | toast only |

**Transitions (per-sku config progress)**

| From | Trigger | To | Guard |
|---|---|---|---|
| unconfigured | save success with sku (`:760-762`) → `loadAllSkuConfigs` (`:447-477`) | configured | `requestVersion === skuConfigRequestVersion` (`:460,473`) |

**Enforcement**: **the strongest in the codebase** — three monotonic generation counters plus an identity re-check `isCurrentGoods(accountId, goodsId)` (`:133-134`) after every await (`:429,439,460,473,496,501,551,604,621,671`), `skuId !== selectedSkuId` re-check (`:605`), and `confirmDiscardChanges` on every navigation transition. `loadConfig` also marks the snapshot saved (`:658`) so the dirty flag has no false positive after load.

**Defects**

1. **`savedConfigSnapshot` cannot represent "nothing loaded yet" cleanly**: `hasUnsavedChanges` requires `snapshot !== ''` (`:128-129`). After a load failure the snapshot is cleared (`:669`) → dirty detection is disabled, so `confirmDiscardChanges` (`:130-132`) silently allows discarding; conversely `handleAccountChange` resets the snapshot to `''` (`:402`) — "unknown" and "clean" are conflated into one string value.
2. **Optimistic toggles without full rollback**: `toggleAutoDelivery` (`:774-809`) writes `selectedGoods.xianyuAutoDeliveryOn` and the matching `goodsList` entry; on failure it reverts **only** `selectedGoods` (`:806-808`), leaving `goodsList` showing the wrong state until the next `loadGoods`. Same for `toggleAutoConfirmShipment` (`:811-838`, reverts only `configForm`).
3. **No in-flight guard on either toggle**: `saving`/`loading` are not checked (`:774`, `:811`) ⇒ double-click issues two PUTs whose responses race; the late-arriving failure rollback can invert a *successful* toggle.
4. **`saveConfig` hard-codes `autoConfirmShipment: 1`** (`:751`) instead of `configForm.value.autoConfirmShipment`, so saving the delivery config silently turns on auto-confirm-shipment — a state transition the user never requested (and the independent toggle at `:811` can then switch it off, leaving UI and DB disagreeing).
5. **`loading` is a mixed-purpose flag**: reset per row in `confirmDialog.onConfirm` for trigger-delivery (`:990, :1011`) *and* read as a mutual-exclusion gate (`:1057-1060`, `:1062`). It does not cover `handleConfirmShipment` (`:897-938`) at all, so two different write operations on the same order can run simultaneously.
6. `confirmDialog` is a **single-slot dialog state**: opening a second confirm while one is pending overwrites `onConfirm` (`:1007-1014`), so the confirm button may execute a different action than the message shown.

## M13. Auto-reply automation toggle cluster (5 tri-states, one shared endpoint)

**Purpose**: per-goods switches for AI reply, context carry, keyword reply, human intervention, intervention minutes.

**Files/state**: `views/auto-reply/useAutoReply.ts`

| State | Line | Values |
|---|---|---|
| `xianyuAutoReplyOn` | `toggleAutoReply` `:798-839`, `toggleContextOn` `:841-879` | `0|1` |
| `xianyuAutoReplyContextOn` | `:798-879` | `0|1|undefined` (auto-promoted to 1 on enable, `:817-819`) |
| `xianyuKeywordReplyOn` | `toggleKeywordReply` `:402-436` | `0|1` |
| `humanInterventionOn` | `toggleHumanIntervention` `:438-473` | `0|1` |
| `humanInterventionMinutes` | `updateHumanInterventionMinutes` `:475-511` | `1..120` |
| mirrors in list | `goodsList` entry updated at `:415-418, 456-459, 496-499, 829-836, 860-866` | |

**Transitions** (each toggle is an independent transition on the *same* aggregate server object)

| From | Trigger (`file:line`) | To | Guard |
|---|---|---|---|
| off | `toggleAutoReply(true)` (`:798`) | on (local after 200) | selection present (`:799-802`) |
| any | failure (`:836-838`) | inverted locally | — |
| off | `toggleKeywordReply(true)` (`:402`) | on | selection |
| off | `toggleHumanIntervention(true)` (`:438`) | on | selection |
| any | `toggleContextOn(v)` (`:841`) | v | selection |
| minutes | `updateHumanInterventionMinutes(m)` (`:475`) | m | `1 ≤ m ≤ 120` (`:477`), silent no-op otherwise |

**Enforcement**: optimistic write + inverted-absolute rollback on error (`:836-838`, `:431-434`, `:468-471`, `:875-878`). No serialization between the five toggles.

**Defects**

1. **Partial-aggregate PUTs (High)**: each request sends a *different subset* of the five fields. `toggleContextOn` sends only `xianyuAutoReplyOn` + `xianyuAutoReplyContextOn` (`:849-856`); `toggleKeywordReply` sends three fields (`:408-414`); `toggleHumanIntervention` sends four (`:444-452`). Any backend treating an omitted field as "default/reset" silently clears the switches this call did not mention. Even with patch semantics, the *responses* race: two toggles 200 ms apart interleave their local writes with stale `selectedGoods` reads (`:409-410`, `:445-447`), producing a server state no single click sequence asked for.
2. **Rollback inverts an absolute value**: `selectedGoods.value.xianyuAutoReplyOn = value ? 0 : 1` (`:837`, same idiom `:432`, `:469`, `:876`) — if the first of two overlapping toggles fails, it writes `1` over a state the second toggle already set to `0`. No compare-and-set against the pre-click value.
3. **No in-flight flag at all** for these five; the only `saving` ref (`:33`) is unused by them. Double-clicks issue two requests.
4. **Tri-state vs binary**: `xianyuAutoReplyContextOn` is explicitly treated as possibly `undefined` (`:809, :817-819`) and promoted to `1` when enabling auto-reply — a hidden third state ("unset") that renders as `未开启` in lists (`views/messages/workspace.vue:351` tests `=== 1`).
5. **`updateHumanInterventionMinutes` fails silently** (`:507-509`): out-of-range minutes are dropped with no toast and no revert, because the local value is only written on success (`:497`).
6. **AI chat sub-machine**: `chatMessages[].loading` (`:21`) + `chatSending` (`:71`). `handleSendChat` (`:1030+`) sets `assistantMsg.loading=false` **before** the stream is consumed, so the typing indicator disappears while text is still arriving; on stream error the placeholder is overwritten with `对话失败，请稍后重试` (`:1146-1150`) — a partial answer is destroyed with no `FAILED` marker. `chatSending` is reset in `finally` (`:1153`) but the guard (`:1037`) is a plain early return, so Enter-to-send during streaming is silently dropped rather than queued.
7. `loadConfig` (`:745-763`) has no generation guard: switching goods while it is in flight writes the previous goods' `ragDelaySeconds` into `delaySeconds` (`:755`).

---

# Group F — Orders & fulfillment

## M14. Delivery record status machine + per-row action flags + rate-sync generations

**Purpose**: describe the lifecycle of one delivery record and gate the actions allowed in each state.

**Files/state**

| What | File:line |
|---|---|
| server status set (literal strings) | `views/orders/order-status.ts:12-22` |
| `errorStatuses` set | `order-status.ts:24` |
| `getDeliveryStatusMeta` fallback on numeric `state` | `order-status.ts:32-37` |
| `shouldShowDeliveryError`, `canConfirmShipment` | `order-status.ts:39-58` |
| rate-status derivation (`rateSyncing`, `rateDetail.*`) | `order-status.ts:60-79` |
| per-row transient flags type | `views/orders/useOrderManager.ts:12-17` |
| flags reset on refresh | `useOrderManager.ts:204-209` |
| flag writes | `:268, 293, 315, 339`; resets `:279, 301, 321, 347` |
| rate sync generation `rateSyncSequence` + `WeakMap` | `useOrderManager.ts:169-170, 173-198` |
| duplicated 0/1 mapping (`成功`/`失败`) | `useOrderManager.ts:157-167` **and** `OrderTable.vue:115-125` **and** `:137-139` (`已确认`/`未确认`) |

**State set**: `deliveryStatus ∈ {PENDING, PROCESSING, RETRY_WAIT, DELIVERED, CONFIRMING, COMPLETED, FAILED, REVIEW_REQUIRED, SKIPPED}`; orthogonal numeric `state ∈ {0 = 待处理, 1 = 成功, -1 = 失败}`; `confirmState ∈ {1 = 已确认, other}`; `rateDetail` ∈ {undefined (未同步), synced+canRate, synced+rated, tradeStatus 已发货/交易关闭, error}; row action flags `confirming | retrying | markingDelivered | resending ∈ {true, undefined}`.

**Transitions (visible to the user)**

| From | Trigger (`file:line`) | To | Guard |
|---|---|---|---|
| any | filter change (`:233-241`) | re-query | `parseDeliveryStatuses` drops unknown values (`order-status.ts:26-30`) |
| `PENDING/PROCESSING/RETRY_WAIT/FAILED/REVIEW_REQUIRED` | `handleRetryDelivery` (`:283-303`) | re-queued | confirm dialog (`:289`) |
| `DELIVERED` | `handleConfirmShipment` (`:262-281`) | `confirmState=1` | `row.orderId` only (`:263`) — **not** `canConfirmShipment` |
| any | `handleMarkDelivered` (`:326-349`) | `state=1` (local only) | confirm dialog |
| any with `orderId` | `handleResendDelivery` (`:305-324`) | none (no refresh!) | confirm dialog |
| any | rate refresh (`:173-198`) | `rateSyncing → rateDetail` | per-record `WeakMap` request id |

**Enforcement**: `canConfirmShipment` (`order-status.ts:57-58`) exists but is **only used for rendering** (`OrderTable.vue:7, 127`); the handler (`useOrderManager.ts:262-281`) does not call it. Rate sync uses a proper per-record generation stamp (`:178-182, 187, 190, 194-196`).

**Defects**

1. **`handleConfirmShipment` bypasses its own guard**: the machine declares confirm legal only when `state === 1 && confirmState !== 1` (`order-status.ts:58`), but the action handler checks only `orderId` (`useOrderManager.ts:263`). A row that renders without the confirm button can still reach the API via a stale click target.
2. **Two vocabularies for one field**: `getDeliveryStatusMeta` says `COMPLETED/等待处理/履约失败` (`order-status.ts:32-37`) while `getStatusText` says `成功/失败` (`useOrderManager.ts:165-167`, `OrderTable.vue:123-125`). `state` is collapsed to a boolean in the latter, so `state = 2` (or any future value) renders as **失败** via `state === 1 ? '成功' : '失败'`.
3. **`getDeliveryStatusMeta`'s fallback invents states**: when `deliveryStatus` is absent it maps `state 1 → COMPLETED`, `0 → PENDING`, **anything else → FAILED** (`:32-37`). A legacy row with `state = null` is painted 履约失败 and `shouldShowDeliveryError` (`:39-40`) turns on the failure-reason row.
4. **`handleResendDelivery` has no follow-up transition**: on success it only toasts (`:317-318`), unlike confirm/retry/mark which reload (`:275, :297, :343`). The row never leaves its display state, so the user cannot tell whether the resend changed anything.
5. **`markingDelivered` is not reset in the refresh mapping** (`:204-209` resets `confirming/retrying/resending` only) — after `loadOrders()` new rows carry `markingDelivered: undefined`, benign but inconsistent with the declared type (`:15`).
6. **`rateSyncing` can be orphaned**: `loadOrders` replaces `orderList` (`:204`) while `loadRateDetails` is awaiting; the old rows keep `rateSyncing = true` forever (unreachable object graph).
7. **`loadRateDetails([target])` from the detail dialog** (`views/orders/index.vue:85-93`) reuses the global `rateSyncSequence` (`useOrderManager.ts:177`); it therefore **invalidates the page-level rate request** (whose `finally` skips those rows, `:194-196`), leaving every other row's `rateSyncing=true` until the next full refresh. Opening the rating dialog for one order blanks the rate column for the whole page.
8. `pendingRateCount` (`orders/index.vue:70`) counts `rateDetail?.canRate` across the *current page only*, while `isRetryable` (`OrderTable.vue:134-135`) and `canConfirmShipment` implement two more independent views of the same status set.

## M15. Pending-orders auto-refresh + auto-deliver loops

**Purpose**: poll pending orders and optionally auto-deliver them on a timer.

**Files/state**: `views/pending-orders/index.vue`

| State | Line |
|---|---|
| `loading`, `delivering`, `deliveringOrderId` | `:11-13` |
| `autoRefresh` (bool), `refreshInterval` (5/10/15/30/60) | `:15-17` |
| `refreshTimer`, `deliverTimer` | `:18, :21` |
| `autoDeliver` (bool), `lastDeliverCount` | `:20, :23` |

**Transitions**

| From | Trigger (`file:line`) | To | Guard |
|---|---|---|---|
| off | `autoRefresh=true` → `toggleAutoRefresh` (`:61`) | `refreshTimer` started | `autoRefresh` |
| any | interval change (`:62`) | refresh timer restarted | `autoRefresh` only — **not** `autoDeliver` |
| off | `autoDeliver=true` → `toggleAutoDeliver` (`:86`) | immediate `handleDeliver()` + `deliverTimer` (`:78-84`) | `autoDeliver` |
| idle | `handleDeliver` (`:64-76`) | `delivering=true` → API → `loadOrders()` | **none** (no `if (delivering.value) return`) |
| idle | `handleSingleDeliver` (`:91-111`) | `deliveringOrderId=orderId` | `orderId` present |

**Enforcement**: teardown on unmount (`:26`); `delivering`/`deliveringOrderId` exist for UI disabling only.

**Defects**

1. **Re-entrant money path**: `handleDeliver` has no re-entrancy guard while a dedicated `delivering` flag exists. The interval (`:82`) fires every `refreshInterval` seconds regardless of whether the previous `deliverPendingOrders` returned → overlapping bulk-delivery requests against the user's real orders. This is the most severe loop in the audit.
2. **`refreshInterval` change does not restart the deliver timer** (`:62` restarts `refreshTimer` only), so the two loops drift apart: the visible "刷新间隔 30s" can coexist with a 5 s delivery loop.
3. **`loadOrders` and `handleDeliver` both toggle `loading`** (`:43, :51, :72`) — a delivery's internal refresh clears the loading state of a user-triggered refresh, and two concurrent `loadOrders` can assign an older `pendingOrders` payload last (`:48`) with no request generation.
4. **Timer survives account switching**: `onAccountChange` (`:54`) only reloads; `deliverTimer` keeps firing against the *new* `selectedAccountId` mid-cycle, so a switch can auto-deliver on an account where the user never enabled it.
5. `lastDeliverCount` (`:23, :71`) is single-slot state reset per run (`:67`), so a failed run destroys the previous count.
6. Terminal state missing: `autoDeliver` never disables itself when there is nothing left to deliver or when the backend keeps erroring (`catch` at `:74` only logs) — an unbounded retry loop with no backoff.
7. Route is `hidden: true` in the router (`router/index.ts:130`) and absent from `MENU_GROUPS` (`config/menu.ts:45-104`) — this high-risk loop is reachable only by direct URL.

---

# Group G — Messaging

## M16. Message-list silent polling + `isNew` marker

**Purpose**: refresh the message list every 3 s (legacy page) / 10 s (current page) without disturbing the UI, and highlight new rows.

**Files/state**

| What | File:line |
|---|---|
| `loading` + `silentLoading` (two flags, one operation) | `views/messages/useMessageManager.ts:11-12` |
| `loadMessages(silent)` | `:113-165` |
| `isNew` injection + clearing | `:139-152` |
| 3 s interval (dead page) | `views/messages/index.vue:147-151`; teardown `:154-160` |
| 10 s interval (live page) | `views/messages/workspace.vue:245-253` |
| `refresh()` guard | `workspace.vue:196-209` |
| render of `isNew` | `components/MessageList.vue:106, 163` |

**Transitions**

| From | Trigger (file:line) | To | Guard |
|---|---|---|---|
| idle | user refresh (`useMessageManager.ts:118-119`) | `loading=true` | `selectedAccountId` |
| idle | poll (`:120-122`) | `silentLoading=true` | `selectedAccountId` (`index.vue:148`) |
| any | response (`:139-152`) | merged list, `total` synced | `silent && list.length>0` |
| any | error (`:156-160`) | list cleared **only if non-silent** | — |
| any | finally (`:161-164`) | **both** flags false | — |

**Enforcement**: `silent` suppresses toasts in the axios layer (`utils/request.ts:88-89`, `api/message.ts:47-52`); `workspace.refresh` guards with `refreshing || platformSyncing` (`:197`).

**Defects**

1. **Two flags cleared together (desync)**: the `finally` sets `loading.value = false` **and** `silentLoading.value = false` (`:162-163`) regardless of which operation ran. A poll tick that finishes during a user's manual refresh clears the user's spinner; conversely a manual refresh clears `silentLoading`. The two flags can never be independently true.
2. **Dead 3 s polling machine**: `views/messages/index.vue` is not routed (router points `/messages` at `workspace.vue`, `router/index.ts:105`) and is imported by nothing, yet it polls the *same* endpoints as the live page's 10 s poller with a different period.
3. **`isNew` is a latch with no expiry**: new ids are flagged (`:143`) and other rows unflagged (`:144`), but if a poll returns **no** new items the flags are never cleared (`:146-148` only updates `total`). Rows stay visually "new" indefinitely, and the flag is dropped by the next non-silent load (`:150`).
4. **`silent` merge mutates `ChatMessage` objects with server data plus a client-only field** (`:143-144`) while the non-silent path assigns raw server rows (`:150`) — `isNew` disappears on refresh, so the highlight depends on which path last ran.
5. `messageList` is ordered by insertion, not `messageTime`; the merge prepends `newItems` in server order (`:144`), so ordering differs between silent and full loads — a state difference not encoded anywhere.
6. `workspace.vue`'s `refresh()` early-returns when `platformSyncing` (`:197`) but the interval does not retry, so a long platform sync skips polls entirely; no "missed tick" state.

## M17. Context dialog paging vs. 1 s refresh (state collision)

**Purpose**: show one conversation's history with infinite scroll up and live refresh down.

**Files/state**: `views/messages/components/ContextDialog.vue`

| State | Line |
|---|---|
| `loading`, `loadingMore` | `:30, :38` |
| `messages` (the shared array) | `:31` |
| `hasMore` | `:37` |
| `sending`, `inputImageUrls`, `showImageUploader` | `:33-35` |
| `refreshTimer` (1000 ms) | `:140-154` |
| `loadContext(append)` | `:69-115` |
| `refreshMessages()` | `:156-175` |

**Transitions**

| From | Trigger (`file:line`) | To | Guard |
|---|---|---|---|
| closed | `visible→true` (`:177-184`) | `loadContext()` + `startRefresh()` | `props.sid` |
| loaded | scroll to top (`:117-133`) → `loadMore` (`:126-139`) | append older page | `loadingMore`, `hasMore`, `!loading` |
| loaded | poll tick (`:145-149`) | `messages = latest 20` (**replace**) | `props.visible && props.sid` |
| loaded | poll detects change (`:168-171`) | replace + scroll to bottom | JSON compare |
| any | `visible→false` (`:181-183`) | `stopRefresh()` | — |

**Enforcement**: `loadingMore`/`loading` flags; a JSON deep-compare before replacing (`:168`).

**Defects**

1. **The poll destroys pagination (High)**: `refreshMessages` assigns `messages.value = newMessages` (latest 20, `:169`) with no awareness of appended pages. After scrolling back through 100 messages, the first poll silently truncates the list to 20 and scrolls to the bottom (`:170`). `hasMore` stays `true` from the earlier page, while `offset = messages.value.length` (`:82`) has reset to 20 ⇒ the next scroll re-fetches page 2, duplicating it (`:95` prepends again).
2. **1 s polling with no in-flight guard**: `refreshMessages` has none (unlike `pollManualFrame`'s `frameRequesting`). On a slow backend, out-of-order responses let an older snapshot overwrite a newer one (`:169`).
3. **`hasMore` is computed from `newMessages.length >= limit`** (`:100`) *after* in-place `reverse()` (`:95-98`) — the same array is reversed while being compared (`:68`), so the "did it change" test compares against a mutated copy.
4. **No terminal/backoff on error**: `catch` is silent (`:72-74`), so a failing endpoint polls every second forever with zero UI feedback; `loading` is not even set by the poll.
5. `props.sid` is not watched (`:177` watches `visible` only) — switching conversation while the dialog is open keeps the old `sid`'s messages until the parent closes and reopens it.

---

# Group H — Operations surfaces

## M18. Kami config dialog cluster + item status

**Purpose**: manage card-key pools, their stock alerts, imports and exports.

**Files/state**: `views/kami-config/index.vue`

| State | Line | Values |
|---|---|---|
| `showCreateDialog` (+ `editingConfigId` = create vs edit mode) | `:38-39`; mode set `:127, 141, 147, 157`; close `:252` | bool / `number|undefined` |
| `showAddDialog` | `:55`; open `:543, 653`; close `:302` | bool |
| `showImportDialog` | `:51`; open `:544, 654`; close `:329` | bool |
| `showAlertDialog` | `:59`; open `:383`; close `:407` | bool |
| `showExportDialog` + `exportStatus{unused,used}` | `:68-69`; open `:420-421`; close `:460` | bool / 2 bools |
| `filterStatus` (item status filter) + `filterKeyword` | `:74-75`; reset `:227` | `number|undefined` |
| `selectedConfigId` + `lowStockOnly` (deep-link `?lowStock=1`) | `:34, :32`; clear `:117-123` | `number|null` / bool |
| item status | rendered `:462` (`status === 0 ? '未使用' : '已使用'`) | `0|1` |

**Transitions**

| From | Trigger (`file:line`) | To | Guard |
|---|---|---|---|
| closed | `openCreateDialog` (`:139-142`) | create-mode dialog | — |
| closed | `openSourceConfigDialog` (`:144-158`) | edit-mode dialog | `selectedConfig` (`:145`) |
| account change | `handleAccountChange` (`:219-223`) | `selectedConfigId=null`, items cleared | — |
| any | `loadKamiConfigs` auto-select (`:188-193`) | first visible config selected | `!isMobile` |
| low-stock filtered | `clearLowStockFilter` (`:117-123`) | filter off, auto-select | — |
| export | `handleExport` (`:424+`) | dialog closed | `unused||used` (`:426-429`) |

**Enforcement**: per-dialog loading flags (`createLoading`, `addLoading`, `importLoading`, `alertLoading`, `configLoading`, `itemsLoading`, `:31-66`), each with `finally` reset. No mutual exclusion between the five dialogs.

**Defects**

1. **Five booleans, one modal surface**: `showCreateDialog/showAddDialog/showImportDialog/showAlertDialog/showExportDialog` can all be `true` simultaneously (only the template's backdrop clicks close them individually, `:730, 789, …`). No `activeDialog` enum, so programmatic opens (`:543-544`, `:653-654`, `:383`, `:420`) can stack dialogs. `showAddDialog`/`showImportDialog` are triggered from *two* layouts (desktop side panel `:543-544`, mobile `:653-654`).
2. **Create vs edit is a mode hidden in a second variable**: `showCreateDialog` is the flag, `editingConfigId` is the mode (`:127, 147`). `resetConfigForm()` (`:126-137`) clears the id, but the overlay/× close paths set only `showCreateDialog = false` (`:730-734, 780`) and never clear `editingConfigId`; one missed reset path and "新建" silently edits an existing config. The title itself reads the mode (`:733`).
3. **Item status is not a closed set in the UI**: filter options are 0/1 only, but rendering collapses everything non-zero to 已使用 (`:462`) and export does the same. Any future status (e.g. `2 = 已作废`) is displayed as 已使用 and cannot be filtered.
4. **Alert edit rewrites the whole config object** (`:386-417`): it re-sends `aliasName`, `sourceType`, `externalApiUrl/Headers/Body/ResultPath/Timeout` from `selectedConfig`. For API-source pools the per-field "leave blank to keep" semantics (`:761` placeholder) do not apply on this path — saving an alert can blank the stored request headers.
5. **`lowStockOnly` is URL state copied into a ref once** (`:32`): never re-synced from `route.query`, while `clearLowStockFilter` (`:117-123`) writes the URL. Back/forward navigation therefore leaves the ref stale.
6. `handleDeleteConfig`/`handleDeleteItem`/`handleResetItem` wrap `showConfirm` in `try { … } catch {}` (`:269-288, 343-370`) and perform the mutation *inside* the `try` — a `showConfirm` rejection and an API rejection are both swallowed by the same empty `catch`, so failure is indistinguishable from cancellation.

## M19. Settings tab + async-operation flags + simulated progress

**Purpose**: a 7-panel settings surface with 20+ independent async operations.

**Files/state**: `views/settings/index.vue`

| State | Line |
|---|---|
| `activeMenu = ref('account')` (plain string, no union) | `:34`; **only writer is the template** `:1202` |
| per-panel saving flags: `sysPromptSaving`, `similarityThresholdSaving`, `aiApiKeySaving`, `embeddingSaving`, `imageSaving`, `emailSaving`, `menuLayoutSaving` | `:59, :66, :131, :150, :166, :186, :222` |
| testing flags: `aiTesting` + `aiTestResult` | `:134-136` |
| `emailTesting`, `emailConfigured` (derived-but-cached) | `:187-189, :812-813, :833-834` |
| `changingPassword`, `loggingOut` | `:46, :53` |
| `backupExporting`/`backupImporting` + `backupExportProgress`/`backupImportProgress` | `:897-900` |
| `logDownloading` (reset by a 2 s timer) | `:904, :953` |
| drag state `draggedGroupId`/`draggedItemGroupId`/`draggedItemId`/`lastGroupTargetId`/`lastItemTargetKey` | `:223-227` |

**Transitions**

| From | Trigger | To | Guard |
|---|---|---|---|
| any panel | menu click (`:1202`) | `activeMenu = key`; **side effect** `item.key === 'backup' && handleBackupMenuEnter()` | none |
| ai panel | any AI field edit → watch (`:216-218`) | `aiTestResult = null` | `!aiTesting` |
| ai panel | test (`:470-502`) | `aiTesting` → result | key/base/model/url/msg validated (`:464-469`) |
| password | submit (`:511-535`) | `changingPassword` | 3 pre-checks |
| account | logout (`:538-560`) | `loggingOut` → token cleared → `/login` | confirm; `finally` resets on the navigation path |
| backup | export (`:968-1014`) | `backupExporting` + progress 0→100 → request | selection non-empty |
| backup | import (`:1041-1097`) | confirm → progress → request | file + selection |
| logs | download (`:933-954`) | `logDownloading` → **2 s timer** clears it | `logSelectedDate` |

**Enforcement**: each operation has its own boolean and (mostly) a `finally` reset; `backupLoaded`/`logDatesLoaded` act as one-shot latches (`:896, :905, :906, :920`).

**Defects**

1. **Panel transition has a side effect in the template** (`:1202`): `activeMenu` is read by 7 `v-if`s (`:1213, 1318, 1535, 1611, 1771, 1879, 1982`). Any non-click way to change the panel (deep link, restore) skips `handleBackupMenuEnter()` (`:1099-1102`), so the backup panel renders empty forever because of the `backupLoaded` latch.
2. **Progress is fake and can outrun reality**: `handleExportBackup` animates to 100 % with `await new Promise(r => setTimeout(r, 100))` per module (`:985-992`) **before** issuing `exportBackup` (`:993`); `handleImportBackup` does the same (`:1060-1068`) **before** `importBackup` (`:1069`). The user sees 100 % complete for the entire duration of the real request, and `finally` resets progress to 0 (`:1010-1013, :1093-1096`) — so failure looks like "the progress bar vanished".
3. **`logDownloading` is cleared by a timer, not by the operation** (`:953`): a second click within 2 s of completion is ignored even though the machine is idle; if the download takes > 2 s the flag clears while the request is still running (double writer: `finally` + deferred `setTimeout`).
4. **`emailConfigured` duplicates server truth**: recomputed from form fields on load (`:812-813, :833-834`) rather than from a server flag, and the panel-open state `emailConfigExpanded` (`:190`) is derived from it — a config whose password is intentionally returned blank (`secret`) is treated as "not configured" and re-expanded every visit.
5. **`activeMenu` is untyped**: a typo in any `v-if` (`'prompt'` vs `'promt'`) yields a blank panel with no default branch — 7 independent `v-if`s, no `v-else` fallback (`:1213-1982`).
6. **Drag state is 5 loose refs** (`:223-227`) with no `dragging` discriminator; the handlers must infer the active drag from which of `draggedGroupId`/`draggedItemGroupId` is non-empty. `lastGroupTargetId`/`lastItemTargetKey` are hysteresis counters never cleared on a cancelled drag, so a stale target can suppress the first `dragenter` of the next drag.

## M20. Image upload progress machine (2 copies)

**Purpose**: upload one image with a progress bar.

**Files/state**: `components/ImageUploader.vue:26-27` (`uploading`, `uploadProgress`), `components/MultiImageUploader.vue:32-33` (`uploadingIndex`, `uploadProgress`).

**State set**: `uploading ∈ {true,false}`, `uploadProgress ∈ 0..100` (incremented by a 100 ms fake timer to 90, then jumped to 100), `uploadingIndex ∈ {-1, 0..max}`.

**Transitions**

| From | Trigger (`file:line`) | To | Guard |
|---|---|---|---|
| idle | `handleFileSelect`/`handleDrop`/`handleFileChange` (`ImageUploader:31-51, 89-106`; `Multi:40-91`) | `uploading=true`, progress 0 → +10/100 ms capped 90 | type/size checks |
| uploading | `uploadImage` resolves (`ImageUploader:65-68`; `Multi:64-67`) | progress 100 → `finally` after 500 ms → idle | `res.code === 200` |
| uploading | `uploadImage` throws (`ImageUploader:78-80`; `Multi:81-82`) | **progress interval is never cleared** | — |

**Enforcement**: `uploading` also disables the file input (`views/product-publish/index.vue:167`).

**Defects**

1. **Interval leak on the error path (both copies)**: `clearInterval(progressInterval)` is only reached on the success path (`ImageUploader:67`, `Multi:66`); the `catch` (`:78`, `:81`) skips it. Because the tick is guarded by `if (progress < 90)`, the leaked timer keeps firing every 100 ms forever, touching a reactive ref on an unmounted component. Every failed upload adds one permanent timer.
2. **`uploading` reset is deferred by `setTimeout(…, 500)`** (`ImageUploader:82-85`, `Multi:84-87`): a second upload started within 500 ms has its `uploading=true`/`uploadingIndex` cleared by the *first* upload's timeout.
3. `uploadProgress` is a **fabricated** signal (10 %/100 ms regardless of bytes), identical for a 1 KB file and a 10 MB file; it is also never reset on the error path until the deferred `finally` runs.
4. `MultiImageUploader.handleFileChange(index)` keys the target slot by `index` and writes `list[index] = url` / `list.push(url)` (`:69-76`) — a concurrent add/remove during upload shifts indices, so the URL lands in the wrong slot (no id-based correlation).

## M21. Operations action lock + status text maps

**Purpose**: one-at-a-time mutation across a resource/task/distribution console.

**Files/state**: `views/operations/index.vue`

| State | Line |
|---|---|
| `activeView: 'overview'|'resources'|'tasks'|'distributions'` | `:26` (writer `switchView`, `:135`) |
| `activeType: ResourceType` (10 literals, `:13-24`) | `:27` (writer `:143`) |
| `pendingAction: string` = `'' | 'retry:<id>' | 'settle:<id>' | …` | `:42`; lock `runWithAction` `:87-95`; probe `isActioning` `:64` |
| `selectedIds`, `showEditor`, `saving`, `loading` | `:35, :37, :41, :28` |
| `statusText` / `taskStatusText` maps | `:59-60`; `taskResultText` `:65` |

**Transitions**

| From | Trigger (`file:line`) | To | Guard |
|---|---|---|---|
| idle | any action (`:274-275` et al.) | `pendingAction = key` | `!pendingAction` (`:88`) |
| busy | action throws/resolves (`:93`) | `pendingAction = ''` | — |
| view A | `switchView(B)` (`:135`) | B, reload | `loading` disabled in template (`:314-316`) |

**Enforcement**: `runWithAction` (`:87-95`) is a genuine single-flight lock; `isActioning(key)` (`:64`) gives per-row spinner identity.

**Defects**

1. **Global lock, per-row UI**: `pendingAction` is a single string, so one in-flight settle disables **every** action button in **all three** tables (`:310, 379, 387`) while the spinner text shows only on the matching key.
2. **Status sets have no closed form**: `statusText` falls back to `处理中` (`:59`), `taskStatusText` to `-` (`:60`), `taskResultText` to `-` (`:65`) — three different fallbacks for three enums none of which is declared anywhere. `settlementStatus` is handled inline as `=== 1 ? 已结算 : 待结算` (`:387`), a fourth ad-hoc mapping.
3. `switchView` does not reset `selectedIds` (`:35`); `loadResources` does (`:120`) but `switchView`+`loadOverview` do not — selection from a previous resource type can survive into a batch publish (`:266-270`).
4. The editor is a single `showEditor` bool with `form` mutated in place (`:46-54`); `openCreate`/`openEdit` (`:149-166`) differ only by preloaded fields, so a failed preload (e.g. `loadKamiConfigs`, `:166`) leaves the previous form contents.

## M22. Buyer detail/rating machine

**Purpose**: one drawer with tabs (orders/messages/ratings) and lazily-synced per-order ratings.

**Files/state**: `views/buyers/index.vue`

| State | Line |
|---|---|
| `detailProfile` (`undefined` = closed) | `:32` |
| `detail` (payload), `detailLoading` | `:31, :30` |
| `detailTab` + `selectedOrderId` | `:33-34` |
| `detailRequestId` (generation) | `:37`; bump `:127, :149`; check `:140, :171, :177, :181` |
| `ratingMap: Record<orderId, OrderRateDetail>` (each with `synced`) | `:36` |
| `ratingLoading` (shared for all orders) | `:35` |
| `editing` + `form` | `:29, :38-44` |

**Transitions**

| From | Trigger (`file:line`) | To | Guard |
|---|---|---|---|
| closed | `openDetail(profile)` (`:126-146`) | open + `orders` tab, `detailLoading=true` | bumps generation |
| open | `save()` success (`:119-123`) | reload list, re-`openDetail` | `editing` set, `tagsError` empty |
| orders | `showOrderConversation` (`:154-157`) | `messages` tab | — |
| orders | `showOrderRating` (`:159-183`) | `ratings` tab + fetch if not synced | `orderId && !ratingMap[orderId]?.synced` (`:162`) |
| any | `closeDetail` (`:148-152`) | closed | bumps generation |

**Enforcement**: `detailRequestId` correctly discards stale detail responses (`:140`).

**Defects**

1. **`detailLoading` is not generation-guarded**: `finally { detailLoading.value = false }` (`:143-145`) runs unconditionally, so opening buyer B while buyer A's request is pending lets A's `finally` clear B's loading state → B renders with `detail === undefined` (empty drawer, no spinner).
2. **`showOrderRating` reuses the generation without bumping it** (`:163` captures `detailRequestId.value`). Two rapid rating requests share the id, and `ratingLoading` is a single boolean for N orders — opening ratings for order X then Y clears the spinner on X.
3. **`ratingMap` has no invalidation** except `openDetail` (`:132`): ratings are cached per order for the drawer's lifetime, so `synced` can be stale after a rating is submitted elsewhere.
4. `orderStatus` (`:193-198`) is a **third** mapping for the delivery status set (after M14's two), with different vocabulary (`已交付/交付失败/待人工复核/处理中`) and it drops `SKIPPED`/`CONFIRMING`/`RETRY_WAIT` into `处理中`.

## M23. Price-comparison seller-profile per-key machine

**State**: `views/price-comparison/index.vue:24-26` `sellerProfiles: Record<key, profile>`, `sellerProfileLoading: Set<key>`, `sellerProfileErrors: Set<key>`; key = `sellerId || itemId` (`:57`).

**Transitions**

| From | Trigger (`:72-92`) | To | Guard |
|---|---|---|---|
| absent | `showSellerProfile` (`:94-97`) | loading | `!profiles[key] && !loading.has(key)` (`:75`) |
| loading | success (`:79-84`) | loaded (written under **both** `key` and `sellerId`) | — |
| loading | failure (`:85-86`) | error | — |
| any | new search (`:121-124`) | all cleared | — |

**Enforcement**: the loading/error sets act as a memo table; `search()` resets all three (`:121-123`).

**Defects**: error keys are only retried by re-invoking `showSellerProfile` (no retry affordance in the error branch); profiles are stored under two keys (`:82-84`) so a `sellerId`-keyed hit and an `itemId`-keyed hit can hold two different snapshots of the same seller; no `searched`-scoped generation, so a response arriving after a new `search()` repopulates `sellerProfiles` that `search` just cleared (`:121`) — a ghost profile for an item no longer in `results`.

## M24. Admin-user editor tri-state

**State**: `views/admin-users/index.vue:19` `editing = ref<PlatformUser | null>()` — **three** meanings: `undefined` = closed (`:81`), `null` = create mode (`:59`), object = edit mode (`:69`); `isCreating = editing === null` (`:32`); `passwordTarget` (`:20`); `saving` (`:15`).

**Transitions**: `openCreate` (`:58-67`) → `null`; `openEdit` (`:69-78`) → user; `closeEditor` (`:80-82`) → `undefined`; `save` (`:96-113`) → `closeEditor()`.

**Defects**: `null` vs `undefined` is the only discriminator between "create" and "closed", and it is invisible at every call site; `editing.value?.id` (`:100`) is `undefined` for both create and closed, so a `save()` reachable while closed would create a user. `save()` performs no validation of `username`/`password` (contrast `views/login/index.vue:19-22`). `passwordTarget` and `newPassword` are never reset on failure (`:120-128`), so a rejected reset leaves the typed password in memory with no error state.

## (unnumbered) Workflow node-editor connect/drag machine

**State**: `views/workflows/index.vue:27` `selectedNodeId`, `:33` `connectingFrom` (`''` = idle, node id = "awaiting target"), `:34` `dragState: DragState{id, offsetX, offsetY, moved} | undefined`.

**Transitions**: `beginConnect` (`:167-171`) → `connectingFrom = selectedNode.id`; `selectOrConnect` (`:173-183`) → edge created if target valid, `connectingFrom` cleared (`:177`); `beginDrag` (`:189-200`) → `dragState`; `drag` (`:202-210`) sets `moved`; `endDrag` (`:212-216`) clears `dragState` in a `setTimeout(0)`.

**Defects**: no way to cancel connect mode (Escape/second click on the same node only, `:178`); `dragState.moved` is used to suppress the click (`:174`) but `endDrag` defers clearing by a macrotask, so the ordering of `pointerup`→`click` vs the timer is browser-dependent; `connectingFrom` is not reset when a node is deleted by another path (only `removeSelectedNode` clears it, `:164`).

---

# Appendix A — Complete inventory of status literal sets found

| Set | Literals | Declared at | Rendered by |
|---|---|---|---|
| QR session | `pending, scanned, confirmed, expired` | `types/index.ts:76` | 3 pollers (M05) |
| Cookie status | `1 有效, 2 过期, 3 失效` | implicit (`WebSocketController.java:443-451`) | 7 mappers (M03) |
| Token validity | `未设置 / 有效 / 已过期` (derived from timestamp) | `useConnectionManager.ts:257-265` | 6 copies |
| Risk guard | `NORMAL, RATE_WAIT, CIRCUIT_OPEN, RECOVERING` | `api/websocket.ts:5` | `ConnectionDetail.vue:255-276` (full coverage incl. default) |
| Captcha task | `PENDING, RUNNING, SUCCEEDED, FAILED, TIMEOUT, UNSUPPORTED, CANCELLED` | `api/websocket.ts:92-99` | `CaptchaGuideDialog.vue:29-37` (complete) |
| Captcha phase | 21 literals | `CaptchaGuideDialog.vue:38-60` | complete vs backend |
| System update | `IDLE…FAILED` (10) | `api/system.ts:20-21` | `UpdateDialog.vue:49-60` (no runtime fallback) |
| Delivery record | 9 literals | `views/orders/order-status.ts:12-22` | 3 conflicting mappers (M14, M22, orders/useOrderManager) |
| Delivery `state` | `0, 1, -1` | implicit | 4 mappers (`order-status.ts:34-36`, `useOrderManager.ts:157-167`, `OrderTable.vue:115-125`, `buyers/index.vue:194-197`) |
| Goods status | `0 在售, 1 已下架, 2 已售出, -1 已删除, -9 平台失效` | `utils/index.ts:42-51` | `GoodsTable.vue:53-70`; filter offers only 0/1/2 (`goods/index.vue:330-332`) → **-1 and -9 are renderable but unfilterable** |
| Account status | `1 正常, -1 需要验证` | `utils/index.ts:53-59` | `AccountTable.vue:40-52` |
| Merchant task | `0 待执行, 1 执行中, 2 成功, -1 失败` | implicit | `operations/index.vue:60,65`; `workflows/index.vue:224`; `automation/index.vue:47` (workflows reports `1` as 等待) |
| Merchant resource | `0 停用, 1 启用, 2 已完成, -1 失败` | implicit | `operations/index.vue:59` |
| Settlement | `1 已结算` else 待结算 | implicit | `operations/index.vue:387` |
| Kami item | `0 未使用` else 已使用 | implicit | `kami-config/index.vue:462` |
| Kami source | `'LOCAL' | 'API'` | `kami-config/index.vue:42, :149` | `:543-544, :653-654` |
| Auto-delivery mode | `1 固定内容, 2 卡密` | `useAutoDelivery.ts:147-148, :152-160` | `auto-delivery/index.vue:359` |
| Automation switches | `0/1` (+ `undefined` for context) | `useAutoReply.ts:809, 817-819` | `GoodsTable.vue:120-124, 239-255` |
| AI rate mode | `0 关闭, 1 始终评价, 2 买家评价后` | `goods/index.vue:181-185, 141-143` | `GoodsTable.vue:252-255` |
| Message content type | `1 user, 2/887/997 image, 888/887 auto, 999 manual` | `useMessageManager.ts:49-73`; `workspace.vue:58-59` | 2 divergent copies (`isImageMessage` includes 2, `getContentTypeText` does not) |
| Conversation tab | `'orders' | 'messages' | 'ratings'` | `buyers/index.vue:33` | `:350-383` |
| Settings tab | `'account'|'menu'|'ai'|'prompt'|'email'|'backup'|'about'` | `settings/index.vue:34, 241-249` | 7 `v-if`s, no fallback |
| Operations view | `'overview'|'resources'|'tasks'|'distributions'` | `operations/index.vue:26` | `:314-316` |

# Appendix B — Dead / duplicate state machines (no live consumer)

| Artifact | File:line | Why it matters |
|---|---|---|
| `useCounterStore` | `stores/counter.ts:4-11` | the only "store"; unused |
| Messages legacy page + 3 s poller | `views/messages/index.vue:134-160` | not routed (`router/index.ts:105` uses `workspace.vue`); duplicates the live 10 s poller |
| `ConnectionDetailDialog.vue` (5 s poller, own dialog cluster) | `:268-283` | zero importers |
| `RefreshCookieDialog.vue` (unguarded `confirmed` poll) | `:53-82` | zero importers (superseded by `QRUpdateDialog`) |
| `MobileCredentialModal.vue` | whole file | zero importers; 6th copy of the cookieStatus mapper |
| `ManualUpdateTokenDialog.vue` | whole file | only referenced by the dead `ConnectionDetailDialog` |
| `GoodsDetailDialog.vue` commented toggle machine | `:84-125` | the auto-delivery/auto-reply toggle state machine remains as comments; live copy is M12/M13 |
| `views/qrlogin/index.vue` | whole file (16 lines) | routed at `router/index.ts:169-173` with permission `menu:accounts`, but renders "扫码登录功能开发中..." — a reachable dead route |
| `checkSyncing` API | `useGoodsManager.ts:14` (import), `api/goods.ts:296-301` | imported, never called → no sync rehydration (M08 defect 2) |
| `views/connection/ConnectionDetail.vue` mobile template + dialogs | `:511-520` | dialogs mounted in addition to the desktop component's own copies |

# Appendix C — Polling/leak matrix

| Loop | Period | Terminal condition | Leak / hang risk |
|---|---|---|---|
| `useConnectionManager.ts:126` | 10 s | none (unmount only) | overlapping ticks for many accounts; stale selected-account write |
| `components/ConnectionDetail.vue:266` | 10 s | none | stale write after account switch; silent tick clears `statusLoading` |
| `ConnectionDetail.vue:298` | 10 s | none | same + duplicate operation-log query |
| `ConnectionDetailDialog.vue:273` (dead) | 5 s | none | orphaned component |
| `ConnectionDetail.vue:70` / `components/ConnectionDetail.vue:256` | 1 s | never | permanent re-render for a countdown that may not exist |
| `QRLoginDialog.vue:59` | 2 s | `expired` / close | re-entrant `confirmed` → duplicate account creation |
| `RefreshCookieDialog.vue:54` (dead) | 2 s | `expired` / close | re-entrant `confirmed` |
| `QRUpdateDialog.vue:57` | 2 s | `expired` / close | guarded (`pollRequestPending`, stop-before-handle) |
| `CaptchaGuideDialog.vue:400` (timeout chain) | 1 s | 5 terminal statuses (`:218-236`) | no client deadline; duplicate retry chain across reopens |
| `CaptchaGuideDialog.vue:414` (timeout chain) | 0.7 s | not `manualTaskRunning` | only while `MANUAL_BROWSER` |
| `CaptchaGuideDialog.vue:428` (clock) | 1 s | close/unmount | fine |
| `UpdateDialog.vue:128` | 2 s | `active === false` (`:117`) | polls forever if backend leaves `active` true on `FAILED` |
| `useGoodsManager.ts:84` | 1 s | `isCompleted || !isRunning` (`:65`) | **never terminates on error**; flags stuck true |
| `goods/index.vue` pull gesture | n/a | `handleRefresh` resolve | `refreshing` collapses before work ends |
| `pending-orders/index.vue:58` | 5–60 s | none | concurrent `loadOrders` races |
| `pending-orders/index.vue:82` | 5–60 s | none | **no re-entrancy guard on bulk delivery** |
| `messages/index.vue:147` (dead page) | 3 s | unmount | duplicated data source |
| `messages/workspace.vue:248` | 10 s | unmount | skipped ticks during platform sync |
| `ContextDialog.vue:144` | 1 s | `visible === false` | **replaces paginated list**; no in-flight guard |
| `ImageUploader.vue:59` / `MultiImageUploader.vue:60` | 0.1 s | never on error | **interval leak per failed upload** |

# Appendix D — Top remediation priorities

1. **P0** — `pending-orders` auto-deliver re-entrancy (M15) and QR `confirmed` re-entrancy (M05): both cause duplicated real-world side effects.
2. **P0** — `useGoodsManager` stuck `syncing` + missing `checkSyncing` rehydration (M08): silently disables the refresh path.
3. **P1** — auto-reply partial-aggregate PUTs and absolute-value rollbacks (M13): server state can diverge from UI without any failing request.
4. **P1** — wizard guard bypass (M10/M11): validation lives only in `next()`.
5. **P1** — `ContextDialog` poll vs. pagination (M17) and `detailLoading` race (M22).
6. **P2** — consolidate the 7 cookieStatus mappers, the 3+3 delivery-status mappers and the 3 merchant-task mappers into one module each; add runtime fallbacks for every enum rendered from a server field.
7. **P2** — replace boolean clusters (`refreshing`+`syncing`, `loading`+`silentLoading`, 5 kami dialog booleans, 4 orders row flags) with single enums; delete the four orphaned components and the unrouted `views/messages/index.vue` / `views/qrlogin/index.vue`.
