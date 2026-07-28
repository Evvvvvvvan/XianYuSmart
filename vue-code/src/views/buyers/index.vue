<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getAccountList } from '@/api/account'
import { getBuyerProfiles, saveBuyerProfile, type BuyerProfile } from '@/api/buyer'
import type { Account } from '@/types'
import { toast } from '@/utils/toast'

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
const form = ref({
  buyerUserName: '',
  tagsText: '',
  note: '',
  automationBlocked: false,
  blockedReason: ''
})

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

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
  await saveBuyerProfile({
    xianyuAccountId: editing.value.xianyuAccountId,
    buyerUserId: editing.value.buyerUserId,
    buyerUserName: form.value.buyerUserName,
    tags: form.value.tagsText.split(/[,，]/).map(value => value.trim()).filter(Boolean),
    note: form.value.note,
    automationBlocked: form.value.automationBlocked,
    blockedReason: form.value.blockedReason
  })
  toast.success('买家资料已保存')
  editing.value = undefined
  loadProfiles()
}

const changePage = (offset: number) => {
  pageNum.value = Math.min(totalPages.value, Math.max(1, pageNum.value + offset))
  loadProfiles()
}

onMounted(async () => {
  await loadAccounts()
  await loadProfiles()
})
</script>

<template>
  <div class="buyer-page">
    <section class="panel toolbar">
      <div>
        <h2>买家管理</h2>
        <p>沉淀交易与咨询记录，按标签识别买家，并对风险买家暂停自动回复和自动发货。</p>
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
            <tr v-for="profile in profiles" :key="profile.id">
              <td>
                <strong>{{ profile.buyerUserName || '未命名买家' }}</strong>
                <small>{{ profile.buyerUserId }}</small>
              </td>
              <td>
                <span v-for="tag in profile.tags" :key="tag" class="tag">{{ tag }}</span>
                <span v-if="!profile.tags?.length" class="muted">未设置</span>
              </td>
              <td>{{ profile.messageCount || 0 }} / {{ profile.orderCount || 0 }}</td>
              <td>¥{{ profile.totalAmount || '0.00' }}</td>
              <td>
                <span :class="profile.automationBlocked ? 'status blocked' : 'status normal'">
                  {{ profile.automationBlocked ? '已暂停' : '正常' }}
                </span>
                <small v-if="profile.blockedReason">{{ profile.blockedReason }}</small>
              </td>
              <td>{{ profile.lastInteractionTime || '-' }}</td>
              <td><button class="link" @click="openEdit(profile)">编辑</button></td>
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
      <div v-if="editing" class="overlay" @click.self="editing = undefined">
        <section class="dialog">
          <header>
            <div>
              <h3>编辑买家资料</h3>
              <p>{{ editing.buyerUserName || editing.buyerUserId }}</p>
            </div>
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
          <footer>
            <button @click="editing = undefined">取消</button>
            <button class="primary" @click="save">保存</button>
          </footer>
        </section>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.buyer-page { height: 100%; overflow: auto; padding: 16px; background: #f7f8fa; box-sizing: border-box; color: #1d2939; }
.panel { background: #fff; border: 1px solid #eaecf0; border-radius: 10px; }
.toolbar { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; padding: 18px; margin-bottom: 12px; }
h2, h3, p { margin: 0; } h2 { font-size: 18px; } .toolbar p { margin-top: 5px; color: #667085; font-size: 13px; }
.filters { display: flex; gap: 8px; flex-wrap: wrap; }
input, select, textarea, button { font: inherit; } input, select, textarea { border: 1px solid #d0d5dd; border-radius: 6px; padding: 8px 10px; color: #344054; background: #fff; box-sizing: border-box; }
.filters input { width: 230px; } button { border: 1px solid #d0d5dd; border-radius: 6px; padding: 8px 14px; background: #fff; cursor: pointer; color: #344054; }
button:disabled { opacity: .45; cursor: default; } .primary { border-color: #155eef; background: #155eef; color: #fff; }
.table-panel { min-height: 360px; } .table-wrap { overflow: auto; } table { width: 100%; border-collapse: collapse; min-width: 900px; }
th, td { padding: 13px 14px; border-bottom: 1px solid #eaecf0; text-align: left; font-size: 13px; vertical-align: top; }
th { color: #667085; font-weight: 500; background: #fcfcfd; } td strong, td small { display: block; } td small { color: #98a2b3; margin-top: 4px; }
.tag { display: inline-block; margin: 0 4px 4px 0; padding: 2px 7px; border-radius: 4px; background: #eef4ff; color: #155eef; font-size: 12px; }
.muted { color: #98a2b3; } .status { display: inline-block; padding: 2px 7px; border-radius: 10px; font-size: 12px; }
.status.normal { color: #067647; background: #ecfdf3; } .status.blocked { color: #b42318; background: #fef3f2; }
.link { padding: 0; border: 0; color: #155eef; } .empty { padding: 80px 20px; text-align: center; color: #98a2b3; }
.pager { display: flex; justify-content: space-between; align-items: center; padding: 12px 14px; font-size: 13px; color: #667085; }
.pager div { display: flex; align-items: center; gap: 10px; }
.overlay { position: fixed; inset: 0; z-index: 2000; display: grid; place-items: center; padding: 20px; background: rgba(16,24,40,.45); }
.dialog { width: min(520px, 100%); padding: 20px; border-radius: 12px; background: #fff; box-shadow: 0 20px 50px rgba(16,24,40,.2); }
.dialog header, .dialog footer, .switch-row { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.dialog header { margin-bottom: 18px; } .dialog header p, .dialog label, .switch-row small { color: #667085; font-size: 13px; }
.dialog label { display: grid; gap: 6px; margin: 13px 0; } .dialog label input, .dialog label textarea { width: 100%; }
.dialog .switch-row { display: flex; padding: 12px; border: 1px solid #eaecf0; border-radius: 8px; color: #344054; }
.switch-row span, .switch-row small { display: block; } .switch-row input { width: auto; }
.dialog footer { justify-content: flex-end; margin-top: 18px; } .close { border: 0; padding: 3px 8px; font-size: 22px; }
@media (max-width: 900px) { .toolbar { align-items: stretch; flex-direction: column; } .filters > * { flex: 1; min-width: 140px; } .filters input { width: auto; } }
</style>
