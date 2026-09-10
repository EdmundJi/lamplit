<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import GrowthScene from './GrowthScene.vue'

// A decorative street vignette using the same licensed atlas as the town.
// This is deliberately a preview, not a representation of the user's live town.
// The atlas (PNG + JSON) is heavy and not needed for first paint, so loading it
// waits until this component is near the viewport *and* the main thread is idle.
const root = ref<HTMLElement | null>(null)
const canvas = ref<HTMLCanvasElement | null>(null)
const ready = ref(false)
const controller = new AbortController()
let disposed = false
let observer: IntersectionObserver | null = null
let idleHandle: number | null = null
let cancelIdle: ((handle: number) => void) | null = null

type Frame = { x: number; y: number; w: number; h: number }

function clearIdle() {
  if (idleHandle !== null && cancelIdle) cancelIdle(idleHandle)
  idleHandle = null
  cancelIdle = null
}

function scheduleIdle(run: () => void) {
  if (typeof requestIdleCallback === 'function') {
    idleHandle = requestIdleCallback(() => run())
    cancelIdle = handle => cancelIdleCallback(handle)
  } else {
    idleHandle = window.setTimeout(run, 300)
    cancelIdle = handle => window.clearTimeout(handle)
  }
}

async function loadScene() {
  try {
    const response = await fetch('/assets/town/town-atlas.json', { signal: controller.signal })
    if (!response.ok) return
    const atlas = await response.json() as { frames: Record<string, { frame: Frame }> }
    const texture = new Image()
    texture.src = '/assets/town/town-atlas.png'
    await texture.decode()
    if (disposed) return
    const context = canvas.value?.getContext('2d')
    if (!context) return
    context.imageSmoothingEnabled = false
    const sky = context.createLinearGradient(0, 0, 0, 480)
    sky.addColorStop(0, '#293f3c'); sky.addColorStop(.6, '#697764'); sky.addColorStop(1, '#a09b73')
    context.fillStyle = sky; context.fillRect(0, 0, 720, 480)
    context.fillStyle = '#e6d5a0'; context.beginPath(); context.arc(536, 92, 32, 0, Math.PI * 2); context.fill()
    context.fillStyle = '#405c4c'
    context.beginPath(); context.moveTo(0, 230); context.quadraticCurveTo(200, 100, 400, 225); context.quadraticCurveTo(560, 130, 720, 202); context.lineTo(720, 480); context.lineTo(0, 480); context.fill()
    const sprite = (name: string, x: number, y: number, scale = 1) => {
      const frame = atlas.frames[name]?.frame
      if (frame) context.drawImage(texture, frame.x, frame.y, frame.w, frame.h, x, y, frame.w * scale, frame.h * scale)
    }
    context.globalAlpha = .4
    for (let i = 0; i < 12; i++) sprite(`tree_${i % 4 + 1}`, i * 68 - 16, 170 + (i % 3) * 15, 1.35)
    context.globalAlpha = 1
    context.fillStyle = '#53694e'; context.fillRect(0, 310, 720, 170)
    context.fillStyle = '#9b9b83'; context.fillRect(0, 361, 720, 59)
    context.fillStyle = '#c0b89b'; context.fillRect(0, 361, 720, 3)
    context.fillStyle = '#777c69'; context.fillRect(0, 416, 720, 4)
    context.strokeStyle = '#878d77'; context.lineWidth = 1
    for (let x = 0; x < 720; x += 30) { context.beginPath(); context.moveTo(x, 365); context.lineTo(x, 415); context.stroke() }
    context.fillStyle = '#283f36'; context.fillRect(339, 345, 214, 16)
    sprite('roof_4', 315, 92, 1)
    sprite('bakery_1', 315, 224, 1)
    sprite('tree_5', 240, 205, 1.25)
    sprite('tree_1', 564, 252, 1.1)
    sprite('flowerbush_1', 306, 345)
    sprite('flowerbush_1', 555, 345)
    sprite('bench_1', 168, 335, 1.1)
    sprite('mailbox_1', 548, 326, .85)
    sprite('lamp_1', 627, 264, 1)
    const glow = context.createRadialGradient(659, 283, 0, 659, 283, 58)
    glow.addColorStop(0, '#ffe2a878'); glow.addColorStop(1, '#ffe2a800')
    context.fillStyle = glow; context.fillRect(601, 225, 116, 116)
    sprite('dog_basenji_orange_idle_1', 428, 368, .75)
    for (let i = 0; i < 10; i++) sprite('flowers_1', 70 + i * 71, 437 + i % 2 * 14)
    sprite('tree_1', 52, 308, 1.7)
    sprite('tree_5', 668, 316, 1.6)
    const shade = context.createLinearGradient(0, 0, 720, 0)
    shade.addColorStop(0, '#182f29'); shade.addColorStop(.25, '#182f2980'); shade.addColorStop(.6, '#182f2900')
    context.fillStyle = shade; context.fillRect(0, 0, 720, 480)
    ready.value = true
  } catch { /* The existing vector scene remains available if optional town assets are absent. */ }
}

function startWhenIdle() {
  if (disposed) return
  scheduleIdle(() => { if (!disposed) loadScene() })
}

onBeforeUnmount(() => {
  disposed = true
  controller.abort()
  observer?.disconnect()
  observer = null
  clearIdle()
})

onMounted(() => {
  if (typeof IntersectionObserver === 'undefined') {
    startWhenIdle()
    return
  }
  observer = new IntersectionObserver((entries) => {
    if (!entries.some(entry => entry.isIntersecting)) return
    observer?.disconnect()
    observer = null
    startWhenIdle()
  }, { rootMargin: '200px' })
  if (root.value) observer.observe(root.value)
  else startWhenIdle()
})
</script>

<template>
  <div ref="root" class="town-preview" aria-hidden="true">
    <GrowthScene v-if="!ready" class="preview-fallback" />
    <canvas v-show="ready" ref="canvas" width="720" height="480" />
  </div>
</template>

<style scoped>
.town-preview { width: 100%; height: 100%; background: #182f29; }
canvas, .preview-fallback { width: 100%; height: 100%; display: block; object-fit: cover; object-position: 70% center; }
canvas { image-rendering: pixelated; }
</style>
