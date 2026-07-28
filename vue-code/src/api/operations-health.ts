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
  exceptionId: number
  exceptionVersion: number
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
  channelType: NotificationChannelType
  webhookUrl: string
  secretConfigured: boolean
  config: Record<string, string>
  messageTemplate?: string
  eventTypes: string[]
  enabled: boolean
  lastSuccessTime?: string
  lastErrorMessage?: string
  updateTime?: string
}

export type NotificationChannelType =
  'WEBHOOK' | 'WECHAT_WORK' | 'DINGTALK' | 'FEISHU' | 'BARK' | 'PUSHPLUS' | 'TELEGRAM'

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

export const acknowledgeOperationException = (data: Pick<OperationException, 'exceptionType' | 'exceptionId' | 'exceptionVersion'>) =>
  request<void>({
    url: '/diagnostics/exceptions/acknowledge',
    method: 'POST',
    data: {
      exceptionType: data.exceptionType,
      exceptionId: data.exceptionId,
      exceptionVersion: data.exceptionVersion
    }
  })

export const acknowledgeAllOperationExceptions = (items: OperationException[]) => request<number>({
  url: '/diagnostics/exceptions/acknowledge-all',
  method: 'POST',
  data: items.map(item => ({
    exceptionType: item.exceptionType,
    exceptionId: item.exceptionId,
    exceptionVersion: item.exceptionVersion
  }))
})

export const getNotificationChannels = () => request<NotificationChannel[]>({
  url: '/notifications/channels',
  method: 'GET'
})

export const saveNotificationChannel = (data: {
  id?: number
  channelName: string
  channelType: NotificationChannelType
  config: Record<string, string>
  messageTemplate: string
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
