<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../../shared/api/client'
import { eventTitle, type TownEventView } from './town-events'
const props = defineProps<{ event: TownEventView }>()
const emit = defineEmits<{ visit: [place: string]; updated: [event: TownEventView] }>()
const current = ref(props.event)
const busy = ref(false)
const error = ref('')
let generation = 0
let receivedAt = Date.now()
const elapsed = ref(0)
let ticker: ReturnType<typeof setInterval> | undefined
onMounted(() => { ticker = setInterval(() => { elapsed.value = Date.now() - receivedAt }, 1000) })
watch(() => props.event, value => { generation++; current.value = value; receivedAt = Date.now(); elapsed.value = 0; busy.value = false; error.value = '' })
onBeforeUnmount(() => { generation++; clearInterval(ticker) })
const places: Record<string, string> = { plaza: '日光广场', park: '树荫公园', cafe: '街角咖啡馆' }
const phase = computed(() => {
  const event = current.value
  if (event.phase === 'CANCELLED' || !event.serverTime) return event.phase ?? 'UNKNOWN'
  const now = Date.parse(event.serverTime) + elapsed.value
  const start = Date.parse(event.startsAt)
  const end = event.endsAt ? Date.parse(event.endsAt) : start + 2 * 60 * 60 * 1000
  if (![now, start, end].every(Number.isFinite)) return event.phase ?? 'UNKNOWN'
  return now < start ? 'UPCOMING' : now < end ? 'ONGOING' : 'ENDED'
})
const phaseText = computed(() => ({ UPCOMING: '尚未开始', ONGOING: '正在相聚', ENDED: '已经结束', CANCELLED: '已取消', UNKNOWN: '时间待确认' })[phase.value])
const canRespond = computed(() => phase.value === 'UPCOMING' || phase.value === 'ONGOING')
function time(value: string) {
  // The offset supplied by the town preserves its calendar time on another device/timezone.
  const match = value.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/)
  return match ? `${Number(match[2])}月${Number(match[3])}日 ${match[4]}:${match[5]}` : '时间待确认'
}
async function save(action: 'response' | 'memory', response?: string) {
  if (busy.value) return
  busy.value = true; error.value = ''
  const token = ++generation
  try {
    const updated = await api.post<TownEventView>(`/town/events/${encodeURIComponent(current.value.publicId)}/${action}`, action === 'response' ? { response } : undefined)
    if (token !== generation) return
    current.value = updated; receivedAt = Date.now(); elapsed.value = 0; emit('updated', updated)
  } catch {
    if (token === generation) error.value = '保存结果暂未确认，请刷新活动查看后再试。'
  } finally { if (token === generation) busy.value = false }
}
</script>
<template>
  <article class="event-card">
    <header><strong>{{ eventTitle(current.kind) }}</strong><span>{{ phaseText }}</span></header>
    <p>{{ current.hostName }} · {{ places[current.venue] ?? '镇上' }}</p>
    <p class="event-time">{{ time(current.startsAt) }}<template v-if="current.endsAt"> — {{ time(current.endsAt) }}</template> · 小镇时间</p>
    <p v-if="current.attendedAt" class="event-memory">你记下了这次相聚。以后回来，也能在这里找到这段经历。</p>
    <p v-else-if="phase === 'ENDED'">这场相聚已经结束。没有留下参加记录也没关系，下次路过再坐坐。</p>
    <p v-else-if="phase === 'CANCELLED'">这次安排取消了，按自己的节奏过今天吧。</p>
    <p v-else>{{ current.response === 'GOING' ? '已记下：想去坐坐。临时改变主意也可以。' : current.response === 'SKIPPED' ? '这次先略过，不影响下次相遇。' : '看心情决定，不打卡，也不计分。' }}</p>
    <div class="event-actions">
      <template v-if="canRespond && !current.attendedAt">
        <button type="button" :disabled="busy" :aria-pressed="current.response === 'GOING'" @click="save('response', 'GOING')">想去坐坐</button>
        <button type="button" :disabled="busy" :aria-pressed="current.response === 'SKIPPED'" @click="save('response', 'SKIPPED')">这次略过</button>
        <button v-if="current.response && current.response !== 'UNDECIDED'" type="button" :disabled="busy" @click="save('response', 'UNDECIDED')">稍后决定</button>
      </template>
      <button v-if="canRespond || phase === 'UNKNOWN'" type="button" @click="emit('visit', current.venue)">去这里走走</button>
      <button v-if="phase === 'ONGOING' && !current.attendedAt" type="button" :disabled="busy" @click="save('memory')">我参加了，留下回忆</button>
    </div>
    <p v-if="error" role="alert">{{ error }}</p>
  </article>
</template>
<style scoped>
.event-card { padding: 14px; border: 1px solid var(--border); border-radius: 12px; background: var(--surface); color: var(--ink); }
header { display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; } header span,.event-time { color: var(--muted); font-size: 12px; }
p { font-size: 13px; line-height: 1.7; margin: 8px 0; }.event-memory { color: var(--primary-strong); }
.event-actions { display: flex; flex-wrap: wrap; gap: 8px; }button { min-height: 40px; padding: 8px 10px; border: 1px solid var(--border); border-radius: 8px; color: inherit; background: var(--surface-muted); }button[aria-pressed=true] { border-color: var(--primary); }button:disabled { opacity: .55; }
[role=alert] { color: var(--danger); }
</style>
