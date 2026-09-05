<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref } from 'vue'
import { Award, BadgeCheck, CheckCircle2, PenLine, Save, TrendingUp } from 'lucide-vue-next'
import { onDataChanged } from '../../../../shared/data-sync'
import { growthIcon } from '../../../achievements/achievement.types'
import {
  confirmedAtLabel,
  earnedAtLabel,
  fulfillmentPercent as fulfillmentPercentOf,
  reviewFact as reviewFactOf,
  useInsightsOverview,
  useWeeklyReview,
} from '../../../insights/insights.logic'
import { worldBridgeKey } from '../panel.types'
import { openFullPage } from './shared'

const FULL_PAGE = '/insights'
type Tab = 'overview' | 'review' | 'badges'

const bridge = inject(worldBridgeKey, undefined)

const {
  overview, roles, achievements, weeklyPlans, selectedPlanId, loading, error,
  changeSummary, earnedAchievementCount, load: loadOverview,
} = useInsightsOverview()

const {
  review, form: reviewForm, loading: reviewLoading, saving: reviewSaving, confirming: reviewConfirming,
  error: reviewError, feedback: reviewFeedback, confirmed: reviewConfirmed,
  load: loadReviewFor, save: saveReviewFor, confirm: confirmReviewFor,
} = useWeeklyReview()

const tab = ref<Tab>('overview')
const showAllBadges = ref(false)
const visibleAchievements = computed(() => (showAllBadges.value ? achievements.value : achievements.value.filter(item => item.earned)))

function fulfillmentPercent() {
  return fulfillmentPercentOf(review.value)
}

function reviewFact(key: string) {
  return reviewFactOf(review.value, key)
}

function loadReview(planId: string) {
  return loadReviewFor(planId)
}

function saveReview() {
  return saveReviewFor(selectedPlanId.value)
}

async function confirmReview() {
  const goalPublicId = weeklyPlans.value.find(plan => plan.publicId === selectedPlanId.value)?.goalPublicId
  const ok = await confirmReviewFor(selectedPlanId.value)
  if (ok) {
    bridge?.emit({ type: 'toast', text: '本周复盘已确认' })
    if (goalPublicId) bridge?.emit({ type: 'celebrate', publicId: goalPublicId })
  }
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
  <section class="world-panel insights-panel">
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="loading" class="empty">正在整理洞察…</p>
    <template v-else-if="overview">
      <nav class="tab-row" role="tablist" aria-label="洞察面板分区">
        <button type="button" role="tab" :aria-selected="tab === 'overview'" :class="{ active: tab === 'overview' }" @click="tab = 'overview'">概览</button>
        <button type="button" role="tab" :aria-selected="tab === 'review'" :class="{ active: tab === 'review' }" @click="tab = 'review'">周复盘</button>
        <button type="button" role="tab" :aria-selected="tab === 'badges'" :class="{ active: tab === 'badges' }" @click="tab = 'badges'">徽章</button>
      </nav>

      <section v-if="tab === 'overview'" class="tab-panel">
        <div class="stat-grid">
          <div class="stat-card">
            <span class="stat-label">有效行动</span>
            <strong>{{ overview.effectiveActions }}</strong>
          </div>
          <div class="stat-card">
            <span class="stat-label">达成率</span>
            <strong>{{ Math.round(overview.fulfillmentRate * 100) }}%</strong>
          </div>
          <div class="stat-card">
            <span class="stat-label">恢复次数</span>
            <strong>{{ overview.recoveryCount }}</strong>
          </div>
          <div class="stat-card">
            <span class="stat-label"><Award :size="13" />成就</span>
            <strong>{{ earnedAchievementCount }} / {{ achievements.length }}</strong>
          </div>
        </div>
        <div class="summary-card">
          <TrendingUp :size="15" />
          <div>
            <strong>{{ changeSummary.headline }}</strong>
            <p>{{ changeSummary.detail }}</p>
          </div>
        </div>
        <ul class="role-list">
          <li v-for="role in roles" :key="role.roleCode" class="role-row">
            <div class="role-head"><span>{{ role.roleName }}</span><b>LV.{{ role.level }}</b></div>
            <div class="progress"><span :style="{ width: `${role.levelProgressPercent}%` }" /></div>
          </li>
        </ul>
      </section>

      <section v-else-if="tab === 'review'" class="tab-panel review-tab">
        <label v-if="weeklyPlans.length > 1" class="plan-picker">
          <span>选择计划</span>
          <select v-model="selectedPlanId" @change="loadReview(selectedPlanId)">
            <option v-for="(plan, index) in weeklyPlans" :key="plan.publicId" :value="plan.publicId">计划 {{ index + 1 }} · {{ plan.weekStartDate }}</option>
          </select>
        </label>
        <p v-if="!weeklyPlans.length" class="empty">本周还没有周计划，创建计划后就可以在这里复盘。</p>
        <p v-else-if="reviewLoading" class="empty">正在读取复盘草稿…</p>
        <p v-else-if="reviewError && !review" class="error" role="alert">{{ reviewError }}</p>
        <form v-else-if="review" class="review-form" @submit.prevent="saveReview()">
          <div v-if="reviewConfirmed" class="confirmed-banner" role="status">
            <CheckCircle2 :size="16" />
            <span>已于 {{ confirmedAtLabel(review.confirmedAt) }} 确认</span>
          </div>
          <fieldset :disabled="reviewConfirmed || reviewSaving || reviewConfirming">
            <label class="field"><span>这周想记住什么？</span><textarea v-model="reviewForm.userReflection"></textarea></label>
            <label class="field"><span>这周最稳定的行动是什么？</span><textarea v-model="reviewForm.steadyAction"></textarea></label>
            <label class="field"><span>哪个任务太大，需要缩小？</span><textarea v-model="reviewForm.shrinkAction"></textarea></label>
            <label class="field"><span>下周要保留哪一个动作？</span><textarea v-model="reviewForm.nextAction"></textarea></label>
            <dl class="review-facts">
              <div><dt>计划行动</dt><dd>{{ reviewFact('plannedActions') }}</dd></div>
              <div><dt>有效行动</dt><dd>{{ reviewFact('effectiveActions') }}</dd></div>
              <div><dt>兑现率</dt><dd>{{ fulfillmentPercent() }}%</dd></div>
            </dl>
          </fieldset>
          <p v-if="reviewError" class="error compact-message" role="alert">{{ reviewError }}</p>
          <p v-if="reviewFeedback" class="review-feedback" role="status" aria-live="polite">{{ reviewFeedback }}</p>
          <div v-if="!reviewConfirmed" class="review-actions">
            <button type="submit" class="secondary" :disabled="reviewSaving || reviewConfirming"><Save :size="14" />{{ reviewSaving ? '保存中…' : '保存草稿' }}</button>
            <button type="button" class="primary" :disabled="reviewSaving || reviewConfirming" @click="confirmReview"><CheckCircle2 :size="14" />{{ reviewConfirming ? '确认中…' : '确认' }}</button>
          </div>
        </form>
      </section>

      <section v-else class="tab-panel">
        <div class="badge-summary">
          <BadgeCheck :size="16" />
          <span>{{ earnedAchievementCount }} / {{ achievements.length }}</span>
          <button type="button" class="secondary badge-toggle" :aria-expanded="showAllBadges" @click="showAllBadges = !showAllBadges">
            {{ showAllBadges ? '只看已获得' : '查看全部' }}
          </button>
        </div>
        <ul class="badge-list">
          <li v-for="achievement in visibleAchievements" :key="achievement.code" class="badge-row" :class="{ earned: achievement.earned }">
            <span class="badge-icon"><component :is="growthIcon(achievement.iconKey)" :size="16" /></span>
            <div>
              <strong>{{ achievement.name }}</strong>
              <small v-if="achievement.earnedAt">{{ earnedAtLabel(achievement.earnedAt) }} 获得</small>
              <small v-else>{{ achievement.triggerText }}</small>
            </div>
          </li>
        </ul>
        <p v-if="!visibleAchievements.length" class="empty">还没有获得徽章。完成行动后，里程碑会出现在这里。</p>
      </section>
    </template>
    <div v-else class="empty">
      <TrendingUp :size="20" />
      <p>先完成几件任务，这里就会留下你的成长痕迹。</p>
    </div>

    <footer class="panel-footer">
      <button class="secondary" type="button" @click="openFullPage(bridge, FULL_PAGE)">打开完整页面</button>
    </footer>
  </section>
</template>

<style scoped>
.world-panel { display: grid; gap: 12px; width: 100%; color: var(--ink); }
.tab-row { display: grid; grid-auto-flow: column; gap: 3px; padding: 3px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface-muted); }
.tab-row button { min-width: 0; min-height: 30px; border: 0; border-radius: calc(var(--radius) - 2px); background: transparent; color: var(--muted); font-size: 12px; padding: 0 6px; }
.tab-row button.active { background: var(--surface); color: var(--primary-strong); font-weight: 700; box-shadow: var(--shadow-soft); }
.tab-panel { display: grid; gap: 12px; }
.stat-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.stat-card { display: grid; gap: 4px; padding: 10px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); }
.stat-label { display: inline-flex; align-items: center; gap: 4px; font-size: 11px; color: var(--muted); }
.stat-card strong { font-size: 17px; }
.summary-card { display: grid; grid-template-columns: 16px minmax(0, 1fr); gap: 8px; padding: 10px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); color: var(--primary); }
.summary-card strong { display: block; font-size: 12px; color: var(--ink); }
.summary-card p { margin: 4px 0 0; color: var(--muted); font-size: 11px; line-height: 1.5; }
.role-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
.role-row { display: grid; gap: 4px; padding: 8px 10px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); }
.role-head { display: flex; justify-content: space-between; font-size: 12px; }
.role-head b { color: var(--amber); }
.plan-picker { display: grid; gap: 4px; font-size: 12px; color: var(--muted); }
.review-form { display: grid; gap: 10px; }
.review-form fieldset { display: grid; gap: 8px; margin: 0; padding: 0; border: 0; }
.field { display: grid; gap: 4px; font-size: 12px; }
.field textarea { min-height: 52px; resize: vertical; }
.review-facts { display: grid; gap: 0; margin: 4px 0 0; padding: 8px 0 0; border-top: 1px solid var(--border); }
.review-facts div { display: flex; justify-content: space-between; padding: 4px 0; font-size: 12px; }
.review-facts dt { color: var(--muted); }
.review-facts dd { margin: 0; font-weight: 700; color: var(--primary); }
.confirmed-banner { display: flex; align-items: center; gap: 7px; padding: 6px 10px; border-left: 3px solid var(--primary); background: var(--primary-soft); color: var(--primary); font-size: 12px; font-weight: 700; }
.review-actions { display: flex; justify-content: flex-end; gap: 8px; }
.review-feedback { margin: 0; color: var(--primary); font-size: 12px; text-align: right; }
.compact-message { margin: 0; }
.badge-summary { display: flex; align-items: center; gap: 8px; color: var(--primary); font-weight: 700; font-size: 12px; }
.badge-toggle { margin-left: auto; min-height: 28px; padding: 0 10px; font-size: 11px; }
.badge-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; max-height: 260px; overflow-y: auto; }
.badge-row { display: grid; grid-template-columns: 28px minmax(0, 1fr); gap: 8px; align-items: center; padding: 8px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); opacity: .7; }
.badge-row.earned { opacity: 1; border-color: color-mix(in srgb, var(--primary) 32%, var(--border)); }
.badge-icon { width: 28px; height: 28px; display: grid; place-items: center; border-radius: 10px; background: var(--surface-muted); color: var(--muted); }
.badge-row.earned .badge-icon { color: var(--primary); background: var(--primary-soft); }
.badge-row strong { display: block; font-size: 12px; }
.badge-row small { color: var(--muted); font-size: 10px; }
.panel-footer { display: flex; justify-content: flex-end; }
</style>
