import { createRouter, createWebHashHistory } from 'vue-router'
import { getToken } from '@/utils/storage'

const routes = [
  { path: '/', redirect: '/workflows' },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录', guestOnly: true }
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/Register.vue'),
    meta: { title: '注册', guestOnly: true }
  },
  {
    path: '/workflows',
    name: 'workflow-list',
    component: () => import('@/views/WorkflowList.vue'),
    meta: { title: '我的工作流', requiresAuth: true }
  },
  {
    path: '/workflows/:id/edit',
    name: 'workflow-edit',
    component: () => import('@/views/WorkflowEdit.vue'),
    meta: { title: '工作流编辑器', requiresAuth: true }
  },
  {
    path: '/executions',
    name: 'executions',
    component: () => import('@/views/Executions.vue'),
    meta: { title: '执行历史', requiresAuth: true }
  },
  {
    path: '/knowledge',
    name: 'knowledge',
    component: () => import('@/views/Knowledge.vue'),
    meta: { title: '知识库', requiresAuth: true }
  },
  { path: '/:pathMatch(.*)*', redirect: '/workflows' }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach((to) => {
  const token = getToken()

  if (to.meta.requiresAuth && !token) {
    return { path: '/login', query: to.fullPath !== '/workflows' ? { redirect: to.fullPath } : {} }
  }
  if (to.meta.guestOnly && token) {
    return { path: '/workflows' }
  }
  return true
})

router.afterEach((to) => {
  const title = to.meta?.title
  document.title = title ? `FlowMind · ${title}` : 'FlowMind'
})

export default router
