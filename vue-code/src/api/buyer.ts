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

export interface BuyerOrder {
  id: number
  xianyuAccountId: number
  xyGoodsId: string
  orderId?: string
  buyerUserId?: string
  buyerUserName?: string
  sid?: string
  content?: string
  state: number
  failReason?: string
  confirmState: number
  rateStatus: number
  rateTime?: string
  rateContent?: string
  rateSource?: string
  createTime: string
  goodsTitle?: string
  skuName?: string
  orderCreateTime?: string
  totalPrice?: string
  buyNum?: number
  deliveryStatus?: string
}

export interface BuyerMessage {
  id: number
  pnmId: string
  sid?: string
  contentType?: number
  content?: string
  senderUserName?: string
  senderUserId?: string
  xyGoodsId?: string
  messageTime?: number
  createTime?: string
  direction: 'BUYER' | 'SELLER'
  relatedOrderIds: string[]
}

export interface BuyerRelatedGoods {
  xyGoodsId: string
  title?: string
  coverPic?: string
  soldPrice?: string
  orderCount: number
  totalAmount: string
  lastOrderTime?: string
}

export interface BuyerProfileDetail {
  profile: BuyerProfile
  orders: BuyerOrder[]
  messages: BuyerMessage[]
  goods: BuyerRelatedGoods[]
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

export function getBuyerProfileDetail(data: { xianyuAccountId: number; buyerUserId: string }) {
  return request<BuyerProfileDetail>({
    url: '/buyers/detail',
    method: 'POST',
    data
  })
}
