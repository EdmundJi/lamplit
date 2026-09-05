<script setup lang="ts">
/**
 * 世界反馈的统一出口：toast 提示条、能力调用的成功/失败结果、庆祝/移镜头的"看得见的那句话"，
 * 都从这里出，各面板/能力不用各写一套提示。真正驱动引擎（Phaser 的 celebrate/focus/setNight/
 * enterAcademy）留在 ImmersiveTown.vue——这个组件只管"玩家能读到的那句话"，所以它不需要认识
 * TownGame，好独立测试。
 */
import { ref } from 'vue'
import type { WorldEvent } from './panel.types'

export type FeedbackKind = 'toast' | 'celebrate' | 'focus' | 'result' | 'error'
type FeedbackItem = { id: number; kind: FeedbackKind; text: string }

const DISMISS_MS = 3200

const items = ref<FeedbackItem[]>([])
let seq = 0

function push(kind: FeedbackKind, text: string) {
  if (!text) return
  const id = ++seq
  items.value.push({ id, kind, text })
  setTimeout(() => { items.value = items.value.filter(item => item.id !== id) }, DISMISS_MS)
}

function dismiss(id: number) {
  items.value = items.value.filter(item => item.id !== id)
}

/** 把一个 WorldEvent 翻译成一句提示。celebrate/focus 的实际引擎调用由调用方（外壳）自己做，
 * 这里只负责配一句能被人读到的话；open/close/night/academy 没有专门的文字反馈。 */
function handle(event: WorldEvent, residentName?: string) {
  if (event.type === 'toast') push('toast', event.text)
  else if (event.type === 'celebrate') push('celebrate', residentName ? `${residentName}完成了一件事` : '完成了一件事')
  else if (event.type === 'focus') push('focus', '镜头带你过去了')
}

/** 能力调用（WorldBridge.run）的结果：成功和失败都能给一句话。 */
function result(ok: boolean, text: string) {
  push(ok ? 'result' : 'error', text)
}

defineExpose({ handle, result, push })
</script>

<template>
  <div class="world-feedback" aria-live="polite">
    <p
      v-for="item in items"
      :key="item.id"
      class="feedback-item"
      :class="`is-${item.kind}`"
      @click="dismiss(item.id)"
    >{{ item.text }}</p>
  </div>
</template>

<style scoped>
.world-feedback { position: absolute; top: 64px; right: 16px; z-index: 7; display: grid; gap: 8px; max-width: min(320px, calc(100vw - 32px)); pointer-events: none; }
.feedback-item { margin: 0; padding: 8px 14px; border-radius: 999px; background: rgb(20 20 20 / 60%); color: #fff; font-size: 13px; backdrop-filter: blur(6px); cursor: pointer; pointer-events: auto; animation: feedback-in var(--motion-medium) ease-out; }
.feedback-item.is-result { background: color-mix(in srgb, var(--primary) 82%, black); }
.feedback-item.is-error { background: color-mix(in srgb, var(--danger) 78%, black); }
.feedback-item.is-celebrate { background: color-mix(in srgb, var(--sun) 88%, black 25%); color: var(--tone-celebrate-ink); }
@keyframes feedback-in { from { opacity: 0; transform: translateY(-6px); } to { opacity: 1; transform: none; } }
@media (prefers-reduced-motion: reduce) { .feedback-item { animation: none; } }
</style>
