<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getDataPanelStats, getDataPanelTrend, getRealtimeRevenue, type DataPanelStats, type DataPanelTrend } from '@/api/data-panel'
import '@/styles/merchant-workbench.css'

const loading = ref(false)
const stats = ref<DataPanelStats>({ orderCount: 0, deliverySuccessCount: 0, deliveryFailCount: 0, aiReplyCount: 0, hasData: false })
const trend = ref<DataPanelTrend>({ dates: [], deliverySuccess: [], deliveryFail: [], aiReplies: [] })
const revenue = ref(0)

const maxTrend = computed(() => Math.max(1, ...trend.value.deliverySuccess, ...trend.value.deliveryFail, ...trend.value.aiReplies))
const points = (values: number[]) => values.map((value, index) => {
  const x = values.length <= 1 ? 0 : index * 100 / (values.length - 1)
  const y = 90 - value * 80 / maxTrend.value
  return `${x},${y}`
}).join(' ')

const load = async () => {
  loading.value = true
  try {
    const [statsResult, trendResult, revenueResult] = await Promise.all([
      getDataPanelStats(new Date().toISOString().slice(0, 10)),
      getDataPanelTrend(),
      getRealtimeRevenue()
    ])
    if (statsResult.data) stats.value = statsResult.data
    if (trendResult.data) trend.value = trendResult.data
    revenue.value = Number(revenueResult.data || 0)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="workbench">
    <header class="workbench__header">
      <div>
        <h1>数据看板</h1>
        <p>把成交、履约和客服放在同一张经营视图里。</p>
      </div>
      <button class="workbench__btn" :disabled="loading" @click="load">{{ loading ? '刷新中' : '刷新数据' }}</button>
    </header>

    <div class="workbench__grid">
      <article class="workbench__card workbench__metric"><span>实时成交额</span><strong>¥ {{ revenue.toFixed(2) }}</strong></article>
      <article class="workbench__card workbench__metric"><span>今日订单</span><strong>{{ stats.orderCount }}</strong></article>
      <article class="workbench__card workbench__metric"><span>交付成功</span><strong>{{ stats.deliverySuccessCount }}</strong></article>
      <article class="workbench__card workbench__metric"><span>AI 回复</span><strong>{{ stats.aiReplyCount }}</strong></article>
    </div>

    <article class="workbench__card workbench__section">
      <h2 class="workbench__section-title">近 7 天服务趋势</h2>
      <div v-if="stats.hasData" class="trend-chart">
        <svg viewBox="0 0 100 100" preserveAspectRatio="none" aria-label="履约与回复趋势">
          <line v-for="y in [10, 30, 50, 70, 90]" :key="y" x1="0" :y1="y" x2="100" :y2="y" />
          <polyline class="trend-chart__success" :points="points(trend.deliverySuccess)" />
          <polyline class="trend-chart__failed" :points="points(trend.deliveryFail)" />
          <polyline class="trend-chart__reply" :points="points(trend.aiReplies)" />
        </svg>
        <div class="trend-chart__labels"><span v-for="date in trend.dates" :key="date">{{ date }}</span></div>
        <div class="workbench__tags">
          <span class="workbench__tag workbench__tag--good">交付成功</span>
          <span class="workbench__tag workbench__tag--warn">交付失败</span>
          <span class="workbench__tag">AI 回复</span>
        </div>
      </div>
      <div v-else class="workbench__empty">产生订单与消息后，这里会自动形成经营趋势。</div>
    </article>
  </section>
</template>

<style scoped>
.trend-chart { min-height: 300px; }
.trend-chart svg { width: 100%; height: 250px; overflow: visible; }
.trend-chart line { stroke: #eaecf0; stroke-width: .3; }
.trend-chart polyline { fill: none; stroke-width: 1.5; vector-effect: non-scaling-stroke; }
.trend-chart__success { stroke: #12b76a; }
.trend-chart__failed { stroke: #f04438; }
.trend-chart__reply { stroke: #155eef; }
.trend-chart__labels { display: flex; justify-content: space-between; margin: 6px 0 14px; color: #98a2b3; font-size: 11px; }
@media (max-width: 767px) { .trend-chart { min-height: 220px; } .trend-chart svg { height: 170px; } }
</style>
