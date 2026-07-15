<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue';
import {
  closeCaptchaSession,
  replayCaptchaDrag,
  startCaptchaSession,
  type CaptchaDragPoint
} from '@/api/websocket';
import { showError, showSuccess } from '@/utils';

interface Props {
  modelValue: boolean;
  accountId: number;
  captchaUrl: string;
}

interface Emits {
  (e: 'update:modelValue', value: boolean): void;
  (e: 'success'): void;
}

const props = defineProps<Props>();
const emit = defineEmits<Emits>();

const imageRef = ref<HTMLImageElement | null>(null);
const sessionId = ref('');
const screenshot = ref('');
const statusText = ref('正在加载验证页面...');
const loading = ref(false);
const submitting = ref(false);
let dragging = false;
let dragPoints: CaptchaDragPoint[] = [];
let lastPointTime = 0;
let requestSequence = 0;

const getErrorMessage = (error: unknown, fallback: string) => error instanceof Error ? error.message : fallback;

const releaseSession = () => {
  const currentSessionId = sessionId.value;
  sessionId.value = '';
  if (currentSessionId) {
    closeCaptchaSession(props.accountId, currentSessionId).catch(() => undefined);
  }
};

const loadCaptchaSession = async () => {
  const currentSequence = ++requestSequence;
  releaseSession();
  screenshot.value = '';
  statusText.value = '正在加载验证页面...';
  loading.value = true;
  try {
    const response = await startCaptchaSession(props.accountId, props.captchaUrl);
    if (currentSequence !== requestSequence || !props.modelValue) {
      if (response.data?.sessionId) {
        closeCaptchaSession(props.accountId, response.data.sessionId).catch(() => undefined);
      }
      return;
    }
    if ((response.code === 0 || response.code === 200) && response.data) {
      sessionId.value = response.data.sessionId;
      screenshot.value = response.data.screenshot || '';
      statusText.value = response.data.message || '请在验证画面中拖动滑块';
    } else {
      throw new Error(response.msg || '加载验证页面失败');
    }
  } catch (error: unknown) {
    statusText.value = getErrorMessage(error, '加载验证页面失败');
    showError(statusText.value);
  } finally {
    if (currentSequence === requestSequence) {
      loading.value = false;
    }
  }
};

const getDragPoint = (event: PointerEvent): CaptchaDragPoint | null => {
  const image = imageRef.value;
  if (!image) return null;
  const rect = image.getBoundingClientRect();
  if (!rect.width || !rect.height || !image.naturalWidth || !image.naturalHeight) return null;

  const now = performance.now();
  const point = {
    x: Math.max(0, Math.min(image.naturalWidth, (event.clientX - rect.left) * image.naturalWidth / rect.width)),
    y: Math.max(0, Math.min(image.naturalHeight, (event.clientY - rect.top) * image.naturalHeight / rect.height)),
    delayMs: lastPointTime ? Math.max(0, Math.min(40, Math.round(now - lastPointTime))) : 0
  };
  lastPointTime = now;
  return point;
};

const handlePointerDown = (event: PointerEvent) => {
  if (submitting.value || !sessionId.value) return;
  const point = getDragPoint(event);
  if (!point) return;

  dragging = true;
  dragPoints = [point];
  (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
};

const handlePointerMove = (event: PointerEvent) => {
  if (!dragging || dragPoints.length >= 159) return;
  const point = getDragPoint(event);
  const previous = dragPoints[dragPoints.length - 1];
  if (!point || !previous || (Math.abs(point.x - previous.x) < 1 && Math.abs(point.y - previous.y) < 1)) return;
  dragPoints.push(point);
};

const handlePointerUp = async (event: PointerEvent) => {
  if (!dragging) return;
  dragging = false;
  const finalPoint = getDragPoint(event);
  if (finalPoint) {
    if (dragPoints.length >= 160) {
      dragPoints[159] = finalPoint;
    } else {
      dragPoints.push(finalPoint);
    }
  }
  if (dragPoints.length < 2 || !sessionId.value) return;

  submitting.value = true;
  statusText.value = '正在提交验证结果...';
  try {
    const response = await replayCaptchaDrag(props.accountId, sessionId.value, dragPoints);
    if ((response.code === 0 || response.code === 200) && response.data) {
      statusText.value = response.data.message;
      if (response.data.success) {
        showSuccess(response.data.message);
        sessionId.value = '';
        emit('success');
        emit('update:modelValue', false);
        return;
      }
      screenshot.value = response.data.screenshot || screenshot.value;
    } else {
      throw new Error(response.msg || '滑块验证失败');
    }
  } catch (error: unknown) {
    statusText.value = getErrorMessage(error, '滑块验证失败');
    showError(statusText.value);
  } finally {
    submitting.value = false;
    dragPoints = [];
  }
};

const handlePointerCancel = () => {
  dragging = false;
  dragPoints = [];
};

const handleClose = () => {
  requestSequence++;
  releaseSession();
  emit('update:modelValue', false);
};

watch(() => props.modelValue, (visible) => {
  if (visible) {
    loadCaptchaSession();
  } else {
    requestSequence++;
    releaseSession();
  }
});

onUnmounted(() => {
  requestSequence++;
  releaseSession();
});
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="modelValue" class="modal-overlay" @click.self="handleClose">
        <div class="modal-container">
          <div class="modal-header">
            <div>
              <h2 class="modal-title">滑块验证</h2>
              <p class="modal-subtitle">验证在服务器浏览器中完成，成功后自动更新Cookie并重连</p>
            </div>
            <button class="modal-close" type="button" aria-label="关闭" @click="handleClose">×</button>
          </div>

          <div class="modal-body">
            <div v-if="loading" class="captcha-state">正在加载验证页面...</div>
            <div v-else-if="screenshot" class="captcha-viewer" :class="{ 'is-submitting': submitting }">
              <img
                ref="imageRef"
                class="captcha-image"
                :src="screenshot"
                alt="滑块验证页面"
                draggable="false"
                @pointerdown.prevent="handlePointerDown"
                @pointermove.prevent="handlePointerMove"
                @pointerup.prevent="handlePointerUp"
                @pointercancel="handlePointerCancel"
              >
            </div>
            <div v-else class="captcha-state captcha-state--error">{{ statusText }}</div>
            <p v-if="screenshot" class="captcha-status">{{ statusText }}</p>
          </div>

          <div class="modal-footer">
            <button class="btn btn-secondary" type="button" @click="handleClose">取消</button>
            <button class="btn btn-secondary" type="button" :disabled="loading || submitting" @click="loadCaptchaSession">
              重新加载
            </button>
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
  width: min(920px, 96vw);
  max-height: 92vh;
  display: flex;
  flex-direction: column;
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
  min-height: 0;
  padding: 20px;
  overflow: auto;
}

.captcha-viewer {
  overflow: hidden;
  border: 1px solid #dfe3e8;
  border-radius: 8px;
  background: #f7f8fa;
  line-height: 0;
}

.captcha-viewer.is-submitting {
  opacity: 0.65;
  pointer-events: none;
}

.captcha-image {
  display: block;
  width: 100%;
  height: auto;
  user-select: none;
  touch-action: none;
  cursor: grab;
}

.captcha-image:active {
  cursor: grabbing;
}

.captcha-state {
  min-height: 260px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #6b7280;
  font-size: 14px;
}

.captcha-state--error {
  color: #b42318;
}

.captcha-status {
  margin: 12px 0 0;
  color: #4b5563;
  font-size: 13px;
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

.btn:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.btn-secondary {
  background: #ffffff;
  color: #374151;
}

.btn-secondary:hover:not(:disabled) {
  background: #f7f8fa;
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
