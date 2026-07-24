<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useDashboard } from './useDashboard'

const router = useRouter()
const { loading, stats, loadStatistics } = useDashboard()

const todoCount = computed(() =>
  stats.pendingTaskCount + stats.reviewRequiredCount + stats.failedTaskCount + stats.lowStockConfigCount
)

const setupSteps = computed(() => [
  { title: '连接闲鱼账号', description: '扫码登录或更新 Cookie，确认连接状态正常。', path: '/connection', action: '管理连接', completed: stats.accountCount > 0 },
  { title: '同步并配置商品', description: '同步在售商品，在商品管理统一配置评价与擦亮规则。', path: '/goods', action: '配置商品', completed: stats.itemCount > 0 },
  { title: '准备卡密库存', description: '创建卡密仓库并补充库存，再关联自动发货规则。', path: '/kami-config', action: '管理库存', completed: stats.availableKamiCount > 0 },
  { title: '处理订单与评价', description: '在订单页查看履约结果、双方评价并处理待评价订单。', path: '/orders', action: '进入订单', completed: stats.todayDeliveryCount > 0 }
])

const money = (value: number) => Number(value || 0).toLocaleString('zh-CN', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2
})

const go = (path: string, query?: Record<string, string>) => router.push({ path, query })

const refreshWhenVisible = () => {
  if (document.visibilityState === 'visible') loadStatistics()
}

let refreshTimer: ReturnType<typeof setInterval> | undefined

onMounted(() => {
  loadStatistics()
  refreshTimer = setInterval(refreshWhenVisible, 60000)
  document.addEventListener('visibilitychange', refreshWhenVisible)
})

onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
  document.removeEventListener('visibilitychange', refreshWhenVisible)
})
</script>

<template>
  <main class="merchant-dashboard" :aria-busy="loading">
    <header class="merchant-dashboard__header">
      <div>
        <h1>经营概览</h1>
        <p>优先处理履约异常和卡密库存，保证订单按时交付。</p>
      </div>
      <button class="button button--secondary" :disabled="loading" @click="loadStatistics">
        {{ loading ? '刷新中' : '刷新数据' }}
      </button>
    </header>

    <section class="metric-grid" aria-label="今日经营指标">
      <article class="metric-card metric-card--primary">
        <span>今日成交额</span>
        <strong>¥ {{ money(stats.todayRevenue) }}</strong>
        <small>已成功交付订单</small>
      </article>
      <article class="metric-card">
        <span>今日履约</span>
        <strong>{{ stats.todayDeliveryCount }}</strong>
        <small>笔订单已完成</small>
      </article>
      <article class="metric-card">
        <span>可用卡密</span>
        <strong>{{ stats.availableKamiCount }}</strong>
        <small>{{ stats.lowStockConfigCount }} 个仓库库存预警</small>
      </article>
      <article class="metric-card">
        <span>在售商品</span>
        <strong>{{ stats.sellingItemCount }}</strong>
        <small>共 {{ stats.itemCount }} 个商品</small>
      </article>
    </section>

    <section class="dashboard-panel setup-panel">
      <div class="panel-heading">
        <div>
          <h2>快速上手</h2>
          <p>按顺序完成基础配置，自动回复、发货和评价才会进入稳定运行状态。</p>
        </div>
        <button class="button button--secondary" @click="go('/operations')">查看运营向导</button>
      </div>
      <div class="setup-grid">
        <button v-for="(step, index) in setupSteps" :key="step.path" class="setup-step" @click="go(step.path)">
          <span class="setup-step__number" :class="{ 'setup-step__number--done': step.completed }">{{ step.completed ? '✓' : index + 1 }}</span>
          <span class="setup-step__content"><strong>{{ step.title }}</strong><small>{{ step.description }}</small></span>
          <span class="setup-step__action">{{ step.action }}</span>
        </button>
      </div>
    </section>

    <section class="dashboard-panel">
      <div class="panel-heading">
        <div>
          <h2>待处理事项</h2>
          <p>{{ todoCount }} 项需要关注</p>
        </div>
      </div>
      <div class="todo-list">
        <button class="todo-row" @click="go('/orders', { deliveryStatus: 'PENDING,PROCESSING,RETRY_WAIT' })">
          <span class="status-dot status-dot--blue"></span>
          <span class="todo-row__label">等待履约</span>
          <strong>{{ stats.pendingTaskCount }}</strong>
          <span class="todo-row__action">查看订单</span>
        </button>
        <button class="todo-row" @click="go('/orders', { deliveryStatus: 'REVIEW_REQUIRED' })">
          <span class="status-dot status-dot--orange"></span>
          <span class="todo-row__label">需要人工核对</span>
          <strong>{{ stats.reviewRequiredCount }}</strong>
          <span class="todo-row__action">立即核对</span>
        </button>
        <button class="todo-row" @click="go('/orders', { deliveryStatus: 'FAILED' })">
          <span class="status-dot status-dot--red"></span>
          <span class="todo-row__label">履约失败</span>
          <strong>{{ stats.failedTaskCount }}</strong>
          <span class="todo-row__action">处理失败</span>
        </button>
        <button class="todo-row" @click="go('/kami-config', { lowStock: '1' })">
          <span class="status-dot status-dot--orange"></span>
          <span class="todo-row__label">卡密库存预警</span>
          <strong>{{ stats.lowStockConfigCount }}</strong>
          <span class="todo-row__action">补充库存</span>
        </button>
      </div>
    </section>

    <div class="dashboard-columns">
      <section class="dashboard-panel">
        <div class="panel-heading">
          <div>
            <h2>运行概况</h2>
            <p>核心业务数据</p>
          </div>
        </div>
        <dl class="overview-list">
          <div><dt>闲鱼账号</dt><dd>{{ stats.accountCount }}</dd></div>
          <div><dt>今日自动回复</dt><dd>{{ stats.todayReplyCount }}</dd></div>
          <div><dt>已下架商品</dt><dd>{{ stats.offShelfItemCount }}</dd></div>
          <div><dt>已售出商品</dt><dd>{{ stats.soldItemCount }}</dd></div>
        </dl>
      </section>

      <section class="dashboard-panel">
        <div class="panel-heading">
          <div>
            <h2>常用操作</h2>
            <p>快速进入经营配置</p>
          </div>
        </div>
        <div class="quick-actions">
          <button class="button button--secondary" @click="go('/kami-config')">管理卡密</button>
          <button class="button button--secondary" @click="go('/auto-delivery')">发货配置</button>
          <button class="button button--secondary" @click="go('/auto-reply')">回复配置</button>
          <button class="button button--secondary" @click="go('/orders')">订单评价</button>
          <button class="button button--secondary" @click="go('/operations')">商品运营</button>
          <button class="button button--secondary" @click="go('/connection')">连接状态</button>
        </div>
      </section>
    </div>
  </main>
</template>

<style scoped src="./dashboard.css"></style>
