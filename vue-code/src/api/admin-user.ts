import { request } from '@/utils/request'
import type { UserRole } from '@/api/system'

export interface PlatformUser {
  id: number
  username: string
  role: UserRole
  status: number
  permissions: string[]
  lastLoginTime?: string
  lastLoginIp?: string
  createdTime?: string
}

export interface PlatformUserList {
  records: PlatformUser[]
  total: number
  activeCount: number
  adminCount: number
}

export interface PermissionOption {
  code: string
  label: string
  group: string
  type: 'MENU' | 'ACTION'
}

export function getPlatformUsers() {
  return request<PlatformUserList>({
    url: '/admin/users/list',
    method: 'POST'
  })
}

export function getPermissionOptions() {
  return request<PermissionOption[]>({
    url: '/admin/users/permissions',
    method: 'POST'
  })
}

export function savePlatformUser(data: {
  id?: number
  username?: string
  password?: string
  role: UserRole
  status: number
  permissions: string[]
}) {
  return request<PlatformUser>({
    url: '/admin/users/save',
    method: 'POST',
    data
  })
}

export function resetPlatformUserPassword(data: { userId: number; newPassword: string }) {
  return request<null>({
    url: '/admin/users/resetPassword',
    method: 'POST',
    data
  })
}
