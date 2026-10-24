import { createRouter, createWebHistory } from 'vue-router'

import MainLayout from '@/layouts/MainLayout.vue'
import { useUserStore } from '@/stores/user'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },
    { path: '/change-password', component: () => import('@/views/ChangePasswordView.vue') },
    {
      path: '/',
      component: MainLayout,
      redirect: '/jobs',
      children: [
        { path: 'projects', component: () => import('@/views/ProjectsView.vue'), meta: { title: '项目' } },
        { path: 'datasources', component: () => import('@/views/DatasourcesView.vue'), meta: { title: '数据源' } },
        { path: 'jobs', component: () => import('@/views/jobs/JobsView.vue'), meta: { title: '任务' } },
        {
          // The tab is part of the path (the config tab has none), so refresh and copied links keep it.
          path: 'jobs/:id/:tab(schedule|backfills|versions|runs)?',
          component: () => import('@/views/jobs/JobDetailView.vue'),
          meta: { title: '任务详情', menu: '/jobs' },
        },
        { path: 'instances', component: () => import('@/views/instances/InstancesView.vue'), meta: { title: '运行实例' } },
        {
          path: 'instances/:id',
          component: () => import('@/views/instances/InstanceDetailView.vue'),
          meta: { title: '运行详情', menu: '/instances' },
        },
        { path: 'cluster', component: () => import('@/views/ClusterView.vue'), meta: { title: '集群健康检查' } },
        { path: 'users', component: () => import('@/views/UsersView.vue'), meta: { title: '用户管理', admin: true } },
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
    return '/jobs'
  }
  return true
})

export default router
