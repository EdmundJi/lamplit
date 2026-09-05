<script setup lang="ts">
import { computed, onBeforeUnmount, onErrorCaptured, onMounted, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { Building2, Film, Flame, Footprints, GraduationCap, HelpCircle, Mail, Maximize2, Moon, Rabbit, Sparkles, Sun, LocateFixed, RefreshCw, X } from 'lucide-vue-next'
import { encouragement } from '../../shared/encouragement'
import { useTownStore } from './town.store'
import { activityFor, activityLabels, dimensionLabels, floorsForLevel } from './building-kit'
import NpcDialogue from './NpcDialogue.vue'
import TownOnboarding from './TownOnboarding.vue'
import { useNpcChatStore } from './npc-chat'
import { useTownNpcStore } from './town-npc.store'
import { TownControls, type RunMode } from './town-controls'
import type { TownGame, TownSelection } from './town.engine'
import type { TownModel } from './town.types'

const store = useTownStore()
const npcChatStore = useNpcChatStore()
const townNpcStore = useTownNpcStore()
const router = useRouter()
const canvas = ref<HTMLElement | null>(null)
const onboardingRef = ref<InstanceType<typeof TownOnboarding> | null>(null)
const night = ref(new Date().getHours() >= 18 || new Date().getHours() < 6)
const selection = ref<TownSelection>(null)
const engineError = ref('')
const insideAcademy = ref(false)
const playerPosition = ref<{ x: number; y: number } | null>(null)
const distanceToGuide = ref<number | null>(null)
// 观察模式：镜头脱离玩家在兴趣点之间巡游（plan D4）。既是录 demo 的工具，也是一个真功能。
const observing = ref(false)
// A NpcDialogue that throws while mounting/streaming falls back to the old static card below,
// instead of leaving the panel blank — reset whenever the selection changes so a fresh open retries.
const npcDialogueError = ref(false)
let game: TownGame | null = null
let controls: TownControls | null = null
let mountSequence = 0
let residentSignature = ''

function residentKey(model: TownModel | null) {
  return model ? model.residents.map(item => item.publicId).join(',') : ''
}

const residents = computed(() => store.model?.residents ?? [])
const selected = computed(() => residents.value.find(item => item.publicId === selection.value) ?? null)
const self = computed(() => residents.value.find(item => item.isSelf) ?? null)
const awakeCount = computed(() => residents.value.filter(item => activityFor(item) !== 'resting').length)
const studying = computed(() => residents.value.filter(item => activityFor(item) === 'done'))
const unread = computed(() => store.model?.unread ?? 0)
// TownControls 是普通对象，不是响应式的：用 computed 读它的 getter 会因为没有任何依赖而
// 求值一次就永久缓存，按钮的 aria-pressed/文案再也不会更新。改成由控制器的回调回写 ref。
const running = ref(false)
const runMode = ref<RunMode>('walk')
const anyPanelOpen = computed(() => selection.value !== null)
const assistantLine = computed(() => {
  if (!self.value) return ''
  const activity = activityFor(self.value)
  if (activity === 'done') return encouragement('taskCompleted')
  if (activity === 'working') return encouragement('taskStarted')
  if (activity === 'planned') return '今天的安排已经排好了，先挑最小的那一件开始，房子就会亮灯。'
  return '还没有今天的安排。要不要让我帮你把目标拆成今天能做的一小步？'
})
const nextFloorLevel = computed(() => (selected.value ? (floorsForLevel(selected.value.level) + 1) * 2 + 1 : 0))
const postmanLine = computed(() => (unread.value > 0 ? `有 ${unread.value} 封新信在等你，是朋友们捎来的话。` : '今天没有新的信，朋友们都在各忙各的。'))
// 小助的开场白：优先用昨晚反思的问候语，没有就退回原来按今日状态生成的那句鼓励。
const guideOpener = computed(() => npcChatStore.reflection?.greeting || assistantLine.value)
const isDialogueOpen = computed(() => !npcDialogueError.value && (selection.value === 'npc:assistant' || selection.value === 'npc:postman'))

async function mountGame() {
  if (!canvas.value || !store.model) return
  const sequence = ++mountSequence
  engineError.value = ''
  try {
    const { createTownGame } = await import('./town.engine')
    if (sequence !== mountSequence) return
    game?.destroy()
    game = null
    const created = await createTownGame(canvas.value, store.model, {
      onSelect: value => { selection.value = value },
      onAcademyChange: inside => { insideAcademy.value = inside; if (!inside && selection.value === 'academy') selection.value = null },
      onPlayerMove: (x, y) => { playerPosition.value = { x, y } },
      onDistanceToGuide: distance => { distanceToGuide.value = distance },
    })
    if (sequence !== mountSequence) { created.destroy(); return }
    game = created
    game.setNight(night.value)
    game.setRun(running.value)
    residentSignature = residentKey(store.model)
    // 名册单独拉：/town 的载荷保持原样，小镇社会是加在旁边的一层，拉不到也不该让画面起不来。
    await townNpcStore.load()
    if (sequence === mountSequence && game) game.applyNpcs(townNpcStore.npcs, townNpcStore.budget)
  } catch (error) {
    engineError.value = (error as Error).message || '小镇画面初始化失败'
  }
}

function toggleObservation() {
  observing.value = !observing.value
  game?.setObservation(observing.value)
}

function toggleNight() {
  night.value = !night.value
  game?.setNight(night.value)
}

function toggleRun() {
  controls?.toggleRunMode()
}

/** 把控制器的实际跑步状态（持久模式 + Shift 临时加速）同步给界面和引擎。 */
function syncRunning() {
  running.value = controls?.isRunning ?? false
  game?.setRun(running.value)
}

function goHome() {
  if (self.value) { game?.focus(self.value.publicId); selection.value = self.value.publicId }
}

function toggleAcademy() {
  if (insideAcademy.value) game?.exitAcademy()
  else { game?.enterAcademy(); selection.value = 'academy' }
}

async function reload() {
  await store.load()
}

function showOnboarding() {
  onboardingRef.value?.restart()
}

/** An action run from inside the dialogue (task start/complete/defer/skip) touches server state,
 * so refresh the town straight away instead of waiting for the next 30s poll. */
async function onNpcAction() {
  await store.load()
}

function onKeyDown(event: KeyboardEvent) {
  if (controls?.handleKeyDown(event)) {
    event.preventDefault()
  }
}

function onKeyUp(event: KeyboardEvent) {
  if (controls?.handleKeyUp(event)) {
    event.preventDefault()
  }
}

onMounted(async () => {
  void npcChatStore.loadReflection()

  // 初始化控制器
  controls = new TownControls({
    onRunModeChange: mode => {
      runMode.value = mode
      syncRunning()
    },
    onShiftChange: () => {
      syncRunning()
    },
  })

  // 注册键盘事件
  document.addEventListener('keydown', onKeyDown)
  document.addEventListener('keyup', onKeyUp)

  if (store.model) await mountGame()
  else await store.load()
  store.startPolling()
})

watch(selection, () => { npcDialogueError.value = false })

// NpcDialogue talks to a still-shifting SSE contract; if it throws, fall back to the old
// static card (with its RouterLink) instead of leaving the panel blank.
onErrorCaptured(error => {
  if (selection.value === 'npc:assistant' || selection.value === 'npc:postman') {
    console.error('NpcDialogue 渲染失败，退回静态提示', error)
    npcDialogueError.value = true
    return false
  }
  return true
})

// Same residents as before → just update the running scene in place; a different resident
// list (friends added/removed, solo mode toggled) rebuilds the game from scratch.
watch(() => store.model, async model => {
  if (!model) return
  if (game && residentKey(model) === residentSignature) {
    game.applyModel(model)
  } else {
    await mountGame()
  }
})

watch(() => store.lastCelebrations, celebrations => {
  if (!game) return
  for (const item of celebrations) game.celebrate(item.publicId)
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeyDown)
  document.removeEventListener('keyup', onKeyUp)
  store.stopPolling()
  controls?.destroy()
  controls = null
  game?.destroy()
  game = null
})
</script>

<template>
  <section class="page page--scene town-page">
    <TownOnboarding
      v-if="self"
      ref="onboardingRef"
      :user-id="self.publicId"
      :player-position="playerPosition"
      :distance-to-guide="distanceToGuide"
      :any-panel-open="anyPanelOpen"
    />

    <header class="page-head">
      <div>
        <p class="eyebrow">一步一步，自成风景</p>
        <h1>成长小镇</h1>
      </div>
      <div class="actions">
        <button class="secondary" type="button" @click="toggleNight">
          <component :is="night ? Sun : Moon" :size="17" />{{ night ? '切到白天' : '切到夜晚' }}
        </button>
        <button class="secondary run-toggle" type="button" :aria-pressed="runMode === 'run'" @click="toggleRun">
          <component :is="runMode === 'run' ? Rabbit : Footprints" :size="17" />{{ runMode === 'run' ? '奔跑中' : '开始奔跑' }}
        </button>
        <button class="secondary" type="button" :aria-pressed="observing" title="镜头脱离玩家，在小镇的几个热闹处之间缓慢巡游" @click="toggleObservation">
          <Film :size="17" />{{ observing ? '退出观察' : '观察小镇' }}
        </button>
        <button class="secondary" type="button" @click="selection = 'npc:assistant'"><Sparkles :size="17" />找小助</button>
        <button class="secondary" type="button" @click="toggleAcademy"><GraduationCap :size="17" />{{ insideAcademy ? '回到小镇' : '去学院' }}</button>
        <button class="secondary" type="button" :disabled="!self" @click="goHome"><LocateFixed :size="17" />回到我家</button>
        <button class="secondary" type="button" title="重新打开新手引导" @click="showOnboarding"><HelpCircle :size="17" />帮助</button>
        <button class="secondary" type="button" title="进入沉浸模式" @click="router.push('/town/immersive')"><Maximize2 :size="17" />沉浸模式</button>
        <button class="icon-button" type="button" title="刷新" aria-label="刷新" :disabled="store.loading" @click="reload"><RefreshCw :size="17" /></button>
      </div>
    </header>

    <p v-if="store.error" class="error" role="alert">{{ store.error }}</p>
    <p v-else-if="engineError" class="error" role="alert">{{ engineError }}</p>

    <div class="town-stage">
      <div ref="canvas" class="town-canvas" data-testid="town-canvas" />
      <div v-if="store.loading && !store.model" class="town-loading" role="status">正在把大家的房子搬进小镇…</div>

      <aside class="town-panel" :class="{ 'is-open': selection !== null, 'has-dialogue': isDialogueOpen }" @pointerdown.stop>
        <button v-if="selection !== null && !isDialogueOpen" class="town-panel-close" type="button" aria-label="关闭" @click="selection = null"><X :size="16" /></button>

        <template v-if="selected">
          <p class="eyebrow">{{ selected.isSelf ? '这是你的房子' : '邻居' }}</p>
          <h2>{{ selected.displayName }}<small v-if="selected.title"> · {{ selected.title }}</small></h2>
          <dl class="town-facts">
            <div><dt>综合等级</dt><dd>LV.{{ selected.level }} · {{ floorsForLevel(selected.level) + 1 }} 层</dd></div>
            <div><dt>主要方向</dt><dd>{{ selected.dominantDimension ? dimensionLabels[selected.dominantDimension] : '还在摸索' }}</dd></div>
            <div><dt>今天</dt><dd>{{ activityLabels[activityFor(selected)] }}<template v-if="selected.todayPlanned">（{{ selected.todayDone }}/{{ selected.todayPlanned }}）</template></dd></div>
            <div><dt>最长连续</dt><dd><Flame :size="14" /> {{ selected.longestStreak }} 天</dd></div>
            <div v-if="floorsForLevel(selected.level) < 4"><dt>下一层</dt><dd>升到 LV.{{ nextFloorLevel }} 加盖</dd></div>
          </dl>
          <RouterLink v-if="selected.isSelf" class="button primary" to="/today">继续今天的行动</RouterLink>
          <RouterLink v-else class="button secondary" :to="`/friends/${selected.publicId}`">看看 TA 的成长</RouterLink>
        </template>

        <template v-else-if="selection === 'npc:assistant'">
          <NpcDialogue v-if="!npcDialogueError" npc="GUIDE" display-name="小助" :opener="guideOpener" @close="selection = null" @action="onNpcAction" />
          <template v-else>
            <p class="eyebrow">学院门口的向导</p>
            <h2><Sparkles :size="20" /> 小助</h2>
            <p class="town-speech">{{ assistantLine }}</p>
            <RouterLink class="button primary" to="/ai">找小助聊聊今天</RouterLink>
          </template>
        </template>

        <template v-else-if="selection === 'npc:postman'">
          <NpcDialogue v-if="!npcDialogueError" npc="POSTMAN" display-name="邮递员" :opener="postmanLine" @close="selection = null" @action="onNpcAction" />
          <template v-else>
            <p class="eyebrow">街上的邮递员</p>
            <h2><Mail :size="20" /> 邮递员</h2>
            <p class="town-speech">{{ postmanLine }}</p>
            <RouterLink class="button" :class="unread > 0 ? 'primary' : 'secondary'" to="/friends/chat">去看看信件</RouterLink>
          </template>
        </template>

        <template v-else-if="selection === 'academy'">
          <p class="eyebrow">大家一起变好的地方</p>
          <h2><GraduationCap :size="20" /> 成长学院</h2>
          <p class="town-speech">今天有 {{ studying.length }} 位邻居完成了任务，正在学院里读书。</p>
          <ul v-if="studying.length" class="town-roll">
            <li v-for="item in studying" :key="item.publicId">{{ item.isSelf ? '我' : item.displayName }} · {{ item.todayDone }}/{{ item.todayPlanned }}</li>
          </ul>
          <RouterLink class="button secondary" to="/friends">邀请朋友搬来小镇</RouterLink>
        </template>

        <template v-else>
          <p class="eyebrow">小镇现状</p>
          <h2><Building2 :size="20" /> {{ residents.length }} 户人家</h2>
          <p class="muted">今天已经开张 {{ awakeCount }} 户。点一栋房子、一个小人，看看背后的故事。</p>
          <ul class="town-legend">
            <li><strong>门面</strong>是主人最擅长的方向：书店是知识，健身房是健康，公寓是职场，冰淇淋店是关系，面包房是心境。</li>
            <li><strong>楼层</strong>随综合等级增加，每两级加一层。</li>
            <li><strong>卷帘门</strong>放下来，说明主人今天还没动手。</li>
            <li><strong>屋顶装置</strong>来自连续记录，中断不会拆掉已经装好的东西。</li>
            <li>去<strong>学院</strong>读书的小人，是今天已经完成任务的人。</li>
          </ul>
        </template>
      </aside>

      <p class="town-hint">拖动平移 · 滚轮缩放 · 点击房子和小人 · 方向键/WASD 移动，按 R 或右上角按钮切换跑步</p>
    </div>

    <details class="town-directory"><summary>地点与居民 · 也可以从这里探索</summary><div class="actions"><button class="secondary" @click="selection = 'academy'">成长学院</button><button class="secondary" @click="selection = 'npc:assistant'">小助</button><button class="secondary" @click="selection = 'npc:postman'">邮递员</button><button v-for="resident in residents" :key="resident.publicId" class="secondary" @click="selection = resident.publicId">{{ resident.isSelf ? '我的家' : resident.displayName }}</button></div></details>
    <p class="town-credits muted">像素美术：LimeZu（limezu.itch.io），已获授权使用。</p>
  </section>
</template>

<style scoped>
.town-page { width: min(100%, 1280px); }
.town-stage { position: relative; }
.town-canvas { width: 100%; height: clamp(480px, 72vh, 820px); border-radius: var(--radius); overflow: hidden; background: #78a95f; box-shadow: var(--shadow); touch-action: none; }
.town-canvas :deep(canvas) { display: block; image-rendering: pixelated; }
.town-loading { position: absolute; inset: 0; display: grid; place-items: center; color: #fff; font-weight: 700; background: rgb(30 40 30 / 35%); border-radius: var(--radius); }
.town-hint { position: absolute; left: 14px; bottom: 12px; margin: 0; padding: 5px 10px; border-radius: 999px; font-size: 12px; color: #fff; background: rgb(20 20 20 / 45%); backdrop-filter: blur(6px); pointer-events: none; }
.town-panel { position: absolute; top: 14px; right: 14px; width: min(320px, calc(100% - 28px)); display: grid; gap: 12px; padding: 18px; border: 1px solid color-mix(in srgb, var(--border) 60%, transparent); border-radius: var(--radius); background: color-mix(in srgb, var(--surface) 92%, transparent); backdrop-filter: blur(12px); box-shadow: var(--shadow); pointer-events: auto; user-select: none; }
.town-panel.has-dialogue { width: min(360px, calc(100% - 28px)); padding: 10px; background: transparent; border-color: transparent; box-shadow: none; backdrop-filter: none; }
.town-panel-close { position: absolute; top: 10px; right: 10px; width: 30px; height: 30px; min-height: 0; display: grid; place-items: center; padding: 0; border: 0; border-radius: 999px; background: transparent; color: var(--muted); user-select: none; }
.town-panel-close:hover { background: var(--surface-muted); color: var(--ink); }
.town-panel h2 { margin: 0; display: flex; align-items: center; gap: 8px; font-size: 22px; }
.town-panel h2 small { font-size: 13px; font-weight: 600; color: var(--muted); }
.town-panel .eyebrow { margin: 0; }
.town-speech { margin: 0; padding: 12px 14px; border-radius: var(--radius); background: color-mix(in srgb, var(--primary-soft) 60%, var(--surface)); color: var(--ink); font-size: 14px; line-height: 1.65; }
.town-facts { margin: 0; display: grid; gap: 8px; }
.town-facts div { display: flex; justify-content: space-between; gap: 12px; font-size: 14px; }
.town-facts dt { color: var(--muted); }
.town-facts dd { margin: 0; display: inline-flex; align-items: center; gap: 4px; font-weight: 650; text-align: right; }
.town-roll { margin: 0; padding: 0 0 0 18px; font-size: 13px; line-height: 1.7; }
.town-legend { margin: 0; padding: 0 0 0 18px; display: grid; gap: 6px; font-size: 13px; line-height: 1.6; color: var(--muted); }
.town-legend strong { color: var(--ink); }
.town-credits { margin: 14px 0 0; font-size: 12px; }
.run-toggle[aria-pressed='true'] { background: var(--primary-strong); color: var(--surface); border-color: var(--primary-strong); }
@media (max-width: 760px) {
  .town-canvas { height: clamp(420px, 60vh, 640px); }
  .town-panel { top: auto; bottom: 44px; left: 14px; right: 14px; width: auto; max-height: 55%; overflow: auto; }
  .town-panel:not(.is-open) { display: none; }
}
.town-page { width: 100%; max-width: 1600px; }
.town-stage { border: 6px solid var(--forest); border-radius: var(--radius-scene); background: var(--forest); overflow: hidden; }
.town-canvas { border-radius: calc(var(--radius-scene) - 6px); box-shadow: none; height: clamp(480px, 65vh, 820px); }
.town-panel { border-radius: var(--radius-panel); padding: 22px; box-shadow: var(--shadow); }
.town-directory { margin-top: 18px; padding: 14px 18px; background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius); }
.town-directory summary { cursor: pointer; color: var(--primary-strong); font-size: 13px; }
.town-directory .actions { padding-top: 14px; }
@media (max-width:760px) { .town-stage { border-width: 4px; } .town-canvas { height: 56vh; min-height: 380px; } .town-page .page-head .actions { gap: 6px; } .town-page .page-head .actions button { font-size: 11px; padding-inline: 10px; } }
</style>
