import { createRouter, createWebHistory } from 'vue-router'

import MainLayout from '@/layouts/MainLayout.vue'
import { useUserStore } from '@/stores/user'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },
    {
      path: '/',
      component: MainLayout,
      redirect: '/jobs',
      children: [
        { path: 'projects', component: () => import('@/views/ProjectsView.vue'), meta: { title: '项目' } },
        { path: 'datasources', component: () => import('@/views/DatasourcesView.vue'), meta: { title: '数据源' } },
        { path: 'jobs', component: () => import('@/views/jobs/JobsView.vue'), meta: { title: '任务' } },
        { path: 'jobs/:id', component: () => import('@/views/jobs/JobDetailView.vue'), meta: { title: '任务详情', menu: '/jobs' } },
        { path: 'instances', component: () => import('@/views/instances/InstancesView.vue'), meta: { title: '运行实例' } },
        {
          path: 'instances/:id',
          component: () => import('@/views/instances/InstanceDetailView.vue'),
          meta: { title: '运行详情', menu: '/instances' },
        },
        { path: 'cluster', component: () => import('@/views/ClusterView.vue'), meta: { title: '集群' } },
      ],
    },
  ],
})

router.beforeEach(async (to) => {
  if (to.meta.public) {
    return true
  }
  const loggedIn = await useUserStore().ensureLoaded()
  return loggedIn ? true : { path: '/login', query: { redirect: to.fullPath } }
})

export default router
