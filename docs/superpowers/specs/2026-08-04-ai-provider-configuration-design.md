# 全局 AI 多协议配置设计

## 目标

将现有仅适配 OpenAI 兼容接口、固定阿里云默认地址的全局 AI 配置，调整为官方服务商预设与第三方中转站并存的配置方式，同时解决 Base URL 重复拼接、模型更新不生效、Embedding 误复用聊天地址和连接状态不可见的问题。

## 现状与根因

当前 `DynamicAIChatClientManager` 直接把 `ai_base_url` 传给 Spring AI。Spring AI 1.1.4 的 `OpenAiApi` 默认继续追加 `/v1/chat/completions`，因此填写以 `/v1` 结尾的地址时会形成 `/v1/v1/chat/completions`。

聊天、Embedding 和商品图当前共用部分配置。聊天服务不支持 Embedding 时，RAG 检索仍会向聊天 Base URL 请求 `/v1/embeddings`，造成回复前出现 404。商品图模型同时作为聊天配置的必填项，导致未使用生图功能时仍必须填写无关模型。

现有日志还显示模型保存前仍使用旧的 `deepseek-v3`，而第三方普通请求成功、流式请求无内容时页面缺少明确反馈，难以区分地址、模型、鉴权和流式兼容问题。

## 设计原则

- 只适配市场常用的 OpenAI Chat Completions 和 Anthropic Messages 两种协议。
- 官方服务商使用预设，默认只需填写 API Key。
- 第三方中转站允许填写名称、协议、Base URL 和模型。
- 地址解析集中在一个轻量类中，不引入 LiteLLM、NewAPI 等额外网关服务。
- 聊天、Embedding 和商品图配置相互独立，未启用的能力不发请求。
- 保留现有 `ai_api_key`、`ai_base_url`、`ai_model`，避免数据库迁移和升级断档。

## 提供商预设

配置页提供以下选择：

| 提供商 | 默认协议 | 默认 Base URL | 推荐模型 |
| --- | --- | --- | --- |
| OpenAI | OpenAI | `https://api.openai.com` | `gpt-5.6-terra` |
| Anthropic Claude | Anthropic | `https://api.anthropic.com` | `claude-sonnet-5` |
| DeepSeek | OpenAI | `https://api.deepseek.com` | `deepseek-v4-flash` |
| 阿里云百炼 / Qwen | OpenAI | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen3.7-plus` |
| Google Gemini | OpenAI | `https://generativelanguage.googleapis.com/v1beta/openai` | `gemini-3.6-flash` |
| 第三方中转站 | OpenAI | 空 | 空 |

DeepSeek 和阿里云百炼允许切换为 Anthropic 协议。切换后分别使用 `https://api.deepseek.com/anthropic` 和 `https://dashscope.aliyuncs.com/apps/anthropic`。OpenAI、Anthropic、Gemini 使用服务商支持的固定协议。

推荐模型在选择提供商时自动填入，高级配置中仍可覆盖，避免模型迭代受前端版本限制。

## 配置模型

### 聊天配置

继续使用现有键，并新增三个轻量标识：

```text
ai_provider
ai_protocol
ai_custom_name
ai_api_key
ai_base_url
ai_model
```

旧数据缺少 `ai_provider` 和 `ai_protocol` 时，存在旧 Base URL、模型或 Key 则按“第三方中转站 + OpenAI 协议”读取；全新配置默认显示 DeepSeek 官方预设。

### Embedding 高级配置

```text
ai_embedding_enabled
ai_embedding_api_key
ai_embedding_base_url
ai_embedding_model
```

默认关闭，不再回退到聊天 Key 和 Base URL。旧数据已经存在独立 Embedding API Key 时视为启用，保证已有独立配置继续工作。Embedding 只使用 OpenAI-compatible `/embeddings` 协议。

### 商品图高级配置

```text
ai_image_enabled
ai_image_api_key
ai_image_base_url
ai_image_model
```

默认关闭。启用后使用独立鉴权、地址和模型，通过 OpenAI-compatible `/images/generations` 调用。聊天配置不再校验商品图模型。

## 地址解析

新增 `AIEndpointResolver`，输入配置地址和能力类型，输出 SDK 使用的 origin、请求 path 和用于界面展示的完整地址。

### OpenAI Chat Completions

| 输入 | 完整请求地址 |
| --- | --- |
| `https://host` | `https://host/v1/chat/completions` |
| `https://host/v1` | `https://host/v1/chat/completions` |
| `https://host/v1/chat/completions` | 原样使用 |
| `https://host/compatible-mode/v1` | `https://host/compatible-mode/v1/chat/completions` |
| `https://host/v1beta/openai` | `https://host/v1beta/openai/chat/completions` |

### Anthropic Messages

| 输入 | 完整请求地址 |
| --- | --- |
| `https://host` | `https://host/v1/messages` |
| `https://host/v1` | `https://host/v1/messages` |
| `https://host/v1/messages` | 原样使用 |
| `https://host/apps/anthropic` | `https://host/apps/anthropic/v1/messages` |

Embedding 和商品图沿用相同规则，目标路径分别为 `/embeddings` 和 `/images/generations`。解析器只接受 `http`、`https`，拒绝包含用户名或密码的 URL。

## 动态客户端

`DynamicAIChatClientManager` 继续向业务层提供统一 `ChatClient`：

- OpenAI 协议创建 `OpenAiChatModel`，显式设置解析后的 `completionsPath`。
- Anthropic 协议创建 `AnthropicChatModel`，显式设置解析后的 `completionsPath`。
- OpenAI 默认参数不再强制发送 `temperature`，兼容会拒绝该参数的新模型。
- Anthropic 设置必要的 `maxTokens`，协议头由 Spring AI 负责。
- 缓存签名包含协议、Base URL、模型和 API Key，任一配置变化都会重建当前租户客户端。
- 日志不再输出任何 API Key 片段。

`AIServiceImpl` 继续复用 `ChatClient`，不拆分两套业务调用链。

## 连通性测试

设置模块新增 `/api/setting/ai/test`。请求使用当前表单内容，不依赖已保存配置，测试过程不修改数据库。

请求字段：

```text
provider
protocol
customName
apiKey
baseUrl
model
message
```

后端使用与正式聊天完全相同的地址解析器和客户端构建逻辑发送一次普通请求，使用 `System.nanoTime()` 记录总延迟。普通请求与现有自动回复主链路一致，也能直接验证第三方 curl 成功场景。

响应字段：

```text
success
reply
latencyMs
provider
protocol
model
endpoint
message
```

错误信息移除 API Key 并限制长度。界面展示测试中、连接成功、连接失败三种状态，并展示回复、延迟、协议、模型和最终请求地址。

## 配置页交互

AI 服务配置页按以下顺序展示：

1. 服务状态摘要。
2. 官方提供商与第三方中转站卡片。
3. API Key。
4. 可折叠的聊天高级配置：协议、Base URL、模型和最终地址预览。
5. 保存和恢复预设按钮。
6. 独立的连通性测试面板。
7. 默认折叠的 Embedding 高级配置。
8. 默认折叠的 AI 商品图高级配置。

提供商图标使用当前 Vue/CSS/SVG 体系绘制，不加载外部图片，不新增前端依赖。第三方中转站卡片显示自定义名称；未填写时显示“第三方中转站”。

## 配置热更新与备份

聊天相关键变更时清理 ChatClient 缓存。Embedding 相关键变更时清理 VectorStore 缓存，立即生效。商品图每次调用读取独立配置，无需缓存重建。

系统备份增加提供商、协议、非密钥地址、模型和启用状态；API Key 继续不进入备份文件。

## 异常处理

- 未配置聊天 Key：AI 服务状态显示不可用。
- Base URL 非法：保存前前端提示，调用时后端返回明确错误。
- 401/403：显示鉴权或权限错误原文的脱敏摘要。
- 404：同时展示最终请求地址，便于判断路径是否错误。
- 429：显示服务限流提示。
- Embedding 未启用：普通回复跳过 RAG；知识库写入返回需要启用 Embedding 的提示。
- 商品图未启用：生图入口返回需要启用商品图高级配置的提示。

## 验收标准

- 根地址、`/v1` 地址和完整 endpoint 均不会形成重复 `/v1/v1`。
- 第三方 OpenAI curl 示例对应的根地址和 `/v1` 地址均能生成同一最终 endpoint。
- `deepseek-v4-flash` 保存后立即用于下一次调用。
- OpenAI 与 Anthropic 两种协议都能通过本地协议模拟测试。
- Embedding 默认关闭且不再向聊天服务发送 `/embeddings`。
- 商品图关闭时聊天配置无需商品图模型。
- 连通性测试可自定义文本并显示回复、延迟和最终地址。
- Java 构建、前端类型检查和生产构建通过。

## 参考

- OpenAI API 模型与接口文档：<https://developers.openai.com/api/docs/models>
- Anthropic 模型与 Messages 文档：<https://platform.claude.com/docs/en/about-claude/models/overview>
- DeepSeek OpenAI/Anthropic 兼容文档：<https://api-docs.deepseek.com/updates/>、<https://api-docs.deepseek.com/guides/anthropic_api>
- 阿里云百炼 Base URL 总览：<https://help.aliyun.com/zh/model-studio/base-url>
- Gemini OpenAI 兼容文档：<https://ai.google.dev/gemini-api/docs/openai>
- Open WebUI：<https://github.com/open-webui/open-webui>
- LibreChat 自定义端点示例：<https://github.com/danny-avila/LibreChat/blob/main/librechat.example.yaml>
- LiteLLM 协议网关：<https://github.com/BerriAI/litellm>
