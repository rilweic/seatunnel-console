import { createRouter, createWebHistory } from 'vue-router'

import MainLayout from '@/layouts/MainLayout.vue'
import { trackListUrls } from '@/composables/useBreadcrumb'
import { useUserStore } from '@/stores/user'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },
    { path: '/change-password', component: () => import('@/views/ChangePasswordView.vue') },
    {
      path: '/',
      component: MainLayout,
      redirect: '/dashboard',
      children: [
        { path: 'dashboard', component: () => import('@/views/DashboardView.vue'), meta: { title: '概览' } },
        { path: 'projects', component: () => import('@/views/ProjectsView.vue'), meta: { title: '项目', list: true } },
        { path: 'datasources', component: () => import('@/views/DatasourcesView.vue'), meta: { title: '数据源', list: true } },
        { path: 'jobs', component: () => import('@/views/jobs/JobsView.vue'), meta: { title: '离线同步', list: true } },
        {
          // Tabs and sections are part of the path (the first one has none), so refresh and links keep them.
          path: 'jobs/:id/edit/:section(schedule|basic)?',
          component: () => import('@/views/jobs/JobEditView.vue'),
          meta: { title: '编辑任务', menu: '/jobs' },
        },
        {
          path: 'jobs/:id/:tab(runs|backfills|versions)?',
          component: () => import('@/views/jobs/JobDetailView.vue'),
          meta: { title: '任务详情', menu: '/jobs' },
        },
        { path: 'instances', component: () => import('@/views/instances/InstancesView.vue'), meta: { title: '运行记录', list: true } },
        { path: 'realtime', component: () => import('@/views/ComingSoon.vue'), meta: { title: '实时同步', list: true } },
        {
          path: 'instances/:id',
          component: () => import('@/views/instances/InstanceDetailView.vue'),
          meta: { title: '运行详情', menu: '/instances' },
        },
        { path: 'cluster', component: () => import('@/views/ClusterView.vue'), meta: { title: '集群健康检查' } },
        { path: 'users', component: () => import('@/views/UsersView.vue'), meta: { title: '用户管理', admin: true } },
        { path: 'encrypt-keys', component: () => import('@/views/EncryptKeysView.vue'), meta: { title: '加密密钥', admin: true } },
      ],
    },
  ],
})

router.beforeEach(async (to) => {
  if (to.meta.public) {
    return true
  }
  const store = useUserStore()
  const loggedIn = await store.ensureLoaded()
  if (!loggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  // An initial or reset password must be replaced before anything else (the API enforces it too).
  if (store.user?.mustChangePassword && to.path !== '/change-password') {
    return { path: '/change-password', query: { redirect: to.fullPath } }
  }
  if (to.meta.admin && !store.isAdmin) {
    return '/dashboard'
  }
  return true
})

trackListUrls(router)

export default router
