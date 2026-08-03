<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { BarChart3, Brain, BriefcaseBusiness, HeartHandshake, Sparkles, Sprout } from 'lucide-vue-next'
import { init, use, type ECharts } from 'echarts/core'
import { RadarChart } from 'echarts/charts'
import { LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { api } from '../../shared/api/client'

use([RadarChart, LegendComponent, TooltipComponent, CanvasRenderer])

type AttributeRow = {
  code: string
  name: string
  dimensionName: string
  description: string
  experience: number
  level: number
  radarScore: number
  currentLevelExperience: number
  nextLevelExperience?: number | null
  experienceToNextLevel: number
}

type AttributesOverview = {
  totalExperience: number
  overallLevel: number
  attributes: AttributeRow[]
}

const attributeIcons: Record<string, unknown> = {
  KNOWLEDGE: Brain,
  HEALTH: Sprout,
  CAREER: BriefcaseBusiness,
  RELATIONSHIP: HeartHandshake,
  WELLBEING: Sparkles,
}

const attributeTones: Record<string, string> = {
  KNOWLEDGE: '#c85f47',
  HEALTH: '#4f856a',
  CAREER: '#b67a22',
  RELATIONSHIP: '#3f7f8f',
  WELLBEING: '#9b5f77',
}

const chartElement = ref<HTMLElement | null>(null)
const data = ref<AttributesOverview | null>(null)
const loading = ref(true)
const error = ref('')
let chart: ECharts | undefined

const strongest = computed(() => data.value?.attributes.reduce<AttributeRow | null>(
  (best, item) => !best || item.experience > best.experience ? item : best,
  null,
))

function progress(row: AttributeRow) {
  if (!row.nextLevelExperience) return 100
  const span = row.nextLevelExperience - row.currentLevelExperience
  return span <= 0 ? 100 : Math.max(0, Math.min(100, Math.round((row.experience - row.currentLevelExperience) * 100 / span)))
}

function renderChart() {
  if (!chartElement.value || !data.value) return
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
      formatter: () => data.value?.attributes.map(item => `${item.name}：${item.radarScore}`).join('<br>') ?? '',
    },
    radar: {
      radius: '67%',
      splitNumber: 4,
      indicator: data.value.attributes.map(item => ({ name: item.name, max: 100 })),
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
      data: [{ name: '成长指数', value: data.value.attributes.map(item => item.radarScore) }],
    }],
    textStyle: { color: muted },
  })
}

function resizeChart() {
  chart?.resize()
}

onMounted(async () => {
  try {
    data.value = await api.get<AttributesOverview>('/insights/attributes')
    loading.value = false
    await nextTick()
    renderChart()
    window.addEventListener('resize', resizeChart)
  } catch {
    error.value = '属性数据暂时无法加载'
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
  <section class="page attributes-page">
    <header class="page-head">
      <div>
        <p class="eyebrow">每次完成都会留下真实增量</p>
        <h1>成长属性</h1>
      </div>
      <div v-if="data" class="overall-level"><span>综合等级</span><strong>LV.{{ data.overallLevel }}</strong></div>
    </header>

    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-else-if="loading" class="loading-state" role="status">正在整理你的成长轨迹…</div>
    <template v-else-if="data">
      <section class="attribute-overview" aria-labelledby="radar-title">
        <div class="radar-copy">
          <p class="eyebrow">总体属性</p>
          <h2 id="radar-title">你的行动正在长成形状</h2>
          <p>雷达图展示各维度的成长指数，原始经验仍来自任务完成记录。它只用于观察自己的变化，不代表能力测评。</p>
          <dl>
            <div><dt>累计属性经验</dt><dd>{{ data.totalExperience }}</dd></div>
            <div><dt>当前优势</dt><dd>{{ strongest?.name ?? '等待第一次行动' }}</dd></div>
          </dl>
        </div>
        <div ref="chartElement" class="radar-chart" role="img" :aria-label="`成长属性雷达图：${data.attributes.map(item => `${item.name}${item.radarScore}`).join('，')}`"></div>
      </section>

      <section class="band" aria-labelledby="attribute-list-title">
        <div class="section-title">
          <div><p class="eyebrow">属性明细</p><h2 id="attribute-list-title">完成相关任务即可增加</h2></div>
          <BarChart3 :size="20" />
        </div>
        <div class="attribute-grid">
          <article v-for="item in data.attributes" :key="item.code" class="attribute-card" :style="{ '--attribute-color': attributeTones[item.code] }">
            <div class="attribute-icon"><component :is="attributeIcons[item.code]" :size="22" /></div>
            <div class="attribute-main">
              <div class="attribute-heading"><div><strong>{{ item.name }}</strong><small>{{ item.dimensionName }}维度</small></div><b>LV.{{ item.level }}</b></div>
              <p>{{ item.description }}</p>
              <div class="attribute-progress-copy"><span>{{ item.experience }} 经验</span><span>{{ item.nextLevelExperience ? `距升级 ${item.experienceToNextLevel}` : '已达当前上限' }}</span></div>
              <div class="progress" :aria-label="`${item.name}升级进度 ${progress(item)}%`"><span :style="{ width: `${progress(item)}%` }"></span></div>
            </div>
            <span class="score"><b>{{ item.radarScore }}</b><small>成长指数</small></span>
          </article>
        </div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.attributes-page { max-width: 1120px; }
.overall-level { display: grid; justify-items: end; gap: 2px; }
.overall-level span { color: var(--muted); font-size: 12px; }
.overall-level strong { color: var(--primary); font-size: 23px; }
.loading-state { min-height: 420px; display: grid; place-items: center; color: var(--muted); }
.attribute-overview { min-height: 390px; display: grid; grid-template-columns: minmax(260px, .72fr) minmax(420px, 1fr); align-items: center; gap: 20px; padding: 24px 0 30px; border-top: 1px solid var(--border); }
.radar-copy { align-self: center; max-width: 390px; }
.radar-copy h2 { margin: 0; font-size: 24px; }
.radar-copy > p:not(.eyebrow) { margin: 12px 0 22px; color: var(--muted); line-height: 1.75; }
.radar-copy dl { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; margin: 0; }
.radar-copy dl div { padding: 13px; border-left: 3px solid var(--primary); background: color-mix(in srgb, var(--primary-soft) 45%, var(--surface)); }
.radar-copy dt { color: var(--muted); font-size: 12px; }
.radar-copy dd { margin: 5px 0 0; color: var(--ink); font-size: 18px; font-weight: 800; }
.radar-chart { width: 100%; min-height: 360px; }
.section-title { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; color: var(--primary); }
.section-title h2 { margin: 0; font-size: 18px; color: var(--ink); }
.attribute-grid { display: grid; gap: 10px; }
.attribute-card { min-height: 116px; display: grid; grid-template-columns: 48px minmax(0, 1fr) 72px; align-items: center; gap: 14px; padding: 15px; border: 1px solid var(--border); border-left: 4px solid var(--attribute-color); border-radius: var(--radius); background: color-mix(in srgb, var(--surface) 90%, transparent); box-shadow: var(--shadow-soft); }
.attribute-icon { width: 44px; height: 44px; display: grid; place-items: center; border-radius: 14px; color: var(--attribute-color); background: color-mix(in srgb, var(--attribute-color) 11%, var(--surface)); }
.attribute-main { min-width: 0; display: grid; gap: 8px; }
.attribute-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.attribute-heading > div { min-width: 0; display: flex; align-items: baseline; gap: 8px; }
.attribute-heading strong { font-size: 16px; }
.attribute-heading small, .attribute-progress-copy { color: var(--muted); font-size: 12px; }
.attribute-heading b { color: var(--attribute-color); font-size: 12px; }
.attribute-main p { margin: 0; color: var(--muted); font-size: 13px; line-height: 1.5; }
.attribute-progress-copy { display: flex; justify-content: space-between; gap: 10px; }
.attribute-card .progress > span { background: var(--attribute-color); }
.score { display: grid; justify-items: center; gap: 4px; color: var(--attribute-color); }
.score b { font-size: 26px; line-height: 1; }
.score small { color: var(--muted); font-size: 10px; }
@media (prefers-reduced-motion: no-preference) {
  .attribute-card { animation: attribute-enter var(--motion-medium) ease-out both; transition: transform var(--motion-fast) ease, box-shadow var(--motion-fast) ease; }
  .attribute-card:hover { transform: translateX(3px); box-shadow: var(--shadow); }
  .attribute-card:nth-child(2) { animation-delay: 55ms; }
  .attribute-card:nth-child(3) { animation-delay: 110ms; }
  .attribute-card:nth-child(4) { animation-delay: 165ms; }
  .attribute-card:nth-child(5) { animation-delay: 220ms; }
  .attribute-icon { animation: icon-float 3.2s ease-in-out infinite; }
}
@keyframes attribute-enter { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: translateY(0); } }
@keyframes icon-float { 0%, 100% { transform: translateY(0); } 50% { transform: translateY(-3px); } }
@media (max-width: 850px) { .attribute-overview { grid-template-columns: 1fr; } .radar-copy { max-width: none; } .radar-chart { min-height: 330px; } }
@media (max-width: 560px) { .attribute-overview { padding-top: 18px; } .radar-chart { min-height: 290px; } .attribute-card { grid-template-columns: 44px minmax(0, 1fr); } .score { grid-column: 1 / -1; grid-template-columns: auto auto; justify-content: end; align-items: baseline; } .attribute-heading > div { align-items: start; flex-direction: column; gap: 2px; } }
</style>
