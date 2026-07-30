import { computed, ref } from 'vue'
import { getCurrentUser, type CurrentUser } from '@/api/system'

export const permissionState = ref<CurrentUser>()

let loadingPromise: Promise<CurrentUser | undefined> | undefined

export async function loadCurrentUser(force = false) {
  if (permissionState.value && !force) return permissionState.value
  if (!loadingPromise || force) {
    loadingPromise = getCurrentUser()
      .then(response => {
        permissionState.value = response.data
        return response.data
      })
      .finally(() => {
        loadingPromise = undefined
      })
  }
  return loadingPromise
}

export const isPlatformAdmin = computed(() => permissionState.value?.role === 'ADMIN')

export function hasPermission(code: string) {
  return isPlatformAdmin.value || permissionState.value?.permissions?.includes(code) === true
}

export function updateMenuLayout(menuLayout: string) {
  if (permissionState.value) permissionState.value.menuLayout = menuLayout
}

const menuPaths: Array<[string, string]> = [
  ['menu:dashboard', '/dashboard'],
  ['menu:accounts', '/accounts'],
  ['menu:connection', '/connection'],
  ['menu:goods', '/goods'],
  ['menu:operations', '/operations'],
  ['menu:messages', '/messages'],
  ['menu:buyers', '/buyers'],
  ['menu:kami', '/kami-config'],
  ['menu:fixed-delivery', '/fixed-delivery-templates'],
  ['menu:auto-delivery', '/auto-delivery'],
  ['menu:orders', '/orders'],
  ['menu:auto-reply', '/auto-reply'],
  ['menu:operation-log', '/operation-log'],
  ['menu:health', '/operations-health'],
  ['menu:settings', '/settings']
]

export function firstAccessiblePath() {
  if (isPlatformAdmin.value) return '/dashboard'
  return menuPaths.find(([permission]) => hasPermission(permission))?.[1] || '/login'
}
