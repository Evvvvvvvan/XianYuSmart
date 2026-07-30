import { request } from '@/utils/request'

export type UserRole = 'ADMIN' | 'USER'

export interface CurrentUser {
  username: string
  role: UserRole
  permissions: string[]
  menuLayout?: string
  lastLoginTime: string
}

export interface SystemUpdateStatus {
  available: boolean
  requestPending: boolean
  active: boolean
  canRetry: boolean
  taskId?: string
  version?: string
  status: 'IDLE' | 'REQUESTED' | 'CHECKING' | 'DOWNLOADING' | 'VERIFYING'
    | 'INSTALLING' | 'RESTARTING' | 'HEALTH_CHECKING' | 'SUCCESS' | 'FAILED'
  progress: number
  message?: string
  downloadedBytes: number
  totalBytes: number
  requestedAt?: string
  updatedAt?: string
}

/** 获取当前用户信息 */
export function getCurrentUser() {
  return request<CurrentUser>({
    url: '/system/currentUser',
    method: 'post'
  })
}

/** 修改密码 */
export function changePassword(data: { oldPassword: string; newPassword: string; confirmPassword: string }) {
  return request<null>({
    url: '/system/changePassword',
    method: 'post',
    data
  })
}

/** 获取当前版本号 */
export function getVersion() {
  return request<string>({
    url: '/system/version',
    method: 'get'
  })
}

/** 检查更新 */
export function checkUpdate() {
  return request<{
    currentVersion: string
    latestVersion: string
    hasUpdate: boolean
    updateContent: string
    publishedAt: string
    downloadUrl: string
  }>({
    url: '/system/checkUpdate',
    method: 'get'
  })
}

/** 请求服务器自动更新到最新正式版本 */
export function requestSystemUpdate() {
  return request<SystemUpdateStatus>({
    url: '/system/update',
    method: 'post'
  })
}

/** 获取当前自动更新任务状态 */
export function getSystemUpdateStatus() {
  return request<SystemUpdateStatus>({
    url: '/system/update/status',
    method: 'get'
  })
}
