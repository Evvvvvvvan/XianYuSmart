<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  deleteNotificationChannel,
  getHealthOverview,
  getNotificationChannels,
  getNotificationLogs,
  getOperationExceptions,
  saveNotificationChannel,
  testNotificationChannel,
  type HealthOverview,
  type NotificationChannel,
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
const activeTab = ref('health')
const loading = ref(false)
const overview = ref<HealthOverview>()
const exceptions = ref<OperationException[]>([])
const channels = ref<NotificationChannel[]>([])
const logs = ref<NotificationLog[]>([])
const editing = ref(false)
const channelForm = ref({
  id: undefined as number | undefined,
  channelName: '',
  webhookUrl: '',
  signingSecret: '',
  eventTypes: [] as string[],
  enabled: true
})

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
    webhookUrl: channel.webhookUrl,
    signingSecret: '',
    eventTypes: [...channel.eventTypes],
    enabled: channel.enabled
  } : {
    id: undefined,
    channelName: '',
    webhookUrl: '',
    signingSecret: '',
    eventTypes: ['DELIVERY_EXCEPTION', 'ACCOUNT_OFFLINE', 'CREDENTIAL_EXPIRED', 'KAMI_STOCK_LOW'],
    enabled: true
  }
  editing.value = true
}

const saveChannel = async () => {
  if (!channelForm.value.channelName.trim() || !channelForm.value.webhookUrl.trim()) {
    toast.warning('请填写渠道名称和 Webhook 地址')
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
      <article v-for="item in exceptions" :key="`${item.exceptionType}-${item.targetId}-${item.occurredAt}`">
        <div class="item-main">
          <span class="badge warning">{{ item.exceptionType }}</span>
          <div><strong>{{ item.title }}</strong><p>{{ item.reason }}</p></div>
        </div>
        <div class="item-meta"><span>{{ item.status }}</span><span>{{ item.occurredAt }}</span></div>
      </article>
    </section>

    <section v-else-if="activeTab === 'channels'" class="panel list">
      <div v-if="channels.length === 0" class="empty">尚未配置通知渠道。</div>
      <article v-for="channel in channels" :key="channel.id">
        <div class="item-main">
          <span :class="channel.enabled ? 'badge success' : 'badge'">{{ channel.enabled ? '已启用' : '已停用' }}</span>
          <div>
            <strong>{{ channel.channelName }}</strong>
            <p>{{ channel.webhookUrl }}</p>
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
          <label>渠道名称<input v-model="channelForm.channelName" maxlength="100" placeholder="例如：运维群机器人" /></label>
          <label>Webhook 地址<input v-model="channelForm.webhookUrl" placeholder="仅支持公网 HTTPS 地址" /></label>
          <label>签名密钥<input v-model="channelForm.signingSecret" type="password" :placeholder="channelForm.id ? '留空则保持原密钥' : '可选，用于校验消息签名'" /></label>
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
.event-tags { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 8px; } .event-tags span { padding: 2px 6px; border-radius: 4px; color: #155eef; background: #eef4ff; font-size: 12px; }
.actions { display: flex; gap: 6px; } .empty { padding: 70px 20px; text-align: center; color: #98a2b3; }
.overlay { position: fixed; inset: 0; z-index: 2000; display: grid; place-items: center; padding: 20px; background: rgba(16,24,40,.45); }
.dialog { width: min(560px, 100%); padding: 20px; border-radius: 12px; background: #fff; box-shadow: 0 20px 50px rgba(16,24,40,.2); }
.dialog header, .dialog footer, .enabled { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.dialog header { margin-bottom: 18px; } .dialog label { display: grid; gap: 6px; margin: 13px 0; color: #667085; font-size: 13px; }
.dialog input { width: 100%; padding: 9px 10px; border: 1px solid #d0d5dd; border-radius: 6px; box-sizing: border-box; }
.dialog fieldset { border: 1px solid #eaecf0; border-radius: 8px; } .dialog .check-option { display: inline-flex; align-items: center; gap: 5px; margin-right: 16px; }
.dialog .check-option input, .dialog .enabled input { width: auto; } .dialog .enabled { display: flex; padding: 10px 0; color: #344054; }
.dialog footer { justify-content: flex-end; margin-top: 18px; } .close { border: 0; padding: 3px 8px; font-size: 22px; }
@media (max-width: 720px) { .summary { grid-template-columns: 1fr; } .page-head, .list article { align-items: stretch; flex-direction: column; } .actions { justify-content: flex-end; } .item-meta { align-items: flex-start; text-align: left; } }
</style>
