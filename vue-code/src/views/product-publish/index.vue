<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { getAccountList } from '@/api/account'
import { createPublishPlan, getResources, type MerchantResource } from '@/api/merchant'
import { CHINA_PROVINCES, CHINA_REGIONS } from '@/data/china-regions'
import type { Account } from '@/types'
import { toast } from '@/utils/toast'
import '@/styles/merchant-workbench.css'

const step = ref(1)
const loading = ref(false)
const accounts = ref<Account[]>([])
const materials = ref<MerchantResource[]>([])
const form = reactive({
  xianyuAccountId: 0,
  name: '',
  description: '',
  amount: 0,
  stock: 1,
  category: '虚拟商品',
  province: '北京市',
  city: '北京市',
  district: '',
  deliveryMethod: '线上交付',
  imagesText: ''
})

const cities = computed(() => CHINA_REGIONS[form.province] || [])
const images = computed(() => form.imagesText.split('\n').map(value => value.trim()).filter(Boolean))

const load = async () => {
  const [accountResult, materialResult] = await Promise.all([getAccountList(), getResources('MATERIAL', 1)])
  accounts.value = accountResult.data?.accounts || []
  materials.value = materialResult.data || []
  form.xianyuAccountId ||= accounts.value[0]?.id || 0
}

const useMaterial = (event: Event) => {
  const id = Number((event.target as HTMLSelectElement).value)
  const material = materials.value.find(item => item.id === id)
  if (!material) return
  form.name = String(material.data?.title || material.name)
  form.description = String(material.data?.description || '')
  form.amount = Number(material.amount || 0)
  form.stock = material.stock || 1
  form.imagesText = Array.isArray(material.data?.images) ? material.data.images.join('\n') : ''
}

const next = () => {
  if (step.value === 1 && (!form.name.trim() || !form.description.trim())) return toast.error('请完善标题和详情')
  if (step.value === 2 && (!form.amount || !images.value.length)) return toast.error('请完善价格并添加图片')
  if (step.value === 3 && !form.xianyuAccountId) return toast.error('请选择发布账号')
  step.value = Math.min(4, step.value + 1)
}

const submit = async (dryRun: boolean) => {
  loading.value = true
  try {
    await createPublishPlan({ ...form, images: images.value, dryRun })
    toast.success(dryRun ? '发布前校验通过' : '商品发布任务已提交')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="workbench publish">
    <header class="workbench__header">
      <div><h1>商品发布</h1><p>本地行政区划、素材复用和发布前校验均已内置，无需额外 API Key。</p></div>
    </header>
    <div class="workbench__steps">
      <button v-for="(label, index) in ['1 商品内容', '2 价格图片', '3 账号位置', '4 确认发布']" :key="label" class="workbench__step" :class="{ 'workbench__step--active': step === index + 1 }" @click="step = index + 1">{{ label }}</button>
    </div>

    <form class="workbench__card publish__panel workbench__section" @submit.prevent>
      <template v-if="step === 1">
        <label class="workbench__field">复用已有素材<select class="workbench__select" @change="useMaterial"><option value="">从素材库选择（可选）</option><option v-for="item in materials" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
        <label class="workbench__field">商品标题<input v-model="form.name" class="workbench__input" maxlength="120"></label>
        <label class="workbench__field">商品详情<textarea v-model="form.description" class="workbench__textarea" maxlength="3000"></textarea></label>
      </template>
      <template v-else-if="step === 2">
        <div class="workbench__grid workbench__grid--two">
          <label class="workbench__field">售价<input v-model.number="form.amount" class="workbench__input" type="number" min="0.01" step="0.01"></label>
          <label class="workbench__field">库存<input v-model.number="form.stock" class="workbench__input" type="number" min="0"></label>
          <label class="workbench__field">商品分类<input v-model="form.category" class="workbench__input"></label>
          <label class="workbench__field">交付方式<select v-model="form.deliveryMethod" class="workbench__select"><option>线上交付</option><option>快递发货</option><option>当面交易</option></select></label>
        </div>
        <label class="workbench__field">图片 HTTPS 地址（每行一张，最多 9 张）<textarea v-model="form.imagesText" class="workbench__textarea" maxlength="5000"></textarea></label>
        <div class="publish__images"><img v-for="image in images.slice(0, 9)" :key="image" :src="image" alt=""></div>
      </template>
      <template v-else-if="step === 3">
        <div class="workbench__grid workbench__grid--two">
          <label class="workbench__field">发布账号<select v-model="form.xianyuAccountId" class="workbench__select"><option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.accountNote || account.unb }}</option></select></label>
          <label class="workbench__field">省份<select v-model="form.province" class="workbench__select" @change="form.city = cities[0] || ''"><option v-for="province in CHINA_PROVINCES" :key="province">{{ province }}</option></select></label>
          <label class="workbench__field">城市<select v-model="form.city" class="workbench__select"><option v-for="city in cities" :key="city">{{ city }}</option></select></label>
          <label class="workbench__field">区县/详细位置<input v-model="form.district" class="workbench__input" placeholder="按实际发布位置填写"></label>
        </div>
      </template>
      <template v-else>
        <div class="publish__summary">
          <img :src="images[0]" alt="">
          <div>
            <h2>{{ form.name }}</h2>
            <p>{{ form.description }}</p>
            <strong>¥ {{ form.amount }} · 库存 {{ form.stock }}</strong>
            <small>{{ form.category }} · {{ form.deliveryMethod }} · {{ form.province }} {{ form.city }} {{ form.district }}</small>
          </div>
        </div>
        <div class="publish__notice">提交后进入后台任务队列；遇到平台验证时会停止执行并提示人工处理，不会反复重试触发风控。</div>
      </template>
      <footer class="workbench__actions publish__footer">
        <button v-if="step > 1" class="workbench__btn" @click="step--">上一步</button>
        <button v-if="step < 4" class="workbench__btn workbench__btn--primary" @click="next">下一步</button>
        <template v-else>
          <button class="workbench__btn" :disabled="loading" @click="submit(true)">发布前校验</button>
          <button class="workbench__btn workbench__btn--primary" :disabled="loading" @click="submit(false)">提交发布</button>
        </template>
      </footer>
    </form>
  </section>
</template>

<style scoped>
.publish__panel { max-width: 920px; margin-right: auto; margin-left: auto; }
.publish__panel > .workbench__field { margin-bottom: 14px; }
.publish__footer { justify-content: flex-end; margin-top: 18px; }
.publish__images { display: grid; grid-template-columns: repeat(6, 1fr); gap: 8px; margin-top: 12px; }
.publish__images img { width: 100%; aspect-ratio: 1; border-radius: 7px; object-fit: cover; background: #f2f4f7; }
.publish__summary { display: grid; grid-template-columns: 200px 1fr; gap: 18px; }
.publish__summary img { width: 200px; height: 200px; border-radius: 8px; object-fit: cover; background: #f2f4f7; }
.publish__summary p { color: #667085; white-space: pre-wrap; }
.publish__summary strong, .publish__summary small { display: block; margin-top: 10px; }
.publish__notice { margin-top: 16px; padding: 11px; border: 1px solid #fedf89; border-radius: 7px; color: #93370d; background: #fffaeb; font-size: 12px; }
@media (max-width: 767px) { .publish__images { grid-template-columns: repeat(3, 1fr); } .publish__summary { grid-template-columns: 1fr; } .publish__summary img { width: 100%; height: auto; aspect-ratio: 1; } }
</style>
