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

const selected = computed(() => workflows.value.find(item => item.id === selectedId.value))
const selectedNode = computed(() => nodes.value.find(node => node.id === selectedNodeId.value))
const metrics = computed(() => ({
  total: tasks.value.length,
  success: tasks.value.filter(task => task.status === 2).length,
  failed: tasks.value.filter(task => task.status === -1).length,
  rate: tasks.value.length ? Math.round(tasks.value.filter(task => task.status === 2).length * 100 / tasks.value.length) : 0
}))

const resetDefinition = () => {
  nodes.value = [
    { id: 'trigger', type: 'TRIGGER', name: '手动触发', x: 30, y: 150, config: {} },
    { id: 'search', type: 'SEARCH', name: '商机搜索', x: 210, y: 70, config: { keyword: '', limit: 20 } },
    { id: 'filter', type: 'FILTER', name: '机会筛选', x: 390, y: 70, config: { minScore: 60, limit: 10 } },
    { id: 'collect', type: 'COLLECT', name: '写入货源', x: 390, y: 230, config: {} },
    { id: 'material', type: 'MATERIAL', name: '生成素材', x: 570, y: 230, config: {} },
    { id: 'publish', type: 'PUBLISH', name: '发布任务', x: 750, y: 150, config: { dryRun: true } }
  ]
  edges.value = [
    { source: 'trigger', target: 'search' },
    { source: 'search', target: 'filter' },
    { source: 'filter', target: 'collect' },
    { source: 'collect', target: 'material' },
    { source: 'material', target: 'publish' }
  ]
  selectedNodeId.value = 'trigger'
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
}

const newWorkflow = () => {
  selectedId.value = undefined
  name.value = '新工作流'
  accountId.value = accounts.value[0]?.id
  resetDefinition()
}

const save = async () => {
  if (!name.value.trim()) return toast.error('工作流名称不能为空')
  const response = await saveResource({
    id: selectedId.value,
    resourceType: 'WORKFLOW',
    name: name.value,
    status: 1,
    xianyuAccountId: accountId.value,
    data: { nodes: nodes.value, edges: edges.value }
  })
  selectedId.value = response.data?.id
  toast.success('工作流已保存')
  await load()
}

const run = async () => {
  if (!selectedId.value) await save()
  if (!selectedId.value) return
  loading.value = true
  try {
    await executeResource(selectedId.value)
    toast.success('工作流执行完成')
    await load()
  } finally {
    loading.value = false
  }
}

const line = (edge: WorkflowEdge) => {
  const source = nodes.value.find(node => node.id === edge.source)
  const target = nodes.value.find(node => node.id === edge.target)
  return source && target ? { x1: source.x + 135, y1: source.y + 38, x2: target.x, y2: target.y + 38 } : undefined
}

const statusText = (status: number) => status === 2 ? '成功' : status === -1 ? '失败' : status === 1 ? '执行中' : '等待'

onMounted(load)
</script>

<template>
  <section class="workbench workflow">
    <header class="workbench__header">
      <div><h1>工作流</h1><p>将商机搜索、筛选、入库、素材生成和发布编排成可追踪任务。</p></div>
      <div class="workbench__actions">
        <button class="workbench__btn" @click="save">保存草稿</button>
        <button class="workbench__btn workbench__btn--primary" :disabled="loading" @click="run">{{ loading ? '执行中' : '运行测试' }}</button>
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
          <option :value="undefined">不绑定账号</option>
          <option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.accountNote || account.unb }}</option>
        </select>
        <button v-for="workflow in workflows" :key="workflow.id" class="workflow__list-item" :class="{ 'workflow__list-item--active': selectedId === workflow.id }" @click="selectWorkflow(workflow)">
          <strong>{{ workflow.name }}</strong><small>{{ workflow.status === 1 ? '已启用' : '草稿' }}</small>
        </button>
        <button class="workbench__btn workbench__btn--primary" @click="newWorkflow">新建工作流</button>
      </aside>

      <main class="workbench__card workflow__canvas">
        <svg>
          <line v-for="edge in edges" :key="`${edge.source}-${edge.target}`" v-bind="line(edge)" />
        </svg>
        <button v-for="node in nodes" :key="node.id" class="workflow__node" :class="{ 'workflow__node--active': selectedNodeId === node.id }" :style="{ left: `${node.x}px`, top: `${node.y}px` }" @click="selectedNodeId = node.id">
          <span>{{ node.type }}</span><strong>{{ node.name }}</strong><small>{{ node.type === 'PUBLISH' && node.config.dryRun ? '安全测试' : '已配置' }}</small>
        </button>
      </main>

      <aside class="workbench__card workflow__config">
        <template v-if="selectedNode">
          <h2>节点配置</h2>
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
            <span><strong>安全测试</strong><small>开启时只生成素材，不提交平台发布</small></span>
            <input v-model="selectedNode.config.dryRun" type="checkbox">
          </label>
        </template>
      </aside>
    </div>

    <article class="workbench__card workbench__section">
      <h2 class="workbench__section-title">最近执行记录</h2>
      <div class="workbench__list">
        <div v-for="task in tasks" :key="task.id" class="workflow__task">
          <span>#{{ task.id }}</span><strong>{{ selected?.name || '工作流' }}</strong>
          <span class="workbench__tag" :class="{ 'workbench__tag--good': task.status === 2, 'workbench__tag--warn': task.status === -1 }">{{ statusText(task.status) }}</span>
          <small>{{ task.errorMessage || task.createdTime }}</small>
        </div>
        <div v-if="!tasks.length" class="workbench__empty">尚无执行记录</div>
      </div>
    </article>
  </section>
</template>

<style scoped>
.workflow__layout { display: grid; grid-template-columns: 220px minmax(600px, 1fr) 260px; gap: 12px; min-height: 500px; }
.workflow__list, .workflow__config { display: flex; flex-direction: column; gap: 10px; align-self: stretch; }
.workflow__list-item { display: flex; align-items: flex-start; flex-direction: column; gap: 4px; padding: 11px; border: 1px solid #eaecf0; border-radius: 7px; color: #344054; background: #fff; text-align: left; cursor: pointer; }
.workflow__list-item small { color: #667085; }
.workflow__list-item--active { border-color: #84adff; background: #f5f8ff; }
.workflow__canvas { position: relative; min-height: 500px; overflow: auto; background-color: #fbfcfe; background-image: linear-gradient(#eaecf0 1px, transparent 1px), linear-gradient(90deg, #eaecf0 1px, transparent 1px); background-size: 20px 20px; }
.workflow__canvas svg { position: absolute; width: 920px; height: 430px; pointer-events: none; }
.workflow__canvas line { stroke: #84adff; stroke-width: 2; }
.workflow__node { position: absolute; display: flex; width: 135px; min-height: 76px; align-items: flex-start; flex-direction: column; justify-content: center; gap: 4px; padding: 10px 12px; border: 1px solid #b2ccff; border-left: 4px solid #155eef; border-radius: 8px; color: #344054; background: #fff; box-shadow: 0 4px 12px rgba(16, 24, 40, .08); text-align: left; cursor: pointer; }
.workflow__node span, .workflow__node small { color: #667085; font-size: 10px; }
.workflow__node--active { outline: 3px solid rgba(21, 94, 239, .12); }
.workflow__config h2 { margin: 0; font-size: 16px; }
.workflow__switch { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px; border: 1px solid #eaecf0; border-radius: 7px; }
.workflow__switch span { display: flex; flex-direction: column; gap: 3px; }
.workflow__switch small { color: #667085; }
.workflow__task { display: grid; grid-template-columns: 70px minmax(150px, 1fr) 80px 2fr; align-items: center; gap: 10px; padding: 10px 0; border-bottom: 1px solid #eaecf0; font-size: 13px; }
.workflow__task small { overflow: hidden; color: #667085; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 1200px) { .workflow__layout { grid-template-columns: 200px minmax(600px, 1fr); overflow-x: auto; } .workflow__config { grid-column: 1 / -1; } }
@media (max-width: 767px) { .workflow__layout { display: flex; overflow: visible; flex-direction: column; } .workflow__canvas { min-height: 460px; overflow-x: auto; } .workflow__task { grid-template-columns: 55px 1fr auto; } .workflow__task small { grid-column: 1 / -1; } }
</style>
