<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { init, use, type ECharts } from 'echarts/core'
import { RadarChart } from 'echarts/charts'
import { LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { ArrowLeft, BadgeCheck, MessageCircle, UserRound } from 'lucide-vue-next'
import { motionAllowed } from '../../shared/ui/interaction/motion'
import { computeBadges, type Badge } from '../insights/badges'
import EmptyState from '../../shared/ui/EmptyState.vue'
import RivePet from '../partners/RivePet.vue'
import { friendInitial, memberSinceLabel as memberSinceLabelOf, useFriendProfile } from './friends.logic'

use([RadarChart, LegendComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{ publicId: string }>()

const chartElement = ref<HTMLElement | null>(null)
const { profile, loading, error, load, doneCount, petProgress } = useFriendProfile()
let chart: ECharts | undefined

const initial = computed(() => friendInitial(profile.value?.displayName ?? ''))

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
const memberSinceLabel = computed(() => profile.value ? memberSinceLabelOf(profile.value.memberSince) : '')

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
  const primary = styles.getPropertyValue('--primary-strong').trim() || '#255643'
  chart.setOption({
    animation: motionAllowed(),
    animationDuration: 400,
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
  await load(props.publicId)
  if (profile.value) {
    await nextTick()
    renderChart()
    window.addEventListener('resize', resizeChart)
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
      <div class="friend-head-row">
        <RouterLink class="back-link" to="/friends"><ArrowLeft :size="17" />返回好友</RouterLink>
        <RouterLink v-if="profile" class="secondary button chat-head-entry" :to="`/friends/${profile.publicId}/chat`"><MessageCircle :size="17" />发消息</RouterLink>
      </div>
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
      <dl class="stats">
        <div><dt>本周有效行动</dt><dd>{{ profile.overview.effectiveActions }}</dd></div>
        <div><dt>本周兑现率</dt><dd>{{ Math.round(profile.overview.fulfillmentRate * 100) }}%</dd></div>
        <div><dt>恢复次数</dt><dd>{{ profile.overview.recoveryCount }}</dd></div>
        <div><dt>累计行动经验</dt><dd>{{ profile.overview.totalExperience }}</dd></div>
      </dl>

      <section class="band today-band" aria-labelledby="friend-today-title">
        <div class="section-head">
          <h2 id="friend-today-title" class="section-title">今日完成情况</h2>
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
        <EmptyState v-else sprite="chicken_white_idle_1" title="今天还没有安排" description="对方今天的日程还是空的。" />
      </section>

      <section class="band attribute-band" aria-labelledby="friend-radar-title">
        <div class="attribute-overview">
          <div class="radar-copy">
            <h2 id="friend-radar-title" class="section-title">对方行动的轮廓</h2>
            <p>五维成长指数只反映行动积累，不构成任何能力测评。</p>
            <p class="radar-note">累计属性经验 {{ profile.totalExperience }} · 连续行动最长 {{ profile.longestStreak }} 天</p>
          </div>
          <div ref="chartElement" class="radar-chart" role="img" :aria-label="`好友成长属性雷达图：${profile.attributes.map(item => `${item.name}${item.radarScore}`).join('，')}`"></div>
        </div>
      </section>

      <section v-if="profile.pet" class="band pet-band" aria-labelledby="friend-pet-title">
        <h2 id="friend-pet-title" class="section-title">{{ profile.pet.name }}正在陪着ta</h2>
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
        <div class="section-head">
          <h2 id="friend-badge-title" class="section-title">对方获得的里程碑</h2>
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
.friend-head-row { width: 100%; display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.chat-head-entry { display: inline-flex; align-items: center; gap: 7px; }
.back-link { display: inline-flex; align-items: center; gap: 6px; color: var(--muted); text-decoration: none; font-size: 13px; font-weight: 700; }
.back-link:hover { color: var(--primary); }
.friend-identity { display: flex; align-items: center; gap: 15px; }
.friend-avatar { width: 64px; height: 64px; display: grid; place-items: center; border-radius: var(--radius-scene) var(--radius-scene) var(--radius-scene) var(--radius); background: linear-gradient(145deg, var(--primary), color-mix(in srgb, var(--primary) 72%, var(--amber))); color: white; font-size: 27px; font-weight: 900; }
.friend-identity h1 { margin: 4px 0 0; }
.loading-state { min-height: 420px; display: grid; place-items: center; color: var(--muted); }
.stats { display: flex; flex-wrap: wrap; gap: 0; margin: 0 0 8px; }
.stats > div { padding: 0 20px; border-right: 1px solid var(--border); }
.stats > div:first-child { padding-left: 0; }
.stats > div:last-child { border-right: 0; }
.stats dt { margin: 0; color: var(--muted); font-size: 12px; }
.stats dd { margin: 4px 0 0; color: var(--ink); font-size: 20px; font-weight: 600; font-variant-numeric: tabular-nums; }
.section-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 32px 0 12px; padding-bottom: 8px; border-bottom: 1px solid var(--border); }
.section-head .section-title { flex: 1; margin: 0; padding: 0; border: 0; }
.today-band .section-head { margin-top: 0; }
.section-count { padding: 3px 9px; border-radius: 999px; background: var(--surface-muted); color: var(--muted); font-size: 12px; font-weight: 800; }
.task-list { display: grid; gap: 9px; }
.task-row { min-height: 74px; display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 13px 15px; border: 1px solid var(--border); border-radius: var(--radius); background: color-mix(in srgb, var(--surface) 90%, transparent); }
.task-row > div { min-width: 0; display: grid; gap: 5px; }
.task-row .status { font-size: 12px; font-weight: 700; color: var(--primary); }
.task-row h3 { margin: 0; font-size: 15px; line-height: 1.4; overflow-wrap: anywhere; }
.task-row time { color: var(--muted); font-size: 12px; }
.task-state { flex: none; padding: 4px 10px; border-radius: 999px; background: var(--surface-muted); color: var(--muted); font-size: 12px; font-weight: 700; }
.task-state.done { background: color-mix(in srgb, var(--accent) 14%, var(--surface)); color: var(--accent); }
.attribute-overview { min-height: 330px; display: grid; grid-template-columns: minmax(240px, .72fr) minmax(360px, 1fr); align-items: center; gap: 20px; padding: 20px 0 24px; }
.radar-copy > p { margin: 12px 0 0; color: var(--muted); line-height: 1.75; }
.radar-copy .radar-note { color: var(--ink); font-variant-numeric: tabular-nums; }
.radar-chart { width: 100%; min-height: 320px; }
.pet-stage { position: relative; height: 250px; display: grid; place-items: center; border: 1px solid color-mix(in srgb, var(--accent) 25%, var(--border)); border-radius: var(--radius-scene) var(--radius-scene) var(--radius-card) var(--radius-card); background: linear-gradient(180deg, color-mix(in srgb, var(--accent) 10%, var(--surface)), color-mix(in srgb, var(--amber) 9%, var(--surface))); overflow: hidden; }
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
.badge-card.earned { color: var(--ink); opacity: 1; border-color: color-mix(in srgb, var(--badge-color) 42%, var(--border)); background: linear-gradient(145deg, color-mix(in srgb, var(--badge-color) 10%, var(--surface)), var(--surface)); }
.badge-card.green { --badge-color: var(--primary); }
.badge-card.blue { --badge-color: var(--tone-blue); }
.badge-card.amber { --badge-color: var(--amber); }
.badge-card.violet { --badge-color: var(--tone-violet); }
.badge-icon { width: 44px; height: 44px; display: grid; place-items: center; border: 1px solid color-mix(in srgb, var(--badge-color) 32%, var(--border)); border-radius: var(--radius-panel); color: var(--badge-color); background: var(--surface); }
.badge-card:not(.earned) .badge-icon { color: var(--muted); border-color: var(--border); background: var(--surface-muted); }
.badge-copy { display: grid; gap: 7px; align-content: start; min-width: 0; }
.badge-title-line { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.badge-title-line strong { min-width: 0; font-size: 15px; line-height: 1.35; color: var(--ink); }
.badge-title-line span { flex: none; padding: 2px 6px; border: 1px solid var(--border); border-radius: 999px; color: var(--muted); font-size: 11px; font-weight: 700; }
.badge-card.earned .badge-title-line span { border-color: color-mix(in srgb, var(--badge-color) 36%, var(--border)); color: var(--badge-color); background: color-mix(in srgb, var(--badge-color) 8%, var(--surface)); }
.badge-card p { margin: 0; color: var(--muted); font-size: 12px; line-height: 1.5; }
.badge-card small { color: var(--muted); font-size: 11px; line-height: 1.45; }
@media (prefers-reduced-motion: no-preference) {
  .friend-card, .task-row, .badge-card { animation: friend-enter var(--motion-medium) var(--ease) both; }
}
@keyframes friend-enter { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: translateY(0); } }
@media (max-width: 850px) { .attribute-overview { grid-template-columns: 1fr; } .radar-copy { max-width: none; } .radar-chart { min-height: 300px; } }
@media (max-width: 820px) { .badge-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 600px) { .stats { gap: 10px 0; } .stats > div { flex: 1 1 40%; border-right: 0; } }
@media (max-width: 460px) { .badge-grid { grid-template-columns: 1fr; } }
.badge-card, .friend-pet-stage { border-radius: var(--radius-panel); }
.page > .band { padding-block: 28px; }
</style>
