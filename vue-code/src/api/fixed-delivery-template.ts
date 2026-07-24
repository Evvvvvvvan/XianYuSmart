import { request } from '@/utils/request'

export interface FixedDeliveryTemplate {
  id: number
  xianyuAccountId: number
  templateName: string
  deliveryContent: string
  messageTemplate: string
  createTime: string
  updateTime: string
}

export interface SaveFixedDeliveryTemplateReq {
  id?: number
  xianyuAccountId: number
  templateName: string
  deliveryContent: string
  messageTemplate: string
}

export function getFixedDeliveryTemplates(xianyuAccountId: number) {
  return request<FixedDeliveryTemplate[]>({
    url: '/fixed-delivery-template/list',
    method: 'GET',
    params: { xianyuAccountId }
  })
}

export function saveFixedDeliveryTemplate(data: SaveFixedDeliveryTemplateReq) {
  return request<FixedDeliveryTemplate>({
    url: '/fixed-delivery-template/save',
    method: 'POST',
    data
  })
}

export function deleteFixedDeliveryTemplate(xianyuAccountId: number, id: number) {
  return request<void>({
    url: '/fixed-delivery-template/delete',
    method: 'POST',
    params: { xianyuAccountId, id }
  })
}
