<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useMessageManager } from './useMessageManager'
import ContextDialog from './components/ContextDialog.vue'
import type { ChatMessage } from '@/api/message'
import '@/styles/merchant-workbench.css'

const {
  loading,
  accounts,
  selectedAccountId,
  messageList,
  goodsList,
  filterCurrentAccount,
  getCurrentAccountUnb,
  loadAccounts,
  loadMessages,
  loadGoodsList,
  handleAccountChange,
  formatMessageTime
} = useMessageManager()

const selectedSid = ref('')
const searchText = ref('')
const contextVisible = ref(false)

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
    return {
      sid,
      messages: ordered,
      buyerName: buyer?.senderUserName || '买家',
      buyerId: buyer?.senderUserId || '',
      latest,
      goodsTitle: goods?.item.title || latest.xyGoodsId || '未关联商品',
      goodsCover: goods?.item.coverPic || ''
    }
  }).filter(item => {
    const keyword = searchText.value.trim().toLowerCase()
    return !keyword || `${item.buyerName} ${item.goodsTitle} ${item.latest.msgContent}`.toLowerCase().includes(keyword)
  }).sort((a, b) => Number(b.latest.messageTime) - Number(a.latest.messageTime))
})

const selected = computed(() => conversations.value.find(item => item.sid === selectedSid.value) || conversations.value[0])

watch(conversations, value => {
  if (!value.length) selectedSid.value = ''
  else if (!value.some(item => item.sid === selectedSid.value)) selectedSid.value = value[0]!.sid
}, { immediate: true })

const refresh = () => loadMessages(true)
let timer: ReturnType<typeof setInterval> | undefined

onMounted(async () => {
  await loadAccounts()
  await Promise.all([loadGoodsList(), loadMessages()])
  timer = setInterval(refresh, 3000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <section class="workbench chat">
    <header class="workbench__header">
      <div><h1>在线消息</h1><p>按会话聚合买家、商品和上下文，减少在消息记录之间来回查找。</p></div>
      <div class="workbench__actions">
        <select v-model="selectedAccountId" class="workbench__select chat__account" @change="handleAccountChange">
          <option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.accountNote || account.unb }}</option>
        </select>
        <button class="workbench__btn" :disabled="loading" @click="loadMessages()">{{ loading ? '同步中' : '同步消息' }}</button>
      </div>
    </header>

    <div class="chat__layout">
      <aside class="workbench__card chat__conversations">
        <div class="chat__search">
          <input v-model="searchText" class="workbench__input" placeholder="搜索买家、商品或消息">
          <label><input v-model="filterCurrentAccount" type="checkbox" @change="loadMessages()"> 只看买家消息</label>
        </div>
        <button v-for="conversation in conversations" :key="conversation.sid" class="chat__conversation" :class="{ 'chat__conversation--active': selected?.sid === conversation.sid }" @click="selectedSid = conversation.sid">
          <div class="chat__avatar">{{ conversation.buyerName.slice(0, 1) }}</div>
          <div>
            <strong>{{ conversation.buyerName }}</strong>
            <span>{{ conversation.goodsTitle }}</span>
            <p>{{ conversation.latest.msgContent }}</p>
          </div>
          <time>{{ formatMessageTime(conversation.latest.messageTime) }}</time>
        </button>
        <div v-if="!conversations.length" class="workbench__empty">暂无会话</div>
      </aside>

      <main class="workbench__card chat__main">
        <template v-if="selected">
          <header class="chat__main-header">
            <div class="chat__avatar">{{ selected.buyerName.slice(0, 1) }}</div>
            <div><strong>{{ selected.buyerName }}</strong><span>{{ selected.goodsTitle }}</span></div>
            <button class="workbench__btn workbench__btn--primary" @click="contextVisible = true">进入会话</button>
          </header>
          <div class="chat__messages">
            <article v-for="message in [...selected.messages].reverse()" :key="message.id" class="chat__message" :class="{ 'chat__message--mine': message.senderUserId === getCurrentAccountUnb }">
              <span>{{ message.senderUserName }}</span>
              <p>{{ message.msgContent }}</p>
              <time>{{ formatMessageTime(message.messageTime) }}</time>
            </article>
          </div>
          <footer class="chat__hint">进入会话后可查看完整上下文、发送文本或图片。</footer>
        </template>
        <div v-else class="workbench__empty">选择会话后查看内容</div>
      </main>

      <aside class="workbench__card chat__context">
        <h2>关联信息</h2>
        <template v-if="selected">
          <img :src="selected.goodsCover" alt="">
          <strong>{{ selected.goodsTitle }}</strong>
          <dl>
            <dt>买家</dt><dd>{{ selected.buyerName }}</dd>
            <dt>买家 ID</dt><dd>{{ selected.buyerId }}</dd>
            <dt>商品 ID</dt><dd>{{ selected.latest.xyGoodsId || '-' }}</dd>
            <dt>消息数</dt><dd>{{ selected.messages.length }}</dd>
          </dl>
          <router-link class="workbench__btn" to="/buyers">查看买家档案</router-link>
          <router-link class="workbench__btn" to="/orders">查看关联订单</router-link>
        </template>
      </aside>
    </div>

    <ContextDialog
      v-if="selected"
      v-model:visible="contextVisible"
      :sid="selected.sid"
      :goods-name="selected.goodsTitle"
      :xianyu-account-id="selectedAccountId || undefined"
      :sender-user-id="selected.buyerId"
      :xy-goods-id="selected.latest.xyGoodsId"
      :current-account-unb="getCurrentAccountUnb"
    />
  </section>
</template>

<style scoped>
.chat { height: 100%; overflow: hidden; }
.chat__account { width: 180px; }
.chat__layout { display: grid; height: calc(100% - 68px); min-height: 500px; grid-template-columns: 310px minmax(0, 1fr) 250px; gap: 12px; }
.chat__conversations, .chat__main, .chat__context { min-height: 0; overflow: hidden; padding: 0; }
.chat__conversations { overflow-y: auto; }
.chat__search { position: sticky; top: 0; z-index: 2; padding: 12px; border-bottom: 1px solid #eaecf0; background: #fff; }
.chat__search label { display: block; margin-top: 8px; color: #667085; font-size: 12px; }
.chat__conversation { display: grid; width: 100%; grid-template-columns: auto minmax(0, 1fr) auto; align-items: start; gap: 9px; padding: 12px; border: 0; border-bottom: 1px solid #f2f4f7; color: #344054; background: #fff; text-align: left; cursor: pointer; }
.chat__conversation--active { background: #f0f5ff; }
.chat__conversation div:nth-child(2) { min-width: 0; }
.chat__conversation strong, .chat__conversation span, .chat__conversation p { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chat__conversation span, .chat__conversation p, .chat__conversation time { color: #667085; font-size: 11px; }
.chat__conversation p { margin: 5px 0 0; }
.chat__avatar { display: grid; width: 38px; height: 38px; flex: 0 0 38px; place-items: center; border-radius: 50%; color: #155eef; background: #eaf0ff; font-weight: 700; }
.chat__main { display: flex; flex-direction: column; }
.chat__main-header { display: flex; align-items: center; gap: 10px; padding: 12px 14px; border-bottom: 1px solid #eaecf0; }
.chat__main-header div:nth-child(2) { display: flex; min-width: 0; flex: 1; flex-direction: column; }
.chat__main-header span { overflow: hidden; color: #667085; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.chat__messages { display: flex; flex: 1; overflow-y: auto; flex-direction: column; gap: 10px; padding: 18px; }
.chat__message { max-width: 72%; align-self: flex-start; }
.chat__message span, .chat__message time { color: #98a2b3; font-size: 11px; }
.chat__message p { margin: 4px 0; padding: 9px 12px; border-radius: 4px 10px 10px; background: #f2f4f7; line-height: 1.6; white-space: pre-wrap; }
.chat__message--mine { align-self: flex-end; text-align: right; }
.chat__message--mine p { border-radius: 10px 4px 10px 10px; color: #fff; background: #155eef; text-align: left; }
.chat__hint { padding: 10px; border-top: 1px solid #eaecf0; color: #667085; font-size: 12px; text-align: center; }
.chat__context { display: flex; flex-direction: column; gap: 10px; padding: 14px; }
.chat__context h2 { margin: 0; font-size: 16px; }
.chat__context img { width: 100%; aspect-ratio: 4 / 3; border-radius: 7px; object-fit: cover; background: #f2f4f7; }
.chat__context dl { display: grid; grid-template-columns: 60px minmax(0, 1fr); gap: 8px; margin: 0; font-size: 12px; }
.chat__context dt { color: #667085; }
.chat__context dd { margin: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 1100px) { .chat__layout { grid-template-columns: 280px minmax(0, 1fr); } .chat__context { display: none; } }
@media (max-width: 767px) { .chat { height: auto; overflow: visible; } .chat__layout { display: block; height: auto; min-height: 0; } .chat__conversations { max-height: 45vh; } .chat__main { min-height: 55vh; margin-top: 10px; } .chat__message { max-width: 88%; } }
</style>
