<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { glide, projectRelease, releaseVelocity, snapToStep, type DragSample } from './snap-slider'

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
let cancelGlide = () => {}
let samples: DragSample[] = []

watch(() => props.modelValue, value => { if (!dragging.value && !settling) display.value = value })

function clamp(value: number) {
  return Math.max(props.min, Math.min(props.max, value))
}

function commit(value: number) {
  cancelGlide()
  settling = false
  display.value = clamp(value)
  emit('update:modelValue', display.value)
}

function glideTo(target: number) {
  cancelGlide()
  settling = true
  cancelGlide = glide(
    display.value,
    target,
    value => {
      const rounded = Math.round(value)
      if (rounded !== display.value) {
        display.value = rounded
        emit('update:modelValue', rounded)
      }
    },
    () => commit(target),
  )
}

function onPointerdown() {
  cancelGlide()
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

onBeforeUnmount(() => cancelGlide())
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
