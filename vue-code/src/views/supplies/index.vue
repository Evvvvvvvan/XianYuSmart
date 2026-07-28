<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { getAccountList } from '@/api/account'
import { convertSupplyToMaterial, deleteResource, executeResource, getResources, saveResource, type MerchantResource } from '@/api/merchant'
import type { Account } from '@/types'
import { toast } from '@/utils/toast'
import '@/styles/merchant-workbench.css'

const loading = ref(false)
const supplies = ref<MerchantResource[]>([])
const accounts = ref<Account[]>([])
const keyword = ref('')
const editorOpen = ref(false)
const form = reactive({
  id: undefined as number | undefined,
  name: '',
  xianyuAccountId: undefined as number | undefined,
  xyGoodsId: '',
  amount: 0,
  stock: 1,
  sourceUrl: '',
  description: '',
  imagesText: ''
})

const filtered = computed(() => {
  const value = keyword.value.trim().toLowerCase()
  return value ? supplies.value.filter(item => `${item.name} ${item.xyGoodsId || ''}`.toLowerCase().includes(value)) : supplies.value
})

const load = async () => {
  loading.value = true
  try {
    const [resourceResult, accountResult] = await Promise.all([getResources('SUPPLY'), getAccountList()])
    supplies.value = resourceResult.data || []
    accounts.value = accountResult.data?.accounts || []
  } finally {
    loading.value = false
  }
}

const edit = (item?: MerchantResource) => {
  Object.assign(form, {
    id: item?.id,
    name: item?.name || '',
    xianyuAccountId: item?.xianyuAccountId || accounts.value[0]?.id,
    xyGoodsId: item?.xyGoodsId || '',
    amount: Number(item?.amount || 0),
    stock: item?.stock ?? 1,
    sourceUrl: item?.data?.sourceUrl || '',
    description: item?.data?.description || '',
    imagesText: Array.isArray(item?.data?.images) ? item?.data.images.join('\n') : ''
  })
  editorOpen.value = true
}

const save = async () => {
  if (!form.name.trim()) return toast.error('货源名称不能为空')
  await saveResource({
    id: form.id,
    resourceType: 'SUPPLY',
    name: form.name,
    status: 1,
    xianyuAccountId: form.xianyuAccountId,
    xyGoodsId: form.xyGoodsId,
    amount: form.amount,
    stock: form.stock,
    data: {
      sourceUrl: form.sourceUrl,
      description: form.description,
      images: form.imagesText.split('\n').map(value => value.trim()).filter(Boolean)
    }
  })
  editorOpen.value = false
  toast.success('货源已保存')
  await load()
}

const collect = async (item: MerchantResource) => {
  await executeResource(item.id)
  toast.success('采集任务已完成')
  await load()
}

const materialize = async (item: MerchantResource) => {
  await convertSupplyToMaterial(item.id)
  toast.success('已生成可发布素材')
}

const remove = async (item: MerchantResource) => {
  if (!window.confirm(`确认删除货源“${item.name}”？`)) return
  await deleteResource(item.id)
  await load()
}

onMounted(load)
</script>

<template>
  <section class="workbench">
    <header class="workbench__header">
      <div><h1>货源库</h1><p>统一管理来源、素材完整度、价格和库存，再转为可发布素材。</p></div>
      <button class="workbench__btn workbench__btn--primary" @click="edit()">新增货源</button>
    </header>
    <div class="workbench__card workbench__toolbar">
      <input v-model="keyword" class="workbench__input" placeholder="搜索货源名称或商品 ID">
      <span class="workbench__muted">共 {{ filtered.length }} 条</span>
      <button class="workbench__btn" :disabled="loading" @click="load">刷新</button>
    </div>
    <div class="workbench__list workbench__section">
      <article v-for="item in filtered" :key="item.id" class="workbench__item">
        <img :src="item.data?.images?.[0]" alt="">
        <div>
          <h3>{{ item.name }}</h3>
          <div class="workbench__tags">
            <span class="workbench__tag">¥ {{ item.amount }}</span>
            <span class="workbench__tag">库存 {{ item.stock }}</span>
            <span class="workbench__tag" :class="{ 'workbench__tag--good': item.data?.images?.length }">{{ item.data?.images?.length ? `${item.data.images.length} 张图` : '待补图片' }}</span>
            <span v-if="item.xyGoodsId" class="workbench__tag">ID {{ item.xyGoodsId }}</span>
          </div>
        </div>
        <div class="workbench__actions">
          <button class="workbench__btn" @click="collect(item)">采集更新</button>
          <button class="workbench__btn" @click="materialize(item)">生成素材</button>
          <button class="workbench__btn" @click="edit(item)">编辑</button>
          <button class="workbench__btn workbench__btn--danger" @click="remove(item)">删除</button>
        </div>
      </article>
      <div v-if="!filtered.length" class="workbench__empty">暂无货源，可从商机发掘导入或手动创建。</div>
    </div>

    <div v-if="editorOpen" class="supply-dialog" @click.self="editorOpen = false">
      <form class="workbench__card supply-dialog__panel" @submit.prevent="save">
        <h2>{{ form.id ? '编辑货源' : '新增货源' }}</h2>
        <div class="workbench__grid workbench__grid--two">
          <label class="workbench__field">货源名称<input v-model="form.name" class="workbench__input"></label>
          <label class="workbench__field">关联账号<select v-model="form.xianyuAccountId" class="workbench__select"><option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.accountNote || account.unb }}</option></select></label>
          <label class="workbench__field">来源商品 ID<input v-model="form.xyGoodsId" class="workbench__input"></label>
          <label class="workbench__field">来源链接<input v-model="form.sourceUrl" class="workbench__input" placeholder="仅支持 HTTPS 闲鱼商品地址"></label>
          <label class="workbench__field">成本/参考价<input v-model.number="form.amount" class="workbench__input" type="number" min="0" step="0.01"></label>
          <label class="workbench__field">库存<input v-model.number="form.stock" class="workbench__input" type="number" min="0"></label>
        </div>
        <label class="workbench__field">说明<textarea v-model="form.description" class="workbench__textarea"></textarea></label>
        <label class="workbench__field">图片地址（每行一张）<textarea v-model="form.imagesText" class="workbench__textarea"></textarea></label>
        <div class="workbench__actions supply-dialog__footer">
          <button type="button" class="workbench__btn" @click="editorOpen = false">取消</button>
          <button class="workbench__btn workbench__btn--primary">保存货源</button>
        </div>
      </form>
    </div>
  </section>
</template>

<style scoped>
.supply-dialog { position: fixed; inset: 0; z-index: 1200; display: grid; place-items: center; padding: 16px; background: rgba(16, 24, 40, .45); }
.supply-dialog__panel { width: min(760px, 100%); max-height: 90vh; overflow: auto; }
.supply-dialog__panel h2 { margin-top: 0; }
.supply-dialog__panel > .workbench__field { margin-top: 14px; }
.supply-dialog__footer { justify-content: flex-end; margin-top: 16px; }
</style>
