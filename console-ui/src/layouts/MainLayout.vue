<script setup lang="ts">
import { ArrowDown, Coin, Connection, DArrowLeft, DArrowRight, Folder, Monitor, Tickets } from '@element-plus/icons-vue'
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const title = computed(() => (route.meta.title as string | undefined) ?? '')
const activeMenu = computed(() => (route.meta.menu as string | undefined) ?? route.path)

function readCollapsed(): boolean {
  try {
    return localStorage.getItem('sider-collapsed') === '1'
  } catch {
    return false
  }
}

const collapsed = ref(readCollapsed())

function toggle() {
  collapsed.value = !collapsed.value
  try {
    localStorage.setItem('sider-collapsed', collapsed.value ? '1' : '0')
  } catch {
    // remembered only when storage is available
  }
}

const menus = [
  { path: '/projects', title: '项目', icon: Folder },
  { path: '/datasources', title: '数据源', icon: Coin },
  { path: '/jobs', title: '任务', icon: Tickets },
  { path: '/instances', title: '运行实例', icon: Connection },
  { path: '/cluster', title: '集群健康', icon: Monitor },
]

const roleText = computed(() => (userStore.user?.role === 'ADMIN' ? '管理员' : '普通用户'))

async function logout() {
  await userStore.logout()
  router.replace('/login')
}
</script>

<template>
  <div class="layout">
    <header class="header">
      <div class="brand">
        <span class="logo">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"
            stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M2 12c2.5 0 2.5-3 5-3s2.5 3 5 3 2.5-3 5-3 2.5 3 5 3" />
            <path d="M2 18c2.5 0 2.5-3 5-3s2.5 3 5 3 2.5-3 5-3 2.5 3 5 3" />
            <path d="M2 6c2.5 0 2.5-3 5-3s2.5 3 5 3 2.5-3 5-3 2.5 3 5 3" />
          </svg>
        </span>
        <span class="brand-name">SeaTunnel Console</span>
        <span v-if="title" class="divider" />
        <span v-if="title" class="page-title">{{ title }}</span>
      </div>
      <el-dropdown v-if="userStore.user" trigger="click">
        <span class="user">
          <span class="avatar">{{ (userStore.user.displayName || userStore.user.username).slice(0, 1).toUpperCase() }}</span>
          {{ userStore.user.displayName || userStore.user.username }}
          <span class="role">{{ roleText }}</span>
          <el-icon class="caret"><ArrowDown /></el-icon>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item @click="logout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </header>

    <div class="body">
      <aside class="sider" :class="{ collapsed }">
        <div class="collapse" :title="collapsed ? '展开菜单' : '收起菜单'" @click="toggle">
          <el-icon><component :is="collapsed ? DArrowRight : DArrowLeft" /></el-icon>
        </div>
        <el-menu :default-active="activeMenu" :collapse="collapsed" :collapse-transition="false" router class="menu">
          <el-menu-item v-for="menu in menus" :key="menu.path" :index="menu.path">
            <el-icon><component :is="menu.icon" /></el-icon>
            <template #title>{{ menu.title }}</template>
          </el-menu-item>
        </el-menu>
      </aside>
      <main class="content">
        <router-view :key="route.fullPath" />
      </main>
    </div>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.header {
  position: relative;
  z-index: 10;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--app-header-height);
  padding: 0 24px 0 20px;
  background: #fff;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  color: #fff;
  background: linear-gradient(135deg, #4096ff, #1677ff);
  flex: none;
}

.brand-name {
  font-size: 18px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  white-space: nowrap;
}

.divider {
  width: 1px;
  height: 16px;
  background: var(--el-border-color);
}

.page-title {
  color: var(--el-text-color-regular);
  white-space: nowrap;
}

.user {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 36px;
  padding: 0 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  cursor: pointer;
  color: var(--el-text-color-primary);
}

.user:hover {
  border-color: var(--el-color-primary-light-5);
}

.avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  font-size: 12px;
  color: #fff;
  background: var(--el-color-primary);
}

.role {
  padding: 0 6px;
  font-size: 12px;
  line-height: 20px;
  color: #fa541c;
  background: #fff2e8;
  border: 1px solid #ffbb96;
  border-radius: 4px;
}

.caret {
  color: var(--el-text-color-secondary);
}

.body {
  flex: 1;
  display: flex;
  min-height: 0;
}

.sider {
  flex: none;
  width: 220px;
  background: var(--app-sider-bg);
  transition: width 0.2s;
  overflow-x: hidden;
  overflow-y: auto;
}

.sider.collapsed {
  width: 64px;
}

.collapse {
  display: flex;
  justify-content: flex-end;
  padding: 16px 20px 8px;
  color: rgba(255, 255, 255, 0.75);
  cursor: pointer;
}

.sider.collapsed .collapse {
  justify-content: center;
  padding: 16px 0 8px;
}

.collapse:hover {
  color: #fff;
}

.menu {
  --el-menu-bg-color: transparent;
  --el-menu-text-color: rgba(255, 255, 255, 0.9);
  --el-menu-hover-text-color: #fff;
  --el-menu-active-color: #fff;
  --el-menu-hover-bg-color: var(--app-sider-hover-bg);
  --el-menu-item-height: 52px;
  --el-menu-item-font-size: 15px;
  border-right: none;
}

.menu :deep(.el-menu-item) {
  padding-left: 24px !important;
}

.menu.el-menu--collapse :deep(.el-menu-item) {
  padding-left: 22px !important;
}

.menu :deep(.el-menu-item.is-active) {
  background: var(--app-sider-active-bg);
  font-weight: 500;
}

.menu :deep(.el-menu-item .el-icon) {
  font-size: 18px;
}

.content {
  flex: 1;
  min-width: 0;
  padding: 16px;
  overflow: auto;
}

@media (max-width: 768px) {
  .brand-name,
  .divider,
  .page-title,
  .role {
    display: none;
  }

  .sider {
    width: 64px;
  }
}
</style>
