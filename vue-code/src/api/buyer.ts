import { request } from '@/utils/request'

export interface BuyerProfile {
  id: number
  xianyuAccountId: number
  buyerUserId: string
  buyerUserName?: string
  tags: string[]
  note?: string
  automationBlocked: boolean
  blockedReason?: string
  messageCount: number
  orderCount: number
  totalAmount: string
  lastInteractionTime?: string
}

export interface BuyerProfilePage {
  records: BuyerProfile[]
  total: number
  pageNum: number
  pageSize: number
}

export function getBuyerProfiles(data: {
  xianyuAccountId?: number
  keyword?: string
  automationBlocked?: boolean
  pageNum?: number
  pageSize?: number
}) {
  return request<BuyerProfilePage>({
    url: '/buyers/list',
    method: 'POST',
    data
  })
}

export function saveBuyerProfile(data: {
  xianyuAccountId: number
  buyerUserId: string
  buyerUserName?: string
  tags?: string[]
  note?: string
  automationBlocked?: boolean
  blockedReason?: string
}) {
  return request<BuyerProfile>({
    url: '/buyers/save',
    method: 'POST',
    data
  })
}
