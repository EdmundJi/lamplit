<script setup lang="ts">
import { computed } from 'vue'
import { positionAt, dayPlanFallback } from '../day-plan'
import type { TownNpcView } from '../town-npc.types'

const emit = defineEmits<{ visit: [place: string] }>()
function visit(event: Event, place: string) {
  (event.target as HTMLElement).closest('details')?.removeAttribute('open')
  emit('visit', place)
}
const props = defineProps<{ npcs: TownNpcView[]; time: string }>()
const places: Record<string, string> = { home: '家里', academy: '学院', gym: '健身房', cafe: '咖啡馆', park: '公园', plaza: '广场' }
const minute = computed(() => {
  const [h, m] = props.time.split(':').map(Number)
  return (h || 0) * 60 + (m || 0)
})
function formatMinute(value: number) { return `${String(Math.floor(value / 60)).padStart(2, '0')}:${String(value % 60).padStart(2, '0')}` }
const neighbours = computed(() => props.npcs.filter(n => n.layer !== 1).map(n => {
  const plan = n.dayPlan ?? dayPlanFallback(n.schedule)
  const at = positionAt(plan, minute.value)
  const away = at.kind === 'WALKING' || at.place !== 'home'
  const next = plan.errands.find(e => e.place !== 'home' && e.startMinute > minute.value)
  return { code: n.code, name: n.displayName, away,
    where: at.kind === 'WALKING' ? `正在去${places[at.toPlace] ?? at.toPlace}` : `在${places[at.place] ?? at.place}`,
    next: next ? `${formatMinute(next.startMinute)} · ${places[next.place] ?? next.place}` : '今天没有更多外出安排' }
}))
const awayCount = computed(() => neighbours.value.filter(n => n.away).length)
</script>

<template>
  <details v-if="neighbours.length" class="neighbour-schedule">
    <summary>邻居的今天 · {{ awayCount }} 位外出 / {{ neighbours.length }} 位邻居</summary>
    <div class="schedule-card">
      <strong>{{ time }} · 小镇日程</strong>
      <p>小助和邮递员在街上陪你。在家的邻居会按自己的日程出门。</p>
      <ul><li v-for="n in neighbours" :key="n.code"><strong>{{ n.name }}</strong><span>{{ n.where }}</span><small>{{ n.next }}</small><div class="actions"><button type="button" @click="visit($event, `neighbour:${n.code}`)">去他家门口</button></div></li></ul>
    </div>
  </details>
</template>

<style scoped>
.neighbour-schedule { position: relative; font-size: 12px; color: var(--ink); }
summary { cursor: pointer; padding: 8px 10px; border-radius: 8px; background: var(--surface); }
.schedule-card { position: absolute; top: calc(100% + 8px); left: 0; width: min(330px, 85vw); padding: 16px; border: 1px solid var(--border); border-radius: 12px; background: var(--surface); box-shadow: 0 8px 32px #0002; }
p, small { color: var(--muted); line-height: 1.5; }
ul { list-style: none; padding: 0; margin: 0; max-height: 48vh; overflow: auto; }
li { display: grid; grid-template-columns: 1fr 1fr; gap: 5px; padding: 10px 0; border-bottom: 1px solid var(--border); }
.actions { grid-column:1 / -1;display:flex;gap:8px; } .actions button { cursor:pointer;padding:6px 10px;border:1px solid var(--border);border-radius:6px;background:var(--surface);color:var(--ink); }
small { grid-column: 1 / -1; }
</style>
