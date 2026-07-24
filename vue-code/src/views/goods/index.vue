<script setup lang="ts">
import { onMounted, ref, computed, inject, defineComponent, h } from 'vue'
import { useGoodsManager } from './useGoodsManager'
import './goods.css'
import '@/styles/header-selectors.css'

import IconShoppingBag from '@/components/icons/IconShoppingBag.vue'
import IconRefresh from '@/components/icons/IconRefresh.vue'
import IconFilter from '@/components/icons/IconFilter.vue'
import IconChevronDown from '@/components/icons/IconChevronDown.vue'
import IconChevronLeft from '@/components/icons/IconChevronLeft.vue'
import IconChevronRight from '@/components/icons/IconChevronRight.vue'
import type { GoodsItemWithConfig } from '@/api/goods'
import { parseRatingContents, serializeRatingContents } from '@/utils/rating-content'

import GoodsTable from './components/GoodsTable.vue'
import GoodsDetail from './components/GoodsDetail.vue'
import GoodsEditDialog from './components/GoodsEditDialog.vue'

const {
  loading,
  refreshing,
  syncing,
  syncProgress,
  accounts,
  selectedAccountId,
  statusFilter,
  goodsList,
  currentPage,
  pageSize,
  total,
  totalPages,
  dialogs,
  selectedGoodsId,
  editingGoods,
  editSaving,
  deleteTarget,
  loadAccounts,
  loadGoods,
  handleRefresh,
  handleAccountChange,
  handleStatusFilter,
  handlePageChange,
  viewDetail,
  editGoods,
  saveGoodsInfo,
  openPlatformGoods,
  syncEditingGoods,
  configAutoDelivery,
  toggleAutoDelivery,
  toggleAutoReply,
  toggleAutoRate,
  toggleAutoPolish,
  saveRateSettings,
  confirmDelete,
  executeDelete,
  getGoodsStatusText,
  formatPrice,
  formatTime,
  syncSingleGoods
} = useGoodsManager()

const ratePresets = [
  '交易愉快，感谢支持，期待再次合作。满意的话欢迎点亮小红花。',
  '感谢信任与支持，订单已顺利完成，期待下次继续合作。',
  '很高兴为本次交易提供服务，感谢支持，祝使用愉快。'
]
const rateDialogVisible = ref(false)
const rateTarget = ref<GoodsItemWithConfig | null>(null)
const rateEnabled = ref(false)
const rateContents = ref<string[]>([])
const rateSaving = ref(false)
const rateError = computed(() => {
  const contents = rateContents.value.map(item => item.trim()).filter(Boolean)
  if (!contents.length) return '至少需要一条评价文案'
  if (contents.length > 10) return '最多配置10条评价文案'
  if (contents.some(item => item.length > 500)) return '单条评价文案不能超过500个字符'
  return ''
})

const openRateSettings = (item: GoodsItemWithConfig) => {
  rateTarget.value = item
  rateEnabled.value = item.xianyuAutoRateOn === 1
  rateContents.value = parseRatingContents(item.xianyuAutoRateContent)
  if (!rateContents.value.length) rateContents.value = [ratePresets[0]!]
  rateDialogVisible.value = true
}

const addRateContent = (content = '') => {
  if (rateContents.value.length >= 10) return
  if (content && rateContents.value.includes(content)) return
  rateContents.value.push(content)
}

const removeRateContent = (index: number) => {
  if (rateContents.value.length > 1) rateContents.value.splice(index, 1)
}

const appendRateVariable = (index: number, variable: string) => {
  rateContents.value[index] = (rateContents.value[index] || '') + variable
}

const submitRateSettings = async () => {
  if (!rateTarget.value || rateError.value) return
  rateSaving.value = true
  try {
    if (await saveRateSettings(rateTarget.value, rateEnabled.value, serializeRatingContents(rateContents.value))) {
      rateDialogVisible.value = false
    }
  } finally {
    rateSaving.value = false
  }
}

// 下拉刷新相关状态
const pullRefreshState = ref<'idle' | 'pulling' | 'ready' | 'refreshing'>('idle')
const pullDistance = ref(0)
const tableWrapRef = ref<HTMLElement | null>(null)
const startY = ref(0)
const isMobile = ref(false)

// 检测是否为手机模式
const checkMobile = () => {
  isMobile.value = window.innerWidth <= 768
}

// 计算下拉刷新的显示距离
const pullRefreshDistance = computed(() => {
  const maxDistance = 80
  return Math.min(pullDistance.value, maxDistance)
})

// 处理触摸开始
const handleTouchStart = (e: TouchEvent) => {
  if (!isMobile.value || !tableWrapRef.value) return
  
  // 只在列表顶部时才允许下拉
  if (tableWrapRef.value.scrollTop === 0) {
    startY.value = e.touches[0]?.clientY ?? 0
    pullDistance.value = 0
    pullRefreshState.value = 'idle'
  }
}

// 处理触摸移动
const handleTouchMove = (e: TouchEvent) => {
  if (!isMobile.value || !tableWrapRef.value || startY.value === 0) return
  
  if (tableWrapRef.value.scrollTop === 0) {
    const currentY = e.touches[0]?.clientY ?? 0
    const distance = currentY - startY.value
    
    if (distance > 0) {
      e.preventDefault()
      pullDistance.value = distance
      
      if (distance < 60) {
        pullRefreshState.value = 'pulling'
      } else {
        pullRefreshState.value = 'ready'
      }
    }
  }
}

// 处理触摸结束
const handleTouchEnd = async () => {
  if (!isMobile.value) return
  
  if (pullRefreshState.value === 'ready' && pullDistance.value >= 60) {
    pullRefreshState.value = 'refreshing'
    await handleRefresh()
    // 动画反弹
    pullDistance.value = 0
    pullRefreshState.value = 'idle'
  } else {
    // 回弹动画
    pullDistance.value = 0
    pullRefreshState.value = 'idle'
  }
  
  startY.value = 0
}

// 获取导航栏内容设置函数
const setHeaderContent = inject<(content: any) => void>('setHeaderContent')

// 创建导航栏选择器组件
const HeaderSelectors = defineComponent({
  setup() {
    return () => h('div', { class: 'header-selectors' }, [
      h('div', { class: 'header-select-wrap' }, [
        h('select', {
          class: 'header-select',
          value: selectedAccountId.value,
          onChange: (e: Event) => {
            const target = e.target as HTMLSelectElement
            selectedAccountId.value = target.value ? parseInt(target.value) : null
            handleAccountChange()
          }
        }, [
          h('option', { value: '', disabled: true }, '选择账号'),
          ...accounts.value.map(acc => 
            h('option', { value: acc.id.toString() }, acc.accountNote || acc.unb)
          )
        ]),
        h(IconChevronDown, { class: 'header-select-icon' })
      ]),
      h('div', { class: 'header-select-wrap' }, [
        h('select', {
          class: 'header-select',
          value: statusFilter.value,
          onChange: (e: Event) => {
            const target = e.target as HTMLSelectElement
            statusFilter.value = target.value
            handleStatusFilter()
          }
        }, [
          h('option', { value: '' }, '全部状态'),
          h('option', { value: '0' }, '在售'),
          h('option', { value: '1' }, '已下架'),
          h('option', { value: '2' }, '已售出')
        ]),
        h(IconChevronDown, { class: 'header-select-icon' })
      ]),
      h('button', {
        class: ['header-refresh-btn', { 'header-refresh-btn--loading': refreshing.value || syncing.value }],
        disabled: refreshing.value || syncing.value || !selectedAccountId.value,
        onClick: handleRefresh
      }, [
        h(IconRefresh, { class: 'header-refresh-icon' })
      ])
    ])
  }
})

onMounted(() => {
  loadAccounts()
  checkMobile()
  window.addEventListener('resize', checkMobile)
  
  // 只在手机模式下设置导航栏内容
  if (setHeaderContent) {
    setHeaderContent(HeaderSelectors)
  }
})

// 分页按钮列表
const getPageButtons = () => {
  const buttons: number[] = []
  const maxVisible = 5
  let start = Math.max(1, currentPage.value - Math.floor(maxVisible / 2))
  const end = Math.min(totalPages.value, start + maxVisible - 1)
  start = Math.max(1, end - maxVisible + 1)
  for (let i = start; i <= end; i++) {
    buttons.push(i)
  }
  return buttons
}
</script>

<template>
  <div class="goods">
    <!-- Header -->
    <div class="goods__header">
      <div class="goods__title-row desktop-only">
        <div class="goods__title-icon">
          <IconShoppingBag />
        </div>
        <h1 class="goods__title">商品管理</h1>
      </div>

      <div class="goods__actions">
        <template v-if="!isMobile">
          <div class="goods__select-wrap">
            <select
              v-model="selectedAccountId"
              class="goods__select"
              @change="handleAccountChange"
            >
              <option :value="null" disabled>选择账号</option>
              <option v-for="acc in accounts" :key="acc.id" :value="acc.id">
                {{ acc.accountNote || acc.unb }}
              </option>
            </select>
            <span class="goods__select-icon">
              <IconChevronDown />
            </span>
          </div>

          <div class="goods__select-wrap">
            <select
              v-model="statusFilter"
              class="goods__select"
              @change="handleStatusFilter"
            >
              <option value="">全部状态</option>
              <option value="0">在售</option>
              <option value="1">已下架</option>
              <option value="2">已售出</option>
            </select>
            <span class="goods__select-icon">
              <IconChevronDown />
            </span>
          </div>
        </template>

        <button
          class="btn btn--primary desktop-only"
          :class="{ 'btn--loading': refreshing || syncing }"
          :disabled="refreshing || syncing || !selectedAccountId"
          @click="handleRefresh"
        >
          <IconRefresh />
          <span class="mobile-hidden">同步闲鱼商品</span>
        </button>

        <span v-if="total > 0 && !isMobile" class="goods__count">
          共 {{ total }} 件
        </span>

        <div v-if="syncing && syncProgress" class="goods__sync-progress">
          <span class="goods__sync-text">
            详情同步: {{ syncProgress.completedCount }}/{{ syncProgress.totalCount }}
          </span>
          <div class="goods__sync-bar">
            <div 
              class="goods__sync-bar-fill" 
              :style="{ width: `${(syncProgress.completedCount / syncProgress.totalCount) * 100}%` }"
            ></div>
          </div>
        </div>
      </div>
    </div>

    <!-- Content Card -->
    <div class="goods__content">
      <!-- Pull Refresh Indicator (Mobile Only) -->
      <div 
        v-if="isMobile && pullDistance > 0"
        class="goods__pull-refresh"
        :style="{ height: `${pullRefreshDistance}px` }"
        :class="{
          'goods__pull-refresh--pulling': pullRefreshState === 'pulling',
          'goods__pull-refresh--ready': pullRefreshState === 'ready',
          'goods__pull-refresh--refreshing': pullRefreshState === 'refreshing'
        }"
      >
        <div class="goods__pull-refresh-content">
          <div class="goods__pull-refresh-icon">
            <IconRefresh />
          </div>
          <div class="goods__pull-refresh-text">
            {{ pullRefreshState === 'pulling' ? '下拉刷新' : pullRefreshState === 'ready' ? '释放刷新' : '刷新中...' }}
          </div>
        </div>
      </div>

      <!-- Table/Cards -->
      <div 
        ref="tableWrapRef"
        class="goods__table-wrap"
        @touchstart="handleTouchStart"
        @touchmove="handleTouchMove"
        @touchend="handleTouchEnd"
      >
        <GoodsTable
          :goods-list="goodsList"
          :loading="loading"
          @view="viewDetail"
          @edit="editGoods"
          @sync="syncSingleGoods"
          @toggle-auto-delivery="toggleAutoDelivery"
          @toggle-auto-reply="toggleAutoReply"
          @toggle-auto-rate="toggleAutoRate"
          @toggle-auto-polish="toggleAutoPolish"
          @config-auto-rate="openRateSettings"
          @config-auto-delivery="configAutoDelivery"
          @delete="confirmDelete"
        />
      </div>

      <!-- Pagination -->
      <div v-if="totalPages > 1" class="goods__pagination">
        <button
          class="goods__page-btn"
          :class="{ 'goods__page-btn--disabled': currentPage <= 1 }"
          @click="handlePageChange(currentPage - 1)"
        >
          <IconChevronLeft />
        </button>

        <template v-for="page in getPageButtons()" :key="page">
          <button
            class="goods__page-btn"
            :class="{ 'goods__page-btn--active': page === currentPage }"
            @click="handlePageChange(page)"
          >
            {{ page }}
          </button>
        </template>

        <button
          class="goods__page-btn"
          :class="{ 'goods__page-btn--disabled': currentPage >= totalPages }"
          @click="handlePageChange(currentPage + 1)"
        >
          <IconChevronRight />
        </button>

        <span class="goods__page-info">{{ currentPage }} / {{ totalPages }}</span>
      </div>
    </div>

    <!-- Edit Dialog -->
    <GoodsEditDialog
      v-model="dialogs.edit"
      :goods="editingGoods"
      :saving="editSaving"
      @save="saveGoodsInfo"
      @open-platform="openPlatformGoods"
      @sync-platform="syncEditingGoods"
    />

    <!-- Detail Dialog -->
    <GoodsDetail
      v-model="dialogs.detail"
      :goods-id="selectedGoodsId"
      :account-id="selectedAccountId"
      @refresh="loadGoods"
    />

    <Transition name="overlay-fade">
      <div v-if="rateDialogVisible" class="goods__dialog-overlay" @click.self="rateDialogVisible = false">
        <form class="goods__dialog rate-dialog" @submit.prevent="submitRateSettings">
          <div class="goods__dialog-header">
            <div>
              <h3 class="goods__dialog-title">自动评价设置</h3>
              <p class="rate-dialog__subtitle">{{ rateTarget?.item.title }}</p>
            </div>
          </div>
          <div class="goods__dialog-body rate-dialog__body">
            <label class="rate-dialog__switch-row">
              <span><strong>自动评价</strong><small>仅在买家完成评价后回评，不会提前评价</small></span>
              <input v-model="rateEnabled" type="checkbox">
            </label>
            <div class="rate-dialog__pool-heading"><span><strong>评价文案池</strong><small>按订单稳定轮换，重试不会更换文案</small></span><button type="button" @click="addRateContent()">添加文案</button></div>
            <label v-for="(content, index) in rateContents" :key="index" class="rate-dialog__field">
              <span class="rate-dialog__field-title">文案 {{ index + 1 }}<span><button type="button" @click="appendRateVariable(index, '{buyerName}')">买家</button><button type="button" @click="appendRateVariable(index, '{goodsName}')">商品</button><button type="button" @click="appendRateVariable(index, '{orderId}')">订单号</button><button type="button" :disabled="rateContents.length === 1" @click="removeRateContent(index)">删除</button></span></span>
              <textarea v-model="rateContents[index]" maxlength="500" rows="3" placeholder="输入买家评价后自动发送的评价内容"></textarea>
              <small>{{ content.trim().length }}/500</small>
            </label>
            <div class="rate-dialog__presets">
              <span>添加快捷文案</span>
              <button v-for="preset in ratePresets" :key="preset" type="button" @click="addRateContent(preset)">{{ preset }}</button>
            </div>
            <small v-if="rateError" class="rate-dialog__error">{{ rateError }}</small>
          </div>
          <div class="goods__dialog-footer">
            <button type="button" class="goods__dialog-btn goods__dialog-btn--cancel" @click="rateDialogVisible = false">取消</button>
            <button type="submit" class="goods__dialog-btn goods__dialog-btn--confirm" :disabled="Boolean(rateError) || rateSaving">{{ rateSaving ? '保存中' : '保存设置' }}</button>
          </div>
        </form>
      </div>
    </Transition>

    <!-- Delete Confirm -->
    <Transition name="overlay-fade">
      <div v-if="dialogs.deleteConfirm" class="goods__dialog-overlay" @click.self="dialogs.deleteConfirm = false">
        <div class="goods__dialog">
          <div class="goods__dialog-header">
            <h3 class="goods__dialog-title">删除商品</h3>
          </div>
          <div class="goods__dialog-body">
            <p class="goods__dialog-text">
              确定要删除「{{ deleteTarget?.title }}」吗？此操作不可恢复。
            </p>
          </div>
          <div class="goods__dialog-footer">
            <button
              class="goods__dialog-btn goods__dialog-btn--cancel"
              @click="dialogs.deleteConfirm = false"
            >
              取消
            </button>
            <button
              class="goods__dialog-btn goods__dialog-btn--danger"
              @click="executeDelete"
            >
              删除
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

.rate-dialog{max-width:680px}.rate-dialog__subtitle{margin:5px 0 0;color:#86868b;font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.rate-dialog__body{display:flex;flex-direction:column;gap:14px;max-height:68vh;overflow:auto}.rate-dialog__switch-row{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:14px;border:1px solid rgba(60,60,67,.12);border-radius:10px}.rate-dialog__switch-row span{display:flex;flex-direction:column;gap:4px}.rate-dialog__switch-row small,.rate-dialog__field small,.rate-dialog__pool-heading small{color:#86868b;font-size:12px}.rate-dialog__switch-row input{width:18px;height:18px}.rate-dialog__pool-heading,.rate-dialog__field-title{display:flex;align-items:center;justify-content:space-between;gap:10px}.rate-dialog__pool-heading>span{display:flex;flex-direction:column;gap:3px}.rate-dialog__pool-heading button,.rate-dialog__field-title button{border:0;border-radius:6px;padding:5px 8px;background:rgba(0,122,255,.08);color:#007aff;cursor:pointer}.rate-dialog__field-title>span{display:flex;gap:5px;flex-wrap:wrap}.rate-dialog__field-title button:last-child{color:#ff3b30;background:rgba(255,59,48,.07)}.rate-dialog__field-title button:disabled{opacity:.35;cursor:not-allowed}.rate-dialog__field{display:flex;flex-direction:column;gap:7px;font-size:13px;font-weight:600}.rate-dialog__field textarea{width:100%;box-sizing:border-box;border:1px solid rgba(60,60,67,.2);border-radius:8px;padding:10px;font:inherit;resize:vertical}.rate-dialog__presets{display:flex;flex-direction:column;gap:7px}.rate-dialog__presets>span{font-size:13px;font-weight:600}.rate-dialog__presets button{text-align:left;border:1px solid rgba(0,122,255,.15);background:rgba(0,122,255,.04);color:#1d1d1f;border-radius:8px;padding:9px 10px;cursor:pointer}.rate-dialog__error{color:#ff3b30}.goods__dialog-btn--confirm{color:#fff;background:#007aff;border-color:#007aff}.goods__dialog-btn:disabled{opacity:.5;cursor:not-allowed}
</style>
