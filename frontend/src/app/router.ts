import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../modules/auth/auth.store'

const routes = [
  { path: '/town/demo', component: () => import('../modules/town/demo/CafeDemo.vue'), meta: { public: true, standaloneDemo: true } },
  { path: '/auth', component: () => import('../modules/auth/AuthView.vue'), meta: { public: true } },
  { path: '/desktop-pet', component: () => import('../modules/partners/DesktopPetWindow.vue') },
  { path: '/onboarding', component: () => import('../modules/onboarding/OnboardingView.vue') },
  { path: '/town/immersive', component: () => import('../modules/town/immersive/ImmersiveTown.vue') },
  { path: '/', component: () => import('./UserLayout.vue'), children: [
    { path: '', redirect: '/today' },
    { path: 'today', component: () => import('../modules/today/TodayEntry.vue') },
    { path: 'goals', component: () => import('../modules/goals/GoalsView.vue') },
    { path: 'partners', component: () => import('../modules/partners/PartnersView.vue') },
    { path: 'town', component: () => import('../modules/town/TownView.vue') },
    { path: 'friends', component: () => import('../modules/friends/FriendsView.vue') },
    { path: 'friends/chat', component: () => import('../modules/friends/ConversationsView.vue') },
    { path: 'friends/groups/:publicId', component: () => import('../modules/friends/GroupChatView.vue'), props: true },
    { path: 'friends/:publicId', component: () => import('../modules/friends/FriendDetailView.vue'), props: true },
    { path: 'friends/:publicId/chat', component: () => import('../modules/friends/ChatView.vue'), props: true },
    { path: 'attributes', component: () => import('../modules/attributes/AttributesView.vue') },
    { path: 'insights', component: () => import('../modules/insights/InsightsView.vue') },
    { path: 'ai', component: () => import('../modules/ai/AiView.vue') },
    { path: 'profile', component: () => import('../modules/profile/ProfileView.vue') },
    { path: 'settings', component: () => import('../modules/settings/SettingsView.vue') },
  ] },
  { path: '/admin', component: () => import('./AdminLayout.vue'), meta: { admin: true }, children: [
    { path: '', component: () => import('../modules/admin/AdminDashboard.vue') },
  ] },
]

export const router = createRouter({ history: createWebHistory(), routes })
router.beforeEach(async to => {
  if (to.meta.standaloneDemo) return
  const auth = useAuthStore()
  if (!auth.initialized) await auth.load()
  if (!to.meta.public && !auth.signedIn) return '/auth'
  if (to.meta.admin && !auth.isAdmin) return '/today'
  if (to.path === '/auth' && auth.signedIn) return auth.isAdmin ? '/admin' : '/today'
})
