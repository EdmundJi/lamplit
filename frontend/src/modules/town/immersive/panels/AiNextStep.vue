<script setup lang="ts">
import { computed, inject, onBeforeUnmount, ref } from 'vue'
import { ArrowRight, Sparkles } from 'lucide-vue-next'
import { api } from '../../../../shared/api/client'
import { saveGoalDraft, type GoalDraft } from '../../../ai/goal-draft'
import { worldBridgeKey } from '../panel.types'

const props = defineProps<{ session: string; disabled: boolean; canReplace?: boolean }>()
const bridge = inject(worldBridgeKey, undefined)
const goal = ref<GoalDraft | null>(null)
const selected = ref(0)
const loading = ref(false)
const error = ref('')
const step = computed(() => goal.value?.starterTasks[selected.value])
let disposed = false
let request = 0
async function generate() {
  if (!props.session || props.disabled || loading.value) return
  const operation = ++request
  loading.value = true; error.value = ''
  try {
    const result = await api.post<GoalDraft>('/ai/goal-template', { sessionPublicId: props.session })
    if (disposed || operation !== request) return
    if (!Array.isArray(result.starterTasks) || !result.starterTasks.some(task => task?.title?.trim())) {
      error.value = '这次还没有拆出可执行的一步。补充你想完成的事情，再试一次。'
      return
    }
    goal.value = { ...result, starterTasks: result.starterTasks.filter(task => task?.title?.trim()).map(task => ({ ...task, estimatedMinutes: Math.min(240, Math.max(5, Number(task.estimatedMinutes) || 15)) })) }
    selected.value = 0
  } catch {
    if (!disposed && operation === request) error.value = '暂时没能整理成行动。对话仍在，可以稍后再试。'
  } finally { if (!disposed && operation === request) loading.value = false }
}
function confirm() {
  if (!goal.value || !step.value?.title.trim() || !bridge) return
  try {
    saveGoalDraft({ ...goal.value, starterTasks: [{ ...step.value, title: step.value.title.trim() }] })
    // A deliberate next step can replace the finished chat view, but never discard an unsent draft.
    if (props.canReplace) bridge.emit({ type: 'close' })
    bridge.emit({ type: 'open', panel: 'goals' })
  } catch { error.value = '暂时无法带入目标，请保留这段对话后重试。' }
}
onBeforeUnmount(() => { disposed = true; request++ })
</script>

<template>
  <section class="next-step" aria-label="把对话变成行动">
    <div class="step-heading"><Sparkles :size="16" /><strong>把想法变成今天的一小步</strong></div>
    <p v-if="!goal">聊清楚之后，选一件现在能开始的事。</p>
    <button v-if="!goal" type="button" class="secondary" :disabled="disabled || loading" @click="generate">{{ loading ? '正在整理这段对话…' : '帮我拆出第一步' }}</button>
    <form v-else @submit.prevent="confirm">
      <p class="step-context">{{ goal.title }}</p>
      <label v-if="goal.starterTasks.length > 1" class="step-field">今天先选哪一件
        <select v-model.number="selected"><option v-for="(task, index) in goal.starterTasks" :key="index" :value="index">{{ task.title }}</option></select>
      </label>
      <template v-if="step">
        <label class="step-field">这一步做到什么
          <input v-model="step.title" maxlength="160" required aria-label="今天的第一步">
        </label>
        <label class="step-minutes">给自己 <input v-model.number="step.estimatedMinutes" type="number" min="5" max="240" required aria-label="第一步预计分钟"> 分钟</label>
      </template>
      <p class="step-note">下一步确认目标和时间，保存后就能在「今天」开始。</p>
      <button type="submit" class="primary" :disabled="!bridge || disabled || !step?.title.trim()">确认这一步<ArrowRight :size="15" /></button>
    </form>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.next-step { display: grid; gap: 10px; padding: 14px; border: 1px solid color-mix(in srgb, var(--primary) 32%, var(--border)); border-radius: 12px; background: color-mix(in srgb, var(--primary-soft) 62%, var(--surface)); }
.step-heading { display: flex; align-items: center; gap: 7px; color: var(--primary); font-size: 13px; }
.next-step p { margin: 0; font-size: 12px; line-height: 1.6; color: var(--muted); }
.next-step form { display: grid; gap: 10px; }
.next-step .step-context { color: var(--ink); font-weight: 600; }
.step-field { display: grid; gap: 5px; color: var(--muted); font-size: 12px; }
.step-field input, .step-field select { width: 100%; min-width: 0; padding: 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--ink); }
.step-minutes { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--muted); }
.step-minutes input { width: 62px; padding: 6px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--ink); }
.next-step button { justify-self: start; display: inline-flex; align-items: center; gap: 6px; }
</style>
