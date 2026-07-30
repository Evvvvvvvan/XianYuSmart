# 滑块三模式与小刀订单免拼发货设计

## 1. 背景

当前连接流程检测到 `FAIL_SYS_USER_VALIDATE` 后，只能打开闲鱼页面并手工复制 Cookie。现有 Java 项目已经包含 Playwright、Cookie 更新、Token 刷新和 WebSocket 重连能力，但没有滑块任务编排、自动拖动和可视人工接管。

当前自动发货链已经具备订单任务幂等、固定内容、卡密预占与提交、失败释放、凭证和私聊双渠道能力。小刀订单缺少“待刀成”免拼处理，实时监听器也没有把“小刀成功”事件接入现有发货任务。

参考实现：

- `dameng2026/xianyu-pilot` 的 `apps/crawler/src/sliderSolver.ts`
- `dameng2026/xianyu-pilot` 的 `apps/api/app/services/ws_delivery_handler.py`
- `dameng2026/xianyu-pilot` 的 `apps/api/app/services/xianyu_api_service.py`

参考项目使用 Apache-2.0 许可证。正式实现采用 Java 重写；若直接保留实质性代码片段，需要在对应源码中保留来源与许可证说明。

## 2. 目标

1. 滑块验证弹窗提供全自动求解、人工可视拖动和 Cookie 粘贴三种入口。
2. 自动和人工 Playwright 模式完成验证后，自动回收 Cookie、刷新 Token 并恢复 WebSocket。
3. 自动模式在现有 Docker 无头环境运行；人工可视模式在具备桌面会话的本地环境运行。
4. “待刀成”消息调用闲鱼免拼接口；“小刀成功”消息进入现有自动发货链。
5. 普通订单、固定内容、卡密、凭证和私聊原有行为保持不变。
6. 不新增 Node 服务、第三方求解接口或数据库表。

## 3. 非目标

1. 不接入收费远程滑块服务。
2. 不保存滑块失败截图，除非后续明确启用诊断开关。
3. 不修改商品发货模式、卡密扣减规则、评价和售后流程。
4. 不在本次任务中部署生产环境或使用真实账号 Cookie 执行线上验收。

## 4. 方案选择

### 4.1 原生 Java Playwright

采用。复用现有 Java Playwright、账号 Cookie、Token 刷新和 WebSocket 重连能力，部署组件最少。

### 4.2 Node 爬虫旁路

不采用。虽然可直接运行上游 TypeScript，但需要新增服务、内部鉴权、容器配置和重复的 Cookie 管理。

### 4.3 远程求解接口

不采用。需要向第三方发送 Cookie，存在费用、可用性和账号凭据风险。

## 5. 总体架构

### 5.1 前端

共享的 `CaptchaGuideDialog` 展示三个操作：

- `AUTO`：全自动求解。
- `MANUAL_BROWSER`：打开本地可视浏览器，由人工拖动。
- `COOKIE`：打开现有 Cookie 粘贴弹窗。

`AUTO` 和 `MANUAL_BROWSER` 启动后台任务并轮询状态。`COOKIE` 不创建滑块任务，继续调用现有 `/websocket/updateCookie`。

任务状态统一为：

- `PENDING`
- `RUNNING`
- `SUCCEEDED`
- `FAILED`
- `TIMEOUT`
- `UNSUPPORTED`

执行中禁用重复启动。失败后保留三种入口，可切换其他模式。

### 5.2 后端

新增独立的滑块任务服务，职责包括：

1. 校验账号归属和当前租户。
2. 从 `WebSocketTokenService` 的待验证状态读取验证地址。
3. 校验验证地址属于闲鱼或淘宝相关允许域名。
4. 保证同账号只有一个任务，全局最多同时运行两个浏览器任务。
5. 在同一异步线程内创建、操作并关闭 Playwright，避免跨线程使用 Playwright 对象。
6. 保存任务状态，供前端轮询。
7. 成功后保存 Cookie、清理验证等待状态并重建 WebSocket。
8. 超时、异常或页面关闭时释放 Browser、BrowserContext 和任务锁。

现有 `PlaywrightManager` 的无头浏览器继续服务 Cookie 刷新。滑块任务使用独立短生命周期 Playwright 实例，避免自动和人工模式改变共享浏览器的固定无头行为。

## 6. 滑块处理

### 6.1 自动模式

自动模式默认无头，单次任务最多尝试 5 次，总超时 5 分钟。

处理顺序：

1. 创建浏览器和上下文。
2. 注入当前账号 Cookie。
3. 在页面加载前注入自动模式指纹脚本。
4. 打开服务端保存的验证地址；没有地址时打开闲鱼 IM 页面。
5. 在主页面和所有嵌套 iframe 中检测 Baxia 组件。
6. 按已知选择器查找滑块按钮，失败时使用尺寸、可见性和可拖动样式做启发式查找。
7. 计算可拖动距离：轨道宽度减按钮宽度，并限制在合理范围。
8. 使用 Playwright `Mouse` 生成按下、加速、减速、Y 轴偏移、短暂停顿、轻微回退、过冲和回调轨迹。
9. 检查成功标识或滑块组件消失。
10. 导出闲鱼和淘宝相关域 Cookie。
11. 保存 Cookie 并刷新 Token；只有 Token 刷新成功才标记任务成功。

自动模式指纹脚本仅作用于该临时浏览器上下文，覆盖上游当前使用的检测点：

- `navigator.webdriver`
- `window.chrome`
- `navigator.plugins`
- `navigator.languages`
- `navigator.permissions`
- WebGL vendor 和 renderer
- `hardwareConcurrency`
- `deviceMemory`
- CDP 注入痕迹

指纹脚本不写入全局静态资源，也不影响人工模式和现有共享浏览器。

### 6.2 人工可视模式

人工模式使用有头浏览器，不执行自动拖动。处理顺序：

1. 检查当前进程是否具备可视桌面环境。
2. 创建有头浏览器并注入当前账号 Cookie。
3. 打开验证地址或闲鱼 IM 页面。
4. 等待滑块组件消失。
5. 导出 Cookie、刷新 Token 并恢复 WebSocket。

Windows 本地交互式启动支持该模式。Docker 无 `DISPLAY` 或 Windows 非交互式服务环境返回 `UNSUPPORTED`，不降级成无头人工模式。

### 6.3 Cookie 粘贴模式

保持现有流程：

1. 填写 Cookie。
2. 从 Cookie 提取 `unb`。
3. 调用 `AccountService.updateAccountCookie`。
4. 调用 `WebSocketService.restartAfterCredentialUpdate`。

## 7. 滑块接口

在现有 WebSocket 控制器下增加：

### 7.1 启动任务

`POST /websocket/captcha/solve`

请求：

```json
{
  "xianyuAccountId": 1,
  "mode": "AUTO"
}
```

`mode` 只接受 `AUTO` 和 `MANUAL_BROWSER`。验证地址不能由前端提交。

### 7.2 查询状态

`POST /websocket/captcha/status`

请求：

```json
{
  "xianyuAccountId": 1
}
```

响应包含模式、状态、尝试次数和可公开错误信息，不返回 Cookie、Token、验证地址或浏览器指纹。

## 8. 小刀订单免拼发货

### 8.1 消息识别

现有 `SyncMessageHandler` 继续解析消息、卡片、订单号、商品 ID、买家 ID 和会话 ID。

自动发货监听器增加两个判断：

- 待刀成：提醒文案同时包含“小刀”和“待刀成”。
- 小刀成功：提醒文案包含“小刀成功”或“我已成功小刀”。

识别不再限定只有现有两条普通付款文案，并兼容当前已经解析的 `contentType=26` 和 `contentType=32` 卡片。

### 8.2 待刀成

处理顺序：

1. 提取订单号、商品 ID、买家 ID 和消息 ID。
2. 校验字段完整且商品属于当前账号。
3. 校验商品已经配置并开启自动发货。
4. 调用 `OrderService.freeShippingBargain`。
5. 不创建正式发货任务，不预占卡密，不发送交付内容。

免拼接口：

- API：`mtop.idle.groupon.activity.seller.freeshipping`
- `bizOrderId`：字符串
- `itemId`：数值
- `buyerId`：数值

调用继续复用 `XianyuApiCallUtils` 的签名、Cookie 合并和 Token 刷新能力。

### 8.3 小刀成功

收到小刀成功消息后，使用现有 `DeliveryTaskService.discover` 创建订单任务。后续统一进入：

`DeliveryTaskService → DeliveryTaskScheduler → AutoDeliveryService.executeDelivery`

固定内容、卡密、发货凭证和买家私聊继续复用原逻辑。

## 9. 幂等与失败处理

### 9.1 滑块

- 同账号已有运行任务时返回现有任务状态。
- 自动模式最多 5 次，不后台无限重试。
- 浏览器启动失败、页面关闭、超时和未检测到滑块均返回明确状态。
- 只有滑块消失并且 Token 刷新成功才返回 `SUCCEEDED`。
- 自动失败后不修改 Cookie 有效状态，不伪造连接成功。

### 9.2 小刀

- 字段缺失时不调用免拼接口。
- 进程内使用账号 ID、消息 ID 和阶段组成的短期去重键，避免 WebSocket 重复推送。
- Token 过期只由现有 API 工具执行一次安全刷新重试。
- `ORDER_ALREADY_DELIVERY` 或平台明确返回“已发货成功”视为幂等成功。
- 免拼失败不预占卡密、不创建发货成功状态。
- 后续收到“小刀成功”事件时，以平台事件为准，仍可进入正式发货任务。
- WebSocket 与 HTTP 补偿同时发现订单时，继续依赖现有订单唯一约束和任务发现逻辑。
- 发货结果不确定时继续进入现有人工复核流程，不重复提交发货凭证。

## 10. 安全与资源管理

1. Cookie、Token、完整验证地址和指纹详情不写日志。
2. 失败截图默认关闭。
3. 验证地址使用允许域名列表，禁止任意地址访问。
4. 所有账号读取、Cookie 保存和任务执行都保持租户上下文。
5. 浏览器任务结束后必须关闭页面、上下文和浏览器。
6. 页面异常不能阻塞全局浏览器并发许可。
7. 前端状态接口只返回展示需要的信息。

## 11. 测试设计

### 11.1 Java

使用临时测试类和本地测试页面验证：

- 主页面和嵌套 iframe 滑块检测。
- 滑块按钮选择器和启发式查找。
- 轨道距离计算和距离限制。
- 成功、失败、超时、关闭和浏览器不可用状态。
- 同账号去重和全局并发限制。
- 验证域名校验。
- Cookie 导出、保存和日志脱敏。
- 待刀成、小刀成功和普通付款消息分流。
- 免拼参数、商品归属、自动发货开关和幂等成功。
- 免拼失败不预占卡密。
- 小刀成功只创建一个现有发货任务。

临时测试类、HTML、测试 Cookie 和测试数据在验证后删除，再运行项目正式测试。

### 11.2 前端

- 三种模式显示正确。
- 任务执行中禁止重复点击。
- 状态轮询正确结束。
- 自动失败后允许切换模式。
- 不支持人工可视模式时显示明确提示。
- Cookie 粘贴继续使用现有弹窗和重连流程。

执行 TypeScript 检查和正式构建。

### 11.3 不执行的测试

- 不使用真实账号 Cookie 运行线上滑块。
- 不调用真实小刀订单免拼接口。
- 不部署生产环境。

## 12. 验收标准

1. 滑块弹窗可选择全自动、人工可视拖动或 Cookie 粘贴。
2. 自动模式在本地测试页面完成滑块轨迹并正确判定结果。
3. 人工模式在支持的 Windows 交互环境打开可视浏览器并回收 Cookie。
4. Docker 无桌面环境时自动模式可启动，人工模式返回不支持。
5. 滑块成功后 Cookie 更新、Token 刷新和 WebSocket 重连形成闭环。
6. “待刀成”只调用免拼接口，不扣卡密。
7. “小刀成功”进入现有发货链，普通订单行为不变。
8. 重复消息和双发现入口不造成重复发货或重复扣卡密。
9. Java 21 测试、前端类型检查和正式构建通过。
10. 工作区不保留本次临时测试产物。
