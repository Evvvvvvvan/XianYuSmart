# XianYuSmart Merchant Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建设单商家私有部署的 XianYuSmart 1.0.0，重点解决 MySQL 部署、卡密重复交付、任务重启丢失、公网安全和商家待办效率。

**Architecture:** 保持 Spring Boot 3 + Vue 3 单体结构，以 MySQL 8 同时承担业务存储、幂等约束和短租约任务队列。WebSocket 与订单轮询只负责发现订单，履约协调器统一预占卡密、发送、确认和重试；不引入 Redis、消息队列、微服务或新的前端组件库。

**Tech Stack:** Java 21、Spring Boot 3.5、MyBatis-Plus、Flyway、MySQL 8、Vue 3、TypeScript、Vite、Docker Compose、Nginx。

---

## 文件结构

- `pom.xml`：产品坐标、MySQL/Flyway/Actuator 依赖和测试依赖去重。
- `src/main/resources/application.yaml`：全部环境变量、连接池、线程池、安全和日志默认值。
- `src/main/resources/db/migration/V1__baseline.sql`：全新 MySQL 8 基线结构、唯一约束和生产索引。
- `compose.yaml`、`.env.example`、`deploy/nginx/default.conf`：Windows/Linux 共用部署入口。
- `src/main/java/com/xianyusmart/config/AsyncConfig.java`：统一有界线程池。
- `src/main/java/com/xianyusmart/config/WebMvcConfig.java`：同源优先 CORS、认证拦截和 SPA 路由。
- `src/main/java/com/xianyusmart/service/impl/KamiConfigServiceImpl.java`：事务预占、提交、释放和导入去重。
- `src/main/java/com/xianyusmart/service/impl/AutoDeliveryServiceImpl.java`：单一履约状态机。
- `src/main/java/com/xianyusmart/service/DeliveryTaskService.java`：订单发现、租约领取、重试与恢复边界。
- `src/main/java/com/xianyusmart/service/impl/DeliveryTaskServiceImpl.java`：MySQL 持久任务实现。
- `src/main/java/com/xianyusmart/service/DeliveryTaskScheduler.java`：固定小批量领取到期任务。
- `src/main/java/com/xianyusmart/service/reply/AutoReplyDelayServiceImpl.java`：数据库恢复依据和有界执行。
- `src/main/java/com/xianyusmart/service/reply/HumanTakeoverManager.java`：人工接管持久化。
- `src/main/java/com/xianyusmart/util/JwtUtil.java`、`src/main/java/com/xianyusmart/service/impl/AuthServiceImpl.java`：强密钥、短会话、令牌摘要和会话撤销。
- `src/main/java/com/xianyusmart/service/impl/ImageUploadServiceImpl.java`：URL 图片 SSRF、体积和超时边界。
- `src/main/java/com/xianyusmart/controller/DataBackupController.java`：日志目录边界。
- `vue-code/src/assets/theme.css`、`vue-code/src/views/dashboard/dashboard.css`：简约商用视觉变量与页面结构。
- `vue-code/src/components/layout/NavMenu.vue`、`vue-code/src/views/dashboard/index.vue`：经营导向导航和待办首屏。
- `README.md`、`docs/ARCHITECTURE.md`、`docs/OPERATIONS.md`：产品能力、痛点映射、部署和故障处理。

### Task 1: 建立 MySQL 与可复制部署基线

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yaml`
- Delete: `src/main/java/com/xianyusmart/config/DatabaseConfig.java`
- Delete: `src/main/java/com/xianyusmart/config/DatabaseInitListener.java`
- Delete: `src/main/java/com/xianyusmart/config/SqlSchemaParser.java`
- Delete: `src/main/java/com/xianyusmart/utils/DatabaseChecker.java`
- Create: `src/main/resources/db/migration/V1__baseline.sql`
- Delete: `src/main/resources/sql/schema.sql`
- Create: `compose.yaml`
- Create: `.env.example`
- Create: `deploy/nginx/default.conf`
- Modify: `Dockerfile`
- Temporary test: `src/test/java/com/xianyusmart/config/MySqlBaselineTest.java`

- [ ] **Step 1: 写入 MySQL 基线失败测试**

```java
@SpringBootTest
@ActiveProfiles("test")
class MySqlBaselineTest {
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void flywayCreatesDeliveryConstraints() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics " +
                "WHERE table_schema = DATABASE() AND index_name = 'uk_goods_order_account_order'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 运行测试并确认 SQLite 配置无法满足 MySQL 断言**

Run: `./mvnw.cmd -Dtest=MySqlBaselineTest test`

Expected: FAIL，原因是 MySQL 驱动、数据源或 Flyway 基线尚未配置。

- [ ] **Step 3: 切换依赖与环境配置**

`pom.xml` 使用以下核心坐标，删除 SQLite 驱动和重复的独立 JUnit 依赖：

```xml
<artifactId>xianyusmart</artifactId>
<version>1.0.0</version>
<name>XianYuSmart</name>
<description>单商家私有化闲鱼虚拟商品经营助手</description>
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

`application.yaml` 的数据源必须由环境变量提供：

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:mysql://localhost:3306/xianyusmart?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true}
    username: ${DB_USERNAME:xianyusmart}
    password: ${DB_PASSWORD:xianyusmart}
    hikari:
      maximum-pool-size: ${DB_POOL_MAX_SIZE:10}
      minimum-idle: ${DB_POOL_MIN_IDLE:2}
  flyway:
    enabled: true
    locations: classpath:db/migration
```

- [ ] **Step 4: 创建完整 MySQL 8 基线**

将现有表逐表翻译为 MySQL 8，统一使用 `BIGINT AUTO_INCREMENT`、`DATETIME(3)`、`utf8mb4_0900_ai_ci` 和 InnoDB；履约表至少包含以下唯一键与索引：

```sql
ALTER TABLE xianyu_goods_order
    ADD UNIQUE KEY uk_goods_order_account_message (xianyu_account_id, pnm_id),
    ADD UNIQUE KEY uk_goods_order_account_order (xianyu_account_id, order_id),
    ADD KEY idx_goods_order_task (delivery_status, next_retry_time, lease_expire_time),
    ADD KEY idx_goods_order_created (xianyu_account_id, create_time);

ALTER TABLE xianyu_kami_item
    ADD UNIQUE KEY uk_kami_config_content (kami_config_id, kami_content(191)),
    ADD KEY idx_kami_reserve (kami_config_id, status, sort_order, id),
    ADD KEY idx_kami_order (order_id, status);

ALTER TABLE xianyu_kami_usage_record
    ADD COLUMN delivery_index INT NOT NULL DEFAULT 1 COMMENT '订单内交付序号',
    ADD COLUMN delivery_status VARCHAR(24) NOT NULL DEFAULT 'RESERVED' COMMENT '卡密交付状态',
    ADD UNIQUE KEY uk_usage_order_index (xianyu_account_id, order_id, delivery_index);
```

- [ ] **Step 5: 创建 Compose 与 Nginx 配置**

`compose.yaml` 只包含 `mysql`、`app` 和 `nginx`，应用等待 MySQL 健康状态；`app` 不直接暴露公网端口，开发 profile 可映射 `12400`；Nginx 限制请求体并转发可信代理头。

```yaml
services:
  mysql:
    image: mysql:8.4
    environment:
      MYSQL_DATABASE: ${DB_NAME:-xianyusmart}
      MYSQL_USER: ${DB_USERNAME:-xianyusmart}
      MYSQL_PASSWORD: ${DB_PASSWORD}
      MYSQL_ROOT_PASSWORD: ${DB_ROOT_PASSWORD}
    healthcheck:
      test: ["CMD-SHELL", "mysqladmin ping -h localhost -uroot -p$$MYSQL_ROOT_PASSWORD --silent"]
      interval: 5s
      timeout: 3s
      retries: 30
  app:
    build: .
    depends_on:
      mysql:
        condition: service_healthy
    environment:
      DB_URL: jdbc:mysql://mysql:3306/${DB_NAME:-xianyusmart}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true
      DB_USERNAME: ${DB_USERNAME:-xianyusmart}
      DB_PASSWORD: ${DB_PASSWORD}
      JWT_SECRET: ${JWT_SECRET}
  nginx:
    image: nginx:1.27-alpine
    profiles: ["proxy"]
    depends_on: [app]
```

- [ ] **Step 6: 验证配置、迁移和构建**

Run: `docker compose --env-file .env.example config`

Expected: 配置解析成功且 `mysql`、`app`、`nginx` 均存在。

Run: `./mvnw.cmd test`

Expected: BUILD SUCCESS。

- [ ] **Step 7: 删除临时测试并提交**

删除 `src/test/java/com/xianyusmart/config/MySqlBaselineTest.java`，再次运行 `./mvnw.cmd test` 后提交。

```bash
git add pom.xml src/main src/test compose.yaml .env.example deploy Dockerfile
git commit -m "feat: 建立 XianYuSmart MySQL 部署基线"
```

### Task 2: 修正 MySQL SQL 并统一有界资源

**Files:**
- Modify: `src/main/java/com/xianyusmart/mapper/XianyuHumanInterventionRecordMapper.java`
- Modify: `src/main/java/com/xianyusmart/mapper/XianyuKamiItemMapper.java`
- Modify: `src/main/java/com/xianyusmart/mapper/XianyuGoodsOrderMapper.java`
- Modify: `src/main/java/com/xianyusmart/mapper/XianyuKeywordReplyRuleMapper.java`
- Modify: `src/main/java/com/xianyusmart/mapper/XianyuGoodsAutoReplyRecordMapper.java`
- Modify: `src/main/java/com/xianyusmart/config/AsyncConfig.java`
- Modify: `src/main/resources/logback-spring.xml`
- Temporary test: `src/test/java/com/xianyusmart/mapper/MySqlMapperSyntaxTest.java`

- [ ] **Step 1: 写入 mapper 集成失败测试**

```java
@SpringBootTest
class MySqlMapperSyntaxTest {
    @Autowired XianyuKamiItemMapper kamiItemMapper;
    @Autowired XianyuHumanInterventionRecordMapper interventionMapper;

    @Test
    void mysqlSpecificQueriesExecute() {
        assertThatCode(() -> kamiItemMapper.findByConfigIdWithFilter(1L, 0, "demo"))
                .doesNotThrowAnyException();
        assertThatCode(() -> interventionMapper.deleteExpired())
                .doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: 运行测试并确认 SQLite 方言失败**

Run: `./mvnw.cmd -Dtest=MySqlMapperSyntaxTest test`

Expected: FAIL，SQL 错误包含 `RANDOM`、`||` 或 `datetime('now')`。

- [ ] **Step 3: 替换全部 SQLite 方言**

```sql
ORDER BY RAND()
LIKE CONCAT('%', #{keyword}, '%')
NOW(3)
```

Run: `rg -n "AUTOINCREMENT|sqlite_master|PRAGMA|RANDOM\(\)|datetime\('now'|INSERT OR IGNORE|LIKE '%' \|\|" src/main`

Expected: 无匹配。

- [ ] **Step 4: 配置统一有界执行器**

```java
@Bean("businessExecutor")
public ThreadPoolTaskExecutor businessExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(corePoolSize);
    executor.setMaxPoolSize(maxPoolSize);
    executor.setQueueCapacity(queueCapacity);
    executor.setThreadNamePrefix("xys-business-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.initialize();
    return executor;
}
```

Run: `rg -n "new Thread\(|newScheduledThreadPool|newFixedThreadPool|newSingleThread" src/main/java`

Expected: 核心履约、回复和自动确认流程无手工线程创建。

- [ ] **Step 5: 删除临时测试、全量构建并提交**

Run: `./mvnw.cmd test`

Expected: BUILD SUCCESS。

```bash
git add src/main
git commit -m "perf: 适配 MySQL 并统一有界执行资源"
```

### Task 3: 建立幂等卡密履约状态机

**Files:**
- Modify: `src/main/java/com/xianyusmart/entity/XianyuKamiItem.java`
- Modify: `src/main/java/com/xianyusmart/entity/XianyuKamiUsageRecord.java`
- Modify: `src/main/java/com/xianyusmart/entity/XianyuGoodsOrder.java`
- Create: `src/main/java/com/xianyusmart/enums/KamiStatus.java`
- Create: `src/main/java/com/xianyusmart/enums/DeliveryStatus.java`
- Create: `src/main/java/com/xianyusmart/enums/DeliveryChannel.java`
- Modify: `src/main/java/com/xianyusmart/mapper/XianyuKamiItemMapper.java`
- Modify: `src/main/java/com/xianyusmart/mapper/XianyuGoodsOrderMapper.java`
- Modify: `src/main/java/com/xianyusmart/service/KamiConfigService.java`
- Modify: `src/main/java/com/xianyusmart/service/impl/KamiConfigServiceImpl.java`
- Modify: `src/main/java/com/xianyusmart/service/impl/AutoDeliveryServiceImpl.java`
- Temporary test: `src/test/java/com/xianyusmart/service/KamiReservationConcurrencyTest.java`

- [ ] **Step 1: 写入并发预占失败测试**

```java
@Test
void tenConcurrentOrdersNeverShareCard() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(10);
    List<Future<List<XianyuKamiItem>>> futures = IntStream.range(0, 10)
            .mapToObj(i -> pool.submit(() -> kamiConfigService.reserveKami(1L, "ORDER-" + i, 1)))
            .toList();
    List<Long> ids = futures.stream().flatMap(f -> get(f).stream()).map(XianyuKamiItem::getId).toList();
    assertThat(ids).doesNotHaveDuplicates();
}
```

- [ ] **Step 2: 运行测试并确认旧单进程锁方案失败或缺少接口**

Run: `./mvnw.cmd -Dtest=KamiReservationConcurrencyTest test`

Expected: FAIL，`reserveKami` 尚不存在。

- [ ] **Step 3: 增加清晰状态枚举和字段**

```java
public enum KamiStatus {
    AVAILABLE(0), DELIVERED(1), RESERVED(2), REVIEW_REQUIRED(3);
    private final int code;
}

public enum DeliveryStatus {
    PENDING, PROCESSING, RETRY_WAIT, DELIVERED, CONFIRMING, COMPLETED, FAILED, REVIEW_REQUIRED
}

public enum DeliveryChannel {
    WEBSOCKET, HTTP_API
}
```

订单实体增加 `deliveryStatus`、`expectedQuantity`、`deliveredQuantity`、`attemptCount`、`nextRetryTime`、`leaseOwner`、`leaseExpireTime`、`deliveryChannel`、`lastErrorCode` 和 `lastErrorMessage`。

- [ ] **Step 4: 使用数据库行锁批量预占**

```java
@Select("SELECT * FROM xianyu_kami_item WHERE kami_config_id = #{kamiConfigId} " +
        "AND status = 0 ORDER BY sort_order, id LIMIT #{quantity} FOR UPDATE SKIP LOCKED")
List<XianyuKamiItem> lockAvailable(@Param("kamiConfigId") Long kamiConfigId,
                                   @Param("quantity") int quantity);

@Update("<script>UPDATE xianyu_kami_item SET status = 2, order_id = #{orderId}, " +
        "reserved_time = NOW(3) WHERE status = 0 AND id IN " +
        "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
int reserve(@Param("ids") List<Long> ids, @Param("orderId") String orderId);
```

```java
@Transactional
public List<XianyuKamiItem> reserveKami(Long configId, String orderId, int quantity) {
    List<XianyuKamiItem> existing = kamiItemMapper.findReservedByOrder(configId, orderId);
    if (!existing.isEmpty()) return existing;
    List<XianyuKamiItem> items = kamiItemMapper.lockAvailable(configId, quantity);
    if (items.size() != quantity) throw new BusinessException(409, "卡密库存不足");
    int changed = kamiItemMapper.reserve(items.stream().map(XianyuKamiItem::getId).toList(), orderId);
    if (changed != quantity) throw new BusinessException(409, "卡密预占冲突");
    return items;
}
```

- [ ] **Step 5: 发送后提交，明确失败释放，不确定结果隔离**

```java
switch (sendResult.status()) {
    case SUCCESS -> kamiConfigService.commitReservation(orderId);
    case DEFINITE_FAILURE -> kamiConfigService.releaseReservation(orderId);
    case UNKNOWN -> kamiConfigService.markReservationReviewRequired(orderId);
}
```

多件购买必须先一次预占完整数量；缺少任意一件时不得发送部分内容。图片发送结果与文本/卡密交付结果分开记录。

- [ ] **Step 6: 验证并提交**

Run: `./mvnw.cmd -Dtest=KamiReservationConcurrencyTest test`

Expected: PASS，卡密 ID 无重复。

删除临时测试后再次运行 `./mvnw.cmd test`。

```bash
git add src/main
git commit -m "feat: 增加卡密事务预占与幂等交付"
```

### Task 4: 统一订单发现、持久任务与重启恢复

**Files:**
- Create: `src/main/java/com/xianyusmart/service/DeliveryTaskService.java`
- Create: `src/main/java/com/xianyusmart/service/impl/DeliveryTaskServiceImpl.java`
- Create: `src/main/java/com/xianyusmart/service/DeliveryTaskScheduler.java`
- Modify: `src/main/java/com/xianyusmart/event/chatMessageEvent/lister/ChatMessageEventAutoDeliveryListener.java`
- Modify: `src/main/java/com/xianyusmart/service/PendingOrderPollService.java`
- Delete: `src/main/java/com/xianyusmart/service/ApiDeliveryScheduler.java`
- Modify: `src/main/java/com/xianyusmart/service/impl/AutoDeliveryServiceImpl.java`
- Temporary test: `src/test/java/com/xianyusmart/service/DeliveryTaskRecoveryTest.java`

- [ ] **Step 1: 写入双入口幂等和租约恢复失败测试**

```java
@Test
void websocketAndPollingCreateOneTask() {
    deliveryTaskService.discover(accountId, orderId, messageId, DeliveryChannel.WEBSOCKET);
    deliveryTaskService.discover(accountId, orderId, null, DeliveryChannel.HTTP_API);
    assertThat(orderMapper.countByAccountAndOrder(accountId, orderId)).isEqualTo(1);
}

@Test
void expiredLeaseCanBeClaimedAgain() {
    seedProcessingTaskWithExpiredLease();
    assertThat(deliveryTaskService.claimDueTasks("worker-b", 20)).hasSize(1);
}
```

- [ ] **Step 2: 运行测试并确认当前双履约实现失败**

Run: `./mvnw.cmd -Dtest=DeliveryTaskRecoveryTest test`

Expected: FAIL，统一任务接口尚不存在。

- [ ] **Step 3: 订单发现仅执行 upsert**

```java
public interface DeliveryTaskService {
    void discover(Long accountId, String orderId, String messageId, DeliveryChannel channel);
    List<XianyuGoodsOrder> claimDueTasks(String workerId, int batchSize);
    void execute(Long taskId, String workerId);
}
```

WebSocket 监听器和订单轮询只调用 `discover`，不直接解析卡密或发送消息。重复键返回已有状态，不依赖异常文本判断。

- [ ] **Step 4: 短租约小批量领取**

```sql
UPDATE xianyu_goods_order
SET delivery_status = 'PROCESSING', lease_owner = :workerId,
    lease_expire_time = DATE_ADD(NOW(3), INTERVAL 60 SECOND)
WHERE id IN (
    SELECT id FROM (
        SELECT id FROM xianyu_goods_order
        WHERE delivery_status IN ('PENDING', 'RETRY_WAIT', 'PROCESSING')
          AND (next_retry_time IS NULL OR next_retry_time <= NOW(3))
          AND (lease_expire_time IS NULL OR lease_expire_time < NOW(3))
        ORDER BY create_time LIMIT :batchSize
    ) due
);
```

- [ ] **Step 5: 分类重试与人工待办**

网络超时、临时 API 错误、断连进入 `RETRY_WAIT`，重试间隔为 `min(300, 5 * 2^attempt)` 秒并增加小抖动；缺货、凭证失效、SKU 不匹配、配置缺失和 ACK 不确定进入 `REVIEW_REQUIRED`。超过最大尝试次数进入 `FAILED`。

- [ ] **Step 6: 验证重启恢复并提交**

Run: `./mvnw.cmd -Dtest=DeliveryTaskRecoveryTest test`

Expected: PASS，重复发现只有一条任务，过期租约可重新领取。

删除临时测试后运行 `./mvnw.cmd test`。

```bash
git add src/main
git commit -m "feat: 统一订单发现与可恢复履约任务"
```

### Task 5: 持久化客服状态并消除阻塞与 N+1

**Files:**
- Modify: `src/main/java/com/xianyusmart/entity/XianyuGoodsAutoReplyRecord.java`
- Modify: `src/main/java/com/xianyusmart/mapper/XianyuGoodsAutoReplyRecordMapper.java`
- Modify: `src/main/java/com/xianyusmart/mapper/XianyuHumanInterventionRecordMapper.java`
- Modify: `src/main/java/com/xianyusmart/mapper/XianyuKeywordReplyRuleMapper.java`
- Modify: `src/main/java/com/xianyusmart/service/reply/AutoReplyDelayServiceImpl.java`
- Modify: `src/main/java/com/xianyusmart/service/reply/HumanTakeoverManager.java`
- Modify: `src/main/java/com/xianyusmart/service/impl/KeywordReplyServiceImpl.java`
- Modify: `src/main/java/com/xianyusmart/service/impl/AutoReplyServiceImpl.java`
- Temporary test: `src/test/java/com/xianyusmart/service/AutoReplyRecoveryTest.java`

- [ ] **Step 1: 写入人工接管和延迟回复恢复失败测试**

```java
@Test
void takeoverAndPendingReplySurviveManagerRecreation() {
    humanTakeoverManager.start(accountId, sessionId, Duration.ofHours(1));
    autoReplyDelayService.schedule(context);
    clearOnlyLocalCaches();
    assertThat(humanTakeoverManager.isTakenOver(accountId, sessionId)).isTrue();
    assertThat(autoReplyRecordMapper.findDue(Instant.now(), 20)).hasSize(1);
}
```

- [ ] **Step 2: 运行测试并确认内存状态无法恢复**

Run: `./mvnw.cmd -Dtest=AutoReplyRecoveryTest test`

Expected: FAIL。

- [ ] **Step 3: 数据库作为恢复依据**

回复任务保存 `scheduled_time`、`attempt_count`、`next_retry_time`、`lease_owner`、`lease_expire_time` 和 `last_error_code`；人工接管以 `(xianyu_account_id, s_id)` 唯一键更新结束时间。内存只做热点缓存，缓存未命中必须回查数据库。

- [ ] **Step 4: 批量读取规则与内容**

```sql
SELECT r.*, c.id AS content_id, c.content_type, c.content, c.sort_order
FROM xianyu_keyword_reply_rule r
LEFT JOIN xianyu_keyword_reply_content c ON c.rule_id = r.id
WHERE r.xianyu_account_id = #{accountId}
  AND r.xy_goods_id = #{goodsId}
ORDER BY r.priority DESC, r.id, c.sort_order, c.id;
```

一次查询组装规则和内容，禁止对每条规则再次查询内容。

- [ ] **Step 5: AI 使用独立有界执行和明确降级**

AI 调用设置连接、读取和总超时；超时或限流时直接返回关键词原文或配置的降级文案，不能占用延时调度线程。文本和图片全部要求项成功后才记录回复成功。

- [ ] **Step 6: 验证、删除临时测试并提交**

Run: `./mvnw.cmd test`

Expected: BUILD SUCCESS。

```bash
git add src/main
git commit -m "feat: 增加可恢复客服任务与人工接管"
```

### Task 6: 完成公网部署安全收口

**Files:**
- Modify: `src/main/java/com/xianyusmart/util/JwtUtil.java`
- Modify: `src/main/java/com/xianyusmart/service/impl/AuthServiceImpl.java`
- Modify: `src/main/java/com/xianyusmart/interceptor/AuthInterceptor.java`
- Modify: `src/main/java/com/xianyusmart/config/WebMvcConfig.java`
- Modify: `src/main/java/com/xianyusmart/controller/LoginController.java`
- Modify: `src/main/java/com/xianyusmart/controller/DataBackupController.java`
- Modify: `src/main/java/com/xianyusmart/service/impl/ImageUploadServiceImpl.java`
- Modify: `src/main/java/com/xianyusmart/backup/handler/AccountBackupHandler.java`
- Modify: `src/main/java/com/xianyusmart/backup/handler/SystemSettingBackupHandler.java`
- Modify: `vue-code/src/utils/request.ts`
- Temporary test: `src/test/java/com/xianyusmart/security/SecurityBoundaryTest.java`

- [ ] **Step 1: 写入安全边界失败测试**

```java
@Test
void unauthorizedUsesHttp401() throws Exception {
    mockMvc.perform(get("/api/account/list"))
            .andExpect(status().isUnauthorized());
}

@Test
void internalImageUrlIsRejected() {
    assertThatThrownBy(() -> imageUploadService.download("http://127.0.0.1/a.png"))
            .hasMessageContaining("公网");
}
```

- [ ] **Step 2: 运行测试并确认旧行为失败**

Run: `./mvnw.cmd -Dtest=SecurityBoundaryTest test`

Expected: FAIL，未认证请求当前返回 HTTP 200，URL 下载未阻止内网。

- [ ] **Step 3: 强制密钥和令牌摘要**

```yaml
jwt:
  secret: ${JWT_SECRET}
  expiration: ${JWT_EXPIRATION_MS:28800000}
```

启动时校验 JWT 密钥至少 32 字节。数据库和本地缓存键只保存 `SHA-256(token)`；登录响应仍只返回一次原始 JWT。修改密码后删除该用户全部会话，退出日志只记录用户 ID。

- [ ] **Step 4: 返回标准状态并限制跨域与代理头**

```java
private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    ResultObject<?> result = ResultObject.unauthorized(null);
    result.setMsg(message);
    response.getWriter().write(gson.toJson(result));
}
```

删除控制器上的 `@CrossOrigin(origins = "*")`，只在 `WebMvcConfig` 读取 `app.security.allowed-origins`。仅当 `app.security.trust-proxy=true` 时读取代理 IP 头。

- [ ] **Step 5: 限制文件、URL、备份和日志**

URL 下载逐次校验 DNS 解析与重定向目标，拒绝回环、链路本地、私网和本机地址；限制 10 MB、连接 5 秒、读取 10 秒。日志日期严格解析 `yyyy-MM-dd` 并在规范化后确认路径仍位于日志目录。普通备份排除 Cookie、Token、AI Key 和邮箱密码。

Run: `rg -n "@CrossOrigin\(origins\s*=\s*\"\*\"\)|token=\{|console\.(log|debug)" src/main/java vue-code/src`

Expected: 无敏感日志和任意来源 CORS。

- [ ] **Step 6: 验证、删除临时测试并提交**

Run: `./mvnw.cmd test`

Expected: BUILD SUCCESS。

```bash
git add src/main vue-code/src
git commit -m "security: 收紧认证文件与公网访问边界"
```

### Task 7: 改造成商家待办驱动的简约后台

**Files:**
- Modify: `src/main/java/com/xianyusmart/controller/dto/DashboardStatsRespDTO.java`
- Modify: `src/main/java/com/xianyusmart/controller/DashboardController.java`
- Modify: `src/main/java/com/xianyusmart/mapper/XianyuGoodsOrderMapper.java`
- Modify: `vue-code/src/assets/theme.css`
- Modify: `vue-code/src/assets/main.css`
- Modify: `vue-code/src/components/layout/AppLayout.vue`
- Modify: `vue-code/src/components/layout/NavMenu.vue`
- Modify: `vue-code/src/views/dashboard/index.vue`
- Modify: `vue-code/src/views/dashboard/dashboard.css`
- Modify: `vue-code/src/views/dashboard/useDashboard.ts`
- Modify: `vue-code/src/views/login/index.vue`
- Modify: `vue-code/index.html`

- [ ] **Step 1: 以单次聚合查询返回经营与待办指标**

```sql
SELECT
  COUNT(CASE WHEN create_time >= CURRENT_DATE THEN 1 END) AS today_orders,
  COUNT(CASE WHEN create_time >= CURRENT_DATE AND delivery_status IN ('DELIVERED','CONFIRMING','COMPLETED') THEN 1 END) AS today_delivered,
  COUNT(CASE WHEN delivery_status = 'RETRY_WAIT' THEN 1 END) AS retry_count,
  COUNT(CASE WHEN delivery_status = 'REVIEW_REQUIRED' THEN 1 END) AS review_count,
  COALESCE(SUM(CASE WHEN create_time >= CURRENT_DATE THEN CAST(total_price AS DECIMAL(12,2)) ELSE 0 END), 0) AS today_revenue
FROM xianyu_goods_order;
```

趋势接口按日期分组一次查询，禁止 Java 按天循环调用 mapper。

- [ ] **Step 2: 重组首屏信息层级**

首屏固定顺序为：系统健康条、关键指标、统一待办、近七日履约趋势、库存预警。待办行直接链接订单、卡密、账号或会话详情；无异常时显示明确空状态。

- [ ] **Step 3: 统一商用视觉变量**

```css
:root {
  --color-brand: #2563eb;
  --color-success: #15803d;
  --color-warning: #b45309;
  --color-danger: #b91c1c;
  --color-text: #172033;
  --color-muted: #667085;
  --color-border: #e4e7ec;
  --color-surface: #ffffff;
  --color-page: #f7f8fa;
  --radius-sm: 6px;
  --radius-md: 8px;
  --radius-lg: 12px;
  --shadow-panel: 0 1px 2px rgba(16, 24, 40, 0.05);
}
```

删除背景渐变、毛玻璃、发光、重阴影和装饰动画；状态色只表达业务状态。

- [ ] **Step 4: 经营导向导航和品牌统一**

导航分组固定为“总览、经营、自动化、系统”，页面标题、登录页和浏览器标题统一为 XianYuSmart。保留现有路由与 API，避免无收益的页面重写。

- [ ] **Step 5: 前端类型检查和生产构建**

Run: `npm ci`

Run: `npm run type-check && npm run build:spring`

Workdir: `vue-code`

Expected: 两个命令退出码均为 0，静态资源写入 `src/main/resources/static`。

- [ ] **Step 6: 提交界面与查询优化**

```bash
git add src/main vue-code src/main/resources/static
git commit -m "feat: 增加商家待办总览与简约商用界面"
```

### Task 8: 品牌、文档、全链路验证与临时产物清理

**Files:**
- Modify: `README.md`
- Create: `docs/ARCHITECTURE.md`
- Create: `docs/OPERATIONS.md`
- Modify: `Dockerfile`
- Modify: `install.sh`
- Modify: `vue-code/README.md`
- Modify: `vue-code/DEPLOYMENT.md`
- Modify: `src/main/java/com/xianyusmart/XianYuSmartApplication.java`

- [ ] **Step 1: 统一产品名称与许可信息**

README 必须包含 XianYuSmart 定位、能力矩阵与 PolyForm 非商业许可。Java 包路径保持不变，避免无业务收益的大规模差异。

Run: `rg -n "XianYuSmart|xianyusmart" README.md vue-code/src vue-code/index.html Dockerfile install.sh pom.xml`

Expected: 对外产品位置统一使用 XianYuSmart。

- [ ] **Step 2: 整理项目逻辑和痛点映射**

`docs/ARCHITECTURE.md` 必须覆盖：消息入口、订单发现、卡密预占、交付提交、重试恢复、回复策略、人工接管、经营查询和模块依赖。`docs/OPERATIONS.md` 必须覆盖：Windows 测试、Linux 生产、HTTPS、备份策略、凭证失效、卡密待核对、缺货和版本升级。

- [ ] **Step 3: 执行后端、前端和 Compose 验证**

Run: `./mvnw.cmd test`

Expected: BUILD SUCCESS。

Run: `npm run type-check && npm run build:spring`

Workdir: `vue-code`

Expected: 退出码 0。

Run: `docker compose --env-file .env.example config --quiet`

Expected: 退出码 0。

Run: `rg -n "AUTOINCREMENT|sqlite_master|PRAGMA|RANDOM\(\)|datetime\('now'|INSERT OR IGNORE" src/main`

Expected: 无匹配。

- [ ] **Step 4: 删除本次全部临时测试和可视化产物**

确认 `src/test` 不包含本次新增测试，停止视觉陪伴服务并删除工作区 `.superpowers` 临时目录；不得删除项目已有测试。

- [ ] **Step 5: 复核正式差异并提交**

Run: `git diff --check`

Expected: 无空白错误。

Run: `git status --short`

Expected: 仅包含正式交付文件，或工作区干净。

```bash
git add README.md docs Dockerfile install.sh vue-code src/main pom.xml compose.yaml .env.example deploy
git commit -m "docs: 完成 XianYuSmart 部署与运维说明"
```
