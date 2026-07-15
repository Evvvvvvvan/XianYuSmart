export interface GoodsEditForm {
  title: string
  soldPrice: string
  detailInfo: string
  coverPic: string
}

export const normalizeGoodsEditForm = (form: GoodsEditForm): GoodsEditForm => ({
  title: form.title.trim(),
  soldPrice: Number(form.soldPrice.trim()).toString(),
  detailInfo: form.detailInfo.trim(),
  coverPic: form.coverPic.trim()
})

export const validateGoodsEditForm = (form: GoodsEditForm): string | null => {
  const title = form.title.trim()
  if (!title) return '请输入商品标题'
  if (title.length > 256) return '商品标题不能超过 256 个字符'

  const priceText = form.soldPrice.trim()
  const price = Number(priceText)
  if (!/^(?:\d+(?:\.\d*)?|\.\d+)$/.test(priceText) || !Number.isFinite(price)) return '请输入正确的商品价格'
  if (price < 0 || price > 9999999.99) return '商品价格应在 0 至 9999999.99 之间'
  const decimalLength = priceText.includes('.') ? (priceText.split('.')[1]?.length || 0) : 0
  if (decimalLength > 2) return '商品价格最多保留两位小数'

  if (form.detailInfo.trim().length > 5000) return '商品描述不能超过 5000 个字符'
  const coverPic = form.coverPic.trim()
  if (coverPic.length > 2000) return '封面地址不能超过 2000 个字符'
  if (coverPic) {
    try {
      const url = new URL(coverPic)
      if (!['http:', 'https:'].includes(url.protocol)) {
        return '封面地址必须为空或使用 HTTP/HTTPS 协议'
      }
    } catch {
      return '封面地址必须为空或使用 HTTP/HTTPS 协议'
    }
  }
  return null
}

export const resolvePlatformItemUrl = (detailUrl: string | undefined, xyGoodsId: string): string => {
  if (detailUrl) {
    try {
      const url = new URL(detailUrl)
      if (['http:', 'https:'].includes(url.protocol)) return url.toString()
    } catch {
      // 无效详情地址回退到商品页。
    }
  }
  return `https://www.goofish.com/item?id=${encodeURIComponent(xyGoodsId)}`
}
