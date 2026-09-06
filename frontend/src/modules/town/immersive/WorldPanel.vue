<script setup lang="ts">
/**
 * 沉浸式小镇里的一扇「窗口」：标题栏 + 可拖动 + 最小化/关闭 + 内容异步加载。
 * 外壳只管窗口的位置/层级（由 immersive.store 记着），内容是面板清单里各自的组件。
 */
import { defineAsyncComponent, h, ref, onBeforeUnmount } from 'vue'
import { Minus, X } from 'lucide-vue-next'
import type { WorldPanelDef } from './panel.types'

const props = defineProps<{ def: WorldPanelDef; x: number; y: number; z: number }>()
const emit = defineEmits<{ close: []; minimize: []; focus: []; move: [x: number, y: number] }>()

const LoadingStub = { render: () => h('p', { class: 'panel-status' }, '正在打开…') }
const ErrorStub = { render: () => h('p', { class: 'panel-status panel-status--error' }, '这个面板暂时打不开，稍后再试试。') }
// props.def 在这扇窗口的生命周期里不会换成另一个面板（v-for 用 key 保证），所以只在 setup 时算一次。
const body = defineAsyncComponent({ loader: props.def.loader, loadingComponent: LoadingStub, errorComponent: ErrorStub, delay: 100, timeout: 20000 })

const panelEl = ref<HTMLElement | null>(null)
let dragging = false
let startX = 0
let startY = 0
let originX = 0
let originY = 0

function onHeaderPointerDown(event: PointerEvent) {
  if ((event.target as HTMLElement).closest('button')) return
  emit('focus')
  dragging = true
  startX = event.clientX
  startY = event.clientY
  originX = props.x
  originY = props.y
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp, { once: true })
}

function onPointerMove(event: PointerEvent) {
  if (!dragging) return
  const width = panelEl.value?.offsetWidth ?? 320
  const height = panelEl.value?.offsetHeight ?? 200
  const maxX = Math.max(0, window.innerWidth - width)
  const maxY = Math.max(0, window.innerHeight - height)
  const nextX = Math.min(Math.max(0, originX + (event.clientX - startX)), maxX)
  const nextY = Math.min(Math.max(0, originY + (event.clientY - startY)), maxY)
  emit('move', nextX, nextY)
}

function onPointerUp() {
  dragging = false
  window.removeEventListener('pointermove', onPointerMove)
}
onBeforeUnmount(() => { window.removeEventListener('pointermove', onPointerMove); window.removeEventListener('pointerup', onPointerUp) })
</script>

<template>
  <section
    ref="panelEl"
    class="world-panel world-window"
    :class="`is-${def.size}`"
    :style="{ left: `${x}px`, top: `${y}px`, zIndex: z }"
    role="dialog"
    :aria-label="def.title"
    @pointerdown="emit('focus')"
    @keydown.esc.stop="emit('close')"
    @keydown.stop
  >
    <header class="world-panel-head" @pointerdown="onHeaderPointerDown">
      <component :is="def.icon" :size="18" aria-hidden="true" />
      <div class="world-panel-titles">
        <strong>{{ def.title }}</strong>
        <small>{{ def.subtitle }}</small>
      </div>
      <button class="icon-button" type="button" aria-label="最小化" @click="emit('minimize')"><Minus :size="15" /></button>
      <button class="icon-button" type="button" aria-label="关闭" @click="emit('close')"><X :size="15" /></button>
    </header>
    <div class="world-panel-body">
      <component :is="body" />
    </div>
  </section>
</template>

<style scoped>
.world-window { color: var(--ink); position: fixed; display: flex; flex-direction: column; max-height: min(78vh, 640px); border: 1px solid color-mix(in srgb, var(--border) 60%, transparent); border-radius: var(--radius-panel); background: color-mix(in srgb, var(--surface) 94%, transparent); backdrop-filter: blur(14px); box-shadow: var(--shadow); overflow: hidden; }
.world-window.is-compact { width: min(360px, calc(100vw - 24px)); }
.world-window.is-wide { width: min(560px, calc(100vw - 24px)); }
.world-panel-head { flex-shrink: 0; display: flex; align-items: center; gap: 10px; padding: 12px 8px 12px 14px; border-bottom: 1px solid var(--border); background: var(--surface-muted); cursor: grab; touch-action: none; }
.world-panel-head:active { cursor: grabbing; }
.world-panel-titles { min-width: 0; flex: 1; display: grid; }
.world-panel-titles strong { font-size: 14px; }
.world-panel-titles small { color: var(--muted); font-size: 11px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.world-panel-head .icon-button { width: 28px; min-height: 28px; height: 28px; padding: 0; }
.world-panel-body { min-height: 0; overflow: auto; padding: 16px; }
.world-panel-body :deep(.panel-status) { margin: 0; padding: 24px 16px; text-align: center; color: var(--muted); font-size: 13px; }
.world-panel-body :deep(.panel-status--error) { color: var(--danger); }
@media (prefers-reduced-motion: reduce) { .world-window { transition: none; } }
@media (max-width: 760px) {
  .world-window,
  .world-window.is-compact,
  .world-window.is-wide { left: 0 !important; right: 0; bottom: 0; top: auto !important; width: 100%; max-height: 72vh; border-radius: var(--radius-panel) var(--radius-panel) 0 0; }
}
</style>
