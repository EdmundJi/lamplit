<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  Award,
  BadgeCheck,
  BookOpenCheck,
  CalendarCheck,
  CalendarDays,
  CheckCircle2,
  CircleDot,
  Compass,
  Flame,
  Footprints,
  HeartHandshake,
  Leaf,
  ListChecks,
  Medal,
  Mountain,
  PenLine,
  Repeat2,
  Rocket,
  Sparkles,
  Sprout,
  Star,
  Target,
  Trophy,
} from 'lucide-vue-next'
import { api } from '../../shared/api/client'

type RoleProgress = { roleCode: string; roleName: string; level: number; experience: number; maxExperience: number; totalExperience: number; nextLevelExperience: number | null; experienceToNextLevel: number; levelProgressPercent: number }
type TrendRow = { date: string; effectiveActions: number; experience: number }
type Badge = { title: string; trigger: string; body: string; earned: boolean; icon: unknown; tone: string }

const data = ref<any>(null)
const trends = ref<TrendRow[]>([])
const roles = ref<RoleProgress[]>([])
const error = ref('')
const reviewAnswers = reactive(['', '', ''])

function localDateKey(date: Date) {
  const year = date.getFullYear()
  const month = `${date.getMonth() + 1}`.padStart(2, '0')
  const day = `${date.getDate()}`.padStart(2, '0')
  return `${year}-${month}-${day}`
}

function longestActionStreak(rows: TrendRow[]) {
  const activeDates = new Set(rows.filter(row => row.effectiveActions > 0).map(row => row.date))
  if (!activeDates.size) return 0
  const sorted = [...activeDates].sort()
  let longest = 1
  let current = 1
  for (let index = 1; index < sorted.length; index += 1) {
    const previous = new Date(`${sorted[index - 1]}T00:00:00`)
    const date = new Date(`${sorted[index]}T00:00:00`)
    const delta = Math.round((date.getTime() - previous.getTime()) / 86400000)
    current = delta === 1 ? current + 1 : 1
    longest = Math.max(longest, current)
  }
  return longest
}

const trendMap = computed(() => new Map(trends.value.map(row => [row.date, row])))
const calendarDays = computed(() => {
  const now = new Date()
  const first = new Date(now.getFullYear(), now.getMonth(), 1)
  const start = new Date(first)
  start.setDate(first.getDate() - first.getDay())
  return Array.from({ length: 42 }, (_, index) => {
    const day = new Date(start)
    day.setDate(start.getDate() + index)
    const date = localDateKey(day)
    const row = trendMap.value.get(date)
    return {
      date,
      label: day.getDate(),
      currentMonth: day.getMonth() === now.getMonth(),
      tone: row?.effectiveActions ? 'done' : row?.experience ? 'partial' : 'empty',
      row,
    }
  })
})
const longestStreak = computed(() => longestActionStreak(trends.value))
const bestRoleLevel = computed(() => Math.max(0, ...roles.value.map(role => role.level)))
const roleCountLevel2 = computed(() => roles.value.filter(role => role.level >= 2).length)
const roleCountLevel5 = computed(() => roles.value.filter(role => role.level >= 5).length)
const monthLabel = computed(() => new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long' }).format(new Date()))
const badges = computed<Badge[]>(() => [
  { title: '第一步行动', trigger: '本周有效行动 ≥ 1', body: '迈出第一步，系统才开始有你的真实节奏。', earned: (data.value?.effectiveActions ?? 0) >= 1, icon: Footprints, tone: 'green' },
  { title: '三次推进', trigger: '本周有效行动 ≥ 3', body: '一周内完成三次有效推进。', earned: (data.value?.effectiveActions ?? 0) >= 3, icon: ListChecks, tone: 'green' },
  { title: '五点成线', trigger: '本周有效行动 ≥ 5', body: '把零散行动连成更稳定的周节奏。', earned: (data.value?.effectiveActions ?? 0) >= 5, icon: CheckCircle2, tone: 'green' },
  { title: '兑现过半', trigger: '本周兑现率 ≥ 50%', body: '计划已经不只是写下来。', earned: (data.value?.fulfillmentRate ?? 0) >= .5, icon: CircleDot, tone: 'blue' },
  { title: '稳定一周', trigger: '本周兑现率 ≥ 75%', body: '本周兑现率达到稳定区间。', earned: (data.value?.fulfillmentRate ?? 0) >= .75, icon: CalendarCheck, tone: 'blue' },
  { title: '高度贴合', trigger: '本周兑现率 ≥ 90%', body: '计划规模和真实生活匹配得很好。', earned: (data.value?.fulfillmentRate ?? 0) >= .9, icon: Target, tone: 'blue' },
  { title: '温和恢复', trigger: '恢复次数 ≥ 1', body: '中断之后重新回到计划。', earned: (data.value?.recoveryCount ?? 0) >= 1, icon: Leaf, tone: 'amber' },
  { title: '恢复熟练', trigger: '恢复次数 ≥ 3', body: '你已经在练习不责备地回来。', earned: (data.value?.recoveryCount ?? 0) >= 3, icon: Repeat2, tone: 'amber' },
  { title: '小火苗', trigger: '连续行动天数 ≥ 2', body: '连续两天留下行动痕迹。', earned: longestStreak.value >= 2, icon: Flame, tone: 'amber' },
  { title: '一周足迹', trigger: '连续行动天数 ≥ 7', body: '连续一周保持可见行动。', earned: longestStreak.value >= 7, icon: CalendarDays, tone: 'amber' },
  { title: '十点经验', trigger: '累计经验 ≥ 10', body: '经验来自行动记录，不代表人格或能力评价。', earned: (data.value?.totalExperience ?? 0) >= 10, icon: Star, tone: 'violet' },
  { title: '五十经验', trigger: '累计经验 ≥ 50', body: '积累开始变得可见。', earned: (data.value?.totalExperience ?? 0) >= 50, icon: Sparkles, tone: 'violet' },
  { title: '百点经验', trigger: '累计经验 ≥ 100', body: '长期行动留下了更厚的轨迹。', earned: (data.value?.totalExperience ?? 0) >= 100, icon: Trophy, tone: 'violet' },
  { title: '学习起步', trigger: '学生等级 ≥ 2', body: '学习角色完成第一次升级。', earned: roles.value.some(role => role.roleCode === 'STUDENT' && role.level >= 2), icon: BookOpenCheck, tone: 'green' },
  { title: '身体照顾', trigger: '健身用户等级 ≥ 2', body: '身体照顾角色完成第一次升级。', earned: roles.value.some(role => role.roleCode === 'FITNESS_USER' && role.level >= 2), icon: Sprout, tone: 'green' },
  { title: '职场推进', trigger: '打工人等级 ≥ 2', body: '职场角色完成第一次升级。', earned: roles.value.some(role => role.roleCode === 'WORKER' && role.level >= 2), icon: Rocket, tone: 'blue' },
  { title: '情绪支持', trigger: '情绪支持等级 ≥ 2', body: '情绪支持角色完成第一次升级。', earned: roles.value.some(role => role.roleCode === 'EMOTIONAL_SUPPORT_USER' && role.level >= 2), icon: HeartHandshake, tone: 'violet' },
  { title: '多角色探索', trigger: '至少 2 个角色达到 LV.2', body: '你开始在多个生活场景里留下行动。', earned: roleCountLevel2.value >= 2, icon: Compass, tone: 'blue' },
  { title: '角色进阶', trigger: '至少 2 个角色达到 LV.5', body: '多个角色进入更稳定的成长阶段。', earned: roleCountLevel5.value >= 2, icon: Mountain, tone: 'violet' },
  { title: '满级职业', trigger: '任一角色达到 LV.10', body: '至少一个职业等级已满级。', earned: bestRoleLevel.value >= 10, icon: Medal, tone: 'amber' },
])
const earnedBadgeCount = computed(() => badges.value.filter(badge => badge.earned).length)
const reviewDraft = computed(() => {
  const [steady, heavy, next] = reviewAnswers
  return [
    steady ? `本周最稳定的是：${steady}` : '本周最稳定的是：还需要填写。',
    heavy ? `需要缩小的是：${heavy}` : '需要缩小的是：还需要填写。',
    next ? `下周保留的动作：${next}` : '下周保留的动作：还需要填写。',
  ].join('\n')
})

onMounted(async () => {
  try {
    [data.value, trends.value, roles.value] = await Promise.all([
      api.get<any>('/insights/overview'),
      api.get<TrendRow[]>('/insights/trends'),
      api.get<RoleProgress[]>('/progress/roles'),
    ])
  } catch {
    error.value = '洞察暂时无法加载'
  }
})
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div>
        <p class="eyebrow">只和自己的历史比较</p>
        <h1>洞察</h1>
      </div>
    </header>

    <p v-if="error" class="error">{{ error }}</p>
    <template v-else-if="data">
      <div class="metrics">
        <div><strong>{{ data.effectiveActions }}</strong><span>本周有效行动</span></div>
        <div><strong>{{ Math.round(data.fulfillmentRate * 100) }}%</strong><span>计划兑现率</span></div>
        <div><strong>{{ data.recoveryCount }}</strong><span>恢复次数</span></div>
        <div><strong>{{ data.totalExperience }}</strong><span>累计行动经验</span></div>
      </div>

      <section class="band badge-section" aria-labelledby="badge-title">
        <div class="section-title">
          <div>
            <p class="eyebrow">个人徽章</p>
            <h2 id="badge-title">只记录你的里程碑</h2>
          </div>
          <div class="badge-summary">
            <BadgeCheck :size="20" />
            <span>{{ earnedBadgeCount }} / {{ badges.length }}</span>
          </div>
        </div>
        <div class="badge-grid">
          <article v-for="badge in badges" :key="badge.title" class="badge-card" :class="[badge.tone, { earned: badge.earned }]">
            <div class="badge-icon" aria-hidden="true">
              <component :is="badge.icon" :size="22" />
            </div>
            <div class="badge-copy">
              <div class="badge-title-line">
                <strong>{{ badge.title }}</strong>
                <span>{{ badge.earned ? '已获得' : '未获得' }}</span>
              </div>
              <p>{{ badge.body }}</p>
              <small>触发条件：{{ badge.trigger }}</small>
            </div>
          </article>
        </div>
      </section>

      <section class="band calendar-section" aria-labelledby="calendar-title">
        <div class="section-title">
          <div>
            <p class="eyebrow">月历视图</p>
            <h2 id="calendar-title">行动留下的痕迹</h2>
          </div>
          <CalendarDays :size="20" />
        </div>
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
        <div class="section-title">
          <div>
            <p class="eyebrow">四个职业独立成长</p>
            <h2>职业等级</h2>
          </div>
          <Award :size="20" />
        </div>
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
        <div class="section-title">
          <div>
            <p class="eyebrow">周复盘引导</p>
            <h2 id="review-title">确认后再进入下一周</h2>
          </div>
          <PenLine :size="20" />
        </div>
        <div class="review-grid">
          <div class="review-questions">
            <label class="field" for="steady"><span>这周最稳定的行动是什么？</span><textarea id="steady" v-model="reviewAnswers[0]"></textarea></label>
            <label class="field" for="heavy"><span>哪个任务太大，需要缩小？</span><textarea id="heavy" v-model="reviewAnswers[1]"></textarea></label>
            <label class="field" for="next"><span>下周要保留哪一个动作？</span><textarea id="next" v-model="reviewAnswers[2]"></textarea></label>
          </div>
          <div class="review-draft">
            <strong>复盘草稿</strong>
            <p>{{ reviewDraft }}</p>
          </div>
        </div>
      </section>

      <section class="band">
        <h2>最近趋势</h2>
        <table>
          <caption class="sr-only">每日有效行动与经验</caption>
          <thead><tr><th>日期</th><th>有效行动</th><th>经验</th></tr></thead>
          <tbody><tr v-for="row in trends" :key="row.date"><td>{{ row.date }}</td><td>{{ row.effectiveActions }}</td><td>{{ row.experience }}</td></tr></tbody>
        </table>
        <p v-if="!trends.length" class="empty">完成行动后，这里会出现你的趋势。</p>
      </section>
    </template>
  </section>
</template>

<style scoped>
.metrics { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 24px; }
.metrics div { min-height: 104px; display: grid; align-content: end; gap: 8px; padding: 16px; border: 1px solid var(--border); border-radius: var(--radius); background: color-mix(in srgb, var(--surface) 88%, transparent); box-shadow: var(--shadow-soft); }
.metrics div:last-child { border: 1px solid var(--border); }
.metrics strong { display: block; font-size: 30px; line-height: 1; color: var(--primary); }
.metrics span { font-size: 13px; color: var(--muted); }
.section-title { display: flex; align-items: center; justify-content: space-between; color: var(--primary); margin-bottom: 14px; }
.section-title h2 { margin: 0; font-size: 18px; }
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
.calendar-shell { border: 1px solid var(--border); border-radius: calc(var(--radius) + 4px); background: color-mix(in srgb, var(--surface) 90%, transparent); padding: 16px; overflow-x: auto; box-shadow: var(--shadow-soft); }
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
.role-row:hover { background: var(--surface); border-color: color-mix(in srgb, var(--primary) 22%, var(--border)); box-shadow: var(--shadow-soft); }
.role-level { display: flex; align-items: baseline; gap: 12px; }
.role-level span { font-weight: 800; font-size: 13px; color: var(--amber); }
.role-level strong { font-size: 16px; }
.role-progress { display: grid; gap: 8px; }
.role-progress-copy { display: flex; justify-content: space-between; gap: 16px; color: var(--muted); font-size: 12px; }
.role-progress-copy b { color: var(--primary); }
.review-grid { display: grid; grid-template-columns: minmax(0, 1fr) 300px; gap: 22px; align-items: start; }
.review-questions { display: grid; gap: 12px; }
.field span { font-weight: 650; font-size: 14px; }
.review-draft { min-height: 230px; padding: 16px; border: 1px solid var(--border); border-radius: var(--radius); background: color-mix(in srgb, var(--surface) 88%, transparent); box-shadow: var(--shadow-soft); }
.review-draft p { white-space: pre-wrap; color: var(--muted); line-height: 1.75; }
table { width: 100%; border-collapse: collapse; border: 1px solid var(--border); border-radius: var(--radius); overflow: hidden; background: var(--surface); }
th, td { text-align: left; padding: 11px; border-bottom: 1px solid var(--border); }
tbody tr:hover { background: var(--surface-muted); }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); }
@media (prefers-reduced-motion: no-preference) {
  .metrics div, .badge-grid article, .calendar-day, .role-row, tbody tr, .review-draft { animation: insight-enter var(--motion-medium) ease-out both; }
  .metrics div:nth-child(2), .badge-grid article:nth-child(2), .role-row:nth-child(2), tbody tr:nth-child(2) { animation-delay: 45ms; }
  .metrics div:nth-child(3), .badge-grid article:nth-child(3), .role-row:nth-child(3), tbody tr:nth-child(3) { animation-delay: 90ms; }
  .metrics div:nth-child(4), .badge-grid article:nth-child(4), .role-row:nth-child(4), tbody tr:nth-child(4) { animation-delay: 135ms; }
  .role-row, tbody tr, .calendar-day, .badge-grid article { transition: background-color var(--motion-fast) ease, transform var(--motion-fast) ease; }
  .calendar-day:hover, .badge-grid article:hover { transform: translateY(-2px); }
}
@keyframes insight-enter { from { opacity: 0; transform: translateY(7px); } to { opacity: 1; transform: translateY(0); } }
@media (max-width: 820px) {
  .badge-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .review-grid { grid-template-columns: 1fr; }
}
@media (max-width: 600px) {
  .metrics { grid-template-columns: repeat(2, 1fr); }
  .role-row { grid-template-columns: 1fr; gap: 9px; padding: 16px 2px; }
  .role-progress-copy { gap: 8px; }
}
@media (max-width: 460px) {
  .badge-grid { grid-template-columns: 1fr; }
  .calendar-day { min-height: 34px; }
}
</style>
