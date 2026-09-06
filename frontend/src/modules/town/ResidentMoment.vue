<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { MessageCircle, X } from 'lucide-vue-next'
import { useTownNpcStore } from './town-npc.store'
import type { NpcTalkingPoint, TownNpcView } from './town-npc.types'
import { api } from '../../shared/api/client'
import { minuteInZone, npcWhereabouts } from './world-life'
import { useTownStore } from './town.store'

const props = defineProps<{ npc: TownNpcView }>()
const emit = defineEmits<{ close: [] }>()
const store = useTownNpcStore()
const townStore = useTownStore()
const townZone = computed(() => townStore.model?.residents.find(item => item.isSelf)?.timezone || 'Asia/Shanghai')
const points = ref<NpcTalkingPoint[]>([])
const index = ref(0)
const loading = ref(false)
const error = ref('')
type TellKind = 'RHYTHM' | 'DIMENSION_FOCUS' | 'STREAK_HINT' | 'LEVEL_BUCKET'
const tellKinds: { kind: TellKind; label: string }[] = [
  { kind: 'RHYTHM', label: '最近的行动节奏' },
  { kind: 'DIMENSION_FOCUS', label: '主要投入的成长方向' },
  { kind: 'STREAK_HINT', label: '坚持记录的大致情况' },
  { kind: 'LEVEL_BUCKET', label: '目前的成长阶段' },
]
const tellExpanded = ref(false)
const tellKind = ref<TellKind | ''>('')
const tellBusy = ref(false)
const tellError = ref('')
const tellSuccess = ref('')
let tellRequest = 0
function chooseTellKind() { tellError.value = ''; tellSuccess.value = '' }
async function tell() {
  if (props.npc.layer !== 2 || !tellExpanded.value || tellBusy.value || tellSuccess.value
    || !tellKinds.some(item => item.kind === tellKind.value)) return
  const sequence = ++tellRequest
  const { code, displayName } = props.npc
  tellBusy.value = true
  tellError.value = ''
  try {
    await api.post<void>(`/town/npc/${encodeURIComponent(code)}/tell`, { kind: tellKind.value })
    if (sequence === tellRequest) tellSuccess.value = `已告诉${displayName}这类近况。对方之后可能会转述给其他居民。`
  } catch {
    if (sequence === tellRequest) tellError.value = '暂时没能告诉对方，请稍后重试。'
  } finally {
    if (sequence === tellRequest) tellBusy.value = false
  }
}
watch(() => [props.npc.code, props.npc.layer], () => {
  tellRequest++
  tellExpanded.value = false
  tellKind.value = ''
  tellBusy.value = false
  tellError.value = ''
  tellSuccess.value = ''
})
const now = ref(new Date())
let request = 0
const timer = setInterval(() => { now.value = new Date() }, 30_000)
onBeforeUnmount(() => { tellRequest++; request++; clearInterval(timer) })
const serverOffset = computed(() => { const timestamp = Date.parse(townStore.model?.serverTime ?? ''); return Number.isFinite(timestamp) ? timestamp - Date.now() : 0 })
const whereabouts = computed(() => npcWhereabouts(props.npc, minuteInZone(now.value.getTime() + serverOffset.value, townZone.value)))
const point = computed(() => points.value[index.value])
async function listen() {
  const sequence = ++request
  const code = props.npc.code
  loading.value = true
  error.value = ''
  points.value = []
  index.value = 0
  try {
    const result = await store.talkingPoints(code)
    if (sequence === request) points.value = result
  } catch {
    if (sequence === request) error.value = '风声有些大，没听清。稍后再听听吧。'
  } finally {
    if (sequence === request) loading.value = false
  }
}
watch(() => props.npc.code, () => { void listen() }, { immediate: true })
</script>

<template>
  <section class="resident-moment" aria-label="路边闲聊" @pointerdown.stop @keydown.esc.stop="emit('close')" @keydown.stop>
    <header>
      <div><span class="moment-kicker">偶遇 · 镇上的熟面孔</span><h2><MessageCircle :size="18" />{{ npc.displayName }}</h2></div>
      <button type="button" aria-label="结束闲聊" @click="emit('close')"><X :size="18" /></button>
    </header>
    <p class="moment-where">{{ whereabouts }}</p>
    <div class="moment-words" aria-live="polite" aria-atomic="true">
      <p v-if="loading">{{ npc.displayName }}转过身来，想了想…</p>
      <p v-else-if="error">{{ error }}</p>
      <template v-else-if="point">
        <p>“{{ point.text }}”</p>
        <small>{{ point.hops > 0 ? '是辗转听来的，未必就是原来的样子。' : '这是对方自己知道的一点近况。' }}</small>
      </template>
      <p v-else>“这会儿没什么新鲜事。你慢慢逛，我也继续忙啦。”</p>
    </div>
    <div v-if="npc.layer === 2" class="moment-tell">
      <button type="button" :aria-expanded="tellExpanded" @click="tellExpanded = !tellExpanded">{{ tellExpanded ? '收起近况分享' : '主动聊聊我的近况' }}</button>
      <form v-if="tellExpanded" :aria-busy="tellBusy" @submit.prevent="tell">
        <p class="tell-notice">自愿分享：这类近况可能被转述给其他居民。只会分享大致情况，不包含具体任务标题、数字或私人原文。</p>
        <fieldset :disabled="tellBusy">
          <legend>想告诉{{ npc.displayName }}哪类近况？</legend>
          <label v-for="item in tellKinds" :key="item.kind"><input v-model="tellKind" type="radio" name="tell-kind" :value="item.kind" @change="chooseTellKind" />{{ item.label }}</label>
        </fieldset>
        <button type="submit" class="moment-leave" :disabled="!tellKind || tellBusy || !!tellSuccess">{{ tellBusy ? '正在告诉对方…' : tellSuccess ? '已告知' : tellError ? '重试告知' : '确认告诉对方' }}</button>
        <p v-if="tellBusy" role="status">正在分享这类近况…</p>
        <p v-if="tellError" role="alert">{{ tellError }}</p>
        <p v-if="tellSuccess" role="status">{{ tellSuccess }}</p>
      </form>
    </div>
    <footer>
      <button v-if="error" type="button" @click="listen">再听一次</button>
      <button v-else-if="index + 1 < points.length" type="button" @click="index++">再聊一句</button>
      <span v-else>短短相遇，也算打过照面。</span>
      <button type="button" class="moment-leave" @click="emit('close')">挥手告别</button>
    </footer>
  </section>
</template>

<style scoped>
.resident-moment { width: 360px; max-width: 100%; min-width: 0; padding: 22px; box-sizing: border-box; border: 1px solid #dccdb5; border-radius: 18px; background: #fff9ee; color: #34483b; box-shadow: 0 16px 50px #142b3433; }
header, h2, footer { display: flex; align-items: center; }
header > div { min-width: 0; overflow-wrap: anywhere; }
footer { flex-wrap: wrap; }
header, footer { justify-content: space-between; gap: 12px; }
h2 { gap: 8px; margin: 7px 0 0; font-size: 20px; }
.moment-kicker { font-size: 10px; letter-spacing: .14em; color: #796e5e; }
.moment-where { color: #776f61; font-size: 12px; margin: 12px 0 18px; }
.moment-words { border-block: 1px solid #e8dfcf; padding: 15px 0; min-height: 85px; }
.moment-words p { margin: 0; line-height: 1.9; font-size: 15px; overflow-wrap: anywhere; }
.moment-words small { display: block; margin-top: 12px; color: #796e5e; line-height: 1.6; }
footer { margin-top: 18px; font-size: 11px; color: #796e5e; }
button { border: 0; background: transparent; color: #355b44; padding: 9px; border-radius: 8px; cursor: pointer; flex-shrink: 0; }
button:hover { background: #e9ebda; }
button:focus-visible { outline: 2px solid #355b44; outline-offset: 2px; }
.moment-leave { background: #e9ebda; }
.moment-tell { margin-top: 16px; font-size: 13px; line-height: 1.65; }
.moment-tell p { margin: 8px 0; overflow-wrap: anywhere; }
.tell-notice { color: #796e5e; }
.moment-tell fieldset { display: grid; gap: 8px; min-width: 0; margin: 12px 0; padding: 0; border: 0; }
.moment-tell legend { margin-bottom: 8px; font-weight: 600; }
.moment-tell label { display: flex; align-items: baseline; gap: 8px; cursor: pointer; }
.moment-tell input { width: auto; margin: 0; flex: 0 0 auto; accent-color: #355b44; }
button:disabled { opacity: .6; cursor: default; }
</style>
