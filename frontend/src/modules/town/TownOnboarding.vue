<script setup lang="ts">
/**
 * 成长小镇新手引导：分步教程，localStorage 记录进度，支持跳过与重放
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowDown, ChevronRight, X } from 'lucide-vue-next'
import type { TownResident } from './town.types'

export interface OnboardingProps {
  /** 当前登录用户的 publicId（用于 localStorage key） */
  userId: string
  /** 玩家当前坐标（用于检测移动） */
  playerPosition?: { x: number; y: number } | null
  /** 玩家与小助的距离（用于检测靠近 NPC） */
  distanceToGuide?: number | null
  /** 是否打开了任意面板（用于检测面板交互） */
  anyPanelOpen?: boolean
}

const props = defineProps<OnboardingProps>()

const emit = defineEmits<{
  close: []
  skip: []
}>()

// 引导步骤定义
type StepCondition = () => boolean
type StepDefinition = {
  id: string
  title: string
  content: string
  highlight: string | null
  arrow: string | null
  autoNext: boolean
  condition?: StepCondition
}

const steps: StepDefinition[] = [
  {
    id: 'welcome',
    title: '欢迎来到成长小镇',
    content: '这是你的成长数据可视化空间，每个人都有自己的家，邻居是你的好友。点一栋房子，看看背后的故事。',
    highlight: null,
    arrow: null,
    autoNext: false,
  },
  {
    id: 'movement',
    title: '四处走走',
    content: '点击地面让小人走过去，或用方向键 / WASD 移动。右下角按钮可以切换跑步模式，按住 Shift 也能临时加速。',
    highlight: '.town-canvas',
    arrow: 'bottom',
    autoNext: true,
    condition: () => {
      const initial = initialPosition.value
      const current = props.playerPosition
      if (!initial || !current) return false
      const dx = current.x - initial.x
      const dy = current.y - initial.y
      return Math.sqrt(dx * dx + dy * dy) > 50
    },
  },
  {
    id: 'npc',
    title: '和 NPC 打个招呼',
    content: '走到小助旁边试试看！你可以和 NPC 对话、进入建筑、查看邻居家的成长。',
    highlight: null,
    arrow: null,
    autoNext: true,
    condition: () => {
      const dist = props.distanceToGuide
      return dist !== null && dist !== undefined && dist < 80
    },
  },
  {
    id: 'panels',
    title: '功能面板',
    content: '点击底部图标打开功能面板：今天的任务、目标规划、好友消息…… 完成任务后小镇会放庆祝动画！',
    highlight: '.page-head .actions, .immersive-dock',
    arrow: 'top',
    autoNext: true,
    condition: () => props.anyPanelOpen === true,
  },
  {
    id: 'immersive',
    title: '沉浸模式',
    content: '点击顶部"沉浸模式"按钮进入全屏体验，获得更大的空间。按 Esc 随时退出。',
    highlight: '.town-immersive-link, .immersive-topbar .icon-button',
    arrow: null,
    autoNext: false,
  },
  {
    id: 'done',
    title: '开始探索',
    content: '引导结束！你可以自由探索小镇了。右上角的"？"按钮可以重新打开引导。',
    highlight: null,
    arrow: null,
    autoNext: false,
  },
]

const currentStep = ref(0)
const initialPosition = ref<{ x: number; y: number } | null>(null)
const visible = ref(false)

const step = computed(() => steps[currentStep.value])
const isLastStep = computed(() => currentStep.value === steps.length - 1)
const progress = computed(() => ((currentStep.value + 1) / steps.length) * 100)

const STORAGE_KEY = computed(() => `better-self:town-onboarding:${props.userId}:completed`)

function checkCompleted(): boolean {
  try {
    return localStorage.getItem(STORAGE_KEY.value) === 'true'
  } catch {
    return false
  }
}

function markCompleted(): void {
  try {
    localStorage.setItem(STORAGE_KEY.value, 'true')
  } catch {
    // localStorage 不可用时静默失败
  }
}

function next(): void {
  if (isLastStep.value) {
    close()
  } else {
    currentStep.value++
  }
}

function skip(): void {
  markCompleted()
  visible.value = false
  emit('skip')
}

function close(): void {
  markCompleted()
  visible.value = false
  emit('close')
}

// 监听自动推进条件
watch(
  () => step.value.condition?.(),
  (met) => {
    if (met && step.value.autoNext) {
      setTimeout(next, 600) // 延迟一下让用户看到完成提示
    }
  },
)

// 记录初始坐标（用于检测移动）
watch(
  () => props.playerPosition,
  (pos) => {
    if (pos && !initialPosition.value && currentStep.value === 1) {
      initialPosition.value = { ...pos }
    }
  },
  { immediate: true },
)

onMounted(() => {
  if (!checkCompleted()) {
    visible.value = true
  }
})

defineExpose({
  restart: () => {
    currentStep.value = 0
    initialPosition.value = null
    visible.value = true
  },
  isCompleted: checkCompleted,
})
</script>

<template>
  <Teleport to="body">
    <Transition name="onboarding-fade">
      <div v-if="visible" class="town-onboarding">
        <div class="onboarding-overlay" @click.self="skip" />

        <div class="onboarding-card">
          <button
            class="onboarding-close"
            type="button"
            aria-label="关闭引导"
            @click="skip"
          >
            <X :size="16" />
          </button>

          <div class="onboarding-progress">
            <div class="progress-bar" :style="{ width: `${progress}%` }" />
          </div>

          <div class="onboarding-content">
            <h2 class="onboarding-title">{{ step.title }}</h2>
            <p class="onboarding-text">{{ step.content }}</p>

            <div v-if="step.autoNext" class="onboarding-hint">
              <ArrowDown :size="16" />
              <span>完成操作后自动继续</span>
            </div>
          </div>

          <div class="onboarding-actions">
            <button
              v-if="currentStep > 0"
              class="secondary"
              type="button"
              @click="currentStep--"
            >
              上一步
            </button>
            <button
              class="secondary onboarding-skip"
              type="button"
              @click="skip"
            >
              跳过引导
            </button>
            <button
              v-if="!step.autoNext"
              class="primary"
              type="button"
              @click="next"
            >
              {{ isLastStep ? '开始探索' : '下一步' }}
              <ChevronRight :size="16" />
            </button>
          </div>

          <p class="onboarding-step-count">
            第 {{ currentStep + 1 }} 步，共 {{ steps.length }} 步
          </p>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.town-onboarding {
  position: fixed;
  inset: 0;
  z-index: 9999;
  display: grid;
  place-items: center;
  padding: 20px;
  pointer-events: none;
}

.onboarding-overlay {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.7);
  backdrop-filter: blur(4px);
  pointer-events: auto;
}

.onboarding-card {
  position: relative;
  z-index: 1;
  width: min(460px, 100%);
  display: grid;
  gap: 20px;
  padding: 28px;
  border-radius: var(--radius-panel);
  background: var(--surface);
  box-shadow: var(--shadow);
  pointer-events: auto;
  animation: onboarding-enter var(--motion-medium) ease-out;
}

.onboarding-close {
  position: absolute;
  top: 12px;
  right: 12px;
  width: 32px;
  height: 32px;
  min-height: 0;
  display: grid;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}

.onboarding-close:hover {
  background: var(--surface-muted);
  color: var(--ink);
}

.onboarding-progress {
  height: 4px;
  border-radius: 999px;
  background: var(--surface-muted);
  overflow: hidden;
}

.progress-bar {
  height: 100%;
  background: linear-gradient(90deg, var(--primary), var(--accent));
  transition: width var(--motion-medium) ease-out;
}

.onboarding-content {
  display: grid;
  gap: 12px;
}

.onboarding-title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  color: var(--ink);
}

.onboarding-text {
  margin: 0;
  font-size: 15px;
  line-height: 1.65;
  color: var(--ink);
}

.onboarding-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  border-radius: var(--radius);
  background: var(--primary-soft);
  color: var(--primary-strong);
  font-size: 13px;
  animation: onboarding-pulse 2s ease-in-out infinite;
}

.onboarding-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}

.onboarding-actions .primary {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 4px;
}

.onboarding-skip {
  font-size: 13px;
  color: var(--muted);
}

.onboarding-step-count {
  margin: 0;
  font-size: 12px;
  text-align: center;
  color: var(--muted);
}

@keyframes onboarding-enter {
  from {
    opacity: 0;
    transform: scale(0.95) translateY(20px);
  }
  to {
    opacity: 1;
    transform: scale(1) translateY(0);
  }
}

@keyframes onboarding-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.7; }
}

.onboarding-fade-enter-active,
.onboarding-fade-leave-active {
  transition: opacity var(--motion-medium) ease-out;
}

.onboarding-fade-enter-from,
.onboarding-fade-leave-to {
  opacity: 0;
}

.onboarding-fade-enter-active .onboarding-card,
.onboarding-fade-leave-active .onboarding-card {
  transition: transform var(--motion-medium) ease-out, opacity var(--motion-medium) ease-out;
}

.onboarding-fade-enter-from .onboarding-card,
.onboarding-fade-leave-to .onboarding-card {
  transform: scale(0.95) translateY(20px);
  opacity: 0;
}

@media (max-width: 760px) {
  .onboarding-card {
    padding: 24px 20px;
    gap: 16px;
  }

  .onboarding-title {
    font-size: 19px;
  }

  .onboarding-text {
    font-size: 14px;
  }

  .onboarding-actions {
    flex-wrap: wrap;
  }

  .onboarding-actions .primary {
    flex: 1 1 100%;
    margin-left: 0;
  }
}
</style>
