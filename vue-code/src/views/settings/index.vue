<script setup lang="ts">
import { computed, ref, onMounted, markRaw, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getCurrentUser, changePassword } from '@/api/system'
import { logout } from '@/api/auth'
import { getSetting, saveSetting, testEmail } from '@/api/setting'
import { getAIStatus, testAIConnection, type AIConnectionTestResult } from '@/api/ai'
import { getBackupModules, exportBackup, importBackup, getLogDates, downloadLog, type BackupModule } from '@/api/backup'
import { toast } from '@/utils/toast'
import { showConfirm } from '@/utils/confirm'
import { clearAuthToken } from '@/utils/request'
import { hasPermission, permissionState, updateMenuLayout } from '@/utils/permission'
import {
  MENU_GROUPS,
  MENU_LAYOUT_SETTING_KEY,
  createDefaultMenuLayout,
  moveMenuGroup,
  moveMenuItem,
  normalizeMenuLayout,
  serializeMenuLayout,
  type MenuItemDefinition
} from '@/config/menu'
import IconUser from '@/components/icons/IconUser.vue'
import IconRobot from '@/components/icons/IconRobot.vue'
import IconChat from '@/components/icons/IconChat.vue'
import IconMail from '@/components/icons/IconMail.vue'
import IconBackup from '@/components/icons/IconBackup.vue'
import IconInfo from '@/components/icons/IconInfo.vue'
import IconTooling from '@/components/icons/IconTooling.vue'

const router = useRouter()

// 当前选中的菜单
const activeMenu = ref('account')

// 账号信息
const username = ref('')
const lastLoginTime = ref('')
const loading = ref(false)

// 修改密码
const showPasswordForm = ref(false)
const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const changingPassword = ref(false)

const showOldPassword = ref(false)
const showNewPassword = ref(false)
const showConfirmPassword = ref(false)

// 退出登录
const loggingOut = ref(false)

// 系统提示词
const SYS_PROMPT_KEY = 'sys_prompt'
const DEFAULT_SYS_PROMPT = '作为闲鱼虚拟商品店铺客服，结合商品和知识库信息简短、准确回复。信息不足时明确说明需要补充的内容，不编造卡密、库存、价格或售后承诺。'
const sysPromptValue = ref('')
const sysPromptSaving = ref(false)
const sysPromptLoaded = ref(false)

// 相似度阈值
const SIMILARITY_THRESHOLD_KEY = 'similarity_threshold'
const DEFAULT_SIMILARITY_THRESHOLD = 0.1
const similarityThreshold = ref(DEFAULT_SIMILARITY_THRESHOLD)
const similarityThresholdSaving = ref(false)

// AI 对话配置
const AI_PROVIDER_SETTING = 'ai_provider'
const AI_PROTOCOL_SETTING = 'ai_protocol'
const AI_CUSTOM_NAME_SETTING = 'ai_custom_name'
const AI_API_KEY_SETTING = 'ai_api_key'
const AI_BASE_URL_SETTING = 'ai_base_url'
const AI_MODEL_SETTING = 'ai_model'

type AIProtocol = 'openai' | 'anthropic'
type AIProviderPreset = {
  id: string
  name: string
  description: string
  tone: string
  protocols: AIProtocol[]
  defaults: Partial<Record<AIProtocol, { baseUrl: string; model: string }>>
}

const AI_PROVIDERS: AIProviderPreset[] = [
  { id: 'openai', name: 'OpenAI', description: 'OpenAI 官方服务', tone: 'emerald', protocols: ['openai'], defaults: { openai: { baseUrl: 'https://api.openai.com', model: 'gpt-5.6-terra' } } },
  { id: 'anthropic', name: 'Anthropic Claude', description: 'Anthropic 官方 Messages API', tone: 'amber', protocols: ['anthropic'], defaults: { anthropic: { baseUrl: 'https://api.anthropic.com', model: 'claude-sonnet-5' } } },
  { id: 'deepseek', name: 'DeepSeek', description: 'DeepSeek 官方双协议接口', tone: 'blue', protocols: ['openai', 'anthropic'], defaults: { openai: { baseUrl: 'https://api.deepseek.com', model: 'deepseek-v4-flash' }, anthropic: { baseUrl: 'https://api.deepseek.com/anthropic', model: 'deepseek-v4-flash' } } },
  { id: 'qwen', name: '阿里云百炼 / Qwen', description: '百炼北京地域官方接口', tone: 'violet', protocols: ['openai', 'anthropic'], defaults: { openai: { baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen3.7-plus' }, anthropic: { baseUrl: 'https://dashscope.aliyuncs.com/apps/anthropic', model: 'qwen3.7-plus' } } },
  { id: 'gemini', name: 'Google Gemini', description: 'Gemini OpenAI 兼容接口', tone: 'cyan', protocols: ['openai'], defaults: { openai: { baseUrl: 'https://generativelanguage.googleapis.com/v1beta/openai', model: 'gemini-3.6-flash' } } },
  { id: 'custom', name: '第三方中转站', description: '自定义 OpenAI 或 Anthropic 兼容接口', tone: 'slate', protocols: ['openai', 'anthropic'], defaults: {} }
]

const aiProvider = ref('deepseek')
const aiProtocol = ref<AIProtocol>('openai')
const aiCustomName = ref('')
const aiApiKey = ref('')
const aiBaseUrl = ref('https://api.deepseek.com')
const aiModel = ref('deepseek-v4-flash')
const aiApiKeySaving = ref(false)
const showApiKey = ref(false)
const showAIAdvanced = ref(false)
const aiTesting = ref(false)
const aiTestMessage = ref('请用一句话回复：连接成功')
const aiTestResult = ref<AIConnectionTestResult | null>(null)

// Embedding 独立高级配置
const EMBEDDING_ENABLED_SETTING = 'ai_embedding_enabled'
const EMBEDDING_API_KEY_SETTING = 'ai_embedding_api_key'
const EMBEDDING_BASE_URL_SETTING = 'ai_embedding_base_url'
const EMBEDDING_MODEL_SETTING = 'ai_embedding_model'
const DEFAULT_EMBEDDING_BASE_URL = 'https://dashscope.aliyuncs.com/compatible-mode/v1'
const DEFAULT_EMBEDDING_MODEL = 'text-embedding-v3'

const embeddingEnabled = ref(false)
const embeddingApiKey = ref('')
const embeddingBaseUrl = ref(DEFAULT_EMBEDDING_BASE_URL)
const embeddingModel = ref(DEFAULT_EMBEDDING_MODEL)
const embeddingSaving = ref(false)
const showEmbeddingApiKey = ref(false)
const showEmbeddingConfig = ref(false)

// AI 商品图独立高级配置
const IMAGE_ENABLED_SETTING = 'ai_image_enabled'
const IMAGE_API_KEY_SETTING = 'ai_image_api_key'
const IMAGE_BASE_URL_SETTING = 'ai_image_base_url'
const IMAGE_MODEL_SETTING = 'ai_image_model'
const DEFAULT_IMAGE_BASE_URL = 'https://dashscope.aliyuncs.com/compatible-mode/v1'
const DEFAULT_IMAGE_MODEL = 'wanx2.1-t2i-turbo'

const imageEnabled = ref(false)
const imageApiKey = ref('')
const imageBaseUrl = ref(DEFAULT_IMAGE_BASE_URL)
const imageModel = ref(DEFAULT_IMAGE_MODEL)
const imageSaving = ref(false)
const showImageApiKey = ref(false)
const showImageConfig = ref(false)

// 邮箱通知配置
const EMAIL_SMTP_HOST_KEY = 'email_smtp_host'
const EMAIL_SMTP_PORT_KEY = 'email_smtp_port'
const EMAIL_SMTP_USERNAME_KEY = 'email_smtp_username'
const EMAIL_SMTP_PASSWORD_KEY = 'email_smtp_password'
const EMAIL_SMTP_FROM_KEY = 'email_smtp_from'
const EMAIL_SMTP_SSL_KEY = 'email_smtp_ssl'
const EMAIL_WS_DISCONNECT_NOTIFY_KEY = 'email_notify_ws_disconnect_enabled'
const EMAIL_COOKIE_EXPIRE_NOTIFY_KEY = 'email_notify_cookie_expire_enabled'

const emailSmtpHost = ref('')
const emailSmtpPort = ref('465')
const emailSmtpUsername = ref('')
const emailSmtpPassword = ref('')
const emailSmtpFrom = ref('')
const emailSmtpSsl = ref(true)
const emailSaving = ref(false)
const emailTesting = ref(false)
const showEmailPassword = ref(false)
const emailConfigured = ref(false)
const emailConfigExpanded = ref(true)
const wsDisconnectNotifyEnabled = ref(false)
const cookieExpireNotifyEnabled = ref(false)

// AI 状态
const aiStatus = ref({
  enabled: false,
  available: false,
  apiKeyConfigured: false,
  message: '',
  baseUrl: '',
  model: '',
  provider: '',
  protocol: '',
  endpoint: ''
})

const selectedAIProvider = computed(() => AI_PROVIDERS.find(item => item.id === aiProvider.value) || AI_PROVIDERS[2]!)
const aiEndpointPreview = computed(() => resolveAIEndpointPreview(aiBaseUrl.value, aiProtocol.value))
const aiStatusProviderName = computed(() => aiStatus.value.provider === 'custom'
  ? aiCustomName.value.trim() || '第三方中转站'
  : AI_PROVIDERS.find(item => item.id === aiStatus.value.provider)?.name || aiStatus.value.provider || selectedAIProvider.value.name)

watch([aiProvider, aiProtocol, aiCustomName, aiApiKey, aiBaseUrl, aiModel, aiTestMessage], () => {
  if (!aiTesting.value) aiTestResult.value = null
})

// 菜单排序配置
const menuLayout = ref(normalizeMenuLayout(permissionState.value?.menuLayout))
const menuLayoutSaving = ref(false)
const draggedGroupId = ref('')
const draggedItemGroupId = ref('')
const draggedItemId = ref('')
const lastGroupTargetId = ref('')
const lastItemTargetKey = ref('')
const canSaveMenuLayout = computed(() => hasPermission('action:system-write'))
const menuGroupDefinitionMap = new Map(MENU_GROUPS.map(group => [group.id, group]))
const editableMenuGroups = computed<Array<{ id: string; label: string; items: MenuItemDefinition[] }>>(() => menuLayout.value.groups.flatMap(groupLayout => {
  const definition = menuGroupDefinitionMap.get(groupLayout.id)
  if (!definition) return []
  const items = groupLayout.items.flatMap(itemId => {
    const item = definition.items.find(menuItem => menuItem.id === itemId)
    return item ? [item] : []
  })
  return [{ id: definition.id, label: definition.label, items }]
}))

// 菜单配置
const menuItems = [
  { key: 'account', label: '系统账号', icon: markRaw(IconUser) },
  { key: 'menu', label: '菜单管理', icon: markRaw(IconTooling) },
  { key: 'ai', label: 'AI 服务配置', icon: markRaw(IconRobot) },
  { key: 'prompt', label: 'AI客服配置', icon: markRaw(IconChat) },
  { key: 'email', label: '邮箱通知', icon: markRaw(IconMail) },
  { key: 'backup', label: '备份与恢复', icon: markRaw(IconBackup) },
  { key: 'about', label: '关于', icon: markRaw(IconInfo) }
]

onMounted(async () => {
  loading.value = true
  try {
    const res = await getCurrentUser()
    if (res.code === 200 && res.data) {
      username.value = res.data.username || ''
      lastLoginTime.value = res.data.lastLoginTime || ''
      menuLayout.value = normalizeMenuLayout(res.data.menuLayout)
    }
  } catch (e) {
    console.error('获取用户信息失败:', e)
  } finally {
    loading.value = false
  }

  // 加载系统提示词配置
  try {
    const res = await getSetting({ settingKey: SYS_PROMPT_KEY })
    if (res.code === 200 && res.data) {
      sysPromptValue.value = res.data.settingValue || ''
      sysPromptLoaded.value = true
    }
  } catch (e) {
    console.error('获取系统提示词配置失败:', e)
  }

  // 加载相似度阈值配置
  try {
    const res = await getSetting({ settingKey: SIMILARITY_THRESHOLD_KEY })
    if (res.code === 200 && res.data && res.data.settingValue) {
      similarityThreshold.value = parseFloat(res.data.settingValue) || DEFAULT_SIMILARITY_THRESHOLD
    }
  } catch (e) {
    console.error('获取相似度阈值配置失败:', e)
  }

  // 加载 AI 配置
  await loadAIConfig()
  // 加载 Embedding 配置
  await loadEmbeddingConfig()
  // 加载 AI 商品图配置
  await loadImageConfig()
  // 加载 AI 状态
  await loadAIStatus()
  // 加载邮箱通知配置
  await loadEmailConfig()
})

async function loadAIConfig() {
  try {
    const [providerRes, protocolRes, customNameRes, apiKeyRes, baseUrlRes, modelRes] = await Promise.all([
      getSetting({ settingKey: AI_PROVIDER_SETTING }),
      getSetting({ settingKey: AI_PROTOCOL_SETTING }),
      getSetting({ settingKey: AI_CUSTOM_NAME_SETTING }),
      getSetting({ settingKey: AI_API_KEY_SETTING }),
      getSetting({ settingKey: AI_BASE_URL_SETTING }),
      getSetting({ settingKey: AI_MODEL_SETTING })
    ])

    const storedBaseUrl = baseUrlRes.code === 200 && baseUrlRes.data ? baseUrlRes.data.settingValue || '' : ''
    const storedModel = modelRes.code === 200 && modelRes.data ? modelRes.data.settingValue || '' : ''
    const storedProvider = providerRes.code === 200 && providerRes.data ? providerRes.data.settingValue || '' : ''
    aiProvider.value = AI_PROVIDERS.some(item => item.id === storedProvider)
      ? storedProvider
      : (storedBaseUrl || storedModel ? 'custom' : 'deepseek')
    const storedProtocol = protocolRes.code === 200 && protocolRes.data ? protocolRes.data.settingValue : ''
    aiProtocol.value = storedProtocol === 'anthropic' ? 'anthropic' : 'openai'
    if (!selectedAIProvider.value.protocols.includes(aiProtocol.value)) {
      aiProtocol.value = selectedAIProvider.value.protocols[0] || 'openai'
    }
    if (customNameRes.code === 200 && customNameRes.data) {
      aiCustomName.value = customNameRes.data.settingValue || ''
    }
    if (apiKeyRes.code === 200 && apiKeyRes.data) {
      aiApiKey.value = apiKeyRes.data.settingValue || ''
    }
    const preset = selectedAIProvider.value.defaults[aiProtocol.value]
    aiBaseUrl.value = storedBaseUrl || preset?.baseUrl || ''
    aiModel.value = storedModel || preset?.model || ''
    showAIAdvanced.value = aiProvider.value === 'custom'
  } catch (e) {
    console.error('获取AI配置失败:', e)
  }
}

async function loadEmbeddingConfig() {
  try {
    const [enabledRes, apiKeyRes, baseUrlRes, modelRes] = await Promise.all([
      getSetting({ settingKey: EMBEDDING_ENABLED_SETTING }),
      getSetting({ settingKey: EMBEDDING_API_KEY_SETTING }),
      getSetting({ settingKey: EMBEDDING_BASE_URL_SETTING }),
      getSetting({ settingKey: EMBEDDING_MODEL_SETTING })
    ])

    if (apiKeyRes.code === 200 && apiKeyRes.data) {
      embeddingApiKey.value = apiKeyRes.data.settingValue || ''
    }
    if (baseUrlRes.code === 200 && baseUrlRes.data && baseUrlRes.data.settingValue) {
      embeddingBaseUrl.value = baseUrlRes.data.settingValue
    }
    if (modelRes.code === 200 && modelRes.data && modelRes.data.settingValue) {
      embeddingModel.value = modelRes.data.settingValue
    }
    const enabledValue = enabledRes.code === 200 && enabledRes.data ? enabledRes.data.settingValue : ''
    embeddingEnabled.value = enabledValue
      ? enabledValue === '1' || enabledValue === 'true'
      : !!embeddingApiKey.value
  } catch (e) {
    console.error('获取Embedding配置失败:', e)
  }
}

async function loadImageConfig() {
  try {
    const [enabledRes, apiKeyRes, baseUrlRes, modelRes] = await Promise.all([
      getSetting({ settingKey: IMAGE_ENABLED_SETTING }),
      getSetting({ settingKey: IMAGE_API_KEY_SETTING }),
      getSetting({ settingKey: IMAGE_BASE_URL_SETTING }),
      getSetting({ settingKey: IMAGE_MODEL_SETTING })
    ])

    const enabledValue = enabledRes.code === 200 && enabledRes.data ? enabledRes.data.settingValue : ''
    imageEnabled.value = enabledValue === '1' || enabledValue === 'true'
    if (apiKeyRes.code === 200 && apiKeyRes.data) imageApiKey.value = apiKeyRes.data.settingValue || ''
    if (baseUrlRes.code === 200 && baseUrlRes.data && baseUrlRes.data.settingValue) imageBaseUrl.value = baseUrlRes.data.settingValue
    if (modelRes.code === 200 && modelRes.data && modelRes.data.settingValue) imageModel.value = modelRes.data.settingValue
  } catch (e) {
    console.error('获取AI商品图配置失败:', e)
  }
}

async function loadAIStatus() {
  try {
    const res = await getAIStatus()
    const data = await res.json()
    if (data.code === 200 && data.data) {
      aiStatus.value = data.data
    }
  } catch (e) {
    console.error('获取AI状态失败:', e)
  }
}

function selectAIProvider(provider: AIProviderPreset) {
  aiProvider.value = provider.id
  if (!provider.protocols.includes(aiProtocol.value)) {
    aiProtocol.value = provider.protocols[0] || 'openai'
  }
  const preset = provider.defaults[aiProtocol.value]
  if (preset) {
    aiBaseUrl.value = preset.baseUrl
    aiModel.value = preset.model
  } else {
    aiBaseUrl.value = ''
    aiModel.value = ''
  }
  showAIAdvanced.value = provider.id === 'custom'
  aiTestResult.value = null
}

function selectAIProtocol(protocol: AIProtocol) {
  aiProtocol.value = protocol
  const preset = selectedAIProvider.value.defaults[protocol]
  if (preset) {
    aiBaseUrl.value = preset.baseUrl
    aiModel.value = preset.model
  }
  aiTestResult.value = null
}

function resolveAIEndpointPreview(baseUrl: string, protocol: AIProtocol): string {
  try {
    const url = new URL(baseUrl.trim())
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) return ''
    let path = url.pathname.replace(/\/+$/, '')
    const suffix = protocol === 'anthropic' ? '/messages' : '/chat/completions'
    if (!path) path = `/v1${suffix}`
    else if (!path.toLowerCase().endsWith(suffix)) {
      path += /\/v\d+(?:beta\d*)?$/i.test(path) || path.toLowerCase().endsWith('/openai')
        ? suffix
        : `/v1${suffix}`
    }
    return `${url.origin}${path}`
  } catch {
    return ''
  }
}

function isValidHttpUrl(value: string): boolean {
  try {
    const url = new URL(value.trim())
    return ['http:', 'https:'].includes(url.protocol)
      && !url.username && !url.password && !url.search && !url.hash
  } catch {
    return false
  }
}

async function handleTestAIConnection() {
  if (!aiApiKey.value.trim() || !aiBaseUrl.value.trim() || !aiModel.value.trim()) {
    toast.warning('请先填写 API Key、Base URL 和模型名称')
    return
  }
  if (!isValidHttpUrl(aiBaseUrl.value)) {
    toast.warning('API Base URL 格式不正确')
    return
  }
  if (!aiTestMessage.value.trim()) {
    toast.warning('请输入测试内容')
    return
  }

  aiTesting.value = true
  aiTestResult.value = null
  try {
    const response = await testAIConnection({
      provider: aiProvider.value,
      protocol: aiProtocol.value,
      customName: aiCustomName.value.trim(),
      apiKey: aiApiKey.value.trim(),
      baseUrl: aiBaseUrl.value.trim(),
      model: aiModel.value.trim(),
      message: aiTestMessage.value.trim()
    })
    const payload = await response.json()
    if (payload.code !== 200 || !payload.data) {
      throw new Error(payload.msg || '连接测试失败')
    }
    aiTestResult.value = payload.data
    if (payload.data.success) toast.success(`连接成功，延时 ${payload.data.latencyMs} ms`)
  } catch (e) {
    const message = e instanceof Error ? e.message : '连接测试失败'
    aiTestResult.value = {
      success: false,
      reply: '',
      latencyMs: 0,
      provider: selectedAIProvider.value.name,
      protocol: aiProtocol.value,
      model: aiModel.value,
      endpoint: aiEndpointPreview.value,
      message
    }
  } finally {
    aiTesting.value = false
  }
}

async function handleChangePassword() {
  if (!oldPassword.value) {
    toast.warning('请输入原密码')
    return
  }
  if (!newPassword.value || newPassword.value.length < 6) {
    toast.warning('新密码长度需在6-50之间')
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    toast.warning('两次密码不一致')
    return
  }
  changingPassword.value = true
  try {
    const res = await changePassword({
      oldPassword: oldPassword.value,
      newPassword: newPassword.value,
      confirmPassword: confirmPassword.value
    })
    if (res.code === 200) {
      toast.success('密码修改成功')
      showPasswordForm.value = false
      oldPassword.value = ''
      newPassword.value = ''
      confirmPassword.value = ''
    }
  } finally {
    changingPassword.value = false
  }
}

async function handleLogout() {
  try {
    await showConfirm(
      '确定要退出登录吗？',
      '退出确认'
    )

    loggingOut.value = true
    try {
      await logout()
      clearAuthToken()
      toast.success('已退出登录')
      router.push('/login')
    } catch (e) {
      console.error('退出登录失败:', e)
      // 即使接口失败，也清除本地token并跳转
      clearAuthToken()
      router.push('/login')
    } finally {
      loggingOut.value = false
    }
  } catch {
    // 用户取消
  }
}

async function handleSaveSysPrompt() {
  if (!sysPromptValue.value.trim()) {
    toast.warning('系统提示词不能为空')
    return
  }
  sysPromptSaving.value = true
  try {
    const res = await saveSetting({
      settingKey: SYS_PROMPT_KEY,
      settingValue: sysPromptValue.value,
      settingDesc: 'AI智能回复的系统提示词'
    })
    if (res.code === 200) {
      toast.success('系统提示词保存成功')
      sysPromptLoaded.value = true
    }
  } finally {
    sysPromptSaving.value = false
  }
}

function handleResetSysPrompt() {
  sysPromptValue.value = DEFAULT_SYS_PROMPT
}

async function handleSaveSimilarityThreshold() {
  if (similarityThreshold.value < 0 || similarityThreshold.value > 1) {
    toast.warning('相似度阈值必须在 0 到 1 之间')
    return
  }
  similarityThresholdSaving.value = true
  try {
    const res = await saveSetting({
      settingKey: SIMILARITY_THRESHOLD_KEY,
      settingValue: similarityThreshold.value.toString(),
      settingDesc: 'RAG向量搜索的相似度阈值（0-1之间，值越小匹配越宽松）'
    })
    if (res.code === 200) {
      toast.success('相似度阈值保存成功')
    }
  } catch (e) {
    console.error('保存相似度阈值失败:', e)
    toast.error('保存相似度阈值失败')
  } finally {
    similarityThresholdSaving.value = false
  }
}

function handleResetSimilarityThreshold() {
  similarityThreshold.value = DEFAULT_SIMILARITY_THRESHOLD
}

async function handleSaveAIConfig() {
  if (!aiApiKey.value.trim()) {
    toast.warning('API Key 不能为空')
    return
  }
  if (!aiBaseUrl.value.trim()) {
    toast.warning('API Base URL 不能为空')
    return
  }
  if (!aiModel.value.trim()) {
    toast.warning('模型名称不能为空')
    return
  }
  if (!isValidHttpUrl(aiBaseUrl.value)) {
    toast.warning('API Base URL 格式不正确')
    return
  }

  aiApiKeySaving.value = true
  try {
    const results = await Promise.all([
      saveSetting({
        settingKey: AI_PROVIDER_SETTING,
        settingValue: aiProvider.value,
        settingDesc: 'AI服务提供商'
      }),
      saveSetting({
        settingKey: AI_PROTOCOL_SETTING,
        settingValue: aiProtocol.value,
        settingDesc: 'AI服务接口协议'
      }),
      saveSetting({
        settingKey: AI_CUSTOM_NAME_SETTING,
        settingValue: aiCustomName.value.trim(),
        settingDesc: '第三方中转站名称'
      }),
      saveSetting({
        settingKey: AI_API_KEY_SETTING,
        settingValue: aiApiKey.value.trim(),
        settingDesc: 'AI服务的API Key（配置后立即生效，无需重启）'
      }),
      saveSetting({
        settingKey: AI_BASE_URL_SETTING,
        settingValue: aiBaseUrl.value.trim(),
        settingDesc: 'AI服务的API Base URL'
      }),
      saveSetting({
        settingKey: AI_MODEL_SETTING,
        settingValue: aiModel.value.trim(),
        settingDesc: 'AI对话模型名称'
      })
    ])

    if (results.every(item => item.code === 200)) {
      toast.success('AI 配置保存成功，已立即生效')
      await loadAIStatus()
    }
  } catch (e) {
    console.error('保存AI配置失败:', e)
    toast.error('保存AI配置失败')
  } finally {
    aiApiKeySaving.value = false
  }
}

function handleResetAIConfig() {
  aiApiKey.value = ''
  selectAIProvider(AI_PROVIDERS.find(item => item.id === 'deepseek') || AI_PROVIDERS[0]!)
  aiCustomName.value = ''
}

async function handleSaveEmbeddingConfig() {
  if (embeddingEnabled.value) {
    if (!embeddingApiKey.value.trim() || !embeddingBaseUrl.value.trim() || !embeddingModel.value.trim()) {
      toast.warning('启用 Embedding 后需要填写 API Key、Base URL 和模型名称')
      return
    }
    if (!isValidHttpUrl(embeddingBaseUrl.value)) {
      toast.warning('Embedding Base URL 格式不正确')
      return
    }
  }
  embeddingSaving.value = true
  try {
    const requests = [saveSetting({
      settingKey: EMBEDDING_ENABLED_SETTING,
      settingValue: embeddingEnabled.value ? 'true' : 'false',
      settingDesc: '是否启用Embedding向量模型'
    })]
    if (embeddingEnabled.value) {
      requests.push(
        saveSetting({ settingKey: EMBEDDING_API_KEY_SETTING, settingValue: embeddingApiKey.value.trim(), settingDesc: 'Embedding模型API Key' }),
        saveSetting({ settingKey: EMBEDDING_BASE_URL_SETTING, settingValue: embeddingBaseUrl.value.trim(), settingDesc: 'Embedding模型API Base URL' }),
        saveSetting({ settingKey: EMBEDDING_MODEL_SETTING, settingValue: embeddingModel.value.trim(), settingDesc: 'Embedding模型名称' })
      )
    }
    const results = await Promise.all(requests)

    if (results.every(item => item.code === 200)) {
      toast.success('Embedding 配置保存成功，已立即生效')
    }
  } catch (e) {
    console.error('保存Embedding配置失败:', e)
    toast.error('保存Embedding配置失败')
  } finally {
    embeddingSaving.value = false
  }
}

function handleResetEmbeddingConfig() {
  embeddingEnabled.value = false
}

async function handleSaveImageConfig() {
  if (imageEnabled.value) {
    if (!imageApiKey.value.trim() || !imageBaseUrl.value.trim() || !imageModel.value.trim()) {
      toast.warning('启用 AI 商品图后需要填写 API Key、Base URL 和模型名称')
      return
    }
    if (!isValidHttpUrl(imageBaseUrl.value)) {
      toast.warning('商品图 Base URL 格式不正确')
      return
    }
  }
  imageSaving.value = true
  try {
    const requests = [saveSetting({ settingKey: IMAGE_ENABLED_SETTING, settingValue: imageEnabled.value ? 'true' : 'false', settingDesc: '是否启用AI商品图' })]
    if (imageEnabled.value) {
      requests.push(
        saveSetting({ settingKey: IMAGE_API_KEY_SETTING, settingValue: imageApiKey.value.trim(), settingDesc: 'AI商品图API Key' }),
        saveSetting({ settingKey: IMAGE_BASE_URL_SETTING, settingValue: imageBaseUrl.value.trim(), settingDesc: 'AI商品图API Base URL' }),
        saveSetting({ settingKey: IMAGE_MODEL_SETTING, settingValue: imageModel.value.trim(), settingDesc: 'AI商品图生成模型名称' })
      )
    }
    const results = await Promise.all(requests)
    if (results.every(item => item.code === 200)) toast.success('AI 商品图配置保存成功')
  } catch (e) {
    console.error('保存AI商品图配置失败:', e)
    toast.error('保存AI商品图配置失败')
  } finally {
    imageSaving.value = false
  }
}

function handleResetImageConfig() {
  imageEnabled.value = false
}

async function loadEmailConfig() {
  try {
    const [hostRes, portRes, userRes, passRes, fromRes, sslRes, wsDisconnectRes, cookieExpireRes] = await Promise.all([
      getSetting({ settingKey: EMAIL_SMTP_HOST_KEY }),
      getSetting({ settingKey: EMAIL_SMTP_PORT_KEY }),
      getSetting({ settingKey: EMAIL_SMTP_USERNAME_KEY }),
      getSetting({ settingKey: EMAIL_SMTP_PASSWORD_KEY }),
      getSetting({ settingKey: EMAIL_SMTP_FROM_KEY }),
      getSetting({ settingKey: EMAIL_SMTP_SSL_KEY }),
      getSetting({ settingKey: EMAIL_WS_DISCONNECT_NOTIFY_KEY }),
      getSetting({ settingKey: EMAIL_COOKIE_EXPIRE_NOTIFY_KEY })
    ])

    if (hostRes.code === 200 && hostRes.data) emailSmtpHost.value = hostRes.data.settingValue || ''
    if (portRes.code === 200 && portRes.data && portRes.data.settingValue) emailSmtpPort.value = portRes.data.settingValue
    if (userRes.code === 200 && userRes.data) emailSmtpUsername.value = userRes.data.settingValue || ''
    if (passRes.code === 200 && passRes.data) emailSmtpPassword.value = passRes.data.settingValue || ''
    if (fromRes.code === 200 && fromRes.data) emailSmtpFrom.value = fromRes.data.settingValue || ''
    if (sslRes.code === 200 && sslRes.data && sslRes.data.settingValue !== undefined) {
      emailSmtpSsl.value = sslRes.data.settingValue === '1' || sslRes.data.settingValue === 'true'
    } else {
      emailSmtpSsl.value = true
    }
    if (wsDisconnectRes.code === 200 && wsDisconnectRes.data && wsDisconnectRes.data.settingValue) {
      wsDisconnectNotifyEnabled.value = wsDisconnectRes.data.settingValue === '1' || wsDisconnectRes.data.settingValue === 'true'
    }
    if (cookieExpireRes.code === 200 && cookieExpireRes.data && cookieExpireRes.data.settingValue) {
      cookieExpireNotifyEnabled.value = cookieExpireRes.data.settingValue === '1' || cookieExpireRes.data.settingValue === 'true'
    }

    emailConfigured.value = !!(emailSmtpHost.value && emailSmtpPort.value && emailSmtpUsername.value && emailSmtpPassword.value && emailSmtpFrom.value)
    emailConfigExpanded.value = !emailConfigured.value
  } catch (e) {
    console.error('加载邮箱配置失败:', e)
  }
}

async function handleSaveEmailConfig() {
  emailSaving.value = true
  try {
    const [hostRes, portRes, userRes, passRes, fromRes, sslRes] = await Promise.all([
      saveSetting({ settingKey: EMAIL_SMTP_HOST_KEY, settingValue: emailSmtpHost.value.trim(), settingDesc: 'SMTP服务器地址' }),
      saveSetting({ settingKey: EMAIL_SMTP_PORT_KEY, settingValue: emailSmtpPort.value.trim(), settingDesc: 'SMTP服务器端口' }),
      saveSetting({ settingKey: EMAIL_SMTP_USERNAME_KEY, settingValue: emailSmtpUsername.value.trim(), settingDesc: 'SMTP登录用户名' }),
      saveSetting({ settingKey: EMAIL_SMTP_PASSWORD_KEY, settingValue: emailSmtpPassword.value.trim(), settingDesc: 'SMTP登录密码/授权码' }),
      saveSetting({ settingKey: EMAIL_SMTP_FROM_KEY, settingValue: emailSmtpFrom.value.trim(), settingDesc: '接收通知的收件人邮箱地址' }),
      saveSetting({ settingKey: EMAIL_SMTP_SSL_KEY, settingValue: emailSmtpSsl.value ? '1' : '0', settingDesc: '是否启用SSL（1启用，0关闭）' })
    ])

    if (hostRes.code === 200 && portRes.code === 200 && userRes.code === 200 && passRes.code === 200 && fromRes.code === 200 && sslRes.code === 200) {
      toast.success('邮箱配置保存成功')
      emailConfigured.value = !!(emailSmtpHost.value && emailSmtpPort.value && emailSmtpUsername.value && emailSmtpPassword.value && emailSmtpFrom.value)
      emailConfigExpanded.value = !emailConfigured.value
    }
  } catch (e) {
    console.error('保存邮箱配置失败:', e)
    toast.error('保存邮箱配置失败')
  } finally {
    emailSaving.value = false
  }
}

async function handleSaveWsDisconnectNotify() {
  try {
    const res = await saveSetting({
      settingKey: EMAIL_WS_DISCONNECT_NOTIFY_KEY,
      settingValue: wsDisconnectNotifyEnabled.value ? '1' : '0',
      settingDesc: 'WebSocket断开且无法重连时邮箱通知开关（1启用，0关闭）'
    })
    if (res.code === 200) {
      toast.success(`闲鱼账号消息监听掉线通知已${wsDisconnectNotifyEnabled.value ? '开启' : '关闭'}`)
    }
  } catch (e) {
    console.error('保存通知开关失败:', e)
    toast.error('保存失败')
  }
}

async function handleSaveCookieExpireNotify() {
  try {
    const res = await saveSetting({
      settingKey: EMAIL_COOKIE_EXPIRE_NOTIFY_KEY,
      settingValue: cookieExpireNotifyEnabled.value ? '1' : '0',
      settingDesc: 'Cookie过期且无法续期时邮箱通知开关（1启用，0关闭）'
    })
    if (res.code === 200) {
      toast.success(`Cookie过期通知已${cookieExpireNotifyEnabled.value ? '开启' : '关闭'}`)
    }
  } catch (e) {
    console.error('保存通知开关失败:', e)
    toast.error('保存失败')
  }
}

async function handleTestEmail() {
  emailTesting.value = true
  try {
    const res = await testEmail()
    if (res.code === 200) {
      toast.success('测试邮件发送成功，请检查收件箱')
    } else {
      toast.error(res.msg || '测试邮件发送失败')
    }
  } catch (e: any) {
    console.error('测试邮箱失败:', e)
    toast.error(e.message || '测试邮箱失败')
  } finally {
    emailTesting.value = false
  }
}

// 备份与恢复
const backupModules = ref<BackupModule[]>([])
const backupSelectedModules = ref<string[]>([])
const backupLoaded = ref(false)
const backupExporting = ref(false)
const backupImporting = ref(false)
const backupExportProgress = ref(0)
const backupImportProgress = ref(0)

const logDates = ref<string[]>([])
const logSelectedDate = ref('')
const logDownloading = ref(false)
const logDatesLoaded = ref(false)

async function loadBackupModules() {
  if (backupLoaded.value) return
  try {
    const res = await getBackupModules()
    if (res.code === 200 && res.data) {
      backupModules.value = res.data
      backupSelectedModules.value = res.data.map((m: BackupModule) => m.moduleKey)
      backupLoaded.value = true
    }
  } catch (e) {
    console.error('获取备份模块列表失败:', e)
  }
}

async function loadLogDates() {
  if (logDatesLoaded.value) return
  try {
    const res = await getLogDates()
    if (res.code === 200 && res.data) {
      logDates.value = res.data
      const today = new Date().toISOString().slice(0, 10)
      logSelectedDate.value = res.data.includes(today) ? today : (res.data.length > 0 ? res.data[res.data.length - 1]! : '')
      logDatesLoaded.value = true
    }
  } catch (e) {
    console.error('获取日志日期列表失败:', e)
  }
}

async function handleDownloadLog() {
  if (!logSelectedDate.value) return
  logDownloading.value = true
  try {
    const blob = await downloadLog(logSelectedDate.value)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `logs-${logSelectedDate.value}.zip`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
  } catch (e) {
    console.error('下载日志失败:', e)
    toast.error('下载日志失败')
  } finally {
    setTimeout(() => { logDownloading.value = false }, 2000)
  }
}

function toggleBackupModule(key: string) {
  const idx = backupSelectedModules.value.indexOf(key)
  if (idx >= 0) {
    backupSelectedModules.value.splice(idx, 1)
  } else {
    backupSelectedModules.value.push(key)
  }
}

function toggleAllBackupModules() {
  if (backupSelectedModules.value.length === backupModules.value.length) {
    backupSelectedModules.value = []
  } else {
    backupSelectedModules.value = backupModules.value.map(m => m.moduleKey)
  }
}

async function handleExportBackup() {
  if (backupSelectedModules.value.length === 0) {
    toast.warning('请至少选择一个模块')
    return
  }
  backupExporting.value = true
  backupExportProgress.value = 0
  try {
    const total = backupSelectedModules.value.length
    const progressStep = 100 / total
    for (let i = 0; i < total; i++) {
      backupExportProgress.value = Math.round((i + 1) * progressStep)
      await new Promise(r => setTimeout(r, 100))
    }
    const res = await exportBackup({ modules: backupSelectedModules.value })
    if (res.code === 200 && res.data) {
      backupExportProgress.value = 100
      const jsonStr = res.data.jsonData
      const blob = new Blob([jsonStr], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      const now = new Date()
      const ts = `${now.getFullYear()}${String(now.getMonth()+1).padStart(2,'0')}${String(now.getDate()).padStart(2,'0')}_${String(now.getHours()).padStart(2,'0')}${String(now.getMinutes()).padStart(2,'0')}${String(now.getSeconds()).padStart(2,'0')}`
      a.download = `xianyu_backup_${ts}.json`
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      URL.revokeObjectURL(url)
      toast.success('备份导出成功')
    } else {
      toast.error(res.msg || '导出失败')
    }
  } catch (e: any) {
    console.error('导出备份失败:', e)
    toast.error(e.message || '导出失败')
  } finally {
    backupExporting.value = false
    backupExportProgress.value = 0
  }
}

const importFileInput = ref<HTMLInputElement | null>(null)
const importJsonData = ref('')
const importFileName = ref('')

function triggerImportFile() {
  importFileInput.value?.click()
}

function handleImportFileChange(e: Event) {
  const target = e.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return
  importFileName.value = file.name
  const reader = new FileReader()
  reader.onload = (ev) => {
    importJsonData.value = ev.target?.result as string
  }
  reader.readAsText(file)
}

async function handleImportBackup() {
  if (!importJsonData.value) {
    toast.warning('请先选择备份文件')
    return
  }
  if (backupSelectedModules.value.length === 0) {
    toast.warning('请至少选择一个模块')
    return
  }

  try {
    await showConfirm(
      '导入数据将覆盖当前选中模块的已有数据，是否继续？',
      '确认导入'
    )
  } catch {
    return
  }

  backupImporting.value = true
  backupImportProgress.value = 0
  try {
    const total = backupSelectedModules.value.length
    const progressStep = 100 / (total + 1)
    for (let i = 0; i < total; i++) {
      backupImportProgress.value = Math.round((i + 1) * progressStep)
      await new Promise(r => setTimeout(r, 100))
    }
    const res = await importBackup({ jsonData: importJsonData.value, modules: backupSelectedModules.value })
    if (res.code === 200 && res.data) {
      backupImportProgress.value = 100
      const result = res.data
      if (result.failedModules && result.failedModules.length > 0) {
        toast.warning(`导入完成：${result.successCount}/${result.totalCount} 成功，失败模块：${result.failedModules.join(', ')}`)
      } else {
        toast.success(`导入成功：${result.successCount} 个模块`)
      }
      importJsonData.value = ''
      importFileName.value = ''
      if (importFileInput.value) importFileInput.value.value = ''
    } else {
      toast.error(res.msg || '导入失败')
    }
  } catch (e: any) {
    console.error('导入备份失败:', e)
    toast.error(e.message || '导入失败')
  } finally {
    backupImporting.value = false
    backupImportProgress.value = 0
  }
}

function handleBackupMenuEnter() {
  loadBackupModules()
  loadLogDates()
}

function handleGroupDragStart(event: DragEvent, groupId: string) {
  draggedGroupId.value = groupId
  draggedItemGroupId.value = ''
  draggedItemId.value = ''
  lastGroupTargetId.value = groupId
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', `group:${groupId}`)
  }
}

function handleGroupDragEnter(targetId: string) {
  const sourceId = draggedGroupId.value
  if (!sourceId || sourceId === targetId || lastGroupTargetId.value === targetId) return
  lastGroupTargetId.value = targetId
  menuLayout.value = moveMenuGroup(menuLayout.value, sourceId, targetId)
}

function handleItemDragStart(event: DragEvent, groupId: string, itemId: string) {
  draggedGroupId.value = ''
  draggedItemGroupId.value = groupId
  draggedItemId.value = itemId
  lastItemTargetKey.value = `${groupId}:${itemId}`
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', `item:${groupId}:${itemId}`)
  }
}

function handleItemDragEnter(groupId: string, targetId: string) {
  if (draggedGroupId.value) {
    handleGroupDragEnter(groupId)
    return
  }
  const targetKey = `${groupId}:${targetId}`
  if (!draggedItemId.value || draggedItemGroupId.value !== groupId
    || draggedItemId.value === targetId || lastItemTargetKey.value === targetKey) {
    return
  }
  lastItemTargetKey.value = targetKey
  menuLayout.value = moveMenuItem(menuLayout.value, groupId, draggedItemId.value, targetId)
}

function clearMenuDragState() {
  draggedGroupId.value = ''
  draggedItemGroupId.value = ''
  draggedItemId.value = ''
  lastGroupTargetId.value = ''
  lastItemTargetKey.value = ''
}

function moveGroupByOffset(groupId: string, offset: number) {
  const groupIndex = menuLayout.value.groups.findIndex(group => group.id === groupId)
  const target = menuLayout.value.groups[groupIndex + offset]
  if (target) menuLayout.value = moveMenuGroup(menuLayout.value, groupId, target.id)
}

function moveItemByOffset(groupId: string, itemId: string, offset: number) {
  const group = menuLayout.value.groups.find(groupLayout => groupLayout.id === groupId)
  const itemIndex = group?.items.indexOf(itemId) ?? -1
  const targetId = group?.items[itemIndex + offset]
  if (targetId) menuLayout.value = moveMenuItem(menuLayout.value, groupId, itemId, targetId)
}

function restoreDefaultMenuLayout() {
  menuLayout.value = createDefaultMenuLayout()
  toast.info('已恢复默认排序草稿，保存后生效')
}

async function saveMenuLayout() {
  if (!canSaveMenuLayout.value) {
    toast.warning('当前账号没有修改系统设置的权限')
    return
  }
  menuLayoutSaving.value = true
  try {
    const settingValue = serializeMenuLayout(menuLayout.value)
    const response = await saveSetting({
      settingKey: MENU_LAYOUT_SETTING_KEY,
      settingValue,
      settingDesc: '租户菜单布局'
    })
    if (response.code === 200) {
      updateMenuLayout(settingValue)
      menuLayout.value = normalizeMenuLayout(settingValue)
      toast.success('菜单排序已保存')
    } else {
      toast.error(response.msg || '菜单排序保存失败')
    }
  } catch (e) {
    console.error('保存菜单排序失败:', e)
    toast.error('菜单排序保存失败')
  } finally {
    menuLayoutSaving.value = false
  }
}
</script>

<template>
  <div class="settings">
    <!-- 左侧菜单 -->
    <div class="settings__sidebar">
      <div class="settings__sidebar-title">设置</div>
      <div class="settings__menu">
        <div
          v-for="item in menuItems"
          :key="item.key"
          class="settings__menu-item"
          :class="{ 'settings__menu-item--active': activeMenu === item.key }"
          @click="activeMenu = item.key; item.key === 'backup' && handleBackupMenuEnter()"
        >
          <span class="settings__menu-icon"><component :is="item.icon" /></span>
          <span class="settings__menu-label">{{ item.label }}</span>
        </div>
      </div>
    </div>

    <!-- 右侧内容 -->
    <div class="settings__content">
      <!-- 系统账号（包含修改密码和退出登录） -->
      <div v-if="activeMenu === 'account'" class="settings__panel">
        <div class="settings__panel-title">系统账号</div>
        <div v-if="loading" class="settings__loading">
          <div class="settings__spinner"></div>
          <span>加载中...</span>
        </div>
        <div v-else class="settings__info">
          <div class="settings__info-row">
            <span class="settings__info-label">账号</span>
            <span class="settings__info-value">{{ username }}</span>
          </div>
          <div class="settings__info-row">
            <span class="settings__info-label">最后登录时间</span>
            <span class="settings__info-value">{{ lastLoginTime || '-' }}</span>
          </div>
        </div>

        <!-- 修改密码 -->
        <div class="settings__section">
          <div class="settings__section-header">
            <div class="settings__section-title">修改密码</div>
            <button
              v-if="!showPasswordForm"
              class="settings__toggle-btn"
              @click="showPasswordForm = true"
            >
              修改
            </button>
          </div>
          <div v-if="showPasswordForm" class="settings__form">
            <div class="settings__field">
              <label class="settings__label">原密码</label>
              <div class="settings__input-wrap">
                <input
                  v-model="oldPassword"
                  :type="showOldPassword ? 'text' : 'password'"
                  class="settings__input"
                  placeholder="请输入原密码"
                  :disabled="changingPassword"
                />
                <button class="settings__eye-btn" @click="showOldPassword = !showOldPassword" tabindex="-1">
                  {{ showOldPassword ? '隐藏' : '显示' }}
                </button>
              </div>
            </div>

            <div class="settings__field">
              <label class="settings__label">新密码</label>
              <div class="settings__input-wrap">
                <input
                  v-model="newPassword"
                  :type="showNewPassword ? 'text' : 'password'"
                  class="settings__input"
                  placeholder="请输入新密码（6-50位）"
                  :disabled="changingPassword"
                />
                <button class="settings__eye-btn" @click="showNewPassword = !showNewPassword" tabindex="-1">
                  {{ showNewPassword ? '隐藏' : '显示' }}
                </button>
              </div>
            </div>

            <div class="settings__field">
              <label class="settings__label">确认新密码</label>
              <div class="settings__input-wrap">
                <input
                  v-model="confirmPassword"
                  :type="showConfirmPassword ? 'text' : 'password'"
                  class="settings__input"
                  placeholder="请再次输入新密码"
                  :disabled="changingPassword"
                  @keydown.enter="handleChangePassword"
                />
                <button class="settings__eye-btn" @click="showConfirmPassword = !showConfirmPassword" tabindex="-1">
                  {{ showConfirmPassword ? '隐藏' : '显示' }}
                </button>
              </div>
            </div>

            <div class="settings__actions">
              <button class="settings__btn settings__btn--secondary" :disabled="changingPassword" @click="showPasswordForm = false">
                取消
              </button>
              <button class="settings__btn settings__btn--primary" :disabled="changingPassword" @click="handleChangePassword">
                {{ changingPassword ? '请稍候...' : '确认修改' }}
              </button>
            </div>
          </div>
        </div>

        <!-- 退出登录 -->
        <div class="settings__section">
          <div class="settings__section-title">退出登录</div>
          <p class="settings__logout-text">退出当前系统账号，退出后需要重新登录</p>
          <button
            class="settings__btn settings__btn--danger"
            :disabled="loggingOut"
            @click="handleLogout"
          >
            {{ loggingOut ? '退出中...' : '退出登录' }}
          </button>
        </div>
      </div>

      <!-- AI 服务配置 -->
      <div v-if="activeMenu === 'ai'" class="settings__panel">
        <div class="settings__panel-title">AI 服务配置</div>

        <!-- AI 状态指示 -->
        <div class="settings__ai-overview">
          <div class="settings__ai-overview-main">
            <span class="settings__ai-status-dot" :class="{ 'settings__ai-status-dot--ok': aiStatus.available }"></span>
            <div>
              <div class="settings__ai-overview-title">{{ aiStatus.available ? 'AI 服务已就绪' : 'AI 服务未就绪' }}</div>
              <div class="settings__ai-status-msg">{{ aiStatus.message || '保存配置后立即生效' }}</div>
            </div>
            <span class="settings__ai-status-badge" :class="aiStatus.available ? 'settings__ai-status-badge--ok' : 'settings__ai-status-badge--off'">
              {{ aiStatus.available ? '可用' : '不可用' }}
            </span>
          </div>
          <div class="settings__ai-overview-meta">
            <div><span>提供商</span><strong>{{ aiStatusProviderName }}</strong></div>
            <div><span>协议</span><strong>{{ (aiStatus.protocol || aiProtocol).toUpperCase() }}</strong></div>
            <div><span>模型</span><strong>{{ aiStatus.model || aiModel || '未配置' }}</strong></div>
          </div>
        </div>

        <!-- AI 对话配置 -->
        <div class="settings__section settings__section--ai">
          <div class="settings__section-title">对话模型</div>
          <p class="settings__desc">选择官方服务时默认地址和模型已配置，只需填写 API Key；第三方中转站支持两种主流协议。</p>

          <div class="settings__provider-grid">
            <button
              v-for="provider in AI_PROVIDERS"
              :key="provider.id"
              class="settings__provider-card"
              :class="{ 'settings__provider-card--active': aiProvider === provider.id }"
              :disabled="aiApiKeySaving"
              @click="selectAIProvider(provider)"
            >
              <span class="settings__provider-icon" :class="`settings__provider-icon--${provider.tone}`">
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <g v-if="provider.id === 'openai'" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="12" r="3.2"/><path d="M12 2.8c2.2 0 4 1.8 4 4v1.1l1-.6a4 4 0 1 1 4 6.9l-1 .6v1.1a4 4 0 0 1-6 3.5l-1-.6-1 .6a4 4 0 0 1-6-3.5v-1.1l-1-.6a4 4 0 1 1 4-6.9l1 .6V6.8a4 4 0 0 1 4-4Z"/></g>
                  <g v-else-if="provider.id === 'anthropic'" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M5 19 10.7 5h2.6L19 19M7.5 14h9"/><path d="M16.5 5v5"/></g>
                  <g v-else-if="provider.id === 'deepseek'" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M3 13c3.2-5.8 9.1-7.3 14.7-4.5L21 7l-1.2 4c.4 5.2-4.5 8.7-9.5 7.8C6.7 18.2 4.2 16.3 3 13Z"/><circle cx="15.6" cy="11.2" r="1" fill="currentColor"/></g>
                  <g v-else-if="provider.id === 'qwen'" fill="none" stroke="currentColor" stroke-width="1.8"><path d="m12 3 2.1 5.2L20 9l-4.4 3.7 1.4 5.7-5-3-5 3 1.4-5.7L4 9l5.9-.8L12 3Z"/><circle cx="12" cy="11" r="2.2"/></g>
                  <g v-else-if="provider.id === 'gemini'" fill="currentColor"><path d="M12 2c.8 5.5 3.2 8.1 9 9-5.8.9-8.2 3.5-9 11-.8-7.5-3.2-10.1-9-11 5.8-.9 8.2-3.5 9-9Z"/></g>
                  <g v-else fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="6" cy="12" r="2.5"/><circle cx="18" cy="6" r="2.5"/><circle cx="18" cy="18" r="2.5"/><path d="m8.3 10.9 7.4-3.8M8.3 13.1l7.4 3.8"/></g>
                </svg>
              </span>
              <span class="settings__provider-copy"><strong>{{ provider.name }}</strong><small>{{ provider.description }}</small></span>
              <span v-if="aiProvider === provider.id" class="settings__provider-check">✓</span>
            </button>
          </div>

          <div class="settings__form settings__ai-form">
            <div v-if="aiProvider === 'custom'" class="settings__field">
              <label class="settings__label">中转站名称 <span class="settings__label-hint">用于区分当前第三方服务</span></label>
              <input v-model="aiCustomName" type="text" class="settings__input" placeholder="例如：备用中转站" :disabled="aiApiKeySaving" />
            </div>

            <div class="settings__field">
              <label class="settings__label">API Key</label>
              <div class="settings__input-wrap">
                <input v-model="aiApiKey" :type="showApiKey ? 'text' : 'password'" class="settings__input" placeholder="请输入 API Key" :disabled="aiApiKeySaving" />
                <button class="settings__eye-btn" @click="showApiKey = !showApiKey" tabindex="-1">{{ showApiKey ? '隐藏' : '显示' }}</button>
              </div>
            </div>

            <div class="settings__ai-preset-summary">
              <span>{{ aiProtocol === 'anthropic' ? 'Anthropic Messages' : 'OpenAI Chat Completions' }}</span>
              <strong>{{ aiModel || '待填写模型' }}</strong>
            </div>

            <button class="settings__advanced-trigger" @click="showAIAdvanced = !showAIAdvanced">
              <span>高级配置</span>
              <small>协议、Base URL、模型</small>
              <span>{{ showAIAdvanced ? '收起' : '展开' }}</span>
            </button>

            <div v-if="showAIAdvanced" class="settings__advanced-panel">
              <div class="settings__field">
                <label class="settings__label">接口协议</label>
                <div class="settings__protocol-tabs">
                  <button v-for="protocol in selectedAIProvider.protocols" :key="protocol" :class="{ active: aiProtocol === protocol }" @click="selectAIProtocol(protocol)">
                    {{ protocol === 'openai' ? 'OpenAI 协议' : 'Anthropic 协议' }}
                  </button>
                </div>
              </div>
              <div class="settings__field">
                <label class="settings__label">API Base URL</label>
                <input v-model="aiBaseUrl" type="text" class="settings__input" placeholder="支持根地址、/v1 或完整 endpoint" :disabled="aiApiKeySaving" />
              </div>
              <div class="settings__field">
                <label class="settings__label">模型名称</label>
                <input v-model="aiModel" type="text" class="settings__input" placeholder="请输入模型 ID" :disabled="aiApiKeySaving" />
              </div>
              <div class="settings__endpoint-preview">
                <span>最终请求地址</span>
                <code>{{ aiEndpointPreview || '请输入有效的 Base URL' }}</code>
              </div>
            </div>

            <div class="settings__actions">
              <button class="settings__btn settings__btn--secondary" :disabled="aiApiKeySaving" @click="handleResetAIConfig">恢复默认</button>
              <button class="settings__btn settings__btn--primary" :disabled="aiApiKeySaving" @click="handleSaveAIConfig">{{ aiApiKeySaving ? '保存中...' : '保存并立即生效' }}</button>
            </div>
          </div>
        </div>

        <!-- 连通性测试 -->
        <div class="settings__section settings__connection-test">
          <div class="settings__section-title">连通性测试</div>
          <p class="settings__desc">使用当前表单内容发送一次真实普通请求，无需先保存。</p>
          <div class="settings__test-layout">
            <div class="settings__field">
              <label class="settings__label">测试内容</label>
              <textarea v-model="aiTestMessage" class="settings__textarea" rows="3" placeholder="输入需要发送给模型的内容" :disabled="aiTesting"></textarea>
            </div>
            <button class="settings__test-button" :disabled="aiTesting" @click="handleTestAIConnection">
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m4 12 16-8-5.5 16-3.1-6.4L4 12Z" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"/></svg>
              {{ aiTesting ? '正在连接...' : '发送测试' }}
            </button>
          </div>
          <div v-if="aiTesting" class="settings__test-loading"><span></span><span></span><span></span>正在等待模型回复</div>
          <div v-else-if="aiTestResult" class="settings__test-result" :class="aiTestResult.success ? 'settings__test-result--ok' : 'settings__test-result--error'">
            <div class="settings__test-result-head">
              <div><strong>{{ aiTestResult.success ? '连接成功' : '连接失败' }}</strong><span>{{ aiTestResult.message }}</span></div>
              <div class="settings__latency"><strong>{{ aiTestResult.latencyMs }}</strong><span>ms</span></div>
            </div>
            <div v-if="aiTestResult.reply" class="settings__test-reply"><span>模型回复</span><p>{{ aiTestResult.reply }}</p></div>
            <div class="settings__test-meta">
              <span>{{ aiTestResult.protocol.toUpperCase() }}</span>
              <span>{{ aiTestResult.model }}</span>
              <code>{{ aiTestResult.endpoint }}</code>
            </div>
          </div>
        </div>

        <!-- Embedding 配置 -->
        <div class="settings__section">
          <div class="settings__section-header">
            <div><div class="settings__section-title">Embedding 向量模型</div><p class="settings__desc">仅用于 RAG 知识库，默认关闭且不复用对话配置。</p></div>
            <button class="settings__toggle-btn" @click="showEmbeddingConfig = !showEmbeddingConfig">{{ showEmbeddingConfig ? '收起' : '独立高级配置' }}</button>
          </div>
          <div v-if="showEmbeddingConfig" class="settings__form settings__advanced-panel">
            <div class="settings__capability-switch">
              <div><strong>启用 Embedding</strong><span>关闭后普通 AI 回复仍可正常使用</span></div>
              <label class="settings__switch"><input v-model="embeddingEnabled" type="checkbox" :disabled="embeddingSaving" /><span class="settings__switch-track"></span><span class="settings__switch-thumb"></span></label>
            </div>
            <template v-if="embeddingEnabled">
              <div class="settings__field"><label class="settings__label">API Key</label><div class="settings__input-wrap"><input v-model="embeddingApiKey" :type="showEmbeddingApiKey ? 'text' : 'password'" class="settings__input" placeholder="Embedding 专用 API Key" :disabled="embeddingSaving" /><button class="settings__eye-btn" @click="showEmbeddingApiKey = !showEmbeddingApiKey" tabindex="-1">{{ showEmbeddingApiKey ? '隐藏' : '显示' }}</button></div></div>
              <div class="settings__field"><label class="settings__label">API Base URL</label><input v-model="embeddingBaseUrl" type="text" class="settings__input" placeholder="Embedding 服务地址" :disabled="embeddingSaving" /></div>
              <div class="settings__field"><label class="settings__label">模型名称</label><input v-model="embeddingModel" type="text" class="settings__input" placeholder="例如 text-embedding-v3" :disabled="embeddingSaving" /></div>
            </template>
            <div class="settings__actions"><button class="settings__btn settings__btn--secondary" :disabled="embeddingSaving" @click="handleResetEmbeddingConfig">设为关闭</button><button class="settings__btn settings__btn--primary" :disabled="embeddingSaving" @click="handleSaveEmbeddingConfig">{{ embeddingSaving ? '保存中...' : '保存 Embedding 配置' }}</button></div>
          </div>
        </div>

        <!-- AI 商品图配置 -->
        <div class="settings__section">
          <div class="settings__section-header">
            <div><div class="settings__section-title">AI 商品图</div><p class="settings__desc">仅用于商机发掘中的图片生成，未使用时无需配置模型。</p></div>
            <button class="settings__toggle-btn" @click="showImageConfig = !showImageConfig">{{ showImageConfig ? '收起' : '独立高级配置' }}</button>
          </div>
          <div v-if="showImageConfig" class="settings__form settings__advanced-panel">
            <div class="settings__capability-switch">
              <div><strong>启用 AI 商品图</strong><span>使用独立的 OpenAI-compatible 图片接口</span></div>
              <label class="settings__switch"><input v-model="imageEnabled" type="checkbox" :disabled="imageSaving" /><span class="settings__switch-track"></span><span class="settings__switch-thumb"></span></label>
            </div>
            <template v-if="imageEnabled">
              <div class="settings__field"><label class="settings__label">API Key</label><div class="settings__input-wrap"><input v-model="imageApiKey" :type="showImageApiKey ? 'text' : 'password'" class="settings__input" placeholder="商品图专用 API Key" :disabled="imageSaving" /><button class="settings__eye-btn" @click="showImageApiKey = !showImageApiKey" tabindex="-1">{{ showImageApiKey ? '隐藏' : '显示' }}</button></div></div>
              <div class="settings__field"><label class="settings__label">API Base URL</label><input v-model="imageBaseUrl" type="text" class="settings__input" placeholder="图片生成服务地址" :disabled="imageSaving" /></div>
              <div class="settings__field"><label class="settings__label">模型名称</label><input v-model="imageModel" type="text" class="settings__input" placeholder="例如 wanx2.1-t2i-turbo" :disabled="imageSaving" /></div>
            </template>
            <div class="settings__actions"><button class="settings__btn settings__btn--secondary" :disabled="imageSaving" @click="handleResetImageConfig">设为关闭</button><button class="settings__btn settings__btn--primary" :disabled="imageSaving" @click="handleSaveImageConfig">{{ imageSaving ? '保存中...' : '保存商品图配置' }}</button></div>
          </div>
        </div>
      </div>

      <!-- AI客服配置 -->
      <div v-if="activeMenu === 'prompt'" class="settings__panel">
        <div class="settings__panel-title">AI客服配置</div>

        <!-- 系统提示词 -->
        <div class="settings__section">
          <div class="settings__section-title">系统提示词</div>
          <p class="settings__desc">配置 AI 智能回复的系统提示词，用于设定 AI 的角色和行为规则</p>
          <div class="settings__form">
            <textarea
              v-model="sysPromptValue"
              class="settings__textarea"
              placeholder="请输入系统提示词"
              :disabled="sysPromptSaving"
              rows="8"
            ></textarea>
            <div class="settings__actions">
              <button
                class="settings__btn settings__btn--secondary"
                :disabled="sysPromptSaving"
                @click="handleResetSysPrompt"
              >
                恢复默认
              </button>
              <button
                class="settings__btn settings__btn--primary"
                :disabled="sysPromptSaving"
                @click="handleSaveSysPrompt"
              >
                {{ sysPromptSaving ? '保存中...' : '保存' }}
              </button>
            </div>
          </div>
        </div>

        <!-- 相似度阈值 -->
        <div class="settings__section">
          <div class="settings__section-title">相似度阈值</div>
          <p class="settings__desc">
            配置 RAG 向量搜索的相似度阈值。值越小，匹配越宽松，会返回更多相关度较低的结果；值越大，匹配越严格，只返回高度相关的结果。
          </p>
          <div class="settings__form">
            <div class="settings__field">
              <label class="settings__label">相似度阈值 (0-1)</label>
              <input
                v-model.number="similarityThreshold"
                type="number"
                class="settings__input"
                placeholder="0.1"
                :disabled="similarityThresholdSaving"
                min="0"
                max="1"
                step="0.01"
              />
              <p class="settings__hint">推荐值：0.1（宽松）到 0.5（严格）之间</p>
            </div>
            <div class="settings__actions">
              <button
                class="settings__btn settings__btn--secondary"
                :disabled="similarityThresholdSaving"
                @click="handleResetSimilarityThreshold"
              >
                恢复默认
              </button>
              <button
                class="settings__btn settings__btn--primary"
                :disabled="similarityThresholdSaving"
                @click="handleSaveSimilarityThreshold"
              >
                {{ similarityThresholdSaving ? '保存中...' : '保存' }}
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- 邮箱通知 -->
      <div v-if="activeMenu === 'email'" class="settings__panel">
        <div class="settings__panel-title">邮箱通知</div>

        <!-- 邮箱配置 -->
        <div class="settings__section">
          <div class="settings__section-header">
            <div class="settings__section-title">邮箱配置</div>
            <div class="settings__section-header-actions">
              <button
                v-if="emailConfigured"
                class="settings__toggle-btn settings__toggle-btn--test"
                :disabled="emailTesting"
                @click="handleTestEmail"
              >
                {{ emailTesting ? '发送中...' : '发送测试邮件' }}
              </button>
              <span v-if="emailConfigured" class="settings__status-badge" :class="emailConfigured ? 'settings__status-badge--success' : ''">
                {{ emailConfigured ? '已配置' : '未配置' }}
              </span>
              <button
                v-if="emailConfigured"
                class="settings__toggle-btn"
                @click="emailConfigExpanded = !emailConfigExpanded"
              >
                {{ emailConfigExpanded ? '收起' : '展开' }}
              </button>
            </div>
          </div>
          <p class="settings__desc">配置SMTP邮箱服务，用于接收系统通知（如滑块验证提醒）。发件人固定使用SMTP用户名，收件人邮箱用于接收通知。</p>

          <div v-if="emailConfigExpanded || !emailConfigured" class="settings__form">
            <div class="settings__field">
              <label class="settings__label">SMTP 服务器</label>
              <input
                v-model="emailSmtpHost"
                type="text"
                class="settings__input"
                placeholder="如 smtp.qq.com"
                :disabled="emailSaving"
              />
            </div>
            <div class="settings__field">
              <label class="settings__label">SMTP 端口</label>
              <input
                v-model="emailSmtpPort"
                type="text"
                class="settings__input"
                placeholder="465"
                :disabled="emailSaving"
              />
            </div>
            <div class="settings__field">
              <label class="settings__label">用户名</label>
              <input
                v-model="emailSmtpUsername"
                type="text"
                class="settings__input"
                placeholder="邮箱账号"
                :disabled="emailSaving"
              />
            </div>
            <div class="settings__field">
              <label class="settings__label">密码/授权码</label>
              <div class="settings__input-wrap">
                <input
                  v-model="emailSmtpPassword"
                  :type="showEmailPassword ? 'text' : 'password'"
                  class="settings__input"
                  placeholder="邮箱密码或SMTP授权码"
                  :disabled="emailSaving"
                />
                <button class="settings__eye-btn" @click="showEmailPassword = !showEmailPassword" tabindex="-1">
                  {{ showEmailPassword ? '隐藏' : '显示' }}
                </button>
              </div>
              <p class="settings__hint">QQ邮箱需使用授权码，非登录密码</p>
            </div>
            <div class="settings__field">
              <label class="settings__label">收件人邮箱</label>
              <input
                v-model="emailSmtpFrom"
                type="text"
                class="settings__input"
                placeholder="接收通知的邮箱地址"
                :disabled="emailSaving"
              />
            </div>
            <div class="settings__field">
              <label class="settings__label">启用 SSL</label>
              <label class="settings__switch">
                <input type="checkbox" v-model="emailSmtpSsl" :disabled="emailSaving" />
                <span class="settings__switch-track"></span>
                <span class="settings__switch-thumb"></span>
              </label>
            </div>
            <div class="settings__actions">
              <button
                class="settings__btn settings__btn--primary"
                :disabled="emailSaving"
                @click="handleSaveEmailConfig"
              >
                {{ emailSaving ? '保存中...' : '保存' }}
              </button>
            </div>
          </div>
        </div>

        <!-- 通知开关 -->
        <div class="settings__section">
          <div class="settings__section-title">通知开关</div>
          <p class="settings__desc">选择哪些事件需要通过邮件通知您</p>

          <table class="settings__notify-table">
            <thead>
              <tr>
                <th class="settings__notify-th">通知类型</th>
                <th class="settings__notify-th">说明</th>
                <th class="settings__notify-th">状态</th>
              </tr>
            </thead>
            <tbody>
              <tr class="settings__notify-tr">
                <td class="settings__notify-td">闲鱼账号消息监听掉线通知</td>
                <td class="settings__notify-td settings__notify-td--desc">WebSocket连接失败时发送邮件通知</td>
                <td class="settings__notify-td">
                  <label class="settings__switch">
                    <input
                      type="checkbox"
                      v-model="wsDisconnectNotifyEnabled"
                      :disabled="!emailConfigured"
                      @change="handleSaveWsDisconnectNotify"
                    />
                    <span class="settings__switch-track"></span>
                    <span class="settings__switch-thumb"></span>
                  </label>
                </td>
              </tr>
              <tr class="settings__notify-tr">
                <td class="settings__notify-td">Cookie过期通知</td>
                <td class="settings__notify-td settings__notify-td--desc">Cookie过期且无法自动续期时发送邮件通知</td>
                <td class="settings__notify-td">
                  <label class="settings__switch">
                    <input
                      type="checkbox"
                      v-model="cookieExpireNotifyEnabled"
                      :disabled="!emailConfigured"
                      @change="handleSaveCookieExpireNotify"
                    />
                    <span class="settings__switch-track"></span>
                    <span class="settings__switch-thumb"></span>
                  </label>
                </td>
              </tr>
            </tbody>
          </table>
          <p v-if="!emailConfigured" class="settings__hint" style="color:#e6a23c;margin-top:8px;">请先配置邮箱后再开启通知</p>
        </div>
      </div>

      <!-- 菜单管理 -->
      <div v-if="activeMenu === 'menu'" class="settings__panel">
        <div class="settings__panel-title">菜单管理</div>
        <p class="settings__desc">拖动模块或模块内菜单调整顺序，保存后当前租户的所有终端同步生效。</p>
        <div v-if="!canSaveMenuLayout" class="settings__menu-sort-notice">当前账号可查看排序，但没有保存系统设置的权限。</div>

        <TransitionGroup name="menu-sort" tag="div" class="settings__menu-sort-groups">
          <section
            v-for="(group, groupIndex) in editableMenuGroups"
            :key="group.id"
            class="settings__menu-sort-group"
            :class="{ 'settings__menu-sort-group--dragging': draggedGroupId === group.id }"
            @dragenter.prevent="handleGroupDragEnter(group.id)"
            @dragover.prevent
            @drop.prevent="clearMenuDragState"
          >
            <div class="settings__menu-sort-group-header">
              <button
                type="button"
                class="settings__menu-sort-handle"
                draggable="true"
                :aria-label="`拖动${group.label}模块`"
                @dragstart="handleGroupDragStart($event, group.id)"
                @dragend="clearMenuDragState"
              >
                <span></span><span></span><span></span><span></span><span></span><span></span>
              </button>
              <div class="settings__menu-sort-group-name">
                <strong>{{ group.label }}</strong>
                <small>{{ group.items.length }} 个菜单</small>
              </div>
              <div class="settings__menu-sort-controls">
                <button
                  type="button"
                  :disabled="groupIndex === 0"
                  :aria-label="`${group.label}上移`"
                  @click.stop="moveGroupByOffset(group.id, -1)"
                >↑</button>
                <button
                  type="button"
                  :disabled="groupIndex === editableMenuGroups.length - 1"
                  :aria-label="`${group.label}下移`"
                  @click.stop="moveGroupByOffset(group.id, 1)"
                >↓</button>
              </div>
            </div>

            <TransitionGroup name="menu-sort" tag="div" class="settings__menu-sort-items">
              <div
                v-for="(item, itemIndex) in group.items"
                :key="item.id"
                class="settings__menu-sort-item"
                :class="{ 'settings__menu-sort-item--dragging': draggedItemGroupId === group.id && draggedItemId === item.id }"
                @dragenter.stop.prevent="handleItemDragEnter(group.id, item.id)"
                @dragover.stop.prevent
                @drop.stop.prevent="clearMenuDragState"
              >
                <button
                  type="button"
                  class="settings__menu-sort-handle settings__menu-sort-handle--item"
                  draggable="true"
                  :aria-label="`拖动${item.label}菜单`"
                  @dragstart.stop="handleItemDragStart($event, group.id, item.id)"
                  @dragend.stop="clearMenuDragState"
                >
                  <span></span><span></span><span></span><span></span><span></span><span></span>
                </button>
                <span class="settings__menu-sort-item-name">{{ item.label }}</span>
                <span class="settings__menu-sort-item-path">{{ item.path }}</span>
                <div class="settings__menu-sort-controls settings__menu-sort-controls--item">
                  <button
                    type="button"
                    :disabled="itemIndex === 0"
                    :aria-label="`${item.label}上移`"
                    @click.stop="moveItemByOffset(group.id, item.id, -1)"
                  >↑</button>
                  <button
                    type="button"
                    :disabled="itemIndex === group.items.length - 1"
                    :aria-label="`${item.label}下移`"
                    @click.stop="moveItemByOffset(group.id, item.id, 1)"
                  >↓</button>
                </div>
              </div>
            </TransitionGroup>
          </section>
        </TransitionGroup>

        <div class="settings__actions settings__menu-sort-actions">
          <button
            type="button"
            class="settings__btn settings__btn--secondary"
            :disabled="menuLayoutSaving"
            @click="restoreDefaultMenuLayout"
          >
            恢复默认
          </button>
          <button
            type="button"
            class="settings__btn settings__btn--primary"
            :disabled="menuLayoutSaving || !canSaveMenuLayout"
            @click="saveMenuLayout"
          >
            {{ menuLayoutSaving ? '保存中...' : '保存排序' }}
          </button>
        </div>
      </div>

      <!-- 备份与恢复 -->
      <div v-if="activeMenu === 'backup'" class="settings__panel">
        <div class="settings__panel-title">备份与恢复</div>

        <div class="settings__section">
          <div class="settings__section-title">选择备份模块</div>
          <p class="settings__desc">选择需要导出或导入的数据模块，默认全部选择</p>
          <div class="settings__backup-modules">
            <div class="settings__backup-module-all">
              <label class="settings__checkbox-label" @click.prevent="toggleAllBackupModules">
                <span class="settings__checkbox" :class="{ 'settings__checkbox--checked': backupSelectedModules.length === backupModules.length && backupModules.length > 0 }">
                  <span v-if="backupSelectedModules.length === backupModules.length && backupModules.length > 0" class="settings__checkbox-tick">✓</span>
                </span>
                全选
              </label>
            </div>
            <div v-for="mod in backupModules" :key="mod.moduleKey" class="settings__backup-module-item">
              <label class="settings__checkbox-label" @click.prevent="toggleBackupModule(mod.moduleKey)">
                <span class="settings__checkbox" :class="{ 'settings__checkbox--checked': backupSelectedModules.includes(mod.moduleKey) }">
                  <span v-if="backupSelectedModules.includes(mod.moduleKey)" class="settings__checkbox-tick">✓</span>
                </span>
                {{ mod.moduleName }}
              </label>
            </div>
          </div>
        </div>

        <div class="settings__section">
          <div class="settings__section-title">导出备份</div>
          <p class="settings__desc">将选中模块的数据导出为 JSON 文件</p>
          <div v-if="backupExporting" class="settings__progress-wrap">
            <div class="settings__progress-bar">
              <div class="settings__progress-fill" :style="{ width: backupExportProgress + '%' }"></div>
            </div>
            <span class="settings__progress-text">{{ backupExportProgress }}%</span>
          </div>
          <div class="settings__actions">
            <button
              class="settings__btn settings__btn--primary"
              :disabled="backupExporting || backupSelectedModules.length === 0"
              @click="handleExportBackup"
            >
              {{ backupExporting ? '导出中...' : '导出备份' }}
            </button>
          </div>
        </div>

        <div class="settings__section">
          <div class="settings__section-title">导入恢复</div>
          <p class="settings__desc">从 JSON 备份文件中恢复数据（将覆盖当前选中模块的已有数据）</p>
          <input
            ref="importFileInput"
            type="file"
            accept=".json"
            class="settings__file-input"
            @change="handleImportFileChange"
          />
          <div class="settings__import-file-row">
            <button
              class="settings__btn settings__btn--secondary"
              :disabled="backupImporting"
              @click="triggerImportFile"
            >
              选择文件
            </button>
            <span v-if="importFileName" class="settings__import-file-name">{{ importFileName }}</span>
            <span v-else class="settings__import-file-hint">未选择文件</span>
          </div>
          <div v-if="backupImporting" class="settings__progress-wrap">
            <div class="settings__progress-bar">
              <div class="settings__progress-fill" :style="{ width: backupImportProgress + '%' }"></div>
            </div>
            <span class="settings__progress-text">{{ backupImportProgress }}%</span>
          </div>
          <div class="settings__actions">
            <button
              class="settings__btn settings__btn--danger"
              :disabled="backupImporting || !importJsonData || backupSelectedModules.length === 0"
              @click="handleImportBackup"
            >
              {{ backupImporting ? '导入中...' : '导入恢复' }}
            </button>
          </div>
        </div>

        <div class="settings__section">
          <div class="settings__section-title">日志打包下载</div>
          <p class="settings__desc">选择日期，将该天的日志文件打包为 ZIP 下载</p>
          <div class="settings__log-pack-row">
            <select v-model="logSelectedDate" class="settings__log-select">
              <option v-for="d in logDates" :key="d" :value="d">{{ d }}</option>
            </select>
            <button
              class="settings__btn settings__btn--primary"
              :disabled="!logSelectedDate || logDownloading"
              @click="handleDownloadLog"
            >
              {{ logDownloading ? '打包中...' : '下载日志' }}
            </button>
          </div>
        </div>
      </div>

      <!-- 关于 -->
      <div v-if="activeMenu === 'about'" class="settings__panel">
        <div class="settings__panel-title">关于</div>

        <!-- 产品定位 -->
        <div class="settings__section">
          <div class="settings__section-title">XianYuSmart</div>
          <p class="settings__desc">单商家私有化的闲鱼虚拟商品经营系统，聚焦卡密库存、可恢复发货、客服自动化和异常待办。</p>
        </div>

        <!-- 许可证与使用限制 -->
        <div class="settings__section">
          <div class="settings__section-title">许可证与使用限制</div>
          <p class="settings__desc">本项目采用 PolyForm Noncommercial License 1.0.0，仅授权个人学习、技术研究、实验和其他非商业用途。</p>

          <div class="settings__warning-box">
            <div class="settings__warning-icon">⚠️</div>
            <div class="settings__warning-content">
              <strong>禁止商业用途</strong>
              <ul>
                <li>禁止销售、收费部署、托管服务、SaaS、代运营和收费培训</li>
                <li>禁止通过广告、订阅、佣金或增值服务直接或间接获利</li>
                <li>禁止删除、隐藏或误导许可证、版权和免责声明信息</li>
              </ul>
            </div>
          </div>
        </div>

        <!-- 免责声明 -->
        <div class="settings__section">
          <div class="settings__section-title">免责声明</div>
          <p class="settings__desc">本项目仅供技术学习和研究，不代表闲鱼平台，也未获得相关平台的官方授权或认可。</p>

          <div class="settings__warning-box">
            <div class="settings__warning-icon">⚠️</div>
            <div class="settings__warning-content">
              <strong>使用前须知</strong>
              <ul>
                <li>使用行为必须遵守法律法规、平台服务协议和账号使用规则</li>
                <li>严禁用于欺诈、骚扰、垃圾信息、虚假交易、恶意营销或规避平台安全机制</li>
                <li>自动化操作可能触发验证、登录失效、账号限制或封禁</li>
                <li>Cookie、Token、密码、API Key 和卡密必须妥善保管并定期备份</li>
                <li>项目按“现状”提供，相关账号、数据、交易和业务风险由实际使用方承担</li>
              </ul>
            </div>
          </div>
        </div>

        <!-- 更新教程 -->
        <div class="settings__section">
          <div class="settings__section-title">更新教程</div>
          <p class="settings__desc">更新前先完成 MySQL 备份，再重新构建服务。</p>

          <div class="settings__tutorial">
            <div class="settings__tutorial-step">
              <div class="settings__step-number">1</div>
              <div class="settings__step-content">
                <div class="settings__step-title">拉取当前仓库更新</div>
                <div class="settings__code-block">
                  <code>git pull --ff-only</code>
                </div>
              </div>
            </div>

            <div class="settings__tutorial-step">
              <div class="settings__step-number">2</div>
              <div class="settings__step-content">
                <div class="settings__step-title">重新构建并启动</div>
                <div class="settings__code-block">
                  <code>docker compose up -d --build</code>
                </div>
              </div>
            </div>
          </div>

          <div class="settings__warning-box">
            <div class="settings__warning-icon">⚠️</div>
            <div class="settings__warning-content">
              <strong>重要提示：</strong>
              <ul>
                <li>更新前使用 <code>mysqldump</code> 备份数据库</li>
                <li>不要删除 Compose 创建的 <code>mysql-data</code> 卷</li>
                <li>数据库结构由 Flyway 在启动时校验和升级</li>
              </ul>
            </div>
          </div>
        </div>

      </div>
    </div>
  </div>
</template>

<style scoped>
.settings {
  display: flex;
  gap: 24px;
  height: 100%;
  min-height: 0;
  padding: 24px;
  box-sizing: border-box;
}

@media (max-width: 767px) {
  .settings {
    padding: 16px;
  }
}

/* 左侧菜单 */
.settings__sidebar {
  width: 200px;
  flex-shrink: 0;
  background: #ffffff;
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
  border: 1px solid #e4e7ec;
  border-radius: 8px;
  box-shadow: 0 1px 2px rgba(16,24,40,0.04);
  padding: 16px;
  display: flex;
  flex-direction: column;
}

.settings__sidebar-title {
  font-size: 16px;
  font-weight: 600;
  color: #1c1c1e;
  padding: 0 12px;
  margin-bottom: 16px;
}

.settings__menu {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.settings__menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
  color: rgba(28,28,30,.55);
}

.settings__menu-item:hover {
  background: #f8f9fb;
}

.settings__menu-item--active {
  background: #eef4ff;
  color: #155eef;
  font-weight: 500;
}

.settings__menu-icon {
  width: 18px;
  height: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.settings__menu-icon svg {
  width: 18px;
  height: 18px;
}

.settings__menu-label {
  font-size: 14px;
}

/* 右侧内容 */
.settings__content {
  flex: 1;
  min-width: 0;
  background: #ffffff;
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
  border: 1px solid #e4e7ec;
  border-radius: 8px;
  box-shadow: 0 1px 2px rgba(16,24,40,0.04);
  padding: 24px;
  overflow-y: auto;
  position: relative;
}

.settings__content::before {
  content: '';
  position: absolute;
  top: 0;
  left: 10%;
  right: 10%;
  height: 1px;
  background: transparent;
  border-radius: 1px;
  pointer-events: none;
}

.settings__panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.settings__panel-title {
  font-size: 18px;
  font-weight: 600;
  color: #1c1c1e;
}

.settings__section {
  margin-top: 24px;
  padding-top: 24px;
  border-top: 0.5px solid rgba(60,60,67,.12);
}

.settings__section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.settings__section-title {
  font-size: 15px;
  font-weight: 600;
  color: #1c1c1e;
  margin-bottom: 0;
}

.settings__toggle-btn {
  height: 28px;
  padding: 0 12px;
  font-size: 12px;
  font-weight: 590;
  color: #0A84FF;
  background: rgba(255,255,255,0.70);
  backdrop-filter: blur(16px) saturate(1.6);
  -webkit-backdrop-filter: blur(16px) saturate(1.6);
  border: 1px solid rgba(255,255,255,0.85);
  border-radius: 100px;
  cursor: pointer;
  transition: opacity .15s, transform .12s, box-shadow .15s;
  box-shadow: 0 8px 32px rgba(0,0,0,0.08), 0 1.5px 4px rgba(0,0,0,0.04);
}

.settings__toggle-btn:hover {
  background: rgba(255,255,255,0.80);
}

.settings__toggle-btn:active { opacity: .80; transform: scale(.96); }

.settings__collapse-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin-bottom: 16px;
}

.settings__desc {
  font-size: 13px;
  color: rgba(28,28,30,.55);
  margin: 0;
}

/* Loading */
.settings__loading {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 16px;
  color: rgba(28,28,30,.55);
  font-size: 13px;
}

.settings__spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(0, 0, 0, 0.12);
  border-top-color: #1c1c1e;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* Info */
.settings__info {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.settings__info-row {
  display: flex;
  align-items: center;
  gap: 16px;
}

.settings__info-label {
  font-size: 13px;
  color: rgba(28,28,30,.55);
  min-width: 100px;
  flex-shrink: 0;
}

.settings__info-value {
  font-size: 14px;
  color: #1c1c1e;
  font-weight: 500;
}

/* Form */
.settings__form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.settings__field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.settings__label {
  font-size: 13px;
  font-weight: 500;
  color: #1c1c1e;
}

.settings__label-hint {
  font-size: 12px;
  font-weight: 400;
  color: rgba(28,28,30,.55);
}

.settings__link {
  color: #0066cc;
  text-decoration: none;
}

.settings__link:hover {
  text-decoration: underline;
}

.settings__input-wrap {
  position: relative;
  display: flex;
  align-items: center;
}

.settings__input {
  width: 100%;
  height: 40px;
  padding: 0 14px;
  font-size: 14px;
  color: #1c1c1e;
  background: rgba(255,255,255,0.15);
  border: 1px solid rgba(60,60,67,.12);
  border-radius: 8px;
  outline: none;
  transition: all 0.2s;
  box-sizing: border-box;
}

.settings__input:focus {
  border-color: #1c1c1e;
  background: rgba(255,255,255,0.55);
}

.settings__input::placeholder {
  color: rgba(28,28,30,.55);
}

.settings__input:disabled {
  opacity: 0.5;
}

.settings__eye-btn {
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

.settings__eye-btn:hover {
  color: #1c1c1e;
}

.settings__textarea {
  width: 100%;
  min-height: 200px;
  padding: 12px 14px;
  font-size: 14px;
  line-height: 1.6;
  color: #1c1c1e;
  background: rgba(255,255,255,0.15);
  border: 1px solid rgba(60,60,67,.12);
  border-radius: 8px;
  outline: none;
  transition: all 0.2s;
  box-sizing: border-box;
  resize: vertical;
  font-family: inherit;
}

.settings__textarea:focus {
  border-color: #1c1c1e;
  background: rgba(255,255,255,0.55);
}

.settings__textarea::placeholder {
  color: rgba(28,28,30,.55);
}

.settings__textarea:disabled {
  opacity: 0.5;
}

/* 菜单排序 */
.settings__menu-sort-notice {
  padding: 10px 12px;
  border: 1px solid #fedf89;
  border-radius: 8px;
  color: #93370d;
  background: #fffaeb;
  font-size: 13px;
}

.settings__menu-sort-groups {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.settings__menu-sort-group {
  overflow: hidden;
  border: 1px solid #e4e7ec;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 1px 2px rgba(16, 24, 40, .04);
  transition: border-color 180ms ease, box-shadow 180ms ease, opacity 180ms ease;
}

.settings__menu-sort-group:hover {
  border-color: #d0d5dd;
  box-shadow: 0 4px 12px rgba(16, 24, 40, .06);
}

.settings__menu-sort-group--dragging {
  border-color: #84adff;
  opacity: .52;
  box-shadow: 0 8px 24px rgba(21, 94, 239, .12);
}

.settings__menu-sort-group-header {
  display: flex;
  align-items: center;
  min-height: 52px;
  padding: 0 12px;
  border-bottom: 1px solid #f2f4f7;
  background: #f9fafb;
}

.settings__menu-sort-handle {
  display: grid;
  grid-template-columns: repeat(2, 3px);
  gap: 3px;
  width: 30px;
  height: 30px;
  padding: 8px;
  border: 0;
  border-radius: 7px;
  color: #98a2b3;
  background: transparent;
  cursor: grab;
  flex-shrink: 0;
}

.settings__menu-sort-handle:active {
  cursor: grabbing;
}

.settings__menu-sort-handle:hover,
.settings__menu-sort-handle:focus-visible {
  background: #eef4ff;
  outline: none;
}

.settings__menu-sort-handle span {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: currentColor;
}

.settings__menu-sort-group-name {
  display: flex;
  flex-direction: column;
  min-width: 0;
  margin-left: 8px;
  line-height: 1.35;
}

.settings__menu-sort-group-name strong {
  color: #344054;
  font-size: 14px;
  font-weight: 600;
}

.settings__menu-sort-group-name small {
  color: #98a2b3;
  font-size: 11px;
}

.settings__menu-sort-controls {
  display: flex;
  gap: 4px;
  margin-left: auto;
}

.settings__menu-sort-controls button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 1px solid #e4e7ec;
  border-radius: 7px;
  color: #667085;
  background: #fff;
  cursor: pointer;
  transition: color 160ms ease, border-color 160ms ease, background-color 160ms ease;
}

.settings__menu-sort-controls button:hover:not(:disabled),
.settings__menu-sort-controls button:focus-visible {
  border-color: #84adff;
  color: #155eef;
  background: #eef4ff;
  outline: none;
}

.settings__menu-sort-controls button:disabled {
  opacity: .32;
  cursor: not-allowed;
}

.settings__menu-sort-items {
  padding: 6px;
}

.settings__menu-sort-item {
  display: flex;
  align-items: center;
  min-height: 42px;
  padding: 0 7px;
  border-radius: 8px;
  transition: background-color 160ms ease, opacity 160ms ease;
}

.settings__menu-sort-item:hover {
  background: #f9fafb;
}

.settings__menu-sort-item--dragging {
  background: #eef4ff;
  opacity: .5;
}

.settings__menu-sort-handle--item {
  width: 28px;
  height: 28px;
  padding: 7px;
}

.settings__menu-sort-item-name {
  min-width: 110px;
  margin-left: 7px;
  color: #344054;
  font-size: 13px;
  font-weight: 500;
}

.settings__menu-sort-item-path {
  min-width: 0;
  overflow: hidden;
  margin-left: 16px;
  color: #98a2b3;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.settings__menu-sort-controls--item {
  margin-left: auto;
  padding-left: 12px;
}

.settings__menu-sort-actions {
  position: sticky;
  bottom: -24px;
  z-index: 2;
  margin: 4px -24px -24px;
  padding: 14px 24px;
  border-top: 1px solid #e4e7ec;
  background: rgba(255, 255, 255, .94);
  backdrop-filter: blur(12px);
}

.menu-sort-move {
  transition: transform 220ms cubic-bezier(.2, .8, .2, 1);
}

@media (prefers-reduced-motion: reduce) {
  .settings__menu-sort-group,
  .settings__menu-sort-item,
  .settings__menu-sort-controls button,
  .menu-sort-move {
    transition: none;
  }
}

/* Actions */
.settings__actions {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
  margin-top: 8px;
}

.settings__btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 36px;
  padding: 0 18px;
  font-size: 13px;
  font-weight: 590;
  border-radius: 100px;
  border: none;
  cursor: pointer;
  transition: opacity .15s, transform .12s, box-shadow .15s;
  white-space: nowrap;
  min-width: 40px;
  -webkit-tap-highlight-color: transparent;
  font-family: inherit;
  user-select: none;
}

.settings__btn--primary {
  color: #fff;
  background: rgba(10,132,255,0.85);
  backdrop-filter: blur(20px) saturate(1.8);
  -webkit-backdrop-filter: blur(20px) saturate(1.8);
  border: 1px solid rgba(255,255,255,0.35);
  box-shadow: 0 4px 16px rgba(10,132,255,0.35), 0 8px 32px rgba(0,0,0,0.08), 0 1.5px 4px rgba(0,0,0,0.04);
}

@media (hover: hover) {
  .settings__btn--primary:hover {
    background: rgba(10,132,255,0.95);
    box-shadow: 0 6px 20px rgba(10,132,255,0.45), 0 8px 32px rgba(0,0,0,0.08), 0 1.5px 4px rgba(0,0,0,0.04);
  }
}

.settings__btn--primary:active { opacity: .80; transform: scale(.96); }

.settings__btn--secondary {
  color: #0A84FF;
  background: rgba(255,255,255,0.70);
  backdrop-filter: blur(16px) saturate(1.6);
  -webkit-backdrop-filter: blur(16px) saturate(1.6);
  border: 1px solid rgba(255,255,255,0.85);
  box-shadow: 0 8px 32px rgba(0,0,0,0.08), 0 1.5px 4px rgba(0,0,0,0.04);
}

@media (hover: hover) {
  .settings__btn--secondary:hover {
    background: rgba(255,255,255,0.80);
  }
}

.settings__btn--secondary:active { opacity: .80; transform: scale(.96); }

.settings__btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.settings__btn--danger {
  color: #FF453A;
  background: rgba(255,69,58,0.15);
  backdrop-filter: blur(16px) saturate(1.6);
  -webkit-backdrop-filter: blur(16px) saturate(1.6);
  border: 1px solid rgba(255,69,58,0.2);
  box-shadow: 0 8px 32px rgba(0,0,0,0.08), 0 1.5px 4px rgba(0,0,0,0.04);
}

@media (hover: hover) {
  .settings__btn--danger:hover {
    background: rgba(255,69,58,0.22);
    border-color: rgba(255,69,58,0.35);
  }
}

.settings__btn--danger:active { opacity: .80; transform: scale(.96); }

/* AI 服务配置 */
.settings__ai-overview {
  padding: 18px;
  border: 1px solid #dbe5f2;
  border-radius: 14px;
  background: linear-gradient(135deg, #f7fbff 0%, #f4f7ff 55%, #fbf9ff 100%);
}

.settings__ai-overview-main {
  display: flex;
  align-items: center;
  gap: 12px;
}

.settings__ai-overview-main > div {
  flex: 1;
  min-width: 0;
}

.settings__ai-overview-title {
  color: #182230;
  font-size: 15px;
  font-weight: 650;
}

.settings__ai-status-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #98a2b3;
  box-shadow: 0 0 0 5px rgba(152,162,179,.13);
}

.settings__ai-status-dot--ok {
  background: #12b76a;
  box-shadow: 0 0 0 5px rgba(18,183,106,.13);
}

.settings__ai-status-badge {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 500;
}

.settings__ai-status-badge--ok {
  background: #dcfae6;
  color: #067647;
}

.settings__ai-status-badge--off {
  background: #f2f4f7;
  color: #667085;
}

.settings__ai-status-msg {
  font-weight: 400;
  color: #667085;
  font-size: 12px;
  margin-top: 3px;
}

.settings__ai-overview-meta {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-top: 16px;
}

.settings__ai-overview-meta > div {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 9px 11px;
  border-radius: 9px;
  background: rgba(255,255,255,.72);
}

.settings__ai-overview-meta span {
  color: #667085;
  font-size: 11px;
}

.settings__ai-overview-meta strong {
  overflow: hidden;
  color: #344054;
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.settings__provider-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-top: 16px;
}

.settings__provider-card {
  position: relative;
  display: flex;
  align-items: center;
  gap: 11px;
  min-height: 72px;
  padding: 12px;
  border: 1px solid #e4e7ec;
  border-radius: 12px;
  background: #fff;
  color: #344054;
  cursor: pointer;
  font-family: inherit;
  text-align: left;
  transition: border-color .18s, box-shadow .18s, transform .18s;
}

.settings__provider-card:hover {
  border-color: #b2ccff;
  box-shadow: 0 6px 18px rgba(16,24,40,.07);
  transform: translateY(-1px);
}

.settings__provider-card--active {
  border-color: #528bff;
  background: #f5f8ff;
  box-shadow: 0 0 0 3px rgba(21,94,239,.08);
}

.settings__provider-card:disabled {
  cursor: not-allowed;
  opacity: .65;
}

.settings__provider-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  border-radius: 11px;
}

.settings__provider-icon svg {
  width: 23px;
  height: 23px;
}

.settings__provider-icon--emerald { color: #067647; background: #dcfae6; }
.settings__provider-icon--amber { color: #b54708; background: #fef0c7; }
.settings__provider-icon--blue { color: #175cd3; background: #d1e9ff; }
.settings__provider-icon--violet { color: #6938ef; background: #ebe9fe; }
.settings__provider-icon--cyan { color: #087e8b; background: #cff9fe; }
.settings__provider-icon--slate { color: #475467; background: #eaecf0; }

.settings__provider-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.settings__provider-copy strong {
  color: #182230;
  font-size: 13px;
  font-weight: 650;
}

.settings__provider-copy small {
  overflow: hidden;
  color: #667085;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.settings__provider-check {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #155eef;
  color: #fff;
  font-size: 12px;
}

.settings__ai-form {
  margin-top: 16px;
}

.settings__ai-preset-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid #e4e7ec;
  border-radius: 9px;
  background: #f9fafb;
  color: #667085;
  font-size: 12px;
}

.settings__ai-preset-summary strong {
  color: #344054;
  font-weight: 600;
}

.settings__advanced-trigger {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 11px 12px;
  border: 1px solid #e4e7ec;
  border-radius: 9px;
  background: #fff;
  color: #344054;
  cursor: pointer;
  font-family: inherit;
  font-size: 13px;
  text-align: left;
}

.settings__advanced-trigger small {
  color: #98a2b3;
  font-size: 11px;
}

.settings__advanced-trigger span:last-child {
  color: #155eef;
  font-size: 12px;
}

.settings__advanced-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px;
  border: 1px solid #e4e7ec;
  border-radius: 12px;
  background: #f9fafb;
}

.settings__protocol-tabs {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.settings__protocol-tabs button {
  height: 36px;
  border: 1px solid #d0d5dd;
  border-radius: 8px;
  background: #fff;
  color: #475467;
  cursor: pointer;
  font-family: inherit;
  font-size: 12px;
}

.settings__protocol-tabs button.active {
  border-color: #528bff;
  background: #eff4ff;
  color: #155eef;
  font-weight: 600;
}

.settings__endpoint-preview {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 11px 12px;
  border-radius: 9px;
  background: #101828;
}

.settings__endpoint-preview span {
  color: #98a2b3;
  font-size: 11px;
}

.settings__endpoint-preview code {
  overflow-wrap: anywhere;
  color: #d1e9ff;
  font-size: 12px;
}

.settings__connection-test {
  padding: 20px;
  border: 1px solid #dbe5f2;
  border-radius: 14px;
  background: #f8fbff;
}

.settings__test-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
  gap: 12px;
  margin-top: 14px;
}

.settings__test-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  height: 40px;
  padding: 0 17px;
  border: 0;
  border-radius: 9px;
  background: #155eef;
  color: #fff;
  cursor: pointer;
  font-family: inherit;
  font-size: 13px;
  font-weight: 600;
}

.settings__test-button svg {
  width: 17px;
  height: 17px;
}

.settings__test-button:disabled {
  cursor: wait;
  opacity: .6;
}

.settings__test-loading {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: 14px;
  color: #667085;
  font-size: 12px;
}

.settings__test-loading span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #528bff;
  animation: ai-test-pulse 1.1s infinite ease-in-out;
}

.settings__test-loading span:nth-child(2) { animation-delay: .15s; }
.settings__test-loading span:nth-child(3) { animation-delay: .3s; margin-right: 5px; }

@keyframes ai-test-pulse {
  0%, 80%, 100% { opacity: .3; transform: scale(.75); }
  40% { opacity: 1; transform: scale(1); }
}

.settings__test-result {
  margin-top: 14px;
  padding: 15px;
  border: 1px solid;
  border-radius: 12px;
}

.settings__test-result--ok { border-color: #abefc6; background: #ecfdf3; }
.settings__test-result--error { border-color: #fecdca; background: #fef3f2; }

.settings__test-result-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.settings__test-result-head > div:first-child {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.settings__test-result-head strong { color: #182230; font-size: 14px; }
.settings__test-result-head span { color: #667085; font-size: 11px; }

.settings__latency {
  display: flex;
  align-items: baseline;
  gap: 3px;
  color: #155eef;
}

.settings__latency strong { color: inherit; font-size: 24px; }
.settings__latency span { color: inherit; font-size: 11px; }

.settings__test-reply {
  margin-top: 12px;
  padding: 11px 12px;
  border-radius: 8px;
  background: rgba(255,255,255,.75);
}

.settings__test-reply span { color: #667085; font-size: 11px; }
.settings__test-reply p { margin: 5px 0 0; color: #344054; font-size: 13px; line-height: 1.6; white-space: pre-wrap; }

.settings__test-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 10px;
}

.settings__test-meta span {
  padding: 3px 7px;
  border-radius: 6px;
  background: rgba(255,255,255,.75);
  color: #475467;
  font-size: 10px;
}

.settings__test-meta code {
  flex: 1 1 100%;
  overflow-wrap: anywhere;
  color: #667085;
  font-size: 11px;
}

.settings__capability-switch {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.settings__capability-switch > div {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.settings__capability-switch strong { color: #344054; font-size: 13px; }
.settings__capability-switch span { color: #667085; font-size: 11px; }

@media (max-width: 720px) {
  .settings__provider-grid,
  .settings__ai-overview-meta {
    grid-template-columns: 1fr;
  }

  .settings__test-layout {
    grid-template-columns: 1fr;
  }

  .settings__test-button {
    width: 100%;
  }
}

/* Logout */
.settings__logout {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.settings__logout-text {
  font-size: 14px;
  color: rgba(28,28,30,.55);
  margin: 0 0 12px 0;
}

/* QR Code */
.settings__qrcode-wrapper {
  display: flex;
  justify-content: center;
  padding: 20px 0;
}

.settings__qrcode {
  max-width: 300px;
  width: 100%;
  height: auto;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

/* Responsive */
@media (max-width: 768px) {
  .settings {
    flex-direction: column;
    gap: 16px;
  }

  .settings__sidebar {
    width: 100%;
    flex-direction: row;
    flex-wrap: wrap;
  }

  .settings__sidebar-title {
    width: 100%;
    margin-bottom: 8px;
  }

  .settings__menu {
    flex-direction: row;
    flex-wrap: wrap;
    gap: 8px;
  }

  .settings__menu-item {
    padding: 8px 12px;
  }

  .settings__content {
    padding: 16px;
  }

  .settings__menu-sort-actions {
    bottom: -16px;
    margin: 4px -16px -16px;
    padding: 12px 16px;
  }

  .settings__qrcode {
    max-width: 250px;
  }
}

@media (max-width: 480px) {
  .settings__info-row,
  .settings__ai-status-row {
    flex-direction: column;
    align-items: flex-start;
    gap: 4px;
  }

  .settings__actions {
    flex-direction: column;
  }

  .settings__btn {
    width: 100%;
  }

  .settings__menu-sort-group-header {
    padding: 0 8px;
  }

  .settings__menu-sort-item-path {
    display: none;
  }

  .settings__menu-sort-item-name {
    min-width: 0;
  }

  .settings__menu-sort-actions {
    flex-direction: row;
  }

  .settings__qrcode {
    max-width: 200px;
  }
}

/* 更新教程样式 */
.settings__tutorial {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin-top: 16px;
}

.settings__tutorial-step {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.settings__step-number {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: #155eef;
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 600;
  flex-shrink: 0;
}

.settings__step-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.settings__step-title {
  font-size: 14px;
  font-weight: 600;
  color: #1c1c1e;
}

.settings__code-block {
  background: #f5f5f7;
  border: 1px solid rgba(60,60,67,.08);
  border-radius: 8px;
  padding: 12px 16px;
  overflow-x: auto;
}

.settings__code-block pre {
  margin: 0;
  padding: 0;
  background: transparent;
  font-family: inherit;
}

.settings__code-block code {
  font-family: 'SF Mono', 'Monaco', 'Inconsolata', 'Fira Code', monospace;
  font-size: 13px;
  color: #1c1c1e;
  display: block;
}

.settings__step-tip {
  font-size: 12px;
  color: rgba(28,28,30,.55);
  margin: 0;
  padding: 8px 12px;
  background: #f0f9ff;
  border-left: 3px solid #0ea5e9;
  border-radius: 4px;
}

.settings__warning-box {
  display: flex;
  gap: 12px;
  margin-top: 20px;
  padding: 16px;
  background: #fff7ed;
  border: 1px solid #ffedd5;
  border-radius: 8px;
}

.settings__warning-icon {
  font-size: 20px;
  flex-shrink: 0;
}

.settings__warning-content {
  flex: 1;
  font-size: 13px;
  color: #1c1c1e;
}

.settings__warning-content strong {
  display: block;
  margin-bottom: 8px;
  color: #c2410c;
}

.settings__warning-content ul {
  margin: 0;
  padding-left: 20px;
}

.settings__warning-content li {
  margin-bottom: 6px;
  color: rgba(28,28,30,.55);
}

.settings__warning-content li:last-child {
  margin-bottom: 0;
}

.settings__warning-content code {
  background: rgba(60,60,67,.12);
  padding: 2px 6px;
  border-radius: 3px;
  font-size: 12px;
  font-family: 'SF Mono', 'Monaco', 'Inconsolata', 'Fira Code', monospace;
}

.settings__section-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.settings__status-badge {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 500;
  background: rgba(60,60,67,.12);
  color: rgba(28,28,30,.55);
}

.settings__status-badge--success {
  background: rgba(48,209,88,0.12);
  color: #30D158;
}

.settings__switch {
  position: relative;
  display: inline-flex;
  align-items: center;
  cursor: pointer;
}

.settings__switch input {
  position: absolute;
  opacity: 0;
  width: 0;
  height: 0;
}

.settings__switch-track {
  width: 51px;
  height: 31px;
  background: rgba(120,120,128,.24);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  border: 1px solid rgba(255,255,255,.3);
  border-radius: 100px;
  transition: background 0.2s;
  flex-shrink: 0;
}

.settings__switch-thumb {
  position: absolute;
  left: 2px;
  top: 2px;
  width: 27px;
  height: 27px;
  background: linear-gradient(160deg, rgba(255,255,255,1) 0%, rgba(240,240,242,1) 100%);
  border-radius: 50%;
  box-shadow: 0 2px 8px rgba(0,0,0,.22), inset 0 1px 0 rgba(255,255,255,.8);
  transition: transform .22s cubic-bezier(.34,1.56,.64,1);
  pointer-events: none;
}

.settings__switch input:checked + .settings__switch-track {
  background: rgba(48,209,88,0.85);
  border-color: rgba(255,255,255,.4);
}

.settings__switch input:checked + .settings__switch-track + .settings__switch-thumb {
  transform: translateX(20px);
}

.settings__switch input:disabled + .settings__switch-track {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 通知开关表格 */
.settings__notify-table {
  width: 100%;
  border-collapse: collapse;
  border: 1px solid rgba(60,60,67,.12);
  border-radius: 8px;
  overflow: hidden;
  margin-top: 12px;
}

.settings__notify-th {
  background: rgba(255,255,255,0.15);
  padding: 12px 16px;
  font-size: 13px;
  font-weight: 600;
  color: #1c1c1e;
  text-align: left;
  border-bottom: 0.5px solid rgba(60,60,67,.12);
}

.settings__notify-th:last-child {
  text-align: center;
  width: 80px;
}

.settings__notify-tr {
  transition: background 0.2s;
}

.settings__notify-tr:hover {
  background: rgba(255,255,255,0.15);
}

.settings__notify-td {
  padding: 14px 16px;
  font-size: 13px;
  color: #1c1c1e;
  border-bottom: 1px solid rgba(0, 0, 0, 0.04);
}

.settings__notify-tr:last-child .settings__notify-td {
  border-bottom: none;
}

.settings__notify-td:last-child {
  text-align: center;
}

.settings__notify-td--desc {
  color: rgba(28,28,30,.55);
  font-size: 12px;
}

/* 备份与恢复 */
.settings__backup-modules {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}

.settings__backup-module-all {
  width: 100%;
  margin-bottom: 4px;
}

.settings__backup-module-item {
}

.settings__checkbox-label {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  color: #1c1c1e;
  background: rgba(255,255,255,0.15);
  border: 1px solid rgba(60,60,67,.08);
  transition: all 0.2s;
  user-select: none;
}

.settings__checkbox-label:hover {
  background: rgba(255,255,255,0.38);
  border-color: rgba(60,60,67,.12);
}

.settings__checkbox {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 4px;
  border: 1.5px solid rgba(60,60,67,.2);
  flex-shrink: 0;
  transition: all 0.2s;
}

.settings__checkbox--checked {
  background: rgba(10,132,255,0.85);
  border-color: #1c1c1e;
}

.settings__checkbox-tick {
  color: rgba(255,255,255,0.55);
  font-size: 11px;
  line-height: 1;
}

.settings__progress-wrap {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 12px 0;
}

.settings__progress-bar {
  flex: 1;
  height: 6px;
  background: rgba(60,60,67,.12);
  border-radius: 3px;
  overflow: hidden;
}

.settings__progress-fill {
  height: 100%;
  background: rgba(10,132,255,0.85);
  border-radius: 3px;
  transition: width 0.3s ease;
}

.settings__progress-text {
  font-size: 12px;
  color: rgba(28,28,30,.55);
  min-width: 36px;
  text-align: right;
}

.settings__file-input {
  display: none;
}

.settings__import-file-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 12px 0;
}

.settings__import-file-name {
  font-size: 13px;
  color: #1c1c1e;
  font-weight: 500;
}

.settings__import-file-hint {
  font-size: 13px;
  color: rgba(28,28,30,.55);
}

.settings__log-pack-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 12px 0;
}

.settings__log-select {
  flex: 1;
  max-width: 200px;
  padding: 8px 12px;
  font-size: 13px;
  border: 1px solid #d2d2d7;
  border-radius: 8px;
  background: rgba(255,255,255,0.55);
  color: #1c1c1e;
  outline: none;
  appearance: none;
  cursor: pointer;
}

.settings__log-select:focus {
  border-color: #0A84FF;
  box-shadow: 0 0 0 3px rgba(0, 122, 255, 0.12);
}

@media (max-width: 768px) {
  .settings__backup-modules {
    flex-direction: column;
  }
}
</style>
