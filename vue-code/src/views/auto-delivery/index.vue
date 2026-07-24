<script setup lang="ts">
import { inject, defineComponent, h, onMounted, ref, computed } from 'vue'
import { useAutoDelivery } from './useAutoDelivery'
import './auto-delivery.css'
import '@/styles/header-selectors.css'

import IconTruck from '@/components/icons/IconTruck.vue'
import IconChevronDown from '@/components/icons/IconChevronDown.vue'
import IconChevronLeft from '@/components/icons/IconChevronLeft.vue'
import IconChevronRight from '@/components/icons/IconChevronRight.vue'
import IconText from '@/components/icons/IconText.vue'
import IconRobot from '@/components/icons/IconRobot.vue'
import IconSend from '@/components/icons/IconSend.vue'
import IconImage from '@/components/icons/IconImage.vue'
import IconSparkle from '@/components/icons/IconSparkle.vue'
import IconCheck from '@/components/icons/IconCheck.vue'
import IconClock from '@/components/icons/IconClock.vue'
import IconPackage from '@/components/icons/IconPackage.vue'
import IconCopy from '@/components/icons/IconCopy.vue'
import IconChat from '@/components/icons/IconChat.vue'

import GoodsDetail from '../goods/components/GoodsDetail.vue'
import MultiImageUploader from '@/components/MultiImageUploader.vue'

const goodsPanelCollapsed = ref(true)
const isDesktopCollapsed = computed(() => !isMobile.value && goodsPanelCollapsed.value)

const {
  saving,
  accounts,
  selectedAccountId,
  goodsList,
  selectedGoods,
  currentConfig,
  configForm,
  skuList,
  selectedSkuId,
  skuConfigs,
  hasMultipleSku,
  hasFixedDelivery,
  hasCardDelivery,
  fixedTemplateOptions,
  selectedFixedTemplate,
  goodsTotal,
  goodsLoading,
  goodsListRef,
  onlyOnSale,
  detailDialogVisible,
  selectedGoodsId,
  deliveryRecords,
  recordsLoading,
  recordsTotal,
  recordsPageNum,
  recordsPageSize,
  recordsTotalPages,
  isMobile,
  mobileView,
  confirmDialog,
  loadAccounts,
  handleAccountChange,
  selectGoods,
  saveConfig,
  toggleAutoDelivery,
  selectDeliveryMode,
  goToFixedTemplates,
  loadDeliveryRecords,
  handleRecordsPageChange,
  viewGoodsDetail,
  goToAutoReply,
  handleConfirmShipment,
  handleTriggerDelivery,
  handleDialogConfirm,
  handleDialogCancel,
  handleSkuChange,
  handleGoodsScroll,
  goBackToGoods,
  toggleOnlyOnSale,
  formatTime,
  formatPrice,
  getStatusText,
  getStatusClass,
  getRecordStatusText,
  getRecordStatusClass,
  kamiConfigOptions,
  selectedKamiConfigId,
  addReceiptFollowUpMessage,
  removeReceiptFollowUpMessage,
  appendDeliveryVariable,
  appendReceiptVariable,
  apiHintUrl,
  apiHintParamsJson,
  confirmShipmentUrl,
  confirmShipmentParamsJson,
  copyApiUrl,
  copyApiParams,
  copyConfirmShipmentUrl,
  copyConfirmShipmentParams
} = useAutoDelivery()

const showCustomDelivery = ref(false)

// 注入导航栏内容 — inject 必须在 setup 顶层
const setHeaderContent = inject<(content: any) => void>('setHeaderContent')

const HeaderSelectors = defineComponent({
  setup() {
    return () => h('div', { class: 'header-selectors' }, [
      h('div', { class: 'header-select-wrap' }, [
        h('select', {
          class: 'header-select',
          onChange: (e: Event) => {
            const val = (e.target as HTMLSelectElement).value
            selectedAccountId.value = val ? parseInt(val) : null
            handleAccountChange()
          }
        }, [
          h('option', { value: '', disabled: true, selected: !selectedAccountId.value }, '账号'),
          ...accounts.value.map(acc =>
            h('option', {
              value: acc.id.toString(),
              selected: selectedAccountId.value === acc.id
            }, acc.accountNote || acc.unb)
          )
        ]),
        h(IconChevronDown, { class: 'header-select-icon' })
      ])
    ])
  }
})

onMounted(() => {
  if (setHeaderContent) {
    setHeaderContent(HeaderSelectors)
  }
})
</script>

<template>
  <div class="ad">
    <!-- Header -->
    <div class="ad__header">
      <div class="ad__title-row">
        <div class="ad__title-icon">
          <IconTruck />
        </div>
        <h1 class="ad__title">自动发货</h1>
      </div>
      <div class="ad__actions">
        <div class="ad__select-wrap">
          <select
            :value="selectedAccountId"
            class="ad__select"
            @change="(e: Event) => { selectedAccountId = (e.target as HTMLSelectElement).value ? parseInt((e.target as HTMLSelectElement).value) : null; handleAccountChange() }"
          >
            <option :value="null" disabled>选择账号</option>
            <option v-for="acc in accounts" :key="acc.id" :value="acc.id">
              {{ acc.accountNote || acc.unb }}
            </option>
          </select>
          <span class="ad__select-icon">
            <IconChevronDown />
          </span>
        </div>
      </div>
    </div>

    <!-- Body -->
    <div class="ad__body">
      <!-- Goods Panel -->
      <div
        class="ad__goods-panel"
        :class="{ 'ad__goods-panel--hidden': isMobile && mobileView === 'config', 'ad__goods-panel--collapsed': isDesktopCollapsed }"
      >
        <template v-if="!isDesktopCollapsed">
          <div class="ad__goods-toolbar">
            <span class="ad__goods-toolbar-title">商品列表</span>
            <span v-if="goodsTotal > 0" class="ad__goods-toolbar-count">共 {{ goodsTotal }} 件</span>
            <button class="ad__only-on-sale-btn" :class="{ 'ad__only-on-sale-btn--active': onlyOnSale }" @click="toggleOnlyOnSale">
              {{ onlyOnSale ? '在售' : '全部' }}
            </button>
          </div>

          <div
            class="ad__goods-list"
            ref="goodsListRef"
            @scroll="handleGoodsScroll"
          >
            <!-- Loading first page -->
            <div v-if="goodsLoading && goodsList.length === 0" class="ad__loading">
              <div class="ad__spinner"></div>
              <span>加载中...</span>
            </div>

            <!-- Goods items -->
            <div
              v-for="goods in goodsList"
              :key="goods.item.xyGoodId"
              class="ad__goods-item"
              :class="{ 'ad__goods-item--active': selectedGoods?.item.xyGoodId === goods.item.xyGoodId, 'ad__goods-item--offline': goods.item.status !== 0 }"
              @click="selectGoods(goods)"
            >
              <img
                :src="goods.item.coverPic"
                :alt="goods.item.title"
                class="ad__goods-cover"
              />
              <div class="ad__goods-info">
                <div class="ad__goods-title">{{ goods.item.title }}</div>
                <div class="ad__goods-meta">
                  <span class="ad__goods-price">{{ formatPrice(goods.item.soldPrice) }}</span>
                  <span v-if="goods.item.skuCount > 1" class="ad__goods-sku-tag">{{ goods.item.skuCount }}规格</span>
                  <span
                    class="ad__goods-status"
                    :class="`ad__goods-status--${getStatusClass(goods.item.status)}`"
                  >
                    {{ getStatusText(goods.item.status) }}
                  </span>
                  <span
                    v-if="goods.xianyuAutoDeliveryOn === 1"
                    class="ad__goods-auto-badge ad__goods-auto-badge--on"
                  >
                    <IconSparkle />
                    {{ goods.autoDeliveryType === 2 ? '卡密' : '固定' }}
                  </span>
                </div>
              </div>
            </div>

            <!-- Loading more -->
            <div v-if="goodsLoading && goodsList.length > 0" class="ad__loading">
              <div class="ad__spinner"></div>
              <span>加载中...</span>
            </div>

            <!-- No more data -->
            <div
              v-if="!goodsLoading && goodsList.length > 0 && goodsList.length >= goodsTotal"
              class="ad__no-more"
            >
              已加载全部
            </div>

            <!-- Empty -->
            <div v-if="!goodsLoading && goodsList.length === 0" class="ad__empty">
              <IconPackage />
              <span class="ad__empty-text">暂无商品</span>
            </div>
          </div>
        </template>
        <template v-else>
          <div class="ad__goods-icons">
            <div
              v-for="goods in goodsList"
              :key="goods.item.xyGoodId"
              class="ad__goods-icon-item"
              :class="{ 'ad__goods-icon-item--active': selectedGoods?.item.xyGoodId === goods.item.xyGoodId }"
              :title="goods.item.title"
              @click="selectGoods(goods)"
            >
              <img :src="goods.item.coverPic" class="ad__goods-icon-img" />
            </div>
          </div>
        </template>
        <button
          class="ad__goods-toggle"
          :title="goodsPanelCollapsed ? '展开商品列表' : '折叠商品列表'"
          @click="goodsPanelCollapsed = !goodsPanelCollapsed"
        >
          <svg v-if="goodsPanelCollapsed" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18l6-6-6-6"/></svg>
          <svg v-else xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M15 18l-6-6 6-6"/></svg>
        </button>
      </div>

      <!-- Config Panel -->
      <div
        class="ad__config-panel"
        :class="{ 'ad__config-panel--hidden': isMobile && mobileView === 'goods' }"
      >
        <!-- Mobile back button -->
        <div v-if="isMobile && selectedGoods" class="ad__config-header">
          <button class="ad__back-btn" @click="goBackToGoods">
            <IconChevronLeft />
            返回
          </button>
          <img
            v-if="selectedGoods"
            :src="selectedGoods.item.coverPic"
            :alt="selectedGoods.item.title"
            class="ad__config-goods-cover"
          />
          <div class="ad__config-goods-info">
            <div class="ad__config-goods-title">{{ selectedGoods.item.title }}</div>
            <div class="ad__config-goods-sub">{{ formatPrice(selectedGoods.item.soldPrice) }}</div>
          </div>
          <button class="btn btn--ghost btn--sm" @click="viewGoodsDetail">
            <IconImage />
            <span class="mobile-hidden">详情</span>
          </button>
          <button class="btn btn--ghost btn--sm ad__custom-delivery-btn" @click="showCustomDelivery = !showCustomDelivery">
            <IconRobot />
            <span class="mobile-hidden">自定义发货</span>
          </button>
          <button class="btn btn--ghost btn--sm" @click="goToAutoReply">
            <IconChat />
            <span class="mobile-hidden">配置回复</span>
          </button>
        </div>

        <!-- Desktop config header -->
        <div v-if="!isMobile && selectedGoods" class="ad__config-header">
          <img
            :src="selectedGoods.item.coverPic"
            :alt="selectedGoods.item.title"
            class="ad__config-goods-cover"
          />
          <div class="ad__config-goods-info">
            <div class="ad__config-goods-title">{{ selectedGoods.item.title }}</div>
            <div class="ad__config-goods-sub">{{ formatPrice(selectedGoods.item.soldPrice) }}</div>
          </div>
          <button class="btn btn--ghost btn--sm" @click="viewGoodsDetail">
            <IconImage />
            <span class="mobile-hidden">详情</span>
          </button>
          <button class="btn btn--ghost btn--sm ad__custom-delivery-btn" @click="showCustomDelivery = !showCustomDelivery">
            <IconRobot />
            <span class="mobile-hidden">自定义发货</span>
          </button>
          <button class="btn btn--ghost btn--sm" @click="goToAutoReply">
            <IconChat />
            <span class="mobile-hidden">配置回复</span>
          </button>
        </div>

        <!-- Empty state -->
        <div v-if="!selectedGoods" class="ad__config-empty">
          <IconPackage />
          <span class="ad__config-empty-text">选择商品以配置自动发货</span>
        </div>

        <!-- Config content -->
        <div v-if="selectedGoods" class="ad__config-scroll">
          <!-- 自动发货总开关 -->
          <div class="ad__config-section ad__config-section--no-pad-bottom">
            <div class="ad__master-toggles">
              <div class="ad__master-toggle">
                <div class="ad__toggle-label">自动发货</div>
                <label class="ad__switch ad__switch--sm">
                  <input
                    type="checkbox"
                    :checked="selectedGoods.xianyuAutoDeliveryOn === 1"
                    @change="toggleAutoDelivery(($event.target as HTMLInputElement).checked)"
                  />
                  <span class="ad__switch-track"></span>
                  <span class="ad__switch-thumb"></span>
                </label>
              </div>
              <div class="ad__channel-lock">
                <strong>当前发货类型</strong>
                <span>{{ hasFixedDelivery ? '固定内容' : '卡密' }}</span>
              </div>
            </div>
          </div>

          <!-- SKU Selector -->
          <div v-if="hasMultipleSku" class="ad__config-section ad__config-section--no-pad-bottom">
            <div class="ad__config-section-title">选择规格</div>
            <div class="ad__sku-tabs">
              <button
                v-for="sku in skuList"
                :key="sku.skuId || sku.id"
                class="ad__sku-tab"
                :class="{ 'ad__sku-tab--active': selectedSkuId === sku.skuId, 'ad__sku-tab--configured': skuConfigs.has(sku.skuId || '') }"
                @click="selectedSkuId = sku.skuId || null; handleSkuChange()"
              >
                <span class="ad__sku-tab__name">{{ sku.valueText || `规格${sku.skuId}` }}</span>
                <span class="ad__sku-tab__price">¥{{ (sku.price / 100).toFixed(2) }}</span>
              </button>
            </div>
          </div>

          <!-- 第一步：选择互斥的发货类型 -->
          <div class="ad__config-section">
            <div class="ad__step-heading">
              <span>1</span>
              <div><strong>选择发货类型</strong><small>固定内容和卡密严格二选一，两套配置互不依赖</small></div>
            </div>
            <div class="ad__mode-options">
              <label class="ad__mode-option" :class="{ 'ad__mode-option--active': hasFixedDelivery }">
                <input type="radio" name="deliveryMode" :checked="hasFixedDelivery" @change="selectDeliveryMode(1)">
                <IconText />
                <span><strong>固定内容</strong><small>每笔订单复用同一份说明，不消耗卡密库存</small></span>
              </label>
              <label class="ad__mode-option" :class="{ 'ad__mode-option--active': hasCardDelivery }">
                <input type="radio" name="deliveryMode" :checked="hasCardDelivery" @change="selectDeliveryMode(2)">
                <span class="ad__mode-key">🔑</span>
                <span><strong>卡密内容</strong><small>每笔订单从绑定仓库分配卡密并扣减库存</small></span>
              </label>
            </div>
          </div>

          <!-- 第二步：配置当前发货类型 -->
          <div class="ad__step-heading ad__step-heading--standalone">
            <span>2</span>
            <div><strong>配置发货来源</strong><small>{{ hasFixedDelivery ? '选择已维护的固定内容模板' : '选择卡密仓库并设置卡密发货文案' }}</small></div>
          </div>
          <template v-if="hasFixedDelivery">
            <div class="ad__config-section">
              <div class="ad__field-heading">
                <span>固定内容模板</span>
                <button type="button" class="ad__add-message-btn" @click="goToFixedTemplates">管理模板</button>
              </div>
              <div class="ad__section-note">模板在独立模块中批量维护，商品只引用模板，不会读取或消耗卡密。</div>
              <select v-model="configForm.fixedTemplateId" class="native-select">
                <option :value="null" disabled>请选择固定内容模板</option>
                <option v-for="template in fixedTemplateOptions" :key="template.id" :value="template.id">
                  {{ template.templateName }}
                </option>
              </select>
              <div v-if="fixedTemplateOptions.length === 0" class="ad__field-empty">
                暂无模板，请先点击“管理模板”创建
              </div>
              <div v-if="selectedFixedTemplate" class="ad__template-preview">
                <div><strong>全部发货内容</strong><p>{{ selectedFixedTemplate.deliveryContent }}</p></div>
                <div><strong>最终发送模板</strong><p>{{ selectedFixedTemplate.messageTemplate }}</p></div>
              </div>
            </div>
          </template>

          <template v-if="hasCardDelivery">
            <div class="ad__config-section">
              <div class="ad__config-section-title">卡密发货配置</div>
              <div class="ad__section-note">只从所选仓库分配卡密，不读取固定内容模板；同一订单只扣减一次。</div>
              <div class="ad__field-block">
                <label>卡密仓库</label>
                <select
                  v-model="selectedKamiConfigId"
                  class="native-select"
                >
                  <option value="" disabled>请选择卡密配置</option>
                  <option
                    v-for="opt in kamiConfigOptions"
                    :key="opt.id"
                    :value="String(opt.id)"
                  >
                    {{ opt.aliasName || `配置#${opt.id}` }}
                  </option>
                </select>
                <div v-if="kamiConfigOptions.length === 0" class="ad__field-empty">
                  暂无卡密配置，请先在「卡密配置」页面创建
                </div>
              </div>

              <div class="ad__field-block">
                <div class="ad__field-heading">
                  <span>卡密发货模板</span>
                  <div class="ad__variable-list">
                    <button type="button" @click="appendDeliveryVariable('{buyerName}')">会员名称</button>
                    <button type="button" @click="appendDeliveryVariable('{orderId}')">订单号</button>
                    <button type="button" @click="appendDeliveryVariable('{deliveryContent}')">全部发货内容</button>
                  </div>
                </div>
                <textarea
                  v-model="configForm.deliveryMessageTemplate"
                  class="ad__textarea ad__textarea--compact"
                  maxlength="1000"
                  placeholder="例：您好，{buyerName}，订单 {orderId} 的卡密为：{deliveryContent}"
                ></textarea>
                <div class="ad__textarea-footer">
                  <span class="ad__textarea-hint">{deliveryContent} 会替换为本单实际分配的全部卡密</span>
                  <span class="ad__textarea-count">{{ configForm.deliveryMessageTemplate.length }} / 1000</span>
                </div>
              </div>
            </div>
          </template>

          <!-- 第三步：独立选择发送渠道 -->
          <div class="ad__config-section">
            <div class="ad__step-heading">
              <span>3</span>
              <div>
                <strong>{{ hasFixedDelivery ? '固定内容发送方式' : '卡密发送方式' }}</strong>
                <small>可只开启一个；两个都开启时先写发货凭证，再发送买家私聊</small>
              </div>
            </div>
            <div class="ad__channel-summary">
              <label>
                <span><strong>发货凭证</strong><small>把最终内容写入订单凭证</small></span>
                <span class="ad__switch ad__switch--sm">
                  <input type="checkbox" :checked="configForm.voucherDeliveryEnabled === 1" @change="configForm.voucherDeliveryEnabled = ($event.target as HTMLInputElement).checked ? 1 : 0">
                  <span class="ad__switch-track"></span><span class="ad__switch-thumb"></span>
                </span>
              </label>
              <label>
                <span><strong>买家私聊</strong><small>发送同一份最终内容，失败自动重试</small></span>
                <span class="ad__switch ad__switch--sm">
                  <input type="checkbox" :checked="configForm.chatDeliveryEnabled === 1" @change="configForm.chatDeliveryEnabled = ($event.target as HTMLInputElement).checked ? 1 : 0">
                  <span class="ad__switch-track"></span><span class="ad__switch-thumb"></span>
                </span>
              </label>
            </div>
            <div v-if="!configForm.voucherDeliveryEnabled && !configForm.chatDeliveryEnabled" class="ad__limit-note">
              至少开启一个发送渠道。
            </div>
            <div v-else-if="configForm.voucherDeliveryEnabled" class="ad__limit-note">
              发货凭证限制最终内容不超过200字；内容较长时可只开启买家私聊。
            </div>

            <div class="ad__image-section">
              <div class="ad__image-section-title">发货图片</div>
              <div class="ad__image-section-hint">可选，最多3张；随已开启的发货渠道发送</div>
              <MultiImageUploader
                v-if="selectedAccountId"
                :account-id="selectedAccountId"
                :max="3"
                v-model="configForm.autoDeliveryImageUrl"
              />
            </div>

            <div class="ad__follow-up-heading">
              <div>
                <strong>确认收货后好评引导</strong>
                <span>检测到买家确认收货后，按顺序逐条发送；买家已评价则停止发送</span>
              </div>
              <button type="button" class="ad__add-message-btn" @click="addReceiptFollowUpMessage">添加话术</button>
            </div>
            <div v-if="configForm.receiptFollowUpMessages.length === 0" class="ad__follow-up-empty">
              暂未配置，确认收货后不会发送引导消息
            </div>
            <div v-for="(message, index) in configForm.receiptFollowUpMessages" :key="index" class="ad__follow-up-item">
              <div class="ad__field-heading">
                <span>第 {{ index + 1 }} 条</span>
                <div class="ad__variable-list">
                  <button type="button" @click="appendReceiptVariable(index, '{buyerName}')">会员名称</button>
                  <button type="button" @click="appendReceiptVariable(index, '{orderId}')">订单号</button>
                  <button type="button" @click="appendReceiptVariable(index, '{deliveryContent}')">发货内容</button>
                  <button type="button" class="ad__variable-remove" @click="removeReceiptFollowUpMessage(index)">删除</button>
                </div>
              </div>
              <textarea v-model="configForm.receiptFollowUpMessages[index]" class="ad__textarea ad__textarea--compact" maxlength="500" placeholder="例：感谢支持，满意的话欢迎点亮小红花。"></textarea>
              <div class="ad__textarea-footer">
                <span class="ad__textarea-hint">单条独立发送</span>
                <span class="ad__textarea-count">{{ message.length }} / 500</span>
              </div>
            </div>
            <label v-if="configForm.receiptFollowUpMessages.length > 1" class="ad__interval-field">
              <span>每条间隔</span>
              <input v-model.number="configForm.receiptFollowUpIntervalSeconds" type="number" min="5" max="600" />
              <span>秒</span>
            </label>

            <div class="ad__save-row">
              <button class="btn btn--primary" :class="{ 'btn--loading': saving }" :disabled="saving" @click="saveConfig">
                <IconCheck />
                {{ saving ? '保存中' : '保存全部配置' }}
              </button>
              <span v-if="currentConfig" class="ad__save-time">更新于 {{ formatTime(currentConfig.updateTime) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Goods Detail Dialog -->
    <GoodsDetail
      v-model="detailDialogVisible"
      :goods-id="selectedGoodsId"
      :account-id="selectedAccountId"
    />

    <!-- Confirm Dialog -->
    <Transition name="overlay-fade">
      <div
        v-if="confirmDialog.visible"
        class="ad__dialog-overlay"
        @click.self="handleDialogCancel"
      >
        <div class="ad__dialog">
          <div class="ad__dialog-header">
            <h3 class="ad__dialog-title">{{ confirmDialog.title }}</h3>
          </div>
          <div class="ad__dialog-body">
            <p class="ad__dialog-text" :class="{ 'ad__dialog-text--danger': confirmDialog.type === 'danger' }" style="white-space: pre-line;">{{ confirmDialog.message }}</p>
          </div>
          <div class="ad__dialog-footer">
            <button
              class="ad__dialog-btn ad__dialog-btn--cancel"
              @click="handleDialogCancel"
            >
              取消
            </button>
            <button
              class="ad__dialog-btn"
              :class="confirmDialog.type === 'danger' ? 'ad__dialog-btn--danger' : 'ad__dialog-btn--primary'"
              @click="handleDialogConfirm"
            >
              确定
            </button>
          </div>
        </div>
      </div>
    </Transition>

    <!-- Custom Delivery API Hint Dialog -->
    <Transition name="overlay-fade">
      <div v-if="showCustomDelivery" class="ad__overlay" @click.self="showCustomDelivery = false">
        <div class="ad__dialog ad__dialog--wide">
          <div class="ad__dialog-header">
            <h3 class="ad__dialog-title">API 接入指南</h3>
            <button class="ad__dialog-close" @click="showCustomDelivery = false">&times;</button>
          </div>
          <div class="ad__dialog-body">
            <div class="ad__api-hint-desc">
              自定义发货需调用 <code>/api/order/list</code> 获取待发货订单，再调用 <code>/api/order/confirmShipment</code> 确认发货。
            </div>
            <div class="ad__api-hint-cols">
              <div class="ad__api-hint-col">
                <div class="ad__api-hint-col-title">获取订单列表</div>
                <div class="ad__api-hint-section">
                  <div class="ad__api-hint-label">
                    接口地址
                    <button class="ad__api-hint-copy-btn" @click="copyApiUrl"><IconCopy /> 复制</button>
                  </div>
                  <div class="ad__api-hint-code">POST {{ apiHintUrl }}</div>
                </div>
                <div class="ad__api-hint-section">
                  <div class="ad__api-hint-label">
                    请求参数
                    <button class="ad__api-hint-copy-btn" @click="copyApiParams"><IconCopy /> 复制</button>
                  </div>
                  <pre class="ad__api-hint-pre"><code>{{ apiHintParamsJson }}</code></pre>
                </div>
                <div class="ad__api-hint-params-desc">
                  <div class="ad__api-hint-params-title">参数说明</div>
                  <table class="ad__api-hint-table">
                    <thead><tr><th>参数</th><th>类型</th><th>必填</th><th>说明</th></tr></thead>
                    <tbody>
                      <tr><td>xianyuAccountId</td><td>number</td><td>否</td><td>闲鱼账号ID</td></tr>
                      <tr><td>xyGoodsId</td><td>string</td><td>否</td><td>闲鱼商品ID</td></tr>
                      <tr><td>orderStatus</td><td>number</td><td>否</td><td>1=待付款 2=待发货 3=已发货 4=已完成 5=已关闭</td></tr>
                      <tr><td>pageNum</td><td>number</td><td>是</td><td>页码</td></tr>
                      <tr><td>pageSize</td><td>number</td><td>是</td><td>每页条数</td></tr>
                    </tbody>
                  </table>
                </div>
              </div>
              <div class="ad__api-hint-col">
                <div class="ad__api-hint-col-title">确认发货</div>
                <div class="ad__api-hint-section">
                  <div class="ad__api-hint-label">
                    接口地址
                    <button class="ad__api-hint-copy-btn" @click="copyConfirmShipmentUrl"><IconCopy /> 复制</button>
                  </div>
                  <div class="ad__api-hint-code">POST {{ confirmShipmentUrl }}</div>
                </div>
                <div class="ad__api-hint-section">
                  <div class="ad__api-hint-label">
                    请求参数
                    <button class="ad__api-hint-copy-btn" @click="copyConfirmShipmentParams"><IconCopy /> 复制</button>
                  </div>
                  <pre class="ad__api-hint-pre"><code>{{ confirmShipmentParamsJson }}</code></pre>
                </div>
                <div class="ad__api-hint-params-desc">
                  <div class="ad__api-hint-params-title">参数说明</div>
                  <table class="ad__api-hint-table">
                    <thead><tr><th>参数</th><th>类型</th><th>必填</th><th>说明</th></tr></thead>
                    <tbody>
                      <tr><td>xianyuAccountId</td><td>number</td><td>是</td><td>闲鱼账号ID</td></tr>
                      <tr><td>orderId</td><td>string</td><td>是</td><td>订单ID</td></tr>
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
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
</style>

<style>
.kami-config-select-popper {
  min-width: 180px !important;
}
.kami-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 8px;
}
.kami-option__name {
  font-size: 14px;
  color: #1c1c1e;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.kami-option__stats {
  display: flex;
  align-items: center;
  gap: 2px;
  font-size: 11px;
  white-space: nowrap;
  flex-shrink: 0;
}
.kami-option__avail {
  color: #30D158;
  font-weight: 600;
}
.kami-option__divider {
  color: #c0c4cc;
}
.kami-option__total {
  color: #909399;
}
.ad__record-action-btn--manual {
  color: #FF9F0A;
  border-color: rgba(255, 149, 0, 0.2);
  margin-left: 4px;
}
@media (hover: hover) {
  .ad__record-action-btn--manual:hover {
    background: rgba(255, 149, 0, 0.06);
  }
}
.ad__overlay {
  position: fixed;
  top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(0,0,0,0.20);
  z-index: 950;
  backdrop-filter: blur(28px) saturate(1.8);
  -webkit-backdrop-filter: blur(28px) saturate(1.8);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
}
.ad__dialog {
  width: 100%;
  max-width: 480px;
  max-height: 80vh;
  background: rgba(255,255,255,0.72);
  backdrop-filter: blur(40px) saturate(2);
  -webkit-backdrop-filter: blur(40px) saturate(2);
  border: 1px solid rgba(255,255,255,0.75);
  border-radius: 20px;
  box-shadow: 0 16px 48px rgba(0,0,0,0.16), 0 2px 8px rgba(0,0,0,0.08);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.ad__dialog--wide {
  max-width: 720px;
}
.ad__dialog-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid rgba(60,60,67,.12);
}
.ad__dialog-title { font-size: 16px; font-weight: 600; color: #1c1c1e; margin: 0; }
.ad__dialog-close {
  width: 28px; height: 28px; display: flex; align-items: center; justify-content: center;
  font-size: 20px; color: rgba(28,28,30,.55); background: none; border: none; cursor: pointer; border-radius: 6px;
}
.ad__dialog-close:hover { background: rgba(0,0,0,0.04); }
.ad__dialog-body { flex: 1; overflow-y: auto; padding: 16px 20px; text-align: left; }
.ad__dialog-rows { display: flex; flex-direction: column; gap: 10px; }
.ad__dialog-row { display: flex; align-items: flex-start; gap: 12px; font-size: 13px; }
.ad__dialog-label { color: rgba(28,28,30,.55); min-width: 60px; flex-shrink: 0; line-height: 1.5; }
.ad__dialog-value { color: #1c1c1e; word-break: break-all; line-height: 1.5; }
.ad__manual-textarea {
  width: 100%; padding: 10px 12px; font-size: 13px; line-height: 1.5;
  border: 1px solid rgba(60,60,67,.12); border-radius: 8px; resize: vertical;
  font-family: inherit; color: #1c1c1e; background: transparent; box-sizing: border-box;
}
.ad__manual-textarea:focus { outline: none; border-color: #0A84FF; background: rgba(255,255,255,0.55); }
.ad__manual-btn {
  height: 32px; padding: 0 16px; font-size: 13px; font-weight: 500;
  border-radius: 8px; border: none; cursor: pointer; transition: all 0.2s ease;
}
.ad__manual-btn--cancel { background: rgba(60,60,67,.12); color: rgba(28,28,30,.55); }
.ad__manual-btn--confirm { background: #0A84FF; color: rgba(255,255,255,0.55); }
.ad__manual-btn--confirm:disabled { opacity: 0.5; cursor: not-allowed; }

.btn-glass { display: inline-flex; align-items: center; justify-content: center; gap: 6px; padding: 8px 16px; border-radius: 100px; font-size: 13px; font-weight: 590; cursor: pointer; transition: opacity .15s, transform .12s; border: none; font-family: inherit; user-select: none; white-space: nowrap; }
.btn-glass:active { opacity: .80; transform: scale(.96); }
.btn-glass--primary { background: rgba(10,132,255,0.85); color: #fff; border: 1px solid rgba(255,255,255,0.35); box-shadow: 0 4px 16px rgba(10,132,255,0.35), 0 8px 32px rgba(0,0,0,0.08); }
.btn-glass--default { background: rgba(255,255,255,0.70); color: #0A84FF; border: 1px solid rgba(255,255,255,0.85); box-shadow: 0 8px 32px rgba(0,0,0,0.08); }
.btn-glass--success { background: rgba(48,209,88,0.85); color: #fff; border: 1px solid rgba(255,255,255,0.35); }
.btn-glass--warning { background: rgba(255,159,10,0.85); color: #fff; border: 1px solid rgba(255,255,255,0.35); }
.btn-glass--danger { color: #FF453A; background: rgba(255,69,58,0.15); border: 1px solid rgba(255,69,58,0.2); }
.tag { display: inline-flex; align-items: center; padding: 2px 10px; border-radius: 100px; font-size: 12px; font-weight: 500; }
.tag--success { background: rgba(48,209,88,0.12); color: #30D158; }
.tag--warning { background: rgba(255,159,10,0.12); color: #FF9F0A; }
.tag--info { background: rgba(120,120,128,0.12); color: rgba(28,28,30,.55); }
.tag--danger { background: rgba(255,69,58,0.12); color: #FF453A; }
.native-select { padding: 8px 12px; border: 1px solid rgba(60,60,67,.12); border-radius: 8px; background: rgba(255,255,255,0.55); color: #1c1c1e; font-size: 13px; outline: none; cursor: pointer; font-family: inherit; }
.native-select:focus { border-color: #0A84FF; }
.native-input { padding: 8px 12px; border: 1px solid rgba(60,60,67,.12); border-radius: 8px; background: rgba(255,255,255,0.55); color: #1c1c1e; font-size: 13px; outline: none; font-family: inherit; box-sizing: border-box; width: 100%; resize: vertical; line-height: 1.5; }
.native-input:focus { border-color: #0A84FF; }

</style>
