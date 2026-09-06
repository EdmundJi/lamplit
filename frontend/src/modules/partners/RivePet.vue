<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Alignment, Fit, Layout, Rive, RuntimeLoader, StateMachineInputType, type StateMachineInput } from '@rive-app/canvas'
import { ChevronUp, Palette, PawPrint, RotateCcw } from 'lucide-vue-next'
import { petVariants, variantAt, variantCountFor, type PetInputSpec, type PetReaction, type PetVariant } from './pet-variants'

RuntimeLoader.setWasmUrl('/assets/rive/rive.wasm')
RuntimeLoader.setWasmFallbackUrl('/assets/rive/rive_fallback.wasm')

const props = withDefaults(defineProps<{
  speciesCode: string
  name: string
  variantIndex?: number
  showVariantSwitcher?: boolean
}>(), {
  variantIndex: 0,
  showVariantSwitcher: false,
})

const emit = defineEmits<{
  'select-variant': [index: number]
}>()

const canvas = ref<HTMLCanvasElement | null>(null)
const loading = ref(false)
const failed = ref(false)
const reacting = ref(false)
const menuOpen = ref(false)
const currentReaction = ref<PetReaction>('greet')
const variantCount = computed(() => variantCountFor(props.speciesCode))
const activeIndex = computed(() => (props.variantIndex % Math.max(1, variantCount.value)))
const variantList = computed(() => petVariants[props.speciesCode as keyof typeof petVariants] ?? [])
const animation = computed(() => variantAt(props.speciesCode, props.variantIndex))

let rive: Rive | null = null
let inputs: StateMachineInput[] = []
let resizeObserver: ResizeObserver | null = null
let reactionTimer: number | undefined
let inputTimers: number[] = []
let motionObserver: MutationObserver | null = null
function reduceMotion() {
  return ['off', 'reduced'].includes(document.documentElement.dataset.motion ?? '') || (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false)
}
function syncPlayback() {
  if (!rive) return
  if (reduceMotion() || document.hidden) rive.pause()
  else rive.play()
}

function cleanup() {
  window.clearTimeout(reactionTimer)
  inputTimers.forEach(timer => window.clearTimeout(timer))
  inputTimers = []
  resizeObserver?.disconnect()
  resizeObserver = null
  inputs = []
  rive?.cleanup()
  rive = null
}

async function initialize() {
  cleanup()
  failed.value = false

  const config = animation.value
  if (!config || config.kind === 'image') {
    loading.value = false
    return
  }

  loading.value = true
  await nextTick()
  if (!canvas.value || animation.value !== config) return

  const prefersReducedMotion = reduceMotion()
  const canvasElement = canvas.value

  rive = new Rive({
    src: config.src,
    canvas: canvasElement,
    artboard: config.artboard,
    stateMachines: config.stateMachine,
    autoplay: !prefersReducedMotion,
    layout: new Layout({ fit: Fit.Contain, alignment: Alignment.Center }),
    isTouchScrollEnabled: true,
    onLoad: () => {
      if (!rive || animation.value !== config) return
      inputs = rive.stateMachineInputs(config.stateMachine)
      rive.resizeDrawingSurfaceToCanvas()
      loading.value = false
    },
    onLoadError: () => {
      loading.value = false
      failed.value = true
    },
  })

  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => rive?.resizeDrawingSurfaceToCanvas())
    resizeObserver.observe(canvasElement)
  }
}

function input(name: string) {
  return inputs.find(item => item.name === name)
}

function pulseBoolean(name: string, duration = 520) {
  const target = input(name)
  if (!target || target.type !== StateMachineInputType.Boolean) return false
  target.value = true
  inputTimers.push(window.setTimeout(() => {
    if (target.type === StateMachineInputType.Boolean) target.value = false
  }, duration))
  return true
}

function fireTrigger(name: string) {
  const target = input(name)
  if (!target || target.type !== StateMachineInputType.Trigger) return false
  target.fire()
  return true
}

function setNumber(name: string, value: number) {
  const target = input(name)
  if (!target || target.type !== StateMachineInputType.Number) return false
  target.value = value
  return true
}

function activateInput(name: string, duration = 520) {
  return fireTrigger(name) || pulseBoolean(name, duration)
}

function repeatInput(name: string, delay = 180) {
  inputTimers.push(window.setTimeout(() => activateInput(name), delay))
}

function applySpec(spec: PetInputSpec) {
  if (spec.mode === 'fire') fireTrigger(spec.name)
  else if (spec.mode === 'pulse') pulseBoolean(spec.name, spec.value ?? 520)
  else if (spec.mode === 'set') setNumber(spec.name, spec.value ?? 0)
  else activateInput(spec.name, spec.value ?? 520)
}

function react(kind: PetReaction = 'greet') {
  const config = animation.value
  if (!config) return
  currentReaction.value = kind
  reacting.value = true
  window.clearTimeout(reactionTimer)
  const duration = kind === 'comfort' ? 900 : kind === 'celebrate' ? 820 : 720
  reactionTimer = window.setTimeout(() => { reacting.value = false }, duration)

  if (config.kind === 'image' || !rive || reduceMotion()) return
  rive.play(config.stateMachine)

  for (const spec of config.reactions?.[kind] ?? []) {
    if (spec.delay) repeatInput(spec.name, spec.delay)
    else applySpec(spec)
  }
}

function selectVariant(index: number) {
  menuOpen.value = false
  if (index === activeIndex.value) return
  emit('select-variant', index)
}

function onDocumentPointerDown() {
  menuOpen.value = false
}

watch(() => [props.speciesCode, props.variantIndex] as const, initialize, { immediate: true })
onMounted(() => {
  document.addEventListener('pointerdown', onDocumentPointerDown)
  document.addEventListener('visibilitychange', syncPlayback)
  motionObserver = new MutationObserver(syncPlayback)
  motionObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-motion'] })
})
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocumentPointerDown)
  document.removeEventListener('visibilitychange', syncPlayback)
  motionObserver?.disconnect()
  cleanup()
})

defineExpose({ react })
</script>

<template>
  <div class="rive-pet-wrap">
    <div
      class="rive-pet"
      :class="{ reacting, unavailable: !animation || failed }"
      :data-species="speciesCode"
      :data-variant="animation?.id"
      :data-reaction="currentReaction"
    >
      <canvas v-if="animation?.kind === 'rive' && !failed" ref="canvas">
        {{ name }}的动态伙伴形象
      </canvas>

      <img v-else-if="animation?.kind === 'image'" class="static-pet" :src="animation.src" :style="animation.hue ? { filter: `drop-shadow(0 18px 12px rgb(24 35 27 / 16%)) hue-rotate(${animation.hue})` } : undefined" alt="">

      <span v-if="loading" class="pet-status" role="status">正在唤醒 {{ name }}…</span>

      <span v-else-if="!animation" class="pet-fallback">
        <PawPrint :size="42" stroke-width="1.6" />
        <strong>{{ name }}</strong>
        <small>动态形象正在制作</small>
      </span>

      <span v-else-if="failed" class="pet-fallback">
        <RotateCcw :size="38" stroke-width="1.6" />
        <strong>动画加载失败</strong>
        <small>刷新页面后重试</small>
      </span>

      <span v-if="animation && !failed" class="asset-credit">{{ animation.kind === 'rive' ? 'Rive · ' : '' }}{{ animation.credit }}</span>
    </div>

    <div
      v-if="showVariantSwitcher && variantCount > 1 && animation && !failed"
      class="variant-switcher"
      :data-open="menuOpen"
    >
      <button
        class="variant-switcher-trigger"
        type="button"
        :aria-label="`选择${name}的素材，当前是${animation.label}，共${variantCount}个`"
        :aria-expanded="menuOpen"
        @click.stop="menuOpen = !menuOpen"
      >
        <Palette :size="13" />
        <span>{{ animation.label }}</span>
        <ChevronUp :size="12" :class="{ flipped: !menuOpen }" />
      </button>
      <Transition name="variant-menu">
        <div v-if="menuOpen" class="variant-menu" role="menu" :aria-label="`选择${name}的素材`">
          <button
            v-for="(variant, index) in variantList"
            :key="variant.id"
            type="button"
            role="menuitem"
            :class="{ active: index === activeIndex }"
            @click.stop="selectVariant(index)"
          >
            <span class="variant-dot" aria-hidden="true" />
            <span>{{ variant.label }}</span>
            <em v-if="index === activeIndex">当前</em>
          </button>
        </div>
      </Transition>
    </div>
  </div>
</template>

<style scoped>
.rive-pet-wrap {
  position: absolute;
  inset: 0;
  border-radius: inherit;
}

.rive-pet {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  padding: 0;
  overflow: hidden;
  border: 0;
  border-radius: inherit;
  background: var(--pet-scene, #f4f1eb);
  color: var(--ink);
}

.rive-pet canvas { display: block; width: 100%; height: 100%; }
.static-pet { position: absolute; inset: 0; display: block; width: min(58%, 240px); height: min(68%, 240px); margin: auto; object-fit: contain; filter: drop-shadow(0 18px 12px rgb(24 35 27 / 16%)); }

.pet-status,
.pet-fallback {
  position: absolute;
  inset: 0;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 8px;
  padding: 24px;
  background: color-mix(in srgb, var(--surface) 92%, transparent);
  color: var(--muted);
}

.pet-fallback strong { color: var(--ink); font-size: 17px; }
.pet-fallback small { font-size: 13px; }
.pet-fallback svg { color: var(--primary); }

.asset-credit {
  position: absolute;
  right: 10px;
  bottom: 9px;
  max-width: calc(100% - 20px);
  padding: 4px 7px;
  border-radius: 4px;
  background: rgb(18 23 30 / 68%);
  color: white;
  font-size: 10px;
  line-height: 1.2;
}

.variant-switcher {
  position: absolute;
  z-index: 7;
  left: 12px;
  bottom: 10px;
}

.variant-switcher-trigger {
  min-height: 28px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 10px;
  border: 1px solid rgb(255 255 255 / 22%);
  border-radius: 999px;
  background: rgb(18 23 30 / 68%);
  color: white;
  font-size: 11px;
  font-weight: 800;
  line-height: 1;
  backdrop-filter: blur(4px);
  cursor: pointer;
}

.variant-switcher-trigger:hover { background: rgb(18 23 30 / 82%); }
.variant-switcher-trigger svg { flex: 0 0 auto; color: #ffd9a0; }
.variant-switcher-trigger .flipped { transform: rotate(180deg); }
.variant-switcher-trigger > span { min-width: 0; max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.variant-menu {
  position: absolute;
  left: 0;
  bottom: calc(100% + 6px);
  width: 148px;
  display: grid;
  gap: 2px;
  padding: 5px;
  border: 1px solid rgb(255 255 255 / 14%);
  border-radius: 8px;
  background: rgb(24 30 38 / 92%);
  box-shadow: 0 12px 28px rgb(0 0 0 / 28%);
  backdrop-filter: blur(8px);
}

.variant-menu button {
  min-height: 30px;
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 0 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #e8e2da;
  font-size: 11px;
  text-align: left;
  cursor: pointer;
}

.variant-menu button:hover { background: rgb(255 255 255 / 10%); color: white; }
.variant-menu button.active { background: rgb(223 107 87 / 22%); color: #ffd9a0; }
.variant-dot { width: 7px; height: 7px; flex: 0 0 auto; border-radius: 50%; background: #6eaa8c; }
.variant-menu button:not(.active) .variant-dot { background: rgb(255 255 255 / 28%); }
.variant-menu button > span:nth-child(2) { min-width: 0; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.variant-menu button em { font-style: normal; color: #ffd9a0; font-size: 10px; opacity: .85; }

.variant-menu-enter-active, .variant-menu-leave-active { transition: opacity 120ms ease, transform 120ms ease; }
.variant-menu-enter-from, .variant-menu-leave-to { opacity: 0; transform: translateY(5px); }

@media (prefers-reduced-motion: no-preference) {
  .rive-pet.reacting[data-reaction='greet'] { animation: pet-greet 720ms ease; }
  .rive-pet.reacting[data-reaction='play'] { animation: pet-play 720ms ease; }
  .rive-pet.reacting[data-reaction='comfort'] { animation: pet-comfort 900ms ease; }
  .rive-pet.reacting[data-reaction='celebrate'] { animation: pet-celebrate 820ms ease; }
  .rive-pet.reacting[data-reaction='feed'] { animation: pet-feed 720ms ease; }
  .rive-pet[data-species='SNAKE'] .static-pet { animation: snake-idle 3.4s ease-in-out infinite; }
  .rive-pet[data-species='SNAKE'].reacting[data-reaction='play'] .static-pet { animation: snake-play 720ms ease; }
  .rive-pet[data-species='SNAKE'].reacting[data-reaction='comfort'] .static-pet { animation: snake-comfort 900ms ease; }
  .rive-pet[data-species='SNAKE'].reacting[data-reaction='celebrate'] .static-pet { animation: snake-celebrate 820ms ease; }
  .rive-pet[data-species='FOX'].reacting[data-reaction='play'] canvas { animation: fox-play 720ms ease; }
  .rive-pet[data-species='FOX'].reacting[data-reaction='celebrate'] canvas { animation: fox-celebrate 820ms ease; }
}

@keyframes pet-greet {
  0%, 100% { transform: scale(1); }
  45% { transform: scale(1.018); }
}

@keyframes pet-play {
  0%, 100% { transform: translateX(0); }
  30% { transform: translateX(-5px) rotate(-.4deg); }
  65% { transform: translateX(5px) rotate(.4deg); }
}

@keyframes pet-comfort {
  0%, 100% { transform: scale(1); }
  45% { transform: scale(.992); filter: saturate(.9); }
}

@keyframes pet-celebrate {
  0%, 100% { transform: translateY(0) scale(1); }
  35% { transform: translateY(-7px) scale(1.012); }
  65% { transform: translateY(-2px) scale(1.006); }
}

@keyframes pet-feed {
  0%, 100% { transform: scale(1); }
  42% { transform: scale(1.014) translateY(2px); }
}

@keyframes snake-idle {
  0%, 100% { transform: translateY(0) rotate(-2deg); }
  50% { transform: translateY(-8px) rotate(3deg); }
}

@keyframes snake-play {
  0%, 100% { transform: translateX(0) rotate(-2deg); }
  25% { transform: translateX(-10px) rotate(-8deg); }
  70% { transform: translateX(10px) rotate(8deg); }
}

@keyframes snake-comfort {
  0%, 100% { transform: scale(1) rotate(-2deg); }
  50% { transform: scale(.92) rotate(4deg); }
}

@keyframes snake-celebrate {
  0%, 100% { transform: translateY(0) rotate(-2deg); }
  35% { transform: translateY(-18px) rotate(6deg); }
  65% { transform: translateY(-6px) rotate(-5deg); }
}

@keyframes fox-play {
  0%, 100% { transform: translateX(0); }
  40% { transform: translateX(-8px); }
  72% { transform: translateX(8px); }
}

@keyframes fox-celebrate {
  0%, 100% { transform: translateY(0) scale(1); }
  38% { transform: translateY(-10px) scale(1.02); }
}
</style>
