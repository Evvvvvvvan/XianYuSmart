<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  acknowledgeAllOperationExceptions,
  acknowledgeOperationException,
  deleteNotificationChannel,
  getHealthOverview,
  getNotificationChannels,
  getNotificationLogs,
  getOperationExceptions,
  saveNotificationChannel,
  testNotificationChannel,
  type HealthOverview,
  type NotificationChannel,
  type NotificationChannelType,
  type NotificationLog,
  type OperationException
} from '@/api/operations-health'
import { toast } from '@/utils/toast'
import { showConfirm } from '@/utils/confirm'

const tabs = [
  { key: 'health', label: '系统检查' },
  { key: 'exceptions', label: '异常待办' },
  { key: 'channels', label: '通知渠道' },
  { key: 'logs', label: '发送记录' }
]
const eventOptions = [
  { value: 'ORDER_CREATED', label: '发现订单' },
  { value: 'DELIVERY_SUCCESS', label: '发货成功' },
  { value: 'DELIVERY_EXCEPTION', label: '发货异常' },
  { value: 'ACCOUNT_OFFLINE', label: '账号离线' },
  { value: 'CREDENTIAL_EXPIRED', label: '凭证失效' },
  { value: 'KAMI_STOCK_LOW', label: '卡密低库存' }
]
const channelTypes: Array<{
  value: NotificationChannelType
  label: string
  description: string
  fields: Array<{ key: string; label: string; placeholder: string; secret?: boolean }>
  defaults?: Record<string, string>
}> = [
  { value: 'WECHAT_WORK', label: '企业微信', description: '群机器人通知', fields: [
    { key: 'webhookUrl', label: '机器人 Webhook', placeholder: 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...' }
  ] },
  { value: 'DINGTALK', label: '钉钉', description: '群机器人通知，支持加签', fields: [
    { key: 'webhookUrl', label: '机器人 Webhook', placeholder: 'https://oapi.dingtalk.com/robot/send?access_token=...' },
    { key: 'secret', label: '加签密钥（可选）', placeholder: 'SEC...', secret: true }
  ] },
  { value: 'FEISHU', label: '飞书', description: '群机器人通知，支持签名校验', fields: [
    { key: 'webhookUrl', label: '机器人 Webhook', placeholder: 'https://open.feishu.cn/open-apis/bot/v2/hook/...' },
    { key: 'secret', label: '签名密钥（可选）', placeholder: '留空则不启用签名', secret: true }
  ] },
  { value: 'BARK', label: 'Bark', description: 'iPhone 实时推送', fields: [
    { key: 'serverUrl', label: '服务地址', placeholder: 'https://api.day.app' },
    { key: 'deviceKey', label: 'Device Key', placeholder: 'Bark 设备密钥', secret: true },
    { key: 'group', label: '推送分组（可选）', placeholder: 'XianYuSmart' }
  ], defaults: { serverUrl: 'https://api.day.app' } },
  { value: 'PUSHPLUS', label: 'PushPlus', description: '微信消息推送', fields: [
    { key: 'token', label: 'Token', placeholder: 'PushPlus Token', secret: true },
    { key: 'topic', label: '群组编码（可选）', placeholder: 'topic' }
  ] },
  { value: 'TELEGRAM', label: 'Telegram', description: '机器人私聊或群组通知', fields: [
    { key: 'botToken', label: 'Bot Token', placeholder: '123456:ABC...', secret: true },
    { key: 'chatId', label: 'Chat ID', placeholder: '-100xxxxxxxxxx' }
  ] },
  { value: 'WEBHOOK', label: '通用 Webhook', description: '向自建系统发送标准 JSON', fields: [
    { key: 'webhookUrl', label: 'Webhook 地址', placeholder: 'https://example.com/webhook' },
    { key: 'secret', label: '签名密钥（可选）', placeholder: '用于 X-XianYuSmart-Signature', secret: true }
  ] }
]
const defaultMessageTemplate = '【{eventName}】{title}\n{content}\n账号：{accountId}'
const activeTab = ref('health')
const loading = ref(false)
const overview = ref<HealthOverview>()
const exceptions = ref<OperationException[]>([])
const channels = ref<NotificationChannel[]>([])
const logs = ref<NotificationLog[]>([])
const editing = ref(false)
const acknowledging = ref('')
const channelForm = ref({
  id: undefined as number | undefined,
  channelName: '',
  channelType: 'WECHAT_WORK' as NotificationChannelType,
  config: {} as Record<string, string>,
  messageTemplate: defaultMessageTemplate,
  eventTypes: [] as string[],
  enabled: true
})
const activeChannelType = () =>
  channelTypes.find(item => item.value === channelForm.value.channelType) || channelTypes[0]!
const selectChannelType = (channelType: NotificationChannelType) => {
  channelForm.value.channelType = channelType
  channelForm.value.config = { ...(channelTypes.find(item => item.value === channelType)?.defaults || {}) }
}

const load = async () => {
  loading.value = true
  try {
    if (activeTab.value === 'health') overview.value = (await getHealthOverview()).data
    if (activeTab.value === 'exceptions') exceptions.value = (await getOperationExceptions()).data || []
    if (activeTab.value === 'channels') channels.value = (await getNotificationChannels()).data || []
    if (activeTab.value === 'logs') logs.value = (await getNotificationLogs()).data || []
  } finally {
    loading.value = false
  }
}

const changeTab = (key: string) => {
  activeTab.value = key
  load()
}

const openChannel = (channel?: NotificationChannel) => {
  channelForm.value = channel ? {
    id: channel.id,
    channelName: channel.channelName,
    channelType: channel.channelType || 'WEBHOOK',
    config: { ...(channel.config || {}) },
    messageTemplate: channel.messageTemplate || defaultMessageTemplate,
    eventTypes: [...channel.eventTypes],
    enabled: channel.enabled
  } : {
    id: undefined,
    channelName: '',
    channelType: 'WECHAT_WORK',
    config: {},
    messageTemplate: defaultMessageTemplate,
    eventTypes: ['DELIVERY_EXCEPTION', 'ACCOUNT_OFFLINE', 'CREDENTIAL_EXPIRED', 'KAMI_STOCK_LOW'],
    enabled: true
  }
  editing.value = true
}

const saveChannel = async () => {
  if (!channelForm.value.channelName.trim()) {
    toast.warning('请填写渠道名称')
    return
  }
  const missingField = activeChannelType().fields.find(field =>
    !field.label.includes('可选') && !channelForm.value.config[field.key]?.trim()
    && !(channelForm.value.id && field.secret)
  )
  if (missingField) {
    toast.warning(`请填写${missingField.label}`)
    return
  }
  if (channelForm.value.eventTypes.length === 0) {
    toast.warning('请至少选择一个通知事件')
    return
  }
  await saveNotificationChannel(channelForm.value)
  toast.success('通知渠道已保存')
  editing.value = false
  load()
}

const testChannel = async (channel: NotificationChannel) => {
  await testNotificationChannel(channel.id)
  toast.success('测试通知发送成功')
  load()
}

const removeChannel = async (channel: NotificationChannel) => {
  try {
    await showConfirm(`确定删除通知渠道「${channel.channelName}」？`, '删除确认')
    await deleteNotificationChannel(channel.id)
    toast.success('通知渠道已删除')
    load()
  } catch {}
}

const exceptionKey = (item: OperationException) =>
  `${item.exceptionType}-${item.exceptionId}-${item.exceptionVersion}`

const acknowledgeException = async (item: OperationException) => {
  const key = exceptionKey(item)
  acknowledging.value = key
  try {
    await acknowledgeOperationException(item)
    toast.success('异常已标记为已处理')
    await load()
  } finally {
    acknowledging.value = ''
  }
}

const acknowledgeAllExceptions = async () => {
  try {
    await showConfirm(`确定将当前 ${exceptions.value.length} 条异常全部标记为已处理？`, '批量处理异常')
    const response = await acknowledgeAllOperationExceptions(exceptions.value)
    toast.success(`已处理 ${response.data || 0} 条异常`)
    await load()
  } catch {}
}

const eventLabel = (value: string) =>
  eventOptions.find(option => option.value === value)?.label || value

onMounted(load)
</script>

<template>
  <div class="health-page">
    <section class="panel page-head">
      <div>
        <h2>通知与诊断</h2>
        <p>集中查看账号、发货、回复和库存异常，并通过 Webhook 把关键事件发送到已有协作工具。</p>
      </div>
      <button v-if="activeTab === 'channels'" class="primary" @click="openChannel()">新建渠道</button>
      <div v-else-if="activeTab === 'exceptions'" class="actions">
        <button @click="load">刷新</button>
        <button v-if="exceptions.length > 0" class="primary" @click="acknowledgeAllExceptions">全部标记已处理</button>
      </div>
      <button v-else @click="load">刷新</button>
    </section>

    <nav class="tabs">
      <button v-for="tab in tabs" :key="tab.key" :class="{ active: activeTab === tab.key }" @click="changeTab(tab.key)">
        {{ tab.label }}
      </button>
    </nav>

    <section v-if="loading" class="panel empty">加载中...</section>

    <template v-else-if="activeTab === 'health'">
      <section v-if="overview" class="summary" :class="overview.overallStatus.toLowerCase()">
        <div>
          <span>当前状态</span>
          <strong>{{ overview.overallStatus === 'HEALTHY' ? '运行正常' : overview.overallStatus === 'CRITICAL' ? '需要立即处理' : '存在待处理项' }}</strong>
        </div>
        <div><span>紧急异常</span><strong>{{ overview.criticalCount }}</strong></div>
        <div><span>一般提醒</span><strong>{{ overview.warningCount }}</strong></div>
      </section>
      <section class="check-grid">
        <article v-for="check in overview?.checks || []" :key="check.key" class="panel check">
          <header>
            <strong>{{ check.name }}</strong>
            <span :class="check.status === 'HEALTHY' ? 'badge success' : 'badge warning'">
              {{ check.status === 'HEALTHY' ? '正常' : `${check.count} 项` }}
            </span>
          </header>
          <p>{{ check.status === 'HEALTHY' ? '当前未发现异常。' : check.action }}</p>
        </article>
      </section>
    </template>

    <section v-else-if="activeTab === 'exceptions'" class="panel list">
      <div v-if="exceptions.length === 0" class="empty">暂无异常待办。</div>
      <article v-for="item in exceptions" :key="exceptionKey(item)">
        <div class="item-main">
          <span class="badge warning">{{ item.exceptionType }}</span>
          <div><strong>{{ item.title }}</strong><p>{{ item.reason }}</p></div>
        </div>
        <div class="exception-actions">
          <div class="item-meta"><span>{{ item.status }}</span><span>{{ item.occurredAt }}</span></div>
          <button :disabled="acknowledging === exceptionKey(item)" @click="acknowledgeException(item)">
            {{ acknowledging === exceptionKey(item) ? '处理中...' : '标记已处理' }}
          </button>
        </div>
      </article>
    </section>

    <section v-else-if="activeTab === 'channels'" class="panel list">
      <div v-if="channels.length === 0" class="empty">尚未配置通知渠道。</div>
      <article v-for="channel in channels" :key="channel.id">
        <div class="item-main">
          <span :class="channel.enabled ? 'badge success' : 'badge'">{{ channel.enabled ? '已启用' : '已停用' }}</span>
          <div>
            <strong>{{ channel.channelName }}</strong>
            <p>{{ channelTypes.find(item => item.value === channel.channelType)?.label || '通用 Webhook' }} · {{ channelTypes.find(item => item.value === channel.channelType)?.description }}</p>
            <div class="event-tags"><span v-for="event in channel.eventTypes" :key="event">{{ eventLabel(event) }}</span></div>
            <small v-if="channel.lastErrorMessage" class="error">{{ channel.lastErrorMessage }}</small>
          </div>
        </div>
        <div class="actions">
          <button @click="testChannel(channel)">测试</button>
          <button @click="openChannel(channel)">编辑</button>
          <button class="danger" @click="removeChannel(channel)">删除</button>
        </div>
      </article>
    </section>

    <section v-else class="panel list">
      <div v-if="logs.length === 0" class="empty">暂无通知发送记录。</div>
      <article v-for="item in logs" :key="item.id">
        <div class="item-main">
          <span :class="item.sendStatus === 1 ? 'badge success' : 'badge warning'">{{ item.sendStatus === 1 ? '成功' : '失败' }}</span>
          <div><strong>{{ item.title }}</strong><p>{{ eventLabel(item.eventType) }} · HTTP {{ item.httpStatus || '-' }}</p></div>
        </div>
        <div class="item-meta"><span v-if="item.errorMessage" class="error">{{ item.errorMessage }}</span><span>{{ item.createTime }}</span></div>
      </article>
    </section>

    <Teleport to="body">
      <div v-if="editing" class="overlay" @click.self="editing = false">
        <section class="dialog">
          <header><h3>{{ channelForm.id ? '编辑通知渠道' : '新建通知渠道' }}</h3><button class="close" @click="editing = false">×</button></header>
          <label>渠道名称<input v-model="channelForm.channelName" maxlength="100" placeholder="例如：运维群机器人" /><small>{{ channelForm.channelName.length }} / 100</small></label>
          <div class="channel-types">
            <button v-for="item in channelTypes" :key="item.value" type="button"
              :class="{ active: channelForm.channelType === item.value }"
              @click="selectChannelType(item.value)">
              <strong>{{ item.label }}</strong><small>{{ item.description }}</small>
            </button>
          </div>
          <label v-for="field in activeChannelType().fields" :key="field.key">
            {{ field.label }}
            <input v-model="channelForm.config[field.key]" :type="field.secret ? 'password' : 'text'"
              :placeholder="channelForm.id && field.secret ? '留空则保持原值' : field.placeholder" />
          </label>
          <label>通知内容模板
            <textarea v-model="channelForm.messageTemplate" maxlength="1000" rows="5"></textarea>
            <small>可用变量：{eventName} 事件、{title} 标题、{content} 内容、{accountId} 账号 · {{ channelForm.messageTemplate.length }} / 1000</small>
          </label>
          <fieldset>
            <legend>接收事件</legend>
            <label v-for="option in eventOptions" :key="option.value" class="check-option">
              <input v-model="channelForm.eventTypes" type="checkbox" :value="option.value" />{{ option.label }}
            </label>
          </fieldset>
          <label class="enabled"><span>启用渠道</span><input v-model="channelForm.enabled" type="checkbox" /></label>
          <footer><button @click="editing = false">取消</button><button class="primary" @click="saveChannel">保存</button></footer>
        </section>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.health-page { height: 100%; overflow: auto; padding: 16px; box-sizing: border-box; background: #f7f8fa; color: #1d2939; }
.panel { background: #fff; border: 1px solid #eaecf0; border-radius: 10px; }
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 18px; }
h2, h3, p { margin: 0; } h2 { font-size: 18px; } .page-head p { margin-top: 5px; color: #667085; font-size: 13px; }
button, input { font: inherit; } button { padding: 8px 14px; border: 1px solid #d0d5dd; border-radius: 6px; background: #fff; color: #344054; cursor: pointer; }
.primary { color: #fff; border-color: #155eef; background: #155eef; } .danger, .error { color: #b42318; }
.tabs { display: flex; gap: 4px; margin: 12px 0; border-bottom: 1px solid #eaecf0; }
.tabs button { border: 0; border-radius: 6px 6px 0 0; background: transparent; color: #667085; }
.tabs button.active { color: #155eef; background: #eef4ff; font-weight: 600; }
.summary { display: grid; grid-template-columns: 2fr 1fr 1fr; gap: 1px; margin-bottom: 12px; overflow: hidden; border: 1px solid #eaecf0; border-radius: 10px; background: #eaecf0; }
.summary > div { padding: 18px; background: #fff; } .summary span, .summary strong { display: block; } .summary span { color: #667085; font-size: 12px; }
.summary strong { margin-top: 5px; font-size: 20px; } .summary.critical strong { color: #b42318; } .summary.warning strong { color: #b54708; } .summary.healthy strong { color: #067647; }
.check-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(230px, 1fr)); gap: 12px; }
.check { padding: 16px; } .check header { display: flex; justify-content: space-between; gap: 12px; } .check p { margin-top: 16px; color: #667085; font-size: 13px; }
.badge { display: inline-block; width: max-content; padding: 3px 8px; border-radius: 12px; color: #475467; background: #f2f4f7; font-size: 12px; white-space: nowrap; }
.badge.success { color: #067647; background: #ecfdf3; } .badge.warning { color: #b54708; background: #fffaeb; }
.list { overflow: hidden; } .list article { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 15px 16px; border-bottom: 1px solid #eaecf0; }
.list article:last-child { border-bottom: 0; } .item-main { min-width: 0; display: flex; align-items: flex-start; gap: 12px; }
.item-main p { margin-top: 4px; color: #667085; font-size: 13px; overflow-wrap: anywhere; } .item-meta { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; color: #98a2b3; font-size: 12px; text-align: right; }
.exception-actions { display: flex; align-items: center; gap: 12px; }
.event-tags { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 8px; } .event-tags span { padding: 2px 6px; border-radius: 4px; color: #155eef; background: #eef4ff; font-size: 12px; }
.actions { display: flex; gap: 6px; } .empty { padding: 70px 20px; text-align: center; color: #98a2b3; }
.overlay { position: fixed; inset: 0; z-index: 2000; display: grid; place-items: center; padding: 20px; background: rgba(16,24,40,.45); }
.dialog { width: min(680px, 100%); max-height: calc(100vh - 40px); overflow: auto; padding: 20px; border-radius: 12px; background: #fff; box-shadow: 0 20px 50px rgba(16,24,40,.2); }
.dialog header, .dialog footer, .enabled { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.dialog header { margin-bottom: 18px; } .dialog label { display: grid; gap: 6px; margin: 13px 0; color: #667085; font-size: 13px; }
.dialog input, .dialog textarea { width: 100%; padding: 9px 10px; border: 1px solid #d0d5dd; border-radius: 6px; box-sizing: border-box; font: inherit; }
.dialog textarea { resize: vertical; line-height: 1.55; }
.dialog label small { color: #98a2b3; }
.channel-types { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.channel-types button { display: flex; flex-direction: column; align-items: flex-start; gap: 3px; text-align: left; }
.channel-types button.active { border-color: #155eef; color: #155eef; background: #eef4ff; }
.channel-types small { color: #98a2b3; font-size: 11px; }
.dialog fieldset { border: 1px solid #eaecf0; border-radius: 8px; } .dialog .check-option { display: inline-flex; align-items: center; gap: 5px; margin-right: 16px; }
.dialog .check-option input, .dialog .enabled input { width: auto; } .dialog .enabled { display: flex; padding: 10px 0; color: #344054; }
.dialog footer { justify-content: flex-end; margin-top: 18px; } .close { border: 0; padding: 3px 8px; font-size: 22px; }
@media (max-width: 720px) { .summary { grid-template-columns: 1fr; } .page-head, .list article { align-items: stretch; flex-direction: column; } .actions { justify-content: flex-end; } .exception-actions { justify-content: space-between; } .item-meta { align-items: flex-start; text-align: left; } .channel-types { grid-template-columns: repeat(2, 1fr); } }
</style>
