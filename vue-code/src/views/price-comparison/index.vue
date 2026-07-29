<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getAccountList } from '@/api/account'
import { searchOpportunities, type OpportunityCandidate } from '@/api/merchant'
import type { Account } from '@/types'
import { toast } from '@/utils/toast'
import '@/styles/merchant-workbench.css'

const accounts = ref<Account[]>([])
const accountId = ref<number>()
const keyword = ref('')
const minPrice = ref<number | ''>('')
const maxPrice = ref<number | ''>('')
const sortMode = ref<'relevance' | 'price-asc' | 'price-desc'>('relevance')
const loading = ref(false)
const searched = ref(false)
const results = ref<OpportunityCandidate[]>([])
const platformTotal = ref(0)

const priceNumber = (value?: string | number) => {
  const normalized = String(value ?? '').replace(/[^0-9.]/g, '')
  const price = Number(normalized)
  return Number.isFinite(price) ? price : 0
}

const filteredResults = computed(() => {
  const minimum = minPrice.value === '' ? undefined : Number(minPrice.value)
  const maximum = maxPrice.value === '' ? undefined : Number(maxPrice.value)
  const list = results.value.filter(item => {
    const price = priceNumber(item.price)
    if (minimum != null && price < minimum) return false
    if (maximum != null && price > maximum) return false
    return true
  })
  if (sortMode.value === 'price-asc') return [...list].sort((a, b) => priceNumber(a.price) - priceNumber(b.price))
  if (sortMode.value === 'price-desc') return [...list].sort((a, b) => priceNumber(b.price) - priceNumber(a.price))
  return list
})

const priceSummary = computed(() => {
  const prices = filteredResults.value.map(item => priceNumber(item.price)).filter(price => price > 0).sort((a, b) => a - b)
  if (!prices.length) return { lowest: 0, median: 0, highest: 0 }
  const middle = Math.floor(prices.length / 2)
  const median = prices.length % 2 ? prices[middle]! : (prices[middle - 1]! + prices[middle]!) / 2
  return { lowest: prices[0]!, median, highest: prices[prices.length - 1]! }
})

const displayFact = (value?: string | number) => value == null || value === '' ? '平台未公开' : String(value)

const loadAccounts = async () => {
  const response = await getAccountList()
  accounts.value = response.data?.accounts || []
  accountId.value ||= accounts.value[0]?.id
}

const search = async () => {
  if (!accountId.value) return toast.warning('请选择用于比价的账号')
  if (!keyword.value.trim()) return toast.warning('请输入需要比价的商品关键词')
  if (minPrice.value !== '' && maxPrice.value !== '' && minPrice.value > maxPrice.value) {
    return toast.warning('最低价不能高于最高价')
  }
  loading.value = true
  try {
    const response = await searchOpportunities({
      xianyuAccountId: accountId.value,
      keyword: keyword.value,
      pageNumber: 1,
      limit: 50
    })
    results.value = response.data?.items || []
    platformTotal.value = Number(response.data?.total || results.value.length)
    searched.value = true
  } finally {
    loading.value = false
  }
}

const resetFilters = () => {
  minPrice.value = ''
  maxPrice.value = ''
  sortMode.value = 'relevance'
}

onMounted(loadAccounts)
</script>

<template>
  <section class="workbench comparison">
    <header class="workbench__header">
      <div>
        <h1>全站比价</h1>
        <p>按闲鱼全站真实搜索结果比较价格，并集中查看平台公开的卖家评价与信用信息。</p>
      </div>
    </header>

    <div class="workbench__card comparison__search">
      <select v-model="accountId" class="workbench__select">
        <option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.accountNote || account.unb }}</option>
      </select>
      <input v-model="keyword" class="workbench__input" placeholder="输入商品关键词，例如：iPhone 15 256G" @keyup.enter="search">
      <button class="workbench__btn workbench__btn--primary" :disabled="loading" @click="search">{{ loading ? '比价中' : '开始比价' }}</button>
    </div>

    <div class="workbench__card comparison__filters">
      <label class="workbench__field">最低价<input v-model.number="minPrice" class="workbench__input" type="number" min="0" placeholder="不限"></label>
      <label class="workbench__field">最高价<input v-model.number="maxPrice" class="workbench__input" type="number" min="0" placeholder="不限"></label>
      <label class="workbench__field">排序
        <select v-model="sortMode" class="workbench__select">
          <option value="relevance">综合匹配</option>
          <option value="price-asc">价格从低到高</option>
          <option value="price-desc">价格从高到低</option>
        </select>
      </label>
      <button class="workbench__btn" @click="resetFilters">重置筛选</button>
    </div>

    <div v-if="searched" class="comparison__metrics">
      <div class="workbench__card"><span>当前结果</span><strong>{{ filteredResults.length }}</strong><small>平台匹配 {{ platformTotal || results.length }} 件</small></div>
      <div class="workbench__card"><span>最低价</span><strong>¥ {{ priceSummary.lowest || '--' }}</strong><small>当前筛选范围</small></div>
      <div class="workbench__card"><span>中位价</span><strong>¥ {{ priceSummary.median || '--' }}</strong><small>减少极端价格干扰</small></div>
      <div class="workbench__card"><span>最高价</span><strong>¥ {{ priceSummary.highest || '--' }}</strong><small>当前筛选范围</small></div>
    </div>

    <div class="comparison__list workbench__section">
      <article v-for="item in filteredResults" :key="item.itemId" class="workbench__card comparison__item">
        <img v-if="item.images?.[0]" :src="item.images[0]" alt="">
        <div v-else class="comparison__image-empty">暂无图片</div>
        <div class="comparison__content">
          <h2>{{ item.title }}</h2>
          <div class="comparison__seller">
            <strong>{{ item.sellerNick || '平台卖家' }}</strong>
            <span>卖家信用：{{ displayFact(item.sellerCredit) }}</span>
            <span>买家信用：{{ displayFact(item.buyerCredit) }}</span>
          </div>
          <div class="comparison__reviews">
            <span class="comparison__positive">历史好评 {{ displayFact(item.sellerPositiveCount) }}</span>
            <span class="comparison__negative">历史差评 {{ displayFact(item.sellerNegativeCount) }}</span>
          </div>
          <small>信用和评价仅展示平台本次接口公开的真实字段，未返回时不会推测或补造。</small>
        </div>
        <div class="comparison__action">
          <strong>¥ {{ item.price || '--' }}</strong>
          <a class="workbench__btn" :href="item.sourceUrl" target="_blank" rel="noopener noreferrer">查看原商品</a>
        </div>
      </article>
      <div v-if="searched && !filteredResults.length" class="workbench__card workbench__empty">当前价格范围内没有结果，可调整筛选条件后查看。</div>
      <div v-else-if="!searched" class="workbench__card workbench__empty">输入关键词后开始全站比价。</div>
    </div>
  </section>
</template>

<style scoped>
.comparison__search { display: grid; grid-template-columns: 190px minmax(0, 1fr) auto; gap: 10px; }
.comparison__filters { display: grid; grid-template-columns: repeat(3, minmax(150px, 220px)) auto; align-items: end; gap: 12px; margin-top: 12px; }
.comparison__metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-top: 12px; }
.comparison__metrics span, .comparison__metrics small { display: block; color: #667085; font-size: 12px; }
.comparison__metrics strong { display: block; margin: 7px 0 3px; font-size: 22px; }
.comparison__list { display: flex; flex-direction: column; gap: 10px; }
.comparison__item { display: grid; grid-template-columns: 92px minmax(0, 1fr) 130px; align-items: center; gap: 14px; }
.comparison__item > img, .comparison__image-empty { width: 92px; height: 92px; border-radius: 8px; object-fit: cover; background: #f2f4f7; }
.comparison__image-empty { display: grid; place-items: center; color: #98a2b3; font-size: 11px; }
.comparison__content { min-width: 0; }
.comparison__content h2 { margin: 0 0 9px; overflow: hidden; font-size: 15px; line-height: 1.5; text-overflow: ellipsis; white-space: nowrap; }
.comparison__seller, .comparison__reviews { display: flex; flex-wrap: wrap; gap: 7px 14px; color: #667085; font-size: 12px; }
.comparison__reviews { margin-top: 8px; }
.comparison__positive { color: #067647; }
.comparison__negative { color: #b42318; }
.comparison__content small { display: block; margin-top: 8px; color: #98a2b3; }
.comparison__action { display: flex; align-items: stretch; flex-direction: column; gap: 10px; }
.comparison__action > strong { color: #d92d20; font-size: 22px; text-align: right; white-space: nowrap; }
@media (max-width: 900px) {
  .comparison__filters, .comparison__metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 767px) {
  .comparison__search, .comparison__filters, .comparison__metrics { grid-template-columns: 1fr; }
  .comparison__item { grid-template-columns: 72px minmax(0, 1fr); align-items: start; }
  .comparison__item > img, .comparison__image-empty { width: 72px; height: 72px; }
  .comparison__content h2 { display: -webkit-box; white-space: normal; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
  .comparison__action { grid-column: 1 / -1; align-items: center; flex-direction: row; justify-content: space-between; }
  .comparison__action > strong { text-align: left; }
}
</style>
