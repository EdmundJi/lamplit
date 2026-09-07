<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { CalendarDays, X } from 'lucide-vue-next'
import { api } from '../../shared/api/client'
import { useTownEventsStore, type TownEventView } from './town-events'
import TownEventCard from './TownEventCard.vue'
const emit = defineEmits<{ close: []; visit: [venue: string] }>()
const store = useTownEventsStore()
const tab = ref<'today' | 'history'>('today')
const history = ref<TownEventView[]>([])
const historyLoading = ref(false)
const historyError = ref('')
let generation = 0
async function loadHistory() {
  tab.value = 'history'; historyLoading.value = true; historyError.value = ''
  const token = ++generation
  try { const result = await api.get<TownEventView[]>('/town/events/history'); if (token === generation) history.value = result }
  catch { if (token === generation) historyError.value = '暂时没能取出过去的活动，请再试一次。' }
  finally { if (token === generation) historyLoading.value = false }
}
function update(event: TownEventView) { store.events = store.events.map(item => item.publicId === event.publicId ? event : item) }
onMounted(() => { void store.load() })
onBeforeUnmount(() => { generation++ })
</script>
<template>
  <section class="town-events-board" aria-label="小镇活动公告" @pointerdown.stop @keydown.esc.stop="emit('close')">
    <header><h2><CalendarDays :size="18" />镇上的邀约</h2><button type="button" aria-label="关闭活动公告" @click="emit('close')"><X :size="18" /></button></header>
    <p class="intro">相聚只为聊聊，不打卡，也不计分。</p>
    <nav aria-label="活动时间"><button type="button" :aria-pressed="tab === 'today'" @click="tab = 'today'; store.load()">今天的安排</button><button type="button" :aria-pressed="tab === 'history'" @click="loadHistory">过去七天</button></nav>
    <template v-if="tab === 'today'">
      <p v-if="store.loading" role="status">正在看看公告栏…</p>
      <div v-else-if="store.error" role="alert"><p>{{ store.error }}</p><button type="button" @click="store.load()">重试</button></div>
      <p v-else-if="!store.events.length">今天没有安排聚会。居民各自忙着，也欢迎随时在路上打个招呼。</p>
      <div v-else class="event-list"><TownEventCard v-for="event in store.events" :key="event.publicId" :event="event" @updated="update" @visit="emit('visit', $event)" /></div>
    </template>
    <template v-else>
      <p v-if="historyLoading" role="status">正在翻看过去的安排…</p>
      <p v-else-if="historyError" role="alert">{{ historyError }} <button type="button" @click="loadHistory">重试</button></p>
      <p v-else-if="!history.length">过去七天没有活动记录，新的相遇以后会留在这里。</p>
      <div v-else class="event-list"><TownEventCard v-for="event in history" :key="event.publicId" :event="event" @visit="emit('visit', $event)" /></div>
    </template>
  </section>
</template>
<style scoped>
.town-events-board { box-sizing: border-box; width: 100%; max-width: 420px; max-height: 75dvh; overflow-y: auto; padding: 20px; background: var(--surface, #fff9ee); color: var(--ink, #34483b); border: 1px solid var(--border, #dccdb5); border-radius: 18px; box-shadow: var(--shadow); }
header,h2,nav { display: flex; align-items: center; gap: 8px; } header { justify-content: space-between; } h2 { margin: 0; font-size: 18px; } button { padding: 8px 12px; border-radius: 8px; background: var(--surface-muted); color: inherit; border: 1px solid var(--border); cursor: pointer; }p { font-size: 13px; line-height: 1.7; }.intro { color: var(--muted); }.event-list { display: grid; gap: 12px; margin-top: 12px; }button[aria-pressed=true] { border-color: var(--primary); }
</style>
