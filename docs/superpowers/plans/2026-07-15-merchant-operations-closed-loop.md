# XianYuSmart Merchant Operations Closed Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让商家从经营首页一步定位履约异常和低库存仓库，安全恢复明确失败的订单，并在商品页完成本地资料维护与平台修改回读。

**Architecture:** 保持 Spring Boot + MyBatis + Vue 3 单体结构，在现有发货记录查询链路中补齐履约状态，在现有任务服务中增加带账号与状态条件的原子重排队操作。前端提取无依赖纯函数统一订单状态、库存预警和商品编辑校验，经营首页通过路由参数进入精准筛选页面。商品资料更新只写本地安全字段，平台修改通过商品页入口和现有同步能力闭环。

**Tech Stack:** Java 21、Spring Boot 3.5、MyBatis、MySQL 8、Flyway、Vue 3、TypeScript、Vue Router、Node.js 24

---

## 文件结构

- 修改 `src/main/java/com/xianyusmart/controller/dto/AutoDeliveryRecordReqDTO.java`：接收履约状态列表。
- 修改 `src/main/java/com/xianyusmart/controller/dto/AutoDeliveryRecordDTO.java`：返回履约状态和失败原因。
- 修改 `src/main/java/com/xianyusmart/mapper/XianyuGoodsOrderMapper.java`：状态筛选和失败任务原子重排队。
- 修改 `src/main/java/com/xianyusmart/service/impl/AutoDeliveryServiceImpl.java`：分页边界、状态校验、响应映射。
- 修改 `src/main/java/com/xianyusmart/service/DeliveryTaskService.java`：声明安全重排队方法。
- 修改 `src/main/java/com/xianyusmart/service/impl/DeliveryTaskServiceImpl.java`：实现安全重排队方法。
- 修改 `src/main/java/com/xianyusmart/controller/OrderController.java`：提供失败任务重排队接口。
- 新建 `src/main/resources/db/migration/V2__add_order_delivery_filter_index.sql`：增加后台查询组合索引。
- 修改 `vue-code/src/api/order.ts`：同步状态字段、筛选参数和重排队接口。
- 新建 `vue-code/src/views/orders/order-status.ts`：集中维护状态解析、筛选项和视觉文案。
- 修改 `vue-code/src/views/orders/useOrderManager.ts`：路由筛选和重排队交互。
- 修改 `vue-code/src/views/orders/index.vue`：桌面端与移动端状态筛选。
- 修改 `vue-code/src/views/orders/components/OrderTable.vue`：准确状态、失败原因和重排队按钮。
- 修改 `vue-code/src/views/orders/orders.css`：补充必要的筛选与重排队样式。
- 修改 `vue-code/src/views/dashboard/index.vue`：精准待办跳转和可见页面低频刷新。
- 修改 `vue-code/src/views/dashboard/useDashboard.ts`：阻止并发重复刷新。
- 新建 `vue-code/src/views/kami-config/kami-stock.ts`：统一低库存阈值判断。
- 修改 `vue-code/src/views/kami-config/index.vue`：支持低库存仓库视图和恢复全部。
- 新建 `src/main/java/com/xianyusmart/controller/dto/UpdateItemInfoReqDTO.java`：接收本地商品资料。
- 修改 `src/main/java/com/xianyusmart/service/ItemService.java` 与 `src/main/java/com/xianyusmart/service/impl/ItemServiceImpl.java`：校验并更新本地商品资料。
- 修改 `src/main/java/com/xianyusmart/service/GoodsInfoService.java` 与 `src/main/java/com/xianyusmart/service/impl/GoodsInfoServiceImpl.java`：按账号与商品联合更新安全字段。
- 修改 `src/main/java/com/xianyusmart/controller/ItemController.java`：提供本地商品资料更新接口。
- 修改 `vue-code/src/api/goods.ts`：增加本地商品资料更新请求。
- 新建 `vue-code/src/views/goods/goods-edit.ts` 与 `vue-code/src/views/goods/components/GoodsEditDialog.vue`：商品编辑校验与简洁弹窗。
- 修改 `vue-code/src/views/goods/useGoodsManager.ts`、`vue-code/src/views/goods/index.vue` 与 `vue-code/src/views/goods/components/GoodsTable.vue`：接入编辑、平台跳转和保存刷新。
- 临时创建后删除 `src/test/java/com/xianyusmart/service/impl/AutoDeliveryServiceImplTempTest.java`、`src/test/java/com/xianyusmart/service/impl/DeliveryTaskServiceImplTempTest.java`、`src/test/java/com/xianyusmart/service/impl/ItemServiceImplTempTest.java`、`vue-code/temp-tests/order-status.test.ts`、`vue-code/temp-tests/kami-stock.test.ts`、`vue-code/temp-tests/goods-edit.test.ts`。

### Task 1: 补齐发货记录状态查询与响应

**Files:**
- Modify: `src/main/java/com/xianyusmart/controller/dto/AutoDeliveryRecordReqDTO.java`
- Modify: `src/main/java/com/xianyusmart/controller/dto/AutoDeliveryRecordDTO.java`
- Modify: `src/main/java/com/xianyusmart/mapper/XianyuGoodsOrderMapper.java`
- Modify: `src/main/java/com/xianyusmart/service/impl/AutoDeliveryServiceImpl.java`
- Temporary test: `src/test/java/com/xianyusmart/service/impl/AutoDeliveryServiceImplTempTest.java`

- [ ] **Step 1: 写入临时失败测试**

```java
package com.xianyusmart.service.impl;

import com.xianyusmart.controller.dto.AutoDeliveryRecordReqDTO;
import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoDeliveryServiceImplTempTest {

    @Mock
    private XianyuGoodsOrderMapper orderMapper;

    @InjectMocks
    private AutoDeliveryServiceImpl service;

    @Test
    void shouldClampPaginationAndReturnDeliveryFailureDetails() {
        AutoDeliveryRecordReqDTO request = new AutoDeliveryRecordReqDTO();
        request.setXianyuAccountId(1L);
        request.setPageNum(0);
        request.setPageSize(500);
        request.setDeliveryStatuses(List.of("FAILED"));

        XianyuGoodsOrder order = new XianyuGoodsOrder();
        order.setId(9L);
        order.setDeliveryStatus("FAILED");
        order.setLastErrorMessage("连接不可用");

        when(orderMapper.selectByAccountIdWithPage(1L, null, null, List.of("FAILED"), 100, 0L))
                .thenReturn(List.of(order));
        when(orderMapper.countByAccountId(1L, null, null, List.of("FAILED"))).thenReturn(1L);

        var response = service.getAutoDeliveryRecords(request);

        assertEquals(1, response.getPageNum());
        assertEquals(100, response.getPageSize());
        assertEquals("FAILED", response.getRecords().getFirst().getDeliveryStatus());
        assertEquals("连接不可用", response.getRecords().getFirst().getFailReason());
        verify(orderMapper).selectByAccountIdWithPage(1L, null, null, List.of("FAILED"), 100, 0L);
    }

    @Test
    void shouldRejectUnknownDeliveryStatus() {
        AutoDeliveryRecordReqDTO request = new AutoDeliveryRecordReqDTO();
        request.setXianyuAccountId(1L);
        request.setDeliveryStatuses(List.of("UNKNOWN"));

        assertThrows(IllegalArgumentException.class, () -> service.getAutoDeliveryRecords(request));
    }
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```powershell
$env:JAVA_HOME='E:\java\jdk21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -Dtest=AutoDeliveryServiceImplTempTest test
```

Expected: FAIL，编译提示缺少 `deliveryStatuses`、`deliveryStatus` 或新的 Mapper 参数。

- [ ] **Step 3: 实现最小查询改动**

在请求 DTO 中增加：

```java
private List<String> deliveryStatuses;
```

并增加导入：

```java
import java.util.List;
```

在响应 DTO 中增加：

```java
private String deliveryStatus;

private String failReason;
```

在 `AutoDeliveryServiceImpl` 中校验状态并限制分页：

```java
List<String> deliveryStatuses = reqDTO.getDeliveryStatuses() == null ? List.of() :
        reqDTO.getDeliveryStatuses().stream()
                .map(status -> {
                    try {
                        return com.xianyusmart.enums.DeliveryStatus.valueOf(status).name();
                    } catch (Exception e) {
                        throw new IllegalArgumentException("履约状态无效: " + status);
                    }
                })
                .distinct()
                .toList();
int pageNum = Math.max(1, reqDTO.getPageNum() != null ? reqDTO.getPageNum() : 1);
int pageSize = Math.max(1, Math.min(reqDTO.getPageSize() != null ? reqDTO.getPageSize() : 20, 100));
long offset = (long) (pageNum - 1) * pageSize;
```

调用 Mapper 时传入 `deliveryStatuses`，映射响应时增加：

```java
dto.setDeliveryStatus(record.getDeliveryStatus());
dto.setFailReason(record.getFailReason() != null && !record.getFailReason().isBlank()
        ? record.getFailReason() : record.getLastErrorMessage());
```

在列表和总数 SQL 中加入同一段筛选：

```xml
<if test='deliveryStatuses != null and !deliveryStatuses.isEmpty()'>
AND r.delivery_status IN
<foreach collection='deliveryStatuses' item='deliveryStatus' open='(' separator=',' close=')'>
#{deliveryStatus}
</foreach>
</if>
```

两个 Mapper 方法均增加：

```java
@Param("deliveryStatuses") List<String> deliveryStatuses
```

分页参数中的 `offset` 类型调整为 `long`，避免极大页码发生整数溢出。

结果映射增加：

```java
@Result(property = "deliveryStatus", column = "delivery_status"),
@Result(property = "lastErrorMessage", column = "last_error_message")
```

- [ ] **Step 4: 运行测试并确认通过**

Run:

```powershell
$env:JAVA_HOME='E:\java\jdk21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -Dtest=AutoDeliveryServiceImplTempTest test
```

Expected: PASS，2 tests completed。

### Task 2: 增加失败任务安全重排队

**Files:**
- Modify: `src/main/java/com/xianyusmart/mapper/XianyuGoodsOrderMapper.java`
- Modify: `src/main/java/com/xianyusmart/service/DeliveryTaskService.java`
- Modify: `src/main/java/com/xianyusmart/service/impl/DeliveryTaskServiceImpl.java`
- Modify: `src/main/java/com/xianyusmart/controller/OrderController.java`
- Temporary test: `src/test/java/com/xianyusmart/service/impl/DeliveryTaskServiceImplTempTest.java`

- [ ] **Step 1: 写入临时失败测试**

```java
package com.xianyusmart.service.impl;

import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryTaskServiceImplTempTest {

    @Test
    void shouldOnlyReportSuccessfulAtomicRequeue() {
        XianyuGoodsOrderMapper mapper = mock(XianyuGoodsOrderMapper.class);
        DeliveryTaskServiceImpl service = new DeliveryTaskServiceImpl(mapper);
        when(mapper.requeueFailedTask(9L, 2L)).thenReturn(1);
        when(mapper.requeueFailedTask(10L, 2L)).thenReturn(0);

        assertTrue(service.requeueFailed(9L, 2L));
        assertFalse(service.requeueFailed(10L, 2L));
        verify(mapper).requeueFailedTask(9L, 2L);
        verify(mapper).requeueFailedTask(10L, 2L);
    }
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```powershell
$env:JAVA_HOME='E:\java\jdk21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -Dtest=DeliveryTaskServiceImplTempTest test
```

Expected: FAIL，编译提示 `requeueFailedTask` 或 `requeueFailed` 不存在。

- [ ] **Step 3: 实现原子重排队与接口**

Mapper 增加：

```java
@Update("UPDATE xianyu_goods_order SET delivery_status = 'PENDING', next_retry_time = NOW(3), " +
        "lease_owner = NULL, lease_expire_time = NULL, last_error_code = NULL, last_error_message = NULL " +
        "WHERE id = #{id} AND xianyu_account_id = #{accountId} AND state <> 1 AND delivery_status = 'FAILED'")
int requeueFailedTask(@Param("id") Long id, @Param("accountId") Long accountId);
```

服务接口和实现增加：

```java
boolean requeueFailed(Long taskId, Long accountId);
```

```java
@Override
public boolean requeueFailed(Long taskId, Long accountId) {
    return orderMapper.requeueFailedTask(taskId, accountId) == 1;
}
```

`OrderController` 注入 `DeliveryTaskService` 并增加：

```java
@PostMapping("/requeueDelivery")
public ResultObject<String> requeueDelivery(@RequestBody RequeueDeliveryReqDTO reqDTO) {
    try {
        if (reqDTO.getId() == null || reqDTO.getXianyuAccountId() == null) {
            return ResultObject.failed("订单记录ID和账号ID不能为空");
        }
        if (!deliveryTaskService.requeueFailed(reqDTO.getId(), reqDTO.getXianyuAccountId())) {
            return ResultObject.failed("订单状态已变化，请刷新后重试");
        }
        return ResultObject.success("订单已重新进入发货队列");
    } catch (Exception e) {
        log.error("失败订单重新排队异常: id={}, xianyuAccountId={}",
                reqDTO.getId(), reqDTO.getXianyuAccountId(), e);
        return ResultObject.failed("重新排队失败: " + e.getMessage());
    }
}

@lombok.Data
public static class RequeueDeliveryReqDTO {
    private Long id;
    private Long xianyuAccountId;
}
```

- [ ] **Step 4: 运行测试并确认通过**

Run:

```powershell
$env:JAVA_HOME='E:\java\jdk21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -Dtest=DeliveryTaskServiceImplTempTest test
```

Expected: PASS，1 test completed。

- [ ] **Step 5: 提交后端闭环**

```powershell
git add src/main/java/com/xianyusmart/controller/dto/AutoDeliveryRecordReqDTO.java src/main/java/com/xianyusmart/controller/dto/AutoDeliveryRecordDTO.java src/main/java/com/xianyusmart/mapper/XianyuGoodsOrderMapper.java src/main/java/com/xianyusmart/service/impl/AutoDeliveryServiceImpl.java src/main/java/com/xianyusmart/service/DeliveryTaskService.java src/main/java/com/xianyusmart/service/impl/DeliveryTaskServiceImpl.java src/main/java/com/xianyusmart/controller/OrderController.java
git commit -m "feat: 完善履约状态查询与安全重试"
```

临时测试文件不得加入提交。

### Task 3: 完善发货记录筛选、状态与重试体验

**Files:**
- Modify: `vue-code/src/api/order.ts`
- Create: `vue-code/src/views/orders/order-status.ts`
- Modify: `vue-code/src/views/orders/useOrderManager.ts`
- Modify: `vue-code/src/views/orders/index.vue`
- Modify: `vue-code/src/views/orders/components/OrderTable.vue`
- Modify: `vue-code/src/views/orders/orders.css`
- Temporary test: `vue-code/temp-tests/order-status.test.ts`

- [ ] **Step 1: 写入状态纯函数临时失败测试**

```typescript
import test from 'node:test'
import assert from 'node:assert/strict'
import { getDeliveryStatusMeta, parseDeliveryStatuses } from '../src/views/orders/order-status.ts'

test('parseDeliveryStatuses 过滤非法值并去重', () => {
  assert.deepEqual(
    parseDeliveryStatuses('FAILED,UNKNOWN,FAILED,REVIEW_REQUIRED'),
    ['FAILED', 'REVIEW_REQUIRED']
  )
})

test('getDeliveryStatusMeta 优先使用履约状态并兼容旧状态', () => {
  assert.equal(getDeliveryStatusMeta('RETRY_WAIT', -1).text, '等待重试')
  assert.equal(getDeliveryStatusMeta(undefined, 1).text, '已完成')
})
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `node --test vue-code/temp-tests/order-status.test.ts`

Expected: FAIL，提示无法找到 `order-status.ts`。

- [ ] **Step 3: 创建订单状态纯函数**

```typescript
export const deliveryStatusOptions = [
  { value: '', label: '全部状态' },
  { value: 'PENDING,PROCESSING,RETRY_WAIT', label: '等待履约' },
  { value: 'REVIEW_REQUIRED', label: '需要人工核对' },
  { value: 'FAILED', label: '履约失败' },
  { value: 'DELIVERED', label: '已交付' },
  { value: 'CONFIRMING', label: '确认中' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'SKIPPED', label: '已跳过' }
] as const

const statusMeta: Record<string, { text: string; color: string; background: string }> = {
  PENDING: { text: '等待处理', color: '#0A84FF', background: 'rgba(10,132,255,.14)' },
  PROCESSING: { text: '正在处理', color: '#0A84FF', background: 'rgba(10,132,255,.14)' },
  RETRY_WAIT: { text: '等待重试', color: '#FF9F0A', background: 'rgba(255,159,10,.18)' },
  DELIVERED: { text: '已交付', color: '#30D158', background: 'rgba(48,209,88,.2)' },
  CONFIRMING: { text: '确认中', color: '#0A84FF', background: 'rgba(10,132,255,.14)' },
  COMPLETED: { text: '已完成', color: '#30D158', background: 'rgba(48,209,88,.2)' },
  FAILED: { text: '履约失败', color: '#FF453A', background: 'rgba(255,69,58,.15)' },
  REVIEW_REQUIRED: { text: '需要核对', color: '#FF9F0A', background: 'rgba(255,159,10,.18)' },
  SKIPPED: { text: '已跳过', color: '#8E8E93', background: 'rgba(120,120,128,.12)' }
}

export const parseDeliveryStatuses = (value: unknown): string[] => {
  const raw = Array.isArray(value) ? value[0] : value
  if (typeof raw !== 'string') return []
  return [...new Set(raw.split(',').map(item => item.trim()).filter(item => statusMeta[item]))]
}

export const getDeliveryStatusMeta = (deliveryStatus: string | undefined, state: number) => {
  if (deliveryStatus && statusMeta[deliveryStatus]) return statusMeta[deliveryStatus]
  if (state === 1) return statusMeta.COMPLETED
  if (state === 0) return statusMeta.PENDING
  return statusMeta.FAILED
}
```

- [ ] **Step 4: 运行纯函数测试并确认通过**

Run: `node --test vue-code/temp-tests/order-status.test.ts`

Expected: PASS，2 tests completed。

- [ ] **Step 5: 接入 API、路由筛选和重排队**

`DeliveryRecordQueryReq` 增加 `deliveryStatuses?: string[]`，`DeliveryRecordVO` 增加 `deliveryStatus?: string`，并增加：

```typescript
export function requeueDelivery(data: { id: number; xianyuAccountId: number }) {
  return request<string>({
    url: '/order/requeueDelivery',
    method: 'POST',
    data
  })
}
```

`useOrderManager` 使用 `useRoute`、`useRouter`、`parseDeliveryStatuses` 和现有 `showConfirm`。初始化时读取 `route.query.deliveryStatus`，新增 `selectedDeliveryStatus` 和：

```typescript
const handleDeliveryStatusChange = () => {
  const statuses = parseDeliveryStatuses(selectedDeliveryStatus.value)
  queryParams.deliveryStatuses = statuses.length > 0 ? statuses : undefined
  queryParams.pageNum = 1
  router.replace({
    query: { ...route.query, deliveryStatus: selectedDeliveryStatus.value || undefined }
  })
  loadOrders()
}

const handleRetryDelivery = async (row: DeliveryRecordItem) => {
  if (!row.xianyuAccountId) return
  try {
    await showConfirm(`确认将订单「${row.orderId || row.id}」重新加入发货队列？`, '重新排队')
  } catch {
    return
  }
  row.retrying = true
  try {
    await requeueDelivery({ id: row.id, xianyuAccountId: row.xianyuAccountId })
    showSuccess('订单已重新进入发货队列')
    loadOrders()
  } catch (error: any) {
    showError(error.message || '重新排队失败')
  } finally {
    row.retrying = false
  }
}
```

`DeliveryRecordItem` 增加 `retrying?: boolean`。重置时同时清除状态筛选和路由参数。

- [ ] **Step 6: 接入桌面端与移动端筛选**

桌面端在关键词输入框前加入原生选择框：

```vue
<div class="orders__select-wrap orders__select-wrap--status">
  <select v-model="selectedDeliveryStatus" class="orders__select" @change="handleDeliveryStatusChange">
    <option v-for="option in deliveryStatusOptions" :key="option.value" :value="option.value">
      {{ option.label }}
    </option>
  </select>
  <span class="orders__select-icon"><IconChevronDown /></span>
</div>
```

移动端筛选面板增加相同选项，点击查询时将临时值同步到 `selectedDeliveryStatus` 后调用统一状态处理逻辑。

- [ ] **Step 7: 接入准确状态和失败重试按钮**

`OrderTable` 使用 `getDeliveryStatusMeta(order.deliveryStatus, order.state)` 输出文案、颜色和背景。失败原因在非空时直接显示，不再依赖 `state === -1`。

事件增加：

```typescript
(e: 'retryDelivery', item: DeliveryRecordItem): void
```

桌面端和移动端仅在 `order.deliveryStatus === 'FAILED'` 时显示：

```vue
<button
  class="table__action table__action--retry"
  :class="{ 'table__action--loading': order.retrying }"
  :disabled="order.retrying"
  @click="emit('retryDelivery', order)"
>
  <span>{{ order.retrying ? '处理中' : '重新排队' }}</span>
</button>
```

移动端使用现有操作按钮尺寸，CSS 只增加状态选择框宽度、重试按钮颜色和禁用态，不改动其他布局。

- [ ] **Step 8: 运行前端类型检查与构建**

Run:

```powershell
Set-Location vue-code
npm run type-check
npm run build-only
```

Expected: 两条命令退出码均为 0。

- [ ] **Step 9: 提交发货记录体验优化**

```powershell
git add vue-code/src/api/order.ts vue-code/src/views/orders/order-status.ts vue-code/src/views/orders/useOrderManager.ts vue-code/src/views/orders/index.vue vue-code/src/views/orders/components/OrderTable.vue vue-code/src/views/orders/orders.css
git commit -m "feat: 优化异常订单处理体验"
```

临时测试文件不得加入提交。

### Task 4: 完善首页待办与低库存定位

**Files:**
- Modify: `vue-code/src/views/dashboard/index.vue`
- Modify: `vue-code/src/views/dashboard/useDashboard.ts`
- Create: `vue-code/src/views/kami-config/kami-stock.ts`
- Modify: `vue-code/src/views/kami-config/index.vue`
- Temporary test: `vue-code/temp-tests/kami-stock.test.ts`

- [ ] **Step 1: 写入库存判断临时失败测试**

```typescript
import test from 'node:test'
import assert from 'node:assert/strict'
import { isLowStockConfig } from '../src/views/kami-config/kami-stock.ts'

test('数量阈值只标记已启用且低于阈值的仓库', () => {
  assert.equal(isLowStockConfig({ alertEnabled: 1, alertThresholdType: 1, alertThresholdValue: 10, totalCount: 20, availableCount: 9 }), true)
  assert.equal(isLowStockConfig({ alertEnabled: 0, alertThresholdType: 1, alertThresholdValue: 10, totalCount: 20, availableCount: 0 }), false)
})

test('百分比阈值与后端使用同一严格小于规则', () => {
  assert.equal(isLowStockConfig({ alertEnabled: 1, alertThresholdType: 2, alertThresholdValue: 10, totalCount: 100, availableCount: 9 }), true)
  assert.equal(isLowStockConfig({ alertEnabled: 1, alertThresholdType: 2, alertThresholdValue: 10, totalCount: 100, availableCount: 10 }), false)
})
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `node --test vue-code/temp-tests/kami-stock.test.ts`

Expected: FAIL，提示无法找到 `kami-stock.ts`。

- [ ] **Step 3: 创建库存判断纯函数并确认通过**

```typescript
interface StockAlertConfig {
  alertEnabled?: number
  alertThresholdType?: number
  alertThresholdValue?: number
  totalCount: number
  availableCount: number
}

export const isLowStockConfig = (config: StockAlertConfig) => {
  if (config.alertEnabled !== 1) return false
  const threshold = config.alertThresholdValue ?? 10
  if (config.alertThresholdType === 2) {
    return config.totalCount > 0 && config.availableCount * 100 < config.totalCount * threshold
  }
  return config.availableCount < threshold
}
```

Run: `node --test vue-code/temp-tests/kami-stock.test.ts`

Expected: PASS，2 tests completed。

- [ ] **Step 4: 增加首页精准跳转和可见页面刷新**

首页跳转改为：

```vue
@click="go('/orders', { deliveryStatus: 'PENDING,PROCESSING,RETRY_WAIT' })"
@click="go('/orders', { deliveryStatus: 'REVIEW_REQUIRED' })"
@click="go('/orders', { deliveryStatus: 'FAILED' })"
@click="go('/kami-config', { lowStock: '1' })"
```

页面脚本使用 `onUnmounted` 管理 60 秒定时器和 `visibilitychange`：

```typescript
const refreshWhenVisible = () => {
  if (document.visibilityState === 'visible') loadStatistics()
}

let refreshTimer: ReturnType<typeof setInterval> | undefined

onMounted(() => {
  loadStatistics()
  refreshTimer = setInterval(refreshWhenVisible, 60000)
  document.addEventListener('visibilitychange', refreshWhenVisible)
})

onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
  document.removeEventListener('visibilitychange', refreshWhenVisible)
})
```

`loadStatistics` 开头增加：

```typescript
if (loading.value) return
```

- [ ] **Step 5: 增加低库存仓库视图**

卡密页面读取 `route.query.lowStock`，增加：

```typescript
const lowStockOnly = ref(route.query.lowStock === '1')
const visibleKamiConfigs = computed(() => lowStockOnly.value
  ? kamiConfigs.value.filter(isLowStockConfig)
  : kamiConfigs.value)

const clearLowStockFilter = () => {
  lowStockOnly.value = false
  router.replace({ query: { ...route.query, lowStock: undefined } })
}
```

移动端与桌面端配置循环统一使用 `visibleKamiConfigs`。低库存视图顶部显示简洁提示和“查看全部”按钮；空状态显示“暂无低库存仓库”。仓库卡片只在 `isLowStockConfig(config)` 为真时显示“低库存”。

`loadKamiConfigs` 在低库存视图中只从 `visibleKamiConfigs` 选择首个仓库，避免详情区域展示被筛选掉的普通仓库。

- [ ] **Step 6: 运行前端纯函数、类型和构建验证**

Run:

```powershell
node --test vue-code/temp-tests/order-status.test.ts vue-code/temp-tests/kami-stock.test.ts
Set-Location vue-code
npm run type-check
npm run build-only
```

Expected: 4 个纯函数测试通过，类型检查和构建退出码均为 0。

- [ ] **Step 7: 提交经营入口优化**

```powershell
git add vue-code/src/views/dashboard/index.vue vue-code/src/views/dashboard/useDashboard.ts vue-code/src/views/kami-config/kami-stock.ts vue-code/src/views/kami-config/index.vue
git commit -m "feat: 完善经营待办与库存定位"
```

临时测试文件不得加入提交。

### Task 5: 增加商品资料安全编辑闭环

**Files:**
- Create: `src/main/java/com/xianyusmart/controller/dto/UpdateItemInfoReqDTO.java`
- Modify: `src/main/java/com/xianyusmart/service/ItemService.java`
- Modify: `src/main/java/com/xianyusmart/service/impl/ItemServiceImpl.java`
- Modify: `src/main/java/com/xianyusmart/service/GoodsInfoService.java`
- Modify: `src/main/java/com/xianyusmart/service/impl/GoodsInfoServiceImpl.java`
- Modify: `src/main/java/com/xianyusmart/controller/ItemController.java`
- Modify: `vue-code/src/api/goods.ts`
- Create: `vue-code/src/views/goods/goods-edit.ts`
- Create: `vue-code/src/views/goods/components/GoodsEditDialog.vue`
- Modify: `vue-code/src/views/goods/useGoodsManager.ts`
- Modify: `vue-code/src/views/goods/index.vue`
- Modify: `vue-code/src/views/goods/components/GoodsTable.vue`
- Temporary tests: `src/test/java/com/xianyusmart/service/impl/ItemServiceImplTempTest.java`, `vue-code/temp-tests/goods-edit.test.ts`

- [ ] **Step 1: 写入商品字段校验与账号隔离临时失败测试**

后端测试覆盖空标题、非法价格、非法封面协议、跨账号商品和合法更新；前端纯函数测试覆盖相同边界及平台详情地址回退。先运行并确认缺少 DTO、更新方法和纯函数模块。

- [ ] **Step 2: 实现后端本地商品资料更新**

`UpdateItemInfoReqDTO` 只包含账号 ID、商品 ID、标题、价格、商品描述和封面地址。`ItemServiceImpl` 统一校验并规范化文本与价格，`GoodsInfoServiceImpl` 使用账号 ID 与商品 ID 联合查询后仅更新四个安全字段和更新时间。`ItemController` 增加 `POST /api/items/updateInfo`，异常处理沿用现有商品接口模式。

- [ ] **Step 3: 实现前端校验与编辑弹窗**

`goods-edit.ts` 提供无依赖校验和平台详情地址解析。`GoodsEditDialog` 复用现有弹窗视觉，只展示标题、价格、描述、封面地址、只读状态与规格信息；提交期间禁用重复保存。说明本地资料与平台数据边界，按钮保持“取消”“前往闲鱼修改”“保存本地资料”三项。

- [ ] **Step 4: 接入桌面端和移动端商品列表**

`GoodsTable` 增加编辑事件和按钮，移动端使用 `@click.stop` 避免触发详情。`useGoodsManager` 维护当前编辑商品、提交状态、平台跳转和成功刷新；`index.vue` 挂载编辑弹窗并连接事件。平台修改完成后继续使用现有“同步”按钮回读，不复制同步实现。

- [ ] **Step 5: 运行临时测试、类型检查和构建**

Run:

```powershell
$env:JAVA_HOME='E:\java\jdk21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -Dtest=ItemServiceImplTempTest test
node --test vue-code/temp-tests/goods-edit.test.ts
Set-Location vue-code
npm run type-check
npm run build-only
```

Expected: 后端和前端临时测试通过，类型检查与构建退出码均为 0。

- [ ] **Step 6: 提交商品资料编辑闭环**

```powershell
git add src/main/java/com/xianyusmart/controller/dto/UpdateItemInfoReqDTO.java src/main/java/com/xianyusmart/service/ItemService.java src/main/java/com/xianyusmart/service/impl/ItemServiceImpl.java src/main/java/com/xianyusmart/service/GoodsInfoService.java src/main/java/com/xianyusmart/service/impl/GoodsInfoServiceImpl.java src/main/java/com/xianyusmart/controller/ItemController.java vue-code/src/api/goods.ts vue-code/src/views/goods/goods-edit.ts vue-code/src/views/goods/components/GoodsEditDialog.vue vue-code/src/views/goods/useGoodsManager.ts vue-code/src/views/goods/index.vue vue-code/src/views/goods/components/GoodsTable.vue
git commit -m "feat: 增加商品资料安全编辑"
```

临时测试文件不得加入提交。

### Task 6: 增加查询索引并完成全量验证

**Files:**
- Create: `src/main/resources/db/migration/V2__add_order_delivery_filter_index.sql`
- Delete temporary tests created by this plan

- [ ] **Step 1: 创建 Flyway 迁移**

```sql
CREATE INDEX idx_goods_order_account_delivery_created
    ON xianyu_goods_order (xianyu_account_id, delivery_status, create_time);
```

- [ ] **Step 2: 检查迁移顺序与重复索引**

Run:

```powershell
rg -n "idx_goods_order_account_delivery_created|CREATE TABLE xianyu_goods_order|idx_goods_order_task" src/main/resources/db/migration
```

Expected: 新索引名称只出现一次，`V2` 排在现有 `V1` 之后，现有任务索引保持不变。

- [ ] **Step 3: 删除全部临时测试产物**

删除：

```text
src/test/java/com/xianyusmart/service/impl/AutoDeliveryServiceImplTempTest.java
src/test/java/com/xianyusmart/service/impl/DeliveryTaskServiceImplTempTest.java
vue-code/temp-tests/order-status.test.ts
vue-code/temp-tests/kami-stock.test.ts
src/test/java/com/xianyusmart/service/impl/ItemServiceImplTempTest.java
vue-code/temp-tests/goods-edit.test.ts
```

若 `src/test` 或 `vue-code/temp-tests` 为本轮创建且删除文件后为空，同时删除空目录。

- [ ] **Step 4: 在无临时测试条件下执行后端验证**

Run:

```powershell
$env:JAVA_HOME='E:\java\jdk21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd test
.\mvnw.cmd -DskipTests package
```

Expected: BUILD SUCCESS，两条命令退出码均为 0。

- [ ] **Step 5: 执行前端完整验证**

Run:

```powershell
Set-Location vue-code
npm run type-check
npm run build-only
```

Expected: 两条命令退出码均为 0。

- [ ] **Step 6: 执行差异与敏感信息检查**

Run:

```powershell
git diff --check
git status --short
git grep -nE "(password|secret|private[_-]?key)" -- src vue-code docs README.md
```

Expected: `git diff --check` 无输出；临时测试文件不在状态列表；本轮文件不包含服务器、密码或外部项目标识。

- [ ] **Step 7: 自审关键安全路径**

逐项确认：

1. `REVIEW_REQUIRED` 页面无重排队按钮。
2. 重排队 SQL 同时限制记录 ID、账号 ID、`state <> 1` 和 `FAILED`。
3. 列表与总数查询使用相同状态条件。
4. 路由非法状态不会传入后端。
5. 页面隐藏时不调用首页周期刷新。
6. 低库存数量与百分比判断和后端 SQL 完全一致。
7. 商品更新 SQL 同时限制账号 ID 与商品 ID，且不修改状态、SKU、库存和自动化配置。
8. 本地保存与平台修改文案边界清楚，移动端编辑按钮不会误打开详情。

- [ ] **Step 8: 提交数据库迁移与验证收尾**

```powershell
git add src/main/resources/db/migration/V2__add_order_delivery_filter_index.sql
git commit -m "perf: 优化履约记录状态查询"
```

- [ ] **Step 9: 推送并按项目部署技能更新生产环境**

Run:

```powershell
git push origin main
```

随后严格调用项目本地 `updating-xianyusmart` 技能，仅执行本地打包、上传 JAR、服务重启和健康检查，不修改 MySQL、Nginx 或其他服务，不创建 Release。
