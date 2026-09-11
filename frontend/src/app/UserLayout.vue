<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { ArrowUpRight, Building2, Settings, UserRound, X, Bell, CircleHelp, PanelLeftClose } from 'lucide-vue-next'
import { useAuthStore } from '../modules/auth/auth.store'
import DesktopPet from '../modules/partners/DesktopPet.vue'
import WelcomeGuide from '../shared/ui/WelcomeGuide.vue'
import GlobalUnreadBar from '../shared/ui/GlobalUnreadBar.vue'
import OperationGuideBar, { resolveGuide } from '../shared/ui/OperationGuideBar.vue'
import { useDialogFocus } from '../shared/ui/use-dialog-focus'
import { usePeriod } from '../shared/ui/period'
import { radialReveal } from '../shared/ui/interaction/radial-reveal'
import { prefetchRoute } from './router'
import { NAV_ITEMS } from './nav'
import TownStage from '../modules/companion/TownStage.vue'
import { STAGE_PLACES } from '../modules/companion/companion-art'

import { useWorkspaceModeStore } from '../shared/ui/workspace-mode.store'

usePeriod()

const mode = useWorkspaceModeStore()
const router = useRouter()
// Switching between 极简清单 and 完整模式 replaces the whole shell (sidebar,
// topbar, nav); grow the new one from the button pressed, same as an
// appearance change, instead of snapping straight to it.
function changeMode(event: Event, minimal: boolean) {
  void radialReveal(event, () => {
    mode.setMinimal(minimal)
    showWelcome.value = false
    showMobileMore.value = false
    void router.push('/today')
  })
}

const nav = NAV_ITEMS
const groups = ['行动', '成长', '陪伴']
/** The quiet second line under each sidebar label - the docked strip's own place label registry,
 * so both read the exact same name (and the same "筹备中" suffix once a place is a placeholder). */
function navPlaceLabel(item: (typeof nav)[number]) {
  if (!item.place) return '走进去'
  // The one virtual place: the sidebar shows a fixed "跟着你" hint rather than today's actual
  // location (that changes all day and lives in the docked strip's own label instead).
  if (item.place === 'avatar') return '小人身边'
  const place = STAGE_PLACES[item.place]
  if (!place) return ''
  return place.status === 'placeholder' ? `${place.label} · 筹备中` : place.label
}
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

// The "使用提示" popover is a quiet disclosure, not a dialog: it holds no
// focusable content (StepperProgress is read-only here), so it never steals
// focus from the trigger button. Esc and an outside click both close it.
const showGuide = ref(false)
const guideAnchor = ref<HTMLElement | null>(null)
const currentGuide = computed(() => resolveGuide(route.path))

function toggleGuide() {
  showGuide.value = !showGuide.value
}

function closeGuide() {
  showGuide.value = false
}

function onGuideDocumentClick(event: MouseEvent) {
  if (!showGuide.value) return
  if (guideAnchor.value?.contains(event.target as Node)) return
  closeGuide()
}

function onGuideKeydown(event: KeyboardEvent) {
  if (!showGuide.value) return
  if (event.key === 'Escape') { event.preventDefault(); closeGuide() }
}

watch(() => route.fullPath, closeGuide)
const quietWorkspace = computed(() => route.path === '/ai' || route.path.includes('/chat') || route.path.includes('/groups/') || route.path === '/town')
// /auth, /onboarding and /desktop-pet are already separate top-level routes that never mount
// UserLayout at all - this guard is defensive, in case that routing structure ever changes.
const excludedStagePaths = ['/auth', '/onboarding', '/desktop-pet']
// Both modes get a companion layer now - growth mode docks it in the street strip above the page,
// minimal mode docks the same shared instance into the right-hand rail (see TownStage.vue's
// computeTarget()); only the *slot* it lands in differs, never whether it exists.
const showTownStage = computed(() => auth.signedIn && !excludedStagePaths.some(path => route.path === path || route.path.startsWith(`${path}/`)))
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
  return route.path !== '/town' && !mode.minimal && Boolean(auth.user?.publicId) && window.localStorage.getItem(welcomeKey.value) !== 'dismissed'
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
  document.addEventListener('click', onGuideDocumentClick)
  document.addEventListener('keydown', onGuideKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('better-self:show-welcome', openWelcome)
  document.removeEventListener('click', onGuideDocumentClick)
  document.removeEventListener('keydown', onGuideKeydown)
})
</script>
<template>
  <div class="shell" :class="{ 'shell--minimal': mode.minimal }">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <aside v-if="!mode.minimal" class="sidebar">
      <RouterLink class="brand" to="/today"><span class="brand-mark" aria-hidden="true"><Building2 :size="23" /></span><strong>更好的自己</strong></RouterLink>
      <nav aria-label="主导航"><section v-for="group in groups" :key="group" class="nav-group"><p>{{ group }}</p><RouterLink v-for="item in nav.filter(item => item.group === group)" :key="item.to" :to="item.to" @mouseenter="prefetchRoute(item.to)" @focus="prefetchRoute(item.to)"><component :is="item.icon" :size="19"/><span class="nav-text"><strong>{{ item.label }}</strong><small>{{ navPlaceLabel(item) }}</small></span><span v-if="route.path === item.to" class="nav-dot" /></RouterLink></section></nav>
      <RouterLink class="sidebar-note" to="/town"><span class="note-orbit" aria-hidden="true">✦</span><strong>让每一步，<br>长成看得见的生活。</strong><span>去小镇走走 <ArrowUpRight :size="15" /></span></RouterLink>
      <button class="secondary mode-switch" @click="changeMode($event, true)">切换极简清单</button>
      <div class="sidebar-account"><RouterLink to="/profile"><span class="account-avatar">{{ brandInitial }}</span><strong>{{ auth.user?.displayName || '我的成长档案' }}</strong></RouterLink><RouterLink class="account-settings" to="/settings" aria-label="设置"><Settings :size="18" /></RouterLink></div>
    </aside>
    <main id="main-content" class="workspace" tabindex="-1">
      <header v-if="mode.minimal" class="minimal-topbar workspace-topbar"><RouterLink to="/today" class="minimal-brand"><span class="brand-mark" aria-hidden="true"><Building2 :size="18" /></span><strong>我的清单</strong></RouterLink><div><RouterLink class="icon-button" to="/settings" aria-label="设置"><Settings :size="18" /></RouterLink><button type="button" class="secondary mode-switch" @click="changeMode($event, false)">切换成长模式</button></div></header>
      <header v-else class="workspace-topbar">
        <span class="workspace-context"><PanelLeftClose :size="17" /><span>{{ currentNav?.group || '成长' }}</span><span class="context-slash">/</span><strong>{{ currentNav?.label || '更好的自己' }}</strong></span>
        <div>
          <div ref="guideAnchor" class="guide-anchor">
            <button v-if="currentGuide" type="button" class="icon-button guide-trigger" aria-label="使用提示" :aria-expanded="showGuide" @click="toggleGuide"><CircleHelp :size="18" /></button>
            <Transition name="guide-pop">
              <div v-if="showGuide && currentGuide" class="guide-popover" role="region" aria-label="使用提示">
                <OperationGuideBar />
              </div>
            </Transition>
          </div>
          <RouterLink class="icon-button" to="/friends/chat" aria-label="消息中心"><Bell :size="18" /></RouterLink>
        </div>
      </header>
      <div v-if="showTownStage && !mode.minimal" id="town-strip-slot" class="town-strip" :hidden="route.path === '/town'" aria-label="门前小街" />
      <div class="workspace-body" :class="{ 'workspace-body--minimal': mode.minimal }">
        <RouterView v-slot="{ Component, route }">
          <component :is="Component" :key="route.path" />
        </RouterView>
        <!-- 左清单右小镇 (docs/04): minimal mode's own slot for the same shared Teleport target -
             a quiet companion rail beside the list, never a second dashboard competing with it. -->
        <div v-if="showTownStage && mode.minimal" id="town-minimal-slot" class="town-minimal" :hidden="route.path === '/town'" aria-label="小镇陪伴" />
      </div>
      <TownStage v-if="showTownStage" />
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
            <RouterLink v-for="item in mobileMoreNav" :key="item.to" :to="item.to" @click="closeMobileMore" @pointerdown="prefetchRoute(item.to)" @focus="prefetchRoute(item.to)">
              <component :is="item.icon" :size="21" />
              <span>{{ item.label }}</span>
            </RouterLink>
          </nav>
        </section>
      </div>
    </Transition>
    <nav v-if="!mode.minimal" class="mobile-nav" aria-label="主导航">
      <RouterLink v-for="item in mobileNav" :key="item.to" :to="item.to" @pointerdown="prefetchRoute(item.to)" @focus="prefetchRoute(item.to)"><component :is="item.icon" :size="20"/><span>{{ item.label }}</span></RouterLink>
      <button type="button" :class="{ 'is-active': mobileMoreActive }" :aria-expanded="showMobileMore" aria-controls="mobile-more-menu" @click="showMobileMore = !showMobileMore"><UserRound :size="20"/><span>我的</span></button>
    </nav>
    <DesktopPet v-if="!mode.minimal && !isDesktopCompanion && !quietWorkspace" />
    <GlobalUnreadBar v-if="!mode.minimal && !quietWorkspace" />
    <WelcomeGuide v-if="showWelcome && !mode.minimal && route.path !== '/town'" @dismiss="dismissWelcome" />
  </div>
</template>
<style scoped>
.shell.shell--minimal { padding-left: 0; }
.shell--minimal .workspace { padding-bottom: 24px; }
/* The minimal topbar is a narrowed *instance* of .workspace-topbar (same class, applied directly
   in the template), not a parallel bar with its own height/padding/border numbers - it only adds
   the width-centering that growth mode's topbar gets for free from the fixed sidebar narrowing
   its available width. Its brand mark, icon-button and secondary-button children all reuse the
   exact same shared classes as growth mode's sidebar/topbar (see .brand-mark, .icon-button,
   .secondary below) instead of a second set of link/button rules. */
.minimal-topbar { width: min(100%, 1144px); margin: 0 auto; }
.minimal-brand { display: flex; align-items: center; gap: 10px; color: var(--ink); font-weight: 700; text-decoration: none; }
.minimal-brand strong { font-size: 15px; letter-spacing: .02em; }
.minimal-topbar .mode-switch { margin-bottom: 0; }
.mode-switch { margin-bottom: 16px; }

.shell { min-height: 100vh; padding-left: var(--sidebar); }
.sidebar { position: fixed; inset: 0 auto 0 0; width: var(--sidebar); border-right: 1px solid var(--border); background: color-mix(in srgb, var(--surface) 96%, var(--canvas)); padding: 18px 14px; display: flex; flex-direction: column; z-index: 10; }
.sidebar::before { content: ""; position: absolute; inset: 0 0 auto; height: 3px; background: var(--topline); }
.brand { display: flex; align-items: center; gap: 10px; color: var(--ink); text-decoration: none; padding: 7px 8px 22px; }
.brand-mark { position: relative; width: 36px; height: 36px; display: grid; place-items: center; border-radius: var(--radius-card); background: var(--primary); color: white; font-weight: 900; }
.brand-mark::after { content: ""; position: absolute; right: -3px; bottom: -3px; width: 10px; height: 10px; border: 2px solid var(--surface); border-radius: 50%; background: var(--accent); }
nav { display: grid; gap: 5px; }
nav a { position: relative; min-height: 44px; display: flex; align-items: center; gap: 12px; padding: 0 12px; border-radius: var(--radius); color: var(--muted); text-decoration: none; font-size: 14px; }
nav a:hover { background: color-mix(in srgb, var(--surface-muted) 72%, var(--surface)); color: var(--ink); }
nav a.router-link-active { background: color-mix(in srgb, var(--primary-soft) 76%, var(--surface)); color: var(--primary-strong); font-weight: 800; box-shadow: inset 4px 0 0 var(--primary); }
.sidebar-note { margin-top: auto; display: grid; grid-template-columns: 18px minmax(0, 1fr); gap: 8px; align-items: start; padding: 13px 12px; border: 1px solid color-mix(in srgb, var(--amber) 30%, var(--border)); border-radius: var(--radius); background: color-mix(in srgb, var(--amber) 8%, var(--surface)); color: var(--muted); font-size: 12px; line-height: 1.65; }
.sidebar-note svg { margin-top: 2px; color: var(--primary); }
.workspace { min-width: 0; }
.workspace-body { min-width: 0; }
/* 左清单右小镇 (docs/04): minimal mode's one shape - whatever the current route renders (still in
   its own .page wrapper) beside a persistent, quieter companion rail. Both this grid and
   .minimal-topbar share the exact same width/gutter rhythm as growth mode's .page (min(100%,
   1144px), 32px sides) so the rail's edges land where .page's own padding would have put them -
   the list column then turns off .page's own horizontal centering/padding so the two rhythms
   don't stack (see the ".page" override below). */
/* The list column (minmax(0,1fr)) is flexible, not fixed - but it can never actually render wider
   than ~736px, because the whole row is capped at 1144px total: 1144 - 64 (this row's own 32px
   sides) - 24 (gap) - 320 (the rail's own max) = 736. Every page in this app (growth mode's .page,
   minimal mode's own .checklist) already self-manages its width the same way - width:min(100%,
   <its own cap>) plus margin:0 auto - so handing it a column that never exceeds ~736px (under
   .checklist's own 744px cap) means that min() always resolves to 100% of the column: the page
   fills it edge-to-edge and only shrinks further on its own from there, instead of ever having so
   much slack that its self-centering would read as an island adrift in extra space. */
.workspace-body--minimal { display: grid; grid-template-columns: minmax(0, 1fr) minmax(240px, 320px); align-items: start; gap: 24px; width: min(100%, 1144px); margin: 0 auto; padding: 0 32px 32px; }
/* A quiet companion window, not a second dashboard: same box language as .town-strip below (same
   border/radius/surface tone, no shadow - shadows are reserved for floating layers in this app).
   Sticky so it keeps you company while a long list scrolls, same idea as a desk pet staying in
   view - never grabs focus, never scrolls out of reach either. */
.town-minimal { position: sticky; top: 20px; width: 100%; height: 360px; border: 1px solid var(--border); border-radius: var(--radius-panel); background: var(--surface-muted); box-shadow: none; overflow: hidden; }
.town-minimal[hidden] { display: none; }
@media (max-width: 1100px) {
  .workspace-body--minimal { grid-template-columns: minmax(0, 1fr) 280px; gap: 18px; }
  .town-minimal { height: 300px; }
}
@media (max-width: 760px) {
  /* Narrower than this, the two columns cannot both stay legible - the rail collapses back into a
     short, wide band (same shape as the docked strip at this width) that reads below the list
     instead of beside it: 清单优先, the companion follows after it in both DOM and reading order. */
  .workspace-body--minimal { grid-template-columns: 1fr; gap: 14px; padding: 0 18px 24px; }
  .town-minimal { position: static; height: 112px; }
}
.mobile-nav { display: none; }
.mobile-more-layer { display: none; }
@media (max-width:760px) {
  .shell { padding-left: 0; }
  .sidebar { display:none; }
  .workspace { padding-bottom: calc(70px + env(safe-area-inset-bottom)); }
  .mobile-nav { position: fixed; display:grid; grid-template-columns:repeat(6,minmax(0,1fr)); inset:auto 0 0; z-index:20; background: color-mix(in srgb, var(--surface) 96%, transparent); backdrop-filter: blur(16px); border-top:1px solid var(--border); box-shadow: var(--shadow); padding:6px 4px calc(6px + env(safe-area-inset-bottom)); }
  .mobile-nav a,
  .mobile-nav button { min-width:0; min-height:54px; display:flex; align-items:center; justify-content:center; flex-direction:column; gap:3px; padding:2px; border:0; border-radius:var(--radius); background:transparent; color:var(--muted); font:inherit; font-size:10px; cursor:pointer; }
  .mobile-nav button[aria-expanded='true'],
  .mobile-nav button.is-active { background:color-mix(in srgb, var(--primary-soft) 76%, var(--surface)); color:var(--primary-strong); font-weight:800; box-shadow:inset 4px 0 0 var(--primary); }
  .mobile-more-layer { display:block; }
  .mobile-more-backdrop { position:fixed; inset:0 0 calc(66px + env(safe-area-inset-bottom)); z-index:18; width:100%; border:0; background:color-mix(in srgb, var(--ink) 24%, transparent); }
  .mobile-more-sheet { position:fixed; inset:auto 10px calc(76px + env(safe-area-inset-bottom)); z-index:19; padding:18px; border:1px solid var(--border); border-radius:var(--radius); background:var(--surface); box-shadow: var(--shadow); }
  .mobile-more-sheet header { display:flex; align-items:center; justify-content:space-between; gap:16px; margin-bottom:14px; }
  .mobile-more-sheet h2 { margin:2px 0 0; font-size:20px; }
  .mobile-more-sheet .icon-button { min-width:42px; min-height:42px; }
  .mobile-more-links { grid-template-columns:repeat(4,minmax(0,1fr)); gap:8px; }
  .mobile-more-links a { min-width:0; min-height:76px; justify-content:center; flex-direction:column; gap:7px; padding:8px 4px; border:1px solid var(--border); background:var(--surface-muted); font-size:12px; }
  .mobile-more-links a.router-link-active { box-shadow:inset 0 3px 0 var(--primary); }
  .mobile-more-enter-active,
  .mobile-more-leave-active { transition: opacity var(--motion-medium) var(--ease); }
  .mobile-more-enter-active .mobile-more-sheet,
  .mobile-more-leave-active .mobile-more-sheet { transition: transform var(--motion-medium) var(--ease), opacity var(--motion-medium) var(--ease); }
  .mobile-more-enter-from,
  .mobile-more-leave-to { opacity: 0; }
  .mobile-more-enter-from .mobile-more-sheet,
  .mobile-more-leave-to .mobile-more-sheet { transform: translateY(4px); opacity: 0; }
}

.sidebar { padding: 28px 16px 16px; background: var(--nav-bg); color: var(--nav-ink-strong); border: 0; box-shadow: none; overflow-y: auto; }
.sidebar::before, .brand-mark::after { display: none; }
.brand { color: var(--nav-ink-strong); padding: 0 6px 30px; gap: 11px; }
.brand-mark { background: var(--nav-active-bg); color: var(--nav-active-ink); width: 36px; height: 40px; border-radius: var(--radius-panel) var(--radius-panel) var(--radius) var(--radius); }
.brand strong { font-size: 15px; letter-spacing: .04em; }
.nav-group { margin-bottom: 16px; }
.nav-group > p { padding-left: 14px; margin: 0 0 7px; color: var(--nav-muted); font-size: 10px; letter-spacing: .16em; }
.sidebar nav a { color: var(--nav-ink); min-height: 48px; margin-bottom: 4px; font-size: 13px; }
.sidebar nav a:hover { color: var(--nav-ink-strong); background: var(--nav-hover); }
.sidebar nav a.router-link-active { background: var(--nav-active-bg); color: var(--nav-active-ink); box-shadow: none; font-weight: 750; }
.nav-text { display: flex; flex-direction: column; gap: 1px; min-width: 0; }
.nav-text strong { font-weight: inherit; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.nav-text small { font-size: 11px; line-height: 1.2; color: var(--nav-muted); font-weight: 400; }
.sidebar nav a.router-link-active .nav-text small { color: inherit; opacity: .72; }
.nav-dot { width: 5px; height: 5px; margin-left: auto; border-radius: 50%; background: currentColor; }
.sidebar-note { display: block; margin: auto 0 20px; padding: 20px 12px; background: transparent; border: 0; border-top: 1px solid var(--nav-line); border-radius: 0; text-decoration: none; color: var(--nav-card-ink); }
.sidebar-note strong { display: block; font-size: 14px; font-weight: 500; line-height: 1.9; }
.sidebar-note > span:last-child { margin-top: 12px; display: flex; justify-content: space-between; font-size: 11px; color: var(--nav-ink); }
.note-orbit { display: block; font-size: 22px; color: var(--amber); margin-bottom: 10px; }
.sidebar-account { display: flex; gap: 6px; align-items: center; border-top: 1px solid var(--nav-line); padding-top: 16px; }
.sidebar-account > a:first-child { display: flex; align-items: center; gap: 9px; min-width: 0; color: var(--nav-card-ink); text-decoration: none; flex: 1; }
.sidebar-account strong { display: block; font-size: 12px; font-weight: 650; }
.account-avatar { display: grid; place-items: center; width: 32px; height: 32px; flex: none; border-radius: 50%; background: var(--nav-avatar); color: var(--nav-avatar-ink); font-size: 12px; }
.account-settings { color: var(--nav-faint); padding: 8px; }
.workspace-topbar { min-height: 56px; display: flex; justify-content: space-between; align-items: center; padding: 0 32px; border-bottom: 1px solid var(--border); background: var(--canvas); }
/* 门前小街: always-on strip, same width/gutters as .page so it lines up with the page below it.
   Height is a clamp (168-208px, driven by 18vh) rather than a fixed number: at the old 140px the
   docked camera (dockedFrameHeight=128, see TownStage.vue) showed ~84% of the 1248px-wide world in
   one frame, so different STAGE_PLACES targets barely read as different corners of town. Taller
   zooms in enough (zoom = height/128) that the visible slice drops to roughly 56-70% of the world,
   without giving up the CLS=0 property (clamp() resolves before paint, same as the old fixed
   value - see tmp/town-stage-extension.md §4). */
.town-strip { width: min(100%, 1144px); height: clamp(168px, 18vh, 208px); margin: 12px auto 8px; border: 1px solid var(--border); border-radius: var(--radius-panel); background: var(--surface-muted); box-shadow: none; }
.town-strip[hidden] { display: none; }
@media (max-width: 1100px) { .town-strip { height: 144px; } }
@media (max-width: 760px) { .town-strip { height: 112px; margin: 10px auto 6px; } }
.workspace-context { display: flex; gap: 12px; align-items: center; color: var(--muted); font-size: 12px; }
.workspace-context strong { color: var(--ink); font-weight: 500; }
.context-slash { opacity: .4; }
.workspace-topbar > div { display: flex; align-items: center; gap: 6px; }
.guide-anchor { position: relative; }
.guide-popover {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  z-index: 20;
  width: min(300px, 86vw);
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
  background: var(--surface);
  box-shadow: var(--shadow);
}
.guide-pop-enter-active,
.guide-pop-leave-active { transition: opacity var(--motion-medium) var(--ease), transform var(--motion-medium) var(--ease); }
.guide-pop-enter-from,
.guide-pop-leave-to { opacity: 0; transform: translateY(4px); }
.mobile-more-sheet { max-height: 75dvh; overflow-y: auto; }
@media (max-width:760px) {
  .workspace-topbar { min-height: 52px; padding: 0 18px; }
  .workspace-context > svg, .workspace-context > span:not(.context-slash) { display: none; }
  .context-slash { display: none; }
  .mobile-nav { grid-template-columns: repeat(4, minmax(0, 1fr)); }
  .mobile-nav a, .mobile-nav button { font-size: 11px; }
  .mobile-more-links { grid-template-columns: repeat(3, minmax(0,1fr)); }
  .mobile-nav a.router-link-active, .mobile-nav button.is-active, .mobile-nav button[aria-expanded='true'] { box-shadow: none; }
}
</style>
