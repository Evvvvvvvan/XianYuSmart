<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getAccountList } from '@/api/account'
import { getResources, getTasks, type MerchantResource, type MerchantTask } from '@/api/merchant'
import type { Account } from '@/types'
import '@/styles/merchant-workbench.css'

const router = useRouter()
const accounts = ref<Account[]>([])
const risks = ref<MerchantResource[]>([])
const tasks = ref<MerchantTask[]>([])
const selectedAccountId = ref<number>()
const loading = ref(false)
const modules = [
  { path: '/workflows', title: '工作流编排', description: '按商机搜索、筛选、入库、素材和发布节点自动执行。', action: '设计工作流' },
  { path: '/auto-delivery', title: '自动发货', description: '按商品配置固定内容或卡密，并跟踪发货结果。', action: '配置发货' },
  { path: '/auto-reply', title: '自动回复', description: '配置商品专属和关键词回复，失败记录可追踪。', action: '配置回复' },
  { path: '/orders', title: '订单与评价', description: '管理订单同步、手动补发和商品评价策略。', action: '查看订单' },
  { path: '/goods', title: '商品自动化', description: '集中配置商品擦亮、评价和上下架状态。', action: '管理商品' },
  { path: '/operations-health', title: '通知与诊断', description: '查看连接、任务、库存异常和通知发送记录。', action: '检查运行状态' }
]

const selectedAccount = computed(() => accounts.value.find(account => account.id === selectedAccountId.value))

const load = async () => {
  loading.value = true
  try {
    const [accountResult, riskResult, taskResult] = await Promise.all([
      getAccountList(),
      getResources('RISK_EVENT'),
      getTasks({ limit: 30 })
    ])
    accounts.value = accountResult.data?.accounts || []
    risks.value = riskResult.data || []
    tasks.value = taskResult.data || []
    selectedAccountId.value ||= accounts.value[0]?.id
  } finally {
    loading.value = false
  }
}

const openVerification = () => {
  window.open('https://www.goofish.com/im', '_blank', 'noopener,noreferrer')
}

const taskStatus = (task: MerchantTask) => task.status === 2 ? '成功' : task.status === -1 ? '失败' : task.status === 1 ? '执行中' : '等待'

onMounted(load)
</script>

<template>
  <section class="workbench">
    <header class="workbench__header">
      <div><h1>自动化</h1><p>从业务入口进入配置，集中查看执行记录；平台验证仅在异常时人工接管。</p></div>
      <button class="workbench__btn" :disabled="loading" @click="load">刷新状态</button>
    </header>

    <div class="workbench__grid">
      <article class="workbench__card workbench__metric"><span>账号</span><strong>{{ accounts.length }}</strong></article>
      <article class="workbench__card workbench__metric"><span>最近任务</span><strong>{{ tasks.length }}</strong></article>
      <article class="workbench__card workbench__metric"><span>执行失败</span><strong>{{ tasks.filter(task => task.status === -1).length }}</strong></article>
      <article class="workbench__card workbench__metric"><span>平台验证待处理</span><strong>{{ risks.filter(item => item.status === 1).length }}</strong></article>
    </div>

    <div class="automation__modules workbench__section">
      <button v-for="module in modules" :key="module.path" class="workbench__card automation__module" @click="router.push(module.path)">
        <div><strong>{{ module.title }}</strong><p>{{ module.description }}</p></div>
        <span>{{ module.action }} →</span>
      </button>
    </div>

    <div class="workbench__grid workbench__grid--two workbench__section">
      <article class="workbench__card">
        <h2 class="workbench__section-title">连接异常时人工接管</h2>
        <label class="workbench__field">选择账号
          <select v-model="selectedAccountId" class="workbench__select">
            <option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.accountNote || account.unb }}</option>
          </select>
        </label>
        <ol class="automation__steps">
          <li>使用常用浏览器打开闲鱼消息页并完成平台验证。</li>
          <li>验证成功后回到连接管理，更新该账号 Cookie。</li>
          <li>可选择全自动拖动、本机人工拖动或粘贴 Cookie，验证完成后系统会刷新凭证并重连。</li>
        </ol>
        <div class="workbench__actions">
          <button class="workbench__btn workbench__btn--primary" :disabled="!selectedAccount" @click="openVerification">打开闲鱼验证页</button>
          <button class="workbench__btn" :disabled="!selectedAccountId" @click="router.push(`/connection/${selectedAccountId}`)">更新凭证</button>
        </div>
      </article>

      <article class="workbench__card">
        <h2 class="workbench__section-title">近期验证与风险事件</h2>
        <div class="workbench__list">
          <div v-for="risk in risks.slice(0, 8)" :key="risk.id" class="automation__event">
            <span class="workbench__tag workbench__tag--warn">{{ risk.data?.level || '提醒' }}</span>
            <div><strong>{{ risk.name }}</strong><small>{{ risk.data?.content || risk.updatedTime }}</small></div>
          </div>
          <div v-if="!risks.length" class="workbench__empty">当前没有平台验证事件</div>
        </div>
      </article>
    </div>

    <article class="workbench__card workbench__section">
      <h2 class="workbench__section-title">任务执行记录</h2>
      <div class="automation__table">
        <div v-for="task in tasks" :key="task.id" class="automation__task">
          <span>#{{ task.id }}</span><strong>{{ task.taskType }}</strong>
          <span class="workbench__tag" :class="{ 'workbench__tag--good': task.status === 2, 'workbench__tag--warn': task.status === -1 }">{{ taskStatus(task) }}</span>
          <small>{{ task.errorMessage || task.createdTime }}</small>
        </div>
        <div v-if="!tasks.length" class="workbench__empty">暂无自动化任务</div>
      </div>
    </article>
  </section>
</template>

<style scoped>
.automation__modules { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.automation__module { display: flex; min-height: 132px; align-items: flex-start; justify-content: space-between; flex-direction: column; color: #344054; text-align: left; cursor: pointer; }
.automation__module strong { font-size: 15px; }
.automation__module p { margin: 8px 0 0; color: #667085; font-size: 12px; line-height: 1.7; }
.automation__module span { color: #155eef; font-size: 12px; font-weight: 600; }
.automation__steps { margin: 16px 0; padding-left: 22px; color: #475467; font-size: 13px; line-height: 1.8; }
.automation__event { display: grid; grid-template-columns: auto 1fr; align-items: start; gap: 10px; padding: 10px 0; border-bottom: 1px solid #eaecf0; }
.automation__event div { min-width: 0; }
.automation__event strong, .automation__event small { display: block; }
.automation__event small { margin-top: 4px; overflow: hidden; color: #667085; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.automation__task { display: grid; grid-template-columns: 70px 150px 80px 1fr; align-items: center; gap: 10px; padding: 10px 0; border-bottom: 1px solid #eaecf0; font-size: 13px; }
.automation__task small { overflow: hidden; color: #667085; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 1000px) { .automation__modules { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 767px) { .automation__modules { grid-template-columns: 1fr; } .automation__module { min-height: 116px; } .automation__task { grid-template-columns: 55px 1fr auto; } .automation__task small { grid-column: 1 / -1; } }
</style>
