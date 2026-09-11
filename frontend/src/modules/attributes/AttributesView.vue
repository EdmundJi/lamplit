<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { init, use, type ECharts } from 'echarts/core'
import { RadarChart } from 'echarts/charts'
import { LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { onDataChanged } from '../../shared/data-sync'
import { motionAllowed } from '../../shared/ui/interaction/motion'
import { attributeIcons, attributeProgress as progress, attributeTones, useAttributesOverview } from './attributes.logic'

use([RadarChart, LegendComponent, TooltipComponent, CanvasRenderer])

const { data, loading, error, strongest, load: loadOverview } = useAttributesOverview()

const chartElement = ref<HTMLElement | null>(null)
let chart: ECharts | undefined

function renderChart() {
  if (!chartElement.value || !data.value) return
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

async function load(showLoading = true) {
  await loadOverview(showLoading)
  if (!error.value) {
    await nextTick()
    renderChart()
    window.addEventListener('resize', resizeChart)
  }
}

const stopDataSync = onDataChanged(['attributes', 'tasks'], () => load(false))

onMounted(() => load(true))

onBeforeUnmount(() => {
  stopDataSync()
  window.removeEventListener('resize', resizeChart)
  chart?.dispose()
})
</script>

<template>
  <section class="page page--read attributes-page">
    <header class="page-head">
      <div>
        <h1>成长属性<span v-if="data" class="overall-level muted">综合等级 LV.{{ data.overallLevel }}</span></h1>
      </div>
    </header>

    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-else-if="loading" class="loading-state" role="status">正在整理你的成长轨迹…</div>
    <template v-else-if="data">
      <section class="attribute-overview" aria-label="属性总览">
        <div class="radar-copy">
          <p class="radar-summary">雷达图展示各维度的成长指数，只用于观察自己的变化，不代表能力测评。</p>
          <dl class="stats">
            <div><dt>累计属性经验</dt><dd>{{ data.totalExperience }}</dd></div>
            <div><dt>当前优势</dt><dd>{{ strongest?.name ?? '等待第一次行动' }}</dd></div>
          </dl>
        </div>
        <div ref="chartElement" class="radar-chart" role="img" :aria-label="`成长属性雷达图：${data.attributes.map(item => `${item.name}${item.radarScore}`).join('，')}`"></div>
      </section>

      <section class="band" aria-labelledby="attribute-list-title">
        <h2 id="attribute-list-title" class="section-title">属性明细</h2>
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
.overall-level { margin-left: 12px; font-size: 15px; font-weight: 400; }
.loading-state { min-height: 420px; display: grid; place-items: center; color: var(--muted); }
.attribute-overview { min-height: 360px; display: grid; grid-template-columns: minmax(260px, .55fr) minmax(380px, 1fr); align-items: center; gap: 28px; padding: 8px 0 24px; }
.radar-copy { align-self: center; max-width: 360px; }
.radar-summary { margin: 0 0 20px; color: var(--muted); font-size: 15px; line-height: 1.7; }
.stats { display: flex; flex-wrap: wrap; gap: 0; margin: 0; padding: 0; }
.stats > div { display: flex; flex-direction: column; gap: 4px; padding: 0 20px; }
.stats > div:first-child { padding-left: 0; }
.stats > div + div { border-left: 1px solid var(--border); }
.stats dt { margin: 0; font-size: 12px; color: var(--muted); }
.stats dd { margin: 0; font-size: 20px; font-weight: 600; font-variant-numeric: tabular-nums; color: var(--ink); }
.radar-chart { width: 100%; min-height: 360px; }
.attribute-grid { display: grid; gap: 10px; }
.attribute-card { min-height: 96px; display: grid; grid-template-columns: 26px minmax(0, 1fr) 60px; align-items: center; gap: 14px; padding: 16px; border: 1px solid var(--border); border-left: 4px solid var(--attribute-color); border-radius: var(--radius-panel); background: color-mix(in srgb, var(--surface) 90%, transparent); }
.attribute-icon { width: 22px; height: 22px; display: grid; place-items: center; color: var(--attribute-color); }
.attribute-main { min-width: 0; display: grid; gap: 8px; }
.attribute-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.attribute-heading > div { min-width: 0; display: flex; align-items: baseline; gap: 8px; }
.attribute-heading strong { font-size: 16px; }
.attribute-heading small, .attribute-progress-copy { color: var(--muted); font-size: 12px; }
.attribute-heading b { color: var(--ink); font-size: 12px; }
.attribute-main p { margin: 0; color: var(--muted); font-size: 13px; line-height: 1.5; }
.attribute-progress-copy { display: flex; justify-content: space-between; gap: 10px; }
.attribute-card .progress > span { background: var(--attribute-color); }
.score { display: grid; justify-items: center; gap: 4px; color: var(--attribute-color); }
.score b { font-size: 20px; font-weight: 600; line-height: 1; font-variant-numeric: tabular-nums; color: var(--ink); }
.score small { color: var(--muted); font-size: 10px; }
@media (prefers-reduced-motion: no-preference) {
  .attribute-card { animation: attribute-enter var(--motion-medium) var(--ease) both; transition: border-color var(--motion-fast) var(--ease); }
  .attribute-card:hover { border-color: color-mix(in srgb, var(--attribute-color) 34%, var(--border)); }
  .attribute-card:nth-child(2) { animation-delay: 55ms; }
  .attribute-card:nth-child(3) { animation-delay: 110ms; }
  .attribute-card:nth-child(4) { animation-delay: 165ms; }
  .attribute-card:nth-child(5) { animation-delay: 220ms; }
}
@keyframes attribute-enter { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: translateY(0); } }
@media (max-width: 900px) { .attribute-overview { grid-template-columns: minmax(0, 1fr); } .radar-copy { max-width: none; } .radar-chart { min-height: 330px; } }
@media (max-width: 560px) { .radar-chart { min-height: 290px; } .attribute-card { grid-template-columns: 22px minmax(0, 1fr); } .score { grid-column: 1 / -1; grid-template-columns: auto auto; justify-content: end; align-items: baseline; } .attribute-heading > div { align-items: start; flex-direction: column; gap: 2px; } }
</style>
