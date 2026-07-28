<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getAccountList } from '@/api/account'
import { executeResource, getResources, getTasks, saveResource, type MerchantResource, type MerchantTask } from '@/api/merchant'
import type { Account } from '@/types'
import { toast } from '@/utils/toast'
import '@/styles/merchant-workbench.css'

type NodeType = 'TRIGGER' | 'SEARCH' | 'FILTER' | 'COLLECT' | 'MATERIAL' | 'PUBLISH'
interface WorkflowNode { id: string; type: NodeType; name: string; x: number; y: number; config: Record<string, any> }
interface WorkflowEdge { source: string; target: string }
interface DragState { id: string; offsetX: number; offsetY: number; moved: boolean }

const nodeOptions: Array<{ type: NodeType; label: string }> = [
  { type: 'SEARCH', label: '商机搜索' },
  { type: 'FILTER', label: '机会筛选' },
  { type: 'COLLECT', label: '写入货源' },
  { type: 'MATERIAL', label: '生成素材' },
  { type: 'PUBLISH', label: '商品发布' }
]

const workflows = ref<MerchantResource[]>([])
const tasks = ref<MerchantTask[]>([])
const accounts = ref<Account[]>([])
const accountId = ref<number>()
const selectedId = ref<number>()
const selectedNodeId = ref('')
const name = ref('商机采集与发布')
const nodes = ref<WorkflowNode[]>([])
const edges = ref<WorkflowEdge[]>([])
const loading = ref(false)
const canvas = ref<HTMLElement>()
const connectingFrom = ref('')
const dragState = ref<DragState>()

const selected = computed(() => workflows.value.find(item => item.id === selectedId.value))
const selectedNode = computed(() => nodes.value.find(node => node.id === selectedNodeId.value))
const selectedTasks = computed(() => tasks.value.filter(task => task.resourceId === selectedId.value))
const latestTask = computed(() => selectedTasks.value[0])
const latestNodeResults = computed(() => {
  if (!latestTask.value?.resultJson) return []
  try {
    return JSON.parse(latestTask.value.resultJson)?.nodes || []
  } catch {
    return []
  }
})
const metrics = computed(() => ({
  total: tasks.value.length,
  success: tasks.value.filter(task => task.status === 2).length,
  failed: tasks.value.filter(task => task.status === -1).length,
  rate: tasks.value.length ? Math.round(tasks.value.filter(task => task.status === 2).length * 100 / tasks.value.length) : 0
}))

const resetDefinition = () => {
  nodes.value = [
    { id: 'trigger', type: 'TRIGGER', name: '手动触发', x: 40, y: 210, config: {} },
    { id: 'search', type: 'SEARCH', name: '商机搜索', x: 220, y: 90, config: { keyword: '', limit: 20 } },
    { id: 'filter', type: 'FILTER', name: '机会筛选', x: 410, y: 90, config: { minScore: 60, limit: 10 } },
    { id: 'collect', type: 'COLLECT', name: '写入货源', x: 410, y: 280, config: {} },
    { id: 'material', type: 'MATERIAL', name: '生成素材', x: 600, y: 280, config: {} },
    { id: 'publish', type: 'PUBLISH', name: '商品发布', x: 790, y: 210, config: { dryRun: true } }
  ]
  edges.value = [
    { source: 'trigger', target: 'search' },
    { source: 'search', target: 'filter' },
    { source: 'filter', target: 'collect' },
    { source: 'collect', target: 'material' },
    { source: 'material', target: 'publish' }
  ]
  selectedNodeId.value = 'trigger'
  connectingFrom.value = ''
}

const load = async () => {
  const [workflowResult, taskResult, accountResult] = await Promise.all([
    getResources('WORKFLOW'),
    getTasks({ taskType: 'WORKFLOW', limit: 20 }),
    getAccountList()
  ])
  workflows.value = workflowResult.data || []
  tasks.value = taskResult.data || []
  accounts.value = accountResult.data?.accounts || []
  accountId.value ||= accounts.value[0]?.id
  if (!selectedId.value && workflows.value.length) selectWorkflow(workflows.value[0]!)
  if (!nodes.value.length) resetDefinition()
}

const selectWorkflow = (workflow: MerchantResource) => {
  selectedId.value = workflow.id
  name.value = workflow.name
  accountId.value = workflow.xianyuAccountId || accounts.value[0]?.id
  nodes.value = Array.isArray(workflow.data?.nodes) ? workflow.data.nodes : []
  edges.value = Array.isArray(workflow.data?.edges) ? workflow.data.edges : []
  selectedNodeId.value = nodes.value[0]?.id || ''
  connectingFrom.value = ''
}

const newWorkflow = () => {
  selectedId.value = undefined
  name.value = '新工作流'
  accountId.value = accounts.value[0]?.id
  resetDefinition()
}

const save = async () => {
  if (!name.value.trim()) throw new Error('工作流名称不能为空')
  if (!accountId.value) throw new Error('请选择执行账号')
  const response = await saveResource({
    id: selectedId.value,
    resourceType: 'WORKFLOW',
    name: name.value,
    status: 1,
    xianyuAccountId: accountId.value,
    data: { nodes: nodes.value, edges: edges.value }
  })
  selectedId.value = response.data?.id
  await load()
  toast.success('工作流已保存')
}

const run = async () => {
  loading.value = true
  try {
    await save()
    if (!selectedId.value) return
    const response = await executeResource(selectedId.value)
    await load()
    if (response.data?.status === -1) {
      return toast.error(response.data.errorMessage || '工作流执行失败')
    }
    toast.success('工作流所有节点执行完成')
  } finally {
    loading.value = false
  }
}

const addNode = (type: NodeType) => {
  if (nodes.value.length >= 24) return toast.error('单个工作流最多24个节点')
  if (nodes.value.some(node => node.type === type)) return toast.error('同一种业务节点只能添加一次')
  const index = nodes.value.filter(node => node.type === type).length + 1
  const option = nodeOptions.find(item => item.type === type)
  const id = `${type.toLowerCase()}-${Date.now()}`
  const config = type === 'SEARCH' ? { keyword: '', limit: 20 }
    : type === 'FILTER' ? { minScore: 60, limit: 10 }
      : type === 'PUBLISH' ? { dryRun: true } : {}
  nodes.value.push({
    id,
    type,
    name: `${option?.label || type}${index > 1 ? ` ${index}` : ''}`,
    x: 230 + (nodes.value.length % 3) * 190,
    y: 80 + Math.floor(nodes.value.length / 3) * 130,
    config
  })
  selectedNodeId.value = id
}

const removeSelectedNode = () => {
  const node = selectedNode.value
  if (!node || node.type === 'TRIGGER') return
  nodes.value = nodes.value.filter(item => item.id !== node.id)
  edges.value = edges.value.filter(edge => edge.source !== node.id && edge.target !== node.id)
  selectedNodeId.value = 'trigger'
  connectingFrom.value = ''
}

const beginConnect = () => {
  if (!selectedNode.value) return
  connectingFrom.value = selectedNode.value.id
  toast.info('请选择下一个节点完成连线')
}

const selectOrConnect = (node: WorkflowNode) => {
  if (dragState.value?.moved) return
  if (connectingFrom.value) {
    const source = connectingFrom.value
    connectingFrom.value = ''
    if (source === node.id) return
    if (edges.value.some(edge => edge.source === source && edge.target === node.id)) return
    edges.value.push({ source, target: node.id })
  }
  selectedNodeId.value = node.id
}

const removeEdge = (edge: WorkflowEdge) => {
  edges.value = edges.value.filter(item => item !== edge)
}

const beginDrag = (event: PointerEvent, node: WorkflowNode) => {
  if (!canvas.value || event.button !== 0) return
  const rect = canvas.value.getBoundingClientRect()
  selectedNodeId.value = node.id
  dragState.value = {
    id: node.id,
    offsetX: event.clientX - rect.left + canvas.value.scrollLeft - node.x,
    offsetY: event.clientY - rect.top + canvas.value.scrollTop - node.y,
    moved: false
  }
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
}

const drag = (event: PointerEvent) => {
  if (!canvas.value || !dragState.value) return
  const node = nodes.value.find(item => item.id === dragState.value?.id)
  if (!node) return
  const rect = canvas.value.getBoundingClientRect()
  node.x = Math.max(8, Math.min(1040, event.clientX - rect.left + canvas.value.scrollLeft - dragState.value.offsetX))
  node.y = Math.max(8, Math.min(540, event.clientY - rect.top + canvas.value.scrollTop - dragState.value.offsetY))
  dragState.value.moved = true
}

const endDrag = () => {
  setTimeout(() => {
    dragState.value = undefined
  }, 0)
}

const line = (edge: WorkflowEdge) => {
  const source = nodes.value.find(node => node.id === edge.source)
  const target = nodes.value.find(node => node.id === edge.target)
  return source && target ? { x1: source.x + 145, y1: source.y + 40, x2: target.x, y2: target.y + 40 } : undefined
}

const statusText = (status: number) => status === 2 ? '成功' : status === -1 ? '失败' : status === 1 ? '执行中' : '等待'

onMounted(load)
</script>

<template>
  <section class="workbench workflow">
    <header class="workbench__header">
      <div><h1>工作流</h1><p>拖动节点设计单路径业务链；保存时校验顺序和连线，运行后逐节点展示平台结果。</p></div>
      <div class="workbench__actions">
        <button class="workbench__btn" :disabled="loading" @click="save">保存工作流</button>
        <button class="workbench__btn workbench__btn--primary" :disabled="loading" @click="run">{{ loading ? '执行中' : '运行工作流' }}</button>
      </div>
    </header>

    <div class="workbench__grid">
      <article class="workbench__card workbench__metric"><span>工作流</span><strong>{{ workflows.length }}</strong></article>
      <article class="workbench__card workbench__metric"><span>执行次数</span><strong>{{ metrics.total }}</strong></article>
      <article class="workbench__card workbench__metric"><span>成功</span><strong>{{ metrics.success }}</strong></article>
      <article class="workbench__card workbench__metric"><span>成功率</span><strong>{{ metrics.rate }}%</strong></article>
    </div>

    <div class="workflow__layout workbench__section">
      <aside class="workbench__card workflow__list">
        <input v-model="name" class="workbench__input" placeholder="工作流名称">
        <select v-model="accountId" class="workbench__select">
          <option :value="undefined">选择执行账号</option>
          <option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.accountNote || account.unb }}</option>
        </select>
        <div class="workflow__saved">
          <button v-for="workflow in workflows" :key="workflow.id" class="workflow__list-item" :class="{ 'workflow__list-item--active': selectedId === workflow.id }" @click="selectWorkflow(workflow)">
            <strong>{{ workflow.name }}</strong><small>{{ workflow.status === 1 ? '已启用' : '草稿' }}</small>
          </button>
        </div>
        <button class="workbench__btn workbench__btn--primary" @click="newWorkflow">新建工作流</button>
      </aside>

      <main class="workflow__main">
        <div class="workbench__card workflow__palette">
          <span>添加节点</span>
          <button v-for="option in nodeOptions" :key="option.type" class="workbench__btn" @click="addNode(option.type)">+ {{ option.label }}</button>
          <span v-if="connectingFrom" class="workflow__connecting">正在选择下一个节点</span>
        </div>
        <div ref="canvas" class="workbench__card workflow__canvas" @pointermove="drag" @pointerup="endDrag" @pointercancel="endDrag">
          <div class="workflow__surface">
            <svg>
              <defs><marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 Z" /></marker></defs>
              <line v-for="edge in edges" :key="`${edge.source}-${edge.target}`" v-bind="line(edge)" marker-end="url(#arrow)" />
            </svg>
            <button
              v-for="node in nodes"
              :key="node.id"
              class="workflow__node"
              :class="{ 'workflow__node--active': selectedNodeId === node.id, 'workflow__node--source': connectingFrom === node.id }"
              :style="{ left: `${node.x}px`, top: `${node.y}px` }"
              @pointerdown="beginDrag($event, node)"
              @click="selectOrConnect(node)"
            >
              <span>{{ node.type }}</span><strong>{{ node.name }}</strong>
              <small>{{ node.type === 'PUBLISH' && node.config.dryRun ? '仅校验，不发布' : '真实执行' }}</small>
            </button>
          </div>
        </div>
      </main>

      <aside class="workbench__card workflow__config">
        <template v-if="selectedNode">
          <div class="workflow__config-title"><h2>节点配置</h2><button v-if="selectedNode.type !== 'TRIGGER'" @click="removeSelectedNode">删除</button></div>
          <label class="workbench__field">节点名称<input v-model="selectedNode.name" class="workbench__input"></label>
          <template v-if="selectedNode.type === 'SEARCH'">
            <label class="workbench__field">搜索关键词<input v-model="selectedNode.config.keyword" class="workbench__input" placeholder="例如：华为 Mate 80"></label>
            <label class="workbench__field">候选数量<input v-model.number="selectedNode.config.limit" class="workbench__input" type="number" min="1" max="50"></label>
          </template>
          <template v-if="selectedNode.type === 'FILTER'">
            <label class="workbench__field">最低机会分<input v-model.number="selectedNode.config.minScore" class="workbench__input" type="number" min="0" max="100"></label>
            <label class="workbench__field">保留数量<input v-model.number="selectedNode.config.limit" class="workbench__input" type="number" min="1" max="50"></label>
          </template>
          <label v-if="selectedNode.type === 'PUBLISH'" class="workflow__switch">
            <span><strong>仅校验</strong><small>关闭后会真实发布商品</small></span>
            <input v-model="selectedNode.config.dryRun" type="checkbox">
          </label>
          <button class="workbench__btn workbench__btn--primary" @click="beginConnect">连接下一个节点</button>
          <div class="workflow__edges">
            <strong>当前连线</strong>
            <div v-for="edge in edges.filter(item => item.source === selectedNode?.id || item.target === selectedNode?.id)" :key="`${edge.source}-${edge.target}`">
              <span>{{ edge.source }} → {{ edge.target }}</span><button @click="removeEdge(edge)">移除</button>
            </div>
          </div>
        </template>
      </aside>
    </div>

    <div class="workflow__results workbench__section">
      <article class="workbench__card">
        <h2 class="workbench__section-title">最近一次节点结果</h2>
        <div v-if="latestNodeResults.length" class="workflow__node-results">
          <div v-for="result in latestNodeResults" :key="result.nodeId">
            <span>{{ result.type }}</span><strong>{{ result.nodeId }}</strong>
            <small>{{ result.count !== undefined ? `处理 ${result.count} 条` : result.status || '完成' }}</small>
          </div>
        </div>
        <div v-else class="workbench__empty">运行工作流后显示各节点真实结果</div>
      </article>
      <article class="workbench__card">
        <h2 class="workbench__section-title">执行记录</h2>
        <div class="workbench__list">
          <div v-for="task in selectedTasks" :key="task.id" class="workflow__task">
            <span>#{{ task.id }}</span><strong>{{ selected?.name || '工作流' }}</strong>
            <span class="workbench__tag" :class="{ 'workbench__tag--good': task.status === 2, 'workbench__tag--warn': task.status === -1 }">{{ statusText(task.status) }}</span>
            <small>{{ task.errorMessage || task.createdTime }}</small>
          </div>
          <div v-if="!selectedTasks.length" class="workbench__empty">当前工作流尚无执行记录</div>
        </div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.workflow__layout { display: grid; grid-template-columns: 220px minmax(0, 1fr) 270px; gap: 12px; min-height: 610px; }
.workflow__list, .workflow__config { display: flex; min-height: 0; flex-direction: column; gap: 10px; }
.workflow__saved { display: flex; min-height: 0; overflow-y: auto; flex: 1; flex-direction: column; gap: 8px; }
.workflow__list-item { display: flex; align-items: flex-start; flex-direction: column; gap: 4px; padding: 11px; border: 1px solid #eaecf0; border-radius: 7px; color: #344054; background: #fff; text-align: left; cursor: pointer; }
.workflow__list-item small { color: #667085; }
.workflow__list-item--active { border-color: #84adff; background: #f5f8ff; }
.workflow__main { display: flex; min-width: 0; flex-direction: column; gap: 10px; }
.workflow__palette { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; padding: 9px; }
.workflow__palette > span:first-child { margin-right: 4px; color: #667085; font-size: 12px; }
.workflow__connecting { color: #155eef; font-size: 12px; }
.workflow__canvas { position: relative; min-height: 540px; overflow: auto; padding: 0; cursor: default; touch-action: none; background-color: #fbfcfe; background-image: linear-gradient(#eaecf0 1px, transparent 1px), linear-gradient(90deg, #eaecf0 1px, transparent 1px); background-size: 20px 20px; }
.workflow__surface { position: relative; width: 1200px; height: 620px; }
.workflow__surface svg { position: absolute; width: 1200px; height: 620px; pointer-events: none; }
.workflow__surface line { stroke: #84adff; stroke-width: 2; }
.workflow__surface marker path { fill: #84adff; }
.workflow__node { position: absolute; display: flex; width: 145px; min-height: 80px; align-items: flex-start; flex-direction: column; justify-content: center; gap: 4px; padding: 10px 12px; border: 1px solid #b2ccff; border-left: 4px solid #155eef; border-radius: 8px; color: #344054; background: #fff; box-shadow: 0 4px 12px rgba(16, 24, 40, .08); text-align: left; cursor: grab; user-select: none; touch-action: none; }
.workflow__node:active { cursor: grabbing; }
.workflow__node span, .workflow__node small { color: #667085; font-size: 10px; }
.workflow__node--active { outline: 3px solid rgba(21, 94, 239, .12); }
.workflow__node--source { border-color: #079455; border-left-color: #079455; }
.workflow__config h2 { margin: 0; font-size: 16px; }
.workflow__config-title { display: flex; align-items: center; justify-content: space-between; }
.workflow__config-title button, .workflow__edges button { border: 0; color: #d92d20; background: transparent; cursor: pointer; }
.workflow__switch { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px; border: 1px solid #eaecf0; border-radius: 7px; }
.workflow__switch span { display: flex; flex-direction: column; gap: 3px; }
.workflow__switch small { color: #667085; }
.workflow__edges { display: flex; flex-direction: column; gap: 7px; padding-top: 8px; border-top: 1px solid #eaecf0; font-size: 11px; }
.workflow__edges > div { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
.workflow__edges span { overflow: hidden; color: #667085; text-overflow: ellipsis; white-space: nowrap; }
.workflow__results { display: grid; grid-template-columns: minmax(320px, .8fr) minmax(420px, 1.2fr); gap: 12px; }
.workflow__node-results { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
.workflow__node-results > div { display: flex; min-width: 0; flex-direction: column; gap: 3px; padding: 9px; border: 1px solid #eaecf0; border-radius: 7px; }
.workflow__node-results span, .workflow__node-results small { color: #667085; font-size: 11px; }
.workflow__node-results strong { overflow: hidden; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.workflow__task { display: grid; grid-template-columns: 55px minmax(120px, 1fr) 70px 1.5fr; align-items: center; gap: 10px; padding: 10px 0; border-bottom: 1px solid #eaecf0; font-size: 13px; }
.workflow__task small { overflow: hidden; color: #667085; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 1280px) { .workflow__layout { grid-template-columns: 200px minmax(0, 1fr); } .workflow__config { grid-column: 1 / -1; } }
@media (max-width: 900px) { .workflow__results { grid-template-columns: 1fr; } }
@media (max-width: 767px) { .workflow__layout { display: flex; flex-direction: column; } .workflow__list { max-height: 340px; } .workflow__canvas { min-height: 480px; } .workflow__palette { align-items: stretch; } .workflow__palette .workbench__btn { flex: 1 0 42%; } .workflow__node-results { grid-template-columns: 1fr 1fr; } .workflow__task { grid-template-columns: 45px 1fr auto; } .workflow__task small { grid-column: 1 / -1; } }
</style>
