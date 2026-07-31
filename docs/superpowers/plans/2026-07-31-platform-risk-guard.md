# Platform Risk Guard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为非时效平台写操作增加账号级逻辑限流，为所有受控 MTOP 写操作增加持久化熔断，并让订单履约在恢复后自动继续。

**Architecture:** `RiskControlService` 保存每账号逻辑桶和熔断状态，`XianyuApiCallUtils` 统一阻止熔断期写请求并识别平台风控响应。商品与评价在业务入口获取一次额度；普通订单复用 `xianyu_goods_order`，小刀与人工确认发货复用 `merchant_task`，连接状态接口负责实时展示。

**Tech Stack:** Java 21、Spring Boot 3.5、Jackson、MyBatis、Vue 3、TypeScript、Vite。

---

### Task 1: 持久化账号风险护栏

**Files:**
- Modify: `src/main/java/com/xianyusmart/service/RiskControlService.java`
- Modify: `src/main/java/com/xianyusmart/service/impl/RiskControlServiceImpl.java`
- Modify: `src/main/java/com/xianyusmart/constants/OperationConstants.java`
- Temporary test: `src/test/java/com/xianyusmart/service/impl/RiskControlServiceImplTest.java`

- [ ] **Step 1: 写失败测试**

```java
assertTrue(service.tryAcquire(1L, WriteOperation.ITEM_PUBLISH).allowed());
assertEquals(GuardState.RATE_WAIT,
        service.tryAcquire(1L, WriteOperation.ITEM_PUBLISH).state());
service.recordResponse(1L, Map.of("ret", List.of("FAIL_SYS_RGV587_ERROR::异常流量")));
assertEquals(GuardState.CIRCUIT_OPEN, service.getStatus(1L).state());
```

- [ ] **Step 2: 执行定向测试并确认因缺少新接口失败**

```powershell
$env:JAVA_HOME='E:\java\jdk21'
.\mvnw.cmd -Dtest=RiskControlServiceImplTest test
```

- [ ] **Step 3: 实现最小接口和标准库文件持久化**

```java
enum WriteOperation { ITEM_PUBLISH, ITEM_DELETE, ITEM_STATUS, ITEM_POLISH, ORDER_RATE }
enum GuardState { NORMAL, RATE_WAIT, CIRCUIT_OPEN, RECOVERING }
record GuardDecision(boolean allowed, GuardState state, long remainingSeconds,
                     long retryAt, String reason, WriteOperation operation) {}
record GuardStatus(GuardState state, long remainingSeconds, long retryAt,
                   String reason, WriteOperation operation) {}

GuardDecision tryAcquire(Long accountId, WriteOperation operation);
GuardDecision checkApiWrite(Long accountId, String apiName);
void recordResponse(Long accountId, Map<String, Object> response);
void clearCircuit(Long accountId);
GuardStatus getStatus(Long accountId);
```

状态文件使用 `${app.risk-guard.state-file:${user.dir}/data/platform-risk-guard.json}`，写入临时文件后使用 `ATOMIC_MOVE` 替换；文件只保存账号、逻辑桶和时间戳。

- [ ] **Step 4: 执行定向测试确认限流、熔断、恢复、损坏文件和重载均通过**

```powershell
$env:JAVA_HOME='E:\java\jdk21'
.\mvnw.cmd -Dtest=RiskControlServiceImplTest test
```

### Task 2: MTOP 公共入口接入熔断

**Files:**
- Modify: `src/main/java/com/xianyusmart/utils/XianyuApiCallUtils.java`
- Extend temporary test: `src/test/java/com/xianyusmart/service/impl/RiskControlServiceImplTest.java`

- [ ] **Step 1: 增加 API 分类失败测试**

```java
assertFalse(service.checkApiWrite(1L, "mtop.taobao.idle.trade.sold.get").state()
        == GuardState.CIRCUIT_OPEN);
assertEquals(GuardState.CIRCUIT_OPEN,
        service.checkApiWrite(1L, "mtop.taobao.idle.logistics.merchant.consign.dummy").state());
```

- [ ] **Step 2: 执行测试并确认写 API 分类尚未生效**

```powershell
.\mvnw.cmd -Dtest=RiskControlServiceImplTest test
```

- [ ] **Step 3: 在真实网络调用前检查熔断，并在解析响应后记录风险**

```java
RiskControlService.GuardDecision guard = riskControlService.checkApiWrite(accountId, apiName);
if (!guard.allowed()) {
    return ApiCallResult.guardBlocked(guard);
}
riskControlService.recordResponse(accountId, responseMap);
```

`ApiCallResult` 增加 `guardState`、`remainingSeconds`、`retryAt` 和 `isGuardBlocked()`；Token 递归重试只重复熔断检查，不重复获取逻辑业务额度。

- [ ] **Step 4: 执行定向测试和 Java 编译**

```powershell
.\mvnw.cmd -Dtest=RiskControlServiceImplTest test
.\mvnw.cmd -DskipTests compile
```

### Task 3: 商品、评价和擦亮接入逻辑桶

**Files:**
- Modify: `src/main/java/com/xianyusmart/service/PlatformPublishService.java`
- Modify: `src/main/java/com/xianyusmart/service/GoodsAutomationService.java`
- Modify: `src/main/java/com/xianyusmart/service/GoodsAutomationScheduler.java`
- Modify: `src/main/java/com/xianyusmart/mapper/MerchantTaskMapper.java`
- Modify: `src/main/java/com/xianyusmart/service/MerchantOperationsService.java`
- Temporary test: `src/test/java/com/xianyusmart/service/GoodsAutomationRiskGuardTest.java`

- [ ] **Step 1: 写自动评价被护栏阻止时不更新失败状态的测试**

```java
when(riskControlService.tryAcquire(accountId, WriteOperation.ORDER_RATE))
        .thenReturn(new GuardDecision(false, RATE_WAIT, 42, retryAt, "写操作等待", ORDER_RATE));
service.manualRate(accountId, orderId, "感谢支持");
verify(goodsOrderMapper, never()).updateRateResult(anyLong(), anyString(), eq(-1), any(), any());
```

- [ ] **Step 2: 执行测试并确认当前代码仍会调用平台或写失败状态**

```powershell
.\mvnw.cmd -Dtest=GoodsAutomationRiskGuardTest test
```

- [ ] **Step 3: 在完整逻辑操作前只获取一次额度**

```java
requirePermit(accountId, WriteOperation.ITEM_DELETE); // 下架和删除共享本次许可
RiskControlService.GuardDecision permit =
        riskControlService.tryAcquire(accountId, WriteOperation.ORDER_RATE);
if (!permit.allowed()) {
    return XianyuApiCallUtils.ApiCallResult.guardBlocked(permit);
}
```

发布额度在图片上传完成、最终发布前获取；上架 Playwright 操作也使用 `ITEM_STATUS`。自动评价和擦亮遇到等待时停止账号本轮，不更新失败状态。评价和擦亮调度默认周期改为 60 秒。

- [ ] **Step 4: 运营任务遇到本地等待时改为延后，不消耗失败次数**

```java
@Update("UPDATE merchant_task SET status = 0, attempt_count = GREATEST(attempt_count - 1, 0), " +
        "scheduled_time = #{retryAt}, next_retry_time = NULL, error_message = #{message} WHERE id = #{id}")
int defer(@Param("id") Long id, @Param("retryAt") LocalDateTime retryAt,
          @Param("message") String message);
```

- [ ] **Step 5: 执行定向测试和 Java 编译**

```powershell
.\mvnw.cmd -Dtest=GoodsAutomationRiskGuardTest test
.\mvnw.cmd -DskipTests compile
```

### Task 4: 完整凭证更新解除熔断

**Files:**
- Modify: `src/main/java/com/xianyusmart/service/impl/AccountServiceImpl.java`
- Extend temporary test: `src/test/java/com/xianyusmart/service/impl/RiskControlServiceImplTest.java`

- [ ] **Step 1: 写完整更新解除、普通合并不解除的测试**

```java
service.recordResponse(1L, riskResponse);
service.clearCircuit(1L);
assertEquals(GuardState.NORMAL, service.getStatus(1L).state());
```

- [ ] **Step 2: 在 `updateAccountCookie` 成功保存后解除，保持 `updateCookie` 不变**

```java
if (updated) {
    // 完整凭证已生效后解除熔断，避免局部 Set-Cookie 误恢复写操作。
    riskControlService.clearCircuit(accountId);
}
```

- [ ] **Step 3: 执行定向测试和 Java 编译**

```powershell
.\mvnw.cmd -Dtest=RiskControlServiceImplTest test
.\mvnw.cmd -DskipTests compile
```

### Task 5: 普通发货、小刀和人工确认发货持久化恢复

**Files:**
- Modify: `src/main/java/com/xianyusmart/service/OrderService.java`
- Modify: `src/main/java/com/xianyusmart/service/impl/OrderServiceImpl.java`
- Modify: `src/main/java/com/xianyusmart/service/DeliveryTaskService.java`
- Modify: `src/main/java/com/xianyusmart/service/impl/DeliveryTaskServiceImpl.java`
- Modify: `src/main/java/com/xianyusmart/service/DeliveryTaskScheduler.java`
- Modify: `src/main/java/com/xianyusmart/service/impl/AutoDeliveryServiceImpl.java`
- Modify: `src/main/java/com/xianyusmart/mapper/XianyuGoodsOrderMapper.java`
- Modify: `src/main/java/com/xianyusmart/service/MerchantOperationsService.java`
- Modify: `src/main/java/com/xianyusmart/mapper/MerchantTaskMapper.java`
- Modify: `src/main/java/com/xianyusmart/event/chatMessageEvent/lister/ChatMessageEventAutoDeliveryListener.java`
- Modify: `src/main/java/com/xianyusmart/controller/OrderController.java`
- Temporary test: `src/test/java/com/xianyusmart/service/DeliveryRiskGuardTest.java`

- [ ] **Step 1: 写发货熔断时延后且不消耗失败次数的测试**

```java
service.deferForRisk(taskId, retryAt, "账号风控冷却中");
verify(orderMapper).deferForRisk(taskId, retryAt, "账号风控冷却中");
verify(orderMapper, never()).retryOrFailTask(eq(taskId), eq("FAILED"), any(), any());
```

- [ ] **Step 2: 执行测试确认缺少延后接口**

```powershell
.\mvnw.cmd -Dtest=DeliveryRiskGuardTest test
```

- [ ] **Step 3: 普通发货返回专用等待结果并复用订单队列**

```java
String CONSIGN_DEFERRED = "平台风控冷却中，发货任务已等待恢复";

@Update("UPDATE xianyu_goods_order SET delivery_status = 'RETRY_WAIT', " +
        "attempt_count = GREATEST(attempt_count - 1, 0), next_retry_time = #{retryAt}, " +
        "lease_owner = NULL, lease_expire_time = NULL, last_error_code = 'RISK_GUARD_WAIT', " +
        "last_error_message = #{message} WHERE id = #{id}")
int deferForRisk(...);
```

卡密在平台请求前被熔断时释放本次预占并取消暂存私聊，订单恢复后重新解析；已向平台发出但结果不确定时继续进入人工复核。

- [ ] **Step 4: 小刀和人工确认发货复用 `merchant_task`**

```java
TASK_TYPES = Set.of(..., "BARGAIN_FREE_SHIPPING", "CONFIRM_SHIPMENT");
requestKey = accountId + ":" + orderId;
request = Map.of("orderId", orderId, "itemId", itemId, "buyerId", buyerId);
```

任务参数只保存业务 ID。小刀直接成功时不创建任务，失败或熔断时按账号与订单去重入队；人工确认发货熔断时返回“已进入恢复队列”，不能更新 `confirm_state`。

- [ ] **Step 5: 执行定向测试和 Java 编译**

```powershell
.\mvnw.cmd -Dtest=DeliveryRiskGuardTest test
.\mvnw.cmd -DskipTests compile
```

### Task 6: 连接管理实时展示

**Files:**
- Modify: `src/main/java/com/xianyusmart/controller/WebSocketController.java`
- Modify: `src/main/java/com/xianyusmart/mapper/MerchantTaskMapper.java`
- Modify: `src/main/java/com/xianyusmart/mapper/XianyuGoodsOrderMapper.java`
- Modify: `vue-code/src/api/websocket.ts`
- Modify: `vue-code/src/views/connection/useConnectionManager.ts`
- Modify: `vue-code/src/views/connection/ConnectionDetail.vue`
- Modify: `vue-code/src/views/connection/components/ConnectionDetail.vue`
- Modify: `vue-code/src/views/connection/components/ConnectionCard.vue`

- [ ] **Step 1: 后端状态增加风险和等待数量**

```java
private RiskControlService.GuardStatus riskGuard;
private Long deferredPlatformActions;

respDTO.setRiskGuard(riskControlService.getStatus(accountId));
respDTO.setDeferredRiskActions(
        merchantTaskMapper.countPendingPlatformActions(accountId)
        + orderMapper.countDeferredActions(accountId));
```

- [ ] **Step 2: 前端类型和本地每秒倒计时**

```ts
export interface RiskGuardStatus {
  state: 'NORMAL' | 'RATE_WAIT' | 'CIRCUIT_OPEN' | 'RECOVERING'
  retryAt: number
  reason?: string
  operation?: string
}

const riskRemainingSeconds = computed(() =>
  Math.max(0, Math.ceil(((connectionStatus.value?.riskGuard?.retryAt || 0) - now.value) / 1000))
)
```

- [ ] **Step 3: 桌面、移动详情和账号卡片显示状态、原因、倒计时与等待任务数**

```vue
<span>{{ riskGuardText }}</span>
<span v-if="riskRemainingSeconds > 0">剩余 {{ riskRemainingSeconds }} 秒</span>
<span v-if="connectionStatus.deferredPlatformActions">等待恢复 {{ connectionStatus.deferredPlatformActions }} 项</span>
```

- [ ] **Step 4: 执行前端类型检查和正式构建**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task frontend
```

### Task 7: 清理临时测试并完成全量验证

**Files:**
- Delete temporary tests created by Tasks 1, 3 and 5.

- [ ] **Step 1: 删除本次临时测试文件**

```text
src/test/java/com/xianyusmart/service/impl/RiskControlServiceImplTest.java
src/test/java/com/xianyusmart/service/GoodsAutomationRiskGuardTest.java
src/test/java/com/xianyusmart/service/DeliveryRiskGuardTest.java
```

- [ ] **Step 2: 确认正式代码不依赖临时测试后运行 Java 21 全量测试**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task test
```

- [ ] **Step 3: 构建一次部署产物**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task package
```

- [ ] **Step 4: 审查只包含本次范围的差异**

```powershell
git status --short
git diff --check
git diff --stat
git diff -- src/main/java vue-code/src docs/superpowers
```

### Task 8: 提交、推送与生产闭环

**Files:**
- Use verified JAR from Task 7.

- [ ] **Step 1: 只暂存本次文件，排除 `README.md` 和 `DISCLAIMER.md`**

```powershell
git add docs/superpowers src/main/java vue-code/src
```

- [ ] **Step 2: 提交并推送 `origin/main`**

```powershell
git commit -m "feat: add platform risk guard"
git push origin main
```

- [ ] **Step 3: 使用 `updating-xianyusmart` 的 `-SkipBuild` 路径部署已验证产物**

- [ ] **Step 4: 生产验收连接状态、读取、非时效限流、订单履约等待恢复和 Cookie 解除熔断；任何一项失败立即按更新技能回滚。**
