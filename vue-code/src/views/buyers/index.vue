<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getAccountList } from '@/api/account'
import {
  getBuyerProfileDetail,
  getBuyerProfiles,
  saveBuyerProfile,
  type BuyerMessage,
  type BuyerOrder,
  type BuyerProfile,
  type BuyerProfileDetail
} from '@/api/buyer'
import { queryOrderRateDetails, type OrderRateDetail } from '@/api/order'
import type { Account } from '@/types'
import { hasPermission } from '@/utils/permission'
import { toast } from '@/utils/toast'

type DetailTab = 'orders' | 'messages' | 'goods' | 'ratings'

const accounts = ref<Account[]>([])
const accountId = ref<number>()
const keyword = ref('')
const blockedFilter = ref('')
const loading = ref(false)
const profiles = ref<BuyerProfile[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = 30
const editing = ref<BuyerProfile>()
const detailLoading = ref(false)
const detail = ref<BuyerProfileDetail>()
const detailProfile = ref<BuyerProfile>()
const detailTab = ref<DetailTab>('orders')
const selectedOrderId = ref('')
const ratingLoading = ref(false)
const ratingMap = ref<Record<string, OrderRateDetail>>({})
const detailRequestId = ref(0)
const form = ref({
  buyerUserName: '',
  tagsText: '',
  note: '',
  automationBlocked: false,
  blockedReason: ''
})

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))
const orderedMessages = computed(() => {
  const records = detail.value?.messages || []
  const filtered = selectedOrderId.value
    ? records.filter(message => message.relatedOrderIds?.includes(selectedOrderId.value))
    : records
  return [...filtered].sort((a, b) => (a.messageTime || 0) - (b.messageTime || 0))
})
const loadAccounts = async () => {
  const response = await getAccountList()
  accounts.value = response.data?.accounts || []
  accountId.value = accountId.value || accounts.value[0]?.id
}

const loadProfiles = async () => {
  loading.value = true
  try {
    const response = await getBuyerProfiles({
      xianyuAccountId: accountId.value,
      keyword: keyword.value.trim() || undefined,
      automationBlocked: blockedFilter.value === '' ? undefined : blockedFilter.value === 'true',
      pageNum: pageNum.value,
      pageSize
    })
    profiles.value = response.data?.records || []
    total.value = response.data?.total || 0
  } finally {
    loading.value = false
  }
}

const search = () => {
  pageNum.value = 1
  loadProfiles()
}

const openEdit = (profile: BuyerProfile) => {
  editing.value = profile
  form.value = {
    buyerUserName: profile.buyerUserName || '',
    tagsText: (profile.tags || []).join('，'),
    note: profile.note || '',
    automationBlocked: profile.automationBlocked,
    blockedReason: profile.blockedReason || ''
  }
}

const save = async () => {
  if (!editing.value) return
  const target = editing.value
  await saveBuyerProfile({
    xianyuAccountId: target.xianyuAccountId,
    buyerUserId: target.buyerUserId,
    buyerUserName: form.value.buyerUserName,
    tags: form.value.tagsText.split(/[,，]/).map(value => value.trim()).filter(Boolean),
    note: form.value.note,
    automationBlocked: form.value.automationBlocked,
    blockedReason: form.value.blockedReason
  })
  toast.success('买家资料已保存')
  editing.value = undefined
  await loadProfiles()
  if (detailProfile.value?.buyerUserId === target.buyerUserId) {
    await openDetail(profiles.value.find(item => item.buyerUserId === target.buyerUserId) || target)
  }
}

const openDetail = async (profile: BuyerProfile) => {
  const requestId = ++detailRequestId.value
  detailProfile.value = profile
  detail.value = undefined
  detailTab.value = 'orders'
  selectedOrderId.value = ''
  ratingMap.value = {}
  ratingLoading.value = false
  detailLoading.value = true
  try {
    const response = await getBuyerProfileDetail({
      xianyuAccountId: profile.xianyuAccountId,
      buyerUserId: profile.buyerUserId
    })
    if (requestId !== detailRequestId.value) return
    detail.value = response.data
    detailProfile.value = response.data?.profile || profile
  } finally {
    detailLoading.value = false
  }
}

const closeDetail = () => {
  detailRequestId.value++
  detailProfile.value = undefined
  detail.value = undefined
}

const showOrderConversation = (order: BuyerOrder) => {
  selectedOrderId.value = order.orderId || ''
  detailTab.value = 'messages'
}

const showOrderRating = async (order: BuyerOrder) => {
  selectedOrderId.value = order.orderId || ''
  detailTab.value = 'ratings'
  if (!order.orderId || ratingMap.value[order.orderId]?.synced) return
  const requestId = detailRequestId.value
  ratingLoading.value = true
  try {
    // 老订单按订单号精确补查，避免为查看单条历史评价扫描大量平台数据。
    const response = await queryOrderRateDetails({
      xianyuAccountId: order.xianyuAccountId,
      orderIds: [order.orderId]
    })
    if (requestId !== detailRequestId.value) return
    const result = response.data?.[0]
    if (result) {
      ratingMap.value = { ...ratingMap.value, [order.orderId]: result }
    }
  } catch {
    if (requestId === detailRequestId.value) {
      toast.error('评价同步失败，请稍后重试')
    }
  } finally {
    if (requestId === detailRequestId.value) ratingLoading.value = false
  }
}

const changePage = (offset: number) => {
  pageNum.value = Math.min(totalPages.value, Math.max(1, pageNum.value + offset))
  loadProfiles()
}

const orderMessageCount = (order: BuyerOrder) =>
  detail.value?.messages.filter(message => message.relatedOrderIds?.includes(order.orderId || '')).length || 0

const orderStatus = (order: BuyerOrder) => {
  if (order.deliveryStatus === 'COMPLETED' || order.state === 1) return '已交付'
  if (order.deliveryStatus === 'FAILED' || order.state === -1) return '交付失败'
  if (order.deliveryStatus === 'REVIEW_REQUIRED') return '待人工复核'
  return '处理中'
}

const formatTime = (value?: string | number) => {
  if (!value) return '-'
  if (typeof value === 'number') return new Date(value).toLocaleString('zh-CN', { hour12: false })
  return value.replace('T', ' ').slice(0, 19)
}

const messageSender = (message: BuyerMessage) =>
  message.direction === 'BUYER' ? (message.senderUserName || detailProfile.value?.buyerUserName || '买家') : '商家'

onMounted(async () => {
  await loadAccounts()
  await loadProfiles()
})
</script>

<template>
  <div class="buyer-page">
    <section class="panel toolbar">
      <div>
        <span class="eyebrow">CUSTOMER 360</span>
        <h2>买家管理</h2>
        <p>从买家进入订单、商品、会话和双方评价，交易关系在一个工作台内完整回溯。</p>
      </div>
      <div class="filters">
        <select v-model="accountId" @change="search">
          <option v-for="account in accounts" :key="account.id" :value="account.id">
            {{ account.accountNote || account.unb }}
          </option>
        </select>
        <select v-model="blockedFilter" @change="search">
          <option value="">全部买家</option>
          <option value="false">自动化正常</option>
          <option value="true">已暂停自动化</option>
        </select>
        <input v-model="keyword" placeholder="搜索名称、ID、标签或备注" @keyup.enter="search" />
        <button class="primary" @click="search">查询</button>
      </div>
    </section>

    <section class="panel table-panel">
      <div v-if="loading" class="empty">加载中...</div>
      <div v-else-if="profiles.length === 0" class="empty">暂无买家记录，收到咨询或订单后会自动建立资料。</div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>买家</th>
              <th>标签</th>
              <th>消息 / 订单</th>
              <th>成交金额</th>
              <th>自动化状态</th>
              <th>最近互动</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="profile in profiles" :key="profile.id" class="buyer-row" tabindex="0" @click="openDetail(profile)" @keyup.enter="openDetail(profile)">
              <td>
                <strong>{{ profile.buyerUserName || '未命名买家' }}</strong>
                <small>{{ profile.buyerUserId }}</small>
              </td>
              <td>
                <span v-for="tag in profile.tags" :key="tag" class="tag">{{ tag }}</span>
                <span v-if="!profile.tags?.length" class="muted">未设置</span>
              </td>
              <td><strong>{{ profile.messageCount || 0 }} 条消息</strong><small>{{ profile.orderCount || 0 }} 笔订单</small></td>
              <td>¥{{ profile.totalAmount || '0.00' }}</td>
              <td>
                <span :class="profile.automationBlocked ? 'status blocked' : 'status normal'">
                  {{ profile.automationBlocked ? '已暂停' : '正常' }}
                </span>
                <small v-if="profile.blockedReason">{{ profile.blockedReason }}</small>
              </td>
              <td>{{ formatTime(profile.lastInteractionTime) }}</td>
              <td class="row-actions">
                <button class="link" @click.stop="openDetail(profile)">查看全链路</button>
                <button v-if="hasPermission('action:buyer-write')" class="link secondary" @click.stop="openEdit(profile)">编辑</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer class="pager">
        <span>共 {{ total }} 位买家</span>
        <div>
          <button :disabled="pageNum <= 1" @click="changePage(-1)">上一页</button>
          <span>{{ pageNum }} / {{ totalPages }}</span>
          <button :disabled="pageNum >= totalPages" @click="changePage(1)">下一页</button>
        </div>
      </footer>
    </section>

    <Teleport to="body">
      <div v-if="detailProfile" class="detail-overlay" @click.self="closeDetail">
        <aside class="detail-drawer">
          <header class="detail-header">
            <div class="buyer-identity">
              <span class="avatar">{{ (detailProfile.buyerUserName || '买').slice(0, 1) }}</span>
              <span><small>买家全链路</small><h3>{{ detailProfile.buyerUserName || '未命名买家' }}</h3><p>{{ detailProfile.buyerUserId }}</p></span>
            </div>
            <div class="detail-header-actions">
              <button v-if="hasPermission('action:buyer-write')" @click="openEdit(detailProfile)">编辑资料</button>
              <button class="close" @click="closeDetail">×</button>
            </div>
          </header>

          <div v-if="detailLoading" class="detail-loading">正在汇总订单、会话、商品与评价...</div>
          <template v-else-if="detail">
            <section class="profile-strip">
              <div><span>成交金额</span><strong>¥{{ detail.profile.totalAmount || '0.00' }}</strong></div>
              <div><span>订单</span><strong>{{ detail.orders.length }}</strong></div>
              <div><span>消息</span><strong>{{ detail.messages.length }}</strong></div>
              <div><span>关联商品</span><strong>{{ detail.goods.length }}</strong></div>
              <div><span>自动化</span><strong :class="{ danger: detail.profile.automationBlocked }">{{ detail.profile.automationBlocked ? '已暂停' : '正常' }}</strong></div>
            </section>

            <section v-if="detail.profile.tags?.length || detail.profile.note" class="profile-note">
              <div><span v-for="tag in detail.profile.tags" :key="tag" class="tag">{{ tag }}</span></div>
              <p v-if="detail.profile.note">{{ detail.profile.note }}</p>
            </section>

            <nav class="detail-tabs">
              <button :class="{ active: detailTab === 'orders' }" @click="detailTab = 'orders'">订单 {{ detail.orders.length }}</button>
              <button :class="{ active: detailTab === 'messages' }" @click="detailTab = 'messages'">会话 {{ detail.messages.length }}</button>
              <button :class="{ active: detailTab === 'goods' }" @click="detailTab = 'goods'">商品 {{ detail.goods.length }}</button>
              <button :class="{ active: detailTab === 'ratings' }" @click="detailTab = 'ratings'">评价记录</button>
            </nav>

            <main class="detail-body">
              <section v-if="detailTab === 'orders'" class="order-list">
                <article v-for="order in detail.orders" :key="order.id" class="order-card">
                  <div class="order-main">
                    <span class="order-title"><strong>{{ order.goodsTitle || '未命名商品' }}</strong><small>{{ order.skuName || '默认规格' }}</small></span>
                    <span><small>订单号</small><strong>{{ order.orderId || '-' }}</strong></span>
                    <span><small>下单时间</small><strong>{{ formatTime(order.orderCreateTime || order.createTime) }}</strong></span>
                    <span><small>实付 / 数量</small><strong>¥{{ order.totalPrice || '0.00' }} · {{ order.buyNum || 1 }} 件</strong></span>
                    <span :class="['order-state', orderStatus(order)]">{{ orderStatus(order) }}</span>
                  </div>
                  <div class="order-links">
                    <span>{{ orderMessageCount(order) }} 条关联消息</span>
                    <button :disabled="orderMessageCount(order) === 0" @click="showOrderConversation(order)">查看订单会话</button>
                    <button @click="showOrderRating(order)">查看评价</button>
                  </div>
                </article>
                <div v-if="detail.orders.length === 0" class="empty compact">暂无关联订单</div>
              </section>

              <section v-else-if="detailTab === 'messages'" class="conversation-view">
                <header>
                  <div><strong>订单会话</strong><small>买卖双方消息按时间顺序排列</small></div>
                  <select v-model="selectedOrderId">
                    <option value="">全部会话</option>
                    <option v-for="order in detail.orders.filter(item => item.orderId)" :key="order.id" :value="order.orderId">
                      {{ order.orderId }} · {{ order.goodsTitle || '未命名商品' }}
                    </option>
                  </select>
                </header>
                <div class="message-timeline">
                  <article v-for="message in orderedMessages" :key="message.id" :class="['message', message.direction.toLowerCase()]">
                    <div><strong>{{ messageSender(message) }}</strong><time>{{ formatTime(message.messageTime || message.createTime) }}</time></div>
                    <p>{{ message.content || '[非文本消息]' }}</p>
                    <small v-if="message.relatedOrderIds?.length">订单 {{ message.relatedOrderIds.join('、') }}</small>
                  </article>
                  <div v-if="orderedMessages.length === 0" class="empty compact">该筛选下暂无已同步消息</div>
                </div>
              </section>

              <section v-else-if="detailTab === 'goods'" class="goods-grid">
                <article v-for="goods in detail.goods" :key="goods.xyGoodsId">
                  <img v-if="goods.coverPic" :src="goods.coverPic" alt="" />
                  <span v-else class="goods-placeholder">商品</span>
                  <div><strong>{{ goods.title || '未命名商品' }}</strong><small>ID {{ goods.xyGoodsId }}</small></div>
                  <dl><div><dt>订单</dt><dd>{{ goods.orderCount }}</dd></div><div><dt>成交</dt><dd>¥{{ goods.totalAmount || '0.00' }}</dd></div><div><dt>最近</dt><dd>{{ formatTime(goods.lastOrderTime) }}</dd></div></dl>
                </article>
                <div v-if="detail.goods.length === 0" class="empty compact">暂无关联商品</div>
              </section>

              <section v-else class="rating-list">
                <div v-if="ratingLoading" class="sync-tip">正在从闲鱼同步双方真实评价...</div>
                <article v-for="order in detail.orders.filter(item => !selectedOrderId || item.orderId === selectedOrderId)" :key="order.id" class="rating-card">
                  <header>
                    <span><strong>{{ order.goodsTitle || '未命名商品' }}</strong><small>订单 {{ order.orderId || '-' }}</small></span>
                    <span class="rating-status">
                      {{ ratingMap[order.orderId || '']?.statusText || (order.rateStatus === 1 ? '商家已评价' : '尚未同步') }}
                      <button v-if="order.orderId && !ratingMap[order.orderId]?.synced" @click="showOrderRating(order)">同步双方评价</button>
                    </span>
                  </header>
                  <div class="rating-columns">
                    <section>
                      <h4>买家评价</h4>
                      <div v-for="(rate, index) in ratingMap[order.orderId || '']?.buyerRates || []" :key="index" class="rate-content">
                        <p>{{ rate.content || '买家未填写文字评价' }}</p><small>{{ formatTime(rate.createdTime) }}</small>
                      </div>
                      <p v-if="!(ratingMap[order.orderId || '']?.buyerRates?.length)" class="muted">尚未同步到买家评价</p>
                    </section>
                    <section>
                      <h4>商家评价</h4>
                      <div v-for="(rate, index) in ratingMap[order.orderId || '']?.sellerRates || []" :key="index" class="rate-content">
                        <p>{{ rate.content || '商家未填写文字评价' }}</p><small>{{ formatTime(rate.createdTime) }}</small>
                      </div>
                      <div v-if="!(ratingMap[order.orderId || '']?.sellerRates?.length) && order.rateContent" class="rate-content"><p>{{ order.rateContent }}</p><small>{{ formatTime(order.rateTime) }}</small></div>
                      <p v-else-if="!(ratingMap[order.orderId || '']?.sellerRates?.length)" class="muted">尚未评价</p>
                    </section>
                  </div>
                </article>
                <div v-if="detail.orders.length === 0" class="empty compact">暂无可关联评价的订单</div>
                <button v-if="selectedOrderId" class="clear-filter" @click="selectedOrderId = ''">查看全部订单评价</button>
              </section>
            </main>
          </template>
        </aside>
      </div>

      <div v-if="editing" class="overlay" @click.self="editing = undefined">
        <section class="dialog">
          <header>
            <div><h3>编辑买家资料</h3><p>{{ editing.buyerUserName || editing.buyerUserId }}</p></div>
            <button class="close" @click="editing = undefined">×</button>
          </header>
          <label>买家名称<input v-model="form.buyerUserName" maxlength="200" /></label>
          <label>标签<input v-model="form.tagsText" placeholder="例如：老客户，高意向；最多10个" /></label>
          <label>运营备注<textarea v-model="form.note" rows="3" maxlength="500"></textarea></label>
          <label class="switch-row">
            <span><strong>暂停自动化</strong><small>开启后不再自动回复，新订单进入人工复核。</small></span>
            <input v-model="form.automationBlocked" type="checkbox" />
          </label>
          <label v-if="form.automationBlocked">暂停原因<input v-model="form.blockedReason" maxlength="200" /></label>
          <footer><button @click="editing = undefined">取消</button><button class="primary" @click="save">保存</button></footer>
        </section>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.buyer-page { height: 100%; overflow: auto; padding: 16px; background: var(--color-background); box-sizing: border-box; color: #1d2939; }
.panel { background: #fff; border: 1px solid #eaecf0; border-radius: 10px; }.toolbar { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; padding: 18px; margin-bottom: 12px; }
h2, h3, h4, p { margin: 0; } h2 { margin-top: 2px; font-size: 19px; }.eyebrow { color: #155eef; font-size: 10px; font-weight: 700; letter-spacing: .08em; }.toolbar p { margin-top: 5px; color: #667085; font-size: 13px; }
.filters { display: flex; gap: 8px; flex-wrap: wrap; }input, select, textarea, button { font: inherit; } input, select, textarea { border: 1px solid #d0d5dd; border-radius: 6px; padding: 8px 10px; color: #344054; background: #fff; box-sizing: border-box; }.filters input { width: 230px; }
button { border: 1px solid #d0d5dd; border-radius: 6px; padding: 8px 14px; background: #fff; cursor: pointer; color: #344054; }button:disabled { opacity: .45; cursor: default; }.primary { border-color: #155eef; background: #155eef; color: #fff; }
.table-panel { min-height: 360px; }.table-wrap { overflow: auto; }table { width: 100%; border-collapse: collapse; min-width: 980px; }th, td { padding: 13px 14px; border-bottom: 1px solid #eaecf0; text-align: left; font-size: 13px; vertical-align: top; }th { color: #667085; font-weight: 500; background: #fcfcfd; }
.buyer-row { cursor: pointer; }.buyer-row:hover { background: #f9fafb; }.buyer-row:focus-visible { outline-offset: -2px; }td strong, td small { display: block; }td small { color: #98a2b3; margin-top: 4px; }.tag { display: inline-block; margin: 0 4px 4px 0; padding: 2px 7px; border-radius: 4px; background: #eef4ff; color: #155eef; font-size: 12px; }
.muted { color: #98a2b3; }.status { display: inline-block; padding: 2px 7px; border-radius: 10px; font-size: 12px; }.status.normal { color: #067647; background: #ecfdf3; }.status.blocked { color: #b42318; background: #fef3f2; }.link { padding: 0; border: 0; color: #155eef; }.link.secondary { margin-left: 10px; color: #475467; }
.empty { padding: 80px 20px; text-align: center; color: #98a2b3; }.empty.compact { padding: 40px 16px; }.pager { display: flex; justify-content: space-between; align-items: center; padding: 12px 14px; font-size: 13px; color: #667085; }.pager div { display: flex; align-items: center; gap: 10px; }
.detail-overlay { position: fixed; inset: 0; z-index: 2100; display: flex; justify-content: flex-end; background: rgba(16,24,40,.42); }.detail-drawer { display: flex; flex-direction: column; width: min(1180px, 92vw); height: 100%; background: #f7f8fa; box-shadow: -18px 0 50px rgba(16,24,40,.16); }
.detail-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 16px 20px; border-bottom: 1px solid #e4e7ec; background: #fff; }.buyer-identity { display: flex; align-items: center; gap: 12px; }.buyer-identity .avatar { display: grid; place-items: center; width: 42px; height: 42px; border-radius: 9px; background: #155eef; color: #fff; font-weight: 700; }.buyer-identity small, .buyer-identity p { color: #98a2b3; font-size: 11px; }.buyer-identity h3 { margin: 2px 0; font-size: 18px; }.detail-header-actions { display: flex; align-items: center; gap: 7px; }.close { padding: 2px 8px; border: 0; font-size: 23px; }
.detail-loading { padding: 100px 20px; text-align: center; color: #667085; }.profile-strip { display: grid; grid-template-columns: repeat(5, 1fr); margin: 12px 14px 0; border: 1px solid #e4e7ec; border-radius: 9px; background: #fff; }.profile-strip > div { padding: 13px 15px; border-right: 1px solid #eaecf0; }.profile-strip > div:last-child { border-right: 0; }.profile-strip span { display: block; color: #667085; font-size: 11px; }.profile-strip strong { display: block; margin-top: 5px; font-size: 18px; }.profile-strip .danger { color: #b42318; }
.profile-note { margin: 10px 14px 0; padding: 10px 13px; border: 1px solid #e4e7ec; border-radius: 8px; background: #fff; }.profile-note p { margin-top: 6px; color: #667085; font-size: 12px; }.detail-tabs { display: flex; gap: 2px; margin: 12px 14px 0; padding: 4px; border: 1px solid #e4e7ec; border-radius: 8px; background: #fff; }.detail-tabs button { flex: 1; border: 0; }.detail-tabs button.active { background: #eef4ff; color: #155eef; font-weight: 600; }
.detail-body { flex: 1; margin: 10px 14px 14px; overflow: auto; }.order-list, .rating-list { display: grid; gap: 8px; }.order-card, .conversation-view, .goods-grid article, .rating-card { border: 1px solid #e4e7ec; border-radius: 9px; background: #fff; }.order-main { display: grid; grid-template-columns: minmax(210px, 1.4fr) minmax(170px, 1fr) minmax(150px, .8fr) 130px 90px; align-items: center; gap: 14px; padding: 14px 16px; }.order-main span strong, .order-main span small { display: block; }.order-main small { color: #98a2b3; font-size: 11px; }.order-main strong { margin-top: 4px; font-size: 12px; font-weight: 500; }.order-title > strong { margin-top: 0; color: #101828; font-size: 14px; font-weight: 600; }
.order-state { width: fit-content; padding: 3px 8px; border-radius: 999px; color: #344054; background: #f2f4f7; font-size: 11px; }.order-state.已交付 { color: #067647; background: #ecfdf3; }.order-state.交付失败 { color: #b42318; background: #fef3f2; }.order-state.待人工复核 { color: #b54708; background: #fffaeb; }.order-links { display: flex; align-items: center; justify-content: flex-end; gap: 8px; padding: 8px 14px; border-top: 1px solid #f2f4f7; color: #667085; font-size: 12px; }.order-links button { padding: 5px 9px; font-size: 12px; }
.conversation-view { overflow: hidden; }.conversation-view > header { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 14px; border-bottom: 1px solid #eaecf0; }.conversation-view header strong, .conversation-view header small { display: block; }.conversation-view header small { margin-top: 3px; color: #98a2b3; font-size: 11px; }.conversation-view select { max-width: 420px; }.message-timeline { display: flex; flex-direction: column; gap: 9px; padding: 16px; }.message { width: min(72%, 680px); padding: 10px 12px; border: 1px solid #e4e7ec; border-radius: 8px; background: #f9fafb; }.message.seller { align-self: flex-end; border-color: #b2ccff; background: #eff4ff; }.message > div { display: flex; justify-content: space-between; gap: 12px; }.message strong { font-size: 12px; }.message time, .message small { color: #98a2b3; font-size: 10px; }.message p { margin: 6px 0; white-space: pre-wrap; word-break: break-word; font-size: 13px; line-height: 1.55; }
.goods-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 9px; }.goods-grid article { display: grid; grid-template-columns: 62px 1fr; gap: 12px; padding: 13px; }.goods-grid img, .goods-placeholder { grid-row: 1 / 3; width: 62px; height: 62px; border-radius: 7px; object-fit: cover; }.goods-placeholder { display: grid; place-items: center; background: #f2f4f7; color: #98a2b3; font-size: 12px; }.goods-grid strong, .goods-grid small { display: block; }.goods-grid small { margin-top: 4px; color: #98a2b3; font-size: 11px; }.goods-grid dl { grid-column: 2; display: flex; gap: 20px; margin: 0; }.goods-grid dl div { display: flex; gap: 5px; }.goods-grid dt { color: #98a2b3; font-size: 11px; }.goods-grid dd { margin: 0; font-size: 11px; }
.sync-tip { padding: 9px 12px; border: 1px solid #b2ccff; border-radius: 7px; background: #eff4ff; color: #155eef; font-size: 12px; }.rating-card { overflow: hidden; }.rating-card > header { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 11px 14px; border-bottom: 1px solid #eaecf0; }.rating-card header strong, .rating-card header small { display: block; }.rating-card header small, .rating-card header > span:last-child { margin-top: 3px; color: #667085; font-size: 11px; }.rating-status { display: flex; align-items: center; gap: 8px; }.rating-status button { padding: 4px 8px; color: #155eef; font-size: 11px; }.rating-columns { display: grid; grid-template-columns: 1fr 1fr; }.rating-columns > section { padding: 13px 14px; }.rating-columns > section + section { border-left: 1px solid #eaecf0; }.rating-columns h4 { margin-bottom: 9px; font-size: 12px; }.rate-content { padding: 9px 10px; border-radius: 6px; background: #f9fafb; }.rate-content + .rate-content { margin-top: 6px; }.rate-content p { font-size: 12px; line-height: 1.55; }.rate-content small { display: block; margin-top: 5px; color: #98a2b3; font-size: 10px; }.clear-filter { justify-self: start; }
.overlay { position: fixed; inset: 0; z-index: 2300; display: grid; place-items: center; padding: 20px; background: rgba(16,24,40,.45); }.dialog { width: min(520px, 100%); padding: 20px; border-radius: 12px; background: #fff; box-shadow: 0 20px 50px rgba(16,24,40,.2); }.dialog header, .dialog footer, .switch-row { display: flex; justify-content: space-between; align-items: center; gap: 12px; }.dialog header { margin-bottom: 18px; }.dialog header p, .dialog label, .switch-row small { color: #667085; font-size: 13px; }.dialog label { display: grid; gap: 6px; margin: 13px 0; }.dialog label input, .dialog label textarea { width: 100%; }.dialog .switch-row { display: flex; padding: 12px; border: 1px solid #eaecf0; border-radius: 8px; color: #344054; }.switch-row span, .switch-row small { display: block; }.switch-row input { width: auto; }.dialog footer { justify-content: flex-end; margin-top: 18px; }
@media (max-width: 1000px) { .toolbar { align-items: stretch; flex-direction: column; }.filters > * { flex: 1; min-width: 140px; }.filters input { width: auto; }.detail-drawer { width: 96vw; }.profile-strip { grid-template-columns: repeat(3, 1fr); }.profile-strip > div:nth-child(3) { border-right: 0; }.order-main { grid-template-columns: 1fr 1fr; }.goods-grid { grid-template-columns: 1fr; } }
@media (max-width: 700px) { .buyer-page { padding: 10px; }.detail-drawer { width: 100%; }.detail-header { padding: 12px; }.profile-strip { grid-template-columns: 1fr 1fr; }.profile-strip > div { border-bottom: 1px solid #eaecf0; }.detail-tabs { overflow-x: auto; }.detail-tabs button { min-width: 90px; }.order-main { grid-template-columns: 1fr; }.order-links { align-items: stretch; flex-direction: column; }.conversation-view > header { align-items: stretch; flex-direction: column; }.conversation-view select { max-width: none; }.message { width: 85%; }.rating-columns { grid-template-columns: 1fr; }.rating-columns > section + section { border-left: 0; border-top: 1px solid #eaecf0; } }
</style>
