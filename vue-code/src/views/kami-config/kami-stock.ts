interface StockAlertConfig {
  alertEnabled?: number
  alertThresholdType?: number
  alertThresholdValue?: number
  totalCount: number
  availableCount: number
}

export const isLowStockConfig = (config: StockAlertConfig) => {
  if (config.alertEnabled !== 1) return false
  const threshold = config.alertThresholdValue ?? 10
  if (config.alertThresholdType === 2) {
    return config.totalCount > 0 && config.availableCount * 100 < config.totalCount * threshold
  }
  return config.availableCount < threshold
}
