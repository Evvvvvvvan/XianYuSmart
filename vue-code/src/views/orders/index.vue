<script setup lang="ts">
import { onMounted, ref, computed, inject, defineComponent, h } from 'vue'
import { useOrderManager } from './useOrderManager'
import './orders.css'
import '@/styles/header-selectors.css'

import IconClipboard from '@/components/icons/IconClipboard.vue'
import IconSearch from '@/components/icons/IconSearch.vue'
import IconRefresh from '@/components/icons/IconRefresh.vue'
import IconFilter from '@/components/icons/IconFilter.vue'
import IconChevronDown from '@/components/icons/IconChevronDown.vue'
import IconChevronLeft from '@/components/icons/IconChevronLeft.vue'
import IconChevronRight from '@/components/icons/IconChevronRight.vue'
import IconPackage from '@/components/icons/IconPackage.vue'

import OrderTable from './components/OrderTable.vue'
import { rateOrder, type OrderRateItem } from '@/api/order'
import { showError, showSuccess } from '@/utils'
import { parseRatingContents } from '@/utils/rating-content'

const goodsPanelCollapsed = ref(true)
const isDesktopCollapsed = computed(() => !isMobile.value && goodsPanelCollapsed.value)

const {
  loading,
  orderList,
  total,
  accounts,
  goodsList,
  goodsTotal,
  goodsLoading,
  goodsListRef,
  onlyOnSale,
  selectedGoodsId,
  selectedDeliveryStatus,
  deliveryStatusOptions,
  queryParams,
  totalPages,
  loadAccounts,
  loadOrders,
  loadRateDetails,
  loadGoods,
  handleAccountChange,
  handleReset,
  handleDeliveryStatusChange,
  handlePageChange,
  copySId,
  handleConfirmShipment,
  handleRetryDelivery,
  handleGoodsScroll,
  selectGoods,
  clearGoodsFilter,
  toggleOnlyOnSale
} = useOrderManager()

const ratePresets = [
  '交易愉快，感谢支持，期待再次合作。满意的话欢迎点亮小红花。',
  '感谢信任与支持，订单已顺利完成，期待下次继续合作。',
  '很高兴为本次交易提供服务，感谢支持，祝使用愉快。'
]
const showManualRateDialog = ref(false)
const manualRateTarget = ref<any>(null)
const manualRateContent = ref(ratePresets[0]!)
const manualRateSaving = ref(false)
const showRateDetailDialog = ref(false)
const rateDetailTarget = ref<any>(null)
const rateDetailRefreshing = ref(false)
const pendingRateCount = computed(() => orderList.value.filter(order => order.rateDetail?.canRate).length)

const openManualRate = (order: any) => {
  manualRateTarget.value = order
  const goods = goodsList.value.find(item => item.item.xyGoodId === order.xyGoodsId)
  manualRateContent.value = parseRatingContents(goods?.xianyuAutoRateContent)[0] || ratePresets[0]!
  showManualRateDialog.value = true
}

const openRateDetail = (order: any) => {
  rateDetailTarget.value = order
  showRateDetailDialog.value = true
  void refreshRateDetail()
}

const refreshRateDetail = async () => {
  if (!rateDetailTarget.value) return
  rateDetailRefreshing.value = true
  try {
    await loadRateDetails([rateDetailTarget.value], true)
  } finally {
    rateDetailRefreshing.value = false
  }
}

const openManualRateFromDetail = () => {
  if (!rateDetailTarget.value?.rateDetail?.canRate) return showError(rateDetailTarget.value?.rateDetail?.statusText || '当前订单暂不可评价')
  showRateDetailDialog.value = false
  openManualRate(rateDetailTarget.value)
}

const getRateLevelText = (rate: OrderRateItem) => {
  if (!rate.main) return '追评'
  if (rate.level === 1) return '好评'
  if (rate.level === -1) return '中评'
  if (rate.level === 0) return '差评'
  return '主评'
}

const submitManualRate = async () => {
  const order = manualRateTarget.value
  const content = manualRateContent.value.trim()
  if (!order?.xianyuAccountId || !order.orderId) return showError('订单信息不完整')
  if (!content || content.length > 500) return showError('评价内容长度应为1至500个字符')
  manualRateSaving.value = true
  try {
    await rateOrder({ xianyuAccountId: order.xianyuAccountId, orderId: order.orderId, content })
    showManualRateDialog.value = false
    showSuccess('评价成功')
    await loadOrders()
  } finally {
    manualRateSaving.value = false
  }
}

const showFilterSheet = ref(false)
const isMobile = ref(false)

const checkScreenSize = () => {
  isMobile.value = window.innerWidth < 768
}

// 导航栏注入
const setHeaderContent = inject<(content: any) => void>('setHeaderContent')

const HeaderSelectors = defineComponent({
  setup() {
    return () => h('div', { class: 'header-selectors' }, [
      h('div', { class: 'header-select-wrap' }, [
        h('select', {
          class: 'header-select',
          onChange: (e: Event) => {
            const val = (e.target as HTMLSelectElement).value
            queryParams.xianyuAccountId = val ? parseInt(val) : undefined
            handleAccountChange()
          }
        }, [
          h('option', { value: '', disabled: true, selected: !queryParams.xianyuAccountId }, '账号'),
          ...accounts.value.map(acc =>
            h('option', {
              value: acc.id.toString(),
              selected: queryParams.xianyuAccountId === acc.id
            }, acc.accountNote || acc.unb)
          )
        ]),
        h(IconChevronDown, { class: 'header-select-icon' })
      ]),
      h('button', {
        class: ['header-refresh-btn', { 'header-refresh-btn--loading': loading.value }],
        disabled: loading.value,
        onClick: loadOrders
      }, [
        h(IconRefresh, { class: 'header-refresh-icon' })
      ]),
      h('button', {
        class: 'header-filter-btn',
        onClick: openFilterSheet
      }, [
        h(IconFilter, { class: 'header-filter-icon' })
      ])
    ])
  }
})

onMounted(async () => {
  checkScreenSize()
  window.addEventListener('resize', checkScreenSize)
  if (setHeaderContent) setHeaderContent(HeaderSelectors)
  await loadAccounts()
  if (setHeaderContent) setHeaderContent(HeaderSelectors)
  loadGoods()
  loadOrders()
})

const filterKeyword = ref('')
const filterDeliveryStatus = ref('')

const openFilterSheet = () => {
  filterKeyword.value = queryParams.keyword || ''
  filterDeliveryStatus.value = selectedDeliveryStatus.value
  showFilterSheet.value = true
}

const applyFilter = () => {
  queryParams.keyword = filterKeyword.value || undefined
  selectedDeliveryStatus.value = filterDeliveryStatus.value
  showFilterSheet.value = false
  handleDeliveryStatusChange()
}

const resetFilter = () => {
  filterKeyword.value = ''
  filterDeliveryStatus.value = ''
  handleReset()
  showFilterSheet.value = false
}

const getPageButtons = () => {
  const buttons: number[] = []
  const maxVisible = 5
  let start = Math.max(1, queryParams.pageNum! - Math.floor(maxVisible / 2))
  const end = Math.min(totalPages.value, start + maxVisible - 1)
  start = Math.max(1, end - maxVisible + 1)
  for (let i = start; i <= end; i++) {
    buttons.push(i)
  }
  return buttons
}

const showConfirmDialog = ref(false)
const confirmTargetOrder = ref<any>(null)

const openConfirmDialog = (order: any) => {
  confirmTargetOrder.value = order
  showConfirmDialog.value = true
}

const executeConfirmShipment = async () => {
  if (confirmTargetOrder.value) {
    await handleConfirmShipment(confirmTargetOrder.value)
  }
  showConfirmDialog.value = false
  confirmTargetOrder.value = null
}
</script>

<template>
  <div class="orders">
    <div class="orders__header">
      <div class="orders__title-row">
        <div class="orders__title-icon">
          <IconClipboard />
        </div>
        <h1 class="orders__title">订单与评价</h1>
      </div>
      <div class="orders__actions">
        <div class="orders__select-wrap">
          <select
            v-model="queryParams.xianyuAccountId"
            class="orders__select"
            @change="handleAccountChange"
          >
            <option :value="undefined" disabled>选择账号</option>
            <option v-for="acc in accounts" :key="acc.id" :value="acc.id">
              {{ acc.accountNote || acc.unb || `账号${acc.id}` }}
            </option>
          </select>
          <span class="orders__select-icon">
            <IconChevronDown />
          </span>
        </div>
        <template v-if="!isMobile">
          <div class="orders__select-wrap orders__select-wrap--status">
            <select
              v-model="selectedDeliveryStatus"
              class="orders__select"
              @change="handleDeliveryStatusChange"
            >
              <option v-for="option in deliveryStatusOptions" :key="option.value" :value="option.value">
                {{ option.label }}
              </option>
            </select>
            <span class="orders__select-icon">
              <IconChevronDown />
            </span>
          </div>
          <div class="orders__input-wrap">
            <input
              v-model="queryParams.keyword"
              class="orders__input"
              placeholder="商品名称/规格/买家/发货内容"
              @keyup.enter="loadOrders"
            />
          </div>
          <button class="btn btn--primary" @click="loadOrders">
            <IconSearch />
            <span>查询</span>
          </button>
          <button class="btn btn--ghost" @click="handleReset">
            重置
          </button>
          <span v-if="total > 0" class="orders__count">
            共 {{ total }} 条
          </span>
        </template>
        <button
          class="btn btn--secondary"
          :class="{ 'btn--loading': loading }"
          :disabled="loading"
          @click="loadOrders"
        >
          <IconRefresh />
          <span class="mobile-hidden">刷新</span>
        </button>
        <button v-if="isMobile" class="btn btn--secondary" @click="openFilterSheet">
          <IconFilter />
          <span>筛选</span>
        </button>
      </div>
    </div>

    <div class="evaluation-guide">
      <div><strong>评价处理</strong><span>状态与双方评价内容均从闲鱼平台同步；自动评价规则统一在商品管理配置，本页只处理订单评价与查看结果。</span></div>
      <span class="evaluation-guide__count">当前列表可评价 {{ pendingRateCount }} 条</span>
    </div>

    <div class="orders__body" :class="{ 'orders__body--no-goods': isMobile }">
      <div
        v-if="!isMobile"
        class="orders__goods-panel"
        :class="{ 'orders__goods-panel--collapsed': isDesktopCollapsed }"
      >
        <template v-if="!isDesktopCollapsed">
          <div class="orders__goods-toolbar">
            <span class="orders__goods-toolbar-title">商品列表</span>
            <span v-if="goodsTotal > 0" class="orders__goods-toolbar-count">共 {{ goodsTotal }} 件</span>
            <button class="orders__only-on-sale-btn" :class="{ 'orders__only-on-sale-btn--active': onlyOnSale }" @click="toggleOnlyOnSale">
              {{ onlyOnSale ? '在售' : '全部' }}
            </button>
          </div>

          <div
            class="orders__goods-list"
            ref="goodsListRef"
            @scroll="handleGoodsScroll"
          >
            <div v-if="goodsLoading && goodsList.length === 0" class="orders__loading">
              <div class="orders__spinner"></div>
              <span>加载中...</span>
            </div>

            <div
              v-for="goods in goodsList"
              :key="goods.item.xyGoodId"
              class="orders__goods-item"
              :class="{ 'orders__goods-item--active': selectedGoodsId === goods.item.xyGoodId, 'orders__goods-item--offline': goods.item.status !== 0 }"
              @click="selectGoods(goods)"
            >
              <img
                :src="goods.item.coverPic"
                :alt="goods.item.title"
                class="orders__goods-cover"
              />
              <div class="orders__goods-info">
                <div class="orders__goods-title">{{ goods.item.title }}</div>
                <div class="orders__goods-meta">
                  <span class="orders__goods-price">¥{{ goods.item.soldPrice }}</span>
                  <span
                    class="orders__goods-status"
                    :class="`orders__goods-status--${goods.item.status === 0 ? 'on-sale' : goods.item.status === 1 ? 'off-shelf' : 'sold'}`"
                  >
                    {{ goods.item.status === 0 ? '在售' : goods.item.status === 1 ? '已下架' : goods.item.status === -1 ? '已删除' : '已售出' }}
                  </span>
                </div>
              </div>
            </div>

            <div v-if="goodsLoading && goodsList.length > 0" class="orders__loading">
              <div class="orders__spinner"></div>
              <span>加载中...</span>
            </div>

            <div
              v-if="!goodsLoading && goodsList.length > 0 && goodsList.length >= goodsTotal"
              class="orders__no-more"
            >
              已加载全部
            </div>

            <div v-if="!goodsLoading && goodsList.length === 0" class="orders__goods-empty">
              <IconPackage />
              <span class="orders__goods-empty-text">暂无商品</span>
            </div>
          </div>
        </template>
        <template v-else>
          <div class="orders__goods-icons">
            <div
              v-for="goods in goodsList"
              :key="goods.item.xyGoodId"
              class="orders__goods-icon-item"
              :class="{ 'orders__goods-icon-item--active': selectedGoodsId === goods.item.xyGoodId }"
              :title="goods.item.title"
              @click="selectGoods(goods)"
            >
              <img :src="goods.item.coverPic" class="orders__goods-icon-img" />
            </div>
          </div>
        </template>
        <button
          class="orders__goods-toggle"
          :title="goodsPanelCollapsed ? '展开商品列表' : '折叠商品列表'"
          @click="goodsPanelCollapsed = !goodsPanelCollapsed"
        >
          <svg v-if="goodsPanelCollapsed" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18l6-6-6-6"/></svg>
          <svg v-else xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M15 18l-6-6 6-6"/></svg>
        </button>
      </div>

      <div class="orders__content">
      <div class="orders__table-wrap">
        <OrderTable
          :order-list="orderList"
          :loading="loading"
          @copy-sid="copySId"
          @confirm-shipment="openConfirmDialog"
          @retry-delivery="handleRetryDelivery"
          @rate="openRateDetail"
        />
      </div>

      <div v-if="totalPages > 1" class="orders__pagination">
        <button
          class="orders__page-btn"
          :class="{ 'orders__page-btn--disabled': queryParams.pageNum! <= 1 }"
          @click="handlePageChange(queryParams.pageNum! - 1)"
        >
          <IconChevronLeft />
        </button>

        <template v-for="page in getPageButtons()" :key="page">
          <button
            class="orders__page-btn"
            :class="{ 'orders__page-btn--active': page === queryParams.pageNum }"
            @click="handlePageChange(page)"
          >
            {{ page }}
          </button>
        </template>

        <button
          class="orders__page-btn"
          :class="{ 'orders__page-btn--disabled': queryParams.pageNum! >= totalPages }"
          @click="handlePageChange(queryParams.pageNum! + 1)"
        >
          <IconChevronRight />
        </button>

        <span class="orders__page-info">{{ queryParams.pageNum }} / {{ totalPages }}</span>
      </div>
      </div>
    </div>

    <Transition name="overlay-fade">
      <div v-if="showFilterSheet" class="orders__filter-overlay" @click="showFilterSheet = false">
        <div
          class="orders__filter-sheet"
          :class="{ 'orders__filter-sheet--open': showFilterSheet }"
          @click.stop
        >
          <div class="orders__filter-sheet-handle"></div>
          <h3 class="orders__filter-sheet-title">筛选条件</h3>

          <div class="orders__filter-group">
            <label class="orders__filter-label">关键词</label>
            <input
              v-model="filterKeyword"
              class="orders__filter-input"
              placeholder="商品名称/规格/买家/发货内容"
            />
          </div>

          <div class="orders__filter-group">
            <label class="orders__filter-label">履约状态</label>
            <select v-model="filterDeliveryStatus" class="orders__filter-input">
              <option v-for="option in deliveryStatusOptions" :key="option.value" :value="option.value">
                {{ option.label }}
              </option>
            </select>
          </div>

          <div class="orders__filter-actions">
            <button class="btn btn--secondary" @click="resetFilter">重置</button>
            <button class="btn btn--primary" @click="applyFilter">查询</button>
          </div>
        </div>
      </div>
    </Transition>

    <Transition name="overlay-fade">
      <div v-if="showRateDetailDialog" class="orders__dialog-overlay" @click.self="showRateDetailDialog = false">
        <div class="orders__dialog rate-detail-dialog">
          <div class="orders__dialog-header"><div><h3 class="orders__dialog-title">双方评价</h3><p>订单 {{ rateDetailTarget?.orderId }}</p></div></div>
          <div class="orders__dialog-body rate-detail-dialog__body">
            <div class="rate-detail-summary">
              <strong>{{ rateDetailTarget?.rateDetail?.statusText || '评价状态未同步' }}</strong>
              <span v-if="rateDetailTarget?.rateDetail?.tradeStatus">闲鱼交易状态：{{ rateDetailTarget.rateDetail.tradeStatus }}</span>
            </div>
            <div class="rate-detail-columns">
              <section class="rate-detail-section">
                <h4>买家评价 <span>{{ rateDetailTarget?.rateDetail?.buyerRates?.length || 0 }}</span></h4>
                <article v-for="(rate, index) in rateDetailTarget?.rateDetail?.buyerRates || []" :key="`buyer-${index}`" class="rate-detail-item">
                  <div><strong>{{ getRateLevelText(rate) }}</strong><time>{{ rate.createdTime || '-' }}</time></div>
                  <p>{{ rate.content || '买家未填写文字评价' }}</p>
                  <small v-if="rate.illegal">该内容已被平台处理</small>
                </article>
                <div v-if="!rateDetailTarget?.rateDetail?.buyerRates?.length" class="rate-detail-empty">暂无买家评价</div>
              </section>
              <section class="rate-detail-section">
                <h4>商家评价 <span>{{ rateDetailTarget?.rateDetail?.sellerRates?.length || 0 }}</span></h4>
                <article v-for="(rate, index) in rateDetailTarget?.rateDetail?.sellerRates || []" :key="`seller-${index}`" class="rate-detail-item">
                  <div><strong>{{ getRateLevelText(rate) }}</strong><time>{{ rate.createdTime || '-' }}</time></div>
                  <p>{{ rate.content || '商家未填写文字评价' }}</p>
                  <small v-if="rate.illegal">该内容已被平台处理</small>
                </article>
                <div v-if="!rateDetailTarget?.rateDetail?.sellerRates?.length" class="rate-detail-empty">暂无商家评价</div>
              </section>
            </div>
          </div>
          <div class="orders__dialog-footer">
            <button type="button" class="orders__dialog-btn orders__dialog-btn--cancel" @click="showRateDetailDialog = false">关闭</button>
            <button type="button" class="orders__dialog-btn orders__dialog-btn--cancel" :disabled="rateDetailRefreshing" @click="refreshRateDetail">{{ rateDetailRefreshing ? '同步中' : '刷新状态' }}</button>
            <button v-if="rateDetailTarget?.rateDetail?.canRate" type="button" class="orders__dialog-btn orders__dialog-btn--confirm" @click="openManualRateFromDetail">评价订单</button>
          </div>
        </div>
      </div>
    </Transition>

    <Transition name="overlay-fade">
      <div v-if="showManualRateDialog" class="orders__dialog-overlay" @click.self="showManualRateDialog = false">
        <form class="orders__dialog rate-dialog" @submit.prevent="submitManualRate">
          <div class="orders__dialog-header"><div><h3 class="orders__dialog-title">手动评价</h3><p>订单 {{ manualRateTarget?.orderId }}</p></div></div>
          <div class="orders__dialog-body rate-dialog__body">
            <label class="rate-dialog__field"><span>评价内容</span><textarea v-model="manualRateContent" maxlength="500" rows="5" autofocus></textarea><small>{{ manualRateContent.trim().length }}/500，提交后不可在本系统撤回</small></label>
            <div class="rate-dialog__presets"><span>快捷文案</span><button v-for="preset in ratePresets" :key="preset" type="button" @click="manualRateContent = preset">{{ preset }}</button></div>
          </div>
          <div class="orders__dialog-footer"><button type="button" class="orders__dialog-btn orders__dialog-btn--cancel" @click="showManualRateDialog = false">取消</button><button type="submit" class="orders__dialog-btn orders__dialog-btn--confirm" :disabled="manualRateSaving">{{ manualRateSaving ? '提交中' : '确认评价' }}</button></div>
        </form>
      </div>
    </Transition>

    <Transition name="overlay-fade">
      <div v-if="showConfirmDialog" class="orders__dialog-overlay" @click.self="showConfirmDialog = false">
        <div class="orders__dialog">
          <div class="orders__dialog-header">
            <h3 class="orders__dialog-title">确认发货</h3>
          </div>
          <div class="orders__dialog-body">
            <p class="orders__dialog-text">
              确认订单「{{ confirmTargetOrder?.orderId }}」已发货吗？
            </p>
          </div>
          <div class="orders__dialog-footer">
            <button
              class="orders__dialog-btn orders__dialog-btn--cancel"
              @click="showConfirmDialog = false"
            >
              取消
            </button>
            <button
              class="orders__dialog-btn orders__dialog-btn--confirm"
              @click="executeConfirmShipment"
            >
              确认
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.overlay-fade-enter-active,
.overlay-fade-leave-active {
  transition: opacity 0.2s ease;
}

.overlay-fade-enter-from,
.overlay-fade-leave-to {
  opacity: 0;
}

.evaluation-guide{display:flex;align-items:center;justify-content:space-between;gap:16px;margin:0 16px 12px;padding:12px 14px;border:1px solid rgba(0,122,255,.14);border-radius:10px;background:rgba(0,122,255,.04)}.evaluation-guide>div{display:flex;flex-direction:column;gap:3px}.evaluation-guide strong{font-size:13px}.evaluation-guide span{color:#6e6e73;font-size:12px}.evaluation-guide__count{white-space:nowrap}.rate-dialog{max-width:580px}.rate-dialog .orders__dialog-header p{margin:4px 0 0;color:#86868b;font-size:12px}.rate-dialog__body{display:flex;flex-direction:column;gap:16px}.rate-dialog__field{display:flex;flex-direction:column;gap:7px;font-size:13px;font-weight:600}.rate-dialog__field select,.rate-dialog__field textarea{box-sizing:border-box;width:100%;border:1px solid rgba(60,60,67,.2);border-radius:8px;padding:10px;background:#fff;font:inherit}.rate-dialog__field textarea{resize:vertical}.rate-dialog__field small,.rate-dialog__switch small{color:#86868b;font-size:12px;font-weight:400}.rate-dialog__switch{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:13px;border:1px solid rgba(60,60,67,.12);border-radius:9px}.rate-dialog__switch span{display:flex;flex-direction:column;gap:4px}.rate-dialog__switch input{width:18px;height:18px}.rate-dialog__presets{display:flex;flex-direction:column;gap:7px}.rate-dialog__presets>span{font-size:13px;font-weight:600}.rate-dialog__presets button{text-align:left;border:1px solid rgba(0,122,255,.15);background:rgba(0,122,255,.04);border-radius:8px;padding:9px 10px;cursor:pointer}.orders__dialog-btn:disabled{opacity:.5;cursor:not-allowed}@media(max-width:767px){.evaluation-guide{margin:0 10px 10px;align-items:flex-start;flex-direction:column}.evaluation-guide__count{white-space:normal}}
.rate-detail-dialog{max-width:760px}.rate-detail-dialog .orders__dialog-header p{margin:4px 0 0;color:#86868b;font-size:12px}.rate-detail-dialog__body{padding-top:8px;text-align:left;max-height:min(68vh,620px);overflow:auto}.rate-detail-summary{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:11px 12px;border:1px solid rgba(60,60,67,.12);border-radius:9px;background:rgba(118,118,128,.06)}.rate-detail-summary strong{font-size:13px}.rate-detail-summary span{font-size:12px;color:#6e6e73}.rate-detail-columns{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:12px}.rate-detail-section{min-width:0}.rate-detail-section h4{display:flex;align-items:center;gap:6px;margin:0 0 8px;font-size:13px}.rate-detail-section h4 span{color:#86868b;font-weight:400}.rate-detail-item{padding:10px 11px;border:1px solid rgba(60,60,67,.12);border-radius:9px;background:#fff}.rate-detail-item+.rate-detail-item{margin-top:8px}.rate-detail-item>div{display:flex;justify-content:space-between;gap:10px}.rate-detail-item strong{font-size:12px;color:#007aff}.rate-detail-item time,.rate-detail-item small{font-size:11px;color:#86868b}.rate-detail-item p{margin:7px 0 0;font-size:13px;line-height:1.55;white-space:pre-wrap;word-break:break-word}.rate-detail-item small{display:block;margin-top:6px;color:#ff3b30}.rate-detail-empty{padding:24px 12px;border:1px dashed rgba(60,60,67,.16);border-radius:9px;text-align:center;color:#86868b;font-size:12px}@media(max-width:767px){.rate-detail-dialog{max-height:88vh}.rate-detail-columns{grid-template-columns:1fr}.rate-detail-summary{align-items:flex-start;flex-direction:column}}
</style>
