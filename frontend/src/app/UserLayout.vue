<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'
import { Activity, Target, CalendarCheck2, ChartNoAxesColumnIncreasing, Heart, Menu, Sparkles, Settings, PawPrint, UserRound, Users, X } from 'lucide-vue-next'
import { useAuthStore } from '../modules/auth/auth.store'
import DesktopPet from '../modules/partners/DesktopPet.vue'
import WelcomeGuide from '../shared/ui/WelcomeGuide.vue'
import GlobalUnreadBar from '../shared/ui/GlobalUnreadBar.vue'
import OperationGuideBar from '../shared/ui/OperationGuideBar.vue'

const nav = [
  { to: '/today', label: '今日', icon: CalendarCheck2, mobile: 'primary' },
  { to: '/goals', label: '目标', icon: Target, mobile: 'primary' },
  { to: '/partners', label: '伙伴', icon: PawPrint, mobile: 'more' },
  { to: '/friends', label: '好友', icon: Users, mobile: 'more' },
  { to: '/attributes', label: '属性', icon: Activity, mobile: 'primary' },
  { to: '/insights', label: '洞察', icon: ChartNoAxesColumnIncreasing, mobile: 'more' },
  { to: '/ai', label: 'AI 助手', icon: Sparkles, mobile: 'primary' },
  { to: '/profile', label: '个人', icon: UserRound, mobile: 'primary' },
  { to: '/settings', label: '设置', icon: Settings, mobile: 'more' },
]
const mobileNav = nav.filter(item => item.mobile === 'primary')
const mobileMoreNav = nav.filter(item => item.mobile === 'more')

const auth = useAuthStore()
const route = useRoute()
const isDesktopCompanion = Boolean(window.betterSelfDesktop?.isDesktopApp)
const showWelcome = ref(false)
const showMobileMore = ref(false)
const welcomeKey = computed(() => `better-self:welcome:${auth.user?.publicId ?? 'guest'}`)
const mobileMoreActive = computed(() => mobileMoreNav.some(item => route.path === item.to || route.path.startsWith(`${item.to}/`)))
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

function closeMobileMore() {
  showMobileMore.value = false
}

watch(() => route.fullPath, closeMobileMore)

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
    <main class="workspace">
      <OperationGuideBar />
      <RouterView v-slot="{ Component, route }">
        <Transition name="route-view" mode="out-in">
          <component :is="Component" :key="route.path" />
        </Transition>
      </RouterView>
    </main>
    <Transition name="mobile-more">
      <div v-if="showMobileMore" class="mobile-more-layer">
        <button class="mobile-more-backdrop" type="button" aria-label="关闭更多功能" @click="closeMobileMore" />
        <section id="mobile-more-menu" class="mobile-more-sheet" aria-labelledby="mobile-more-title">
          <header>
            <div><p class="eyebrow">完整导航</p><h2 id="mobile-more-title">更多功能</h2></div>
            <button class="icon-button" type="button" aria-label="关闭更多功能" @click="closeMobileMore"><X :size="20" /></button>
          </header>
          <nav class="mobile-more-links" aria-label="更多功能">
            <RouterLink v-for="item in mobileMoreNav" :key="item.to" :to="item.to" @click="closeMobileMore">
              <component :is="item.icon" :size="21" />
              <span>{{ item.label }}</span>
            </RouterLink>
          </nav>
        </section>
      </div>
    </Transition>
    <nav class="mobile-nav" aria-label="主导航">
      <RouterLink v-for="item in mobileNav" :key="item.to" :to="item.to"><component :is="item.icon" :size="20"/><span>{{ item.label }}</span></RouterLink>
      <button type="button" :class="{ 'is-active': mobileMoreActive }" :aria-expanded="showMobileMore" aria-controls="mobile-more-menu" @click="showMobileMore = !showMobileMore"><Menu :size="20"/><span>更多</span></button>
    </nav>
    <DesktopPet v-if="!isDesktopCompanion" />
    <GlobalUnreadBar />
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
.mobile-more-layer { display: none; }
@media (max-width:760px) {
  .shell { padding-left: 0; }
  .sidebar { display:none; }
  .workspace { padding-bottom: calc(70px + env(safe-area-inset-bottom)); }
  .mobile-nav { position: fixed; display:grid; grid-template-columns:repeat(6,minmax(0,1fr)); inset:auto 0 0; z-index:20; background: color-mix(in srgb, var(--surface) 96%, transparent); backdrop-filter: blur(16px); border-top:1px solid var(--border); box-shadow:0 -10px 26px color-mix(in srgb, var(--ink) 8%, transparent); padding:6px 4px calc(6px + env(safe-area-inset-bottom)); }
  .mobile-nav a,
  .mobile-nav button { min-width:0; min-height:54px; display:flex; align-items:center; justify-content:center; flex-direction:column; gap:3px; padding:2px; border:0; border-radius:var(--radius); background:transparent; color:var(--muted); font:inherit; font-size:10px; cursor:pointer; }
  .mobile-nav button[aria-expanded='true'],
  .mobile-nav button.is-active { background:color-mix(in srgb, var(--primary-soft) 76%, var(--surface)); color:var(--primary-strong); font-weight:800; box-shadow:inset 4px 0 0 var(--primary); }
  .mobile-more-layer { display:block; }
  .mobile-more-backdrop { position:fixed; inset:0 0 calc(66px + env(safe-area-inset-bottom)); z-index:18; width:100%; border:0; background:color-mix(in srgb, var(--ink) 24%, transparent); }
  .mobile-more-sheet { position:fixed; inset:auto 10px calc(76px + env(safe-area-inset-bottom)); z-index:19; padding:18px; border:1px solid var(--border); border-radius:var(--radius); background:var(--surface); box-shadow:0 18px 48px color-mix(in srgb, var(--ink) 24%, transparent); }
  .mobile-more-sheet header { display:flex; align-items:center; justify-content:space-between; gap:16px; margin-bottom:14px; }
  .mobile-more-sheet h2 { margin:2px 0 0; font-size:20px; }
  .mobile-more-sheet .icon-button { min-width:42px; min-height:42px; }
  .mobile-more-links { grid-template-columns:repeat(4,minmax(0,1fr)); gap:8px; }
  .mobile-more-links a { min-width:0; min-height:76px; justify-content:center; flex-direction:column; gap:7px; padding:8px 4px; border:1px solid var(--border); background:var(--surface-muted); font-size:12px; }
  .mobile-more-links a.router-link-active { box-shadow:inset 0 3px 0 var(--primary); }
  .mobile-more-enter-active,
  .mobile-more-leave-active { transition:opacity var(--motion-fast) ease; }
  .mobile-more-enter-active .mobile-more-sheet,
  .mobile-more-leave-active .mobile-more-sheet { transition:transform var(--motion-medium) ease, opacity var(--motion-fast) ease; }
  .mobile-more-enter-from,
  .mobile-more-leave-to { opacity:0; }
  .mobile-more-enter-from .mobile-more-sheet,
  .mobile-more-leave-to .mobile-more-sheet { transform:translateY(12px); opacity:0; }
}
@media (prefers-reduced-motion: no-preference) { .brand-mark { transition: transform var(--motion-medium) ease; } .brand:hover .brand-mark { transform: rotate(-8deg) scale(1.04); } nav a.router-link-active svg { animation: nav-pop var(--motion-medium) ease-out; } .route-view-enter-active, .route-view-leave-active { transition: opacity var(--motion-medium) ease, transform var(--motion-medium) ease; } .route-view-enter-from { opacity: 0; transform: translateY(8px); } .route-view-leave-to { opacity: 0; transform: translateY(-4px); } }
@keyframes nav-pop { 0% { transform: scale(.88); } 70% { transform: scale(1.08); } 100% { transform: scale(1); } }
</style>
