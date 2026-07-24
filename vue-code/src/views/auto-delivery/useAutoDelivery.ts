import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getAccountList } from '@/api/account'
import { getGoodsList, updateAutoDeliveryStatus, updateAutoConfirmShipment } from '@/api/goods'
import {
  getAutoDeliveryConfig,
  saveOrUpdateAutoDeliveryConfig,
  getAutoDeliveryConfigsByGoodsId,
  getGoodsSkuList,
  type AutoDeliveryConfig,
  type SaveAutoDeliveryConfigReq,
  type GetAutoDeliveryConfigReq,
  type GoodsSku
} from '@/api/auto-delivery-config'
import {
  getAutoDeliveryRecords,
  confirmShipment,
  triggerAutoDelivery,
  type AutoDeliveryRecordReq,
  type AutoDeliveryRecordResp,
  type ConfirmShipmentReq,
  type TriggerAutoDeliveryReq
} from '@/api/auto-delivery-record'
import { showSuccess, showError, showInfo } from '@/utils'
import { getConnectionStatus } from '@/api/websocket'
import { toast } from '@/utils/toast'
import {
  getKamiConfigsByAccountId,
  type KamiConfig
} from '@/api/kami-config'
import {
  getFixedDeliveryTemplates,
  type FixedDeliveryTemplate
} from '@/api/fixed-delivery-template'
import type { Account } from '@/types'
import type { GoodsItemWithConfig } from '@/api/goods'

const DEFAULT_DELIVERY_MESSAGE_TEMPLATE = '您好，{buyerName}，订单 {orderId} 已发货：\n{deliveryContent}'

const copyToClipboard = (text: string) => {
  navigator.clipboard.writeText(text).then(() => {
    showSuccess('已复制到剪贴板')
  }).catch(() => {
    const textarea = document.createElement('textarea')
    textarea.value = text
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    document.body.removeChild(textarea)
    showSuccess('已复制到剪贴板')
  })
}

export function useAutoDelivery() {
  const route = useRoute()
  const router = useRouter()

  const gotoConnection = () => router.push('/connection')
  ;(window as any).__gotoConnection = gotoConnection

  const loading = ref(false)
  const saving = ref(false)
  const accounts = ref<Account[]>([])
  const selectedAccountId = ref<number | null>(null)
  const goodsList = ref<GoodsItemWithConfig[]>([])
  const selectedGoods = ref<GoodsItemWithConfig | null>(null)
  const currentConfig = ref<AutoDeliveryConfig | null>(null)

  const skuList = ref<GoodsSku[]>([])
  const selectedSkuId = ref<string | null>(null)
  const skuConfigs = ref<Map<string, AutoDeliveryConfig>>(new Map())

  const goodsCurrentPage = ref(1)
  const goodsTotal = ref(0)
  const goodsLoading = ref(false)
  const goodsListRef = ref<HTMLElement | null>(null)
  const onlyOnSale = ref(true)

  const detailDialogVisible = ref(false)
  const selectedGoodsId = ref<string>('')

  const configForm = ref({
    deliveryMode: 1,
    fixedTemplateId: null as number | null,
    kamiConfigIds: '',
    deliveryMessageTemplate: DEFAULT_DELIVERY_MESSAGE_TEMPLATE,
    voucherDeliveryEnabled: 1,
    chatDeliveryEnabled: 1,
    receiptFollowUpMessages: [] as string[],
    receiptFollowUpIntervalSeconds: 5,
    autoDeliveryImageUrl: '',
    autoConfirmShipment: 0
  })

  const kamiConfigOptions = ref<KamiConfig[]>([])
  const fixedTemplateOptions = ref<FixedDeliveryTemplate[]>([])

  const selectedKamiConfigId = computed({
    get: () => configForm.value.kamiConfigIds || '',
    set: (val: string) => { configForm.value.kamiConfigIds = val }
  })

  const hasMultipleSku = computed(() => skuList.value.length > 1)
  const hasFixedDelivery = computed(() => configForm.value.deliveryMode === 1)
  const hasCardDelivery = computed(() => configForm.value.deliveryMode === 2)
  const selectedFixedTemplate = computed(() =>
    fixedTemplateOptions.value.find(template => template.id === configForm.value.fixedTemplateId) || null)

  const selectDeliveryMode = (mode: number) => {
    configForm.value.deliveryMode = mode
    if (mode === 1) {
      configForm.value.kamiConfigIds = ''
      configForm.value.deliveryMessageTemplate = DEFAULT_DELIVERY_MESSAGE_TEMPLATE
    } else {
      configForm.value.fixedTemplateId = null
    }
  }

  const parseReceiptFollowUpMessages = (value?: string) => {
    if (!value) return []
    try {
      const messages = JSON.parse(value)
      return Array.isArray(messages) ? messages.filter(item => typeof item === 'string') : []
    } catch {
      return []
    }
  }

  const addReceiptFollowUpMessage = () => {
    if (configForm.value.receiptFollowUpMessages.length >= 5) {
      showInfo('最多添加5条确认收货后话术')
      return
    }
    configForm.value.receiptFollowUpMessages.push('')
  }

  const removeReceiptFollowUpMessage = (index: number) => {
    configForm.value.receiptFollowUpMessages.splice(index, 1)
  }

  const appendDeliveryVariable = (variable: string) => {
    configForm.value.deliveryMessageTemplate += variable
  }

  const goToFixedTemplates = () => {
    router.push({
      path: '/fixed-delivery-templates',
      query: selectedAccountId.value ? { accountId: String(selectedAccountId.value) } : {}
    })
  }

  const appendReceiptVariable = (index: number, variable: string) => {
    configForm.value.receiptFollowUpMessages[index] =
      (configForm.value.receiptFollowUpMessages[index] || '') + variable
  }

  const recordsLoading = ref(false)
  const deliveryRecords = ref<any[]>([])
  const recordsTotal = ref(0)
  const recordsPageNum = ref(1)
  const recordsPageSize = ref(20)

  const isMobile = ref(false)
  const mobileView = ref<'goods' | 'config'>('goods')

  const confirmDialog = ref({
    visible: false,
    title: '',
    message: '',
    type: 'danger' as 'danger' | 'primary',
    onConfirm: () => {}
  })

  const apiHintUrl = computed(() => '/api/order/list')

  const apiHintParams = computed(() => {
    const params: Record<string, any> = {
      xianyuAccountId: selectedAccountId.value || undefined,
      xyGoodsId: selectedGoods.value?.item.xyGoodId || undefined,
      orderStatus: 2,
      pageNum: 1,
      pageSize: 20
    }
    return params
  })

  const apiHintParamsJson = computed(() => JSON.stringify(apiHintParams.value, null, 2))

  const confirmShipmentUrl = computed(() => '/api/order/confirmShipment')

  const confirmShipmentParams = computed(() => {
    const params: Record<string, any> = {
      xianyuAccountId: selectedAccountId.value || undefined,
      orderId: '订单ID'
    }
    return params
  })

  const confirmShipmentParamsJson = computed(() => JSON.stringify(confirmShipmentParams.value, null, 2))

  const copyApiUrl = () => { copyToClipboard(apiHintUrl.value) }
  const copyApiParams = () => { copyToClipboard(apiHintParamsJson.value) }
  const copyConfirmShipmentUrl = () => { copyToClipboard(confirmShipmentUrl.value) }
  const copyConfirmShipmentParams = () => { copyToClipboard(confirmShipmentParamsJson.value) }

  const checkScreenSize = () => {
    isMobile.value = window.innerWidth < 768
    if (!isMobile.value) { mobileView.value = 'goods' }
  }

  const goBackToGoods = () => { mobileView.value = 'goods' }

  const formatTime = (time: string) => {
    if (!time) return '-'
    return time.replace('T', ' ').substring(0, 19)
  }

  const formatPrice = (price: string) => { return price ? `¥${price}` : '-' }

  const getStatusText = (status: number) => {
    const map: Record<number, string> = { 0: '在售', 1: '已下架', 2: '已售出' }
    return map[status] || '未知'
  }

  const getStatusClass = (status: number) => {
    const map: Record<number, string> = { 0: 'on-sale', 1: 'off-shelf', 2: 'sold' }
    return map[status] || 'off-shelf'
  }

  const getRecordStatusText = (state: number) => {
    if (state === 1) return '成功'
    if (state === 0) return '待发货'
    return '失败'
  }

  const getRecordStatusClass = (state: number) => {
    if (state === 1) return 'success'
    if (state === 0) return 'pending'
    return 'fail'
  }

  const loadAccounts = async () => {
    try {
      const response = await getAccountList()
      if (response.code === 0 || response.code === 200) {
        accounts.value = response.data?.accounts || []

        const accountIdFromQuery = route.query.accountId
        if (accountIdFromQuery) {
          const accountId = parseInt(accountIdFromQuery as string)
          if (accounts.value.some(acc => acc.id === accountId)) {
            selectedAccountId.value = accountId
            await loadGoods()
            return
          }
        }

        if (accounts.value.length > 0 && !selectedAccountId.value) {
          selectedAccountId.value = accounts.value[0]?.id || null
          await loadGoods()
        }
      }
    } catch (error: any) {
      console.error('加载账号列表失败:', error)
    }
  }

  const loadGoods = async () => {
    if (!selectedAccountId.value) {
      showInfo('请先选择账号')
      return
    }

    goodsLoading.value = true
    try {
      const params = {
        xianyuAccountId: selectedAccountId.value,
        onlyOnSale: onlyOnSale.value,
        pageNum: goodsCurrentPage.value,
        pageSize: 20
      }

      const response = await getGoodsList(params)
      if (response.code === 0 || response.code === 200) {
        if (goodsCurrentPage.value === 1) {
          goodsList.value = response.data?.itemsWithConfig || []
        } else {
          goodsList.value.push(...(response.data?.itemsWithConfig || []))
        }
        goodsTotal.value = response.data?.totalCount || 0

        const goodsIdFromQuery = route.query.goodsId
        if (goodsIdFromQuery && goodsCurrentPage.value === 1) {
          const targetGoods = goodsList.value.find(g => g.item.xyGoodId === goodsIdFromQuery)
          if (targetGoods) {
            await selectGoods(targetGoods)
            return
          }
        }

        if (goodsCurrentPage.value === 1 && goodsList.value.length > 0 && !selectedGoods.value && !isMobile.value) {
          await selectGoods(goodsList.value[0]!)
        }

        checkAndLoadMore()
      } else {
        throw new Error(response.msg || '获取商品列表失败')
      }
    } catch (error: any) {
      console.error('加载商品列表失败:', error)
      goodsList.value = []
    } finally {
      goodsLoading.value = false
    }
  }

  const checkAndLoadMore = () => {
    nextTick(() => {
      if (!goodsListRef.value) return
      const { scrollHeight, clientHeight } = goodsListRef.value
      if (scrollHeight <= clientHeight && goodsList.value.length < goodsTotal.value) {
        goodsCurrentPage.value++
        loadGoods()
      }
    })
  }

  const handleGoodsScroll = () => {
    if (!goodsListRef.value || goodsLoading.value) return
    const { scrollTop, scrollHeight, clientHeight } = goodsListRef.value
    if (scrollTop + clientHeight >= scrollHeight - 50) {
      if (goodsList.value.length < goodsTotal.value) {
        goodsCurrentPage.value++
        loadGoods()
      }
    }
  }

  const handleAccountChange = () => {
    selectedGoods.value = null
    currentConfig.value = null
    fixedTemplateOptions.value = []
    goodsCurrentPage.value = 1
    loadFixedTemplateOptions()
    loadGoods()
  }

  const loadSkuList = async () => {
    if (!selectedGoods.value) {
      skuList.value = []
      return
    }
    try {
      const res = await getGoodsSkuList(selectedGoods.value.item.xyGoodId)
      if (res.code === 200 || res.code === 0) {
        skuList.value = (res.data || []).sort((a, b) => {
          if (a.propertySortOrder !== b.propertySortOrder) return (a.propertySortOrder ?? 0) - (b.propertySortOrder ?? 0)
          return (a.valueSortOrder ?? 0) - (b.valueSortOrder ?? 0)
        })
      } else {
        skuList.value = []
      }
    } catch {
      skuList.value = []
    }
  }

  const loadAllSkuConfigs = async () => {
    if (!selectedGoods.value || !selectedAccountId.value) {
      skuConfigs.value = new Map()
      return
    }
    try {
      const res = await getAutoDeliveryConfigsByGoodsId({
        xianyuAccountId: selectedAccountId.value,
        xyGoodsId: selectedGoods.value.item.xyGoodId
      })
      if (res.code === 200 || res.code === 0) {
        const configs = res.data || []
        const map = new Map<string, AutoDeliveryConfig>()
        configs.forEach(c => {
          const key = c.skuId || ''
          map.set(key, c)
        })
        skuConfigs.value = map
      } else {
        skuConfigs.value = new Map()
      }
    } catch {
      skuConfigs.value = new Map()
    }
  }

  const selectGoods = async (goods: GoodsItemWithConfig) => {
    selectedGoods.value = goods
    recordsPageNum.value = 1

    await loadSkuList()

    if (skuList.value.length > 1) {
      selectedSkuId.value = skuList.value[0]?.skuId || null
      await loadAllSkuConfigs()
    } else {
      selectedSkuId.value = null
      skuConfigs.value = new Map()
    }

    await loadConfig()
    await loadDeliveryRecords()
    await loadKamiConfigOptions()
    await loadFixedTemplateOptions()

    if (isMobile.value) { mobileView.value = 'config' }
  }

  const handleSkuChange = async () => {
    await loadConfig()
  }

  const loadKamiConfigOptions = async () => {
    if (!selectedAccountId.value) return
    try {
      const res = await getKamiConfigsByAccountId(selectedAccountId.value)
      if (res.code === 200) {
        kamiConfigOptions.value = res.data || []
      }
    } catch {}
  }

  const loadFixedTemplateOptions = async () => {
    if (!selectedAccountId.value) return
    try {
      const res = await getFixedDeliveryTemplates(selectedAccountId.value)
      fixedTemplateOptions.value = res.code === 200 ? (res.data || []) : []
    } catch {
      fixedTemplateOptions.value = []
    }
  }

  const loadConfig = async () => {
    if (!selectedGoods.value || !selectedAccountId.value) return

    try {
      const baseReq: GetAutoDeliveryConfigReq = {
        xianyuAccountId: selectedAccountId.value,
        xyGoodsId: selectedGoods.value.item.xyGoodId,
        skuId: null
      }
      const baseResponse = await getAutoDeliveryConfig(baseReq)
      if (baseResponse.code === 0 || baseResponse.code === 200) {
        if (baseResponse.data) {
          configForm.value.autoConfirmShipment = baseResponse.data.autoConfirmShipment || 0
        } else {
          configForm.value.autoConfirmShipment = 0
        }
      }

      const req: GetAutoDeliveryConfigReq = {
        xianyuAccountId: selectedAccountId.value,
        xyGoodsId: selectedGoods.value.item.xyGoodId,
        skuId: selectedSkuId.value
      }

      const response = await getAutoDeliveryConfig(req)
      if (response.code === 0 || response.code === 200) {
        currentConfig.value = response.data || null
        if (response.data) {
          configForm.value.deliveryMode = response.data.deliveryMode === 2 ? 2 : 1
          configForm.value.fixedTemplateId = configForm.value.deliveryMode === 1
            ? response.data.fixedTemplateId || null
            : null
          configForm.value.kamiConfigIds = configForm.value.deliveryMode === 2
            ? response.data.kamiConfigIds || ''
            : ''
          configForm.value.deliveryMessageTemplate = configForm.value.deliveryMode === 2
            ? response.data.deliveryMessageTemplate || DEFAULT_DELIVERY_MESSAGE_TEMPLATE
            : DEFAULT_DELIVERY_MESSAGE_TEMPLATE
          configForm.value.voucherDeliveryEnabled = response.data.voucherDeliveryEnabled === 0 ? 0 : 1
          configForm.value.chatDeliveryEnabled = response.data.chatDeliveryEnabled === 0 ? 0 : 1
          configForm.value.receiptFollowUpMessages = parseReceiptFollowUpMessages(response.data.receiptFollowUpMessages)
          configForm.value.receiptFollowUpIntervalSeconds = response.data.receiptFollowUpIntervalSeconds || 5
          configForm.value.autoDeliveryImageUrl = response.data.autoDeliveryImageUrl || ''
          if (response.data.autoConfirmShipment != null) {
            configForm.value.autoConfirmShipment = response.data.autoConfirmShipment
          }
        } else {
          configForm.value.deliveryMode = 1
          configForm.value.fixedTemplateId = null
          configForm.value.kamiConfigIds = ''
          configForm.value.deliveryMessageTemplate = DEFAULT_DELIVERY_MESSAGE_TEMPLATE
          configForm.value.voucherDeliveryEnabled = 1
          configForm.value.chatDeliveryEnabled = 1
          configForm.value.receiptFollowUpMessages = []
          configForm.value.receiptFollowUpIntervalSeconds = 5
          configForm.value.autoDeliveryImageUrl = ''
        }
      } else {
        throw new Error(response.msg || '获取配置失败')
      }
    } catch (error: any) {
      console.error('加载配置失败:', error)
      currentConfig.value = null
    }
  }

  const saveConfig = async () => {
    if (!selectedGoods.value || !selectedAccountId.value) {
      showInfo('请先选择商品')
      return
    }

    if (hasFixedDelivery.value && !configForm.value.fixedTemplateId) {
      showInfo('请选择固定内容模板')
      return
    }
    if (hasCardDelivery.value && !configForm.value.kamiConfigIds) {
      showInfo('请绑定卡密配置')
      return
    }
    if (!configForm.value.voucherDeliveryEnabled && !configForm.value.chatDeliveryEnabled) {
      showInfo('发货凭证和买家私聊至少开启一个')
      return
    }
    if (hasCardDelivery.value) {
      if (!configForm.value.deliveryMessageTemplate.trim()) {
        showInfo('请输入卡密发货模板')
        return
      }
      if (!configForm.value.deliveryMessageTemplate.includes('{deliveryContent}')) {
        showInfo('卡密发货模板必须包含“全部发货内容”变量')
        return
      }
    }
    const receiptMessages = configForm.value.receiptFollowUpMessages
      .map(message => message.trim())
      .filter(Boolean)
    if (receiptMessages.some(message => message.length > 500)) {
      showInfo('单条确认收货后话术不能超过500字')
      return
    }
    const followUpInterval = Number(configForm.value.receiptFollowUpIntervalSeconds)
    if (!Number.isInteger(followUpInterval) || followUpInterval < 5 || followUpInterval > 600) {
      showInfo('话术发送间隔应为5至600秒')
      return
    }

    saving.value = true
    try {
      const skuName = selectedSkuId.value
        ? (skuList.value.find(s => s.skuId === selectedSkuId.value)?.valueText || '')
        : ''

      const req: SaveAutoDeliveryConfigReq = {
        xianyuAccountId: selectedAccountId.value,
        xianyuGoodsId: selectedGoods.value.item.id,
        xyGoodsId: selectedGoods.value.item.xyGoodId,
        deliveryMode: configForm.value.deliveryMode,
        fixedTemplateId: hasFixedDelivery.value ? configForm.value.fixedTemplateId : null,
        skuId: selectedSkuId.value,
        skuName,
        kamiConfigIds: hasCardDelivery.value ? configForm.value.kamiConfigIds : undefined,
        deliveryMessageTemplate: hasCardDelivery.value
          ? configForm.value.deliveryMessageTemplate.trim()
          : undefined,
        voucherDeliveryEnabled: configForm.value.voucherDeliveryEnabled,
        chatDeliveryEnabled: configForm.value.chatDeliveryEnabled,
        receiptFollowUpMessages: JSON.stringify(receiptMessages),
        receiptFollowUpIntervalSeconds: followUpInterval,
        autoDeliveryImageUrl: configForm.value.autoDeliveryImageUrl.trim(),
        autoConfirmShipment: 1
      }

      const response = await saveOrUpdateAutoDeliveryConfig(req)
      if (response.code === 0 || response.code === 200) {
        showSuccess('保存配置成功')
        currentConfig.value = response.data || null
        if (hasMultipleSku.value) {
          await loadAllSkuConfigs()
        }
      } else {
        throw new Error(response.msg || '保存配置失败')
      }
    } catch (error: any) {
      console.error('保存配置失败:', error)
    } finally {
      saving.value = false
    }
  }

  const toggleAutoDelivery = async (value: boolean) => {
    if (!selectedGoods.value || !selectedAccountId.value) {
      showInfo('请先选择商品')
      return
    }

    try {
      const response = await updateAutoDeliveryStatus({
        xianyuAccountId: selectedAccountId.value,
        xyGoodsId: selectedGoods.value.item.xyGoodId,
        xianyuAutoDeliveryOn: value ? 1 : 0
      })

      if (response.code === 0 || response.code === 200) {
        showSuccess(`自动发货${value ? '开启' : '关闭'}成功`)
        if (selectedGoods.value) {
          selectedGoods.value.xianyuAutoDeliveryOn = value ? 1 : 0
        }
        const goodsItem = goodsList.value.find(item => item.item.xyGoodId === selectedGoods.value?.item.xyGoodId)
        if (goodsItem) {
          goodsItem.xianyuAutoDeliveryOn = value ? 1 : 0
        }
      } else {
        throw new Error(response.msg || '操作失败')
      }
    } catch (error: any) {
      console.error('操作失败:', error)
      if (selectedGoods.value) {
        selectedGoods.value.xianyuAutoDeliveryOn = value ? 0 : 1
      }
    }
  }

  const toggleAutoConfirmShipment = async (value: boolean) => {
    if (!selectedGoods.value || !selectedAccountId.value) {
      showInfo('请先选择商品')
      return
    }

    try {
      const response = await updateAutoConfirmShipment({
        xianyuAccountId: selectedAccountId.value,
        xyGoodsId: selectedGoods.value.item.xyGoodId,
        autoConfirmShipment: value ? 1 : 0
      })

      if (response.code === 0 || response.code === 200) {
        showSuccess(`自动确认发货${value ? '开启' : '关闭'}成功`)
        configForm.value.autoConfirmShipment = value ? 1 : 0
      } else {
        throw new Error(response.msg || '操作失败')
      }
    } catch (error: any) {
      console.error('操作失败:', error)
      configForm.value.autoConfirmShipment = value ? 0 : 1
    }
  }

  const loadDeliveryRecords = async () => {
    if (!selectedAccountId.value || !selectedGoods.value) {
      deliveryRecords.value = []
      recordsTotal.value = 0
      return
    }

    recordsLoading.value = true
    try {
      const req: AutoDeliveryRecordReq = {
        xianyuAccountId: selectedAccountId.value,
        xyGoodsId: selectedGoods.value.item.xyGoodId,
        pageNum: recordsPageNum.value,
        pageSize: recordsPageSize.value
      }

      const response = await getAutoDeliveryRecords(req)
      if (response.code === 0 || response.code === 200) {
        deliveryRecords.value = response.data?.records || []
        recordsTotal.value = response.data?.total || 0
      } else {
        throw new Error(response.msg || '获取记录失败')
      }
    } catch (error: any) {
      console.error('加载自动发货记录失败:', error)
      deliveryRecords.value = []
      recordsTotal.value = 0
    } finally {
      recordsLoading.value = false
    }
  }

  const handleRecordsPageChange = (page: number) => {
    recordsPageNum.value = page
    loadDeliveryRecords()
  }

  const viewGoodsDetail = () => {
    if (!selectedGoods.value || !selectedAccountId.value) {
      showInfo('请先选择商品')
      return
    }
    selectedGoodsId.value = selectedGoods.value.item.xyGoodId
    detailDialogVisible.value = true
  }

  const goToAutoReply = () => {
    if (!selectedGoods.value || !selectedAccountId.value) {
      showInfo('请先选择商品')
      return
    }
    router.push({
      path: '/auto-reply',
      query: {
        accountId: String(selectedAccountId.value),
        goodsId: selectedGoods.value.item.xyGoodId
      }
    })
  }

  const handleConfirmShipment = (record: any) => {
    if (!selectedAccountId.value) {
      showInfo('请先选择账号')
      return
    }
    if (!record.orderId) {
      showError('该记录没有订单ID，无法确认已发货')
      return
    }

    confirmDialog.value = {
      visible: true,
      title: '确认已发货',
      message: `确定要确认已发货吗？订单ID: ${record.orderId}`,
      type: 'primary',
      onConfirm: async () => {
        try {
          const req: ConfirmShipmentReq = {
            xianyuAccountId: selectedAccountId.value!,
            orderId: record.orderId
          }
          const response = await confirmShipment(req)
          if (response.code === 0 || response.code === 200) {
            showSuccess(response.data || '确认已发货成功')
            await loadDeliveryRecords()
          } else {
            if (response.msg && (response.msg.includes('Token') || response.msg.includes('令牌'))) {
              throw new Error('Cookie已过期，请重新扫码登录获取新的Cookie')
            }
            throw new Error(response.msg || '确认已发货失败')
          }
        } catch (error: any) {
          console.error('确认已发货失败:', error)
          if (!error.messageShown) {
            showError(error.message || '确认已发货失败')
          }
        } finally {
          confirmDialog.value.visible = false
        }
      }
    }
  }

  const showWsDisconnectedTip = () => {
    toast.warning('请先连接服务器，点击跳转到连接管理页面')
  }

  const handleTriggerDelivery = async (record: any) => {
    if (!selectedAccountId.value || !selectedGoods.value) {
      showInfo('请先选择账号和商品')
      return
    }
    if (!record.orderId) {
      showError('该记录没有订单ID，无法触发发货')
      return
    }

    try {
      const wsStatus = await getConnectionStatus(selectedAccountId.value)
      if (!wsStatus.data?.connected) {
        showWsDisconnectedTip()
        return
      }
    } catch {
      showWsDisconnectedTip()
      return
    }
    if (hasFixedDelivery.value && !configForm.value.fixedTemplateId) {
      showError('请选择固定内容模板！')
      return
    }
    if (hasCardDelivery.value && !configForm.value.kamiConfigIds) {
      showError('请绑定卡密配置！')
      return
    }

    if (loading.value) {
      showInfo('正在处理中，请稍候...')
      return
    }

    const dialogMessage = hasCardDelivery.value
      ? `确认重新发货吗？\n\n⚠️ 本次发货包含卡密内容，将重新分配卡密并扣减一次库存。\n订单ID: ${record.orderId}`
      : `确认重新发货吗？订单ID: ${record.orderId}`

    confirmDialog.value = {
      visible: true,
      title: '重新发货',
      message: dialogMessage,
      type: 'danger',
      onConfirm: async () => {
        if (loading.value) { return }
        
        loading.value = true
        try {
          const req: TriggerAutoDeliveryReq = {
            xianyuAccountId: selectedAccountId.value!,
            xyGoodsId: selectedGoods.value!.item.xyGoodId,
            orderId: record.orderId
          }
          const response = await triggerAutoDelivery(req)
          if (response.code === 0 || response.code === 200) {
            showSuccess(response.data || '触发发货成功')
            await loadDeliveryRecords()
          } else {
            throw new Error(response.msg || '触发发货失败')
          }
        } catch (error: any) {
          console.error('触发发货失败:', error)
          if (!error.messageShown) {
            showError(error.message || '触发发货失败')
          }
        } finally {
          loading.value = false
          confirmDialog.value.visible = false
        }
      }
    }
  }

  const handleDialogConfirm = () => { confirmDialog.value.onConfirm() }
  const handleDialogCancel = () => { confirmDialog.value.visible = false }

  const recordsTotalPages = computed(() => Math.ceil(recordsTotal.value / recordsPageSize.value))

  const toggleOnlyOnSale = () => {
    onlyOnSale.value = !onlyOnSale.value
    goodsCurrentPage.value = 1
    selectedGoods.value = null
    loadGoods()
  }

  onMounted(() => {
    loadAccounts()
    checkScreenSize()
    window.addEventListener('resize', checkScreenSize)
  })

  onUnmounted(() => {
    window.removeEventListener('resize', checkScreenSize)
  })

  return {
    loading,
    saving,
    accounts,
    selectedAccountId,
    goodsList,
    selectedGoods,
    currentConfig,
    configForm,
    skuList,
    selectedSkuId,
    skuConfigs,
    hasMultipleSku,
    hasFixedDelivery,
    hasCardDelivery,
    goodsCurrentPage,
    goodsTotal,
    goodsLoading,
    goodsListRef,
    onlyOnSale,
    detailDialogVisible,
    selectedGoodsId,
    deliveryRecords,
    recordsLoading,
    recordsTotal,
    recordsPageNum,
    recordsPageSize,
    recordsTotalPages,
    isMobile,
    mobileView,
    confirmDialog,
    apiHintUrl,
    apiHintParamsJson,
    confirmShipmentUrl,
    confirmShipmentParamsJson,
    kamiConfigOptions,
    fixedTemplateOptions,
    selectedFixedTemplate,
    selectedKamiConfigId,
    selectDeliveryMode,
    goToFixedTemplates,
    addReceiptFollowUpMessage,
    removeReceiptFollowUpMessage,
    appendDeliveryVariable,
    appendReceiptVariable,

    loadAccounts,
    loadGoods,
    handleAccountChange,
    selectGoods,
    saveConfig,
    toggleAutoDelivery,
    toggleAutoConfirmShipment,
    loadDeliveryRecords,
    handleRecordsPageChange,
    viewGoodsDetail,
    goToAutoReply,
    handleConfirmShipment,
    handleTriggerDelivery,
    handleDialogConfirm,
    handleDialogCancel,
    handleSkuChange,
    copyApiUrl,
    copyApiParams,
    copyConfirmShipmentUrl,
    copyConfirmShipmentParams,
    handleGoodsScroll,
    goBackToGoods,
    toggleOnlyOnSale,
    formatTime,
    formatPrice,
    getStatusText,
    getStatusClass,
    getRecordStatusText,
    getRecordStatusClass,
    checkScreenSize
  }
}
