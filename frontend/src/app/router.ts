import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../modules/auth/auth.store'

const routes = [
  { path: '/auth', component: () => import('../modules/auth/AuthView.vue'), meta: { public: true } },
  { path: '/desktop-pet', component: () => import('../modules/partners/DesktopPetWindow.vue') },
  { path: '/onboarding', component: () => import('../modules/onboarding/OnboardingView.vue') },
  { path: '/', component: () => import('./UserLayout.vue'), children: [
    { path: '', redirect: '/today' },
    { path: 'today', component: () => import('../modules/today/TodayView.vue') },
    { path: 'goals', component: () => import('../modules/goals/GoalsView.vue') },
    { path: 'partners', component: () => import('../modules/partners/PartnersView.vue') },
    { path: 'insights', component: () => import('../modules/insights/InsightsView.vue') },
    { path: 'ai', component: () => import('../modules/ai/AiView.vue') },
    { path: 'settings', component: () => import('../modules/settings/SettingsView.vue') },
  ] },
  { path: '/admin', component: () => import('./AdminLayout.vue'), meta: { admin: true }, children: [
    { path: '', component: () => import('../modules/admin/AdminDashboard.vue') },
  ] },
]

export const router = createRouter({ history: createWebHistory(), routes })
router.beforeEach(async to => {
  const auth = useAuthStore()
  if (!auth.initialized) await auth.load()
  if (!to.meta.public && !auth.signedIn) return '/auth'
  if (to.meta.admin && !auth.isAdmin) return '/today'
  if (to.path === '/auth' && auth.signedIn) return '/today'
})
