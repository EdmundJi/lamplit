<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowLeft,
  ArrowRight,
  BookOpen,
  BriefcaseBusiness,
  Check,
  CheckCircle2,
  Clock3,
  Dumbbell,
  HeartHandshake,
  Route,
  Sparkles,
} from 'lucide-vue-next'
import SnapSlider from '../../shared/ui/interaction/SnapSlider.vue'
import StepperProgress from '../../shared/ui/interaction/StepperProgress.vue'
import { api, type ApiError } from '../../shared/api/client'
import { notifyDataChanged } from '../../shared/data-sync'

type Scene = 'STUDY' | 'FITNESS' | 'CAREER' | 'EMOTIONAL_SUPPORT'
type StarterTask = {
  publicId: string
  title: string
  description: string
  estimatedMinutes: number
  difficulty: number
  plannedLocalTime: string
  rrule: string
  dimensionCode: string
}
type SetupResult = {
  alreadyCompleted: boolean
  completedAt: string
  goalPublicId: string | null
  weeklyPlanPublicId: string | null
  taskPublicIds: string[]
  rewardTitle: {
    code: string
    name: string
    description: string
    graphicKey: string
    frameStyle: string
  }
}

const scenes = [
  { code: 'STUDY' as Scene, name: '学习', detail: '阅读、复习与技能练习', icon: BookOpen },
  { code: 'FITNESS' as Scene, name: '身体照顾', detail: '运动、恢复与日常健康', icon: Dumbbell },
  { code: 'CAREER' as Scene, name: '职场', detail: '专注、沟通与工作推进', icon: BriefcaseBusiness },
  { code: 'EMOTIONAL_SUPPORT' as Scene, name: '情绪支持', detail: '觉察、休息与温和恢复', icon: HeartHandshake },
]
const difficultyNames = ['轻量', '适中', '进阶']
const router = useRouter()
const step = ref(1)
const form = reactive({ scene: 'STUDY' as Scene, dailyMinutes: 30, weeklyFrequency: 3, preferredDifficulty: 2 })
const starters = ref<StarterTask[]>([])
const selectedIds = ref<string[]>([])
const loadingStarters = ref(false)
const busy = ref(false)
const error = ref('')
const result = ref<SetupResult | null>(null)

const selectedScene = computed(() => scenes.find(item => item.code === form.scene) ?? scenes[0])

function goBack() {
  error.value = ''
  step.value = Math.max(1, step.value - 1)
}

async function openStarters() {
  step.value = 3
  error.value = ''
  loadingStarters.value = true
  try {
    starters.value = await api.get<StarterTask[]>(`/onboarding/starters?scene=${form.scene}`)
    selectedIds.value = starters.value.slice(0, 3).map(item => item.publicId)
  } catch {
    starters.value = []
    selectedIds.value = []
    error.value = '起步任务暂时无法加载，请稍后重试'
  } finally {
    loadingStarters.value = false
  }
}

function toggleStarter(publicId: string, checked: boolean) {
  if (!checked) {
    selectedIds.value = selectedIds.value.filter(id => id !== publicId)
    return
  }
  if (selectedIds.value.length < 3) selectedIds.value = [...selectedIds.value, publicId]
}

function handleStarterToggle(publicId: string, event: Event) {
  toggleStarter(publicId, (event.target as HTMLInputElement).checked)
}

async function finish() {
  if (!selectedIds.value.length) {
    error.value = '请至少保留一个起步任务'
    return
  }
  busy.value = true
  error.value = ''
  try {
    result.value = await api.post<SetupResult>('/onboarding/complete', {
      ...form,
      starterTemplatePublicIds: selectedIds.value,
    })
    notifyDataChanged(['goals', 'tasks', 'today', 'insights', 'attributes', 'achievements', 'profile', 'partners'])
    step.value = 4
  } catch (failure) {
    const apiError = failure as Partial<ApiError>
    error.value = apiError.message || '起步设置暂时无法保存，请重试'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <main class="page onboarding-page">
    <header class="onboarding-head">
      <div class="brand-lockup"><span class="brand-mark">好</span><strong>更好的自己</strong></div>
      <span v-if="step < 4" class="step-count">{{ step }} / 3</span>
    </header>

    <StepperProgress v-if="step < 4" class="setup-progress" :steps="3" :current="step" label="入门设置进度" />

    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <section v-if="step === 1" class="setup-band" aria-labelledby="scene-title">
      <div class="setup-title">
        <p class="eyebrow">先选一个方向</p>
        <h1 id="scene-title">最近想把精力放在哪里？</h1>
      </div>
      <div class="scene-grid">
        <button
          v-for="item in scenes"
          :key="item.code"
          type="button"
          class="scene-option"
          :aria-pressed="form.scene === item.code"
          @click="form.scene = item.code"
        >
          <span class="scene-icon"><component :is="item.icon" :size="22" /></span>
          <span><strong>{{ item.name }}</strong><small>{{ item.detail }}</small></span>
          <Check v-if="form.scene === item.code" :size="18" />
        </button>
      </div>
      <div class="setup-actions end"><button type="button" class="primary" @click="step = 2">继续<ArrowRight :size="17" /></button></div>
    </section>

    <section v-else-if="step === 2" class="setup-band" aria-labelledby="rhythm-title">
      <div class="setup-title">
        <p class="eyebrow">{{ selectedScene.name }}节奏</p>
        <h1 id="rhythm-title">给计划留出真实空间</h1>
      </div>
      <div class="rhythm-grid">
        <label class="range-field" for="daily-minutes">
          <span><strong>每天投入</strong><output for="daily-minutes">{{ form.dailyMinutes }} 分钟</output></span>
          <SnapSlider id="daily-minutes" v-model="form.dailyMinutes" :min="5" :max="120" :step="5" :value-text="`${form.dailyMinutes} 分钟`" />
        </label>
        <label class="range-field" for="weekly-frequency">
          <span><strong>每周频次</strong><output for="weekly-frequency">{{ form.weeklyFrequency }} 次</output></span>
          <SnapSlider id="weekly-frequency" v-model="form.weeklyFrequency" :min="1" :max="7" :step="1" :value-text="`每周 ${form.weeklyFrequency} 次`" />
        </label>
      </div>
      <fieldset class="difficulty-field">
        <legend>任务强度</legend>
        <div class="segmented">
          <button
            v-for="(name, index) in difficultyNames"
            :key="name"
            type="button"
            :aria-pressed="form.preferredDifficulty === index + 1"
            @click="form.preferredDifficulty = index + 1"
          >{{ name }}</button>
        </div>
      </fieldset>
      <div class="setup-actions"><button type="button" class="secondary" @click="goBack"><ArrowLeft :size="17" />返回</button><button type="button" class="primary" @click="openStarters">挑选任务<ArrowRight :size="17" /></button></div>
    </section>

    <section v-else-if="step === 3" class="setup-band" aria-labelledby="starters-title">
      <div class="setup-title starter-heading">
        <div><p class="eyebrow">起步任务</p><h1 id="starters-title">先留下几件做得到的事</h1></div>
        <span>{{ selectedIds.length }} / 3</span>
      </div>
      <div v-if="loadingStarters" class="loading-state" role="status">正在准备起步任务…</div>
      <div v-else-if="starters.length" class="starter-list">
        <label v-for="item in starters" :key="item.publicId" class="starter-item" :class="{ selected: selectedIds.includes(item.publicId) }">
          <input
            type="checkbox"
            :checked="selectedIds.includes(item.publicId)"
            :disabled="!selectedIds.includes(item.publicId) && selectedIds.length >= 3"
            @change="handleStarterToggle(item.publicId, $event)"
          >
          <span class="task-check"><Check :size="16" /></span>
          <span class="starter-copy"><strong>{{ item.title }}</strong><small>{{ item.description }}</small></span>
          <span class="task-meta"><span><Clock3 :size="14" />{{ item.estimatedMinutes }} 分钟</span><span>难度 {{ item.difficulty }}</span></span>
        </label>
      </div>
      <div v-else-if="!error" class="empty-state">当前场景还没有可用的起步任务。</div>
      <div class="setup-actions"><button type="button" class="secondary" @click="goBack"><ArrowLeft :size="17" />返回</button><button type="button" class="primary" :disabled="busy || !selectedIds.length" @click="finish"><CheckCircle2 :size="17" />{{ busy ? '正在创建…' : '开始行动' }}</button></div>
    </section>

    <section v-else-if="result" class="completion-band" aria-labelledby="complete-title">
      <div class="reward-mark"><Route :size="32" /></div>
      <p class="eyebrow">起步设置完成</p>
      <h1 id="complete-title">{{ result.rewardTitle.name }}</h1>
      <p>{{ result.rewardTitle.description }}</p>
      <div class="completion-facts">
        <span><CheckCircle2 :size="17" />{{ result.taskPublicIds.length || selectedIds.length }} 个起步任务</span>
        <span><Sparkles :size="17" />新称号已收入</span>
      </div>
      <div class="setup-actions center"><button type="button" class="primary" @click="router.push('/today')">查看今天<ArrowRight :size="17" /></button><button type="button" class="secondary" @click="router.push('/goals')">查看计划</button></div>
    </section>
  </main>
</template>

<style scoped>
.onboarding-page { width: min(880px, calc(100% - 32px)); min-height: 100vh; margin: 0 auto; padding-top: 28px; }
.onboarding-head { min-height: 48px; display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.brand-lockup { display: flex; align-items: center; gap: 10px; }
.brand-mark { width: 38px; height: 38px; display: grid; place-items: center; border-radius: var(--radius-card) var(--radius-card) var(--radius-card) var(--radius); background: var(--primary); color: white; font-size: 15px; font-weight: 900; }
.step-count { color: var(--muted); font-size: 13px; font-weight: 800; }
.setup-progress { margin: 18px 0 42px; }
.setup-band { display: grid; gap: 28px; padding-bottom: 56px; }
.setup-title { max-width: 650px; }
.setup-title h1, .completion-band h1 { margin: 5px 0 0; font-size: 32px; letter-spacing: 0; }
.scene-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.scene-option { min-width: 0; min-height: 112px; display: grid; grid-template-columns: 46px minmax(0, 1fr) 22px; align-items: center; gap: 12px; padding: 16px; border: 1px solid var(--border); background: var(--surface); color: var(--ink); text-align: left; }
.scene-option[aria-pressed='true'] { border-color: var(--primary); background: color-mix(in srgb, var(--primary-soft) 56%, var(--surface)); }
.scene-icon { width: 44px; height: 44px; display: grid; place-items: center; border-radius: var(--radius); background: var(--surface-muted); color: var(--primary); }
.scene-option > span:nth-child(2) { min-width: 0; display: grid; gap: 6px; }
.scene-option small, .starter-copy small { color: var(--muted); line-height: 1.5; }
.scene-option > svg { color: var(--primary); }
.rhythm-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; }
.range-field { display: grid; gap: 22px; min-height: 128px; padding: 18px; border-top: 1px solid var(--border); border-bottom: 1px solid var(--border); }
.range-field > span { display: flex; justify-content: space-between; gap: 12px; }
.range-field output { color: var(--primary); font-weight: 800; }
.range-field input { width: 100%; accent-color: var(--primary); }
.difficulty-field { min-width: 0; margin: 0; padding: 0; border: 0; }
.difficulty-field legend { margin-bottom: 10px; font-weight: 700; }
.segmented { width: min(100%, 480px); display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); padding: 3px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface-muted); }
.segmented button { border: 0; background: transparent; color: var(--muted); }
.segmented button[aria-pressed='true'] { background: var(--surface); color: var(--primary); }
.starter-heading { width: 100%; max-width: none; display: flex; align-items: end; justify-content: space-between; gap: 16px; }
.starter-heading > span { color: var(--primary); font-weight: 800; }
.starter-list { display: grid; gap: 10px; }
.starter-item { min-width: 0; min-height: 92px; display: grid; grid-template-columns: 24px minmax(0, 1fr) auto; align-items: center; gap: 13px; padding: 14px 16px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); cursor: pointer; }
.starter-item.selected { border-color: color-mix(in srgb, var(--primary) 50%, var(--border)); background: color-mix(in srgb, var(--primary-soft) 42%, var(--surface)); }
.starter-item > input { position: absolute; opacity: 0; pointer-events: none; }
.task-check { width: 22px; height: 22px; display: grid; place-items: center; border: 1px solid var(--border); border-radius: var(--radius); color: transparent; background: var(--surface); }
.starter-item.selected .task-check { border-color: var(--primary); background: var(--primary); color: white; }
.starter-copy { min-width: 0; display: grid; gap: 5px; }
.task-meta { display: grid; justify-items: end; gap: 6px; color: var(--muted); font-size: 12px; }
.task-meta span { display: inline-flex; align-items: center; gap: 5px; }
.loading-state, .empty-state { min-height: 210px; display: grid; place-items: center; color: var(--muted); border-top: 1px solid var(--border); border-bottom: 1px solid var(--border); }
.setup-actions { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding-top: 4px; }
.setup-actions.end { justify-content: flex-end; }
.setup-actions.center { justify-content: center; }
.setup-actions button { display: inline-flex; align-items: center; justify-content: center; gap: 8px; min-width: 132px; }
.completion-band { min-height: 560px; display: grid; place-items: center; align-content: center; gap: 12px; text-align: center; }
.completion-band > p:not(.eyebrow) { max-width: 460px; margin: 0; color: var(--muted); line-height: 1.7; }
.reward-mark { width: 78px; height: 78px; display: grid; place-items: center; margin-bottom: 10px; border: 1px solid color-mix(in srgb, var(--accent) 48%, var(--border)); border-radius: var(--radius-scene) var(--radius-scene) var(--radius-scene) var(--radius-card); background: color-mix(in srgb, var(--accent) 12%, var(--surface)); color: var(--accent); }
.completion-facts { display: flex; flex-wrap: wrap; justify-content: center; gap: 18px; margin: 10px 0 16px; color: var(--muted); font-size: 13px; }
.completion-facts span { display: inline-flex; align-items: center; gap: 6px; }
@media (prefers-reduced-motion: no-preference) {
  .scene-option, .starter-item, .reward-mark { animation: setup-enter var(--motion-medium) var(--ease) both; }
  .scene-option:hover, .starter-item:hover { border-color: color-mix(in srgb, var(--primary) 35%, var(--border)); }
  .scene-option:nth-child(2), .starter-item:nth-child(2) { animation-delay: 45ms; }
  .scene-option:nth-child(3), .starter-item:nth-child(3) { animation-delay: 90ms; }
  .scene-option:nth-child(4), .starter-item:nth-child(4) { animation-delay: 135ms; }
}
@keyframes setup-enter { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: translateY(0); } }
@media (max-width: 680px) {
  .onboarding-page { width: min(100% - 24px, 880px); padding-top: 16px; }
  .setup-progress { margin-bottom: 30px; }
  .setup-title h1, .completion-band h1 { font-size: 27px; }
  .scene-grid, .rhythm-grid { grid-template-columns: 1fr; }
  .starter-item { grid-template-columns: 24px minmax(0, 1fr); }
  .task-meta { grid-column: 2; grid-template-columns: repeat(2, auto); justify-content: start; }
}
@media (max-width: 430px) {
  .setup-actions { align-items: stretch; flex-direction: column-reverse; }
  .setup-actions.end, .setup-actions.center { flex-direction: column; }
  .setup-actions button { width: 100%; }
  .completion-facts { display: grid; justify-items: start; }
}
.onboarding-page { max-width: 900px; padding-top: 40px; }
.setup-band { background: var(--surface); padding: 32px; border: 1px solid var(--border); border-radius: var(--radius-scene); margin-bottom: 32px; }
.setup-title h1 { font-size: 32px; line-height: 1.45; }
.scene-option { border-radius: var(--radius-panel); padding: 20px; }
.scene-option[aria-pressed='true'] { box-shadow: inset 0 0 0 1px var(--primary); }
.brand-mark { border-radius: var(--radius-card) var(--radius-card) var(--radius) var(--radius); background: var(--forest); color: var(--sun); }
@media (max-width:760px) { .setup-band { padding: 20px; } .setup-title h1 { font-size: 26px; } }
</style>
