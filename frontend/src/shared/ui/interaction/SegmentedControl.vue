<script setup lang="ts">
/**
 * A mutually-exclusive choice of 2-5 options with the same hand-feel as
 * SnapSlider: a thumb that glides to a click (or a keyboard move) with
 * cubic-out motion, and that can also be dragged and released with a little
 * momentum, landing on the nearest option (never more than one away). The
 * options themselves stay real `role="radio"` buttons — labels, keyboard and
 * screen readers all work exactly like a native radiogroup; only the visual
 * indicator underneath is animated.
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, type Component } from 'vue'
import { motionAllowed } from './motion'
import { glide, projectRelease, releaseVelocity, type DragSample } from './snap-slider'

type Option = { value: string; label: string; icon?: Component }

const props = defineProps<{
  modelValue: string
  options: Option[]
  label: string
  name?: string
}>()
const emit = defineEmits<{ 'update:modelValue': [string, Event?] }>()

const track = ref<HTMLElement>()
const buttonEls: (HTMLElement | null)[] = []
function setButtonRef(el: unknown, index: number) {
  buttonEls[index] = (el as HTMLElement) ?? null
}

type Rect = { left: number; width: number }
const rects = ref<Rect[]>([])
const thumbLeft = ref(0)
const thumbWidth = ref(0)
const dragging = ref(false)

function optionIndex(value: string) {
  const index = props.options.findIndex(option => option.value === value)
  return index === -1 ? 0 : index
}

const currentIndex = ref(optionIndex(props.modelValue))
const focusIndex = ref(currentIndex.value)

let cancelGlide = () => {}

function measure() {
  const trackEl = track.value
  if (!trackEl) return
  const trackRect = trackEl.getBoundingClientRect()
  rects.value = buttonEls.map(el => {
    if (!el) return { left: 0, width: 0 }
    const r = el.getBoundingClientRect()
    return { left: r.left - trackRect.left, width: r.width }
  })
  if (!dragging.value) {
    const rect = rects.value[currentIndex.value]
    if (rect) {
      thumbLeft.value = rect.left
      thumbWidth.value = rect.width
    }
  }
}

function glideThumbTo(index: number) {
  const rect = rects.value[index]
  if (!rect) return
  cancelGlide()
  const startLeft = thumbLeft.value
  const startWidth = thumbWidth.value
  if (startLeft === rect.left && startWidth === rect.width) return
  cancelGlide = glide(
    0,
    1,
    progress => {
      thumbLeft.value = startLeft + (rect.left - startLeft) * progress
      thumbWidth.value = startWidth + (rect.width - startWidth) * progress
    },
    () => {
      thumbLeft.value = rect.left
      thumbWidth.value = rect.width
    },
  )
}

function selectIndex(index: number, event?: Event) {
  const option = props.options[index]
  if (!option) return
  const changed = index !== currentIndex.value
  currentIndex.value = index
  focusIndex.value = index
  if (changed) glideThumbTo(index)
  emit('update:modelValue', option.value, event)
}

watch(() => props.modelValue, value => {
  const index = optionIndex(value)
  if (index === currentIndex.value) return
  currentIndex.value = index
  focusIndex.value = index
  if (!dragging.value) glideThumbTo(index)
})

watch(() => props.options, () => nextTick(measure))

let resizeObserver: ResizeObserver | undefined
onMounted(() => {
  nextTick(measure)
  if (typeof ResizeObserver !== 'undefined' && track.value) {
    resizeObserver = new ResizeObserver(() => measure())
    resizeObserver.observe(track.value)
  }
  window.addEventListener('resize', measure)
})
onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  window.removeEventListener('resize', measure)
  cancelGlide()
})

// --- keyboard: standard radiogroup — arrows move and select, Home/End to the ends, roving tabindex ---
const KEY_DELTA: Record<string, number> = { ArrowLeft: -1, ArrowUp: -1, ArrowRight: 1, ArrowDown: 1 }

function focusButton(index: number) {
  void nextTick(() => buttonEls[index]?.focus())
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Home' || event.key === 'End') {
    event.preventDefault()
    const index = event.key === 'Home' ? 0 : props.options.length - 1
    selectIndex(index, event)
    focusButton(index)
    return
  }
  const delta = KEY_DELTA[event.key]
  if (delta === undefined) return
  event.preventDefault()
  const index = Math.max(0, Math.min(props.options.length - 1, currentIndex.value + delta))
  selectIndex(index, event)
  focusButton(index)
}

// --- drag: pointerdown anywhere on a button can turn into a drag once the
// pointer has actually moved, so a plain tap still behaves like a click. ---
const DRAG_THRESHOLD = 6
let pointerDownX = 0
let dragButtonIndex = -1
let suppressClick = false
let samples: DragSample[] = []

function pointerToIndex(clientX: number) {
  const list = rects.value
  if (!list.length) return currentIndex.value
  const trackRect = track.value?.getBoundingClientRect()
  const x = clientX - (trackRect?.left ?? 0)
  const step = list.length > 1 ? list[1].left - list[0].left : (list[0].width || 1)
  const raw = (x - list[0].left - list[0].width / 2) / (step || 1)
  return Math.max(0, Math.min(props.options.length - 1, raw))
}

function rectAt(continuousIndex: number): Rect {
  const list = rects.value
  const clamped = Math.max(0, Math.min(list.length - 1, continuousIndex))
  const lower = Math.floor(clamped)
  const upper = Math.min(list.length - 1, lower + 1)
  const frac = clamped - lower
  const a = list[lower] ?? { left: 0, width: 0 }
  const b = list[upper] ?? a
  return { left: a.left + (b.left - a.left) * frac, width: a.width + (b.width - a.width) * frac }
}

function onButtonPointerdown(index: number, event: PointerEvent) {
  pointerDownX = event.clientX
  dragButtonIndex = index
  dragging.value = false
  samples = [{ value: currentIndex.value, time: performance.now() }]
  try { (event.currentTarget as Element)?.setPointerCapture?.(event.pointerId) } catch { /* not every environment supports capture */ }
}

function onButtonPointermove(event: PointerEvent) {
  if (dragButtonIndex === -1) return
  if (!dragging.value) {
    if (Math.abs(event.clientX - pointerDownX) < DRAG_THRESHOLD) return
    dragging.value = true
    cancelGlide()
  }
  const continuous = pointerToIndex(event.clientX)
  samples = [...samples.slice(-5), { value: continuous, time: performance.now() }]
  const rect = rectAt(continuous)
  thumbLeft.value = rect.left
  thumbWidth.value = rect.width
}

function endDrag(event: PointerEvent) {
  if (dragButtonIndex === -1) return
  const wasDragging = dragging.value
  dragging.value = false
  dragButtonIndex = -1
  if (!wasDragging) return
  suppressClick = true
  requestAnimationFrame(() => { suppressClick = false })
  const velocity = releaseVelocity(samples)
  const continuous = pointerToIndex(event.clientX)
  const target = Math.round(projectRelease(continuous, velocity, 1, 0, props.options.length - 1))
  const changed = target !== currentIndex.value
  currentIndex.value = target
  focusIndex.value = target
  glideThumbTo(target)
  if (changed) {
    const option = props.options[target]
    if (option) emit('update:modelValue', option.value, event)
  }
}

function onButtonClick(index: number, event: MouseEvent) {
  if (suppressClick) { suppressClick = false; return }
  selectIndex(index, event)
}

const trackLabel = computed(() => props.label)
</script>

<template>
  <div
    ref="track"
    class="segmented"
    role="radiogroup"
    :aria-label="trackLabel"
    :data-name="name"
    :data-dragging="dragging || undefined"
  >
    <button
      v-for="(option, index) in options"
      :key="option.value"
      :ref="el => setButtonRef(el, index)"
      type="button"
      role="radio"
      :aria-checked="index === currentIndex"
      :tabindex="index === focusIndex ? 0 : -1"
      @click="onButtonClick(index, $event)"
      @keydown="onKeydown"
      @pointerdown="onButtonPointerdown(index, $event)"
      @pointermove="onButtonPointermove"
      @pointerup="endDrag"
      @pointercancel="endDrag"
    >
      <component :is="option.icon" v-if="option.icon" :size="16" aria-hidden="true" />
      {{ option.label }}
    </button>
    <!-- After the buttons in DOM order, but z-index keeps it visually behind them
         (see .segmented button below) — this way a `:nth-child` on the buttons
         from outside the component still counts only the buttons. -->
    <span class="segmented-thumb" aria-hidden="true" :style="{ transform: `translateX(${thumbLeft}px)`, width: `${thumbWidth}px` }" />
  </div>
</template>

<style scoped>
.segmented {
  position: relative;
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: minmax(0, 1fr);
  gap: 3px;
  padding: 3px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-muted);
}

.segmented-thumb {
  position: absolute;
  top: 3px;
  bottom: 3px;
  left: 0;
  z-index: 0;
  border-radius: calc(var(--radius) - 2px);
  background: var(--surface-raised);
  pointer-events: none;
}

.segmented button {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-width: 0;
  border: 0;
  border-radius: calc(var(--radius) - 2px);
  background: transparent;
  color: var(--muted);
  padding: 0 10px;
  cursor: pointer;
  touch-action: pan-y;
  transition: color var(--motion-fast) var(--ease);
}

.segmented button:hover { color: var(--ink); }

.segmented button[aria-checked='true'] {
  color: var(--ink);
  font-weight: 700;
  cursor: grab;
}

.segmented[data-dragging] button[aria-checked='true'] { cursor: grabbing; }
</style>
