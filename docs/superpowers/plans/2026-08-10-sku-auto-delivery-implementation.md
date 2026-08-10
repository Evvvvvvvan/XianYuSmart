# Multi-SKU Auto-Delivery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让多规格商品能够逐个 SKU 配置自动发货，并在订单规格不明或未配置时停止发货，避免误发其他规格的卡密。

**Architecture:** 继续使用现有 SKU 表和自动发货配置表，在查询与保存入口补齐账号归属，并把配置查询和发货选择改为精确 SKU 语义。前端复用现有配置表单，增加可靠的 SKU 加载状态、规格切换、配置进度和未保存保护。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、Vue 3、TypeScript、Vite

---

## 文件结构

- 修改 `src/main/java/com/xianyusmart/service/GoodsSkuService.java`：增加账号隔离查询、计数和精确 SKU 查询接口。
- 修改 `src/main/java/com/xianyusmart/service/impl/GoodsSkuServiceImpl.java`：实现账号与商品联合条件。
- 修改 `src/main/java/com/xianyusmart/controller/GoodsSkuController.java`：SKU 列表和详情接收账号 ID。
- 修改 `src/main/java/com/xianyusmart/service/impl/AutoDeliveryConfigServiceImpl.java`：保存时校验 SKU 归属，查询时禁止指定 SKU 回退。
- 修改 `src/main/java/com/xianyusmart/service/impl/AutoDeliveryServiceImpl.java`：多规格订单严格匹配 SKU 配置。
- 修改 `vue-code/src/api/auto-delivery-config.ts`：SKU 请求携带账号 ID。
- 修改 `vue-code/src/views/auto-delivery/useAutoDelivery.ts`：SKU 状态、配置进度、错误重试和未保存保护。
- 修改 `vue-code/src/views/auto-delivery/index.vue`：完整规格选择 UI。
- 修改 `vue-code/src/views/auto-delivery/auto-delivery.css`：桌面和移动端布局。
- 临时创建 `src/test/java/com/xianyusmart/service/impl/GoodsSkuServiceImplTempTest.java`、`AutoDeliveryConfigServiceImplTempTest.java` 和 `AutoDeliverySkuRoutingTempTest.java`，验证后删除。

### Task 1: SKU 账号隔离查询

**Files:**
- Modify: `src/main/java/com/xianyusmart/service/GoodsSkuService.java`
- Modify: `src/main/java/com/xianyusmart/service/impl/GoodsSkuServiceImpl.java`
- Modify: `src/main/java/com/xianyusmart/controller/GoodsSkuController.java`
- Test: `src/test/java/com/xianyusmart/service/impl/GoodsSkuServiceImplTempTest.java`

- [ ] **Step 1: 编写失败测试**

使用 Mockito 验证账号查询条件同时包含账号 ID、商品 ID，并验证精确 SKU 查询不会返回其他账号数据。核心断言：

```java
assertThat(service.listByXyGoodsId("goods-1", 10L)).hasSize(1);
assertThat(service.findByXyGoodsIdAndSkuId("goods-1", 10L, "sku-1")).isNotNull();
assertThat(service.findByXyGoodsIdAndSkuId("goods-1", 20L, "sku-1")).isNull();
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -Dtest=GoodsSkuServiceImplTempTest test`

Expected: FAIL，原因是账号隔离方法尚不存在。

- [ ] **Step 3: 实现最小查询接口**

接口增加：

```java
List<XianyuGoodsSku> listByXyGoodsId(String xyGoodsId, Long xianyuAccountId);
int countByXyGoodsId(String xyGoodsId, Long xianyuAccountId);
XianyuGoodsSku findByXyGoodsIdAndSkuId(String xyGoodsId, Long xianyuAccountId, String skuId);
```

实现统一使用账号和商品联合条件；Controller 的 `/list`、`/detail` 增加 `xianyuAccountId` 请求参数并调用新方法。

- [ ] **Step 4: 运行测试并确认通过**

Run: `mvn -Dtest=GoodsSkuServiceImplTempTest test`

Expected: PASS。

- [ ] **Step 5: 提交 SKU 查询改动**

```powershell
git add src/main/java/com/xianyusmart/service/GoodsSkuService.java src/main/java/com/xianyusmart/service/impl/GoodsSkuServiceImpl.java src/main/java/com/xianyusmart/controller/GoodsSkuController.java
git commit -m "fix: scope sku queries by account"
```

### Task 2: SKU 配置保存与查询严格化

**Files:**
- Modify: `src/main/java/com/xianyusmart/service/impl/AutoDeliveryConfigServiceImpl.java`
- Test: `src/test/java/com/xianyusmart/service/impl/AutoDeliveryConfigServiceImplTempTest.java`

- [ ] **Step 1: 编写失败测试**

覆盖两个行为：非当前商品 SKU 保存失败；请求指定 SKU 时精确配置缺失返回空，不回退通用配置。

```java
assertThat(service.saveOrUpdateConfig(requestWithForeignSku()).getCode()).isNotEqualTo(200);
assertThat(service.getConfig(queryForMissingSku()).getData()).isNull();
verify(configMapper, never()).findByAccountIdAndGoodsIdNoSku(10L, "goods-1");
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -Dtest=AutoDeliveryConfigServiceImplTempTest test`

Expected: FAIL，当前保存缺少 SKU 归属校验，指定 SKU 查询仍回退通用配置。

- [ ] **Step 3: 实现 SKU 归属与精确查询**

注入 `GoodsSkuService`。保存时使用精确查询获取数据库 SKU：

```java
XianyuGoodsSku sku = goodsSkuService.findByXyGoodsIdAndSkuId(
        reqDTO.getXyGoodsId(), accountId, reqDTO.getSkuId());
if (sku == null) {
    throw new IllegalArgumentException("商品规格不存在或不属于当前账号");
}
reqDTO.setSkuName(sku.getValueText());
```

`getConfig` 仅在请求没有 `skuId` 时查询无 SKU 配置。

- [ ] **Step 4: 运行测试并确认通过**

Run: `mvn -Dtest=AutoDeliveryConfigServiceImplTempTest test`

Expected: PASS。

- [ ] **Step 5: 提交配置严格化改动**

```powershell
git add src/main/java/com/xianyusmart/service/impl/AutoDeliveryConfigServiceImpl.java
git commit -m "fix: validate sku delivery configuration"
```

### Task 3: 订单发货严格匹配 SKU

**Files:**
- Modify: `src/main/java/com/xianyusmart/service/impl/AutoDeliveryServiceImpl.java`
- Test: `src/test/java/com/xianyusmart/service/impl/AutoDeliverySkuRoutingTempTest.java`

- [ ] **Step 1: 编写失败测试**

覆盖无规格兼容、多规格精确命中、多规格 SKU 缺失、多规格配置缺失四个分支：

```java
assertThat(resolveConfig(10L, "plain", null)).isEqualTo(baseConfig);
assertThat(resolveConfig(10L, "multi", "sku-1")).isEqualTo(skuConfig);
assertThatThrownBy(() -> resolveConfig(10L, "multi", null))
        .hasMessageContaining("订单规格未识别");
assertThatThrownBy(() -> resolveConfig(10L, "multi", "sku-2"))
        .hasMessageContaining("当前规格未配置");
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -Dtest=AutoDeliverySkuRoutingTempTest test`

Expected: FAIL，当前逻辑会回退通用配置。

- [ ] **Step 3: 实现严格分流**

注入 `GoodsSkuService`，在现有订单详情获取后执行：

```java
int skuCount = goodsSkuService.countByXyGoodsId(xyGoodsId, accountId);
if (skuCount == 0) {
    return autoDeliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(accountId, xyGoodsId);
}
if (orderSkuId == null || orderSkuId.isBlank()) {
    throw new IllegalStateException("订单规格未识别，已停止自动发货");
}
if (goodsSkuService.findByXyGoodsIdAndSkuId(xyGoodsId, accountId, orderSkuId) == null) {
    throw new IllegalStateException("订单规格不存在或已失效，已停止自动发货");
}
XianyuGoodsAutoDeliveryConfig config = autoDeliveryConfigMapper
        .findByAccountIdAndGoodsIdAndSkuId(accountId, xyGoodsId, orderSkuId);
if (config == null) {
    throw new IllegalStateException("当前规格未配置自动发货");
}
return config;
```

在领取卡密前捕获该业务错误，更新订单记录后直接返回。

- [ ] **Step 4: 运行测试并确认通过**

Run: `mvn -Dtest=AutoDeliverySkuRoutingTempTest test`

Expected: PASS，并验证未调用卡密领取接口。

- [ ] **Step 5: 提交发货路由改动**

```powershell
git add src/main/java/com/xianyusmart/service/impl/AutoDeliveryServiceImpl.java
git commit -m "fix: require exact sku delivery config"
```

### Task 4: 完善 SKU 前端状态与交互

**Files:**
- Modify: `vue-code/src/api/auto-delivery-config.ts`
- Modify: `vue-code/src/views/auto-delivery/useAutoDelivery.ts`
- Modify: `vue-code/src/views/auto-delivery/index.vue`
- Modify: `vue-code/src/views/auto-delivery/auto-delivery.css`

- [ ] **Step 1: 让类型检查暴露旧接口调用**

先把 `getGoodsSkuList` 签名改为：

```ts
export function getGoodsSkuList(xianyuAccountId: number, xyGoodsId: string)
```

Run: `npm run type-check`

Expected: FAIL，旧调用缺少账号参数。

- [ ] **Step 2: 实现加载、错误和配置进度状态**

在组合式逻辑中增加：

```ts
const skuLoading = ref(false)
const skuLoadError = ref('')
const configuredSkuCount = computed(() =>
  skuList.value.filter(sku => sku.skuId && skuConfigs.value.has(sku.skuId)).length)
```

加载失败保留错误文本；保存按钮在 SKU 加载中、加载失败或当前 SKU 无有效 ID 时禁用。

- [ ] **Step 3: 实现安全切换**

保存最近加载表单的 JSON 快照，切换账号、商品、SKU 和移动端返回前调用：

```ts
const confirmDiscardChanges = () =>
  !hasUnsavedChanges.value || window.confirm('当前配置尚未保存，确认放弃修改吗？')
```

规格按钮改为调用 `selectSku(skuId)`，确认后再修改 `selectedSkuId`，不在模板中直接赋值。

- [ ] **Step 4: 完善 SKU UI 和移动端样式**

显示“已配置 N/M”、加载错误重试、规格名称、价格、库存和配置状态。按钮设置 `title` 展示完整组合名；窄屏下自动换行并保证触控区域不小于现有按钮高度。

- [ ] **Step 5: 执行前端验证**

Run: `npm run type-check`

Expected: PASS。

Run: `npm run build:spring`

Expected: PASS，生成 Spring Boot 静态资源。

- [ ] **Step 6: 提交前端改动**

```powershell
git add vue-code/src/api/auto-delivery-config.ts vue-code/src/views/auto-delivery/useAutoDelivery.ts vue-code/src/views/auto-delivery/index.vue vue-code/src/views/auto-delivery/auto-delivery.css src/main/resources/static
git commit -m "feat: complete sku delivery configuration ui"
```

### Task 5: 清理临时测试并复验

**Files:**
- Delete: `src/test/java/com/xianyusmart/service/impl/GoodsSkuServiceImplTempTest.java`
- Delete: `src/test/java/com/xianyusmart/service/impl/AutoDeliveryConfigServiceImplTempTest.java`
- Delete: `src/test/java/com/xianyusmart/service/impl/AutoDeliverySkuRoutingTempTest.java`

- [ ] **Step 1: 删除本次临时测试文件**

只删除本计划创建的三个临时测试，不删除项目原有测试。

- [ ] **Step 2: 重新执行正式验证**

Run: `mvn test`

Expected: BUILD SUCCESS。

Run: `npm run type-check`

Expected: PASS。

- [ ] **Step 3: 检查差异范围**

Run: `git diff --check`

Expected: 无输出。

Run: `git status --short`

Expected: 不包含临时测试文件。
