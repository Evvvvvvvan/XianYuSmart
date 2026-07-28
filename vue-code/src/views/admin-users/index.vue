<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  getPermissionOptions,
  getPlatformUsers,
  resetPlatformUserPassword,
  savePlatformUser,
  type PermissionOption,
  type PlatformUser,
  type PlatformUserList
} from '@/api/admin-user'
import { toast } from '@/utils/toast'

const loading = ref(false)
const saving = ref(false)
const users = ref<PlatformUser[]>([])
const summary = ref<PlatformUserList>({ records: [], total: 0, activeCount: 0, adminCount: 0 })
const options = ref<PermissionOption[]>([])
const editing = ref<PlatformUser | null>()
const passwordTarget = ref<PlatformUser | null>()
const newPassword = ref('')
const form = ref({
  username: '',
  password: '',
  role: 'USER' as 'ADMIN' | 'USER',
  status: 1,
  permissions: [] as string[]
})

const menuGroups = computed(() => groupOptions('MENU'))
const actionGroups = computed(() => groupOptions('ACTION'))
const isCreating = computed(() => editing.value === null)

function groupOptions(type: 'MENU' | 'ACTION') {
  return options.value
    .filter(option => option.type === type)
    .reduce<Record<string, PermissionOption[]>>((groups, option) => {
      ;(groups[option.group] ||= []).push(option)
      return groups
    }, {})
}

async function load() {
  loading.value = true
  try {
    const [userResponse, permissionResponse] = await Promise.all([
      getPlatformUsers(),
      getPermissionOptions()
    ])
    summary.value = userResponse.data || summary.value
    users.value = userResponse.data?.records || []
    options.value = permissionResponse.data || []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.value = {
    username: '',
    password: '',
    role: 'USER',
    status: 1,
    permissions: options.value.map(option => option.code)
  }
}

function openEdit(user: PlatformUser) {
  editing.value = user
  form.value = {
    username: user.username,
    password: '',
    role: user.role,
    status: user.status,
    permissions: [...(user.permissions || [])]
  }
}

function closeEditor() {
  editing.value = undefined
}

function toggleGroup(groupOptions: PermissionOption[]) {
  const codes = groupOptions.map(option => option.code)
  const selected = codes.every(code => form.value.permissions.includes(code))
  form.value.permissions = selected
    ? form.value.permissions.filter(code => !codes.includes(code))
    : [...new Set([...form.value.permissions, ...codes])]
}

function groupSelected(groupOptions: PermissionOption[]) {
  return groupOptions.every(option => form.value.permissions.includes(option.code))
}

async function save() {
  saving.value = true
  try {
    await savePlatformUser({
      id: editing.value?.id,
      username: isCreating.value ? form.value.username : undefined,
      password: isCreating.value ? form.value.password : undefined,
      role: form.value.role,
      status: form.value.status,
      permissions: form.value.permissions
    })
    toast.success(isCreating.value ? '平台账号已创建' : '账号权限已更新')
    closeEditor()
    await load()
  } finally {
    saving.value = false
  }
}

function openPassword(user: PlatformUser) {
  passwordTarget.value = user
  newPassword.value = ''
}

async function resetPassword() {
  if (!passwordTarget.value) return
  await resetPlatformUserPassword({
    userId: passwordTarget.value.id,
    newPassword: newPassword.value
  })
  toast.success('密码已重置，该账号需要重新登录')
  passwordTarget.value = null
}

function formatTime(value?: string) {
  return value ? value.replace('T', ' ').slice(0, 19) : '从未登录'
}

onMounted(load)
</script>

<template>
  <div class="permission-page">
    <section class="permission-hero">
      <div>
        <span class="eyebrow">PLATFORM ACCESS</span>
        <h2>账号与权限</h2>
        <p>集中管理全站登录账号。管理员拥有完整平台能力，普通用户按菜单和操作精确授权。</p>
      </div>
      <button class="primary" @click="openCreate">创建账号</button>
    </section>

    <section class="summary-grid">
      <article><span>全站账号</span><strong>{{ summary.total }}</strong></article>
      <article><span>启用账号</span><strong>{{ summary.activeCount }}</strong></article>
      <article><span>管理员</span><strong>{{ summary.adminCount }}</strong></article>
    </section>

    <section class="account-panel">
      <header>
        <div>
          <h3>登录账号</h3>
          <p>停用账号会立即失效；角色或状态调整后需要重新登录。</p>
        </div>
        <button :disabled="loading" @click="load">{{ loading ? '刷新中' : '刷新' }}</button>
      </header>
      <div v-if="loading" class="empty">正在读取全站账号...</div>
      <div v-else class="account-list">
        <article v-for="user in users" :key="user.id" class="account-row">
          <div class="identity">
            <span class="avatar">{{ user.username.slice(0, 1).toUpperCase() }}</span>
            <span><strong>{{ user.username }}</strong><small>ID {{ user.id }} · 创建于 {{ formatTime(user.createdTime) }}</small></span>
          </div>
          <div class="role-cell">
            <span :class="['role', user.role.toLowerCase()]">{{ user.role === 'ADMIN' ? '管理员' : '普通用户' }}</span>
            <small>{{ user.role === 'ADMIN' ? '全站完整权限' : `${user.permissions?.length || 0} 项权限` }}</small>
          </div>
          <div class="login-cell">
            <strong>{{ formatTime(user.lastLoginTime) }}</strong>
            <small>{{ user.lastLoginIp || '暂无登录 IP' }}</small>
          </div>
          <span :class="['state', user.status === 1 ? 'enabled' : 'disabled']">
            {{ user.status === 1 ? '已启用' : '已停用' }}
          </span>
          <div class="actions">
            <button @click="openEdit(user)">权限设置</button>
            <button @click="openPassword(user)">重置密码</button>
          </div>
        </article>
        <div v-if="users.length === 0" class="empty">暂无平台账号</div>
      </div>
    </section>

    <Teleport to="body">
      <div v-if="editing !== undefined" class="overlay" @click.self="closeEditor">
        <section class="editor">
          <header>
            <div>
              <span class="eyebrow">{{ isCreating ? 'CREATE ACCOUNT' : 'ACCESS POLICY' }}</span>
              <h3>{{ isCreating ? '创建平台账号' : `设置 ${editing?.username}` }}</h3>
            </div>
            <button class="icon-button" @click="closeEditor">×</button>
          </header>

          <div class="base-form">
            <label v-if="isCreating">用户名<input v-model="form.username" maxlength="20" placeholder="3-20位中英文、数字或下划线" /></label>
            <label v-if="isCreating">初始密码<input v-model="form.password" type="password" maxlength="72" placeholder="至少8位" /></label>
            <label>账号角色
              <select v-model="form.role">
                <option value="USER">普通用户 · 按所选权限使用</option>
                <option value="ADMIN">管理员 · 管理全站并拥有全部权限</option>
              </select>
            </label>
            <label>账号状态
              <select v-model="form.status">
                <option :value="1">启用</option>
                <option :value="0">停用</option>
              </select>
            </label>
          </div>

          <div v-if="form.role === 'ADMIN'" class="admin-notice">
            管理员可管理全站账号、执行服务更新并访问全部菜单和功能，不受下方权限项限制。
          </div>

          <div v-else class="permission-sections">
            <section>
              <div class="section-title">
                <span><strong>菜单权限</strong><small>决定侧边栏可见页面及页面读取权限</small></span>
                <button @click="form.permissions = options.map(option => option.code)">选择全部</button>
              </div>
              <div v-for="(group, name) in menuGroups" :key="name" class="permission-group">
                <label class="group-check"><input type="checkbox" :checked="groupSelected(group)" @change="toggleGroup(group)" />{{ name }}</label>
                <label v-for="option in group" :key="option.code" class="permission-option">
                  <input v-model="form.permissions" type="checkbox" :value="option.code" />
                  <span>{{ option.label }}</span>
                </label>
              </div>
            </section>
            <section>
              <div class="section-title">
                <span><strong>功能权限</strong><small>在已授权页面中继续限制新增、修改、发送和执行</small></span>
              </div>
              <div v-for="(group, name) in actionGroups" :key="name" class="permission-group">
                <label class="group-check"><input type="checkbox" :checked="groupSelected(group)" @change="toggleGroup(group)" />{{ name }}</label>
                <label v-for="option in group" :key="option.code" class="permission-option">
                  <input v-model="form.permissions" type="checkbox" :value="option.code" />
                  <span>{{ option.label }}</span>
                </label>
              </div>
            </section>
          </div>

          <footer>
            <button @click="closeEditor">取消</button>
            <button class="primary" :disabled="saving" @click="save">{{ saving ? '保存中...' : '保存账号与权限' }}</button>
          </footer>
        </section>
      </div>

      <div v-if="passwordTarget" class="overlay" @click.self="passwordTarget = null">
        <section class="password-dialog">
          <header><div><h3>重置密码</h3><p>{{ passwordTarget.username }}</p></div><button class="icon-button" @click="passwordTarget = null">×</button></header>
          <label>新密码<input v-model="newPassword" type="password" maxlength="72" placeholder="8-72位" @keyup.enter="resetPassword" /></label>
          <p class="hint">保存后会清除该账号的登录状态，需要使用新密码重新登录。</p>
          <footer><button @click="passwordTarget = null">取消</button><button class="primary" @click="resetPassword">确认重置</button></footer>
        </section>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.permission-page { height: 100%; overflow: auto; padding: 18px; box-sizing: border-box; background: var(--color-background); color: #101828; }
.permission-hero, .account-panel, .summary-grid article { background: #fff; border: 1px solid #e4e7ec; border-radius: 10px; }
.permission-hero { display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; padding: 20px; }
h2, h3, p { margin: 0; } h2 { margin-top: 3px; font-size: 21px; } .permission-hero p, .account-panel header p { margin-top: 6px; color: #667085; font-size: 13px; }
.eyebrow { color: #155eef; font-size: 11px; font-weight: 700; letter-spacing: .08em; }
button, input, select { font: inherit; } button { padding: 8px 13px; border: 1px solid #d0d5dd; border-radius: 6px; background: #fff; color: #344054; cursor: pointer; }
.primary { border-color: #155eef; background: #155eef; color: #fff; }
.summary-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin: 12px 0; }
.summary-grid article { padding: 15px 17px; } .summary-grid span { color: #667085; font-size: 12px; } .summary-grid strong { display: block; margin-top: 5px; font-size: 24px; }
.account-panel > header { display: flex; align-items: center; justify-content: space-between; padding: 16px 18px; border-bottom: 1px solid #eaecf0; }
.account-panel h3 { font-size: 16px; }
.account-row { display: grid; grid-template-columns: minmax(220px, 1.3fr) minmax(140px, .7fr) minmax(190px, .8fr) 86px auto; align-items: center; gap: 16px; padding: 14px 18px; border-bottom: 1px solid #f0f1f3; }
.account-row:last-child { border-bottom: 0; }.identity { display: flex; align-items: center; gap: 11px; }.avatar { display: grid; place-items: center; width: 36px; height: 36px; flex: 0 0 36px; border-radius: 8px; background: #eef4ff; color: #155eef; font-weight: 700; }
.identity strong, .identity small, .role-cell small, .login-cell strong, .login-cell small { display: block; }.identity small, .role-cell small, .login-cell small { margin-top: 4px; color: #98a2b3; font-size: 11px; }.login-cell strong { font-size: 13px; font-weight: 500; }
.role, .state { display: inline-block; width: fit-content; padding: 3px 8px; border-radius: 999px; font-size: 12px; }.role.admin { color: #6941c6; background: #f4f3ff; }.role.user, .state.enabled { color: #067647; background: #ecfdf3; }.state.disabled { color: #b42318; background: #fef3f2; }
.actions { display: flex; gap: 6px; justify-content: flex-end; }.actions button { padding: 6px 9px; font-size: 12px; }.empty { padding: 70px 20px; text-align: center; color: #98a2b3; }
.overlay { position: fixed; inset: 0; z-index: 2200; display: grid; place-items: center; padding: 24px; background: rgba(16,24,40,.48); }
.editor { display: flex; flex-direction: column; width: min(900px, 96vw); max-height: 92vh; border-radius: 12px; background: #fff; box-shadow: 0 24px 60px rgba(16,24,40,.24); overflow: hidden; }
.editor > header, .password-dialog header { display: flex; align-items: center; justify-content: space-between; padding: 18px 20px; border-bottom: 1px solid #eaecf0; }.editor h3 { margin-top: 4px; }
.icon-button { padding: 2px 8px; border: 0; font-size: 23px; }.base-form { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; padding: 18px 20px; }
label { color: #475467; font-size: 13px; }.base-form label, .password-dialog label { display: grid; gap: 6px; }input, select { width: 100%; padding: 9px 10px; border: 1px solid #d0d5dd; border-radius: 6px; box-sizing: border-box; background: #fff; color: #344054; }
.admin-notice { margin: 0 20px 20px; padding: 13px 14px; border: 1px solid #b2ccff; border-radius: 8px; background: #eff4ff; color: #344054; font-size: 13px; }
.permission-sections { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; padding: 0 20px 18px; overflow: auto; }.permission-sections > section { border: 1px solid #e4e7ec; border-radius: 9px; overflow: hidden; }
.section-title { display: flex; align-items: center; justify-content: space-between; padding: 12px 14px; background: #f9fafb; border-bottom: 1px solid #eaecf0; }.section-title strong, .section-title small { display: block; }.section-title small { margin-top: 3px; color: #98a2b3; font-size: 11px; }.section-title button { padding: 4px 8px; font-size: 12px; }
.permission-group { display: grid; grid-template-columns: 120px 1fr 1fr; align-items: center; gap: 8px; min-height: 40px; padding: 7px 12px; border-bottom: 1px solid #f2f4f7; }.permission-group:last-child { border-bottom: 0; }
.group-check, .permission-option { display: flex; align-items: center; gap: 7px; }.group-check { color: #344054; font-weight: 600; }.permission-option input, .group-check input { width: 15px; height: 15px; margin: 0; }
.editor > footer, .password-dialog footer { display: flex; justify-content: flex-end; gap: 8px; padding: 14px 20px; border-top: 1px solid #eaecf0; }
.password-dialog { width: min(440px, 96vw); padding-bottom: 4px; border-radius: 12px; background: #fff; }.password-dialog header { padding: 16px 18px; }.password-dialog header p { margin-top: 3px; color: #667085; font-size: 12px; }.password-dialog > label, .password-dialog .hint { margin: 16px 18px 0; }.hint { color: #667085; font-size: 12px; line-height: 1.6; }
@media (max-width: 900px) { .account-row { grid-template-columns: 1fr 1fr; }.actions { justify-content: flex-start; }.permission-sections { grid-template-columns: 1fr; }.base-form { grid-template-columns: 1fr; } }
@media (max-width: 600px) { .permission-page { padding: 10px; }.permission-hero { align-items: stretch; flex-direction: column; }.summary-grid { grid-template-columns: 1fr; }.account-row { grid-template-columns: 1fr; }.overlay { padding: 0; }.editor { width: 100%; height: 100%; max-height: 100%; border-radius: 0; }.permission-group { grid-template-columns: 1fr 1fr; }.group-check { grid-column: 1 / -1; } }
</style>
