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
const places: Record<string, string> = { home: '家里', academy: '学院', gym: '健身房', cafe: '咖啡馆', park: '公园', plaza: '广场', street: '街道' }
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
  const current = plan.errands.find(e => e.startMinute <= minute.value && e.endMinute > minute.value)
  const destination = at.kind === 'WALKING' ? at.toPlace : at.place
  const publicPlace = destination !== 'home' && destination !== 'street' ? destination : undefined
  return { code: n.code, name: n.displayName, away, publicPlace,
    visitLabel: publicPlace ? `去${places[publicPlace] ?? publicPlace}` : '',
    where: at.kind === 'WALKING' ? `正在去${places[at.toPlace] ?? at.toPlace}` : `在${places[at.place] ?? at.place}`,
    until: current && current.place !== 'home' ? `计划待到 ${formatMinute(current.endMinute)}` : '',
    next: next ? `下一站 ${formatMinute(next.startMinute)} · ${places[next.place] ?? next.place}` : '今天没有更多外出安排',
    nextPlace: !publicPlace && next && next.place !== 'street' ? next.place : undefined,
    approximate: !n.dayPlan }

}))
const daypart = computed(() => minute.value < 360 || minute.value >= 1260
  ? '夜深了，多数邻居在家休息。你也可以去咖啡馆坐坐，或回家整理今天。'
  : minute.value < 660 ? '晨间的小镇慢慢醒来，看看邻居正在去哪里。'
    : minute.value >= 1020 ? '傍晚有人顺路散步、喝杯咖啡，然后回家休息。'
      : '邻居有自己的日程，也会留时间在家休息。')
const awayCount = computed(() => neighbours.value.filter(n => n.away).length)
</script>

<template>
  <details v-if="neighbours.length" class="neighbour-schedule">
    <summary>邻居的今天 · {{ awayCount }} 位外出 / {{ neighbours.length }} 位邻居</summary>
    <div class="schedule-card">
      <strong>{{ time }} · 小镇日程</strong>
      <p>{{ daypart }}</p>
      <ul><li v-for="n in neighbours" :key="n.code"><strong>{{ n.name }}</strong><span>{{ n.where }}</span><small v-if="n.until">{{ n.until }}</small><small>{{ n.next }}<template v-if="n.approximate">（参考作息）</template></small><div class="actions"><button v-if="n.publicPlace" type="button" @click="visit($event, n.publicPlace)">{{ n.visitLabel }}</button><button v-else-if="n.nextPlace" type="button" @click="visit($event, n.nextPlace)">先去下一站</button><button type="button" @click="visit($event, `neighbour:${n.code}`)">去他家门口</button></div></li></ul>
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
