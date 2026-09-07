<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { Activity, Building2, Target, CalendarCheck2, ChartNoAxesColumnIncreasing, ArrowUpRight, Sparkles, Settings, PawPrint, UserRound, Users, X, Bell, PanelLeftClose } from 'lucide-vue-next'
import { useAuthStore } from '../modules/auth/auth.store'
import DesktopPet from '../modules/partners/DesktopPet.vue'
import WelcomeGuide from '../shared/ui/WelcomeGuide.vue'
import GlobalUnreadBar from '../shared/ui/GlobalUnreadBar.vue'
import OperationGuideBar from '../shared/ui/OperationGuideBar.vue'
import { useDialogFocus } from '../shared/ui/use-dialog-focus'

import { useWorkspaceModeStore } from '../shared/ui/workspace-mode.store'

const mode = useWorkspaceModeStore()
const router = useRouter()
function changeMode(minimal: boolean) {
  mode.setMinimal(minimal)
  showWelcome.value = false
  showMobileMore.value = false
  void router.push('/today')
}

const nav = [
  { to: '/today', label: '今日', icon: CalendarCheck2, mobile: 'primary', group: '行动' },
  { to: '/goals', label: '目标', icon: Target, mobile: 'primary', group: '行动' },
  { to: '/ai', label: 'AI 助手', icon: Sparkles, mobile: 'more', group: '行动' },
  { to: '/attributes', label: '属性', icon: Activity, mobile: 'more', group: '成长' },
  { to: '/insights', label: '洞察', icon: ChartNoAxesColumnIncreasing, mobile: 'more', group: '成长' },
  { to: '/town', label: '小镇', icon: Building2, mobile: 'primary', group: '陪伴' },
  { to: '/partners', label: '伙伴', icon: PawPrint, mobile: 'more', group: '陪伴' },
  { to: '/friends', label: '好友', icon: Users, mobile: 'more', group: '陪伴' },
  { to: '/profile', label: '个人', icon: UserRound, mobile: 'more', group: '账户' },
  { to: '/settings', label: '设置', icon: Settings, mobile: 'more', group: '账户' },
]
const groups = ['行动', '成长', '陪伴']
const mobileNav = nav.filter(item => item.mobile === 'primary')
const mobileMoreNav = nav.filter(item => item.mobile === 'more')

const auth = useAuthStore()
const route = useRoute()
const currentNav = computed(() => nav.find(item => route.path === item.to || route.path.startsWith(`${item.to}/`)))
const isDesktopCompanion = Boolean(window.betterSelfDesktop?.isDesktopApp)
const showWelcome = ref(false)
const showMobileMore = ref(false)
useDialogFocus(() => showMobileMore.value, '.mobile-more-sheet', () => { showMobileMore.value = false })
useDialogFocus(() => showWelcome.value && !mode.minimal, '.welcome-dialog', () => dismissWelcome())
const quietWorkspace = computed(() => route.path === '/ai' || route.path.includes('/chat') || route.path.includes('/groups/') || route.path === '/town')
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
  return !mode.minimal && Boolean(auth.user?.publicId) && window.localStorage.getItem(welcomeKey.value) !== 'dismissed'
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
  <div class="shell" :class="{ 'shell--minimal': mode.minimal }">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <aside v-if="!mode.minimal" class="sidebar">
      <RouterLink class="brand" to="/today"><span class="brand-mark" aria-hidden="true"><Building2 :size="23" /></span><span><strong>更好的自己</strong><small>一步一步，自成风景</small></span></RouterLink>
      <nav aria-label="主导航"><section v-for="group in groups" :key="group" class="nav-group"><p>{{ group }}</p><RouterLink v-for="item in nav.filter(item => item.group === group)" :key="item.to" :to="item.to"><component :is="item.icon" :size="19"/><span>{{ item.label }}</span><span v-if="route.path === item.to" class="nav-dot" /></RouterLink></section></nav>
      <RouterLink class="sidebar-note" to="/town"><span class="note-orbit" aria-hidden="true">✦</span><strong>让每一步，<br>长成看得见的生活。</strong><span>去小镇走走 <ArrowUpRight :size="15" /></span></RouterLink>
      <button class="secondary mode-switch" @click="changeMode(true)">切换极简清单</button>
      <div class="sidebar-account"><RouterLink to="/profile"><span class="account-avatar">{{ brandInitial }}</span><span><strong>{{ auth.user?.displayName || '我的成长档案' }}</strong><small>今天完成一点，也很好</small></span></RouterLink><RouterLink class="account-settings" to="/settings" aria-label="设置"><Settings :size="18" /></RouterLink></div>
    </aside>
    <main id="main-content" class="workspace" tabindex="-1">
      <header v-if="mode.minimal" class="minimal-topbar"><RouterLink to="/today" class="minimal-brand">我的清单</RouterLink><div><RouterLink to="/settings">设置</RouterLink><button type="button" @click="changeMode(false)">切换成长模式</button></div></header>
      <header v-else class="workspace-topbar"><span class="workspace-context"><PanelLeftClose :size="17" /><span>{{ currentNav?.group || '成长' }}</span><span class="context-slash">/</span><strong>{{ currentNav?.label || '更好的自己' }}</strong></span><div><RouterLink class="topbar-ai" to="/ai"><Sparkles :size="15" />和 AI 理一理</RouterLink><RouterLink class="icon-button" to="/friends/chat" aria-label="消息中心"><Bell :size="18" /></RouterLink></div></header>
      <OperationGuideBar v-if="!mode.minimal" />
      <RouterView v-slot="{ Component, route }">
        <Transition name="route-view" mode="out-in">
          <component :is="Component" :key="route.path" />
        </Transition>
      </RouterView>
    </main>
    <Transition name="mobile-more">
      <div v-if="showMobileMore" class="mobile-more-layer">
        <button class="mobile-more-backdrop" type="button" aria-label="关闭更多功能" @click="closeMobileMore" />
        <section id="mobile-more-menu" class="mobile-more-sheet" role="dialog" aria-modal="true" tabindex="-1" aria-labelledby="mobile-more-title">
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
    <nav v-if="!mode.minimal" class="mobile-nav" aria-label="主导航">
      <RouterLink v-for="item in mobileNav" :key="item.to" :to="item.to"><component :is="item.icon" :size="20"/><span>{{ item.label }}</span></RouterLink>
      <button type="button" :class="{ 'is-active': mobileMoreActive }" :aria-expanded="showMobileMore" aria-controls="mobile-more-menu" @click="showMobileMore = !showMobileMore"><UserRound :size="20"/><span>我的</span></button>
    </nav>
    <DesktopPet v-if="!mode.minimal && !isDesktopCompanion && !quietWorkspace" />
    <GlobalUnreadBar v-if="!mode.minimal && !quietWorkspace" />
    <WelcomeGuide v-if="showWelcome && !mode.minimal" @dismiss="dismissWelcome" />
  </div>
</template>
<style scoped>
.shell.shell--minimal { padding-left: 0; }
.shell--minimal .workspace { padding-bottom: 24px; }
.minimal-topbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; max-width: 840px; margin: auto; padding: 24px; border-bottom: 1px solid var(--border); }
.minimal-topbar a { text-decoration: none; color: var(--muted); }
.minimal-topbar .minimal-brand { color: var(--ink); font-weight: 700; }
.minimal-topbar > div { display: flex; align-items: center; gap: 16px; font-size: 13px; }
.minimal-topbar button { border: 0; background: transparent; color: var(--muted); padding: 10px 0; cursor: pointer; font: inherit; }
.mode-switch { margin-bottom: 16px; }

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

.sidebar { padding: 28px 16px 16px; background: var(--nav-bg); color: var(--nav-ink-strong); border: 0; box-shadow: none; overflow-y: auto; }
.sidebar::before, .brand-mark::after { display: none; }
.brand { color: var(--nav-ink-strong); padding: 0 6px 30px; gap: 11px; }
.brand-mark { background: var(--nav-active-bg); color: var(--nav-active-ink); width: 36px; height: 40px; border-radius: 16px 16px 6px 6px; box-shadow: none; }
.brand strong { font-size: 15px; letter-spacing: .04em; }
.brand small { display: block; font-size: 10px; color: var(--nav-faint); margin-top: 2px; letter-spacing: .08em; }
.nav-group { margin-bottom: 16px; }
.nav-group > p { padding-left: 14px; margin: 0 0 7px; color: var(--nav-muted); font-size: 10px; letter-spacing: .16em; }
.sidebar nav a { color: var(--nav-ink); min-height: 44px; margin-bottom: 4px; font-size: 13px; }
.sidebar nav a:hover { color: var(--nav-ink-strong); background: var(--nav-hover); }
.sidebar nav a.router-link-active { background: var(--nav-active-bg); color: var(--nav-active-ink); box-shadow: none; font-weight: 750; }
.nav-dot { width: 5px; height: 5px; margin-left: auto; border-radius: 50%; background: currentColor; }
.sidebar-note { display: block; margin: auto 0 20px; padding: 20px 12px; background: transparent; border: 0; border-top: 1px solid var(--nav-line); border-radius: 0; text-decoration: none; color: var(--nav-card-ink); }
.sidebar-note strong { display: block; font-size: 14px; font-weight: 500; line-height: 1.9; }
.sidebar-note > span:last-child { margin-top: 12px; display: flex; justify-content: space-between; font-size: 11px; color: var(--nav-ink); }
.note-orbit { display: block; font-size: 22px; color: var(--amber); margin-bottom: 10px; }
.sidebar-account { display: flex; gap: 6px; align-items: center; border-top: 1px solid var(--nav-line); padding-top: 16px; }
.sidebar-account > a:first-child { display: flex; align-items: center; gap: 9px; min-width: 0; color: var(--nav-card-ink); text-decoration: none; flex: 1; }
.sidebar-account strong { display: block; font-size: 12px; }
.sidebar-account small { display: block; font-size: 9px; color: var(--nav-muted); }
.account-avatar { display: grid; place-items: center; width: 32px; height: 32px; flex: none; border-radius: 50%; background: var(--nav-avatar); color: var(--nav-avatar-ink); font-size: 12px; }
.account-settings { color: var(--nav-faint); padding: 8px; }
.workspace-topbar { min-height: 64px; display: flex; justify-content: space-between; align-items: center; padding: 0 32px; border-bottom: 0; background: var(--canvas); }
.workspace-context { display: flex; gap: 12px; align-items: center; color: var(--muted); font-size: 12px; }
.workspace-context strong { color: var(--ink); font-weight: 500; }
.context-slash { opacity: .4; }
.workspace-topbar > div { display: flex; align-items: center; gap: 18px; }
.topbar-ai { display: flex; align-items: center; gap: 7px; color: var(--primary-strong); text-decoration: none; font-size: 12px; }
.mobile-more-sheet { max-height: 75dvh; overflow-y: auto; }
@media (max-width:760px) {
  .workspace-topbar { min-height: 54px; padding: 0 18px; }
  .workspace-context > svg, .workspace-context > span:not(.context-slash) { display: none; }
  .context-slash { display: none; }
  .mobile-nav { grid-template-columns: repeat(4, minmax(0, 1fr)); }
  .mobile-nav a, .mobile-nav button { font-size: 11px; }
  .mobile-more-links { grid-template-columns: repeat(3, minmax(0,1fr)); }
  .mobile-nav a.router-link-active, .mobile-nav button.is-active, .mobile-nav button[aria-expanded='true'] { box-shadow: none; }
}
</style>
