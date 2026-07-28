import { createRouter, createWebHistory } from 'vue-router'
import { isLoggedIn } from '@/utils/request'
import { firstAccessiblePath, hasPermission, isPlatformAdmin, loadCurrentUser } from '@/utils/permission'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/data-panel',
      name: 'data-panel',
      component: () => import('@/views/data-panel/index.vue'),
      meta: { title: '数据看板', icon: '▥', permission: 'menu:dashboard' }
    },
    {
      path: '/product-publish',
      name: 'product-publish',
      component: () => import('@/views/product-publish/index.vue'),
      meta: { title: '商品发布', icon: '＋', permission: 'menu:goods', writePermission: 'action:goods-write' }
    },
    {
      path: '/opportunities',
      name: 'opportunities',
      component: () => import('@/views/opportunities/index.vue'),
      meta: { title: '商机发掘', icon: '⌕', permission: 'menu:operations', writePermission: 'action:operations-write' }
    },
    {
      path: '/supplies',
      name: 'supplies',
      component: () => import('@/views/supplies/index.vue'),
      meta: { title: '货源库', icon: '▣', permission: 'menu:operations', writePermission: 'action:operations-write' }
    },
    {
      path: '/workflows',
      name: 'workflows',
      component: () => import('@/views/workflows/index.vue'),
      meta: { title: '工作流', icon: '◇', permission: 'menu:operations', writePermission: 'action:operations-write' }
    },
    {
      path: '/automation',
      name: 'automation',
      component: () => import('@/views/automation/index.vue'),
      meta: { title: '自动化', icon: '⚙', permission: 'menu:operations', writePermission: 'action:operations-write' }
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/login/index.vue'),
      meta: { title: '登录', public: true }
    },
    {
      path: '/',
      redirect: '/dashboard'
    },
    {
      path: '/dashboard',
      name: 'dashboard',
      component: () => import('@/views/dashboard/index.vue'),
      meta: { title: '面板', icon: '📊', permission: 'menu:dashboard' }
    },
    {
      path: '/accounts',
      name: 'accounts',
      component: () => import('@/views/accounts/index.vue'),
      meta: { title: '闲鱼账号', icon: '👤', permission: 'menu:accounts', writePermission: 'action:account-write' }
    },
    {
      path: '/connection',
      name: 'connection',
      component: () => import('@/views/connection/index.vue'),
      meta: { title: '连接管理', icon: '🔗', permission: 'menu:connection', writePermission: 'action:connection-write' }
    },
    {
      path: '/connection/:id',
      name: 'connection-detail',
      component: () => import('@/views/connection/ConnectionDetail.vue'),
      meta: { title: '连接详情', icon: '🔗', permission: 'menu:connection', writePermission: 'action:connection-write' }
    },
    {
      path: '/goods',
      name: 'goods',
      component: () => import('@/views/goods/index.vue'),
      meta: { title: '商品管理', icon: '📦', permission: 'menu:goods', writePermission: 'action:goods-write' }
    },
    {
      path: '/operations',
      name: 'operations',
      component: () => import('@/views/operations/index.vue'),
      meta: { title: '运营中心', icon: '◫', permission: 'menu:operations', writePermission: 'action:operations-write' }
    },
    {
      path: '/orders',
      name: 'orders',
      component: () => import('@/views/orders/index.vue'),
      meta: { title: '订单与评价', icon: '📋', permission: 'menu:orders', writePermission: 'action:order-write' }
    },
    {
      path: '/messages',
      name: 'messages',
      component: () => import('@/views/messages/workspace.vue'),
      meta: { title: '消息管理', icon: '💬', permission: 'menu:messages', writePermission: 'action:message-send' }
    },
    {
      path: '/buyers',
      name: 'buyers',
      component: () => import('@/views/buyers/index.vue'),
      meta: { title: '买家管理', icon: '👥', permission: 'menu:buyers', writePermission: 'action:buyer-write' }
    },
    {
      path: '/auto-delivery',
      name: 'auto-delivery',
      component: () => import('@/views/auto-delivery/index.vue'),
      meta: { title: '自动发货', icon: '🤖', permission: 'menu:auto-delivery', writePermission: 'action:delivery-write' }
    },
    {
      path: '/fixed-delivery-templates',
      name: 'fixed-delivery-templates',
      component: () => import('@/views/fixed-delivery-templates/index.vue'),
      meta: { title: '固定内容模板', icon: '📄', permission: 'menu:fixed-delivery', writePermission: 'action:delivery-write' }
    },
    {
      path: '/pending-orders',
      name: 'pending-orders',
      component: () => import('@/views/pending-orders/index.vue'),
      meta: { title: '待发货订单', icon: '📦', hidden: true, permission: 'menu:orders', writePermission: 'action:order-write' }
    },
    {
      path: '/kami-config',
      name: 'kami-config',
      component: () => import('@/views/kami-config/index.vue'),
      meta: { title: '卡密配置', icon: '🔑', permission: 'menu:kami', writePermission: 'action:delivery-write' }
    },
    {
      path: '/auto-reply',
      name: 'auto-reply',
      component: () => import('@/views/auto-reply/index.vue'),
      meta: { title: '自动回复', icon: '💭', permission: 'menu:auto-reply', writePermission: 'action:automation-write' }
    },
    {
      path: '/operation-log',
      name: 'operation-log',
      component: () => import('@/views/operation-log/index.vue'),
      meta: { title: '操作记录', icon: '📜', permission: 'menu:operation-log', writePermission: 'action:system-write' }
    },
    {
      path: '/operations-health',
      name: 'operations-health',
      component: () => import('@/views/operations-health/index.vue'),
      meta: { title: '通知与诊断', icon: '🩺', permission: 'menu:health', writePermission: 'action:system-write' }
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('@/views/settings/index.vue'),
      meta: { title: '系统设置', icon: '⚙️', permission: 'menu:settings', writePermission: 'action:system-write' }
    },
    {
      path: '/admin/users',
      name: 'admin-users',
      component: () => import('@/views/admin-users/index.vue'),
      meta: { title: '账号与权限', adminOnly: true }
    },
    {
      path: '/qrlogin',
      name: 'qrlogin',
      component: () => import('@/views/qrlogin/index.vue'),
      meta: { title: '扫码登录', icon: '📱', permission: 'menu:accounts', writePermission: 'action:account-write' }
    }
  ]
})

// 路由守卫同时校验登录状态、管理员页面和菜单权限。
router.beforeEach(async (to, _from, next) => {
  if (to.meta.public) {
    next()
    return
  }
  if (!isLoggedIn()) {
    next('/login')
    return
  }
  try {
    await loadCurrentUser()
    if (to.meta.adminOnly && !isPlatformAdmin.value) {
      next(firstAccessiblePath())
      return
    }
    if (to.meta.permission && !hasPermission(String(to.meta.permission))) {
      next(firstAccessiblePath())
      return
    }
    next()
  } catch {
    next('/login')
  }
})

export default router
