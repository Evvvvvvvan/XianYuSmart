import { request } from '@/utils/request'

export interface HealthCheck {
  key: string
  name: string
  count: number
  status: 'HEALTHY' | 'WARNING'
  action: string
}

export interface HealthOverview {
  overallStatus: 'HEALTHY' | 'WARNING' | 'CRITICAL'
  criticalCount: number
  warningCount: number
  checks: HealthCheck[]
}

export interface OperationException {
  exceptionType: string
  accountId?: number
  targetId?: string
  title: string
  reason: string
  status: string
  occurredAt: string
}

export interface NotificationChannel {
  id: number
  channelName: string
  webhookUrl: string
  secretConfigured: boolean
  eventTypes: string[]
  enabled: boolean
  lastSuccessTime?: string
  lastErrorMessage?: string
  updateTime?: string
}

export interface NotificationLog {
  id: number
  channelId?: number
  eventType: string
  xianyuAccountId?: number
  title: string
  sendStatus: number
  httpStatus?: number
  errorMessage?: string
  createTime: string
}

export const getHealthOverview = () => request<HealthOverview>({
  url: '/diagnostics/overview',
  method: 'GET'
})

export const getOperationExceptions = () => request<OperationException[]>({
  url: '/diagnostics/exceptions',
  method: 'GET'
})

export const getNotificationChannels = () => request<NotificationChannel[]>({
  url: '/notifications/channels',
  method: 'GET'
})

export const saveNotificationChannel = (data: {
  id?: number
  channelName: string
  webhookUrl: string
  signingSecret?: string
  eventTypes: string[]
  enabled: boolean
}) => request<NotificationChannel>({
  url: '/notifications/channels',
  method: 'POST',
  data
})

export const deleteNotificationChannel = (id: number) => request<void>({
  url: `/notifications/channels/${id}`,
  method: 'DELETE'
})

export const testNotificationChannel = (id: number) => request<{ httpStatus: number; message: string }>({
  url: `/notifications/channels/${id}/test`,
  method: 'POST'
})

export const getNotificationLogs = () => request<NotificationLog[]>({
  url: '/notifications/logs',
  method: 'GET'
})
