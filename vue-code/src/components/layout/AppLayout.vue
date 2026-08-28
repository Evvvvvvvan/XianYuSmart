<script setup lang="ts">
import { ref, shallowRef, onMounted, onUnmounted, computed, provide, markRaw, watch } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import NavMenu from './NavMenu.vue'
import UpdateDialog from './UpdateDialog.vue'
import { checkUpdate, getCurrentUser } from '@/api/system'
import { hasPermission } from '@/utils/permission'

// 导入所有页面图标
import IconChart from '@/components/icons/IconChart.vue'
import IconAccount from '@/components/icons/IconAccount.vue'
import IconWifi from '@/components/icons/IconWifi.vue'
import IconShoppingBag from '@/components/icons/IconShoppingBag.vue'
import IconTruck from '@/components/icons/IconTruck.vue'
import IconMessage from '@/components/icons/IconMessage.vue'
import IconRobot from '@/components/icons/IconRobot.vue'
import IconChat from '@/components/icons/IconChat.vue'
import IconLog from '@/components/icons/IconLog.vue'
import IconShield from '@/components/icons/IconShield.vue'

const route = useRoute()

declare const __APP_VERSION__: string

const currentVersion = ref(__APP_VERSION__ || '2.0.7')
const hasNewVersion = ref(false)
const isAdmin = ref(false)
const updateDialog = ref<InstanceType<typeof UpdateDialog> | null>(null)

const loadVersion = async () => {
  try {
    const userRes = await getCurrentUser()
    isAdmin.value = userRes.data?.role === 'ADMIN'
    if (!isAdmin.value) return
    const updateRes = await checkUpdate()
    hasNewVersion.value = updateRes.data?.hasUpdate === true
  } catch {
    // ignore
  }
}

const openUpdateDialog = () => {
  if (!isAdmin.value) return
  updateDialog.value?.open()
}

// 响应式设备类型
const initialWidth = window.innerWidth
const isMobile = ref(initialWidth < 768)  // < 768px
const isTablet = ref(initialWidth >= 768 && initialWidth < 1024)  // 768px - 1024px
const isDesktop = ref(initialWidth >= 1024) // > 1024px

// 移动端和平板端共用的抽屉状态
const drawerVisible = ref(false)

// 页面特定的导航栏内容
const headerContent = shallowRef<any>(null)

// 提供给页面组件的方法来设置导航栏内容
const setHeaderContent = (content: any) => {
  headerContent.value = content
}

// 提供给页面组件
provide('setHeaderContent', setHeaderContent)

const pageTitleMap: Record<string, string> = {
  '/dashboard': '仪表板',
  '/accounts': '闲鱼账号',
  '/connection': '连接管理',
  '/goods': '商品管理',
  '/orders': '发货记录',
  '/messages': '消息管理',
  '/buyers': '买家管理',
  '/auto-delivery': '自动发货',
  '/fixed-delivery-templates': '固定内容模板',

  '/auto-reply': '自动回复',
  '/operation-log': '操作日志',
  '/operations-health': '通知与诊断',
  '/settings': '系统设置',
  '/admin/users': '账号与权限'
}

const pageIconMap: Record<string, any> = {
  '/dashboard': markRaw(IconChart),
  '/data-panel': markRaw(IconChart),
  '/accounts': markRaw(IconAccount),
  '/connection': markRaw(IconWifi),
  '/goods': markRaw(IconShoppingBag),
  '/product-publish': markRaw(IconShoppingBag),
  '/operations': markRaw(IconChart),
  '/opportunities': markRaw(IconChart),
  '/price-comparison': markRaw(IconChart),
  '/supplies': markRaw(IconShoppingBag),
  '/workflows': markRaw(IconRobot),
  '/automation': markRaw(IconRobot),
  '/orders': markRaw(IconTruck),
  '/pending-orders': markRaw(IconTruck),
  '/messages': markRaw(IconMessage),
  '/buyers': markRaw(IconAccount),
  '/auto-delivery': markRaw(IconRobot),
  '/fixed-delivery-templates': markRaw(IconTruck),
  '/kami-config': markRaw(IconShield),
  '/auto-reply': markRaw(IconChat),
  '/operation-log': markRaw(IconLog),
  '/operations-health': markRaw(IconChart),
  '/settings': markRaw(IconShield),
  '/admin/users': markRaw(IconAccount),
  '/qrlogin': markRaw(IconWifi)
}

const currentPageTitle = computed(() => String(route.meta.title || pageTitleMap[route.path] || 'XianYuSmart'))
const currentPageIcon = computed(() => pageIconMap[route.path] || (route.path.startsWith('/connection/') ? pageIconMap['/connection'] : null))
const pageReadOnly = computed(() =>
  !!route.meta.writePermission && !hasPermission(String(route.meta.writePermission))
)

// 路由切换时清理旧页面注入的工具栏，防止短暂显示上一页操作。
watch(() => route.path, () => {
  headerContent.value = null
})

// 检测屏幕尺寸并自动设置设备类型
const checkScreenSize = () => {
  const width = window.innerWidth

  // 判断设备类型
  if (width < 768) {
    isMobile.value = true
    isTablet.value = false
    isDesktop.value = false
    // 切换到手机模式时，关闭抽屉
    drawerVisible.value = false
  } else if (width < 1024) {
    isMobile.value = false
    isTablet.value = true
    isDesktop.value = false
    // 切换到平板模式时，关闭抽屉
    drawerVisible.value = false
  } else {
    isMobile.value = false
    isTablet.value = false
    isDesktop.value = true
    // 切换到桌面模式时，关闭抽屉
    drawerVisible.value = false
  }
}

// 切换抽屉（手机端和平板端共用）
const toggleDrawer = () => {
  drawerVisible.value = !drawerVisible.value
}

const closeDrawer = () => {
  drawerVisible.value = false
}

onMounted(() => {
  checkScreenSize()
  window.addEventListener('resize', checkScreenSize)
  loadVersion()
})

onUnmounted(() => {
  window.removeEventListener('resize', checkScreenSize)
})
</script>

<template>
  <div class="app-layout">
    <button v-if="isAdmin && hasNewVersion" class="update-notice" @click="openUpdateDialog">
      <span></span>发现新版本，立即更新
    </button>
    <!-- 手机端: 顶部导航栏 -->
    <div v-if="isMobile" class="mobile-header">
      <button class="menu-toggle-btn" @click="toggleDrawer">
        <span class="menu-icon">☰</span>
      </button>
      <div class="header-title-section">
        <component v-if="currentPageIcon" :is="currentPageIcon" class="header-page-icon" />
        <div class="mobile-page-title">{{ currentPageTitle }}</div>
      </div>
      <div v-if="headerContent" class="header-content-slot">
        <component :is="headerContent" />
      </div>
    </div>

    <!-- 平板端: 顶部导航栏（带抽屉按钮） -->
    <div v-if="isTablet" class="tablet-header">
      <button class="menu-toggle-btn" @click="toggleDrawer">
        <span class="menu-icon">☰</span>
      </button>
      <div class="header-title-section">
        <component v-if="currentPageIcon" :is="currentPageIcon" class="header-page-icon" />
        <div class="tablet-page-title">{{ currentPageTitle }}</div>
      </div>
      <div v-if="headerContent" class="header-content-slot">
        <component :is="headerContent" />
      </div>
    </div>

    <!-- 手机端和平板端: 左侧抽屉菜单 -->
    <transition name="drawer">
      <div v-if="(isMobile || isTablet) && drawerVisible" class="drawer-overlay" @click="closeDrawer">
        <div class="drawer-menu" @click.stop>
          <div class="drawer-header">
            <div class="logo" :class="{ 'is-update-entry': isAdmin }" @click="openUpdateDialog">
              <div class="logo-icon">X</div>
              <div class="logo-text-wrap">
                <div class="logo-text">XianYuSmart</div>
                <div class="version-tag" :class="{ 'has-update': isAdmin && hasNewVersion }">
                  v{{ currentVersion }}
                  <span v-if="isAdmin && hasNewVersion" class="update-dot"></span>
                </div>
              </div>
            </div>
            <button class="drawer-close-btn" @click="closeDrawer">
              <span class="close-icon">✕</span>
            </button>
          </div>
          <div class="drawer-content">
            <NavMenu @select="closeDrawer" />
          </div>
        </div>
      </div>
    </transition>

    <!-- 桌面端: 固定侧边栏 -->
    <div v-if="isDesktop" class="layout-container">
      <aside class="sidebar">
        <div class="logo" :class="{ 'is-update-entry': isAdmin }" @click="openUpdateDialog">
          <div class="logo-icon">X</div>
          <div class="logo-text-wrap">
            <div class="logo-text">XianYuSmart</div>
            <div class="version-tag" :class="{ 'has-update': isAdmin && hasNewVersion }">
              v{{ currentVersion }}
              <span v-if="isAdmin && hasNewVersion" class="update-dot"></span>
            </div>
          </div>
        </div>
        <NavMenu />
      </aside>

      <div class="el-container">
        <main>
          <div v-if="pageReadOnly" class="readonly-notice">当前账号在此页面为只读权限，修改、发送和执行操作已停用。</div>
          <RouterView />
        </main>
      </div>
    </div>

    <!-- 平板端: 主内容区 -->
    <div v-if="isTablet" class="el-container">
      <main>
        <div v-if="pageReadOnly" class="readonly-notice">当前账号在此页面为只读权限，修改、发送和执行操作已停用。</div>
        <RouterView />
      </main>
    </div>

    <!-- 手机端: 主内容区 -->
    <div v-if="isMobile" class="el-container">
      <main>
        <div v-if="pageReadOnly" class="readonly-notice">当前账号在此页面为只读权限，修改、发送和执行操作已停用。</div>
        <RouterView />
      </main>
    </div>

    <UpdateDialog v-if="isAdmin" ref="updateDialog" />
  </div>
</template>

<style scoped>
.app-layout {
  height: 100vh;
  height: 100dvh;
  min-height: 100svh;
  background: var(--bg-gradient);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.logo.is-update-entry {
  cursor: pointer;
}

.update-notice {
  position: fixed;
  top: 10px;
  right: 16px;
  z-index: 1100;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 8px 12px;
  border: 1px solid rgba(21, 94, 239, .2);
  border-radius: 7px;
  color: #155eef;
  background: #fff;
  box-shadow: 0 5px 16px rgba(16, 24, 40, .1);
  cursor: pointer;
}

.update-notice span {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #155eef;
}

.layout-container {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: row;
}

.el-container {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

/* ========== 桌面端: 固定侧边栏 ========== */
.sidebar {
  background: #fcfcfd;
  -webkit-backdrop-filter: none;
  backdrop-filter: none;
  border-right: 1px solid #eaecf0;
  box-shadow: 2px 0 10px rgba(16, 24, 40, .025);
  transition: width 0.3s ease;
  overflow-y: auto;
  overflow-x: hidden;
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.sidebar::-webkit-scrollbar {
  display: none; /* Chrome, Safari, Opera */
}

.logo {
  display: flex;
  align-items: center;
  padding: 18px 20px 16px;
  border-bottom: none;
  gap: 10px;
}

.logo-icon {
  width: 32px;
  height: 32px;
  background: #155eef;
  border: 1px solid #155eef;
  box-shadow: 0 4px 10px rgba(21, 94, 239, .18);
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-size: 18px;
  font-weight: bold;
  flex-shrink: 0;
}

.logo-text {
  font-size: 16px;
  font-weight: 600;
  color: var(--apple-text);
}

.logo-text-wrap {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.version-tag {
  font-size: 11px;
  color: var(--apple-text2);
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.version-tag.has-update {
  color: var(--ab);
}

.update-dot {
  width: 6px;
  height: 6px;
  background: #f56c6c;
  border-radius: 50%;
  display: inline-block;
  animation: pulse-dot 1.5s ease-in-out infinite;
}

@keyframes pulse-dot {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}



main {
  padding: 0;
  overflow: auto;
  background: transparent;
  min-height: 0;
  box-sizing: border-box;
  scrollbar-width: thin;
  scrollbar-color: #c9cdd4 transparent;
  -ms-overflow-style: none;
  flex: 1;
  overscroll-behavior-y: contain;
  -webkit-overflow-scrolling: touch;
}

main::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}

.readonly-notice {
  margin: 10px 14px 0;
  padding: 9px 12px;
  border: 1px solid #fedf89;
  border-radius: 6px;
  color: #93370d;
  background: #fffaeb;
  font-size: 13px;
}


/* ========== 平板端: 顶部导航栏 ========== */
.tablet-header {
  display: flex;
  justify-content: flex-start;
  align-items: center;
  padding: 14px 20px;
  background: var(--glass-bg);
  -webkit-backdrop-filter: var(--glass-blur);
  backdrop-filter: var(--glass-blur);
  border-bottom: 1px solid var(--glass-border);
  box-shadow: var(--glass-shadow);
  z-index: 100;
  gap: 16px;
  height: 64px;
  box-sizing: border-box;
  flex-shrink: 0;
}

.tablet-page-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--apple-text);
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: clip;
  min-width: 0;
}

.header-title-section {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
  flex-shrink: 1;
  min-width: 0;
  overflow: hidden;
}

.header-page-icon {
  width: 24px;
  height: 24px;
  color: var(--apple-text);
  flex-shrink: 1;
  min-width: 0;
  overflow: hidden;
}

.header-content-slot {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-shrink: 0;
}

/* ========== 手机端: 顶部导航栏 ========== */
.mobile-header {
  display: flex;
  justify-content: flex-start;
  align-items: center;
  padding: 12px 16px;
  background: var(--glass-bg);
  -webkit-backdrop-filter: var(--glass-blur);
  backdrop-filter: var(--glass-blur);
  border-bottom: 1px solid var(--glass-border);
  box-shadow: var(--glass-shadow);
  z-index: 100;
  gap: 12px;
  height: 56px;
  box-sizing: border-box;
  flex-shrink: 0;
}

.mobile-page-title {
  font-size: 17px;
  font-weight: 600;
  color: var(--apple-text);
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: clip;
  min-width: 0;
}

.header-content-slot {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-shrink: 0;
}

.menu-toggle-btn {
  width: 44px;
  height: 44px;
  background: #155eef;
  border: 1px solid #155eef;
  border-radius: 6px;
  box-shadow: none;
  color: white;
  font-size: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  padding: 0;
  cursor: pointer;
}

.menu-toggle-btn:hover,
.menu-toggle-btn:active,
.menu-toggle-btn:focus {
  background: #004eeb;
  border-color: #004eeb;
  outline: none;
}

.menu-icon {
  font-size: 22px;
  line-height: 1;
  display: block;
}

/* ========== 左侧抽屉菜单（手机端和平板端共用） ========== */
.drawer-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0,0,0,0.30);
  -webkit-backdrop-filter: none;
  backdrop-filter: none;
  z-index: 1000;
  display: flex;
  align-items: stretch;
}

.drawer-menu {
  width: 280px;
  max-width: 80vw;
  background: #fff;
  -webkit-backdrop-filter: none;
  backdrop-filter: none;
  border-right: 1px solid #eaecf0;
  box-shadow: 12px 0 32px rgba(16, 24, 40, .14);
  display: flex;
  flex-direction: column;
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
}

.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid #eaecf0;
  flex-shrink: 0;
  background: transparent;
}

.drawer-header .logo {
  padding: 0;
  flex: 1;
}

.drawer-close-btn {
  width: 32px;
  height: 32px;
  background: #f9fafb;
  border: 1px solid #e4e7ec;
  border-radius: 8px;
  color: var(--apple-text2);
  -webkit-backdrop-filter: none;
  backdrop-filter: none;
  font-size: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  padding: 0;
  margin-left: 12px;
  cursor: pointer;
}

.drawer-close-btn:hover,
.drawer-close-btn:active {
  background: #f2f4f7;
  border-color: #d0d5dd;
  color: #344054;
  outline: none;
}

.close-icon {
  font-size: 18px;
  line-height: 1;
  display: block;
}

.drawer-content {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 8px 0;
  -webkit-overflow-scrolling: touch;
  /* 隐藏滚动条 */
  scrollbar-width: none; /* Firefox */
  -ms-overflow-style: none; /* IE and Edge */
}

.drawer-content::-webkit-scrollbar {
  display: none; /* Chrome, Safari, Opera */
}

/* 抽屉动画 */
.drawer-enter-active,
.drawer-leave-active {
  transition: opacity 240ms ease;
}

.drawer-enter-active .drawer-menu,
.drawer-leave-active .drawer-menu {
  transition: transform 240ms cubic-bezier(.2, .8, .2, 1);
}

.drawer-enter-from,
.drawer-leave-to {
  opacity: 0;
}

.drawer-enter-from .drawer-menu {
  transform: translateX(-100%);
}

.drawer-leave-to .drawer-menu {
  transform: translateX(-100%);
}

@media (prefers-reduced-motion: reduce) {
  .drawer-enter-active,
  .drawer-leave-active,
  .drawer-enter-active .drawer-menu,
  .drawer-leave-active .drawer-menu {
    transition: none;
  }
}

/* ========== 响应式适配 ========== */
/* 平板模式 (768px - 1024px) */
@media screen and (min-width: 768px) and (max-width: 1024px) {
  .tablet-header {
    padding: 12px 18px;
    height: 60px;
  }

  .tablet-page-title {
    font-size: 17px;
  }

  .menu-toggle-btn {
    width: 42px;
    height: 42px;
    font-size: 20px;
  }

  .menu-icon {
    font-size: 20px;
  }

  main {
    padding: 0;
  }

  .drawer-menu {
    width: 260px;
  }

  .drawer-header {
    padding: 14px 18px;
  }


}

/* 手机模式 (< 768px) */
@media (max-width: 767px) {
  .mobile-header {
    padding: 10px 14px;
    height: 52px;
  }

  .mobile-page-title {
    font-size: 16px;
  }

  .menu-toggle-btn {
    width: 40px;
    height: 40px;
    font-size: 20px;
  }

  .menu-icon {
    font-size: 20px;
  }

  main {
    padding: 0;
    overflow: auto;
    padding-bottom: max(12px, env(safe-area-inset-bottom));
  }

  .drawer-menu {
    width: 260px;
  }

  .drawer-header {
    padding: 12px 16px;
  }

  .drawer-close-btn {
    width: 30px;
    height: 30px;
    font-size: 16px;
  }

  .close-icon {
    font-size: 16px;
  }


}

/* 小屏手机模式 (< 480px) */
@media (max-width: 480px) {
  .mobile-header {
    padding: 8px 12px;
    height: 48px;
  }

  .mobile-page-title {
    font-size: 15px;
  }

  .menu-toggle-btn {
    width: 36px;
    height: 36px;
    font-size: 18px;
  }

  .menu-icon {
    font-size: 18px;
  }

  main {
    padding: 0;
    overflow: auto;
    padding-bottom: max(12px, env(safe-area-inset-bottom));
  }

  .drawer-menu {
    width: 240px;
  }

  .drawer-header {
    padding: 10px 14px;
  }

  .drawer-header .logo-icon {
    width: 28px;
    height: 28px;
    font-size: 16px;
  }

  .drawer-header .logo-text {
    font-size: 15px;
  }

  .drawer-close-btn {
    width: 28px;
    height: 28px;
    font-size: 14px;
  }

  .close-icon {
    font-size: 14px;
  }


}
</style>
