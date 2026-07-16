<script setup lang="ts">
interface Props {
  modelValue: boolean;
}

interface Emits {
  (e: 'update:modelValue', value: boolean): void;
  (e: 'confirm'): void;
}

defineProps<Props>();
const emit = defineEmits<Emits>();

const handleClose = () => {
  emit('update:modelValue', false);
};

const handleConfirm = () => {
  emit('confirm');
  emit('update:modelValue', false);
};
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="modelValue" class="modal-overlay" @click.self="handleClose">
        <div class="modal-container">
          <div class="modal-header">
            <div>
              <h2 class="modal-title">需要滑块验证</h2>
              <p class="modal-subtitle">请在常用浏览器完成验证，再更新账号凭证</p>
            </div>
            <button class="modal-close" type="button" aria-label="关闭" @click="handleClose">×</button>
          </div>

          <div class="modal-body">
            <ol class="captcha-steps">
              <li>点击下方按钮访问闲鱼 IM 页面</li>
              <li>在闲鱼页面完成滑块验证</li>
              <li>按 F12 打开开发者工具并复制最新 Cookie</li>
              <li>返回连接管理，通过“手动更新”保存 Cookie</li>
            </ol>
            <p class="captcha-tip">Cookie 更新成功后会立即刷新凭证并尝试重新连接。</p>
          </div>

          <div class="modal-footer">
            <button class="btn btn-secondary" type="button" @click="handleClose">取消</button>
            <button class="btn btn-primary" type="button" @click="handleConfirm">访问闲鱼 IM</button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(15, 23, 42, 0.42);
}

.modal-container {
  width: min(460px, 96vw);
  overflow: hidden;
  background: #ffffff;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  box-shadow: 0 20px 48px rgba(15, 23, 42, 0.18);
}

.modal-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding: 18px 20px;
  border-bottom: 1px solid #eef0f3;
}

.modal-title {
  margin: 0;
  color: #111827;
  font-size: 16px;
  font-weight: 600;
}

.modal-subtitle {
  margin: 6px 0 0;
  color: #6b7280;
  font-size: 13px;
  line-height: 1.5;
}

.modal-close {
  width: 28px;
  height: 28px;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #6b7280;
  font-size: 20px;
  cursor: pointer;
}

.modal-close:hover {
  background: #f3f4f6;
  color: #111827;
}

.modal-body {
  padding: 20px;
}

.captcha-steps {
  margin: 0;
  padding-left: 24px;
  color: #374151;
  font-size: 14px;
  line-height: 1.65;
}

.captcha-steps li + li {
  margin-top: 8px;
}

.captcha-tip {
  margin: 16px 0 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f8fafc;
  color: #64748b;
  font-size: 13px;
  line-height: 1.5;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 14px 20px;
  border-top: 1px solid #eef0f3;
}

.btn {
  height: 34px;
  padding: 0 16px;
  border: 1px solid #d1d5db;
  border-radius: 7px;
  font-size: 13px;
  cursor: pointer;
}

.btn-secondary {
  background: #ffffff;
  color: #374151;
}

.btn-secondary:hover {
  background: #f7f8fa;
}

.btn-primary {
  border-color: #2563eb;
  background: #2563eb;
  color: #ffffff;
}

.btn-primary:hover {
  background: #1d4ed8;
}

.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.16s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

@media (max-width: 640px) {
  .modal-overlay {
    padding: 10px;
  }

  .modal-header,
  .modal-body,
  .modal-footer {
    padding-left: 14px;
    padding-right: 14px;
  }
}
</style>
