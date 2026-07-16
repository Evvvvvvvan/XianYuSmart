<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { getAccountList } from '@/api/account'
import {
  batchPublish, compensateResource, convertSupplyToMaterial, deleteResource, executeResource, getDistributions,
  getResources, getTasks, requeueTask, saveResource, settleDistribution,
  type MerchantDistribution, type MerchantResource, type MerchantTask, type ResourceType
} from '@/api/merchant'
import type { Account } from '@/types'
import { showConfirm, showError, showSuccess } from '@/utils'

const resourceTypes: Array<{ value: ResourceType; label: string; group: string; description: string; guide: string }> = [
  { value: 'MATERIAL', label: '素材库', group: '商品运营', description: '保存待发布商品的标题、价格、库存、图片与详情。', guide: '先关联账号并完善图片、详情和地址，再单条执行或勾选后批量发布。' },
  { value: 'ADDRESS', label: '地址库', group: '商品运营', description: '复用商品发布时需要的发货地址。', guide: '创建地址后，在素材或发布规则中直接选择，无需重复填写。' },
  { value: 'SUPPLY', label: '商品采集', group: '采集分销', description: '记录外部货源、原商品与预计佣金。', guide: '填写 HTTPS 来源地址或闲鱼商品 ID，执行采集后可一键转入素材库。' },
  { value: 'SELECTION_RULE', label: '选品规则', group: '采集分销', description: '按关键词、价格和库存筛选候选货源。', guide: '关联账号并填写关键词，设置首次执行时间；立即执行可先验证规则结果。' },
  { value: 'PUBLISH_RULE', label: '发布规则', group: '任务规则', description: '按固定间隔自动发布指定素材。', guide: '选择素材、账号和地址，设置首次执行时间与间隔，执行结果在任务记录查看。' },
  { value: 'DELETE_RULE', label: '删除规则', group: '任务规则', description: '按计划删除指定闲鱼商品。', guide: '填写远端商品 ID、关联账号和执行时间；删除属于不可逆操作。' },
  { value: 'PROMOTION_ACCOUNT', label: '返佣账号', group: '返佣运营', description: '复用已登录闲鱼账号维护返佣状态。', guide: '选择已登录账号后执行刷新，状态异常时先到连接管理更新 Cookie。' },
  { value: 'ANNOUNCEMENT', label: '公告管理', group: '服务管理', description: '保存租户内运营公告与执行提醒。', guide: '使用清晰标题和正文记录重要变更，可随时停用或更新。' },
  { value: 'FEEDBACK', label: '系统反馈', group: '服务管理', description: '集中记录使用问题与改进建议。', guide: '写明现象、期望和复现路径，便于后续处理。' },
  { value: 'RISK_EVENT', label: '风控记录', group: '服务管理', description: '查看或补录平台验证、异常流量等风险事件。', guide: '自动化任务触发验证时会自动写入；人工记录可补充处理结论。' }
]

const activeView = ref<'overview' | 'resources' | 'tasks' | 'distributions'>('overview')
const activeType = ref<ResourceType>('MATERIAL')
const loading = ref(false)
const accounts = ref<Account[]>([])
const addresses = ref<MerchantResource[]>([])
const materials = ref<MerchantResource[]>([])
const resources = ref<MerchantResource[]>([])
const tasks = ref<MerchantTask[]>([])
const distributions = ref<MerchantDistribution[]>([])
const selectedIds = ref<number[]>([])
const publishAccountId = ref<number>()
const showEditor = ref(false)
const overviewCounts = reactive<Record<string, number>>({})
const overviewTaskCount = ref(0)
const overviewFailedCount = ref(0)

const form = reactive<any>({})
const formDefaults = () => ({
  id: undefined, resourceType: activeType.value, name: '', status: 1, xianyuAccountId: undefined,
  xyGoodsId: '', stock: 0, amount: 0, scheduledTime: '', description: '', images: '', sourceUrl: '',
  commissionAmount: 0, province: '', city: '', detail: '', keyword: '', minAmount: 0,
  maxAmount: 999999, minStock: 0, intervalMinutes: 1440, materialId: undefined, kamiConfigId: undefined,
  targetUrl: '', addressId: undefined, content: '', level: 'INFO'
})
Object.assign(form, formDefaults())

const currentType = computed(() => resourceTypes.find(item => item.value === activeType.value)!)
const groups = computed(() => [...new Set(resourceTypes.map(item => item.group))])
const accountName = (id?: number) => accounts.value.find(item => item.id === id)?.accountNote || accounts.value.find(item => item.id === id)?.unb || '-'
const statusText = (status: number) => ({ 0: '停用', 1: '启用', 2: '已完成', '-1': '失败' } as Record<string, string>)[String(status)] || '处理中'
const taskStatusText = (status: number) => ({ 0: '待执行', 1: '执行中', 2: '成功', '-1': '失败' } as Record<string, string>)[String(status)] || '-'
const formatTime = (value?: string) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-'
const taskName = (type: string) => ({ COLLECT: '采集', SELECT: '选品', PUBLISH: '发布', DELETE: '删除', COMPENSATE: '补偿', REFRESH_PROMOTION: '返佣刷新' } as Record<string, string>)[type] || type
const actionText = (type: ResourceType) => ({ SUPPLY: '立即采集', SELECTION_RULE: '立即选品', PUBLISH_RULE: '立即发布', DELETE_RULE: '立即删除', PROMOTION_ACCOUNT: '刷新状态', MATERIAL: '立即发布' } as Partial<Record<ResourceType, string>>)[type] || '立即执行'
const resourceSummary = (resource: MerchantResource) => {
  if (resource.resourceType === 'ADDRESS') return [resource.data?.province, resource.data?.city, resource.data?.detail].filter(Boolean).join(' ') || '地址信息待完善'
  if (resource.resourceType === 'MATERIAL') return `¥${Number(resource.amount || 0).toFixed(2)} · 库存 ${resource.stock || 0}`
  if (resource.resourceType === 'SUPPLY') return resource.data?.sourceUrl || resource.xyGoodsId || '来源待完善'
  if (resource.resourceType.endsWith('_RULE')) return `每 ${resource.data?.intervalMinutes || 1440} 分钟 · ${formatTime(resource.scheduledTime)}`
  return resource.data?.content || accountName(resource.xianyuAccountId)
}

const loadOverview = async () => {
  loading.value = true
  try {
    const [resourceResults, taskResult] = await Promise.all([
      Promise.all(resourceTypes.map(item => getResources(item.value))),
      getTasks({ limit: 200 })
    ])
    resourceTypes.forEach((item, index) => { overviewCounts[item.value] = resourceResults[index]?.data?.length || 0 })
    const overviewTasks = taskResult.data || []
    overviewTaskCount.value = overviewTasks.length
    overviewFailedCount.value = overviewTasks.filter(task => task.status === -1).length
  } finally { loading.value = false }
}

const loadResources = async () => {
  loading.value = true
  try {
    const response = await getResources(activeType.value)
    resources.value = response.data || []
    selectedIds.value = []
  } finally { loading.value = false }
}

const loadTasks = async () => {
  loading.value = true
  try { tasks.value = (await getTasks({ limit: 200 })).data || [] } finally { loading.value = false }
}

const loadDistributions = async () => {
  loading.value = true
  try { distributions.value = (await getDistributions({ limit: 200 })).data || [] } finally { loading.value = false }
}

const switchView = async (view: 'overview' | 'resources' | 'tasks' | 'distributions') => {
  activeView.value = view
  if (view === 'overview') await loadOverview()
  if (view === 'resources') await loadResources()
  if (view === 'tasks') await loadTasks()
  if (view === 'distributions') await loadDistributions()
}

const switchType = async (type: ResourceType) => {
  activeType.value = type
  await switchView('resources')
}

const openCreate = () => {
  Object.assign(form, formDefaults())
  if (['SELECTION_RULE', 'PUBLISH_RULE', 'DELETE_RULE'].includes(activeType.value)) {
    const firstRun = new Date(Date.now() + 5 * 60 * 1000)
    form.scheduledTime = new Date(firstRun.getTime() - firstRun.getTimezoneOffset() * 60000).toISOString().slice(0, 16)
  }
  showEditor.value = true
}

const openEdit = (resource: MerchantResource) => {
  Object.assign(form, formDefaults(), resource, resource.data || {}, {
    images: Array.isArray(resource.data?.images) ? resource.data.images.join('\n') : resource.data?.images || ''
  })
  showEditor.value = true
}

const buildData = () => {
  const images = String(form.images || '').split(/\n|,/).map((item: string) => item.trim()).filter(Boolean)
  return {
    description: form.description || undefined, images: images.length ? images : undefined,
    sourceUrl: form.sourceUrl || undefined, commissionAmount: Number(form.commissionAmount || 0),
    province: form.province || undefined, city: form.city || undefined, detail: form.detail || undefined,
    keyword: form.keyword || undefined, minAmount: Number(form.minAmount || 0),
    maxAmount: Number(form.maxAmount || 999999), minStock: Number(form.minStock || 0),
    intervalMinutes: Number(form.intervalMinutes || 1440), materialId: form.materialId ? Number(form.materialId) : undefined,
    kamiConfigId: form.kamiConfigId ? Number(form.kamiConfigId) : undefined,
    targetUrl: form.targetUrl || undefined, addressId: form.addressId ? Number(form.addressId) : undefined,
    content: form.content || undefined, level: form.level || undefined
  }
}

const submitForm = async () => {
  if (!String(form.name || '').trim()) return showError('请输入名称')
  if (form.resourceType === 'ADDRESS' && (!form.province || !form.city || !form.detail)) return showError('请完整填写省份、城市和详细地址')
  if (form.resourceType === 'SUPPLY' && !form.sourceUrl && !form.xyGoodsId) return showError('来源地址和闲鱼商品 ID 至少填写一项')
  if (form.resourceType === 'SELECTION_RULE' && (!form.xianyuAccountId || !form.keyword)) return showError('选品规则需要关联账号并填写关键词')
  if (form.resourceType === 'PUBLISH_RULE' && (!form.xianyuAccountId || !form.materialId)) return showError('发布规则需要选择账号和素材')
  if (form.resourceType === 'DELETE_RULE' && (!form.xianyuAccountId || !form.xyGoodsId)) return showError('删除规则需要关联账号并填写商品 ID')
  if (form.resourceType === 'PROMOTION_ACCOUNT' && !form.xianyuAccountId) return showError('请选择已登录账号')
  if (['SELECTION_RULE', 'PUBLISH_RULE', 'DELETE_RULE'].includes(form.resourceType) && !form.scheduledTime) return showError('请选择首次执行时间')
  if (['ANNOUNCEMENT', 'FEEDBACK', 'RISK_EVENT'].includes(form.resourceType) && !String(form.content || '').trim()) return showError('请输入内容')
  await saveResource({
    id: form.id, resourceType: form.resourceType, name: form.name.trim(), status: Number(form.status),
    xianyuAccountId: form.xianyuAccountId ? Number(form.xianyuAccountId) : undefined,
    xyGoodsId: form.xyGoodsId || undefined, stock: Number(form.stock || 0), amount: Number(form.amount || 0),
    scheduledTime: form.scheduledTime || undefined, data: buildData()
  } as any)
  showEditor.value = false
  showSuccess(form.id ? '保存成功' : '创建成功')
  await loadResources()
  if (activeType.value === 'ADDRESS') addresses.value = [...resources.value]
  if (activeType.value === 'MATERIAL') materials.value = [...resources.value]
}

const removeResource = async (resource: MerchantResource) => {
  try {
    await showConfirm(`确认删除“${resource.name}”？`)
  } catch {
    return
  }
  await deleteResource(resource.id)
  showSuccess('删除成功')
  await loadResources()
}

const runResource = async (resource: MerchantResource) => {
  await executeResource(resource.id)
  showSuccess('任务已执行，可在任务记录查看结果')
  await loadResources()
}

const convertSupply = async (resource: MerchantResource) => {
  await convertSupplyToMaterial(resource.id)
  materials.value = (await getResources('MATERIAL')).data || []
  showSuccess('已写入素材库并建立分销关系')
}

const compensate = async (resource: MerchantResource) => {
  await compensateResource(resource.id)
  showSuccess('发布信息、短链和卡券绑定已完成检查与补偿')
  await loadResources()
}

const publishSelected = async () => {
  if (!selectedIds.value.length) return showError('请先选择素材')
  await batchPublish(selectedIds.value, publishAccountId.value)
  showSuccess(`已创建 ${selectedIds.value.length} 个发布任务`)
  selectedIds.value = []
}

const retryTask = async (task: MerchantTask) => {
  await requeueTask(task.id)
  showSuccess('任务已重新排队')
  await loadTasks()
}

const settle = async (distribution: MerchantDistribution) => {
  await settleDistribution(distribution.id)
  showSuccess('结算状态已更新')
  await loadDistributions()
}

onMounted(async () => {
  accounts.value = (await getAccountList()).data?.accounts || []
  addresses.value = (await getResources('ADDRESS')).data || []
  materials.value = (await getResources('MATERIAL')).data || []
  await loadOverview()
})
</script>

<template>
  <div class="operations-page">
    <header class="page-header">
      <div><h1>运营中心</h1><p>商品素材、采集分销、发布规则与服务记录统一管理</p></div>
      <button v-if="activeView === 'resources'" class="primary-btn" @click="openCreate">新建{{ currentType.label }}</button>
    </header>

    <div class="view-tabs">
      <button :class="{ active: activeView === 'overview' }" @click="switchView('overview')">使用向导</button>
      <button :class="{ active: activeView === 'resources' }" @click="switchView('resources')">资源与规则</button>
      <button :class="{ active: activeView === 'tasks' }" @click="switchView('tasks')">任务记录</button>
      <button :class="{ active: activeView === 'distributions' }" @click="switchView('distributions')">分销结算</button>
    </div>

    <section v-if="activeView === 'overview'" class="overview">
      <div class="overview-intro"><div><h2>从素材到结算的完整流程</h2><p>按顺序完成准备、采集、发布和履约，每个步骤都可先手动执行验证，再开启定时规则。</p></div><button class="primary-btn" @click="switchType('MATERIAL')">从素材库开始</button></div>
      <div class="flow-grid">
        <button @click="switchType('ADDRESS')"><b>1</b><span><strong>准备资料</strong><small>地址库 → 素材库</small></span></button>
        <button @click="switchType('SUPPLY')"><b>2</b><span><strong>采集选品</strong><small>货源采集 → 选品规则</small></span></button>
        <button @click="switchType('PUBLISH_RULE')"><b>3</b><span><strong>发布运营</strong><small>发布规则 → 删除规则</small></span></button>
        <button @click="switchView('distributions')"><b>4</b><span><strong>分销结算</strong><small>任务结果 → 佣金结算</small></span></button>
      </div>
      <div class="overview-stats"><span>运营资源 <strong>{{ Object.values(overviewCounts).reduce((sum, count) => sum + count, 0) }}</strong></span><span>最近任务 <strong>{{ overviewTaskCount }}</strong></span><span :class="{ warn: overviewFailedCount > 0 }">失败待处理 <strong>{{ overviewFailedCount }}</strong></span></div>
      <div class="capability-grid">
        <button v-for="item in resourceTypes" :key="item.value" @click="switchType(item.value)"><span><strong>{{ item.label }}</strong><small>{{ overviewCounts[item.value] || 0 }} 条</small></span><p>{{ item.description }}</p></button>
      </div>
    </section>

    <section v-else-if="activeView === 'resources'" class="workspace">
      <aside class="type-nav">
        <div v-for="group in groups" :key="group" class="type-group">
          <span>{{ group }}</span>
          <button v-for="item in resourceTypes.filter(type => type.group === group)" :key="item.value" :class="{ active: activeType === item.value }" @click="switchType(item.value)">{{ item.label }}</button>
        </div>
      </aside>

      <div class="content-card">
        <div class="card-toolbar">
          <div><strong>{{ currentType.label }}</strong><span>{{ resources.length }} 条</span></div>
          <div v-if="activeType === 'MATERIAL'" class="batch-actions">
            <select v-model="publishAccountId"><option :value="undefined">使用素材关联账号</option><option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.accountNote || account.unb }}</option></select>
            <button class="secondary-btn" :disabled="!selectedIds.length" @click="publishSelected">批量发布</button>
          </div>
        </div>
        <div class="context-guide"><strong>{{ currentType.description }}</strong><span>{{ currentType.guide }}</span></div>
        <div class="table-scroll"><table>
          <thead><tr><th v-if="activeType === 'MATERIAL'" class="check-col"></th><th>名称</th><th>关联账号</th><th>商品/库存</th><th>状态</th><th>计划时间</th><th class="action-col">操作</th></tr></thead>
          <tbody>
            <tr v-for="resource in resources" :key="resource.id">
              <td v-if="activeType === 'MATERIAL'" class="check-col"><input v-model="selectedIds" type="checkbox" :value="resource.id"></td>
              <td><strong>{{ resource.name }}</strong><small>{{ resourceSummary(resource) }}</small></td>
              <td>{{ accountName(resource.xianyuAccountId) }}</td>
              <td><span v-if="resource.xyGoodsId">{{ resource.xyGoodsId }}</span><span v-else>库存 {{ resource.stock }}</span></td>
              <td><span class="status" :class="{ enabled: resource.status === 1 }">{{ statusText(resource.status) }}</span></td>
              <td>{{ formatTime(resource.scheduledTime) }}</td>
              <td class="actions">
                <button @click="openEdit(resource)">编辑</button>
                <button v-if="['SUPPLY','SELECTION_RULE','PUBLISH_RULE','DELETE_RULE','PROMOTION_ACCOUNT','MATERIAL'].includes(resource.resourceType)" @click="runResource(resource)">{{ actionText(resource.resourceType) }}</button>
                <button v-if="resource.resourceType === 'SUPPLY'" @click="convertSupply(resource)">转素材</button>
                <button v-if="resource.resourceType === 'MATERIAL'" @click="compensate(resource)">补偿</button>
                <button class="danger" @click="removeResource(resource)">删除</button>
              </td>
            </tr>
            <tr v-if="!loading && !resources.length"><td :colspan="activeType === 'MATERIAL' ? 7 : 6" class="empty">暂无{{ currentType.label }}，创建后按上方指引继续</td></tr>
          </tbody>
        </table></div>
      </div>
    </section>

    <section v-else-if="activeView === 'tasks'" class="content-card full-card">
      <div class="card-toolbar"><div><strong>任务记录</strong><span>{{ tasks.length }} 条</span></div><button class="secondary-btn" @click="loadTasks">刷新</button></div>
      <div class="table-scroll"><table><thead><tr><th>任务</th><th>资源</th><th>账号</th><th>状态</th><th>执行次数</th><th>计划时间</th><th>结果</th><th>操作</th></tr></thead><tbody>
        <tr v-for="task in tasks" :key="task.id"><td><strong>{{ taskName(task.taskType) }}</strong><small>#{{ task.id }}</small></td><td>{{ task.resourceId ? `#${task.resourceId}` : '-' }}</td><td>{{ accountName(task.xianyuAccountId) }}</td><td><span class="status" :class="{ enabled: task.status === 2, failed: task.status === -1 }">{{ taskStatusText(task.status) }}</span></td><td>{{ task.attemptCount }}/{{ task.maxAttempts }}</td><td>{{ formatTime(task.scheduledTime) }}</td><td class="result-cell" :title="task.errorMessage || task.resultJson || ''">{{ task.errorMessage || task.resultJson || '-' }}</td><td class="actions"><button v-if="task.status === -1" @click="retryTask(task)">重新执行</button></td></tr>
        <tr v-if="!loading && !tasks.length"><td colspan="8" class="empty">暂无任务记录</td></tr>
      </tbody></table></div>
    </section>

    <section v-else class="content-card full-card">
      <div class="card-toolbar"><div><strong>分销与结算</strong><span>{{ distributions.length }} 条</span></div><button class="secondary-btn" @click="loadDistributions">刷新</button></div>
      <div class="table-scroll"><table><thead><tr><th>货源</th><th>素材</th><th>账号</th><th>商品</th><th>佣金</th><th>发布状态</th><th>结算状态</th><th>操作</th></tr></thead><tbody>
        <tr v-for="item in distributions" :key="item.id"><td>#{{ item.supplyResourceId }}</td><td>{{ item.materialResourceId ? `#${item.materialResourceId}` : '-' }}</td><td>{{ accountName(item.xianyuAccountId) }}</td><td>{{ item.xyGoodsId || '-' }}</td><td>¥{{ Number(item.commissionAmount || 0).toFixed(2) }}</td><td>{{ item.status === 1 ? '已发布' : '待发布' }}</td><td><span class="status" :class="{ enabled: item.settlementStatus === 1 }">{{ item.settlementStatus === 1 ? '已结算' : '待结算' }}</span></td><td class="actions"><button v-if="item.settlementStatus !== 1" @click="settle(item)">确认结算</button></td></tr>
        <tr v-if="!loading && !distributions.length"><td colspan="8" class="empty">暂无分销记录</td></tr>
      </tbody></table></div>
    </section>

    <div v-if="showEditor" class="dialog-mask" @click.self="showEditor = false">
      <form class="editor" @submit.prevent="submitForm">
        <header><div><h2>{{ form.id ? '编辑' : '新建' }}{{ currentType.label }}</h2><p>{{ currentType.guide }}</p></div><button type="button" @click="showEditor = false">×</button></header>
        <div class="form-grid">
          <label class="wide"><span>名称</span><input v-model="form.name" required maxlength="200" placeholder="输入便于识别的名称"></label>
          <label><span>状态</span><select v-model="form.status"><option :value="1">启用</option><option :value="0">停用</option></select></label>
          <label><span>关联账号</span><select v-model="form.xianyuAccountId"><option :value="undefined">不关联</option><option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.accountNote || account.unb }}</option></select></label>
          <template v-if="['MATERIAL','SUPPLY'].includes(form.resourceType)">
            <label><span>库存</span><input v-model.number="form.stock" type="number" min="0"></label><label><span>价格</span><input v-model.number="form.amount" type="number" min="0" step="0.01"></label>
            <label class="wide"><span>详情描述</span><textarea v-model="form.description" rows="4" placeholder="商品卖点与交付说明"></textarea></label>
            <label class="wide"><span>图片地址</span><textarea v-model="form.images" rows="3" placeholder="每行一个 HTTPS 图片地址"></textarea></label>
          </template>
          <template v-if="form.resourceType === 'MATERIAL'"><label class="wide"><span>跳转目标</span><input v-model="form.targetUrl" placeholder="用于生成站内短链"></label><label><span>卡券仓库 ID</span><input v-model.number="form.kamiConfigId" type="number" min="1" placeholder="可选"></label></template>
          <template v-if="['MATERIAL','PUBLISH_RULE'].includes(form.resourceType)"><label><span>发布地址</span><select v-model="form.addressId"><option :value="undefined">平台默认地址</option><option v-for="address in addresses" :key="address.id" :value="address.id">{{ address.name }}</option></select></label></template>
          <template v-if="form.resourceType === 'SUPPLY'"><label class="wide"><span>来源地址</span><input v-model="form.sourceUrl" placeholder="货源详情地址"></label><label><span>预计佣金</span><input v-model.number="form.commissionAmount" type="number" min="0" step="0.01"></label><label><span>闲鱼商品 ID</span><input v-model="form.xyGoodsId"></label></template>
          <template v-if="form.resourceType === 'ADDRESS'"><label><span>省份</span><input v-model="form.province"></label><label><span>城市</span><input v-model="form.city"></label><label class="wide"><span>详细地址</span><input v-model="form.detail"></label></template>
          <template v-if="form.resourceType === 'SELECTION_RULE'"><label><span>关键词</span><input v-model="form.keyword"></label><label><span>最低库存</span><input v-model.number="form.minStock" type="number" min="0"></label><label><span>最低价格</span><input v-model.number="form.minAmount" type="number" min="0"></label><label><span>最高价格</span><input v-model.number="form.maxAmount" type="number" min="0"></label></template>
           <template v-if="form.resourceType === 'PUBLISH_RULE'"><label><span>发布素材</span><select v-model="form.materialId"><option :value="undefined">请选择素材</option><option v-for="material in materials" :key="material.id" :value="material.id">{{ material.name }}</option></select></label></template>
          <template v-if="form.resourceType === 'DELETE_RULE'"><label><span>闲鱼商品 ID</span><input v-model="form.xyGoodsId"></label></template>
          <template v-if="['SELECTION_RULE','PUBLISH_RULE','DELETE_RULE'].includes(form.resourceType)"><label><span>执行间隔（分钟）</span><input v-model.number="form.intervalMinutes" type="number" min="5"></label><label><span>下次执行</span><input v-model="form.scheduledTime" type="datetime-local"></label></template>
          <template v-if="['ANNOUNCEMENT','FEEDBACK','RISK_EVENT'].includes(form.resourceType)"><label class="wide"><span>内容</span><textarea v-model="form.content" rows="5"></textarea></label><label v-if="form.resourceType === 'RISK_EVENT'"><span>风险级别</span><select v-model="form.level"><option>INFO</option><option>WARN</option><option>HIGH</option></select></label></template>
         </div>
         <div class="form-help"><strong>保存后的下一步</strong><span>{{ currentType.guide }}</span></div>
        <footer><button type="button" class="secondary-btn" @click="showEditor = false">取消</button><button type="submit" class="primary-btn">保存</button></footer>
      </form>
    </div>
  </div>
</template>

<style scoped>
.operations-page{padding:24px;color:#101828}.page-header{display:flex;justify-content:space-between;align-items:flex-start;margin-bottom:20px}.page-header h1{font-size:24px;margin:0 0 6px}.page-header p,.editor p{margin:0;color:#667085;font-size:14px}.view-tabs{display:flex;gap:4px;border-bottom:1px solid #e4e7ec;margin-bottom:16px}.view-tabs button{border:0;background:transparent;padding:10px 16px;color:#667085;cursor:pointer;border-bottom:2px solid transparent}.view-tabs button.active{color:#155eef;border-bottom-color:#155eef;font-weight:600}.workspace{display:grid;grid-template-columns:180px minmax(0,1fr);gap:16px}.type-nav,.content-card{background:#fff;border:1px solid #e4e7ec;border-radius:8px}.type-nav{padding:12px;height:max-content}.type-group{display:flex;flex-direction:column;margin-bottom:12px}.type-group>span{font-size:12px;color:#98a2b3;padding:7px 10px}.type-group button{border:0;background:transparent;text-align:left;padding:9px 10px;border-radius:6px;color:#475467;cursor:pointer}.type-group button.active{background:#eef4ff;color:#155eef;font-weight:600}.card-toolbar{min-height:60px;display:flex;align-items:center;justify-content:space-between;padding:0 16px;border-bottom:1px solid #e4e7ec}.card-toolbar strong{font-size:16px}.card-toolbar span{font-size:12px;color:#98a2b3;margin-left:8px}.batch-actions{display:flex;gap:8px}.batch-actions select{min-width:170px}.table-scroll{overflow:auto}table{width:100%;border-collapse:collapse;font-size:13px}th{background:#f9fafb;color:#475467;text-align:left;font-weight:600;padding:11px 12px;white-space:nowrap}td{padding:12px;border-top:1px solid #eaecf0;color:#344054}td strong,td small{display:block}td small{color:#98a2b3;margin-top:3px}.check-col{width:32px}.action-col{width:190px}.actions{white-space:nowrap}.actions button{border:0;background:transparent;color:#155eef;cursor:pointer;padding:4px 6px}.actions .danger{color:#d92d20}.status{display:inline-flex;padding:3px 8px;border-radius:10px;background:#f2f4f7;color:#667085}.status.enabled{background:#ecfdf3;color:#027a48}.status.failed{background:#fef3f2;color:#b42318}.result-cell{max-width:260px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.empty{text-align:center!important;color:#98a2b3!important;padding:48px!important}.primary-btn,.secondary-btn{height:36px;padding:0 14px;border-radius:6px;cursor:pointer;font-weight:500}.primary-btn{border:1px solid #155eef;background:#155eef;color:#fff}.secondary-btn{border:1px solid #d0d5dd;background:#fff;color:#344054}.secondary-btn:disabled{opacity:.5}.dialog-mask{position:fixed;inset:0;background:rgba(16,24,40,.35);z-index:1000;display:flex;align-items:center;justify-content:center;padding:20px}.editor{width:min(720px,100%);max-height:90vh;overflow:auto;background:#fff;border-radius:10px}.editor header,.editor footer{display:flex;align-items:center;justify-content:space-between;padding:18px 22px;border-bottom:1px solid #eaecf0}.editor header h2{font-size:18px;margin:0 0 4px}.editor header button{border:0;background:transparent;font-size:24px;color:#667085;cursor:pointer}.editor footer{border-top:1px solid #eaecf0;border-bottom:0;justify-content:flex-end;gap:10px}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px;padding:22px}.form-grid label{display:flex;flex-direction:column;gap:6px}.form-grid label.wide{grid-column:1/-1}.form-grid label>span{font-size:13px;font-weight:500;color:#344054}input,select,textarea{box-sizing:border-box;width:100%;border:1px solid #d0d5dd;border-radius:6px;background:#fff;padding:9px 10px;color:#101828;font:inherit}textarea{resize:vertical}input:focus,select:focus,textarea:focus{outline:0;border-color:#155eef;box-shadow:0 0 0 3px #eef4ff}.overview{display:flex;flex-direction:column;gap:16px}.overview-intro{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:22px;background:#fff;border:1px solid #e4e7ec;border-radius:8px}.overview-intro h2{margin:0 0 6px;font-size:20px}.overview-intro p{margin:0;color:#667085;font-size:13px}.flow-grid,.capability-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:12px}.flow-grid button,.capability-grid button{display:flex;text-align:left;border:1px solid #e4e7ec;background:#fff;border-radius:8px;padding:15px;cursor:pointer}.flow-grid button{align-items:center;gap:12px}.flow-grid b{display:grid;place-items:center;width:28px;height:28px;border-radius:50%;background:#eef4ff;color:#155eef}.flow-grid span,.capability-grid span{display:flex;flex-direction:column;gap:4px}.flow-grid small,.capability-grid small{color:#98a2b3}.overview-stats{display:flex;gap:12px}.overview-stats span{flex:1;padding:14px 16px;background:#fff;border:1px solid #e4e7ec;border-radius:8px;color:#667085}.overview-stats strong{float:right;color:#101828}.overview-stats .warn,.overview-stats .warn strong{color:#b42318}.capability-grid{grid-template-columns:repeat(2,1fr)}.capability-grid button{flex-direction:column;gap:8px}.capability-grid button>span{flex-direction:row;justify-content:space-between}.capability-grid p{margin:0;color:#667085;font-size:12px;line-height:1.5}.context-guide{display:flex;flex-direction:column;gap:5px;margin:14px 16px 0;padding:11px 13px;border-left:3px solid #155eef;background:#f8faff}.context-guide strong{font-size:13px}.context-guide span,.form-help span{color:#667085;font-size:12px}.form-help{display:flex;flex-direction:column;gap:4px;margin:0 22px 18px;padding:11px 13px;background:#f9fafb;border-radius:7px}.form-help strong{font-size:12px}@media(max-width:900px){.operations-page{padding:16px}.workspace{grid-template-columns:1fr}.type-nav{display:flex;overflow:auto;gap:4px}.type-group{display:contents}.type-group>span{display:none}.type-group button{white-space:nowrap}.page-header p{display:none}.flow-grid{grid-template-columns:repeat(2,1fr)}}@media(max-width:640px){.form-grid{grid-template-columns:1fr}.form-grid label.wide{grid-column:auto}.card-toolbar{gap:8px;align-items:flex-start;flex-direction:column;padding:12px}.batch-actions{width:100%}.page-header h1{font-size:20px}.overview-intro{align-items:flex-start;flex-direction:column}.flow-grid,.capability-grid{grid-template-columns:1fr}.overview-stats{flex-direction:column}}
</style>
