<script setup lang="ts">
import { goalTemplates, type GoalTemplate } from './goals.logic'

/** A gallery of ready-made goal shapes; picking one hands the template up to be applied. */
const emit = defineEmits<{ apply: [template: GoalTemplate] }>()
</script>

<template>
  <section class="template-band band" aria-labelledby="template-title">
    <div class="template-head">
      <div>
        <p class="eyebrow">目标模板</p>
        <h2 id="template-title">从熟悉的场景开始</h2>
      </div>
      <span>选择后可继续调整</span>
    </div>
    <div class="template-grid">
      <button
        v-for="template in goalTemplates"
        :key="template.roleCode"
        type="button"
        class="template-card"
        @click="emit('apply', template)"
      >
        <span class="template-icon"><component :is="template.icon" :size="20" /></span>
        <span class="template-copy">
          <strong>{{ template.title }}</strong>
          <small>{{ template.description }}</small>
        </span>
        <span class="template-tasks">
          <span v-for="task in template.tasks" :key="task">{{ task }}</span>
        </span>
      </button>
    </div>
  </section>
</template>

<style scoped>
.template-band { padding-top: 26px; }
.template-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
.template-head h2 { margin: 0; font-size: 18px; }
.template-head > span { color: var(--muted); font-size: 13px; white-space: nowrap; }
.template-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.template-card { min-height: 190px; display: grid; grid-template-rows: auto auto 1fr; gap: 12px; align-items: start; padding: 14px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); color: var(--ink); text-align: left; }
.template-card:hover { border-color: color-mix(in srgb, var(--primary) 34%, var(--border)); box-shadow: var(--shadow-soft); }
.template-icon { width: 34px; height: 34px; display: grid; place-items: center; border: 1px solid color-mix(in srgb, var(--accent) 30%, var(--border)); border-radius: var(--radius); color: var(--accent); background: color-mix(in srgb, var(--accent) 8%, var(--surface)); }
.template-copy { display: grid; gap: 7px; }
.template-copy strong { line-height: 1.4; overflow-wrap: anywhere; }
.template-copy small { color: var(--muted); line-height: 1.5; }
.template-tasks { align-self: end; display: grid; gap: 5px; color: var(--muted); font-size: 12px; }
.template-tasks span { padding-left: 9px; border-left: 2px solid color-mix(in srgb, var(--amber) 42%, var(--border)); }
@media (prefers-reduced-motion: no-preference) {
  .template-card { animation: item-enter var(--motion-medium) ease-out both; }
}
@keyframes item-enter { from { opacity: 0; transform: translateY(7px); } to { opacity: 1; transform: translateY(0); } }
@media (max-width: 980px) {
  .template-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 520px) {
  .template-head { align-items: flex-start; flex-direction: column; gap: 7px; }
  .template-grid { grid-template-columns: 1fr; }
}
</style>
