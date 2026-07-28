<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { getAccountList } from '@/api/account'
import { createPublishPlan, importOpportunities, searchOpportunities, type OpportunityCandidate } from '@/api/merchant'
import { CHINA_PROVINCES, CHINA_REGIONS } from '@/data/china-regions'
import type { Account } from '@/types'
import { toast } from '@/utils/toast'
import '@/styles/merchant-workbench.css'

const accounts = ref<Account[]>([])
const accountId = ref<number>()
const keyword = ref('')
const loading = ref(false)
const results = ref<OpportunityCandidate[]>([])
const selectedIds = ref<string[]>([])
const active = ref<OpportunityCandidate>()
const step = ref(1)
const maxStep = ref(1)
const searched = ref(false)
const draft = reactive({
  name: '',
  description: '',
  amount: 0,
  stock: 1,
  category: '虚拟商品',
  province: '北京市',
  city: '北京市',
  district: '',
  deliveryMethod: '线上交付',
  images: [] as string[]
})

const cities = computed(() => CHINA_REGIONS[draft.province] || [])
const selectedCandidates = computed(() => results.value.filter(item => selectedIds.value.includes(item.itemId)))

const loadAccounts = async () => {
  const response = await getAccountList()
  accounts.value = response.data?.accounts || []
  accountId.value ||= accounts.value[0]?.id
}

const search = async () => {
  if (!keyword.value.trim()) return toast.error('请输入商品关键词')
  if (!accountId.value) return toast.error('请选择搜索账号')
  loading.value = true
  try {
    const response = await searchOpportunities({ keyword: keyword.value, xianyuAccountId: accountId.value, limit: 30 })
    results.value = response.data || []
    searched.value = true
    selectedIds.value = []
    active.value = results.value[0]
  } finally {
    loading.value = false
  }
}

const toggle = (item: OpportunityCandidate) => {
  active.value = item
  selectedIds.value = selectedIds.value.includes(item.itemId)
    ? selectedIds.value.filter(id => id !== item.itemId)
    : [...selectedIds.value, item.itemId]
}

const capture = async () => {
  if (!selectedCandidates.value.length) return toast.error('至少选择一个候选商品')
  await importOpportunities({ candidates: selectedCandidates.value, xianyuAccountId: accountId.value })
  const item = selectedCandidates.value[0]!
  active.value = item
  draft.name = item.title
  draft.description = item.title
  draft.amount = Number(item.price || 0)
  draft.images = item.images || []
  step.value = 2
  maxStep.value = 2
}

const next = () => {
  if (step.value === 2 && (!draft.name.trim() || !draft.description.trim())) return toast.error('标题和详情不能为空')
  if (step.value === 3 && (!draft.amount || !draft.images.length)) return toast.error('请补充价格和至少一张图片')
  step.value = Math.min(4, step.value + 1)
  maxStep.value = Math.max(maxStep.value, step.value)
}

const goStep = (target: number) => {
  if (target <= maxStep.value) step.value = target
}

const publish = async (dryRun = false) => {
  if (!accountId.value) return toast.error('请选择发布账号')
  loading.value = true
  try {
    const response = await createPublishPlan({ xianyuAccountId: accountId.value, ...draft, dryRun })
    if (response.data?.valid === false) {
      return toast.error(String(response.data.error || '商品发布失败'))
    }
    const itemId = response.data?.platform?.itemId
    toast.success(dryRun
      ? '平台校验通过，发布配置可用'
      : `平台已确认发布成功${itemId ? `，商品 ID：${itemId}` : ''}`)
  } finally {
    loading.value = false
  }
}

onMounted(loadAccounts)
</script>

<template>
  <section class="workbench opportunity">
    <header class="workbench__header">
      <div><h1>商机发掘</h1><p>从候选商品到发布任务，一次完成采集、整理与配置。</p></div>
    </header>

    <div class="workbench__steps">
      <button v-for="(label, index) in ['1 捕获', '2 改写', '3 配置', '4 发布']" :key="label" class="workbench__step" :class="{ 'workbench__step--active': step === index + 1 }" :disabled="index + 1 > maxStep" @click="goStep(index + 1)">{{ label }}</button>
    </div>

    <div v-if="step === 1" class="opportunity__layout workbench__section">
      <main>
        <div class="workbench__card workbench__toolbar">
          <select v-model="accountId" class="workbench__select opportunity__account">
            <option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.accountNote || account.unb }}</option>
          </select>
          <input v-model="keyword" class="workbench__input" placeholder="输入商品关键词，例如：华为 Mate 80" @keyup.enter="search">
          <button class="workbench__btn workbench__btn--primary" :disabled="loading" @click="search">{{ loading ? '搜索中' : '开始搜索' }}</button>
        </div>
        <div class="workbench__list workbench__section">
          <button v-for="item in results" :key="item.itemId" class="workbench__item opportunity__result" :class="{ 'opportunity__result--active': active?.itemId === item.itemId }" @click="toggle(item)">
            <input type="checkbox" :checked="selectedIds.includes(item.itemId)" @click.stop="toggle(item)">
            <img :src="item.images?.[0]" alt="">
            <div>
              <h3>{{ item.title }}</h3>
              <div class="workbench__tags">
                <span class="workbench__tag workbench__tag--good">机会分 {{ item.opportunityScore }}</span>
                <span class="workbench__tag" :class="{ 'workbench__tag--warn': item.riskLevel !== 'LOW' }">{{ item.riskLevel === 'LOW' ? '资料完整' : '建议复核' }}</span>
                <span class="workbench__tag">{{ item.matchReason }}</span>
              </div>
            </div>
            <strong>¥ {{ item.price || '--' }}</strong>
          </button>
          <div v-if="!results.length" class="workbench__empty">{{ searched ? '平台未返回可用商品，请更换关键词或检查账号验证状态。' : '输入关键词后开始发现候选商品。' }}</div>
        </div>
      </main>
      <aside class="workbench__card opportunity__preview">
        <template v-if="active">
          <img :src="active.images?.[0]" alt="">
          <h2>{{ active.title }}</h2>
          <strong>¥ {{ active.price || '--' }}</strong>
          <p>{{ active.matchReason }}</p>
        </template>
        <div v-else class="workbench__empty">选择商品后查看预览</div>
        <button class="workbench__btn workbench__btn--primary" :disabled="!selectedIds.length" @click="capture">下一步：整理商品</button>
      </aside>
    </div>

    <div v-else class="workbench__card opportunity__wizard workbench__section">
      <template v-if="step === 2">
        <h2>整理商品文案</h2>
        <label class="workbench__field">商品标题<input v-model="draft.name" class="workbench__input" maxlength="120"></label>
        <label class="workbench__field">商品详情<textarea v-model="draft.description" class="workbench__textarea" maxlength="3000"></textarea></label>
      </template>
      <template v-else-if="step === 3">
        <h2>配置发布参数</h2>
        <div class="workbench__grid workbench__grid--two">
          <label class="workbench__field">价格<input v-model.number="draft.amount" class="workbench__input" type="number" min="0.01" step="0.01"></label>
          <label class="workbench__field">库存<input v-model.number="draft.stock" class="workbench__input" type="number" min="1"></label>
          <label class="workbench__field">素材分类<input v-model="draft.category" class="workbench__input"><small>仅用于站内整理，真实类目由闲鱼发布接口识别。</small></label>
          <label class="workbench__field">交付方式<select v-model="draft.deliveryMethod" class="workbench__select"><option>线上交付</option><option>快递发货</option><option>当面交易</option></select></label>
          <label class="workbench__field">省份<select v-model="draft.province" class="workbench__select" @change="draft.city = cities[0] || ''"><option v-for="province in CHINA_PROVINCES" :key="province">{{ province }}</option></select></label>
          <label class="workbench__field">城市<select v-model="draft.city" class="workbench__select"><option v-for="city in cities" :key="city">{{ city }}</option></select></label>
          <label class="workbench__field">区县/详细位置<input v-model="draft.district" class="workbench__input" placeholder="站内素材备注，发布使用账号常用位置"></label>
          <label class="workbench__field">图片地址（每行一张）<textarea :value="draft.images.join('\n')" class="workbench__textarea" @input="draft.images = ($event.target as HTMLTextAreaElement).value.split('\n').map(v => v.trim()).filter(Boolean)"></textarea></label>
        </div>
      </template>
      <template v-else>
        <h2>发布前确认</h2>
        <div class="opportunity__summary">
          <img :src="draft.images[0]" alt="">
          <div><h3>{{ draft.name }}</h3><p>{{ draft.description }}</p><strong>¥ {{ draft.amount }} · 库存 {{ draft.stock }}</strong><small>{{ draft.province }} {{ draft.city }} {{ draft.district }} · {{ draft.deliveryMethod }}</small></div>
        </div>
      </template>
      <div class="workbench__actions opportunity__footer">
        <button class="workbench__btn" :disabled="step <= 1" @click="step--">上一步</button>
        <button v-if="step < 4" class="workbench__btn workbench__btn--primary" @click="next">下一步</button>
        <template v-else>
          <button class="workbench__btn" :disabled="loading" @click="publish(true)">仅校验</button>
          <button class="workbench__btn workbench__btn--primary" :disabled="loading" @click="publish(false)">立即发布</button>
        </template>
      </div>
    </div>
  </section>
</template>

<style scoped>
.opportunity__layout { display: grid; grid-template-columns: minmax(0, 1fr) 300px; gap: 14px; }
.opportunity__account { max-width: 180px; }
.opportunity__result { width: 100%; color: inherit; text-align: left; cursor: pointer; }
.opportunity__result--active { border-color: #84adff; background: #f5f8ff; }
.opportunity__preview { position: sticky; top: 16px; align-self: start; }
.opportunity__preview > img { width: 100%; aspect-ratio: 4 / 3; border-radius: 8px; object-fit: cover; }
.opportunity__preview h2 { font-size: 15px; line-height: 1.5; }
.opportunity__preview > strong { color: #d92d20; font-size: 22px; }
.opportunity__preview > p { color: #667085; font-size: 12px; }
.opportunity__preview > button { width: 100%; }
.opportunity__wizard { max-width: 980px; margin-right: auto; margin-left: auto; }
.opportunity__wizard h2 { margin-top: 0; }
.opportunity__wizard > .workbench__field { margin-bottom: 14px; }
.opportunity__footer { justify-content: flex-end; margin-top: 18px; }
.opportunity__summary { display: grid; grid-template-columns: 180px 1fr; gap: 18px; }
.opportunity__summary img { width: 180px; height: 180px; border-radius: 8px; object-fit: cover; background: #f2f4f7; }
.opportunity__summary p { color: #667085; white-space: pre-wrap; }
.opportunity__summary strong, .opportunity__summary small { display: block; margin-top: 10px; }
@media (max-width: 900px) { .opportunity__layout { grid-template-columns: 1fr; } .opportunity__preview { position: static; } }
@media (max-width: 767px) { .opportunity__summary { grid-template-columns: 1fr; } .opportunity__summary img { width: 100%; height: auto; aspect-ratio: 1; } }
</style>
