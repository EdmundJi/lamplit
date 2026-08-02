<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { Alignment, Fit, Layout, Rive, RuntimeLoader, StateMachineInputType, type StateMachineInput } from '@rive-app/canvas'
import { PawPrint, RotateCcw } from 'lucide-vue-next'

RuntimeLoader.setWasmUrl('/assets/rive/rive.wasm')
RuntimeLoader.setWasmFallbackUrl('/assets/rive/rive_fallback.wasm')

type Reaction = 'interact' | 'feed' | 'celebrate'

type RivePetAnimation = {
  kind: 'rive'
  src: string
  artboard: string
  stateMachine: string
  credit: string
}

type ImagePetAnimation = {
  kind: 'image'
  src: string
  credit: string
}

type PetAnimation = RivePetAnimation | ImagePetAnimation

const props = defineProps<{
  speciesCode: string
  name: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  activate: []
}>()

const animations: Record<string, PetAnimation> = {
  CAT: {
    kind: 'rive',
    src: '/assets/pets/rive/cat-play-time.riv',
    artboard: '404_artboard',
    stateMachine: 'cat_SM',
    credit: 'Play Time · rkoomera',
  },
  DOG: {
    kind: 'rive',
    src: '/assets/pets/rive/dog-interactive.riv',
    artboard: 'dog-walk-cycle',
    stateMachine: 'State Machine 1',
    credit: 'Interactive Dog · jouri',
  },
  HAMSTER: {
    kind: 'rive',
    src: '/assets/pets/rive/hamster-idle-jump.riv',
    artboard: 'HasmterNested',
    stateMachine: 'State Machine 1',
    credit: 'Hamster · ersanakpinarr',
  },
  SNAKE: {
    kind: 'image',
    src: '/assets/pets/snake-cartoon.svg',
    credit: 'Twemoji · CC BY 4.0',
  },
  RABBIT: {
    kind: 'rive',
    src: '/assets/pets/rive/rabbit-interactive.riv',
    artboard: 'Login',
    stateMachine: 'State Machine 1',
    credit: 'Animated Login Bunny · trong.phanduc34',
  },
  BIRD: {
    kind: 'rive',
    src: '/assets/pets/rive/bird-interactive.riv',
    artboard: 'Bird',
    stateMachine: 'State Machine 1',
    credit: 'Bird · ElmerVergara',
  },
  TURTLE: {
    kind: 'rive',
    src: '/assets/pets/rive/turtle-angry.riv',
    artboard: 'Artboard',
    stateMachine: 'State Machine 1',
    credit: 'Angry Turtle · extraframe28',
  },
  FOX: {
    kind: 'rive',
    src: '/assets/pets/rive/fox-idle.riv',
    artboard: 'fox',
    stateMachine: 'State Machine 1',
    credit: 'Fox · sandeep.k',
  },
}

const canvas = ref<HTMLCanvasElement | null>(null)
const loading = ref(false)
const failed = ref(false)
const reacting = ref(false)
const animation = computed(() => animations[props.speciesCode])

let rive: Rive | null = null
let inputs: StateMachineInput[] = []
let resizeObserver: ResizeObserver | null = null
let reactionTimer: number | undefined
let inputTimers: number[] = []
let dogReactionIndex = 0

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

  const prefersReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
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

  resizeObserver = new ResizeObserver(() => rive?.resizeDrawingSurfaceToCanvas())
  resizeObserver.observe(canvasElement)
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

function react(kind: Reaction = 'interact') {
  const config = animation.value
  if (!config) return
  reacting.value = true
  window.clearTimeout(reactionTimer)
  reactionTimer = window.setTimeout(() => { reacting.value = false }, 720)

  if (config.kind === 'image' || !rive) return
  rive.play(config.stateMachine)

  if (props.speciesCode === 'DOG') {
    const dogInputs = kind === 'feed' ? ['Hit Tong'] : ['Hit Oor', 'Hit Staart', 'Hit Tong']
    const target = dogInputs[dogReactionIndex % dogInputs.length]
    dogReactionIndex += 1
    pulseBoolean(target)
  }

  if (props.speciesCode === 'TURTLE') {
    if (kind === 'celebrate') pulseBoolean('stand up/down', 900)
    else fireTrigger(kind === 'feed' ? 'hit trigger' : 'click trigger')
  }

  if (props.speciesCode === 'RABBIT') {
    if (kind === 'celebrate') fireTrigger('login_success')
    else pulseBoolean('isFocus', 650) || fireTrigger('login_success')
  }

  if (props.speciesCode === 'BIRD') {
    setNumber('direction', (dogReactionIndex++ % 8) * 45)
  }
}

function handleActivate() {
  if (props.disabled || !animation.value || failed.value) return
  react('interact')
  emit('activate')
}

watch(() => props.speciesCode, initialize, { immediate: true })
onBeforeUnmount(cleanup)

defineExpose({ react })
</script>

<template>
  <button
    class="rive-pet"
    :class="{ reacting, unavailable: !animation || failed }"
    :data-species="speciesCode"
    type="button"
    :disabled="disabled || !animation || failed"
    :aria-label="animation ? `和${name}互动` : `${name}的动画正在制作中`"
    @click="handleActivate"
  >
    <canvas v-if="animation?.kind === 'rive' && !failed" ref="canvas">
      {{ name }}的动态伙伴形象
    </canvas>

    <img v-else-if="animation?.kind === 'image'" class="static-pet" :src="animation.src" alt="">

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
  </button>
</template>

<style scoped>
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
  cursor: pointer;
}

.rive-pet:focus-visible {
  outline: 3px solid color-mix(in srgb, var(--primary) 56%, white);
  outline-offset: -5px;
}

.rive-pet:disabled { cursor: default; }
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

@media (prefers-reduced-motion: no-preference) {
  .rive-pet.reacting { animation: pet-response 720ms ease; }
  .rive-pet[data-species='SNAKE'] .static-pet { animation: snake-idle 3.4s ease-in-out infinite; }
}

@keyframes pet-response {
  0%, 100% { transform: scale(1); }
  45% { transform: scale(1.018); }
}

@keyframes snake-idle {
  0%, 100% { transform: translateY(0) rotate(-2deg); }
  50% { transform: translateY(-8px) rotate(3deg); }
}
</style>
