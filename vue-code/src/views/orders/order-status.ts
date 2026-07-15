export const deliveryStatusOptions = [
  { value: '', label: '全部状态' },
  { value: 'PENDING,PROCESSING,RETRY_WAIT', label: '等待履约' },
  { value: 'REVIEW_REQUIRED', label: '需要人工核对' },
  { value: 'FAILED', label: '履约失败' },
  { value: 'DELIVERED', label: '已交付' },
  { value: 'CONFIRMING', label: '确认中' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'SKIPPED', label: '已跳过' }
] as const

const statusMeta: Record<string, { text: string; color: string; background: string }> = {
  PENDING: { text: '等待处理', color: '#0A84FF', background: 'rgba(10,132,255,.14)' },
  PROCESSING: { text: '正在处理', color: '#0A84FF', background: 'rgba(10,132,255,.14)' },
  RETRY_WAIT: { text: '等待重试', color: '#FF9F0A', background: 'rgba(255,159,10,.18)' },
  DELIVERED: { text: '已交付', color: '#30D158', background: 'rgba(48,209,88,.2)' },
  CONFIRMING: { text: '确认中', color: '#0A84FF', background: 'rgba(10,132,255,.14)' },
  COMPLETED: { text: '已完成', color: '#30D158', background: 'rgba(48,209,88,.2)' },
  FAILED: { text: '履约失败', color: '#FF453A', background: 'rgba(255,69,58,.15)' },
  REVIEW_REQUIRED: { text: '需要核对', color: '#FF9F0A', background: 'rgba(255,159,10,.18)' },
  SKIPPED: { text: '已跳过', color: '#8E8E93', background: 'rgba(120,120,128,.12)' }
}

export const parseDeliveryStatuses = (value: unknown): string[] => {
  const raw = Array.isArray(value) ? value[0] : value
  if (typeof raw !== 'string') return []
  return [...new Set(raw.split(',').map(item => item.trim()).filter(item => statusMeta[item]))]
}

export const getDeliveryStatusMeta = (deliveryStatus: string | undefined, state: number) => {
  if (deliveryStatus && statusMeta[deliveryStatus]) return statusMeta[deliveryStatus]
  if (state === 1) return statusMeta.COMPLETED!
  if (state === 0) return statusMeta.PENDING!
  return statusMeta.FAILED!
}
