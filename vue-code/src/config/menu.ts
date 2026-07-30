export type MenuIcon =
  | 'alert'
  | 'chart'
  | 'clipboard'
  | 'dashboard'
  | 'goods'
  | 'key'
  | 'link'
  | 'log'
  | 'message'
  | 'package'
  | 'plus'
  | 'robot'
  | 'search'
  | 'send'
  | 'shield'
  | 'text'
  | 'tooling'
  | 'truck'
  | 'user'
  | 'users'

export interface MenuItemDefinition {
  id: string
  label: string
  path: string
  permission?: string
  adminOnly?: boolean
  icon: MenuIcon
}

export interface MenuGroupDefinition {
  id: string
  label: string
  items: MenuItemDefinition[]
}

export interface MenuLayout {
  version: 1
  groups: Array<{ id: string; items: string[] }>
}

export const MENU_LAYOUT_SETTING_KEY = 'menu_layout'

export const MENU_GROUPS: MenuGroupDefinition[] = [
  {
    id: 'overview',
    label: '概览',
    items: [
      { id: 'dashboard', label: '面板', path: '/dashboard', permission: 'menu:dashboard', icon: 'chart' },
      { id: 'data-panel', label: '数据看板', path: '/data-panel', permission: 'menu:dashboard', icon: 'dashboard' }
    ]
  },
  {
    id: 'accounts',
    label: '账号与连接',
    items: [
      { id: 'account-list', label: '闲鱼账号', path: '/accounts', permission: 'menu:accounts', icon: 'user' },
      { id: 'connection', label: '连接管理', path: '/connection', permission: 'menu:connection', icon: 'link' }
    ]
  },
  {
    id: 'products',
    label: '商品与获客',
    items: [
      { id: 'goods', label: '商品管理', path: '/goods', permission: 'menu:goods', icon: 'goods' },
      { id: 'product-publish', label: '商品发布', path: '/product-publish', permission: 'menu:goods', icon: 'plus' },
      { id: 'operations', label: '运营中心', path: '/operations', permission: 'menu:operations', icon: 'tooling' },
      { id: 'opportunities', label: '商机发掘', path: '/opportunities', permission: 'menu:operations', icon: 'search' },
      { id: 'price-comparison', label: '全站比价', path: '/price-comparison', permission: 'menu:operations', icon: 'chart' },
      { id: 'supplies', label: '货源库', path: '/supplies', permission: 'menu:operations', icon: 'package' },
      { id: 'workflows', label: '工作流', path: '/workflows', permission: 'menu:operations', icon: 'robot' }
    ]
  },
  {
    id: 'fulfillment',
    label: '成交履约',
    items: [
      { id: 'kami', label: '卡密仓库', path: '/kami-config', permission: 'menu:kami', icon: 'key' },
      { id: 'fixed-delivery', label: '固定内容模板', path: '/fixed-delivery-templates', permission: 'menu:fixed-delivery', icon: 'text' },
      { id: 'auto-delivery', label: '自动发货', path: '/auto-delivery', permission: 'menu:auto-delivery', icon: 'truck' },
      { id: 'orders', label: '订单与评价', path: '/orders', permission: 'menu:orders', icon: 'clipboard' }
    ]
  },
  {
    id: 'customers',
    label: '客户运营',
    items: [
      { id: 'messages', label: '消息管理', path: '/messages', permission: 'menu:messages', icon: 'message' },
      { id: 'buyers', label: '买家管理', path: '/buyers', permission: 'menu:buyers', icon: 'users' },
      { id: 'auto-reply', label: '自动回复', path: '/auto-reply', permission: 'menu:auto-reply', icon: 'send' }
    ]
  },
  {
    id: 'system',
    label: '系统',
    items: [
      { id: 'operation-log', label: '操作日志', path: '/operation-log', permission: 'menu:operation-log', icon: 'log' },
      { id: 'health', label: '通知与诊断', path: '/operations-health', permission: 'menu:health', icon: 'alert' },
      { id: 'settings', label: '系统设置', path: '/settings', permission: 'menu:settings', icon: 'shield' },
      { id: 'admin-users', label: '账号与权限', path: '/admin/users', adminOnly: true, icon: 'users' }
    ]
  }
]

export function createDefaultMenuLayout(): MenuLayout {
  return {
    version: 1,
    groups: MENU_GROUPS.map(group => ({
      id: group.id,
      items: group.items.map(item => item.id)
    }))
  }
}

export function normalizeMenuLayout(value?: string | MenuLayout | null): MenuLayout {
  let parsed: unknown = value
  if (typeof value === 'string') {
    try {
      parsed = JSON.parse(value)
    } catch {
      return createDefaultMenuLayout()
    }
  }
  if (!parsed || typeof parsed !== 'object') {
    return createDefaultMenuLayout()
  }

  const rawGroups = (parsed as { groups?: unknown }).groups
  if (!Array.isArray(rawGroups)) {
    return createDefaultMenuLayout()
  }

  const seenGroups = new Set<string>()
  const groups: MenuLayout['groups'] = []
  for (const rawGroup of rawGroups) {
    if (!rawGroup || typeof rawGroup !== 'object') continue
    const rawId = (rawGroup as { id?: unknown }).id
    if (typeof rawId !== 'string' || seenGroups.has(rawId)) continue

    const definition = MENU_GROUPS.find(group => group.id === rawId)
    if (!definition) continue

    seenGroups.add(rawId)
    const allowedItems = new Set(definition.items.map(item => item.id))
    const seenItems = new Set<string>()
    const rawItems = (rawGroup as { items?: unknown }).items
    const items = Array.isArray(rawItems)
      ? rawItems.filter((item): item is string => {
          if (typeof item !== 'string' || !allowedItems.has(item) || seenItems.has(item)) return false
          seenItems.add(item)
          return true
        })
      : []

    // 自动补齐新菜单，确保旧布局升级后仍能显示新增功能。
    for (const item of definition.items) {
      if (!seenItems.has(item.id)) items.push(item.id)
    }
    groups.push({ id: definition.id, items })
  }

  // 自动补齐新模块，避免版本升级后菜单缺失。
  for (const definition of MENU_GROUPS) {
    if (!seenGroups.has(definition.id)) {
      groups.push({ id: definition.id, items: definition.items.map(item => item.id) })
    }
  }
  return { version: 1, groups }
}

export function moveMenuGroup(layout: MenuLayout, sourceId: string, targetId: string): MenuLayout {
  const normalized = normalizeMenuLayout(layout)
  const groups = normalized.groups.map(group => ({ ...group, items: [...group.items] }))
  const sourceIndex = groups.findIndex(group => group.id === sourceId)
  const targetIndex = groups.findIndex(group => group.id === targetId)
  if (sourceIndex < 0 || targetIndex < 0 || sourceIndex === targetIndex) return normalized

  const source = groups.splice(sourceIndex, 1)[0]!
  groups.splice(targetIndex, 0, source)
  return { version: 1, groups }
}

export function moveMenuItem(
  layout: MenuLayout,
  groupId: string,
  sourceId: string,
  targetId: string
): MenuLayout {
  const normalized = normalizeMenuLayout(layout)
  const groups = normalized.groups.map(group => ({ ...group, items: [...group.items] }))
  const group = groups.find(item => item.id === groupId)
  if (!group) return normalized

  const sourceIndex = group.items.indexOf(sourceId)
  const targetIndex = group.items.indexOf(targetId)
  if (sourceIndex < 0 || targetIndex < 0 || sourceIndex === targetIndex) return normalized

  const source = group.items.splice(sourceIndex, 1)[0]!
  group.items.splice(targetIndex, 0, source)
  return { version: 1, groups }
}

export function serializeMenuLayout(layout: MenuLayout): string {
  return JSON.stringify(normalizeMenuLayout(layout))
}
