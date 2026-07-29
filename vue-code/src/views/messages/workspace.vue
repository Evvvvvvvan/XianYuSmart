<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useMessageManager } from './useMessageManager'
import {
  getContextMessages,
  getConversationProfiles,
  sendMessage,
  syncContextMessages,
  type ChatMessage,
  type ConversationProfile
} from '@/api/message'
import { sendImageMessage } from '@/api/image'
import { getKeywordReplyRules } from '@/api/keywordReply'
import MultiImageUploader from '@/components/MultiImageUploader.vue'
import { showError, showSuccess, showWarning } from '@/utils'
import '@/styles/merchant-workbench.css'

const {
  loading,
  accounts,
  selectedAccountId,
  messageList,
  goodsList,
  getCurrentAccountUnb,
  loadAccounts,
  loadMessages,
  handleAccountChange,
  formatMessageTime
} = useMessageManager()

const selectedSid = ref('')
const searchText = ref('')
const profiles = ref<Record<string, ConversationProfile>>({})
const failedImages = ref(new Set<string>())
const contextMessages = ref<ChatMessage[]>([])
const contextLoading = ref(false)
const platformSyncing = ref(false)
const synchronizedSessions = ref(new Set<string>())
const messageText = ref('')
const imageUrls = ref('')
const showImageUploader = ref(false)
const sending = ref(false)
const refreshing = ref(false)
const quickReplies = ref<string[]>([])
const messagesRef = ref<HTMLElement>()

const normalizeImageUrl = (value?: string) => {
  if (!value) return ''
  if (value.startsWith('//')) return `https:${value}`
  return value.startsWith('http://') ? `https://${value.slice(7)}` : value
}

const markImageError = (url?: string) => {
  if (url) failedImages.value = new Set([...failedImages.value, url])
}

const imageAvailable = (url?: string) => Boolean(url && !failedImages.value.has(url))
const isImageMessage = (message: ChatMessage) => [2, 887, 997].includes(message.contentType)
const isSystemMessage = (message: ChatMessage) => ![1, 2, 887, 888, 997, 999].includes(message.contentType)

const conversations = computed(() => {
  const groups = new Map<string, ChatMessage[]>()
  for (const message of messageList.value) {
    const sid = message.sid || `message-${message.id}`
    groups.set(sid, [...(groups.get(sid) || []), message])
  }
  const currentUserId = getCurrentAccountUnb.value
  return Array.from(groups.entries()).map(([sid, messages]) => {
    const ordered = [...messages].sort((a, b) => Number(b.messageTime) - Number(a.messageTime))
    const latest = ordered[0]!
    const buyer = ordered.find(message => message.senderUserId !== currentUserId) || latest
    const goods = goodsList.value.find(item => item.item.xyGoodId === latest.xyGoodsId)
    const profile = profiles.value[sid]
    return {
      sid,
      messages: ordered,
      buyerName: profile?.nick || buyer.senderUserName || '买家',
      buyerId: buyer.senderUserId || '',
      buyerAvatar: normalizeImageUrl(profile?.avatar),
      latest,
      goods,
      goodsTitle: goods?.item.title || latest.xyGoodsId || '未关联商品',
      goodsCover: normalizeImageUrl(goods?.item.coverPic || '')
    }
  }).filter(item => {
    const keyword = searchText.value.trim().toLowerCase()
    return !keyword || `${item.buyerName} ${item.goodsTitle} ${item.latest.msgContent}`.toLowerCase().includes(keyword)
  }).sort((a, b) => Number(b.latest.messageTime) - Number(a.latest.messageTime))
})

const selected = computed(() => conversations.value.find(item => item.sid === selectedSid.value) || conversations.value[0])
const orderedContext = computed(() => [...contextMessages.value].reverse())
const incomingCount = computed(() => messageList.value.filter(message => message.senderUserId !== getCurrentAccountUnb.value).length)

const scrollToBottom = () => nextTick(() => {
  if (messagesRef.value) messagesRef.value.scrollTop = messagesRef.value.scrollHeight
})

const loadConversationContext = async (syncPlatform = false, showLoading = true) => {
  if (!selectedAccountId.value || !selected.value) {
    contextMessages.value = []
    return
  }
  const accountId = selectedAccountId.value
  const sid = selected.value.sid
  const displayLoading = showLoading && contextMessages.value.length === 0
  if (displayLoading) contextLoading.value = true
  try {
    const response = await getContextMessages({ xianyuAccountId: accountId, sid, limit: 500, offset: 0 })
    if (selectedAccountId.value === accountId && selected.value?.sid === sid) {
      contextMessages.value = response.data || []
      await scrollToBottom()
    }
  } catch (error: any) {
    if (showLoading && !error?.messageShown) showWarning(error?.message || '本地会话读取失败')
    if (!contextMessages.value.length) contextMessages.value = selected.value?.messages || []
  } finally {
    if (displayLoading) contextLoading.value = false
  }
  if (!syncPlatform) return
  platformSyncing.value = true
  try {
    await syncContextMessages({ xianyuAccountId: accountId, sid, maxMessages: 500 })
    synchronizedSessions.value = new Set([...synchronizedSessions.value, `${accountId}:${sid}`])
    const response = await getContextMessages({ xianyuAccountId: accountId, sid, limit: 500, offset: 0 })
    if (selectedAccountId.value === accountId && selected.value?.sid === sid) {
      contextMessages.value = response.data || contextMessages.value
      await scrollToBottom()
    }
  } catch (error: any) {
    // 平台同步失败时继续显示本地消息，避免一次超时让整个会话区域变空。
    if (!error?.messageShown) showWarning(error?.message || '平台历史同步失败，已保留本地会话记录')
  } finally {
    platformSyncing.value = false
  }
}

const loadQuickReplies = async () => {
  quickReplies.value = []
  if (!selectedAccountId.value || !selected.value?.latest.xyGoodsId) return
  try {
    const response = await getKeywordReplyRules({
      xianyuAccountId: selectedAccountId.value,
      xyGoodsId: selected.value.latest.xyGoodsId
    })
    quickReplies.value = [...new Set((response.data || [])
      .flatMap(rule => rule.contents || [])
      .map(content => content.replyText?.trim())
      .filter(Boolean))].slice(0, 8) as string[]
  } catch {
    quickReplies.value = []
  }
}

const sendCurrentMessage = async () => {
  if (!selectedAccountId.value || !selected.value) return
  const text = messageText.value.trim()
  const images = imageUrls.value.split(',').map(value => value.trim()).filter(Boolean)
  if (!text && !images.length) return showWarning('请输入消息或上传图片')
  sending.value = true
  try {
    const cid = selected.value.sid.replace('@goofish', '')
    const toId = selected.value.buyerId.replace('@goofish', '')
    for (const imageUrl of images) {
      await sendImageMessage({
        xianyuAccountId: selectedAccountId.value,
        cid,
        toId,
        imageUrl,
        width: 800,
        height: 800,
        xyGoodsId: selected.value.latest.xyGoodsId
      })
    }
    if (text) {
      await sendMessage({
        xianyuAccountId: selectedAccountId.value,
        cid,
        toId,
        text,
        xyGoodsId: selected.value.latest.xyGoodsId
      })
    }
    messageText.value = ''
    imageUrls.value = ''
    showImageUploader.value = false
    await loadConversationContext(false, false)
    showSuccess('消息发送成功')
  } catch (error: any) {
    showError(error?.message || '消息发送失败')
  } finally {
    sending.value = false
  }
}

const refresh = async () => {
  if (refreshing.value || platformSyncing.value) return
  refreshing.value = true
  try {
    const previousMessageId = selected.value?.latest.id
    await loadMessages(true)
    if (selected.value && selected.value.latest.id !== previousMessageId) {
      // 仅在会话出现新消息时更新正文，避免轮询造成滚动位置跳动。
      await loadConversationContext(false, false)
    }
  } finally {
    refreshing.value = false
  }
}

watch(conversations, value => {
  if (!value.length) selectedSid.value = ''
  else if (!value.some(item => item.sid === selectedSid.value)) selectedSid.value = value[0]!.sid
}, { immediate: true })

watch(selectedAccountId, () => {
  profiles.value = {}
  failedImages.value = new Set()
  synchronizedSessions.value = new Set()
})

watch([selectedAccountId, messageList], async () => {
  if (!selectedAccountId.value) return
  const accountId = selectedAccountId.value
  const sessionIds = [...new Set(messageList.value.map(message => message.sid).filter(Boolean))]
    .filter(sid => !profiles.value[sid])
  for (let index = 0; index < sessionIds.length; index += 20) {
    const response = await getConversationProfiles({
      xianyuAccountId: accountId,
      sessionIds: sessionIds.slice(index, index + 20)
    })
    if (selectedAccountId.value !== accountId) return
    for (const profile of response.data || []) profiles.value[profile.sid] = profile
  }
}, { deep: false })

watch(() => [selectedAccountId.value, selected.value?.sid], async ([accountId, sid]) => {
  contextMessages.value = []
  await loadQuickReplies()
  if (!accountId || !sid) return
  const key = `${accountId}:${sid}`
  await loadConversationContext(!synchronizedSessions.value.has(key))
}, { immediate: true })

let timer: ReturnType<typeof setInterval> | undefined
onMounted(async () => {
  await loadAccounts()
  timer = setInterval(refresh, 10000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <section class="workbench chat">
    <header class="workbench__header">
      <div><h1>在线消息</h1><p>一个入口查看完整历史、关联商品与自动回复状态，并直接处理会话。</p></div>
      <div class="workbench__actions">
        <select v-model="selectedAccountId" class="workbench__select chat__account" @change="handleAccountChange">
          <option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.accountNote || account.unb }}</option>
        </select>
        <button class="workbench__btn" :disabled="loading || platformSyncing || refreshing" @click="refresh">
          {{ loading || platformSyncing || refreshing ? '同步中' : '同步消息' }}
        </button>
      </div>
    </header>

    <div class="chat__layout">
      <aside class="workbench__card chat__conversations">
        <div class="chat__summary">
          <strong>在线消息 <span>{{ conversations.length }}</span></strong>
          <div><span>全部 {{ conversations.length }}</span><span>买家消息 {{ incomingCount }}</span></div>
          <input v-model="searchText" class="workbench__input" placeholder="搜索联系人、商品或关键词">
        </div>
        <button
          v-for="conversation in conversations"
          :key="conversation.sid"
          class="chat__conversation"
          :class="{ 'chat__conversation--active': selected?.sid === conversation.sid }"
          @click="selectedSid = conversation.sid"
        >
          <img v-if="imageAvailable(conversation.buyerAvatar)" class="chat__avatar chat__avatar--image" :src="conversation.buyerAvatar" alt="" @error="markImageError(conversation.buyerAvatar)">
          <div v-else class="chat__avatar">{{ conversation.buyerName.slice(0, 1) }}</div>
          <div class="chat__conversation-copy">
            <strong>{{ conversation.buyerName }}</strong>
            <span>{{ conversation.goodsTitle }}</span>
            <p>{{ isImageMessage(conversation.latest) ? '[图片]' : conversation.latest.msgContent }}</p>
          </div>
          <time>{{ formatMessageTime(conversation.latest.messageTime) }}</time>
        </button>
        <div v-if="!conversations.length" class="workbench__empty">暂无会话</div>
      </aside>

      <main class="workbench__card chat__main">
        <template v-if="selected">
          <header class="chat__main-header">
            <img v-if="imageAvailable(selected.buyerAvatar)" class="chat__avatar chat__avatar--image" :src="selected.buyerAvatar" alt="" @error="markImageError(selected.buyerAvatar)">
            <div v-else class="chat__avatar">{{ selected.buyerName.slice(0, 1) }}</div>
            <div><strong>{{ selected.buyerName }}</strong><span>{{ selected.goodsTitle }}</span></div>
            <button class="workbench__btn" :disabled="platformSyncing" @click="loadConversationContext(true)">
              {{ platformSyncing ? '同步历史中' : '同步完整历史' }}
            </button>
          </header>

          <div ref="messagesRef" class="chat__messages">
            <div v-if="contextLoading" class="chat__loading">正在读取完整会话…</div>
            <template v-for="message in orderedContext" :key="message.id">
              <article v-if="isSystemMessage(message)" class="chat__system">{{ message.msgContent }}</article>
              <article v-else class="chat__message" :class="{ 'chat__message--mine': message.senderUserId === getCurrentAccountUnb }">
                <span>{{ message.senderUserId === getCurrentAccountUnb ? '商家' : (message.senderUserName || selected.buyerName) }}</span>
                <img v-if="isImageMessage(message) && imageAvailable(normalizeImageUrl(message.msgContent))" class="chat__message-image" :src="normalizeImageUrl(message.msgContent)" alt="会话图片" @error="markImageError(normalizeImageUrl(message.msgContent))">
                <p v-else>{{ message.msgContent }}</p>
                <time>{{ formatMessageTime(message.messageTime) }}</time>
              </article>
            </template>
            <div v-if="!contextLoading && !orderedContext.length" class="workbench__empty">暂无历史消息</div>
          </div>

          <footer class="chat__composer">
            <div v-if="quickReplies.length" class="chat__quick">
              <button v-for="reply in quickReplies" :key="reply" @click="messageText = reply">{{ reply }}</button>
            </div>
            <MultiImageUploader v-if="showImageUploader && selectedAccountId" v-model="imageUrls" :account-id="selectedAccountId" :max="5" />
            <textarea v-model="messageText" class="workbench__textarea" rows="3" placeholder="输入消息，Ctrl + Enter 发送" @keydown.ctrl.enter.prevent="sendCurrentMessage"></textarea>
            <div class="chat__composer-actions">
              <button class="workbench__btn" @click="showImageUploader = !showImageUploader">发送图片</button>
              <button class="workbench__btn workbench__btn--primary" :disabled="sending" @click="sendCurrentMessage">{{ sending ? '发送中' : '发送' }}</button>
            </div>
          </footer>
        </template>
        <div v-else class="workbench__empty">选择会话后查看内容</div>
      </main>

      <aside class="workbench__card chat__context">
        <template v-if="selected">
          <section>
            <h2>相关商品</h2>
            <img v-if="imageAvailable(selected.goodsCover)" class="chat__goods-cover" :src="selected.goodsCover" alt="" @error="markImageError(selected.goodsCover)">
            <div v-else class="chat__cover-empty">暂无商品图片</div>
            <strong>{{ selected.goodsTitle }}</strong>
            <p v-if="selected.goods">¥ {{ selected.goods.item.soldPrice || '--' }}</p>
          </section>
          <section>
            <h2>自动回复状态</h2>
            <div class="chat__status">
              <span :class="{ 'chat__status--on': selected.goods?.xianyuAutoReplyOn === 1 }">
                {{ selected.goods?.xianyuAutoReplyOn === 1 ? '已开启' : '未开启' }}
              </span>
              <small>关键词回复 {{ selected.goods?.xianyuKeywordReplyOn === 1 ? '已开启' : '未开启' }}</small>
            </div>
            <router-link class="workbench__btn" to="/auto-reply">配置自动回复</router-link>
          </section>
          <section>
            <h2>关联信息</h2>
            <dl>
              <dt>买家</dt><dd>{{ selected.buyerName }}</dd>
              <dt>买家 ID</dt><dd>{{ selected.buyerId }}</dd>
              <dt>商品 ID</dt><dd>{{ selected.latest.xyGoodsId || '-' }}</dd>
              <dt>历史消息</dt><dd>{{ contextMessages.length }} 条</dd>
            </dl>
            <router-link class="workbench__btn" :to="{ path: '/buyers', query: { buyerId: selected.buyerId } }">查看买家档案</router-link>
            <router-link class="workbench__btn" :to="{ path: '/orders', query: { buyerId: selected.buyerId } }">查看关联订单</router-link>
          </section>
        </template>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.chat { height: 100%; overflow: hidden; }
.chat__account { width: 180px; }
.chat__layout { display: grid; height: calc(100% - 68px); min-height: 560px; grid-template-columns: 300px minmax(420px, 1fr) 280px; gap: 12px; }
.chat__conversations, .chat__main, .chat__context { min-height: 0; overflow: hidden; padding: 0; }
.chat__conversations { overflow-y: auto; }
.chat__summary { position: sticky; top: 0; z-index: 2; padding: 14px; border-bottom: 1px solid #eaecf0; background: #fff; }
.chat__summary > strong { display: flex; justify-content: space-between; margin-bottom: 8px; }
.chat__summary > div { display: flex; gap: 12px; margin-bottom: 10px; color: #667085; font-size: 12px; }
.chat__conversation { display: grid; width: 100%; grid-template-columns: auto minmax(0, 1fr) auto; align-items: start; gap: 9px; padding: 12px; border: 0; border-bottom: 1px solid #f2f4f7; color: #344054; background: #fff; text-align: left; cursor: pointer; }
.chat__conversation--active { background: #f0f5ff; }
.chat__conversation-copy { min-width: 0; }
.chat__conversation-copy strong, .chat__conversation-copy span, .chat__conversation-copy p { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chat__conversation-copy span, .chat__conversation-copy p, .chat__conversation time { color: #667085; font-size: 11px; }
.chat__conversation-copy p { margin: 5px 0 0; }
.chat__avatar { display: grid; width: 38px; height: 38px; flex: 0 0 38px; place-items: center; border-radius: 50%; color: #155eef; background: #eaf0ff; font-weight: 700; }
.chat__avatar--image { display: block; object-fit: cover; }
.chat__main { display: flex; flex-direction: column; }
.chat__main-header { display: flex; align-items: center; gap: 10px; padding: 12px 14px; border-bottom: 1px solid #eaecf0; }
.chat__main-header > div:nth-child(2) { display: flex; min-width: 0; flex: 1; flex-direction: column; }
.chat__main-header span { overflow: hidden; color: #667085; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.chat__messages { display: flex; flex: 1; overflow-y: auto; flex-direction: column; gap: 10px; padding: 18px; }
.chat__loading, .chat__system { align-self: center; padding: 5px 10px; border-radius: 12px; color: #667085; background: #f2f4f7; font-size: 11px; }
.chat__message { max-width: 72%; align-self: flex-start; }
.chat__message span, .chat__message time { display: block; color: #98a2b3; font-size: 11px; }
.chat__message p { margin: 4px 0; padding: 9px 12px; border-radius: 4px 10px 10px; background: #f2f4f7; line-height: 1.6; white-space: pre-wrap; }
.chat__message-image { display: block; max-width: 280px; max-height: 320px; margin: 4px 0; border-radius: 10px; object-fit: contain; }
.chat__message--mine { align-self: flex-end; text-align: right; }
.chat__message--mine p { border-radius: 10px 4px 10px 10px; color: #fff; background: #155eef; text-align: left; }
.chat__composer { padding: 10px 14px 12px; border-top: 1px solid #eaecf0; }
.chat__composer .workbench__textarea { min-height: 70px; resize: vertical; }
.chat__quick { display: flex; overflow-x: auto; gap: 6px; margin-bottom: 8px; }
.chat__quick button { max-width: 180px; flex: 0 0 auto; overflow: hidden; padding: 5px 9px; border: 1px solid #d0d5dd; border-radius: 14px; background: #fff; text-overflow: ellipsis; white-space: nowrap; cursor: pointer; }
.chat__composer-actions { display: flex; justify-content: space-between; margin-top: 8px; }
.chat__context { overflow-y: auto; }
.chat__context section { display: flex; flex-direction: column; gap: 9px; padding: 14px; border-bottom: 1px solid #eaecf0; }
.chat__context h2 { margin: 0; font-size: 15px; }
.chat__goods-cover, .chat__cover-empty { width: 100%; aspect-ratio: 4 / 3; border-radius: 7px; object-fit: cover; background: #f2f4f7; }
.chat__cover-empty { display: grid; place-items: center; color: #98a2b3; font-size: 12px; }
.chat__status { display: flex; align-items: center; justify-content: space-between; }
.chat__status span { padding: 3px 8px; border-radius: 12px; color: #b42318; background: #fee4e2; font-size: 12px; }
.chat__status span.chat__status--on { color: #067647; background: #dcfae6; }
.chat__status small { color: #667085; }
.chat__context dl { display: grid; grid-template-columns: 70px minmax(0, 1fr); gap: 8px; margin: 0; font-size: 12px; }
.chat__context dt { color: #667085; }
.chat__context dd { margin: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 1180px) { .chat__layout { grid-template-columns: 280px minmax(0, 1fr); } .chat__context { display: none; } }
@media (max-width: 767px) {
  .chat { height: auto; overflow: visible; }
  .chat__layout { display: block; height: auto; min-height: 0; }
  .chat__conversations { max-height: 42vh; }
  .chat__main { min-height: 58vh; margin-top: 10px; margin-bottom: max(12px, env(safe-area-inset-bottom)); }
  .chat__message { max-width: 88%; }
  .chat__main-header .workbench__btn { padding: 6px 8px; font-size: 11px; }
}
</style>
