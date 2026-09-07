<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ChevronRight, X } from 'lucide-vue-next'
const props = defineProps<{ userId: string; playerPosition?: { x: number; y: number } | null; distanceToGuide?: number | null; anyPanelOpen?: boolean; conversationOpen?: boolean }>()
const emit = defineEmits<{ close: []; skip: []; action: [action: 'home-style' | 'today' | 'meet'] }>()
const steps = [
  { title: '欢迎来到成长小镇', content: '先给家选一点喜欢的颜色。这里会保存你的经历，也欢迎你只是回来休息。', action: 'home-style' as const, label: '布置我的家' },
  { title: '今天的一小步', content: '打开手账，记下一件想做的小事。暂时没有计划也没关系，可以直接去逛逛。', action: 'today' as const, label: '打开手账' },
  { title: '认识小助', content: '小助可以陪你想清楚今天。之后点击地面行走，用“去哪里”找地点；今天与信箱可以随时直接打开。', action: 'meet' as const, label: '和小助聊聊' },
]
const currentStep = ref(0)
const visible = ref(false)
const step = computed(() => steps[currentStep.value]!)
const progress = computed(() => (currentStep.value + 1) / steps.length * 100)
const STORAGE_KEY = computed(() => `better-self:town-onboarding:${props.userId}:completed`)
function checkCompleted() { try { return localStorage.getItem(STORAGE_KEY.value) === 'true' } catch { return false } }
function markCompleted() { try { localStorage.setItem(STORAGE_KEY.value, 'true') } catch { /* session still completes */ } }
function close() { markCompleted(); visible.value = false; emit('close') }
function skip() { markCompleted(); visible.value = false; emit('skip') }
function next() { if (currentStep.value === steps.length - 1) close(); else currentStep.value++ }
function act() { const action = step.value.action; next(); emit('action', action) }
onMounted(() => { visible.value = !checkCompleted() })
defineExpose({ restart: () => { currentStep.value = 0; visible.value = true }, isCompleted: checkCompleted })
</script>
<template>
  <Teleport to="body">
    <div v-if="visible && !anyPanelOpen && !conversationOpen" class="town-onboarding allows-play">
      <section class="onboarding-card" role="dialog" aria-label="入住小镇">
        <button class="onboarding-close" type="button" aria-label="关闭引导" @click="skip"><X :size="16" /></button>
        <div class="onboarding-progress"><div class="progress-bar" :style="{ width: `${progress}%` }" /></div>
        <div class="onboarding-content"><h2 class="onboarding-title">{{ step.title }}</h2><p class="onboarding-text">{{ step.content }}</p></div>
        <div class="onboarding-actions">
          <button class="secondary onboarding-skip" type="button" @click="skip">先逛逛</button>
          <button class="secondary" type="button" @click="next">{{ currentStep === 2 ? '完成入住' : '下一步' }}</button>
          <button class="primary" type="button" @click="act">{{ step.label }}<ChevronRight :size="16" /></button>
        </div>
        <p class="onboarding-step-count">第 {{ currentStep + 1 }} 步，共 {{ steps.length }} 步 · 随时可从帮助重开</p>
      </section>
    </div>
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

.town-onboarding.allows-play { place-items: end start; padding-bottom: 90px; }
.onboarding-overlay.allows-play { pointer-events: none; background: transparent; backdrop-filter: none; }
.town-onboarding.allows-play .onboarding-card { width: min(340px, 100%); gap: 10px; padding: 18px; max-height: 45vh; overflow: auto; }
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
