<script setup lang="ts">
import { ref, computed, onUnmounted } from 'vue'
import {
  checkUpdate,
  getSystemUpdateStatus,
  getVersion,
  requestSystemUpdate,
  type SystemUpdateStatus
} from '@/api/system'
import IconClose from '@/components/icons/IconClose.vue'
import IconCheck from '@/components/icons/IconCheck.vue'
import IconSparkle from '@/components/icons/IconSparkle.vue'

declare const __APP_VERSION__: string

const appVersion = __APP_VERSION__ || '2.0.6'

const visible = ref(false)
const loading = ref(false)
const submitting = ref(false)
const localError = ref('')
const updateTask = ref<SystemUpdateStatus | null>(null)
const updateInfo = ref<{
  currentVersion: string
  latestVersion: string
  hasUpdate: boolean
  updateContent: string
  publishedAt: string
  downloadUrl: string
} | null>(null)

const isMobile = ref(false)

const checkMobile = () => {
  isMobile.value = window.innerWidth < 768
}

const isUpdateAvailable = computed(() => {
  return updateInfo.value?.hasUpdate === true
})

const isUpdateRunning = computed(() => updateTask.value?.active === true)

const progressPercent = computed(() => {
  const progress = Number(updateTask.value?.progress || 0)
  return Math.min(100, Math.max(0, progress))
})

const stageLabels: Record<SystemUpdateStatus['status'], string> = {
  IDLE: '等待更新',
  REQUESTED: '任务已提交',
  CHECKING: '检查版本',
  DOWNLOADING: '下载更新',
  VERIFYING: '校验文件',
  INSTALLING: '备份并安装',
  RESTARTING: '重启服务',
  HEALTH_CHECKING: '健康检查',
  SUCCESS: '更新完成',
  FAILED: '更新失败'
}

const stageLabel = computed(() => {
  return updateTask.value ? stageLabels[updateTask.value.status] : ''
})

const formatBytes = (bytes: number) => {
  if (!bytes) return '0 B'
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

const downloadText = computed(() => {
  const task = updateTask.value
  if (!task || !task.totalBytes) return ''
  return `${formatBytes(task.downloadedBytes)} / ${formatBytes(task.totalBytes)}`
})

const taskUpdatedAt = computed(() => {
  if (!updateTask.value?.updatedAt) return ''
  return new Date(updateTask.value.updatedAt).toLocaleString()
})

const formattedDate = computed(() => {
  if (!updateInfo.value?.publishedAt) return ''
  const d = new Date(updateInfo.value.publishedAt)
  return `${d.getFullYear()}.${String(d.getMonth() + 1).padStart(2, '0')}.${String(d.getDate()).padStart(2, '0')}`
})

let pollTimer: number | undefined
let dialogSession = 0

const stopPolling = () => {
  if (pollTimer !== undefined) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
}

const reloadAfterSuccessfulUpdate = async () => {
  const task = updateTask.value
  if (!visible.value || task?.status !== 'SUCCESS' || !task.version || appVersion === task.version) return
  const versionResponse = await getVersion()
  if (visible.value && versionResponse.data === task.version) {
    window.location.reload()
  }
}

const refreshTask = async () => {
  if (!visible.value) {
    stopPolling()
    return
  }
  try {
    const response = await getSystemUpdateStatus()
    if (!response.data) return
    updateTask.value = response.data
    if (!response.data.active) {
      stopPolling()
    }
    await reloadAfterSuccessfulUpdate()
  } catch {
    // 应用重启期间继续保留轮询，服务恢复后自动读取持久任务状态
  }
}

const startPolling = () => {
  if (!visible.value || pollTimer !== undefined) return
  pollTimer = window.setInterval(refreshTask, 2000)
}

const open = async () => {
  const session = ++dialogSession
  checkMobile()
  visible.value = true
  loading.value = true
  localError.value = ''
  try {
    const [versionResult, statusResult] = await Promise.allSettled([
      checkUpdate(),
      getSystemUpdateStatus()
    ])
    if (!visible.value || session !== dialogSession) return
    updateInfo.value = versionResult.status === 'fulfilled' ? versionResult.value.data || null : null
    updateTask.value = statusResult.status === 'fulfilled' ? statusResult.value.data || null : null
    if (!updateInfo.value && updateTask.value && updateTask.value.status !== 'IDLE') {
      updateInfo.value = {
        currentVersion: appVersion,
        latestVersion: updateTask.value.version || appVersion,
        hasUpdate: updateTask.value.status !== 'SUCCESS',
        updateContent: '',
        publishedAt: '',
        downloadUrl: ''
      }
    }
    if (!updateInfo.value) {
      throw new Error('检查更新失败')
    }
    if (updateTask.value?.active) {
      startPolling()
    } else {
      await reloadAfterSuccessfulUpdate()
    }
  } catch {
    if (session === dialogSession) {
      updateInfo.value = null
    }
  } finally {
    if (session === dialogSession) {
      loading.value = false
    }
  }
}

const close = () => {
  visible.value = false
  dialogSession++
  stopPolling()
}

const startUpdate = async () => {
  if (submitting.value || isUpdateRunning.value || !updateInfo.value?.hasUpdate) return
  const session = dialogSession
  submitting.value = true
  localError.value = ''
  try {
    const response = await requestSystemUpdate()
    if (visible.value && session === dialogSession && response.data) {
      updateTask.value = response.data
      startPolling()
    } else if (visible.value && session !== dialogSession) {
      await refreshTask()
      if (updateTask.value?.active) {
        startPolling()
      }
    }
  } catch (error: any) {
    if (visible.value && session === dialogSession) {
      localError.value = error.message || '自动更新提交失败'
    }
  } finally {
    submitting.value = false
  }
}

onUnmounted(stopPolling)

defineExpose({ open })
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="visible" class="modal-overlay" @click.self="close">
        <div class="modal-container" :class="{ 'is-mobile': isMobile }">
          <!-- Header -->
          <div class="modal-header">
            <div class="modal-title-wrap">
              <div class="modal-icon">
                <IconSparkle />
              </div>
              <h2 class="modal-title">版本更新</h2>
            </div>
            <button class="modal-close" @click="close">
              <IconClose />
            </button>
          </div>

          <!-- Loading -->
          <div v-if="loading" class="modal-loading">
            <div class="loading-spinner"></div>
            <span>正在检查更新...</span>
          </div>

          <!-- Content -->
          <div v-else-if="updateInfo" class="modal-body">
            <!-- Version Info - 横向紧凑布局 -->
            <div class="version-row">
              <div class="version-item">
                <span class="version-label">当前版本</span>
                <span class="version-value">v{{ appVersion }}</span>
              </div>
              <div class="version-item">
                <span class="version-label">最新版本</span>
                <span class="version-value" :class="{ 'is-new': isUpdateAvailable }">v{{ updateInfo.latestVersion }}</span>
              </div>
              <div v-if="formattedDate" class="version-item">
                <span class="version-label">发布时间</span>
                <span class="version-value">{{ formattedDate }}</span>
              </div>
            </div>

            <!-- Status Badge -->
            <div class="status-badge" :class="{ 'is-updated': !isUpdateAvailable }">
              <IconCheck v-if="!isUpdateAvailable" />
              <span>{{ isUpdateAvailable ? '发现新版本' : '已是最新版本' }}</span>
            </div>
            <div
              v-if="updateTask && updateTask.status !== 'IDLE'"
              class="update-progress"
              :class="{
                'is-failed': updateTask.status === 'FAILED',
                'is-success': updateTask.status === 'SUCCESS'
              }"
            >
              <div class="progress-heading">
                <span>{{ stageLabel }}</span>
                <strong>{{ progressPercent }}%</strong>
              </div>
              <div class="progress-track">
                <span :style="{ width: `${progressPercent}%` }"></span>
              </div>
              <div class="progress-message">{{ updateTask.message }}</div>
              <div v-if="downloadText || taskUpdatedAt" class="progress-meta">
                <span v-if="downloadText">{{ downloadText }}</span>
                <span v-if="taskUpdatedAt">更新于 {{ taskUpdatedAt }}</span>
              </div>
            </div>
            <div v-if="localError" class="update-progress is-failed">{{ localError }}</div>

            <!-- Changelog -->
            <div v-if="updateInfo.updateContent" class="changelog">
              <div class="changelog-title">更新内容</div>
              <div class="changelog-content">{{ updateInfo.updateContent }}</div>
            </div>
          </div>

          <!-- Error -->
          <div v-else class="modal-error">
            <span>检查更新失败，请稍后重试</span>
          </div>

          <!-- Footer -->
          <div v-if="!loading && updateInfo" class="modal-footer">
            <button class="btn btn-secondary" @click="close">关闭</button>
            <button
              v-if="isUpdateAvailable"
              class="btn btn-primary"
              :disabled="submitting || isUpdateRunning"
              @click="startUpdate"
            >
              {{
                submitting
                  ? '正在提交'
                  : isUpdateRunning
                    ? '更新进行中'
                    : updateTask?.status === 'FAILED'
                      ? '重新尝试'
                      : '立即更新'
              }}
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
  background: rgba(0, 0, 0, 0.4);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
  padding: 24px;
}

.modal-container {
  background: #ffffff;
  border: 1px solid #e4e7ec;
  border-radius: 8px;
  width: 100%;
  max-width: 540px;
  aspect-ratio: 4 / 3;
  max-height: 88vh;
  box-shadow:
    0 32px 100px rgba(0, 0, 0, 0.14),
    0 12px 32px rgba(0, 0, 0, 0.1);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.modal-container.is-mobile {
  max-width: 400px;
  aspect-ratio: 9 / 16;
  border-radius: 8px;
}

/* Header - 紧凑 */
.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  flex-shrink: 0;
}

.modal-title-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
}

.modal-icon {
  width: 28px;
  height: 28px;
  background: #0071e3;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.modal-icon svg {
  width: 15px;
  height: 15px;
  color: #fff;
}

.modal-title {
  font-size: 15px;
  font-weight: 600;
  color: #1d1d1f;
  margin: 0;
  letter-spacing: -0.01em;
}

.modal-close {
  width: 26px;
  height: 26px;
  border-radius: 7px;
  border: none;
  background: transparent;
  color: #86868b;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s ease;
}

.modal-close:hover {
  background: rgba(0, 0, 0, 0.06);
  color: #1d1d1f;
}

.modal-close svg {
  width: 12px;
  height: 12px;
}

/* Loading */
.modal-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding: 60px 24px;
  color: #86868b;
  font-size: 14px;
  flex: 1;
}

.loading-spinner {
  width: 28px;
  height: 28px;
  border: 2px solid #f5f5f7;
  border-top-color: #0071e3;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* Body - 主要内容区域 */
.modal-body {
  padding: 0 24px 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  flex: 1;
  overflow-y: auto;
}

.version-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 14px 18px;
  background: #f5f5f7;
  border-radius: 6px;
}

.version-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.version-item:not(:last-child)::after {
  content: '';
  width: 1px;
  height: 20px;
  background: rgba(0, 0, 0, 0.08);
  margin-left: 8px;
}

.version-label {
  font-size: 12px;
  color: #86868b;
  font-weight: 500;
}

.version-value {
  font-size: 14px;
  color: #1d1d1f;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.version-value.is-new {
  color: #0071e3;
}

/* Status Badge */
.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  align-self: flex-start;
  padding: 7px 14px;
  background: rgba(0, 113, 227, 0.08);
  border-radius: 100px;
  font-size: 13px;
  font-weight: 500;
  color: #0071e3;
}

.update-progress {
  margin-top: 12px;
  padding: 12px 14px;
  border-radius: 6px;
  color: #155eef;
  background: #eef4ff;
  font-size: 13px;
}

.progress-heading,
.progress-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.progress-heading strong {
  font-variant-numeric: tabular-nums;
}

.progress-track {
  height: 6px;
  margin: 10px 0;
  overflow: hidden;
  border-radius: 99px;
  background: rgba(21, 94, 239, .14);
}

.progress-track span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: #155eef;
  transition: width .25s ease;
}

.progress-message {
  color: #344054;
  line-height: 1.5;
}

.progress-meta {
  margin-top: 6px;
  color: #667085;
  font-size: 12px;
}

.update-progress.is-failed {
  color: #b42318;
  background: #fef3f2;
}

.update-progress.is-failed .progress-track {
  background: rgba(180, 35, 24, .12);
}

.update-progress.is-failed .progress-track span {
  background: #d92d20;
}

.update-progress.is-success {
  color: #067647;
  background: #ecfdf3;
}

.update-progress.is-success .progress-track {
  background: rgba(6, 118, 71, .12);
}

.update-progress.is-success .progress-track span {
  background: #12b76a;
}

.status-badge svg {
  width: 14px;
  height: 14px;
}

.status-badge.is-updated {
  background: rgba(52, 199, 89, 0.1);
  color: #34c759;
}

/* Changelog */
.changelog {
  margin-top: 2px;
}

.changelog-title {
  font-size: 13px;
  color: #86868b;
  font-weight: 500;
  margin-bottom: 10px;
}

.changelog-content {
  font-size: 14px;
  color: #1d1d1f;
  line-height: 1.6;
  white-space: pre-wrap;
  background: #f5f5f7;
  padding: 16px 18px;
  border-radius: 12px;
  max-height: 160px;
  overflow-y: auto;
}

.changelog-content::-webkit-scrollbar {
  width: 5px;
}

.changelog-content::-webkit-scrollbar-track {
  background: transparent;
}

.changelog-content::-webkit-scrollbar-thumb {
  background: rgba(0, 0, 0, 0.12);
  border-radius: 3px;
}

/* Error */
.modal-error {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 60px 24px;
  color: #86868b;
  font-size: 14px;
  flex: 1;
}

/* Footer - 紧凑 */
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 20px;
  flex-shrink: 0;
}

.btn {
  padding: 8px 18px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
  border: none;
}

.btn-secondary {
  background: rgba(0, 0, 0, 0.06);
  color: #1d1d1f;
}

.btn-secondary:hover {
  background: rgba(0, 0, 0, 0.1);
}

.btn-primary {
  background: #0071e3;
  color: #fff;
}

.btn-primary:hover {
  background: #0077ed;
}

/* Transitions */
.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.2s ease;
}

.modal-enter-active .modal-container,
.modal-leave-active .modal-container {
  transition: transform 0.3s cubic-bezier(0.32, 0.94, 0.6, 1), opacity 0.2s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-from .modal-container,
.modal-leave-to .modal-container {
  transform: scale(0.92) translateY(8px);
  opacity: 0;
}

/* Mobile */
@media (max-width: 480px) {
  .modal-container {
    max-width: 92vw;
    border-radius: 16px;
  }
  .modal-container.is-mobile {
    aspect-ratio: 9 / 16;
  }
  .modal-body {
    padding: 0 20px 20px;
    gap: 14px;
  }
  .version-row {
    flex-wrap: wrap;
    gap: 10px;
    padding: 12px 14px;
  }
  .version-item:not(:last-child)::after {
    display: none;
  }
  .changelog-content {
    max-height: 140px;
  }
}
</style>
