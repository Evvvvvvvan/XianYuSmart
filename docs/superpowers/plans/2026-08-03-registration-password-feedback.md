# Registration Password Feedback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在公开注册页实时显示密码规则、强度和确认状态，并由后端拒绝相同规则下的弱密码。

**Architecture:** Java 与 TypeScript 各自提供一个无依赖的注册密码判断函数，避免控制器和 Vue 页面堆积规则。注册控制器负责最终安全拦截，Vue 页面负责实时反馈和按钮状态；两侧使用相同的规则、错误顺序和测试样例。

**Tech Stack:** Java 21、Spring Boot 3.5、JUnit 5、Vue 3 Composition API、TypeScript 5.9、Node.js 内置测试运行器、Vite 7。

---

## 文件结构

- Create: `src/main/java/com/xianyusmart/util/RegistrationPasswordPolicy.java` — 后端注册密码规则与错误信息。
- Modify: `src/main/java/com/xianyusmart/controller/LoginController.java` — 注册写入前调用密码规则。
- Create temporarily: `src/test/java/com/xianyusmart/util/RegistrationPasswordPolicyTest.java` — 后端红绿回归验证，交付前删除。
- Create: `vue-code/src/utils/registration-password.ts` — 前端密码规则、强度和检查项状态。
- Modify: `vue-code/src/views/login/index.vue` — 注册页实时反馈、确认状态和按钮控制。
- Create temporarily: `vue-code/scripts/registration-password.test.mjs` — 前端纯函数红绿验证，交付前删除。
- Regenerate: `src/main/resources/static/**` — Vite 正式构建产物。

### Task 1: 后端密码策略红绿验证

**Files:**
- Create temporarily: `src/test/java/com/xianyusmart/util/RegistrationPasswordPolicyTest.java`
- Create: `src/main/java/com/xianyusmart/util/RegistrationPasswordPolicy.java`
- Modify: `src/main/java/com/xianyusmart/controller/LoginController.java:53-78`

- [ ] **Step 1: 编写失败测试**

```java
package com.xianyusmart.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RegistrationPasswordPolicyTest {

    @Test
    void acceptsMediumAndStrongPasswords() {
        assertNull(RegistrationPasswordPolicy.validate("merchant", "Abc12345"));
        assertNull(RegistrationPasswordPolicy.validate("merchant", "Abc123!45678"));
    }

    @Test
    void rejectsPasswordsWithOnlyOneCharacterType() {
        assertEquals("密码至少包含字母、数字、符号中的两类",
                RegistrationPasswordPolicy.validate("merchant", "abcdefgh"));
    }

    @Test
    void rejectsUsernameAndCommonPasswords() {
        assertEquals("密码不能与账号相同",
                RegistrationPasswordPolicy.validate("User1234", "user1234"));
        assertEquals("密码过于简单，请更换后重试",
                RegistrationPasswordPolicy.validate("merchant", "Password123"));
    }

    @Test
    void rejectsInvalidLength() {
        assertEquals("密码长度需在8-72之间",
                RegistrationPasswordPolicy.validate("merchant", "Abc123"));
    }
}
```

- [ ] **Step 2: 运行测试确认红灯**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task test
```

Expected: `RegistrationPasswordPolicy` 不存在导致测试编译失败。

- [ ] **Step 3: 实现最小后端规则**

```java
package com.xianyusmart.util;

import java.util.Locale;
import java.util.Set;

/** 注册密码规则。 */
public final class RegistrationPasswordPolicy {

    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "12345678", "password", "password123", "admin123",
            "qwerty123", "abc12345", "11111111", "00000000"
    );

    private RegistrationPasswordPolicy() {
    }

    public static String validate(String username, String password) {
        if (password == null || password.length() < 8 || password.length() > 72) {
            return "密码长度需在8-72之间";
        }
        boolean hasLetter = password.chars().anyMatch(RegistrationPasswordPolicy::isLetter);
        boolean hasDigit = password.chars().anyMatch(RegistrationPasswordPolicy::isDigit);
        boolean hasSymbol = password.chars().anyMatch(value -> !isLetter(value) && !isDigit(value));
        int categories = (hasLetter ? 1 : 0) + (hasDigit ? 1 : 0) + (hasSymbol ? 1 : 0);
        if (categories < 2) {
            return "密码至少包含字母、数字、符号中的两类";
        }
        String normalizedPassword = password.toLowerCase(Locale.ROOT);
        String normalizedUsername = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!normalizedUsername.isEmpty() && normalizedPassword.equals(normalizedUsername)) {
            return "密码不能与账号相同";
        }
        if (COMMON_PASSWORDS.contains(normalizedPassword) || password.chars().distinct().count() == 1) {
            return "密码过于简单，请更换后重试";
        }
        return null;
    }

    private static boolean isLetter(int value) {
        return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z';
    }

    private static boolean isDigit(int value) {
        return value >= '0' && value <= '9';
    }
}
```

在 `LoginController` 增加：

```java
import com.xianyusmart.util.RegistrationPasswordPolicy;
```

将原密码长度判断替换为：

```java
String passwordError = RegistrationPasswordPolicy.validate(reqDTO.getUsername(), reqDTO.getPassword());
if (passwordError != null) {
    return ResultObject.validateFailed(passwordError);
}
```

- [ ] **Step 4: 运行测试确认绿灯**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task test
```

Expected: `BUILD SUCCESS`，四个策略测试通过。

### Task 2: 前端密码分析纯函数红绿验证

**Files:**
- Create temporarily: `vue-code/scripts/registration-password.test.mjs`
- Create: `vue-code/src/utils/registration-password.ts`

- [ ] **Step 1: 编写失败测试**

```javascript
import test from 'node:test'
import assert from 'node:assert/strict'
import { evaluateRegistrationPassword } from '../src/utils/registration-password.ts'

test('accepts medium and strong passwords', () => {
  assert.equal(evaluateRegistrationPassword('Abc12345', 'merchant').strength, 'medium')
  assert.equal(evaluateRegistrationPassword('Abc123!45678', 'merchant').strength, 'strong')
})

test('rejects one-category, username and common passwords', () => {
  assert.equal(evaluateRegistrationPassword('abcdefgh', 'merchant').valid, false)
  assert.equal(evaluateRegistrationPassword('user1234', 'User1234').usernameValid, false)
  assert.equal(evaluateRegistrationPassword('Password123', 'merchant').simpleValid, false)
})
```

- [ ] **Step 2: 运行测试确认红灯**

Run:

```powershell
node --test vue-code/scripts/registration-password.test.mjs
```

Expected: `ERR_MODULE_NOT_FOUND`，因为前端策略文件尚未创建。

- [ ] **Step 3: 实现前端密码分析函数**

```typescript
export type PasswordStrength = 'empty' | 'weak' | 'medium' | 'strong'

export interface RegistrationPasswordEvaluation {
  valid: boolean
  strength: PasswordStrength
  lengthValid: boolean
  categoriesValid: boolean
  usernameValid: boolean
  simpleValid: boolean
  categoryCount: number
}

const commonPasswords = new Set([
  '12345678', 'password', 'password123', 'admin123',
  'qwerty123', 'abc12345', '11111111', '00000000'
])

export function evaluateRegistrationPassword(
  password: string,
  username: string
): RegistrationPasswordEvaluation {
  const lengthValid = password.length >= 8 && password.length <= 72
  const hasLetter = /[A-Za-z]/.test(password)
  const hasDigit = /[0-9]/.test(password)
  const hasSymbol = /[^A-Za-z0-9]/.test(password)
  const categoryCount = Number(hasLetter) + Number(hasDigit) + Number(hasSymbol)
  const categoriesValid = categoryCount >= 2
  const normalizedPassword = password.toLocaleLowerCase()
  const normalizedUsername = username.trim().toLocaleLowerCase()
  const usernameValid = !normalizedUsername || normalizedPassword !== normalizedUsername
  const simpleValid = !commonPasswords.has(normalizedPassword) && !/^(.)\1+$/.test(password)
  const valid = lengthValid && categoriesValid && usernameValid && simpleValid
  const strength: PasswordStrength = !password
    ? 'empty'
    : !valid
      ? 'weak'
      : password.length >= 12 && categoryCount === 3
        ? 'strong'
        : 'medium'

  return {
    valid,
    strength,
    lengthValid,
    categoriesValid,
    usernameValid,
    simpleValid,
    categoryCount
  }
}
```

- [ ] **Step 4: 运行测试确认绿灯**

Run:

```powershell
node --test vue-code/scripts/registration-password.test.mjs
```

Expected: 两个测试通过。

### Task 3: 注册页实时反馈 UI

**Files:**
- Modify: `vue-code/src/views/login/index.vue:1-218`

- [ ] **Step 1: 接入计算状态**

将 Vue 导入改为：

```typescript
import { computed, ref, onMounted } from 'vue'
```

增加密码规则导入：

```typescript
import { evaluateRegistrationPassword } from '@/utils/registration-password'
```

在显示密码状态后增加：

```typescript
const trimmedUsername = computed(() => username.value.trim())
const usernameValid = computed(() => trimmedUsername.value.length >= 3 && trimmedUsername.value.length <= 20)
const passwordEvaluation = computed(() => evaluateRegistrationPassword(password.value, trimmedUsername.value))
const confirmMatches = computed(() => !!confirmPassword.value && password.value === confirmPassword.value)
const canRegister = computed(() => usernameValid.value && passwordEvaluation.value.valid && confirmMatches.value)

const passwordStrengthLabel = computed(() => ({
  empty: '未输入',
  weak: '弱',
  medium: '中',
  strong: '强'
}[passwordEvaluation.value.strength]))
```

将 `handleRegister` 的静默校验替换为：

```typescript
async function handleRegister() {
  if (!canRegister.value) return
  loading.value = true
  try {
    const res = await register({
      username: trimmedUsername.value,
      password: password.value,
      confirmPassword: confirmPassword.value
    })
    if (res.code === 200 && res.data && res.data.token) {
      setAuthToken(res.data.token, res.data.username)
      window.location.href = '/dashboard'
    } else {
      console.error('[Login] register response invalid:', res)
    }
  } catch (e) {
    console.error('[Login] register failed:', e)
  } finally {
    loading.value = false
  }
}
```

- [ ] **Step 2: 增加账号、强度和确认提示**

在注册账号输入框后增加：

```vue
<p class="login-field-message" :class="usernameValid ? 'is-valid' : 'is-pending'">
  {{ usernameValid ? '账号格式正确' : '账号需为3–20位字符' }}
</p>
```

在注册密码输入框后增加：

```vue
<div class="password-feedback" aria-live="polite">
  <div class="password-strength-row">
    <span>密码强度：{{ passwordStrengthLabel }}</span>
    <div class="password-strength-bars" :class="`is-${passwordEvaluation.strength}`" aria-hidden="true">
      <span></span><span></span><span></span>
    </div>
  </div>
  <ul class="password-rules">
    <li :class="{ 'is-valid': passwordEvaluation.lengthValid, 'is-invalid': password && !passwordEvaluation.lengthValid }">8–72 位字符</li>
    <li :class="{ 'is-valid': passwordEvaluation.categoriesValid, 'is-invalid': password && !passwordEvaluation.categoriesValid }">至少包含字母、数字、符号中的两类</li>
    <li :class="{ 'is-valid': passwordEvaluation.usernameValid && passwordEvaluation.simpleValid, 'is-invalid': password && (!passwordEvaluation.usernameValid || !passwordEvaluation.simpleValid) }">不能与账号相同，也不能使用常见或重复弱密码</li>
  </ul>
</div>
```

在确认密码输入框后增加：

```vue
<p v-if="confirmPassword" class="login-field-message" :class="confirmMatches ? 'is-valid' : 'is-invalid'" aria-live="polite">
  {{ confirmMatches ? '两次密码一致' : '两次密码不一致' }}
</p>
```

将注册按钮禁用条件改为：

```vue
:disabled="loading || !canRegister"
```

- [ ] **Step 3: 增加现有卡片风格的最小样式**

```css
.login-field-message {
  margin: 2px 0 0;
  color: #667085;
  font-size: 12px;
  line-height: 1.5;
}

.login-field-message.is-valid,
.password-rules .is-valid {
  color: #067647;
}

.login-field-message.is-invalid,
.password-rules .is-invalid {
  color: #b42318;
}

.password-feedback {
  display: grid;
  gap: 8px;
  padding-top: 2px;
}

.password-strength-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: #475467;
  font-size: 12px;
}

.password-strength-bars {
  display: grid;
  grid-template-columns: repeat(3, 30px);
  gap: 4px;
}

.password-strength-bars span {
  height: 4px;
  border-radius: 999px;
  background: #e4e7ec;
}

.password-strength-bars.is-weak span:first-child,
.password-strength-bars.is-medium span:nth-child(-n+2),
.password-strength-bars.is-strong span {
  background: currentColor;
}

.password-strength-bars.is-weak { color: #d92d20; }
.password-strength-bars.is-medium { color: #dc6803; }
.password-strength-bars.is-strong { color: #079455; }

.password-rules {
  display: grid;
  gap: 4px;
  margin: 0;
  padding-left: 18px;
  color: #667085;
  font-size: 12px;
  line-height: 1.5;
}
```

- [ ] **Step 4: 运行前端类型检查与正式构建**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task frontend
```

Expected: Vue TypeScript 类型检查与 Vite 构建均成功，`src/main/resources/static` 更新。

### Task 4: 清理、视觉验证与完整交付

**Files:**
- Delete: `src/test/java/com/xianyusmart/util/RegistrationPasswordPolicyTest.java`
- Delete: `vue-code/scripts/registration-password.test.mjs`
- Review: all implementation and generated static files

- [ ] **Step 1: 删除临时测试产物**

使用 `apply_patch` 删除两个临时测试文件，并确认：

```powershell
git status --short | Select-String 'RegistrationPasswordPolicyTest|registration-password.test'
```

Expected: 无输出。

- [ ] **Step 2: 删除后重新运行正式验证**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task test
powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task frontend
powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\operating-xianyusmart\scripts\project.ps1 -Task package -Clean -IncludeFrontend
```

Expected: 使用 Java 21，Maven 测试、Vue 类型检查、Vite 构建和 JAR 打包全部成功。

- [ ] **Step 3: 视觉检查注册页**

启动现有 Vite 开发服务器，切换到“注册新租户”，分别输入弱、中、强密码并检查桌面和 480px 宽度：

```powershell
npm.cmd --prefix vue-code run dev -- --host 127.0.0.1 --port 4173
```

Expected: 强度文字与三段条同步变化；规则状态和确认提示不溢出；按钮只在全部有效时可用；显示/隐藏密码、回车和加载状态正常。

- [ ] **Step 4: 审查最终差异**

Run:

```powershell
git diff --check
git diff --stat
git status --short
```

Expected: 无空白错误；没有临时测试；`DISCLAIMER.md` 与 README 原有未提交改动未进入本次范围；没有数据库迁移和依赖变化。

- [ ] **Step 5: 提交并推送实现**

仅暂存实现文件、计划文档与正式前端构建产物：

```powershell
git add -- src/main/java/com/xianyusmart/util/RegistrationPasswordPolicy.java src/main/java/com/xianyusmart/controller/LoginController.java vue-code/src/utils/registration-password.ts vue-code/src/views/login/index.vue
git add -A -- src/main/resources/static
git commit -m "fix: show registration password feedback"
git push origin main
```

Expected: `origin/main` 指向新的实现提交，工作区只保留任务开始前已有的 README 和 `DISCLAIMER.md` 改动。
