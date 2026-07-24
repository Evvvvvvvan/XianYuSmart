<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getAccountList } from '@/api/account'
import {
  deleteFixedDeliveryTemplate,
  getFixedDeliveryTemplates,
  saveFixedDeliveryTemplate,
  type FixedDeliveryTemplate
} from '@/api/fixed-delivery-template'
import type { Account } from '@/types'
import { showConfirm, showError, showSuccess } from '@/utils'

const route = useRoute()
const accounts = ref<Account[]>([])
const selectedAccountId = ref<number | null>(null)
const templates = ref<FixedDeliveryTemplate[]>([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const form = ref({
  id: undefined as number | undefined,
  templateName: '',
  deliveryContent: '',
  messageTemplate: '您好，{buyerName}，订单 {orderId} 已发货：\n{deliveryContent}'
})

const preview = computed(() => form.value.messageTemplate
  .split('{buyerName}').join('示例会员')
  .split('{orderId}').join('202607240001')
  .split('{deliveryContent}').join(form.value.deliveryContent || '这里显示固定发货内容'))

const loadTemplates = async () => {
  if (!selectedAccountId.value) return
  loading.value = true
  try {
    const response = await getFixedDeliveryTemplates(selectedAccountId.value)
    templates.value = response.code === 200 ? (response.data || []) : []
  } finally {
    loading.value = false
  }
}

const openCreate = () => {
  form.value = {
    id: undefined,
    templateName: '',
    deliveryContent: '',
    messageTemplate: '您好，{buyerName}，订单 {orderId} 已发货：\n{deliveryContent}'
  }
  dialogVisible.value = true
}

const openEdit = (template: FixedDeliveryTemplate) => {
  form.value = {
    id: template.id,
    templateName: template.templateName,
    deliveryContent: template.deliveryContent,
    messageTemplate: template.messageTemplate
  }
  dialogVisible.value = true
}

const appendVariable = (variable: string) => {
  form.value.messageTemplate += variable
}

const submit = async () => {
  if (!selectedAccountId.value) return
  if (!form.value.templateName.trim() || !form.value.deliveryContent.trim()) {
    showError('请填写模板名称和全部发货内容')
    return
  }
  if (!form.value.messageTemplate.includes('{deliveryContent}')) {
    showError('发送模板必须包含“全部发货内容”变量')
    return
  }
  saving.value = true
  try {
    const response = await saveFixedDeliveryTemplate({
      ...form.value,
      xianyuAccountId: selectedAccountId.value
    })
    if (response.code !== 200) {
      throw new Error(response.msg || '保存失败')
    }
    showSuccess('固定内容模板已保存')
    dialogVisible.value = false
    await loadTemplates()
  } catch (error: any) {
    showError(error.message || '保存失败')
  } finally {
    saving.value = false
  }
}

const removeTemplate = async (template: FixedDeliveryTemplate) => {
  if (!selectedAccountId.value) return
  try {
    await showConfirm(`确定删除模板“${template.templateName}”？`, '删除模板')
    const response = await deleteFixedDeliveryTemplate(selectedAccountId.value, template.id)
    if (response.code !== 200) {
      throw new Error(response.msg || '删除失败')
    }
    showSuccess('模板已删除')
    await loadTemplates()
  } catch (error: any) {
    if (error !== 'cancel') showError(error.message || '删除失败')
  }
}

watch(selectedAccountId, loadTemplates)

onMounted(async () => {
  const response = await getAccountList()
  accounts.value = response.data?.accounts || []
  const queryAccountId = Number(route.query.accountId)
  selectedAccountId.value = accounts.value.some(account => account.id === queryAccountId)
    ? queryAccountId
    : (accounts.value[0]?.id || null)
})
</script>

<template>
  <div class="fixed-template-page">
    <header class="fixed-template-header">
      <div>
        <h1>固定内容模板</h1>
        <p>统一管理网盘链接、下载说明等固定资源，商品只需选择模板。</p>
      </div>
      <div class="fixed-template-actions">
        <select v-model="selectedAccountId">
          <option v-for="account in accounts" :key="account.id" :value="account.id">
            {{ account.accountNote || account.unb }}
          </option>
        </select>
        <button class="primary-btn" @click="openCreate">新建模板</button>
      </div>
    </header>

    <section class="fixed-template-panel">
      <div v-if="loading" class="empty-state">正在加载...</div>
      <div v-else-if="templates.length === 0" class="empty-state">
        暂无固定内容模板，创建后即可在商品自动发货中复用。
      </div>
      <div v-else class="template-list">
        <article v-for="template in templates" :key="template.id" class="template-card">
          <div class="template-card-header">
            <strong>{{ template.templateName }}</strong>
            <div>
              <button @click="openEdit(template)">编辑</button>
              <button class="danger-text" @click="removeTemplate(template)">删除</button>
            </div>
          </div>
          <dl>
            <dt>全部发货内容</dt>
            <dd>{{ template.deliveryContent }}</dd>
            <dt>最终发送模板</dt>
            <dd>{{ template.messageTemplate }}</dd>
          </dl>
        </article>
      </div>
    </section>

    <div v-if="dialogVisible" class="template-dialog-mask" @click.self="dialogVisible = false">
      <form class="template-dialog" @submit.prevent="submit">
        <header>
          <div>
            <h2>{{ form.id ? '编辑模板' : '新建模板' }}</h2>
            <p>模板可被多个商品复用，不消耗卡密库存。</p>
          </div>
          <button type="button" class="close-btn" @click="dialogVisible = false">×</button>
        </header>
        <label>
          <span>模板名称</span>
          <input v-model="form.templateName" maxlength="100" placeholder="例如：百度网盘资源" />
        </label>
        <label>
          <span>全部发货内容</span>
          <textarea v-model="form.deliveryContent" maxlength="5000" rows="5" placeholder="填写网盘链接、提取码、使用说明等固定内容"></textarea>
        </label>
        <label>
          <span class="template-label-row">
            最终发送模板
            <span>
              <button type="button" @click="appendVariable('{buyerName}')">会员名称</button>
              <button type="button" @click="appendVariable('{orderId}')">订单号</button>
              <button type="button" @click="appendVariable('{deliveryContent}')">全部发货内容</button>
            </span>
          </span>
          <textarea v-model="form.messageTemplate" maxlength="1000" rows="5"></textarea>
        </label>
        <div class="template-preview">
          <strong>发送预览</strong>
          <p>{{ preview }}</p>
        </div>
        <footer>
          <button type="button" @click="dialogVisible = false">取消</button>
          <button type="submit" class="primary-btn" :disabled="saving">{{ saving ? '保存中...' : '保存模板' }}</button>
        </footer>
      </form>
    </div>
  </div>
</template>

<style scoped>
.fixed-template-page { min-height: 100%; padding: 24px; background: #f6f7f9; color: #101828; }
.fixed-template-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; margin-bottom: 18px; }
.fixed-template-header h1 { margin: 0; font-size: 22px; }
.fixed-template-header p, .template-dialog header p { margin: 6px 0 0; color: #667085; font-size: 13px; }
.fixed-template-actions { display: flex; gap: 10px; }
select, input, textarea { box-sizing: border-box; width: 100%; border: 1px solid #d0d5dd; border-radius: 7px; background: #fff; color: #101828; font: inherit; }
select, input { height: 38px; padding: 0 11px; }
textarea { padding: 10px 11px; line-height: 1.55; resize: vertical; }
.fixed-template-actions select { width: 180px; }
button { border: 1px solid #d0d5dd; border-radius: 6px; background: #fff; color: #344054; cursor: pointer; height: 34px; padding: 0 12px; }
.primary-btn { border-color: #155eef; background: #155eef; color: #fff; }
.fixed-template-panel { min-height: 240px; padding: 18px; border: 1px solid #e4e7ec; border-radius: 10px; background: #fff; }
.empty-state { padding: 72px 20px; color: #98a2b3; text-align: center; }
.template-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 12px; }
.template-card { min-width: 0; padding: 15px; border: 1px solid #e4e7ec; border-radius: 8px; }
.template-card-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.template-card-header > div { display: flex; gap: 6px; }
.danger-text { color: #d92d20; }
dl { margin: 14px 0 0; }
dt { margin-top: 10px; color: #667085; font-size: 12px; }
dd { margin: 5px 0 0; color: #344054; font-size: 13px; line-height: 1.55; white-space: pre-wrap; word-break: break-word; }
.template-dialog-mask { position: fixed; inset: 0; z-index: 1000; display: grid; place-items: center; padding: 20px; background: rgba(16, 24, 40, .45); }
.template-dialog { width: min(680px, 100%); max-height: calc(100vh - 40px); overflow: auto; padding: 22px; border-radius: 10px; background: #fff; box-shadow: 0 24px 60px rgba(16, 24, 40, .22); }
.template-dialog header { display: flex; justify-content: space-between; margin-bottom: 18px; }
.template-dialog h2 { margin: 0; font-size: 19px; }
.close-btn { border: 0; font-size: 22px; }
.template-dialog label { display: grid; gap: 7px; margin-top: 14px; color: #344054; font-size: 13px; font-weight: 600; }
.template-label-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.template-label-row > span { display: flex; flex-wrap: wrap; gap: 5px; }
.template-label-row button { height: 27px; padding: 0 8px; color: #155eef; font-size: 12px; }
.template-preview { margin-top: 14px; padding: 12px; border: 1px solid #e4e7ec; border-radius: 7px; background: #f9fafb; }
.template-preview strong { font-size: 12px; }
.template-preview p { margin: 7px 0 0; color: #344054; font-size: 13px; line-height: 1.55; white-space: pre-wrap; word-break: break-word; }
.template-dialog footer { display: flex; justify-content: flex-end; gap: 10px; margin-top: 18px; }
@media (max-width: 680px) {
  .fixed-template-page { padding: 14px; }
  .fixed-template-header { flex-direction: column; }
  .fixed-template-actions, .fixed-template-actions select { width: 100%; }
  .template-list { grid-template-columns: 1fr; }
  .template-label-row { align-items: flex-start; flex-direction: column; }
}
</style>
