# 全局 AI 多协议配置 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 支持 OpenAI/Anthropic 两种协议、官方与第三方 Base URL、独立 Embedding/商品图配置，并在设置页提供带回复和延迟的连通性测试。

**Architecture:** 新增一个无状态地址解析器，将根地址、版本地址和完整 endpoint 统一解析为 Spring AI 所需的 origin/path。现有动态聊天管理器按协议创建 OpenAI 或 Anthropic `ChatClient`，设置控制器复用同一构建逻辑执行临时连接测试。Embedding 和商品图沿用独立键值配置，关闭时完全跳过对应请求。

**Tech Stack:** Java 21、Spring Boot 3.5.7、Spring AI 1.1.4、Vue 3、TypeScript、原生 SVG/CSS。

---

## 文件边界

- 创建 `src/main/java/com/xianyusmart/config/rag/AIEndpointResolver.java`：只负责 URL 校验、能力路径归一化和 origin/path 拆分。
- 修改 `pom.xml`：增加 Spring AI Anthropic 模型依赖。
- 修改 `src/main/java/com/xianyusmart/config/rag/DynamicAIChatClientManager.java`：读取协议配置、创建两类模型、提供临时连接测试。
- 修改 `src/main/java/com/xianyusmart/config/rag/DynamicVectorStoreManager.java`：Embedding 显式启用、独立配置、统一 endpoint。
- 修改 `src/main/java/com/xianyusmart/service/OpportunityImageService.java`：商品图显式启用、独立配置、统一 endpoint。
- 修改 `src/main/java/com/xianyusmart/service/impl/SysSettingServiceImpl.java`：聊天和 Embedding 配置分别热更新。
- 修改 `src/main/java/com/xianyusmart/controller/SysSettingController.java`：增加 `/api/setting/ai/test`。
- 修改 `src/main/java/com/xianyusmart/controller/AIChatController.java`：状态响应增加提供商、协议和最终 endpoint。
- 修改 `src/main/java/com/xianyusmart/service/impl/AIServiceImpl.java`：Embedding 未启用时返回明确知识库提示。
- 修改 `src/main/java/com/xianyusmart/backup/handler/SystemSettingBackupHandler.java`：备份新增非密钥配置。
- 修改 `vue-code/src/api/ai.ts`：增加连接测试请求和响应类型。
- 修改 `vue-code/src/views/settings/index.vue`：实现提供商卡片、高级配置、连通性测试、Embedding 与商品图独立开关。
- 临时创建并删除 `src/test/java/com/xianyusmart/config/rag/AIEndpointResolverTest.java` 与 `DynamicAIChatClientManagerTest.java`：执行 RED/GREEN，结束前清理。

### Task 1: 地址解析器

**Files:**
- Create: `src/main/java/com/xianyusmart/config/rag/AIEndpointResolver.java`
- Test temporarily: `src/test/java/com/xianyusmart/config/rag/AIEndpointResolverTest.java`

- [ ] **Step 1: 写地址解析失败测试**

覆盖根地址、`/v1`、完整 endpoint、阿里云 `/compatible-mode/v1`、Gemini `/v1beta/openai`、Anthropic `/apps/anthropic`、Embedding 和图片路径：

```java
assertEquals("https://host.example/v1/chat/completions",
        AIEndpointResolver.resolve("https://host.example", AIEndpointResolver.Capability.OPENAI_CHAT).endpoint());
assertEquals("https://host.example/v1/chat/completions",
        AIEndpointResolver.resolve("https://host.example/v1", AIEndpointResolver.Capability.OPENAI_CHAT).endpoint());
assertEquals("https://host.example/v1beta/openai/chat/completions",
        AIEndpointResolver.resolve("https://host.example/v1beta/openai", AIEndpointResolver.Capability.OPENAI_CHAT).endpoint());
assertEquals("https://host.example/apps/anthropic/v1/messages",
        AIEndpointResolver.resolve("https://host.example/apps/anthropic", AIEndpointResolver.Capability.ANTHROPIC_CHAT).endpoint());
```

- [ ] **Step 2: 运行 RED**

Run: `powershell -ExecutionPolicy Bypass -File .agents/skills/operating-xianyusmart/scripts/project.ps1 -Task test -Test AIEndpointResolverTest`

Expected: FAIL，`AIEndpointResolver` 不存在。

- [ ] **Step 3: 实现最小解析器**

实现：

```java
public final class AIEndpointResolver {
    public enum Capability { OPENAI_CHAT, ANTHROPIC_CHAT, EMBEDDING, IMAGE }
    public record Endpoint(String baseUrl, String path, String endpoint) {}

    public static Endpoint resolve(String configuredBaseUrl, Capability capability) {
        URI uri = URI.create(configuredBaseUrl.trim());
        String path = normalizePath(uri.getPath(), capability);
        String baseUrl = new URI(uri.getScheme(), uri.getAuthority(), null, null, null).toString();
        return new Endpoint(baseUrl, path, baseUrl + path);
    }
}
```

只允许 `http`/`https`，拒绝 user-info，移除末尾 `/`，完整能力路径保持不变。

- [ ] **Step 4: 运行 GREEN**

Run: `powershell -ExecutionPolicy Bypass -File .agents/skills/operating-xianyusmart/scripts/project.ps1 -Task test -Test AIEndpointResolverTest`

Expected: PASS。

### Task 2: 双协议动态客户端与连接测试

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/java/com/xianyusmart/config/rag/DynamicAIChatClientManager.java`
- Test temporarily: `src/test/java/com/xianyusmart/config/rag/DynamicAIChatClientManagerTest.java`

- [ ] **Step 1: 写真实本地 HTTP 协议测试**

使用 JDK `HttpServer` 提供 OpenAI 和 Anthropic 响应，断言请求路径、鉴权头、模型、回复和延迟：

```java
DynamicAIChatClientManager.ConnectionTestResult result = manager.testConnection(
        new DynamicAIChatClientManager.ChatConfig("custom", "openai", "中转站", "test-key",
                serverUrl + "/v1", "test-model"),
        "请回复连接成功");

assertTrue(result.isSuccess());
assertEquals("连接成功", result.getReply());
assertEquals(serverUrl + "/v1/chat/completions", result.getEndpoint());
assertTrue(result.getLatencyMs() >= 0);
```

Anthropic 用例断言 `/v1/messages` 和 `x-api-key`。

- [ ] **Step 2: 运行 RED**

Run: `powershell -ExecutionPolicy Bypass -File .agents/skills/operating-xianyusmart/scripts/project.ps1 -Task test -Test DynamicAIChatClientManagerTest`

Expected: FAIL，双协议配置和连接测试 API 不存在。

- [ ] **Step 3: 增加 Anthropic 依赖**

在 OpenAI starter 后增加：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-anthropic</artifactId>
</dependency>
```

- [ ] **Step 4: 重构动态客户端**

增加配置记录：

```java
public record ChatConfig(String provider, String protocol, String customName,
                         String apiKey, String baseUrl, String model) {}
```

OpenAI 构建显式使用：

```java
OpenAiApi.builder()
        .apiKey(new SimpleApiKey(config.apiKey().trim()))
        .baseUrl(endpoint.baseUrl())
        .completionsPath(endpoint.path())
        .build();
```

Anthropic 构建显式使用：

```java
AnthropicApi.builder()
        .apiKey(new SimpleApiKey(config.apiKey().trim()))
        .baseUrl(endpoint.baseUrl())
        .completionsPath(endpoint.path())
        .build();
```

删除 OpenAI 默认 `temperature(0.7)`，Anthropic 设置 `maxTokens(2048)`。连接测试调用同一 `buildChatClient`，返回脱敏错误、完整 endpoint 和 `System.nanoTime()` 计算的毫秒延迟。

- [ ] **Step 5: 运行 GREEN**

Run: `powershell -ExecutionPolicy Bypass -File .agents/skills/operating-xianyusmart/scripts/project.ps1 -Task test -Test DynamicAIChatClientManagerTest`

Expected: PASS，两个本地协议用例均通过。

### Task 3: 独立 Embedding 与商品图

**Files:**
- Modify: `src/main/java/com/xianyusmart/config/rag/DynamicVectorStoreManager.java`
- Modify: `src/main/java/com/xianyusmart/service/OpportunityImageService.java`
- Modify: `src/main/java/com/xianyusmart/service/impl/AIServiceImpl.java`
- Extend temporary test: `src/test/java/com/xianyusmart/config/rag/AIEndpointResolverTest.java`

- [ ] **Step 1: 增加能力路径断言并运行 RED**

```java
assertEquals("https://host.example/v1/embeddings",
        AIEndpointResolver.resolve("https://host.example/v1", AIEndpointResolver.Capability.EMBEDDING).endpoint());
assertEquals("https://host.example/v1/images/generations",
        AIEndpointResolver.resolve("https://host.example/v1", AIEndpointResolver.Capability.IMAGE).endpoint());
```

Run: `powershell -ExecutionPolicy Bypass -File .agents/skills/operating-xianyusmart/scripts/project.ps1 -Task test -Test AIEndpointResolverTest`

Expected: 新断言在实现能力路径前失败。

- [ ] **Step 2: 实现独立配置**

`DynamicVectorStoreManager` 使用 `ai_embedding_enabled`，仅在显式为真或已有独立 Embedding Key 时创建模型；不再读取聊天 Key/Base URL。`OpenAiApi` 显式设置 `embeddingsPath(endpoint.path())`。

`OpportunityImageService` 使用 `ai_image_enabled`、`ai_image_api_key`、`ai_image_base_url`、`ai_image_model`。关闭时抛出：

```java
throw new IllegalStateException("AI商品图未启用，请先在系统设置的高级配置中启用");
```

`AIServiceImpl` 在向量库为空时将知识库操作提示改为：

```text
Embedding 未启用或配置不完整，请先在系统设置的高级配置中完成配置
```

- [ ] **Step 3: 运行 GREEN 和编译**

Run: `powershell -ExecutionPolicy Bypass -File .agents/skills/operating-xianyusmart/scripts/project.ps1 -Task test -Test AIEndpointResolverTest`

Expected: PASS。

Run: `powershell -ExecutionPolicy Bypass -File .agents/skills/operating-xianyusmart/scripts/project.ps1 -Task build`

Expected: BUILD SUCCESS。

### Task 4: 热更新、状态和设置测试接口

**Files:**
- Modify: `src/main/java/com/xianyusmart/service/impl/SysSettingServiceImpl.java`
- Modify: `src/main/java/com/xianyusmart/controller/SysSettingController.java`
- Modify: `src/main/java/com/xianyusmart/controller/AIChatController.java`
- Modify: `src/main/java/com/xianyusmart/backup/handler/SystemSettingBackupHandler.java`

- [ ] **Step 1: 扩展热更新键**

聊天键：

```java
Set.of("ai_provider", "ai_protocol", "ai_custom_name", "ai_api_key", "ai_base_url", "ai_model")
```

Embedding 键：

```java
Set.of("ai_embedding_enabled", "ai_embedding_api_key", "ai_embedding_base_url", "ai_embedding_model")
```

聊天键调用 `dynamicAIChatClientManager.forceRebuild()`，Embedding 键调用 `dynamicVectorStoreManager.forceRebuild()`。

- [ ] **Step 2: 增加测试接口**

`SysSettingController` 增加：

```java
@PostMapping("/ai/test")
public ResultObject<DynamicAIChatClientManager.ConnectionTestResult> testAIConnection(
        @RequestBody AIConnectionTestReqDTO reqDTO) {
    return ResultObject.success(dynamicAIChatClientManager.testConnection(reqDTO.toConfig(), reqDTO.getMessage()));
}
```

DTO 校验 Key、Base URL、模型和测试内容不能为空。

- [ ] **Step 3: 扩展状态和备份**

AI 状态响应增加 `provider`、`protocol`、`endpoint`。备份集合增加所有非密钥的聊天、Embedding、图片配置键，继续排除三个 API Key。

- [ ] **Step 4: 编译验证**

Run: `powershell -ExecutionPolicy Bypass -File .agents/skills/operating-xianyusmart/scripts/project.ps1 -Task build`

Expected: BUILD SUCCESS。

### Task 5: 配置页和可视化连接测试

**Files:**
- Modify: `vue-code/src/api/ai.ts`
- Modify: `vue-code/src/views/settings/index.vue`

- [ ] **Step 1: 定义前端协议类型**

在 `ai.ts` 增加：

```ts
export interface AIConnectionTestRequest {
  provider: string
  protocol: 'openai' | 'anthropic'
  customName: string
  apiKey: string
  baseUrl: string
  model: string
  message: string
}

export interface AIConnectionTestResult {
  success: boolean
  reply: string
  latencyMs: number
  provider: string
  protocol: string
  model: string
  endpoint: string
  message: string
}
```

使用现有 `authHeaders()` POST `/api/setting/ai/test`。

- [ ] **Step 2: 实现提供商预设和保存逻辑**

在设置页增加 `AI_PROVIDERS`，选择提供商时填入协议、地址和模型。旧配置缺少提供商时，有旧值则映射到 `custom`，无旧值则使用 `deepseek`。

保存以下六个聊天键：

```text
ai_provider
ai_protocol
ai_custom_name
ai_api_key
ai_base_url
ai_model
```

- [ ] **Step 3: 实现 UI**

使用现有 Vue/CSS，增加：

- 提供商卡片网格与内联 SVG 图标。
- 第三方中转站名称输入。
- OpenAI/Anthropic 协议分段选择。
- 高级配置折叠区与最终 endpoint 预览。
- 自定义测试文本、测试按钮、成功/失败状态、回复、延迟和 endpoint。
- Embedding 启用开关及独立 Key/Base URL/模型。
- 商品图启用开关及独立 Key/Base URL/模型。

关闭 Embedding 或商品图时不校验对应字段。

- [ ] **Step 4: 前端类型与生产构建验证**

Run: `powershell -ExecutionPolicy Bypass -File .agents/skills/operating-xianyusmart/scripts/project.ps1 -Task frontend`

Expected: type-check 与 Vite build 全部通过。

### Task 6: 清理、回归与交付

**Files:**
- Delete temporary: `src/test/java/com/xianyusmart/config/rag/AIEndpointResolverTest.java`
- Delete temporary: `src/test/java/com/xianyusmart/config/rag/DynamicAIChatClientManagerTest.java`

- [ ] **Step 1: 删除临时测试产物**

使用 `apply_patch` 删除本次新增的两个临时测试类，不删除项目原有文件。

- [ ] **Step 2: 清理后重新验证**

Run: `powershell -ExecutionPolicy Bypass -File .agents/skills/operating-xianyusmart/scripts/project.ps1 -Task check`

Expected: Java 21、Maven 和 origin 校验通过。

Run: `powershell -ExecutionPolicy Bypass -File .agents/skills/operating-xianyusmart/scripts/project.ps1 -Task build`

Expected: BUILD SUCCESS。

Run: `powershell -ExecutionPolicy Bypass -File .agents/skills/operating-xianyusmart/scripts/project.ps1 -Task frontend`

Expected: 前端类型检查和生产构建通过。

- [ ] **Step 3: 检查差异范围**

Run: `git status --short`

Expected: 保留既有 `README.md`、`DISCLAIMER.md` 改动，新增差异仅包含本计划列出的 AI 文件和两份文档，不包含临时测试。

Run: `git diff --check`

Expected: 无空白错误。

- [ ] **Step 4: 审查、提交和推送**

只暂存本计划文件，保留既有脏文件不暂存。提交信息：

```text
feat: 完善全局AI多协议配置
```

推送当前分支到已核准的 `origin/main`。
