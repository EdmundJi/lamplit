<script setup lang="ts">
import { inject, onBeforeUnmount, onMounted } from 'vue'
import { Radar } from 'lucide-vue-next'
import { onDataChanged } from '../../../../shared/data-sync'
import { attributeIcons, attributeProgress as progress, attributeTones, useAttributesOverview } from '../../../attributes/attributes.logic'
import { worldBridgeKey } from '../panel.types'
import { openFullPage } from './shared'

const FULL_PAGE = '/attributes'

const bridge = inject(worldBridgeKey, undefined)

const { data, loading, error, strongest, load } = useAttributesOverview()

const stopDataSync = onDataChanged(['attributes'], () => load(false))

onMounted(() => load(true))
onBeforeUnmount(stopDataSync)
</script>

<template>
  <section class="world-panel attributes-panel">
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="loading" class="empty">正在整理属性…</p>
    <template v-else-if="data && data.attributes.length">
      <div class="overall">
        <span>综合等级</span>
        <strong>LV.{{ data.overallLevel }}</strong>
        <span class="muted">累计经验 {{ data.totalExperience }} · 当前优势 {{ strongest?.name ?? '—' }}</span>
      </div>
      <ul class="attribute-list">
        <li v-for="row in data.attributes" :key="row.code" class="attribute-row">
          <div class="attribute-head">
            <span class="attribute-name"><component :is="attributeIcons[row.code]" :size="14" :style="{ color: attributeTones[row.code] }" />{{ row.name }}</span>
            <span class="muted">LV.{{ row.level }}</span>
          </div>
          <div class="progress"><span :style="{ width: `${progress(row)}%`, background: attributeTones[row.code] }" /></div>
          <div class="attribute-foot">
            <span class="muted">{{ row.nextLevelExperience ? `距升级 ${row.experienceToNextLevel}` : '已达当前上限' }}</span>
            <span class="muted">{{ row.experience }} 经验</span>
          </div>
          <p class="attribute-description">{{ row.description }}</p>
        </li>
      </ul>
    </template>
    <div v-else class="empty">
      <Radar :size="20" />
      <p>完成任务后，五项能力的成长会显示在这里。</p>
    </div>

    <footer class="panel-footer">
      <button class="secondary" type="button" @click="openFullPage(bridge, FULL_PAGE)">打开完整页面</button>
    </footer>
  </section>
</template>

<style scoped>
.world-panel { display: grid; gap: 12px; width: 100%; color: var(--ink); }
.overall { display: flex; flex-wrap: wrap; align-items: baseline; gap: 8px; padding: 10px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); font-size: 12px; }
.overall strong { font-size: 16px; }
.attribute-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 12px; max-height: 320px; overflow-y: auto; }
.attribute-row { display: grid; gap: 5px; padding: 8px 0; border-bottom: 1px solid var(--border); }
.attribute-row:last-child { border-bottom: 0; }
.attribute-head { display: flex; justify-content: space-between; font-size: 12px; }
.attribute-name { display: inline-flex; align-items: center; gap: 5px; font-weight: 700; }
.attribute-foot { display: flex; justify-content: space-between; font-size: 11px; }
.attribute-description { margin: 0; color: var(--muted); font-size: 11px; line-height: 1.5; }
.muted { color: var(--muted); }
.panel-footer { display: flex; justify-content: flex-end; }
</style>
