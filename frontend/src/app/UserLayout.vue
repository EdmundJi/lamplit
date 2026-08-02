<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, RouterView } from 'vue-router'
import { Target, CalendarCheck2, ChartNoAxesColumnIncreasing, Heart, Sparkles, Settings, PawPrint } from 'lucide-vue-next'
import { useAuthStore } from '../modules/auth/auth.store'
import DesktopPet from '../modules/partners/DesktopPet.vue'
import WelcomeGuide from '../shared/ui/WelcomeGuide.vue'

const nav = [
  { to: '/today', label: '今日', icon: CalendarCheck2 },
  { to: '/goals', label: '目标', icon: Target },
  { to: '/partners', label: '伙伴', icon: PawPrint },
  { to: '/insights', label: '洞察', icon: ChartNoAxesColumnIncreasing },
  { to: '/ai', label: 'AI 助手', icon: Sparkles },
  { to: '/settings', label: '设置', icon: Settings },
]

const auth = useAuthStore()
const isDesktopCompanion = Boolean(window.betterSelfDesktop?.isDesktopApp)
const showWelcome = ref(false)
const welcomeKey = computed(() => `better-self:welcome:${auth.user?.publicId ?? 'guest'}`)
const brandInitial = computed(() => {
  const name = auth.user?.displayName?.trim() ?? ''
  if (!name) return '好'
  const characters = Array.from(name.replace(/\s+/g, ''))
  if (!characters.length) return '好'
  if (/\p{Script=Han}/u.test(characters[0])) return characters[0]
  const label = characters.slice(0, 2).join('')
  return label.charAt(0).toUpperCase() + label.slice(1)
})

function shouldShowWelcome() {
  return Boolean(auth.user?.publicId) && window.localStorage.getItem(welcomeKey.value) !== 'dismissed'
}

function openWelcome() {
  if (auth.user?.publicId) showWelcome.value = true
}

function dismissWelcome() {
  window.localStorage.setItem(welcomeKey.value, 'dismissed')
  showWelcome.value = false
}

onMounted(() => {
  showWelcome.value = shouldShowWelcome()
  window.addEventListener('better-self:show-welcome', openWelcome)
})

onBeforeUnmount(() => {
  window.removeEventListener('better-self:show-welcome', openWelcome)
})
</script>
<template>
  <div class="shell">
    <aside class="sidebar">
      <RouterLink class="brand" to="/today"><span class="brand-mark" :aria-label="`${auth.user?.displayName ?? '用户'}的标识`">{{ brandInitial }}</span><strong>更好的自己</strong></RouterLink>
      <nav aria-label="主导航"><RouterLink v-for="item in nav" :key="item.to" :to="item.to"><component :is="item.icon" :size="19"/><span>{{ item.label }}</span></RouterLink></nav>
      <p class="sidebar-note"><Heart :size="16" fill="currentColor" /><span>今天完成一点，也很好。</span></p>
    </aside>
    <main class="workspace"><RouterView /></main>
    <nav class="mobile-nav" aria-label="主导航"><RouterLink v-for="item in nav" :key="item.to" :to="item.to"><component :is="item.icon" :size="20"/><span>{{ item.label }}</span></RouterLink></nav>
    <DesktopPet v-if="!isDesktopCompanion" />
    <WelcomeGuide v-if="showWelcome" @dismiss="dismissWelcome" />
  </div>
</template>
<style scoped>
.shell { min-height: 100vh; padding-left: var(--sidebar); }
.sidebar { position: fixed; inset: 0 auto 0 0; width: var(--sidebar); border-right: 1px solid var(--border); background: color-mix(in srgb, var(--surface) 96%, var(--canvas)); padding: 18px 14px; display: flex; flex-direction: column; z-index: 10; box-shadow: 7px 0 24px color-mix(in srgb, var(--ink) 5%, transparent); }
.sidebar::before { content: ""; position: absolute; inset: 0 0 auto; height: 3px; background: var(--topline); }
.brand { display: flex; align-items: center; gap: 10px; color: var(--ink); text-decoration: none; padding: 7px 8px 22px; }
.brand-mark { position: relative; width: 36px; height: 36px; display: grid; place-items: center; border-radius: 8px; background: var(--primary); color: white; font-weight: 900; box-shadow: 0 10px 20px color-mix(in srgb, var(--primary) 22%, transparent); }
.brand-mark::after { content: ""; position: absolute; right: -3px; bottom: -3px; width: 10px; height: 10px; border: 2px solid var(--surface); border-radius: 50%; background: var(--accent); }
nav { display: grid; gap: 5px; }
nav a { position: relative; min-height: 44px; display: flex; align-items: center; gap: 12px; padding: 0 12px; border-radius: var(--radius); color: var(--muted); text-decoration: none; font-size: 14px; }
nav a:hover { background: color-mix(in srgb, var(--surface-muted) 72%, var(--surface)); color: var(--ink); }
nav a.router-link-active { background: color-mix(in srgb, var(--primary-soft) 76%, var(--surface)); color: var(--primary-strong); font-weight: 800; box-shadow: inset 4px 0 0 var(--primary); }
.sidebar-note { margin-top: auto; display: grid; grid-template-columns: 18px minmax(0, 1fr); gap: 8px; align-items: start; padding: 13px 12px; border: 1px solid color-mix(in srgb, var(--amber) 30%, var(--border)); border-radius: var(--radius); background: color-mix(in srgb, var(--amber) 8%, var(--surface)); color: var(--muted); font-size: 12px; line-height: 1.65; }
.sidebar-note svg { margin-top: 2px; color: var(--primary); }
.workspace { min-width: 0; }
.mobile-nav { display: none; }
@media (max-width:760px) { .shell { padding-left: 0; } .sidebar { display:none; } .mobile-nav { position: fixed; display:grid; grid-template-columns:repeat(6,1fr); inset:auto 0 0; z-index:20; background: color-mix(in srgb, var(--surface) 96%, transparent); backdrop-filter: blur(16px); border-top:1px solid var(--border); box-shadow:0 -10px 26px color-mix(in srgb, var(--ink) 8%, transparent); padding:6px 4px calc(6px + env(safe-area-inset-bottom)); } .mobile-nav a { min-width:0; min-height:54px; justify-content:center; flex-direction:column; gap:3px; padding:2px; font-size:10px; } }
@media (prefers-reduced-motion: no-preference) { .brand-mark { transition: transform var(--motion-medium) ease; } .brand:hover .brand-mark { transform: rotate(-8deg) scale(1.04); } nav a.router-link-active svg { animation: nav-pop var(--motion-medium) ease-out; } }
@keyframes nav-pop { 0% { transform: scale(.88); } 70% { transform: scale(1.08); } 100% { transform: scale(1); } }
</style>
