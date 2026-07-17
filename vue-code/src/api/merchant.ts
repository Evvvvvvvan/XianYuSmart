import { request } from '@/utils/request'

export type ResourceType = 'ADDRESS' | 'MATERIAL' | 'SUPPLY' | 'PROMOTION_ACCOUNT' | 'SELECTION_RULE' | 'PUBLISH_RULE' | 'DELETE_RULE' | 'ANNOUNCEMENT' | 'FEEDBACK' | 'RISK_EVENT'

export interface MerchantResource {
  id: number
  resourceType: ResourceType
  name: string
  status: number
  xianyuAccountId?: number
  xyGoodsId?: string
  stock: number
  amount: number
  scheduledTime?: string
  lastRunTime?: string
  data: Record<string, any>
  createdTime: string
  updatedTime: string
}

export interface MerchantTask {
  id: number
  taskType: string
  resourceId?: number
  xianyuAccountId?: number
  xyGoodsId?: string
  status: number
  scheduledTime: string
  attemptCount: number
  maxAttempts: number
  resultJson?: string
  errorMessage?: string
  createdTime: string
}

export interface MerchantDistribution {
  id: number
  supplyResourceId: number
  materialResourceId?: number
  xianyuAccountId?: number
  xyGoodsId?: string
  status: number
  commissionAmount: number
  settlementStatus: number
  settlementTime?: string
  createdTime: string
}

export interface MerchantOverview {
  resourceCounts: Partial<Record<ResourceType, number>>
  taskCount: number
  failedTaskCount: number
}

export function getMerchantOverview() {
  return request<MerchantOverview>({ url: '/merchant/overview', method: 'GET' })
}

export function getResources(type: ResourceType, status?: number) {
  return request<MerchantResource[]>({ url: '/merchant/resources', method: 'GET', params: { type, status } })
}

export function saveResource(data: Partial<MerchantResource>) {
  return request<MerchantResource>({ url: '/merchant/resources', method: 'POST', data })
}

export function deleteResource(id: number) {
  return request<void>({ url: `/merchant/resources/${id}`, method: 'DELETE' })
}

export function executeResource(id: number) {
  return request<MerchantTask>({ url: `/merchant/resources/${id}/execute`, method: 'POST' })
}

export function compensateResource(id: number) {
  return request<MerchantTask>({ url: `/merchant/resources/${id}/compensate`, method: 'POST' })
}

export function convertSupplyToMaterial(id: number) {
  return request<MerchantResource>({ url: `/merchant/supplies/${id}/material`, method: 'POST' })
}

export function getTasks(params: { taskType?: string; status?: number; limit?: number } = {}) {
  return request<MerchantTask[]>({ url: '/merchant/tasks', method: 'GET', params })
}

export function requeueTask(id: number) {
  return request<void>({ url: `/merchant/tasks/${id}/requeue`, method: 'POST' })
}

export function batchPublish(resourceIds: number[], xianyuAccountId?: number) {
  return request<MerchantTask[]>({ url: '/merchant/tasks/batch-publish', method: 'POST', data: { resourceIds, xianyuAccountId } })
}

export function getDistributions(params: { status?: number; settlementStatus?: number; limit?: number } = {}) {
  return request<MerchantDistribution[]>({ url: '/merchant/distributions', method: 'GET', params })
}

export function settleDistribution(id: number) {
  return request<void>({ url: `/merchant/distributions/${id}/settle`, method: 'POST' })
}
