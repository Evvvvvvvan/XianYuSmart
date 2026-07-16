import { ref, reactive, computed, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { getAccountList } from '@/api/account'
import {
  getGoodsList,
  refreshGoods,
  getGoodsDetail,
  updateAutoDeliveryStatus,
  updateAutoReplyStatus,
  updateGoodsAutomationStatus,
  deleteItem,
  syncSingleItem,
  updateGoodsInfo,
  getSyncProgress,
  checkSyncing
} from '@/api/goods'
import { showSuccess, showError, showInfo, showConfirm } from '@/utils'
import { getGoodsStatusText, formatPrice, formatTime } from '@/utils'
import type { Account } from '@/types'
import type { GoodsItemWithConfig, SyncProgressResponse } from '@/api/goods'
import type { GoodsEditForm } from './goods-edit'
import { resolvePlatformItemUrl } from './goods-edit'

export function useGoodsManager() {
  const router = useRouter()

  const loading = ref(false)
  const refreshing = ref(false)
  const accounts = ref<Account[]>([])
  const selectedAccountId = ref<number | null>(null)
  const statusFilter = ref<string>('')
  const goodsList = ref<GoodsItemWithConfig[]>([])
  const currentPage = ref(1)
  const pageSize = ref(20)
  const total = ref(0)

  const dialogs = reactive({
    detail: false,
    edit: false,
    deleteConfirm: false,
    filter: false
  })

  const selectedGoodsId = ref<string>('')
  const selectedGoods = ref<GoodsItemWithConfig | null>(null)
  const editingGoods = ref<GoodsItemWithConfig | null>(null)
  const editSaving = ref(false)
  const deleteTarget = ref<{ id: string; title: string } | null>(null)

  const syncProgress = ref<SyncProgressResponse | null>(null)
  const syncing = ref(false)
  let syncProgressTimer: ReturnType<typeof setInterval> | null = null

  const stopSyncPolling = () => {
    if (syncProgressTimer) {
      clearInterval(syncProgressTimer)
      syncProgressTimer = null
    }
  }

  const pollSyncProgress = async (syncId: string) => {
    try {
      const response = await getSyncProgress(syncId)
      if (response.code === 0 || response.code === 200) {
        if (response.data) {
          syncProgress.value = response.data
          if (response.data.isCompleted || !response.data.isRunning) {
            stopSyncPolling()
            syncing.value = false
            refreshing.value = false
            if (response.data.successCount && response.data.successCount > 0) {
              showSuccess(`详情同步完成: 成功${response.data.successCount}个, 失败${response.data.failedCount}个`)
            }
            await loadGoods()
          }
        }
      }
    } catch (error) {
      console.error('获取同步进度失败:', error)
    }
  }

  const startSyncPolling = (syncId: string) => {
    stopSyncPolling()
    syncing.value = true
    syncProgressTimer = setInterval(() => {
      pollSyncProgress(syncId)
    }, 1000)
  }

  onUnmounted(() => {
    stopSyncPolling()
  })

  // Computed
  const totalPages = computed(() => Math.ceil(total.value / pageSize.value))
  const accountName = computed(() => {
    if (!selectedAccountId.value) return ''
    const acc = accounts.value.find(a => a.id === selectedAccountId.value)
    return acc?.accountNote || acc?.unb || ''
  })

  // 加载账号列表
  const loadAccounts = async () => {
    try {
      const response = await getAccountList()
      if (response.code === 0 || response.code === 200) {
        accounts.value = response.data?.accounts || []
        if (accounts.value.length > 0 && !selectedAccountId.value) {
          selectedAccountId.value = accounts.value[0]?.id || null
          await loadGoods()
        }
      }
    } catch (error: any) {
      console.error('加载账号列表失败:', error)
    }
  }

  // 加载商品列表
  const loadGoods = async () => {
    if (!selectedAccountId.value) {
      showInfo('请先选择账号')
      return
    }

    loading.value = true
    try {
      const params: any = {
        xianyuAccountId: selectedAccountId.value,
        pageNum: currentPage.value,
        pageSize: pageSize.value
      }
      if (statusFilter.value !== '') {
        params.status = parseInt(statusFilter.value)
      }
      const response = await getGoodsList(params)
      if (response.code === 0 || response.code === 200) {
        goodsList.value = response.data?.itemsWithConfig || []
        total.value = response.data?.totalCount || 0
      }
    } catch (error: any) {
      console.error('加载商品列表失败:', error)
      goodsList.value = []
    } finally {
      loading.value = false
    }
  }

  // 刷新商品数据
  const handleRefresh = async () => {
    if (!selectedAccountId.value) {
      showInfo('请先选择账号')
      return
    }
    refreshing.value = true
    try {
      const response = await refreshGoods(selectedAccountId.value)
      if (response.code === 0 || response.code === 200) {
        if (response.data && response.data.success) {
          showSuccess('商品数据刷新成功')
          if (response.data.syncId) {
            startSyncPolling(response.data.syncId)
          } else {
            await loadGoods()
            refreshing.value = false
          }
        } else {
          showError(response.data?.message || '刷新商品数据失败')
          refreshing.value = false
        }
      }
    } catch (error: any) {
      console.error('刷新商品数据失败:', error)
      refreshing.value = false
    }
  }

  // 账号变更
  const handleAccountChange = () => {
    currentPage.value = 1
    loadGoods()
  }

  // 状态筛选
  const handleStatusFilter = () => {
    currentPage.value = 1
    loadGoods()
  }

  // 分页
  const handlePageChange = (page: number) => {
    currentPage.value = page
    loadGoods()
  }

  // 查看详情
  const viewDetail = (xyGoodId: string) => {
    selectedGoodsId.value = xyGoodId
    dialogs.detail = true
  }

  const editGoods = (item: GoodsItemWithConfig) => {
    editingGoods.value = item
    dialogs.edit = true
  }

  const saveGoodsInfo = async (form: GoodsEditForm) => {
    if (!selectedAccountId.value || !editingGoods.value) return
    editSaving.value = true
    try {
      await updateGoodsInfo({
        xianyuAccountId: selectedAccountId.value,
        xyGoodsId: editingGoods.value.item.xyGoodId,
        ...form
      })
      showSuccess('本地商品资料已保存')
      dialogs.edit = false
      editingGoods.value = null
      await loadGoods()
    } catch (error: any) {
      if (!error.messageShown) {
        showError(error.message || '保存本地商品资料失败')
      }
    } finally {
      editSaving.value = false
    }
  }

  const openPlatformGoods = () => {
    if (!editingGoods.value) return
    const item = editingGoods.value.item
    const opened = window.open(resolvePlatformItemUrl(item.detailUrl, item.xyGoodId), '_blank')
    if (opened) {
      opened.opener = null
    } else {
      showError('浏览器阻止了新窗口，请允许弹窗后重试')
    }
  }

  // 配置自动发货
  const configAutoDelivery = (item: GoodsItemWithConfig) => {
    router.push({
      path: '/auto-delivery',
      query: {
        accountId: selectedAccountId.value?.toString(),
        goodsId: item.item.xyGoodId
      }
    })
  }

  // 切换自动发货
  const toggleAutoDelivery = async (item: GoodsItemWithConfig, value: boolean) => {
    if (!selectedAccountId.value) return
    try {
      const response = await updateAutoDeliveryStatus({
        xianyuAccountId: selectedAccountId.value,
        xyGoodsId: item.item.xyGoodId,
        xianyuAutoDeliveryOn: value ? 1 : 0
      })
      if (response.code === 0 || response.code === 200) {
        showSuccess(`自动发货${value ? '开启' : '关闭'}成功`)
        item.xianyuAutoDeliveryOn = value ? 1 : 0
      } else {
        throw new Error(response.msg || '操作失败')
      }
    } catch (error: any) {
      console.error('操作失败:', error)
      item.xianyuAutoDeliveryOn = value ? 0 : 1
    }
  }

  // 切换自动回复
  const toggleAutoReply = async (item: GoodsItemWithConfig, value: boolean) => {
    if (!selectedAccountId.value) return
    try {
      const response = await updateAutoReplyStatus({
        xianyuAccountId: selectedAccountId.value,
        xyGoodsId: item.item.xyGoodId,
        xianyuAutoReplyOn: value ? 1 : 0
      })
      if (response.code === 0 || response.code === 200) {
        showSuccess(`自动回复${value ? '开启' : '关闭'}成功`)
        item.xianyuAutoReplyOn = value ? 1 : 0
      } else {
        throw new Error(response.msg || '操作失败')
      }
    } catch (error: any) {
      console.error('操作失败:', error)
      item.xianyuAutoReplyOn = value ? 0 : 1
    }
  }

  const updateOperationsAutomation = async (item: GoodsItemWithConfig, autoRate: number, autoPolish: number) => {
    if (!selectedAccountId.value) return
    try {
      const response = await updateGoodsAutomationStatus({
        xianyuAccountId: selectedAccountId.value,
        xyGoodsId: item.item.xyGoodId,
        xianyuAutoRateOn: autoRate,
        xianyuAutoPolishOn: autoPolish
      })
      if (response.code !== 0 && response.code !== 200) {
        throw new Error(response.msg || '操作失败')
      }
      item.xianyuAutoRateOn = autoRate
      item.xianyuAutoPolishOn = autoPolish
      showSuccess('商品自动化设置已更新')
    } catch (error: any) {
      if (!error.messageShown) showError(error.message || '商品自动化设置更新失败')
    }
  }

  const toggleAutoRate = (item: GoodsItemWithConfig, value: boolean) => {
    return updateOperationsAutomation(item, value ? 1 : 0, item.xianyuAutoPolishOn || 0)
  }

  const toggleAutoPolish = (item: GoodsItemWithConfig, value: boolean) => {
    return updateOperationsAutomation(item, item.xianyuAutoRateOn || 0, value ? 1 : 0)
  }

  // 删除商品
  const confirmDelete = (xyGoodId: string, title: string) => {
    deleteTarget.value = { id: xyGoodId, title }
    dialogs.deleteConfirm = true
  }

  const executeDelete = async () => {
    if (!selectedAccountId.value || !deleteTarget.value) return
    try {
      const response = await deleteItem({
        xianyuAccountId: selectedAccountId.value,
        xyGoodsId: deleteTarget.value.id
      })
      if (response.code === 0 || response.code === 200) {
        showSuccess('商品删除成功')
        dialogs.deleteConfirm = false
        deleteTarget.value = null
        await loadGoods()
      } else {
        throw new Error(response.msg || '删除失败')
      }
    } catch (error: any) {
      // 只有在错误消息未显示过时才弹出提示（避免重复显示）
      if (!error.messageShown) {
        showError('删除失败: ' + error.message)
      }
    }
  }

  const syncSingleGoods = async (xyGoodId: string) => {
    if (!selectedAccountId.value) return
    try {
      const response = await syncSingleItem({
        xianyuAccountId: selectedAccountId.value,
        xyGoodsId: xyGoodId
      })
      if (response.code === 0 || response.code === 200) {
        showSuccess('同步成功')
        loadGoods()
      } else {
        throw new Error(response.msg || '同步失败')
      }
    } catch (error: any) {
      console.error('同步失败:', error)
    }
  }

  const syncEditingGoods = async () => {
    if (!editingGoods.value) return
    const xyGoodsId = editingGoods.value.item.xyGoodId
    dialogs.edit = false
    editingGoods.value = null
    await syncSingleGoods(xyGoodsId)
  }

  return {
    loading,
    refreshing,
    syncing,
    syncProgress,
    accounts,
    selectedAccountId,
    statusFilter,
    goodsList,
    currentPage,
    pageSize,
    total,
    totalPages,
    accountName,
    dialogs,
    selectedGoodsId,
    selectedGoods,
    editingGoods,
    editSaving,
    deleteTarget,
    loadAccounts,
    loadGoods,
    handleRefresh,
    handleAccountChange,
    handleStatusFilter,
    handlePageChange,
    viewDetail,
    editGoods,
    saveGoodsInfo,
    openPlatformGoods,
    syncEditingGoods,
    configAutoDelivery,
    toggleAutoDelivery,
    toggleAutoReply,
    toggleAutoRate,
    toggleAutoPolish,
    confirmDelete,
    executeDelete,
    getGoodsStatusText,
    formatPrice,
    formatTime,
    syncSingleGoods
  }
}
