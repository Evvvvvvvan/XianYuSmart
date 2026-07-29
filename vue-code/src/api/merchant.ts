import { request } from '@/utils/request'

export type ResourceType = 'ADDRESS' | 'MATERIAL' | 'SUPPLY' | 'PROMOTION_ACCOUNT' | 'SELECTION_RULE' | 'PUBLISH_RULE' | 'DELETE_RULE' | 'ANNOUNCEMENT' | 'FEEDBACK' | 'RISK_EVENT' | 'WORKFLOW'

export interface OpportunityCandidate {
  itemId: string
  title: string
  sourceUrl: string
  description?: string
  images?: string[]
  price?: string | number
  sellerId?: string
  sellerNick?: string
  sellerAvatar?: string
  sellerCredit?: string
  buyerCredit?: string
  sellerPositiveCount?: number
  sellerNeutralCount?: number
  sellerNegativeCount?: number
  opportunityScore: number
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH'
  matchReason: string
}

export interface SellerPublicProfile {
  itemId: string
  sellerId?: string
  sellerNick?: string
  sellerAvatar?: string
  sellerProfileUrl?: string
  sellerCredit?: string
  sellerPositiveCount?: number
  sellerNeutralCount?: number
  sellerNegativeCount?: number
}

export interface OpportunitySearchPage {
  items: OpportunityCandidate[]
  pageNumber: number
  pageSize: number
  hasMore: boolean
  total: number
}

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

export function searchOpportunities(data: {
  keyword: string
  xianyuAccountId?: number
  pageNumber?: number
  limit?: number
}) {
  return request<OpportunitySearchPage>({ url: '/merchant/opportunities/search', method: 'POST', data })
}

export function getSellerPublicProfile(data: { itemId: string; xianyuAccountId: number }) {
  return request<SellerPublicProfile>({
    url: '/merchant/opportunities/seller-profile',
    method: 'POST',
    data,
    silent: true
  })
}

export function crawlShopOpportunities(data: {
  shopUrl: string
  xianyuAccountId?: number
  pageNumber?: number
  limit?: number
}) {
  return request<OpportunitySearchPage>({ url: '/merchant/opportunities/shop', method: 'POST', data })
}

export function importOpportunities(data: { candidates: OpportunityCandidate[]; xianyuAccountId?: number }) {
  return request<MerchantResource[]>({ url: '/merchant/opportunities/import', method: 'POST', data })
}

export function polishOpportunity(data: { title: string; description: string }) {
  return request<{ title: string; description: string }>({
    url: '/merchant/opportunities/polish',
    method: 'POST',
    data
  })
}

export function generateOpportunityImage(data: { xianyuAccountId: number; prompt: string }) {
  return request<{ url: string }>({
    url: '/merchant/opportunities/image',
    method: 'POST',
    data
  })
}

export function createPublishPlan(data: {
  xianyuAccountId: number
  name: string
  description: string
  amount: number
  stock: number
  images: string[]
  category?: string
  province?: string
  city?: string
  district?: string
  divisionId?: string
  gps?: string
  poiId?: string
  poiName?: string
  deliveryMethod?: string
  dryRun?: boolean
}) {
  return request<Record<string, any>>({ url: '/merchant/products/publish', method: 'POST', data })
}
