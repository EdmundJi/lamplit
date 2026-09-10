<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { CheckCircle2, Save } from 'lucide-vue-next'
import { onDataChanged } from '../../shared/data-sync'
import { growthIcon } from '../achievements/achievement.types'
import EmptyState from '../../shared/ui/EmptyState.vue'
import {
  advicePercent as advicePercentOf,
  confirmedAtLabel,
  earnedAtLabel,
  fulfillmentPercent as fulfillmentPercentOf,
  monthLabel as monthLabelOf,
  reviewFact as reviewFactOf,
  useInsightsOverview,
  useWeeklyReview,
} from './insights.logic'

const {
  overview: data,
  trends,
  roles,
  achievements,
  weeklyPlans,
  selectedPlanId,
  loading,
  error,
  changeSummary,
  calendarDays,
  earnedAchievementCount,
  load: loadOverview,
} = useInsightsOverview()

const {
  review,
  form: reviewForm,
  loading: reviewLoading,
  saving: reviewSaving,
  confirming: reviewConfirming,
  error: reviewError,
  feedback: reviewFeedback,
  confirmed: reviewConfirmed,
  load: loadReviewFor,
  save: saveReviewFor,
  confirm: confirmReviewFor,
} = useWeeklyReview()

const showAllBadges = ref(false)
const monthLabel = computed(() => monthLabelOf())
const visibleAchievements = computed(() => (showAllBadges.value ? achievements.value : achievements.value.filter(item => item.earned)))

function advicePercent(advice: string) {
  return advicePercentOf(data.value, advice)
}

function fulfillmentPercent() {
  return fulfillmentPercentOf(review.value)
}

function reviewFact(key: string) {
  return reviewFactOf(review.value, key)
}

function loadReview(planId: string) {
  return loadReviewFor(planId)
}

function saveReview(showFeedback = true) {
  return saveReviewFor(selectedPlanId.value, showFeedback)
}

async function confirmReview() {
  await confirmReviewFor(selectedPlanId.value)
}

async function load(showLoading = true) {
  await loadOverview(showLoading)
  if (!error.value) await loadReview(selectedPlanId.value)
}

const stopDataSync = onDataChanged(['insights', 'attributes', 'tasks', 'achievements'], () => load(false))

onMounted(() => load(true))
onBeforeUnmount(stopDataSync)
</script>
<template>
  <section class="page page--read insights-page">
    <header class="page-head">
      <div>
        <h1>洞察</h1>
      </div>
    </header>

    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-else-if="loading" class="loading-state" role="status">正在整理你的成长记录…</div>
    <template v-else-if="data">
      <dl class="stats">
        <div><dt>本周有效行动</dt><dd>{{ data.effectiveActions }}</dd></div>
        <div><dt>计划兑现率</dt><dd>{{ Math.round(data.fulfillmentRate * 100) }}%</dd></div>
        <div><dt>恢复次数</dt><dd>{{ data.recoveryCount }}</dd></div>
        <div><dt>累计行动经验</dt><dd>{{ data.totalExperience }}</dd></div>
      </dl>

      <section class="band summary-band" aria-labelledby="summary-title">
        <h2 id="summary-title" class="section-title">变化</h2>
        <p class="summary-detail"><strong>{{ changeSummary.headline }}</strong> {{ changeSummary.detail }}</p>
      </section>

      <section v-if="data.statusCheckCount" class="band status-band" aria-labelledby="status-title">
        <h2 id="status-title" class="section-title">状态</h2>
        <div class="status-overview">
          <p class="status-caption">本周填写 {{ data.statusCheckCount }} 天</p>
          <div class="advice-bars">
            <div class="advice-row">
              <span>缩小任务</span>
              <div class="progress" aria-label="缩小任务天数"><span :style="{ width: `${advicePercent('SHRINK')}%` }"></span></div>
              <b>{{ data.statusAdvices.SHRINK }} 天</b>
            </div>
            <div class="advice-row">
              <span>保持原计划</span>
              <div class="progress" aria-label="保持原计划天数"><span :style="{ width: `${advicePercent('KEEP')}%` }"></span></div>
              <b>{{ data.statusAdvices.KEEP }} 天</b>
            </div>
            <div class="advice-row">
              <span>轻量推进</span>
              <div class="progress" aria-label="轻量推进天数"><span :style="{ width: `${advicePercent('LIGHT')}%` }"></span></div>
              <b>{{ data.statusAdvices.LIGHT }} 天</b>
            </div>
          </div>
        </div>
      </section>

      <section class="band trend-section" aria-labelledby="trend-title">
        <h2 id="trend-title" class="section-title">趋势</h2>
        <div v-if="trends.length" class="trend-table">
          <table>
            <caption class="sr-only">每日有效行动与经验</caption>
            <thead><tr><th>日期</th><th>有效行动</th><th>经验</th></tr></thead>
            <tbody><tr v-for="row in trends" :key="row.date"><td>{{ row.date }}</td><td>{{ row.effectiveActions }}</td><td>{{ row.experience }}</td></tr></tbody>
          </table>
        </div>
        <EmptyState v-else sprite="pigeon_1" description="完成行动后，这里会出现你的趋势。" />
      </section>

      <section class="band calendar-section" aria-labelledby="calendar-title">
        <h2 id="calendar-title" class="section-title">日历</h2>
        <div class="calendar-shell">
          <div class="calendar-toolbar">
            <strong>{{ monthLabel }}</strong>
            <div class="calendar-legend" aria-label="日历标记说明">
              <span><i class="legend-dot done"></i>有效行动</span>
              <span><i class="legend-dot partial"></i>仅有经验</span>
              <span><i class="legend-dot none"></i>暂无记录</span>
            </div>
          </div>
          <div class="calendar-grid" aria-label="本月行动日历">
            <span v-for="day in ['日', '一', '二', '三', '四', '五', '六']" :key="day" class="weekday">{{ day }}</span>
            <time v-for="day in calendarDays" :key="day.date" :datetime="day.date" :class="['calendar-day', day.tone, { outside: !day.currentMonth }]" :title="day.row ? `${day.row.effectiveActions} 个有效行动，${day.row.experience} 经验` : '暂无行动'">
              <span>{{ day.label }}</span>
              <small v-if="day.row?.effectiveActions">{{ day.row.effectiveActions }}</small>
            </time>
          </div>
        </div>
      </section>

      <section class="band role-section">
        <h2 class="section-title">职业等级</h2>
        <div class="role-list">
          <article v-for="role in roles" :key="role.roleCode" class="role-row">
            <div class="role-level"><span>LV.{{ role.level }}</span><strong>{{ role.roleName }}</strong></div>
            <div class="role-progress">
              <div class="role-progress-copy">
                <span>{{ role.experience }} / {{ role.maxExperience }} 经验</span>
                <b v-if="role.level === 10">已满级</b>
                <span v-else>距下一级 {{ role.experienceToNextLevel }}</span>
              </div>
              <div class="progress" :aria-label="`${role.roleName}等级进度 ${role.levelProgressPercent}%`">
                <span :style="{ width: `${role.levelProgressPercent}%` }"></span>
              </div>
            </div>
          </article>
        </div>
      </section>

      <section class="band review-section" aria-labelledby="review-title">
        <h2 id="review-title" class="section-title">周复盘</h2>
        <div v-if="weeklyPlans.length > 1" class="review-toolbar">
          <label class="plan-picker" for="review-plan">
            <span>选择计划</span>
            <select id="review-plan" v-model="selectedPlanId" @change="loadReview(selectedPlanId)">
              <option v-for="(plan, index) in weeklyPlans" :key="plan.publicId" :value="plan.publicId">计划 {{ index + 1 }} · {{ plan.weekStartDate }}</option>
            </select>
          </label>
        </div>
        <EmptyState v-if="!weeklyPlans.length" class="review-empty" sprite="crow_1" description="本周还没有周计划，创建计划后就可以在这里复盘。" />
        <div v-else-if="reviewLoading" class="review-loading" role="status">正在读取复盘草稿…</div>
        <p v-else-if="reviewError && !review" class="error" role="alert">{{ reviewError }}</p>
        <form v-else-if="review" class="review-form" @submit.prevent="saveReview()">
          <div v-if="reviewConfirmed" class="confirmed-banner" role="status">
            <CheckCircle2 :size="19" />
            <span>已于 {{ confirmedAtLabel(review.confirmedAt) }} 确认</span>
          </div>
          <fieldset :disabled="reviewConfirmed || reviewSaving || reviewConfirming">
            <div class="review-grid">
              <div class="review-questions">
                <label class="field" for="reflection"><span>这周想记住什么？</span><textarea id="reflection" v-model="reviewForm.userReflection"></textarea></label>
                <label class="field" for="steady"><span>这周最稳定的行动是什么？</span><textarea id="steady" v-model="reviewForm.steadyAction"></textarea></label>
                <label class="field" for="heavy"><span>哪个任务太大，需要缩小？</span><textarea id="heavy" v-model="reviewForm.shrinkAction"></textarea></label>
                <label class="field" for="next"><span>下周要保留哪一个动作？</span><textarea id="next" v-model="reviewForm.nextAction"></textarea></label>
              </div>
              <aside class="review-facts" aria-label="本周计划数据">
                <strong>计划数据</strong>
                <dl>
                  <div><dt>计划行动</dt><dd>{{ reviewFact('plannedActions') }}</dd></div>
                  <div><dt>有效行动</dt><dd>{{ reviewFact('effectiveActions') }}</dd></div>
                  <div><dt>兑现率</dt><dd>{{ fulfillmentPercent() }}%</dd></div>
                </dl>
                <p>{{ reviewConfirmed ? '这份复盘已经归档。' : '草稿可以反复保存，只有确认后才会归档。' }}</p>
              </aside>
            </div>
          </fieldset>
          <p v-if="reviewError" class="error compact-message" role="alert">{{ reviewError }}</p>
          <p v-if="reviewFeedback" class="review-feedback" role="status" aria-live="polite">{{ reviewFeedback }}</p>
          <div v-if="!reviewConfirmed" class="review-actions">
            <button type="submit" class="secondary" :disabled="reviewSaving || reviewConfirming"><Save :size="17" />{{ reviewSaving ? '保存中…' : '保存草稿' }}</button>
            <button type="button" class="primary" :disabled="reviewSaving || reviewConfirming" @click="confirmReview"><CheckCircle2 :size="17" />{{ reviewConfirming ? '确认中…' : '确认本周复盘' }}</button>
          </div>
        </form>
      </section>

      <section class="band badge-section" aria-labelledby="badge-title">
        <div class="section-title badge-head">
          <h2 id="badge-title">成就</h2>
          <div class="badge-summary">
            <span>{{ earnedAchievementCount }} / {{ achievements.length }}</span>
            <button type="button" class="secondary badge-toggle" :aria-expanded="showAllBadges" @click="showAllBadges = !showAllBadges">
              {{ showAllBadges ? '只看已获得' : `查看全部 ${achievements.length} 个` }}
            </button>
          </div>
        </div>
        <div v-if="visibleAchievements.length" class="badge-grid">
          <article v-for="achievement in visibleAchievements" :key="achievement.code" class="badge-card" :class="[achievement.tone, { earned: achievement.earned }]">
            <div class="badge-icon" aria-hidden="true">
              <component :is="growthIcon(achievement.iconKey)" :size="22" />
            </div>
            <div class="badge-copy">
              <div class="badge-title-line">
                <strong>{{ achievement.name }}</strong>
                <span>{{ achievement.earned ? '已获得' : '未获得' }}</span>
              </div>
              <p>{{ achievement.body }}</p>
              <small>触发条件：{{ achievement.triggerText }}</small>
              <small v-if="achievement.earnedAt" class="earned-date">{{ earnedAtLabel(achievement.earnedAt) }} 获得</small>
            </div>
          </article>
        </div>
        <EmptyState v-else class="badge-empty" sprite="duck_brown_idle_1" description="还没有获得徽章。完成行动后，里程碑会出现在这里。" />
      </section>

    </template>
  </section>
</template>

<style scoped>
.stats { display: flex; flex-wrap: wrap; gap: 0; margin: 0 0 8px; padding: 0; }
.stats > div { display: flex; flex-direction: column; gap: 4px; padding: 0 20px; }
.stats > div:first-child { padding-left: 0; }
.stats > div + div { border-left: 1px solid var(--border); }
.stats dt { margin: 0; font-size: 12px; color: var(--muted); }
.stats dd { margin: 0; font-size: 20px; font-weight: 600; font-variant-numeric: tabular-nums; color: var(--ink); }
.summary-detail { margin: 0; color: var(--muted); font-size: 14px; line-height: 1.7; }
.trend-table { overflow-x: auto; }
.trend-table table { width: 100%; border-collapse: collapse; font-variant-numeric: tabular-nums; }
.trend-table th, .trend-table td { padding: 10px 12px; text-align: left; border-bottom: 1px solid var(--border); }
.trend-table th { color: var(--muted); font-size: 13px; font-weight: 650; }
.badge-toggle { min-height: 34px; padding: 0 12px; font-size: 13px; }
.badge-empty { padding: 28px 20px; }
.badge-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.badge-head h2 { margin: 0; font: inherit; color: inherit; }
.badge-summary { display: inline-flex; align-items: center; gap: 8px; color: var(--primary); font-weight: 700; }
.status-overview { display: grid; gap: 10px; }
.status-caption { margin: 0; color: var(--muted); font-size: 13px; }
.advice-bars { display: grid; gap: 10px; }
.advice-row { display: grid; grid-template-columns: 88px minmax(0, 1fr) 44px; align-items: center; gap: 12px; color: var(--muted); font-size: 13px; }
.advice-row b { color: var(--ink); font-size: 12px; text-align: right; }
.advice-row .progress > span { background: var(--primary); }
.advice-row:nth-child(2) .progress > span { background: var(--accent); }
.advice-row:nth-child(3) .progress > span { background: var(--amber); }
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
.badge-card small.earned-date { color: var(--primary); font-weight: 700; }
.calendar-shell { border: 1px solid var(--border); border-radius: var(--radius-card); background: color-mix(in srgb, var(--surface) 90%, transparent); padding: 16px; overflow-x: auto; }
.calendar-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin-bottom: 12px; }
.calendar-toolbar strong { font-size: 15px; }
.calendar-legend { display: flex; flex-wrap: wrap; gap: 10px; color: var(--muted); font-size: 12px; }
.calendar-legend span { display: inline-flex; align-items: center; gap: 5px; }
.legend-dot { flex: none; display: inline-block; width: 10px; height: 10px; aspect-ratio: 1; padding: 0; border-radius: 50%; border: 1px solid var(--border); background: var(--surface-muted); }
.legend-dot.done { background: var(--primary); border-color: var(--primary); }
.legend-dot.partial { background: var(--amber); border-color: var(--amber); }
.calendar-grid { display: grid !important; grid-template-columns: repeat(7, minmax(58px, 1fr)); gap: 7px; min-width: 480px; }
.weekday { display: grid; place-items: center; min-height: 26px; color: var(--muted); font-size: 12px; font-weight: 700; text-align: center; }
.calendar-day { min-height: 58px; display: grid; grid-template-rows: 1fr auto; place-items: center; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); color: var(--ink); font-size: 13px; text-decoration: none; }
.calendar-day.outside { min-height: 58px; opacity: .46; color: var(--muted); background: color-mix(in srgb, var(--surface-muted) 60%, var(--surface)); }
.calendar-day small { min-width: 18px; height: 18px; display: grid; place-items: center; border-radius: 50%; background: var(--primary); color: white; font-size: 10px; line-height: 1; }
.calendar-day.done { border-color: color-mix(in srgb, var(--primary) 38%, var(--border)); background: color-mix(in srgb, var(--primary-soft) 70%, var(--surface)); color: var(--primary); font-weight: 800; }
.calendar-day.partial { border-color: color-mix(in srgb, var(--amber) 38%, var(--border)); background: color-mix(in srgb, var(--amber) 8%, var(--surface)); color: var(--amber); }
.role-list { display: grid; gap: 9px; margin-top: 14px; }
.role-row { display: grid; grid-template-columns: minmax(150px, .45fr) minmax(240px, 1fr); align-items: center; gap: 24px; min-height: 78px; border: 1px solid var(--border); border-radius: var(--radius); padding: 13px 15px; background: color-mix(in srgb, var(--surface) 88%, transparent); }
.role-row:hover { background: var(--surface); border-color: color-mix(in srgb, var(--primary) 22%, var(--border)); }
.role-level { display: flex; align-items: baseline; gap: 12px; }
.role-level span { font-weight: 800; font-size: 13px; color: var(--amber); }
.role-level strong { font-size: 16px; }
.role-progress { display: grid; gap: 8px; }
.role-progress-copy { display: flex; justify-content: space-between; gap: 16px; color: var(--muted); font-size: 12px; }
.role-progress-copy b { color: var(--primary); }
.loading-state { min-height: 300px; display: grid; place-items: center; color: var(--muted); }
.review-toolbar { display: flex; justify-content: flex-end; margin: -4px 0 18px; }
.plan-picker { width: min(100%, 340px); display: grid; grid-template-columns: auto minmax(0, 1fr); align-items: center; gap: 10px; }
.plan-picker span { color: var(--muted); font-size: 13px; font-weight: 700; }
.plan-picker select { min-width: 0; }
.review-empty { min-height: 120px; display: grid; place-items: center; margin: 0; text-align: center; }
.review-loading { min-height: 180px; display: grid; place-items: center; color: var(--muted); }
.review-form { display: grid; gap: 14px; }
.review-form fieldset { min-width: 0; margin: 0; padding: 0; border: 0; }
.review-grid { display: grid; grid-template-columns: minmax(0, 1fr) 280px; gap: 22px; align-items: start; }
.review-questions { display: grid; gap: 12px; }
.field span { font-weight: 650; font-size: 14px; }
.review-facts { display: grid; gap: 16px; padding: 4px 0 16px; border-top: 1px solid var(--border); border-bottom: 1px solid var(--border); }
.review-facts > strong { padding-top: 12px; }
.review-facts dl { display: grid; gap: 0; margin: 0; }
.review-facts dl div { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; padding: 10px 0; border-bottom: 1px solid var(--surface-muted); }
.review-facts dt { color: var(--muted); font-size: 13px; }
.review-facts dd { margin: 0; color: var(--primary); font-size: 20px; font-weight: 800; }
.review-facts p { margin: 0; color: var(--muted); font-size: 12px; line-height: 1.65; }
.confirmed-banner { min-height: 42px; display: flex; align-items: center; gap: 9px; padding: 0 12px; border-left: 3px solid var(--primary); background: var(--primary-soft); color: var(--primary); font-size: 13px; font-weight: 700; }
.review-actions { display: flex; justify-content: flex-end; gap: 10px; }
.review-actions button { display: inline-flex; align-items: center; justify-content: center; gap: 7px; min-width: 132px; }
.review-feedback { margin: 0; color: var(--primary); font-size: 13px; font-weight: 700; text-align: right; }
.compact-message { margin: 0; }
table { width: 100%; border-collapse: collapse; border: 1px solid var(--border); border-radius: var(--radius); overflow: hidden; background: var(--surface); }
th, td { text-align: left; padding: 11px; border-bottom: 1px solid var(--border); }
tbody tr:hover { background: var(--surface-muted); }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); }
@media (prefers-reduced-motion: no-preference) {
  .badge-grid article, .calendar-day, .role-row, tbody tr, .review-facts { animation: insight-enter var(--motion-medium) var(--ease) both; }
  .badge-grid article:nth-child(2), .role-row:nth-child(2), tbody tr:nth-child(2) { animation-delay: 45ms; }
  .badge-grid article:nth-child(3), .role-row:nth-child(3), tbody tr:nth-child(3) { animation-delay: 90ms; }
  .badge-grid article:nth-child(4), .role-row:nth-child(4), tbody tr:nth-child(4) { animation-delay: 135ms; }
  .role-row, tbody tr, .calendar-day, .badge-grid article { transition: background-color var(--motion-fast) var(--ease), border-color var(--motion-fast) var(--ease); }
}
@keyframes insight-enter { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: translateY(0); } }
@media (max-width: 820px) {
  .badge-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .review-grid { grid-template-columns: 1fr; }
}
@media (max-width: 600px) {
  .role-row { grid-template-columns: 1fr; gap: 9px; padding: 16px 2px; }
  .role-progress-copy { gap: 8px; }
  .review-toolbar { justify-content: stretch; }
  .plan-picker { width: 100%; grid-template-columns: 1fr; gap: 6px; }
  .review-actions { align-items: stretch; flex-direction: column; }
  .review-actions button { width: 100%; }
}
@media (max-width: 460px) {
  .badge-grid { grid-template-columns: 1fr; }
  .calendar-day { min-height: 34px; }
}
.insights-page { display: flex; flex-direction: column; }
.calendar-shell, .badge-card, .role-row { border-radius: var(--radius-panel); }
.review-form { padding: 24px; border: 1px solid var(--border); border-radius: var(--radius-panel); background: var(--surface); }
@media (max-width: 760px) { .stats { gap: 10px 0; } .stats > div { padding: 0 16px; } .review-form { padding: 16px; } }
@media (max-width: 460px) { .stats { flex-direction: column; gap: 14px; } .stats > div { padding: 0; border-left: 0 !important; } }
</style>
