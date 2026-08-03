<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { checkUserExists, login, register } from '@/api/auth'
import { setAuthToken, isLoggedIn } from '@/utils/request'
import { evaluateRegistrationPassword } from '@/utils/registration-password'

// 'checking' -> 'login' -> 'register'
const mode = ref<'checking' | 'login' | 'register'>('checking')
const loading = ref(false)

const username = ref('')
const password = ref('')
const confirmPassword = ref('')

const showPassword = ref(false)
const showConfirmPassword = ref(false)

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

const switchMode = (targetMode: 'login' | 'register') => {
  mode.value = targetMode
  password.value = ''
  confirmPassword.value = ''
}

onMounted(async () => {
  // 已登录则跳转首页
  if (isLoggedIn()) {
    window.location.href = '/dashboard'
    return
  }
  // 检查是否有用户，决定显示登录还是注册
  try {
    const res = await checkUserExists()
    if (res.code === 200 && res.data) {
      // exists=true -> 有用户 -> 登录; exists=false -> 无用户 -> 注册
      mode.value = res.data.exists ? 'login' : 'register'
    } else {
      mode.value = 'login'
    }
  } catch {
    mode.value = 'login'
  }
})

async function handleLogin() {
  if (!username.value.trim()) return
  if (!password.value) return
  loading.value = true
  try {
    const res = await login({ username: username.value.trim(), password: password.value })
    if (res.code === 200 && res.data && res.data.token) {
      setAuthToken(res.data.token, res.data.username)
      window.location.href = '/dashboard'
    } else {
      console.error('[Login] login response invalid:', res)
    }
  } catch (e) {
    console.error('[Login] login failed:', e)
  } finally {
    loading.value = false
  }
}

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

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !loading.value) {
    if (mode.value === 'login') handleLogin()
    else if (mode.value === 'register') handleRegister()
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <!-- Logo -->
      <div class="login-logo">
        <div class="login-logo-icon">闲</div>
        <div class="login-logo-text">XianYuSmart</div>
      </div>

      <!-- Loading -->
      <div v-if="mode === 'checking'" class="login-loading">
        <div class="login-spinner"></div>
      </div>

      <!-- Login Form -->
      <div v-else-if="mode === 'login'" class="login-form">
        <h2 class="login-title">登录</h2>
        <p class="login-subtitle">请输入账号密码登录</p>

        <div class="login-field">
          <label class="login-label">账号</label>
          <div class="login-input-wrap">
            <input
              v-model="username"
              type="text"
              class="login-input"
              placeholder="请输入账号"
              autocomplete="username"
              :disabled="loading"
              @keydown="handleKeydown"
            />
          </div>
        </div>

        <div class="login-field">
          <label class="login-label">密码</label>
          <div class="login-input-wrap">
            <input
              v-model="password"
              :type="showPassword ? 'text' : 'password'"
              class="login-input"
              placeholder="请输入密码"
              autocomplete="current-password"
              :disabled="loading"
              @keydown="handleKeydown"
            />
            <button class="login-eye-btn" @click="showPassword = !showPassword" tabindex="-1">
              {{ showPassword ? '隐藏' : '显示' }}
            </button>
          </div>
        </div>

        <button class="login-btn" :disabled="loading" @click="handleLogin">
          <span v-if="loading" class="login-btn-spinner"></span>
          {{ loading ? '请稍候...' : '登录' }}
        </button>
        <button class="login-mode-btn" :disabled="loading" @click="switchMode('register')">注册新租户</button>
      </div>

      <!-- Register Form -->
      <div v-else-if="mode === 'register'" class="login-form">
        <h2 class="login-title">创建账号</h2>
        <p class="login-subtitle">首次使用，请创建管理员账号</p>

        <div class="login-field">
          <label class="login-label">账号</label>
          <div class="login-input-wrap">
            <input
              v-model="username"
              type="text"
              class="login-input"
              placeholder="请输入账号"
              autocomplete="username"
              :disabled="loading"
              @keydown="handleKeydown"
            />
          </div>
          <p class="login-field-message" :class="usernameValid ? 'is-valid' : 'is-pending'">
            {{ usernameValid ? '账号格式正确' : '账号需为3–20位字符' }}
          </p>
        </div>

        <div class="login-field">
          <label class="login-label">密码</label>
          <div class="login-input-wrap">
            <input
              v-model="password"
              :type="showPassword ? 'text' : 'password'"
              class="login-input"
              placeholder="请输入密码"
              autocomplete="new-password"
              maxlength="72"
              :disabled="loading"
              @keydown="handleKeydown"
            />
            <button class="login-eye-btn" @click="showPassword = !showPassword" tabindex="-1">
              {{ showPassword ? '隐藏' : '显示' }}
            </button>
          </div>
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
        </div>

        <div class="login-field">
          <label class="login-label">确认密码</label>
          <div class="login-input-wrap">
            <input
              v-model="confirmPassword"
              :type="showConfirmPassword ? 'text' : 'password'"
              class="login-input"
              placeholder="请再次输入密码"
              autocomplete="new-password"
              maxlength="72"
              :disabled="loading"
              @keydown="handleKeydown"
            />
            <button class="login-eye-btn" @click="showConfirmPassword = !showConfirmPassword" tabindex="-1">
              {{ showConfirmPassword ? '隐藏' : '显示' }}
            </button>
          </div>
          <p v-if="confirmPassword" class="login-field-message" :class="confirmMatches ? 'is-valid' : 'is-invalid'" aria-live="polite">
            {{ confirmMatches ? '两次密码一致' : '两次密码不一致' }}
          </p>
        </div>

        <button class="login-btn" :disabled="loading || !canRegister" @click="handleRegister">
          <span v-if="loading" class="login-btn-spinner"></span>
          {{ loading ? '请稍候...' : '创建账号' }}
        </button>
        <button class="login-mode-btn" :disabled="loading" @click="switchMode('login')">返回登录</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  height: 100vh;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f6f8;
  padding: 16px;
  overflow-y: auto;
}

.login-card {
  width: 100%;
  max-width: 400px;
  background: #ffffff;
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
  border: 1px solid #e4e7ec;
  border-radius: 8px;
  box-shadow: none;
  padding: 40px 32px;
  position: relative;
  overflow: hidden;
}

.login-card::before {
  content: none;
}

/* Logo */
.login-logo {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin-bottom: 32px;
}

.login-logo-icon {
  width: 40px;
  height: 40px;
  background: #155eef;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 22px;
  font-weight: bold;
}

.login-logo-text {
  font-size: 20px;
  font-weight: 600;
  color: #1c1c1e;
}

/* Loading */
.login-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 0;
}

.login-spinner {
  width: 24px;
  height: 24px;
  border: 2px solid #d4d4d4;
  border-top-color: #1c1c1e;
  border-radius: 50%;
  animation: login-spin 0.6s linear infinite;
}

@keyframes login-spin {
  to { transform: rotate(360deg); }
}

/* Form */
.login-form {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.login-title {
  font-size: 22px;
  font-weight: 600;
  color: #1c1c1e;
  margin: 0;
  text-align: center;
}

.login-subtitle {
  font-size: 14px;
  color: rgba(28,28,30,.55);
  margin: -12px 0 0;
  text-align: center;
}

.login-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.login-label {
  font-size: 13px;
  font-weight: 500;
  color: #1c1c1e;
}

.login-input-wrap {
  position: relative;
  display: flex;
  align-items: center;
}

.login-input {
  width: 100%;
  height: 44px;
  padding: 0 14px;
  font-size: 15px;
  color: #101828;
  background: #ffffff;
  border: 1px solid #d0d5dd;
  border-radius: 6px;
  outline: none;
  transition: all 0.2s;
  box-sizing: border-box;
}

.login-input:focus {
  border-color: #155eef;
  background: #ffffff;
}

.login-input::placeholder {
  color: rgba(28,28,30,.55);
}

.login-input:disabled {
  opacity: 0.5;
}

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

.login-eye-btn {
  position: absolute;
  right: 10px;
  background: none;
  border: none;
  font-size: 12px;
  color: rgba(28,28,30,.55);
  cursor: pointer;
  padding: 4px 6px;
  border-radius: 4px;
  transition: color 0.2s;
}

.login-eye-btn:hover {
  color: #1c1c1e;
}

/* Submit Button */
.login-btn {
  width: 100%;
  height: 48px;
  background: #155eef;
  color: #fff;
  border: none;
  border-radius: 6px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-top: 4px;
}

.login-btn:hover {
  background: #004eeb;
}

.login-btn:active {
  transform: none;
}

.login-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.login-mode-btn {
  border: 0;
  background: transparent;
  color: #155eef;
  cursor: pointer;
  font-size: 14px;
}

.login-btn-spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: login-spin 0.6s linear infinite;
}

/* Responsive: Tablet */
@media (max-width: 768px) {
  .login-card {
    padding: 32px 24px;
  }

  .login-title {
    font-size: 20px;
  }
}

/* Responsive: Small phone */
@media (max-width: 480px) {
  .login-card {
    padding: 24px 20px;
    border-radius: 12px;
  }

  .login-logo-icon {
    width: 36px;
    height: 36px;
    font-size: 20px;
  }

  .login-logo-text {
    font-size: 18px;
  }

  .login-title {
    font-size: 18px;
  }

  .login-input {
    height: 42px;
    font-size: 14px;
  }

  .login-btn {
    height: 44px;
    font-size: 15px;
  }
}

/* 矮屏从顶部展示，确保完整注册表单可滚动访问。 */
@media (max-height: 760px) {
  .login-page {
    align-items: flex-start;
  }
}
</style>
