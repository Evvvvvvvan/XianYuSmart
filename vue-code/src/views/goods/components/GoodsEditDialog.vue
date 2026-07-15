<script setup lang="ts">
import { reactive, watch } from 'vue'
import { showError, getGoodsStatusText } from '@/utils'
import type { GoodsItemWithConfig } from '@/api/goods'
import { normalizeGoodsEditForm, validateGoodsEditForm, type GoodsEditForm } from '../goods-edit'

interface Props {
  modelValue: boolean
  goods: GoodsItemWithConfig | null
  saving?: boolean
}

interface Emits {
  (e: 'update:modelValue', value: boolean): void
  (e: 'save', form: GoodsEditForm): void
  (e: 'openPlatform'): void
  (e: 'syncPlatform'): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const form = reactive<GoodsEditForm>({
  title: '',
  soldPrice: '',
  detailInfo: '',
  coverPic: ''
})

watch(
  [() => props.modelValue, () => props.goods],
  () => {
    if (!props.modelValue || !props.goods) return
    form.title = props.goods.item.title || ''
    form.soldPrice = props.goods.item.soldPrice || ''
    form.detailInfo = props.goods.item.detailInfo || ''
    form.coverPic = props.goods.item.coverPic || ''
  },
  { immediate: true }
)

const close = () => {
  if (!props.saving) emit('update:modelValue', false)
}

const submit = () => {
  const message = validateGoodsEditForm(form)
  if (message) {
    showError(message)
    return
  }
  emit('save', normalizeGoodsEditForm(form))
}
</script>

<template>
  <Transition name="goods-edit-fade">
    <div v-if="modelValue && goods" class="goods-edit__overlay" @click.self="close">
      <section class="goods-edit" role="dialog" aria-modal="true" aria-labelledby="goods-edit-title">
        <header class="goods-edit__header">
          <div>
            <h2 id="goods-edit-title">编辑商品资料</h2>
            <p>ID: {{ goods.item.xyGoodId }}</p>
          </div>
          <button class="goods-edit__close" :disabled="saving" aria-label="关闭" @click="close">×</button>
        </header>

        <div class="goods-edit__body">
          <div class="goods-edit__notice">
            <span>本地资料用于系统展示，平台同步后以闲鱼数据为准。</span>
            <button type="button" @click="emit('syncPlatform')">同步平台资料</button>
          </div>

          <div class="goods-edit__readonly">
            <span>平台状态：{{ getGoodsStatusText(goods.item.status).text }}</span>
            <span>规格：{{ goods.item.skuCount > 1 ? `${goods.item.skuCount} 个` : '单规格' }}</span>
          </div>

          <label class="goods-edit__field">
            <span>商品标题</span>
            <input v-model="form.title" maxlength="256" autocomplete="off" />
          </label>

          <label class="goods-edit__field">
            <span>商品价格</span>
            <input v-model="form.soldPrice" inputmode="decimal" autocomplete="off" placeholder="0.00" />
          </label>

          <label class="goods-edit__field">
            <span>封面地址</span>
            <input v-model="form.coverPic" maxlength="2000" autocomplete="off" placeholder="https://" />
          </label>

          <label class="goods-edit__field">
            <span>商品描述</span>
            <textarea v-model="form.detailInfo" maxlength="5000" rows="6"></textarea>
            <small>{{ form.detailInfo.length }}/5000</small>
          </label>

          <p class="goods-edit__boundary">平台状态、SKU、库存、图集和分类需在闲鱼页面修改，避免影响卡密规格匹配。</p>
        </div>

        <footer class="goods-edit__footer">
          <button class="goods-edit__button goods-edit__button--ghost" :disabled="saving" @click="close">取消</button>
          <button class="goods-edit__button goods-edit__button--secondary" :disabled="saving" @click="emit('openPlatform')">前往闲鱼修改</button>
          <button class="goods-edit__button goods-edit__button--primary" :disabled="saving" @click="submit">
            {{ saving ? '保存中' : '保存本地资料' }}
          </button>
        </footer>
      </section>
    </div>
  </Transition>
</template>

<style scoped>
.goods-edit__overlay {
  position: fixed;
  inset: 0;
  z-index: 1100;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(0, 0, 0, 0.28);
}

.goods-edit {
  width: min(560px, 100%);
  max-height: calc(100vh - 40px);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid rgba(60, 60, 67, 0.12);
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.18);
}

.goods-edit__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px 14px;
  border-bottom: 1px solid rgba(60, 60, 67, 0.1);
}

.goods-edit__header h2 {
  margin: 0;
  color: #1c1c1e;
  font-size: 17px;
  font-weight: 600;
}

.goods-edit__header p {
  margin: 4px 0 0;
  color: rgba(28, 28, 30, 0.55);
  font-size: 12px;
}

.goods-edit__close {
  width: 30px;
  height: 30px;
  border: none;
  border-radius: 8px;
  background: rgba(60, 60, 67, 0.08);
  color: rgba(28, 28, 30, 0.65);
  font-size: 20px;
  cursor: pointer;
}

.goods-edit__body {
  overflow-y: auto;
  padding: 18px 20px;
}

.goods-edit__notice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  margin-bottom: 14px;
  border: 1px solid rgba(10, 132, 255, 0.16);
  border-radius: 8px;
  background: rgba(10, 132, 255, 0.06);
  color: #315b82;
  font-size: 12px;
}

.goods-edit__notice button {
  flex-shrink: 0;
  padding: 0;
  border: none;
  background: transparent;
  color: #0a84ff;
  font: inherit;
  cursor: pointer;
}

.goods-edit__readonly {
  display: flex;
  gap: 16px;
  margin-bottom: 16px;
  color: rgba(28, 28, 30, 0.58);
  font-size: 12px;
}

.goods-edit__field {
  display: block;
  margin-bottom: 14px;
}

.goods-edit__field > span {
  display: block;
  margin-bottom: 6px;
  color: #1c1c1e;
  font-size: 13px;
  font-weight: 500;
}

.goods-edit__field input,
.goods-edit__field textarea {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid rgba(60, 60, 67, 0.16);
  border-radius: 8px;
  background: #fff;
  color: #1c1c1e;
  font: inherit;
  font-size: 13px;
  outline: none;
}

.goods-edit__field input {
  height: 38px;
  padding: 0 11px;
}

.goods-edit__field textarea {
  padding: 10px 11px;
  line-height: 1.5;
  resize: vertical;
}

.goods-edit__field input:focus,
.goods-edit__field textarea:focus {
  border-color: #0a84ff;
  box-shadow: 0 0 0 3px rgba(10, 132, 255, 0.1);
}

.goods-edit__field small {
  display: block;
  margin-top: 4px;
  color: rgba(28, 28, 30, 0.45);
  text-align: right;
  font-size: 11px;
}

.goods-edit__boundary {
  margin: 0;
  color: rgba(28, 28, 30, 0.55);
  font-size: 12px;
  line-height: 1.6;
}

.goods-edit__footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 14px 20px;
  border-top: 1px solid rgba(60, 60, 67, 0.1);
}

.goods-edit__button {
  height: 36px;
  padding: 0 14px;
  border: 1px solid transparent;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}

.goods-edit__button--ghost {
  border-color: rgba(60, 60, 67, 0.14);
  background: #fff;
  color: #1c1c1e;
}

.goods-edit__button--secondary {
  border-color: rgba(10, 132, 255, 0.18);
  background: rgba(10, 132, 255, 0.07);
  color: #0a84ff;
}

.goods-edit__button--primary {
  background: #0a84ff;
  color: #fff;
}

.goods-edit__button:disabled,
.goods-edit__close:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.goods-edit-fade-enter-active,
.goods-edit-fade-leave-active {
  transition: opacity 0.18s ease;
}

.goods-edit-fade-enter-from,
.goods-edit-fade-leave-to {
  opacity: 0;
}

@media screen and (max-width: 600px) {
  .goods-edit__overlay {
    align-items: flex-end;
    padding: 0;
  }

  .goods-edit {
    max-height: 92vh;
    border-radius: 16px 16px 0 0;
  }

  .goods-edit__footer {
    flex-wrap: wrap;
    padding-bottom: max(14px, env(safe-area-inset-bottom));
  }

  .goods-edit__button--primary {
    flex: 1;
  }
}
</style>
