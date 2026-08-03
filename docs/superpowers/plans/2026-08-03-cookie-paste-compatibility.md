# Cookie Paste Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 恢复粘贴 Cookie 的原引导闭环，并兼容从 `havana_lgc2_*` 的 `hid` 识别账号。

**Architecture:** 在 `XianyuSignUtils` 集中提取并规范化账号标识，调用入口只复用该工具。前端在现有滑块弹窗内增加 Cookie 引导子状态，继续复用现有手动粘贴弹窗。

**Tech Stack:** Java 21、Spring Boot、Gson、Vue 3、TypeScript、Vite

---

### Task 1: Cookie 账号标识解析与规范化

**Files:**
- Modify: `src/main/java/com/xianyusmart/utils/XianyuSignUtils.java`
- Temporary Test: `src/test/java/com/xianyusmart/utils/XianyuSignUtilsCookieUserIdTest.java`

- [ ] **Step 1: 编写失败测试**

测试覆盖：原 `unb`、`havana_lgc2_*` 的数字 `hid`、无效 Base64、非数字 `hid`、补齐 `unb` 且不重复添加。测试载荷使用脱敏 JSON 动态 Base64 编码，不使用真实 Cookie。

- [ ] **Step 2: 验证测试按预期失败**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task test -Test XianyuSignUtilsCookieUserIdTest`

Expected: FAIL，缺少 `extractUserId` 或 `normalizeCookieUserId`。

- [ ] **Step 3: 实现最小公共方法**

在 `XianyuSignUtils` 中：

```java
public static String extractUserId(String cookieText)
public static String normalizeCookieUserId(String cookieText, String userId)
```

`extractUserId` 优先返回数字 `unb`；否则解码 `havana_lgc2_*`，通过 Gson 读取数字 `hid`。`normalizeCookieUserId` 仅在缺少 `unb` 时追加 `; unb=<hid>`。

- [ ] **Step 4: 验证测试通过**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task test -Test XianyuSignUtilsCookieUserIdTest`

Expected: PASS。

### Task 2: 接入所有凭证保存入口

**Files:**
- Modify: `src/main/java/com/xianyusmart/controller/WebSocketController.java`
- Modify: `src/main/java/com/xianyusmart/controller/AccountController.java`
- Modify: `src/main/java/com/xianyusmart/service/impl/CaptchaSolveServiceImpl.java`

- [ ] **Step 1: 替换重复提取逻辑**

三个入口统一调用：

```java
String unb = XianyuSignUtils.extractUserId(cookieText);
String normalizedCookie = XianyuSignUtils.normalizeCookieUserId(cookieText, unb);
```

无法识别时在任何保存或重连前返回明确错误；保存时传入 `normalizedCookie`。删除两个 Controller 内重复的私有 `extractUnbFromCookie`。

- [ ] **Step 2: 运行后端验证**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task test`

Expected: BUILD SUCCESS，Java 21。

### Task 3: 恢复 Cookie 获取引导

**Files:**
- Modify: `vue-code/src/views/connection/components/CaptchaGuideDialog.vue`
- Modify: `vue-code/src/views/connection/components/ManualUpdateCookieModal.vue`

- [ ] **Step 1: 增加现有弹窗内的引导子状态**

选择 `COOKIE` 后点击操作按钮进入引导，不关闭弹窗。引导显示原四步内容，并提供：

```ts
const openGoofishIm = () => window.open(
  'https://www.goofish.com/im', '_blank', 'noopener,noreferrer'
)
const continueCookiePaste = () => {
  emit('cookie')
  handleClose()
}
```

自动拖动、人工拖动、轮询和取消逻辑保持不变。

- [ ] **Step 2: 更新手动粘贴提示**

重要字段显示为 `unb 或 havana_lgc2_*`，移除“必须包含 unb”的误导示例要求，保留其他字段和安全提示。

- [ ] **Step 3: 运行前端正式验证**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task frontend`

Expected: 类型检查和 Vite 正式构建成功。

### Task 4: 清理、回归与交付

**Files:**
- Delete: `src/test/java/com/xianyusmart/utils/XianyuSignUtilsCookieUserIdTest.java`

- [ ] **Step 1: 删除临时测试并重新验证**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task test`

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task frontend`

Expected: 两项均成功，正式代码不依赖临时测试。

- [ ] **Step 2: 审核并提交**

确认只包含目标 Java、Vue、生成的正式前端资源和计划文档；`README.md`、`DISCLAIMER.md` 保持原有未提交状态。

```powershell
git diff --check
git status --short
```

提交并推送 `origin/main`，不创建 Release；未明确要求时不部署生产。
