<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { motionAllowed, motionDuration } from './motion'
import { projectRelease, releaseVelocity, snapToStep, type DragSample } from './snap-slider'

/**
 * A native range input that drags continuously and lands on a tick. The
 * element stays a real slider, so labels, focus and screen readers behave
 * exactly as before; only the release is ours.
 */
const props = defineProps<{
  modelValue: number
  min: number
  max: number
  step: number
  valueText?: string
}>()
const emit = defineEmits<{ 'update:modelValue': [number] }>()

const display = ref(props.modelValue)
const dragging = ref(false)
let settling = false
let frame = 0
let samples: DragSample[] = []

watch(() => props.modelValue, value => { if (!dragging.value && !settling) display.value = value })

function clamp(value: number) {
  return Math.max(props.min, Math.min(props.max, value))
}

function commit(value: number) {
  cancelAnimationFrame(frame)
  settling = false
  display.value = clamp(value)
  emit('update:modelValue', display.value)
}

function glideTo(target: number) {
  const start = display.value
  if (start === target || !motionAllowed()) {
    commit(target)
    return
  }
  settling = true
  // Timed from the first frame, so the clock always matches the one the frames carry.
  let began = 0
  const duration = motionDuration('medium')
  const tick = (now: number) => {
    began ||= now
    const progress = Math.min(1, (now - began) / duration)
    const eased = 1 - (1 - progress) ** 3
    const value = Math.round(start + (target - start) * eased)
    if (value !== display.value) {
      display.value = value
      emit('update:modelValue', value)
    }
    if (progress < 1) frame = requestAnimationFrame(tick)
    else commit(target)
  }
  frame = requestAnimationFrame(tick)
}

function onPointerdown() {
  cancelAnimationFrame(frame)
  settling = false
  dragging.value = true
  samples = []
}

function onInput(event: Event) {
  const value = Number((event.target as HTMLInputElement).value)
  if (!dragging.value) {
    commit(snapToStep(value, props.step, props.min, props.max))
    return
  }
  display.value = value
  samples = [...samples.slice(-5), { value, time: performance.now() }]
  emit('update:modelValue', value)
}

function onRelease() {
  if (!dragging.value) return
  dragging.value = false
  const velocity = releaseVelocity(samples)
  glideTo(projectRelease(display.value, velocity, props.step, props.min, props.max))
}

const arrows: Record<string, number> = { ArrowLeft: -1, ArrowDown: -1, ArrowRight: 1, ArrowUp: 1, PageDown: -2, PageUp: 2 }

function onKeydown(event: KeyboardEvent) {
  // Native arrow keys would move by one raw unit; keyboard users deserve the ticks too.
  if (event.key === 'Home' || event.key === 'End') {
    event.preventDefault()
    commit(event.key === 'Home' ? props.min : props.max)
    return
  }
  const direction = arrows[event.key]
  if (direction === undefined) return
  event.preventDefault()
  commit(snapToStep(display.value, props.step, props.min, props.max) + direction * props.step)
}

onBeforeUnmount(() => cancelAnimationFrame(frame))
</script>

<template>
  <input
    type="range"
    step="1"
    :min="min"
    :max="max"
    :value="display"
    :aria-valuetext="valueText"
    :data-sliding="dragging || undefined"
    @pointerdown="onPointerdown"
    @pointerup="onRelease"
    @pointercancel="onRelease"
    @input="onInput"
    @keydown="onKeydown"
  >
</template>

<style scoped>
input[data-sliding] { cursor: grabbing; }
</style>
