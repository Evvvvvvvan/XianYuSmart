# Tenant Menu Customization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为每个租户提供可持久化的菜单模块与模块内菜单拖动排序，并将全局导航改造成简约、流畅的数据驱动菜单。

**Architecture:** 复用 `xianyu_sys_setting` 的租户隔离能力保存版本化 JSON，当前用户接口一次性返回布局，避免导航额外请求和加载闪动。前端建立唯一菜单目录，导航渲染和设置页排序共用归一化、移动和序列化逻辑。

**Tech Stack:** Spring Boot 3.5、MyBatis Plus 租户拦截器、Vue 3、TypeScript、Vue TransitionGroup、原生 HTML5 Drag and Drop

---

## 文件结构

- Create: `vue-code/src/config/menu.ts` — 菜单目录、布局归一化、移动和序列化。
- Modify: `src/main/java/com/xianyusmart/controller/dto/CurrentUserRespDTO.java` — 返回当前租户菜单布局。
- Modify: `src/main/java/com/xianyusmart/controller/SystemController.java` — 从现有租户配置服务读取布局。
- Modify: `vue-code/src/api/system.ts` — 声明 `menuLayout` 响应字段。
- Modify: `vue-code/src/utils/permission.ts` — 提供响应式菜单布局更新方法。
- Modify: `vue-code/src/components/layout/NavMenu.vue` — 数据驱动渲染并优化全局菜单 UI。
- Modify: `vue-code/src/views/settings/index.vue` — 增加菜单管理、拖动排序、移动按钮、保存与恢复默认。
- Temporary Test: `vue-code/src/config/menu.test.ts` — 菜单布局纯函数 RED/GREEN 验证，完成后删除。
- Temporary Test: `src/test/java/com/xianyusmart/controller/SystemControllerMenuLayoutTest.java` — 当前用户布局 RED/GREEN 验证，完成后删除。

### Task 1: 菜单目录与布局算法

**Files:**
- Create: `vue-code/src/config/menu.test.ts`
- Create: `vue-code/src/config/menu.ts`

- [ ] **Step 1: 编写失败的临时测试**

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  createDefaultMenuLayout,
  moveMenuGroup,
  moveMenuItem,
  normalizeMenuLayout,
  serializeMenuLayout
} from './menu.ts'

test('非法配置回退默认布局', () => {
  assert.deepEqual(normalizeMenuLayout('{'), createDefaultMenuLayout())
})

test('过滤未知和重复项并补齐缺失项', () => {
  const layout = normalizeMenuLayout(JSON.stringify({
    version: 1,
    groups: [
      { id: 'customers', items: ['messages', 'messages', 'unknown'] },
      { id: 'unknown', items: [] }
    ]
  }))
  assert.equal(layout.groups[0].id, 'customers')
  assert.equal(layout.groups[0].items[0], 'messages')
  assert.equal(new Set(layout.groups.map(group => group.id)).size, layout.groups.length)
  assert.ok(layout.groups.some(group => group.id === 'overview'))
  assert.ok(layout.groups.find(group => group.id === 'customers')?.items.includes('buyers'))
})

test('模块和模块内菜单按目标位置移动', () => {
  const layout = createDefaultMenuLayout()
  const movedGroup = moveMenuGroup(layout, 'system', 'overview')
  assert.equal(movedGroup.groups[0].id, 'system')
  const movedItem = moveMenuItem(layout, 'products', 'workflows', 'goods')
  assert.equal(movedItem.groups.find(group => group.id === 'products')?.items[0], 'workflows')
  assert.deepEqual(normalizeMenuLayout(serializeMenuLayout(movedItem)), movedItem)
})
```

- [ ] **Step 2: 运行测试确认 RED**

Run:

```powershell
node --experimental-strip-types --test vue-code/src/config/menu.test.ts
```

Expected: FAIL，原因是 `menu.ts` 不存在。

- [ ] **Step 3: 实现最小菜单模型**

`menu.ts` 定义：

```ts
export interface MenuItemDefinition {
  id: string
  label: string
  path: string
  permission?: string
  adminOnly?: boolean
  icon: string
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
  if (!parsed || typeof parsed !== 'object') return createDefaultMenuLayout()
  const rawGroups = (parsed as { groups?: unknown }).groups
  if (!Array.isArray(rawGroups)) return createDefaultMenuLayout()

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
    for (const item of definition.items) {
      if (!seenItems.has(item.id)) items.push(item.id)
    }
    groups.push({ id: definition.id, items })
  }
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
  const [source] = groups.splice(sourceIndex, 1)
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
  const [source] = group.items.splice(sourceIndex, 1)
  group.items.splice(targetIndex, 0, source)
  return { version: 1, groups }
}

export function serializeMenuLayout(layout: MenuLayout): string {
  return JSON.stringify(normalizeMenuLayout(layout))
}
```

- [ ] **Step 4: 运行测试确认 GREEN**

Run:

```powershell
node --experimental-strip-types --test vue-code/src/config/menu.test.ts
```

Expected: 3 tests passed，0 failed。

### Task 2: 当前用户接口携带租户布局

**Files:**
- Create: `src/test/java/com/xianyusmart/controller/SystemControllerMenuLayoutTest.java`
- Modify: `src/main/java/com/xianyusmart/controller/dto/CurrentUserRespDTO.java`
- Modify: `src/main/java/com/xianyusmart/controller/SystemController.java`
- Modify: `vue-code/src/api/system.ts`
- Modify: `vue-code/src/utils/permission.ts`

- [ ] **Step 1: 编写失败的控制器临时测试**

```java
package com.xianyusmart.controller;

import com.xianyusmart.entity.SysUser;
import com.xianyusmart.service.AuthService;
import com.xianyusmart.service.PlatformPermissionService;
import com.xianyusmart.service.SysSettingService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemControllerMenuLayoutTest {

    @Test
    void currentUserReturnsTenantMenuLayout() {
        SystemController controller = new SystemController();
        AuthService authService = mock(AuthService.class);
        PlatformPermissionService permissionService = mock(PlatformPermissionService.class);
        SysSettingService settingService = mock(SysSettingService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        SysUser user = new SysUser();
        user.setId(7L);
        user.setUsername("tenant");
        user.setRole(SysUser.ROLE_USER);

        when(request.getAttribute("currentUserId")).thenReturn(7L);
        when(authService.getCurrentUser(7L)).thenReturn(user);
        when(permissionService.getPermissionCodes(7L)).thenReturn(List.of("menu:dashboard"));
        when(settingService.getSettingValue("menu_layout")).thenReturn("{\"version\":1,\"groups\":[]}");
        ReflectionTestUtils.setField(controller, "authService", authService);
        ReflectionTestUtils.setField(controller, "permissionService", permissionService);
        ReflectionTestUtils.setField(controller, "sysSettingService", settingService);

        assertEquals("{\"version\":1,\"groups\":[]}",
                controller.getCurrentUser(request).getData().getMenuLayout());
    }
}
```

- [ ] **Step 2: 使用 JDK 21 运行测试确认 RED**

Run:

```powershell
$env:JAVA_HOME='E:\java\jdk21'
$env:Path="E:\java\jdk21\bin;$env:Path"
.\mvnw.cmd -Dtest=SystemControllerMenuLayoutTest test
```

Expected: FAIL，原因是 DTO 和控制器尚无菜单布局字段。

- [ ] **Step 3: 实现当前用户布局**

在 DTO 增加：

```java
private String menuLayout;
```

在 `SystemController` 复用 `SysSettingService`：

```java
private static final String MENU_LAYOUT_SETTING_KEY = "menu_layout";

@Autowired
private SysSettingService sysSettingService;
```

构造当前用户响应时增加：

```java
respDTO.setMenuLayout(sysSettingService.getSettingValue(MENU_LAYOUT_SETTING_KEY));
```

前端 `CurrentUser` 增加：

```ts
menuLayout?: string
```

权限工具增加：

```ts
export function updateMenuLayout(menuLayout: string) {
  if (permissionState.value) permissionState.value.menuLayout = menuLayout
}
```

- [ ] **Step 4: 运行测试确认 GREEN**

Run: 与 Step 2 相同。

Expected: 1 test passed，0 failed。

### Task 3: 数据驱动全局菜单与简约 UI

**Files:**
- Modify: `vue-code/src/components/layout/NavMenu.vue`

- [ ] **Step 1: 使用菜单目录生成可见模块**

脚本使用 `permissionState` 中的布局，并保留权限规则：

```ts
const iconComponents: Record<string, Component> = {
  alert: markRaw(IconAlert),
  chart: markRaw(IconChart),
  clipboard: markRaw(IconClipboard),
  dashboard: markRaw(IconChart),
  goods: markRaw(IconShoppingBag),
  key: markRaw(IconKey),
  link: markRaw(IconLink),
  log: markRaw(IconLog),
  message: markRaw(IconMessage),
  package: markRaw(IconPackage),
  plus: markRaw(IconPlus),
  robot: markRaw(IconRobot),
  search: markRaw(IconSearch),
  send: markRaw(IconSend),
  shield: markRaw(IconShield),
  text: markRaw(IconText),
  tooling: markRaw(IconTooling),
  truck: markRaw(IconTruck),
  user: markRaw(IconUser),
  users: markRaw(IconUsers)
}

const visibleGroups = computed(() => {
  const layout = normalizeMenuLayout(permissionState.value?.menuLayout)
  return layout.groups
    .map(groupLayout => {
      const definition = MENU_GROUPS.find(group => group.id === groupLayout.id)
      const items = groupLayout.items
        .map(itemId => definition?.items.find(item => item.id === itemId))
        .filter(item => item && (!item.permission || hasPermission(item.permission))
          && (!item.adminOnly || isPlatformAdmin.value))
      return definition && items.length ? { ...definition, items } : null
    })
    .filter(Boolean)
})
```

- [ ] **Step 2: 使用 `TransitionGroup` 渲染模块和菜单**

模板保持 `router-link`、`active-class` 和 `select` 事件不变，增加模块标题、统一图标容器和过渡组。管理员菜单仍受 `adminOnly` 控制。

- [ ] **Step 3: 优化导航样式**

实现：

- 统一 38px 菜单高度和 8px 圆角。
- 激活态浅蓝背景、左侧 3px 指示条、图标轻色块。
- 模块标题替换横线分割器，降低视觉噪声。
- hover、active 和列表移动使用 `180ms` 至 `240ms` 缓动。
- `prefers-reduced-motion: reduce` 下关闭动画。

- [ ] **Step 4: 运行前端类型检查**

Run:

```powershell
npm.cmd run type-check
```

Working directory: `vue-code`

Expected: exit 0。

### Task 4: 菜单管理拖动排序

**Files:**
- Modify: `vue-code/src/views/settings/index.vue`

- [ ] **Step 1: 增加菜单管理状态和操作**

复用 Task 1 纯函数：

```ts
const menuLayout = ref(createDefaultMenuLayout())
const menuLayoutSaving = ref(false)
const draggedGroupId = ref('')
const draggedItem = ref({ groupId: '', itemId: '' })

function moveGroup(sourceId: string, targetId: string) {
  menuLayout.value = moveMenuGroup(menuLayout.value, sourceId, targetId)
}

function moveItem(groupId: string, sourceId: string, targetId: string) {
  menuLayout.value = moveMenuItem(menuLayout.value, groupId, sourceId, targetId)
}

async function saveMenuLayout() {
  const settingValue = serializeMenuLayout(menuLayout.value)
  const response = await saveSetting({
    settingKey: MENU_LAYOUT_SETTING_KEY,
    settingValue,
    settingDesc: '租户菜单布局'
  })
  if (response.code === 200) {
    updateMenuLayout(settingValue)
    toast.success('菜单排序已保存')
  } else {
    toast.error(response.msg || '菜单排序保存失败')
  }
}
```

- [ ] **Step 2: 增加菜单管理入口和模板**

设置侧栏新增“菜单管理”。面板使用嵌套 `TransitionGroup`，模块和菜单项均设置 `draggable="true"`；`dragenter.prevent` 实时移动。每行增加上移、下移按钮，按钮禁用状态根据首尾位置计算。

- [ ] **Step 3: 增加排序交互样式**

实现模块卡片、拖动手柄、菜单行、拖动透明度、保存操作栏和位移动画。移动端改为单列，保留上下移动按钮。

- [ ] **Step 4: 运行前端类型检查**

Run:

```powershell
npm.cmd run type-check
```

Expected: exit 0。

### Task 5: 清理临时测试并完成验证

**Files:**
- Delete: `vue-code/src/config/menu.test.ts`
- Delete: `src/test/java/com/xianyusmart/controller/SystemControllerMenuLayoutTest.java`

- [ ] **Step 1: 删除本次临时测试**

仅删除 Task 1、Task 2 新增的两个临时文件，不删除原有测试目录和文件。

- [ ] **Step 2: 重新运行后端验证**

Run:

```powershell
$env:JAVA_HOME='E:\java\jdk21'
$env:Path="E:\java\jdk21\bin;$env:Path"
.\mvnw.cmd test
```

Expected: BUILD SUCCESS，Java 21。

- [ ] **Step 3: 运行前端正式验证**

Run:

```powershell
npm.cmd run type-check
npm.cmd run build:spring
```

Working directory: `vue-code`

Expected: 两条命令 exit 0。

- [ ] **Step 4: 审核差异**

Run:

```powershell
git diff --check
git status --short
git diff --stat
```

Expected: 无空白错误，仅包含设计、计划和本需求源代码文件；不存在临时测试文件和构建产物。

- [ ] **Step 5: 提交实现**

```powershell
git add -- src/main/java/com/xianyusmart/controller/SystemController.java src/main/java/com/xianyusmart/controller/dto/CurrentUserRespDTO.java vue-code/src/api/system.ts vue-code/src/utils/permission.ts vue-code/src/config/menu.ts vue-code/src/components/layout/NavMenu.vue vue-code/src/views/settings/index.vue docs/superpowers/plans/2026-07-30-tenant-menu-customization.md
git commit -m "feat: add tenant menu customization"
```
