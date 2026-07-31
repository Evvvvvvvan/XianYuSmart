<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import {
  cancelCaptcha,
  getCaptchaStatus,
  solveCaptcha,
  type CaptchaSolveMode,
  type CaptchaTaskStatus
} from '@/api/websocket';
import { showError, showSuccess } from '@/utils';

interface Props {
  modelValue: boolean;
  accountId: number;
}

interface Emits {
  (e: 'update:modelValue', value: boolean): void;
  (e: 'cookie'): void;
  (e: 'success'): void;
}

type CaptchaOption = CaptchaSolveMode | 'COOKIE';

const statusLabels: Record<string, string> = {
  PENDING: '等待执行',
  RUNNING: '正在验证',
  SUCCEEDED: '验证成功',
  FAILED: '验证失败',
  TIMEOUT: '验证超时',
  UNSUPPORTED: '环境不支持',
  CANCELLED: '已取消'
};
const phaseLabels: Record<string, string> = {
  QUEUED: '任务排队',
  CHECKING_ENVIRONMENT: '检查浏览器环境',
  STARTING_BROWSER: '启动浏览器',
  PAUSING_RECONNECT: '暂停后台重连',
  OPENING_PAGE: '打开验证页面',
  FINDING_SLIDER: '识别滑块',
  DRAGGING_SLIDER: '拖动滑块',
  WAITING_RESULT: '等待验证结果',
  RETRYING_SLIDER: '准备重新验证',
  RESETTING_SESSION: '重建验证会话',
  WAITING_BROWSER: '等待浏览器响应',
  WAITING_MANUAL: '等待人工拖动',
  COLLECTING_COOKIE: '回收 Cookie',
  UPDATING_COOKIE: '更新 Cookie 并重连'
};

const props = defineProps<Props>();
const emit = defineEmits<Emits>();
const selectedMode = ref<CaptchaOption>('AUTO');
const taskStatus = ref<CaptchaTaskStatus | null>(null);
const loading = ref(false);
const cancelling = ref(false);
const pollError = ref('');
const now = ref(Date.now());
let pollTimer: ReturnType<typeof setTimeout> | null = null;
let clockTimer: ReturnType<typeof setInterval> | null = null;

const taskRunning = computed(() =>
  taskStatus.value?.status === 'PENDING'
  || taskStatus.value?.status === 'RUNNING'
);
const running = computed(() => loading.value || taskRunning.value);

const elapsedSeconds = computed(() => {
  if (!taskStatus.value) return 0;
  const end = taskStatus.value.finishedAt || now.value;
  return Math.max(0, Math.floor((end - taskStatus.value.startedAt) / 1000));
});

const remainingSeconds = computed(() => {
  if (!taskStatus.value || !taskRunning.value) return 0;
  return Math.max(0, Math.ceil((taskStatus.value.deadlineAt - now.value) / 1000));
});

const updatedSecondsAgo = computed(() => {
  if (!taskStatus.value) return 0;
  return Math.max(0, Math.floor((now.value - taskStatus.value.updatedAt) / 1000));
});

const attemptText = computed(() => {
  if (!taskStatus.value?.attempt) return '';
  return `第 ${taskStatus.value.attempt}/${taskStatus.value.maxAttempts} 次`;
});
const statusText = computed(() =>
  taskStatus.value ? statusLabels[taskStatus.value.status] || taskStatus.value.status : ''
);
const phaseText = computed(() =>
  taskStatus.value ? phaseLabels[taskStatus.value.phase] || taskStatus.value.phase : ''
);

const actionText = computed(() => {
  if (running.value) return '验证处理中';
  if (selectedMode.value === 'AUTO') return '开始自动拖动';
  if (selectedMode.value === 'MANUAL_BROWSER') return '打开人工浏览器';
  return '粘贴更新后的 Cookie';
});

watch(() => props.modelValue, (visible) => {
  if (!visible) {
    clearPolling();
    stopClock();
    return;
  }
  selectedMode.value = 'AUTO';
  taskStatus.value = null;
  loading.value = false;
  cancelling.value = false;
  pollError.value = '';
  startClock();
  void resumeActiveTask();
});

const handleClose = () => {
  clearPolling();
  emit('update:modelValue', false);
};

const handleAction = async () => {
  if (selectedMode.value === 'COOKIE') {
    emit('cookie');
    handleClose();
    return;
  }
  if (!props.accountId) {
    showError('账号ID无效');
    return;
  }

  loading.value = true;
  try {
    const response = await solveCaptcha(props.accountId, selectedMode.value);
    if (response.code !== 0 && response.code !== 200) {
      throw new Error(response.msg || '滑块验证任务启动失败');
    }
    if (!response.data) {
      throw new Error('滑块验证任务状态为空');
    }
    pollError.value = '';
    handleTaskStatus(response.data);
  } catch (error: any) {
    showError(error.message || '滑块验证任务启动失败');
  } finally {
    loading.value = false;
  }
};

const pollStatus = async () => {
  try {
    const response = await getCaptchaStatus(props.accountId);
    if (response.code !== 0 && response.code !== 200) {
      throw new Error(response.msg || '滑块验证状态查询失败');
    }
    if (response.data) {
      pollError.value = '';
      handleTaskStatus(response.data);
    }
  } catch (error: any) {
    const message = error.message || '滑块验证状态查询失败';
    if (message === '未找到滑块验证任务') {
      pollError.value = '';
      return;
    }
    if (!pollError.value) {
      showError(message);
    }
    pollError.value = message;
    schedulePolling();
  }
};

const handleTaskStatus = (status: CaptchaTaskStatus) => {
  taskStatus.value = status;
  selectedMode.value = status.mode;
  clearPolling();
  if (status.status === 'SUCCEEDED') {
    showSuccess(status.message || '滑块验证完成，连接已恢复');
    emit('success');
    handleClose();
    return;
  }
  if (status.status === 'FAILED'
      || status.status === 'TIMEOUT'
      || status.status === 'UNSUPPORTED') {
    showError(status.message || '滑块验证未完成');
    return;
  }
  if (status.status === 'CANCELLED') {
    showSuccess(status.message || '滑块验证已取消');
    return;
  }
  schedulePolling();
};

const handleCancel = async () => {
  if (!props.accountId || !taskRunning.value || cancelling.value) return;
  cancelling.value = true;
  try {
    const response = await cancelCaptcha(props.accountId);
    if (response.code !== 0 && response.code !== 200) {
      throw new Error(response.msg || '滑块验证任务取消失败');
    }
    if (!response.data) {
      throw new Error('滑块验证任务状态为空');
    }
    pollError.value = '';
    handleTaskStatus(response.data);
  } catch (error: any) {
    showError(error.message || '滑块验证任务取消失败');
  } finally {
    cancelling.value = false;
  }
};

async function resumeActiveTask() {
  if (!props.accountId) return;
  try {
    const response = await getCaptchaStatus(props.accountId);
    if ((response.code === 0 || response.code === 200)
        && response.data
        && (response.data.status === 'PENDING' || response.data.status === 'RUNNING')) {
      pollError.value = '';
      handleTaskStatus(response.data);
    }
  } catch (error: any) {
    const message = error.message || '滑块验证状态查询失败';
    if (message === '未找到滑块验证任务') {
      pollError.value = '';
      return;
    }
    if (!pollError.value) {
      showError(message);
    }
    pollError.value = message;
    clearPolling();
    if (props.modelValue && !taskRunning.value) {
      pollTimer = setTimeout(resumeActiveTask, 2000);
    }
  }
}

function schedulePolling() {
  clearPolling();
  if (taskRunning.value && props.modelValue) {
    pollTimer = setTimeout(pollStatus, 2000);
  }
}

function clearPolling() {
  if (pollTimer) {
    clearTimeout(pollTimer);
    pollTimer = null;
  }
}

function startClock() {
  stopClock();
  now.value = Date.now();
  clockTimer = setInterval(() => {
    now.value = Date.now();
  }, 1000);
}

function stopClock() {
  if (clockTimer) {
    clearInterval(clockTimer);
    clockTimer = null;
  }
}

onBeforeUnmount(() => {
  clearPolling();
  stopClock();
});
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="modelValue" class="modal-overlay" @click.self="handleClose">
        <div class="modal-container">
          <div class="modal-header">
            <div>
              <h2 class="modal-title">需要滑块验证</h2>
              <p class="modal-subtitle">请选择自动拖动、人工拖动或粘贴 Cookie</p>
            </div>
            <button class="modal-close" type="button" aria-label="关闭" @click="handleClose">×</button>
          </div>

          <div class="modal-body">
            <div class="captcha-options">
              <label class="captcha-option" :class="{ 'captcha-option--active': selectedMode === 'AUTO' }">
                <input v-model="selectedMode" type="radio" value="AUTO" :disabled="running">
                <span>
                  <strong>全自动拖动</strong>
                  <small>后台识别滑块并模拟拖动，成功后自动回收 Cookie 和重连</small>
                </span>
              </label>
              <label class="captcha-option" :class="{ 'captcha-option--active': selectedMode === 'MANUAL_BROWSER' }">
                <input v-model="selectedMode" type="radio" value="MANUAL_BROWSER" :disabled="running">
                <span>
                  <strong>人工拖动</strong>
                  <small>本机打开可视浏览器，人工完成后自动回收 Cookie 和重连</small>
                </span>
              </label>
              <label class="captcha-option" :class="{ 'captcha-option--active': selectedMode === 'COOKIE' }">
                <input v-model="selectedMode" type="radio" value="COOKIE" :disabled="running">
                <span>
                  <strong>粘贴 Cookie</strong>
                  <small>保留现有手动更新方式，保存后自动刷新凭证并重连</small>
                </span>
              </label>
            </div>
            <div v-if="taskStatus" class="captcha-status" :data-status="taskStatus.status">
              <span>{{ statusText }}</span>
              <p>{{ taskStatus.message }}</p>
              <div class="captcha-progress">
                <span>阶段：{{ phaseText }}</span>
                <span v-if="attemptText">{{ attemptText }}</span>
                <span>已用时：{{ elapsedSeconds }} 秒</span>
                <span v-if="taskRunning">剩余：{{ remainingSeconds }} 秒</span>
                <span>状态更新：{{ updatedSecondsAgo }} 秒前</span>
              </div>
              <p v-if="pollError" class="captcha-poll-error">
                状态查询暂时失败，正在继续重试：{{ pollError }}
              </p>
            </div>
            <p class="captcha-tip">人工浏览器仅支持可显示桌面的本地环境；服务器无界面时可改用粘贴 Cookie。</p>
          </div>

          <div class="modal-footer">
            <button
              class="btn btn-secondary"
              type="button"
              :disabled="cancelling"
              @click="taskRunning ? handleCancel() : handleClose()"
            >
              {{ taskRunning ? (cancelling ? '正在终止' : '终止验证') : '取消' }}
            </button>
            <button class="btn btn-primary" type="button" :disabled="running" @click="handleAction">
              {{ actionText }}
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

.captcha-options {
  display: grid;
  gap: 10px;
}

.captcha-option {
  display: flex;
  gap: 10px;
  padding: 12px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  cursor: pointer;
}

.captcha-option--active {
  border-color: #2563eb;
  background: #eff6ff;
}

.captcha-option input {
  margin-top: 3px;
}

.captcha-option span {
  display: grid;
  gap: 4px;
}

.captcha-option strong {
  color: #111827;
  font-size: 14px;
}

.captcha-option small {
  color: #64748b;
  font-size: 12px;
  line-height: 1.5;
}

.captcha-status {
  margin-top: 14px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f1f5f9;
}

.captcha-status span {
  color: #2563eb;
  font-size: 12px;
  font-weight: 600;
}

.captcha-status p {
  margin: 4px 0 0;
  color: #475569;
  font-size: 13px;
}

.captcha-progress {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 12px;
  margin-top: 8px;
}

.captcha-progress span {
  color: #64748b;
  font-size: 12px;
  font-weight: 400;
}

.captcha-status .captcha-poll-error {
  color: #dc2626;
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

.btn:disabled {
  cursor: not-allowed;
  opacity: 0.58;
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
