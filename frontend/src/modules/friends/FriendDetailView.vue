<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { init, use, type ECharts } from 'echarts/core'
import { RadarChart } from 'echarts/charts'
import { LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { ArrowLeft, BadgeCheck, CalendarCheck2, PawPrint, UserRound } from 'lucide-vue-next'
import { api } from '../../shared/api/client'
import { computeBadges, type Badge } from '../insights/badges'
import RivePet from '../partners/RivePet.vue'
import type { FriendProfile } from './friends.types'

use([RadarChart, LegendComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{ publicId: string }>()

const chartElement = ref<HTMLElement | null>(null)
const profile = ref<FriendProfile | null>(null)
const loading = ref(true)
const error = ref('')
let chart: ECharts | undefined

const initial = computed(() => {
  const characters = Array.from(profile.value?.displayName.trim() ?? '')
  if (!characters.length) return '好'
  if (/\p{Script=Han}/u.test(characters[0])) return characters[0]
  return characters[0].toUpperCase()
})

const doneCount = computed(() => profile.value?.todayTasks.filter(task => task.status === 'DONE').length ?? 0)
const badges = computed<Badge[]>(() => {
  const data = profile.value
  if (!data) return []
  return computeBadges({
    effectiveActions: data.overview.effectiveActions,
    fulfillmentRate: data.overview.fulfillmentRate,
    recoveryCount: data.overview.recoveryCount,
    totalExperience: data.overview.totalExperience,
    longestStreak: data.longestStreak,
    roles: data.roles,
  })
})
const earnedBadgeCount = computed(() => badges.value.filter(badge => badge.earned).length)
const petProgress = computed(() => {
  const pet = profile.value?.pet
  if (!pet) return 0
  return Math.min(100, Math.round(pet.affection * 100 / Math.max(1, pet.nextLevelAffection)))
})
const memberSinceLabel = computed(() => profile.value
  ? new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' }).format(new Date(`${profile.value.memberSince}T00:00:00`))
  : '')

function taskTime(value: string) {
  return new Date(value).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

function renderChart() {
  if (!chartElement.value || !profile.value) return
  chart?.dispose()
  chart = init(chartElement.value)
  const styles = getComputedStyle(document.documentElement)
  const ink = styles.getPropertyValue('--ink').trim() || '#3b312c'
  const muted = styles.getPropertyValue('--muted').trim() || '#74675f'
  const border = styles.getPropertyValue('--border').trim() || '#e4d5c7'
  const surface = styles.getPropertyValue('--surface').trim() || '#fffdfa'
  const primary = styles.getPropertyValue('--primary').trim() || '#c85f47'
  chart.setOption({
    animationDuration: 720,
    animationEasing: 'cubicOut',
    tooltip: {
      trigger: 'item',
      backgroundColor: surface,
      borderColor: border,
      textStyle: { color: ink },
      formatter: () => profile.value?.attributes.map(item => `${item.name}：${item.radarScore}`).join('<br>') ?? '',
    },
    radar: {
      radius: '67%',
      splitNumber: 4,
      indicator: profile.value.attributes.map(item => ({ name: item.name, max: 100 })),
      axisName: { color: ink, fontSize: 13, fontWeight: 700 },
      splitLine: { lineStyle: { color: [border] } },
      splitArea: { areaStyle: { color: ['rgba(200,95,71,.035)', 'rgba(79,133,106,.045)'] } },
      axisLine: { lineStyle: { color: border } },
    },
    series: [{
      type: 'radar',
      symbol: 'circle',
      symbolSize: 7,
      lineStyle: { color: primary, width: 2 },
      itemStyle: { color: primary, borderColor: surface, borderWidth: 2 },
      areaStyle: { color: primary, opacity: .18 },
      data: [{ name: '成长指数', value: profile.value.attributes.map(item => item.radarScore) }],
    }],
    textStyle: { color: muted },
  })
}

function resizeChart() {
  chart?.resize()
}

onMounted(async () => {
  try {
    profile.value = await api.get<FriendProfile>(`/friends/${props.publicId}`)
    loading.value = false
    await nextTick()
    renderChart()
    window.addEventListener('resize', resizeChart)
  } catch {
    error.value = '只能查看好友的资料，或者对方已删除好友关系'
  } finally {
    loading.value = false
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeChart)
  chart?.dispose()
})
</script>

<template>
  <section class="page friend-page">
    <header class="page-head friend-head">
      <RouterLink class="back-link" to="/friends"><ArrowLeft :size="17" />返回好友</RouterLink>
      <div v-if="profile" class="friend-identity">
        <span class="friend-avatar" aria-hidden="true">{{ initial }}</span>
        <div>
          <p class="eyebrow">LV.{{ profile.overallLevel }} 成长者 · {{ memberSinceLabel }}加入</p>
          <h1>{{ profile.displayName }}</h1>
        </div>
      </div>
      <div v-else class="friend-identity">
        <span class="friend-avatar" aria-hidden="true"><UserRound :size="22" /></span>
        <div><p class="eyebrow">好友资料</p><h1>{{ loading ? '正在加载…' : '好友' }}</h1></div>
      </div>
    </header>

    <p v-if="error" class="error" role="alert">{{ error }}<br /><RouterLink to="/friends">回到好友列表</RouterLink></p>
    <div v-else-if="loading" class="loading-state" role="status">正在整理对方的成长轨迹…</div>
    <template v-else-if="profile">
      <div class="metrics">
        <div><strong>{{ profile.overview.effectiveActions }}</strong><span>本周有效行动</span></div>
        <div><strong>{{ Math.round(profile.overview.fulfillmentRate * 100) }}%</strong><span>本周兑现率</span></div>
        <div><strong>{{ profile.overview.recoveryCount }}</strong><span>恢复次数</span></div>
        <div><strong>{{ profile.overview.totalExperience }}</strong><span>累计行动经验</span></div>
      </div>

      <section class="band today-band" aria-labelledby="friend-today-title">
        <div class="section-title">
          <div><p class="eyebrow">今天</p><h2 id="friend-today-title">今日完成情况</h2></div>
          <span class="section-count">{{ doneCount }} / {{ profile.todayTasks.length }} 已完成</span>
        </div>
        <div v-if="profile.todayTasks.length" class="task-list">
          <article v-for="task in profile.todayTasks" :key="task.publicId" class="task-row">
            <div>
              <span class="status">{{ task.status }}<template v-if="task.roleName"> · {{ task.roleName }}</template></span>
              <h3>{{ task.title }}</h3>
              <time>{{ taskTime(task.plannedStartAt) }}</time>
            </div>
            <span class="task-state" :class="{ done: task.status === 'DONE' }">{{ task.status === 'DONE' ? '已完成' : '待完成' }}</span>
          </article>
        </div>
        <div v-else class="empty"><CalendarCheck2 :size="26" /><h3>今天还没有安排</h3><p>对方今天的日程还是空的。</p></div>
      </section>

      <section class="band attribute-band" aria-labelledby="friend-radar-title">
        <div class="attribute-overview">
          <div class="radar-copy">
            <p class="eyebrow">成长雷达</p>
            <h2 id="friend-radar-title">对方行动的轮廓</h2>
            <p>五维成长指数只反映行动积累，不构成任何能力测评。</p>
            <dl>
              <div><dt>累计属性经验</dt><dd>{{ profile.totalExperience }}</dd></div>
              <div><dt>连续行动最长</dt><dd>{{ profile.longestStreak }} 天</dd></div>
            </dl>
          </div>
          <div ref="chartElement" class="radar-chart" role="img" :aria-label="`好友成长属性雷达图：${profile.attributes.map(item => `${item.name}${item.radarScore}`).join('，')}`"></div>
        </div>
      </section>

      <section v-if="profile.pet" class="band pet-band" aria-labelledby="friend-pet-title">
        <div class="section-title">
          <div><p class="eyebrow">当前伙伴</p><h2 id="friend-pet-title">{{ profile.pet.name }}正在陪着ta</h2></div>
          <PawPrint :size="21" />
        </div>
        <div class="pet-stage">
          <RivePet :species-code="profile.pet.speciesCode" :name="profile.pet.name" :disabled="true" />
        </div>
        <div class="pet-details">
          <div><strong>{{ profile.pet.name }}</strong><span>{{ profile.pet.speciesName }} · {{ profile.pet.breed }} · {{ profile.pet.furColor }}</span></div>
          <b>LV.{{ profile.pet.level }}</b>
        </div>
        <div class="pet-affection"><div><span>好感度</span><strong>{{ profile.pet.affection }} / {{ profile.pet.nextLevelAffection }}</strong></div><div class="progress"><span :style="{ width: `${petProgress}%` }"></span></div></div>
      </section>

      <section class="band badge-section" aria-labelledby="friend-badge-title">
        <div class="section-title">
          <div><p class="eyebrow">个人徽章</p><h2 id="friend-badge-title">对方获得的里程碑</h2></div>
          <div class="badge-summary"><BadgeCheck :size="20" /><span>{{ earnedBadgeCount }} / {{ badges.length }}</span></div>
        </div>
        <div class="badge-grid">
          <article v-for="badge in badges" :key="badge.title" class="badge-card" :class="[badge.tone, { earned: badge.earned }]">
            <div class="badge-icon" aria-hidden="true"><component :is="badge.icon" :size="22" /></div>
            <div class="badge-copy">
              <div class="badge-title-line"><strong>{{ badge.title }}</strong><span>{{ badge.earned ? '已获得' : '未获得' }}</span></div>
              <p>{{ badge.body }}</p>
              <small>触发条件：{{ badge.trigger }}</small>
            </div>
          </article>
        </div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.friend-head { display: flex; flex-direction: column; align-items: flex-start; gap: 18px; }
.back-link { display: inline-flex; align-items: center; gap: 6px; color: var(--muted); text-decoration: none; font-size: 13px; font-weight: 700; }
.back-link:hover { color: var(--primary); }
.friend-identity { display: flex; align-items: center; gap: 15px; }
.friend-avatar { width: 64px; height: 64px; display: grid; place-items: center; border-radius: 21px 21px 21px 7px; background: linear-gradient(145deg, var(--primary), color-mix(in srgb, var(--primary) 72%, var(--amber))); color: white; font-size: 27px; font-weight: 900; box-shadow: 0 12px 26px color-mix(in srgb, var(--primary) 22%, transparent); }
.friend-identity h1 { margin: 4px 0 0; }
.loading-state { min-height: 420px; display: grid; place-items: center; color: var(--muted); }
.metrics { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 24px; }
.metrics div { min-height: 104px; display: grid; align-content: end; gap: 8px; padding: 16px; border: 1px solid var(--border); border-radius: var(--radius); background: color-mix(in srgb, var(--surface) 88%, transparent); box-shadow: var(--shadow-soft); }
.metrics strong { display: block; font-size: 30px; line-height: 1; color: var(--primary); }
.metrics span { font-size: 13px; color: var(--muted); }
.section-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; color: var(--primary); }
.section-title h2 { margin: 0; font-size: 18px; color: var(--ink); }
.section-count { padding: 3px 9px; border-radius: 999px; background: var(--surface-muted); color: var(--muted); font-size: 12px; font-weight: 800; }
.task-list { display: grid; gap: 9px; }
.task-row { min-height: 74px; display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 13px 15px; border: 1px solid var(--border); border-radius: var(--radius); background: color-mix(in srgb, var(--surface) 90%, transparent); box-shadow: var(--shadow-soft); }
.task-row > div { min-width: 0; display: grid; gap: 5px; }
.task-row .status { font-size: 12px; font-weight: 700; color: var(--primary); }
.task-row h3 { margin: 0; font-size: 15px; line-height: 1.4; overflow-wrap: anywhere; }
.task-row time { color: var(--muted); font-size: 12px; }
.task-state { flex: none; padding: 4px 10px; border-radius: 999px; background: var(--surface-muted); color: var(--muted); font-size: 12px; font-weight: 700; }
.task-state.done { background: color-mix(in srgb, var(--accent) 14%, var(--surface)); color: var(--accent); }
.empty { border: 1px dashed var(--border); border-radius: var(--radius); padding: 34px 20px; display: grid; place-items: center; justify-items: center; gap: 7px; color: var(--muted); text-align: center; }
.empty h3 { margin: 0; color: var(--ink); font-size: 16px; }
.empty p { margin: 0; font-size: 13px; }
.empty svg { color: var(--primary); }
.attribute-overview { min-height: 330px; display: grid; grid-template-columns: minmax(240px, .72fr) minmax(360px, 1fr); align-items: center; gap: 20px; padding: 20px 0 24px; }
.radar-copy h2 { margin: 0; font-size: 24px; }
.radar-copy > p:not(.eyebrow) { margin: 12px 0 20px; color: var(--muted); line-height: 1.75; }
.radar-copy dl { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; margin: 0; }
.radar-copy dl div { padding: 13px; border-left: 3px solid var(--primary); background: color-mix(in srgb, var(--primary-soft) 45%, var(--surface)); }
.radar-copy dt { color: var(--muted); font-size: 12px; }
.radar-copy dd { margin: 5px 0 0; color: var(--ink); font-size: 18px; font-weight: 800; }
.radar-chart { width: 100%; min-height: 320px; }
.pet-stage { position: relative; height: 250px; display: grid; place-items: center; border: 1px solid color-mix(in srgb, var(--accent) 25%, var(--border)); border-radius: 68px 68px 8px 8px; background: linear-gradient(180deg, color-mix(in srgb, var(--accent) 10%, var(--surface)), color-mix(in srgb, var(--amber) 9%, var(--surface))); overflow: hidden; }
.pet-stage :deep(.rive-pet) { position: absolute; inset: 0; width: min(100%, 310px); height: 230px; margin: auto; }
.pet-details { display: flex; justify-content: space-between; align-items: start; gap: 12px; padding: 13px 2px 8px; }
.pet-details > div { display: grid; gap: 3px; }
.pet-details span { color: var(--muted); font-size: 12px; }
.pet-details b { color: var(--primary); }
.pet-affection { display: grid; gap: 7px; padding: 8px 2px 14px; }
.pet-affection > div:first-child { display: flex; justify-content: space-between; color: var(--muted); font-size: 12px; }
.pet-affection strong { color: var(--ink); }
.badge-summary { display: inline-flex; align-items: center; gap: 8px; color: var(--primary); font-weight: 700; }
.badge-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.badge-card { min-width: 0; min-height: 172px; display: grid; grid-template-rows: auto 1fr; gap: 12px; padding: 15px; border: 1px solid var(--border); border-radius: var(--radius); background: color-mix(in srgb, var(--surface) 92%, transparent); color: var(--muted); opacity: .72; }
.badge-card.earned { color: var(--ink); opacity: 1; border-color: color-mix(in srgb, var(--badge-color) 42%, var(--border)); background: linear-gradient(145deg, color-mix(in srgb, var(--badge-color) 10%, var(--surface)), var(--surface)); box-shadow: var(--shadow-soft); }
.badge-card.green { --badge-color: var(--primary); }
.badge-card.blue { --badge-color: #2a6b80; }
.badge-card.amber { --badge-color: var(--amber); }
.badge-card.violet { --badge-color: #70517a; }
.badge-icon { width: 44px; height: 44px; display: grid; place-items: center; border: 1px solid color-mix(in srgb, var(--badge-color) 32%, var(--border)); border-radius: 14px; color: var(--badge-color); background: var(--surface); box-shadow: inset 0 -10px 18px color-mix(in srgb, var(--badge-color) 7%, transparent); }
.badge-card:not(.earned) .badge-icon { color: var(--muted); border-color: var(--border); background: var(--surface-muted); }
.badge-copy { display: grid; gap: 7px; align-content: start; min-width: 0; }
.badge-title-line { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.badge-title-line strong { min-width: 0; font-size: 15px; line-height: 1.35; color: var(--ink); }
.badge-title-line span { flex: none; padding: 2px 6px; border: 1px solid var(--border); border-radius: 999px; color: var(--muted); font-size: 11px; font-weight: 700; }
.badge-card.earned .badge-title-line span { border-color: color-mix(in srgb, var(--badge-color) 36%, var(--border)); color: var(--badge-color); background: color-mix(in srgb, var(--badge-color) 8%, var(--surface)); }
.badge-card p { margin: 0; color: var(--muted); font-size: 12px; line-height: 1.5; }
.badge-card small { color: var(--muted); font-size: 11px; line-height: 1.45; }
@media (prefers-reduced-motion: no-preference) {
  .metrics div, .friend-card, .task-row, .badge-card { animation: friend-enter var(--motion-medium) ease-out both; }
  .badge-card, .task-row { transition: transform var(--motion-fast) ease, box-shadow var(--motion-fast) ease; }
  .badge-card:hover, .task-row:hover { transform: translateY(-2px); box-shadow: var(--shadow); }
}
@keyframes friend-enter { from { opacity: 0; transform: translateY(7px); } to { opacity: 1; transform: translateY(0); } }
@media (max-width: 850px) { .attribute-overview { grid-template-columns: 1fr; } .radar-copy { max-width: none; } .radar-chart { min-height: 300px; } }
@media (max-width: 820px) { .badge-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 600px) { .metrics { grid-template-columns: repeat(2, 1fr); } .metrics div { min-height: 88px; } }
@media (max-width: 460px) { .badge-grid { grid-template-columns: 1fr; } }
</style>
