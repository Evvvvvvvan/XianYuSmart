import { ref, reactive } from 'vue'
import { getDashboardStats } from '@/api/dashboard'

export function useDashboard() {
  const loading = ref(false)
  
  const stats = reactive({
    accountCount: 0,
    itemCount: 0,
    sellingItemCount: 0,
    offShelfItemCount: 0,
    soldItemCount: 0,
    todayRevenue: 0,
    todayDeliveryCount: 0,
    todayReplyCount: 0,
    pendingTaskCount: 0,
    reviewRequiredCount: 0,
    failedTaskCount: 0,
    availableKamiCount: 0,
    lowStockConfigCount: 0
  })

  const loadStatistics = async () => {
    if (loading.value) return
    loading.value = true
    try {
      const res = await getDashboardStats()
      if (res.code === 0 || res.code === 200) {
        if (res.data) {
          Object.assign(stats, res.data)
        }
      }
    } catch {
      // 请求层统一展示错误
    } finally {
      loading.value = false
    }
  }

  return {
    loading,
    stats,
    loadStatistics
  }
}
