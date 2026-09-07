<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Send, X } from 'lucide-vue-next'
import TownStories from './TownStories.vue'
import { useNpcChatStore, type NpcAction, type NpcActionResult, type NpcCode, type NpcOption } from './npc-chat'

const props = defineProps<{
  npc: NpcCode
  displayName: string
  /** First line to show before any history exists — e.g. a reflection greeting the parent already fetched. */
  opener?: string
  leavingReason?: string
}>()
const emit = defineEmits<{ close: []; action: [result: NpcActionResult]; interrupt: [reason: string] }>()

const store = useNpcChatStore()
const draft = computed({
  get: () => store.byNpc[props.npc].draft,
  set: (value: string) => { store.byNpc[props.npc].draft = value },
})
const showEarlier = ref(false)
const confirmingKey = ref<string | null>(null)
const actionBusy = ref(false)
const actionFeedback = ref('')
let interrupted = false
let actionGeneration = 0
let confirmTimer: ReturnType<typeof setTimeout> | undefined

const state = computed(() => store.byNpc[props.npc])
const leavingReason = computed(() => {
  const reason = props.leavingReason ?? state.value.leavingReason
  return reason == null ? null : reason.replaceAll('/interrupt', '').trim() || '这次先聊到这里，下次再见。'
})
const leaving = computed(() => leavingReason.value !== null)
const messages = computed(() => state.value.messages)
const loading = computed(() => state.value.loading)
const sending = computed(() => state.value.sending)
const streaming = computed(() => state.value.streaming)
const error = computed(() => state.value.error)

const latestAssistantIndex = computed(() => {
  for (let i = messages.value.length - 1; i >= 0; i--) if (messages.value[i].role === 'ASSISTANT') return i
  return -1
})
const latestAssistant = computed(() => (latestAssistantIndex.value === -1 ? null : messages.value[latestAssistantIndex.value]))
const earlierMessages = computed(() => (latestAssistantIndex.value === -1 ? messages.value : messages.value.slice(0, latestAssistantIndex.value)))
const bodyText = computed(() => {
  const latest = latestAssistant.value
  if (!latest) return props.opener || ''
  if (latest.content) return latest.content
  return latest.pending ? `${props.displayName}在想怎么说…` : props.opener || ''
})
const options = computed<NpcOption[]>(() => (latestAssistant.value?.options ?? []).slice(0, 3))
const actions = computed<NpcAction[]>(() => (latestAssistant.value?.actions ?? []).slice(0, 2))
const portraitLabel = computed(() => (props.npc === 'GUIDE' ? '学院向导' : '街上的邮递员'))

// Once the prose has landed the reply is readable, so the composer reopens even
// though the stream is still waiting on the option tail.
const proseReady = computed(() => Boolean(latestAssistant.value?.pending && latestAssistant.value?.content))
const composerBusy = computed(() => leaving.value || (sending.value && !proseReady.value))

// The model writes its option/action tail after the prose, which can take a few
// seconds of silence; say so instead of leaving a blinking caret on a finished line.
const tailPending = ref(false)
let tailTimer: ReturnType<typeof setTimeout> | undefined
watch([bodyText, streaming], () => {
  clearTimeout(tailTimer)
  tailPending.value = false
  if (streaming.value && bodyText.value) {
    tailTimer = setTimeout(() => { tailPending.value = streaming.value }, 2500)
  }
})

function actionKey(action: NpcAction, index: number) {
  return `${action.type}:${action.scheduleId ?? ''}:${index}`
}

async function send(text?: string) {
  const value = (text ?? draft.value).trim()
  if (!value || composerBusy.value) return
  draft.value = ''
  // The prose is already on screen; the stream may still be waiting on the hidden
  // option tail, and that is not worth making the user sit through.
  if (sending.value) store.abort(props.npc)
  await store.send(props.npc, value)
}

async function trigger(action: NpcAction, index: number) {
  if (leaving.value || actionBusy.value) return
  const npc = props.npc
  const revision = state.value.revision
  const generation = actionGeneration
  const key = actionKey(action, index)
  if (confirmingKey.value !== key) {
    confirmingKey.value = key
    clearTimeout(confirmTimer)
    confirmTimer = setTimeout(() => { if (confirmingKey.value === key) confirmingKey.value = null }, 4000)
    return
  }
  clearTimeout(confirmTimer)
  confirmingKey.value = null
  actionBusy.value = true
  actionFeedback.value = ''
  const result = await store.runAction(action)
  if (generation !== actionGeneration) return
  actionBusy.value = false
  if (props.npc !== npc || state.value.revision !== revision || leaving.value) return
  actionFeedback.value = result.message
  emit('action', result)
}

function cleanup(npc: NpcCode) {
  // Store-backed drafts survive an interrupt followed by automatic unmount.
  if (!interrupted && store.byNpc[npc].leavingReason === null && props.leavingReason == null) store.byNpc[npc].draft = ''
  store.abort(npc)
  ++actionGeneration
  clearTimeout(confirmTimer)
  clearTimeout(tailTimer)
  tailPending.value = false
  confirmingKey.value = null
  actionBusy.value = false
  actionFeedback.value = ''
  showEarlier.value = false
}

let closed = false
function close() {
  cleanup(props.npc)
  closed = true
  emit('close')
}

watch(() => props.npc, (npc, previous) => {
  if (previous) cleanup(previous)
  closed = false
  interrupted = false
  void store.history(npc)
}, { immediate: true })
watch(() => state.value.interruptPending, pending => {
  if (!pending) return
  const reason = store.consumeInterrupt(props.npc)
  if (reason !== null) {
    interrupted = true
    emit('interrupt', reason)
  }
}, { flush: 'sync' })
watch(leaving, value => {
  if (!value) return
  clearTimeout(confirmTimer)
  clearTimeout(tailTimer)
  tailPending.value = false
  confirmingKey.value = null
  // External schedule departures also cancel the live request, without losing the draft.
  if (props.leavingReason != null) store.abort(props.npc)
})
onBeforeUnmount(() => { if (!closed) cleanup(props.npc) })
</script>

<template>
  <section class="npc-dialogue" role="dialog" :aria-label="`与${displayName}对话`">
    <button class="npc-close" type="button" aria-label="关闭对话" @click="close"><X :size="15" /></button>

    <header class="npc-head">
      <span class="npc-portrait" :data-npc="npc" role="img" :aria-label="displayName"></span>
      <div class="npc-nameplate">
        <p class="eyebrow">{{ portraitLabel }}</p>
        <h2>{{ displayName }}</h2>
      </div>
    </header>

    <div class="npc-textbox" :class="{ blocked: latestAssistant?.blocked }" aria-live="polite">
      <p v-if="loading && !messages.length" class="npc-loading">正在回想上次聊到哪里…</p>
      <p v-else>{{ bodyText }}<span v-if="streaming" class="npc-caret" aria-hidden="true"></span></p>
    </div>

    <button v-if="earlierMessages.length" type="button" class="npc-history-toggle" @click="showEarlier = !showEarlier">
      {{ showEarlier ? '收起之前的对话' : `查看更早的对话（${earlierMessages.length}）` }}
    </button>
    <div v-if="showEarlier && earlierMessages.length" class="npc-history">
      <p v-for="(item, index) in earlierMessages" :key="item.publicId || index" class="npc-history-line" :class="item.role.toLowerCase()">
        <strong>{{ item.role === 'USER' ? '我' : displayName }}</strong>{{ item.content }}
      </p>
    </div>

    <p v-if="leaving" class="npc-leaving" role="status">{{ leavingReason }} 已告别，暂时不能继续对话。</p>

    <p v-if="tailPending && !leaving" class="npc-tail-hint" role="status">{{ displayName }}正在挑下一步…</p>

    <div v-if="options.length" class="npc-options">
      <button v-for="(option, index) in options" :key="index" type="button" class="npc-chip" :disabled="composerBusy" @click="send(option.label)">
        {{ option.label }}
      </button>
    </div>

    <div v-if="actions.length" class="npc-actions">
      <button
        v-for="(action, index) in actions"
        :key="actionKey(action, index)"
        type="button"
        class="npc-action-button"
        :class="{ confirming: confirmingKey === actionKey(action, index) }"
        :disabled="actionBusy || leaving"
        @click="trigger(action, index)"
      >
        {{ confirmingKey === actionKey(action, index) ? '再点一次确认' : action.label }}
      </button>
    </div>
    <p v-if="actionFeedback" class="npc-action-feedback" role="status">{{ actionFeedback }}</p>

    <p v-if="error" class="error npc-error" role="alert">{{ error }}</p>

    <TownStories :npc-code="npc" :disabled="composerBusy || actionBusy" />

    <form class="npc-composer" @submit.prevent="send()">
      <label class="sr-only" :for="`npc-input-${npc}`">给{{ displayName }}发消息</label>
      <input
        :id="`npc-input-${npc}`"
        v-model="draft"
        type="text"
        maxlength="500"
        :placeholder="`跟${displayName}说点什么`"
        :disabled="composerBusy"
      >
      <button class="primary npc-send" type="submit" :disabled="composerBusy || !draft.trim()" aria-label="发送">
        <Send :size="16" />
      </button>
    </form>
  </section>
</template>

<style scoped>
.npc-dialogue {
  width: 100%;
  max-width: 340px;
  position: relative;
  display: grid;
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  color: var(--ink);
  box-shadow: var(--shadow-soft);
}
.npc-close {
  position: absolute;
  top: 8px;
  right: 8px;
  width: 26px;
  height: 26px;
  min-height: 0;
  display: grid;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: var(--muted);
}
.npc-close:hover { background: var(--surface-muted); color: var(--ink); }
.npc-head { display: flex; align-items: center; gap: 10px; padding-right: 24px; }
.npc-portrait {
  flex: none;
  width: 64px;
  height: 96px;
  border-radius: calc(var(--radius) - 2px);
  border: 1px solid var(--border);
  background-color: var(--surface-muted);
  background-repeat: no-repeat;
  /* row 0, frame 3 of the 32×64 sheet = facing the viewer; shown at 2× from the shoulders up */
  background-position: -192px -20px;
  background-size: 3708px 2624px;
  image-rendering: pixelated;
}
.npc-portrait[data-npc='GUIDE'] { background-image: url('/assets/town/characters/scout.png'); }
.npc-portrait[data-npc='POSTMAN'] { background-image: url('/assets/town/characters/postman.png'); }
.npc-nameplate { min-width: 0; }
.npc-nameplate h2 { margin: 0; font-size: 17px; line-height: 1.25; }
.npc-nameplate .eyebrow { margin: 0 0 2px; }
.npc-textbox {
  min-height: 72px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: color-mix(in srgb, var(--primary-soft) 55%, var(--surface));
  box-shadow: inset 0 1px 0 rgb(255 255 255 / 40%);
}
.npc-textbox p { margin: 0; font-size: 14px; line-height: 1.65; white-space: pre-wrap; overflow-wrap: anywhere; }
.npc-textbox.blocked { border-color: color-mix(in srgb, var(--danger) 32%, var(--border)); background: color-mix(in srgb, var(--danger) 8%, var(--surface)); }
.npc-loading { color: var(--muted); }
.npc-caret { display: inline-block; width: 2px; height: 14px; margin-left: 2px; vertical-align: -2px; background: var(--primary); animation: npc-blink 1s step-end infinite; }
.npc-history-toggle { justify-self: start; padding: 0; border: 0; background: transparent; color: var(--primary); font-size: 12px; font-weight: 700; min-height: 0; }
.npc-history { max-height: 140px; overflow-y: auto; display: grid; gap: 6px; padding: 8px 10px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface-muted); }
.npc-history-line { margin: 0; font-size: 12px; line-height: 1.55; color: var(--muted); }
.npc-history-line strong { margin-right: 5px; color: var(--ink); }
.npc-history-line.user strong { color: var(--primary); }
.npc-tail-hint { margin: 0; font-size: 12px; color: var(--muted); }
.npc-options, .npc-actions { display: flex; flex-wrap: wrap; gap: 7px; }
.npc-chip { min-height: 30px; padding: 0 12px; border: 1px solid color-mix(in srgb, var(--primary) 30%, var(--border)); border-radius: 999px; background: color-mix(in srgb, var(--primary-soft) 45%, var(--surface)); color: var(--primary-strong); font-size: 12px; font-weight: 700; }
.npc-chip:hover:not(:disabled) { background: var(--primary-soft); }
.npc-action-button { min-height: 32px; padding: 0 12px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); color: var(--ink); font-size: 12px; font-weight: 700; }
.npc-action-button.confirming { border-color: var(--amber); color: var(--amber); background: color-mix(in srgb, var(--amber) 12%, var(--surface)); }
.npc-action-feedback { margin: 0; color: var(--accent-strong); font-size: 12px; line-height: 1.5; }
.npc-error { padding: 8px 10px; font-size: 12px; }
.npc-composer { display: grid; grid-template-columns: minmax(0, 1fr) var(--control); gap: 8px; }
.npc-composer input { min-height: var(--control); border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); color: var(--ink); padding: 0 11px; font-size: 13px; }
.npc-send { padding: 0; width: var(--control); }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; }
@keyframes npc-blink { 0%, 50% { opacity: 1; } 50.01%, 100% { opacity: 0; } }
@media (prefers-reduced-motion: reduce) {
  .npc-caret { animation: none; }
}
@media (max-width: 480px) {
  .npc-dialogue { max-width: none; }
}
</style>
