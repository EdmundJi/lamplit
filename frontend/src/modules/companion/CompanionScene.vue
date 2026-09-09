<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type Phaser from 'phaser'
import type { SceneResident, SceneProject, SceneConversation, SceneObject, SceneLabel } from './companion-scene'
import { TownSoundscape } from '../../shared/scene/soundscape'
const props = withDefaults(defineProps<{ residents: SceneResident[]; weather?: 'clear' | 'rain'; minutes?: number; soundEnabled?: boolean; cafeOpen?: boolean; selectedResidentId?: string; selectedPlace?: string; projects?: SceneProject[]; conversations?: SceneConversation[]; objects?: SceneObject[]; overview?: boolean; textBubbles?: boolean; suppressHover?: boolean }>(), { weather: 'clear', minutes: 720, soundEnabled: false, cafeOpen: true, overview: false, textBubbles: false, suppressHover: false })
const emit = defineEmits<{ 'select-resident': [id: string]; 'select-project': [id: string]; 'select-conversation': [id: string] }>()
const host = ref<HTMLDivElement>()
const failed = ref(false)
const labels = ref<SceneLabel[]>([])
const hoveredId = ref<string | null>(null)
const focusedId = ref<string | null>(null)
const topicHoveredId = ref<string | null>(null)
const topicFocusedId = ref<string | null>(null)
function clearHover() { hoveredId.value = focusedId.value = topicHoveredId.value = topicFocusedId.value = null }
function openResident(id: string) { clearHover(); emit('select-resident', id) }
function openConversation(id: string) { clearHover(); emit('select-conversation', id) }
function showCard(label: SceneLabel) { return !props.suppressHover && (hoveredId.value === label.id || focusedId.value === label.id) }
function showDialogue(label: SceneLabel) { return !props.suppressHover && (topicHoveredId.value === label.id || topicFocusedId.value === label.id) }
watch(() => props.suppressHover, () => clearHover())
let resizeObserver: ResizeObserver | undefined
let game: Phaser.Game | undefined
let scene: import('./companion-scene').CompanionStreetScene | undefined
let disposed = false
const sound = new TownSoundscape(() => ({ weather: props.weather, minutes: props.minutes }))
function updateSound() {
  sound.setCafeOpen(props.cafeOpen)
  const self = props.residents.find(resident => resident.role === 'user' || resident.id === 'self' || resident.id === 'user')
  const selected = props.residents.find(resident => resident.id === props.selectedResidentId)
  const viewedPlace = props.overview ? 'street' : props.selectedPlace || selected?.location || self?.location || 'street'
  sound.setSpace(/^cafe([./]|$)/.test(viewedPlace) ? 'cafe' : /^home([./-]|$)/.test(viewedPlace) ? 'home' : 'outdoor')
  sound.refresh()
}
function visibility() { sound.setVisible(!document.hidden) }
watch(() => props.soundEnabled, enabled => sound.setEnabled(enabled))
watch(() => [props.residents, props.weather, props.minutes, props.cafeOpen, props.projects, props.objects, props.conversations, props.selectedResidentId, props.selectedPlace, props.overview], () => { scene?.sync(); updateSound() }, { deep: true })
onMounted(async () => {
  document.addEventListener('visibilitychange', visibility)
  visibility(); updateSound()
  try {
    const [{ default: PhaserRuntime }, { CompanionStreetScene }] = await Promise.all([import('phaser'), import('./companion-scene')])
    if (disposed || !host.value) return
    scene = new CompanionStreetScene(() => ({ residents: props.residents, weather: props.weather, minutes: props.minutes, selectedResidentId: props.selectedResidentId, selectedPlace: props.selectedPlace, projects: props.projects, objects: props.objects, conversations: props.conversations, overview: props.overview }), id => openResident(id), id => emit('select-project', id), value => { labels.value = value })
    const density = Math.min(window.devicePixelRatio || 1, 3)
    const bounds = host.value.getBoundingClientRect()
    game = new PhaserRuntime.Game({ type: PhaserRuntime.AUTO, parent: host.value, width: Math.round(bounds.width * density), height: Math.round(bounds.height * density), backgroundColor: '#8da578', antialias: false, pixelArt: true, roundPixels: true, scene, scale: { mode: PhaserRuntime.Scale.NONE, autoCenter: PhaserRuntime.Scale.NO_CENTER }, audio: { noAudio: true }, banner: false })
    const resize = () => {
      if (!game || !host.value) return
      const bounds = host.value.getBoundingClientRect()
      const ratio = Math.min(window.devicePixelRatio || 1, 3)
      game.scale.resize(Math.round(bounds.width * ratio), Math.round(bounds.height * ratio))
      game.canvas.style.width = `${bounds.width}px`
      game.canvas.style.height = `${bounds.height}px`
      scene?.resizeViewport(bounds.width, bounds.height, ratio)
    }
    resizeObserver = new ResizeObserver(resize)
    resizeObserver.observe(host.value)
    game.events.once('ready', resize)
    resize()
  } catch { failed.value = true }
})
onBeforeUnmount(() => { disposed = true; document.removeEventListener('visibilitychange', visibility); resizeObserver?.disconnect(); sound.destroy(); game?.destroy(true) })
</script>
<template>
  <div class="companion-scene" aria-label="陪伴小街：归家小屋、咖啡馆、门前小街和花园">
    <div ref="host" class="companion-scene__canvas" :aria-hidden="!failed" />
    <div class="companion-scene__labels" aria-label="小街居民">
      <template v-for="label in labels" :key="label.id">
        <button type="button" class="resident-label resident-person" :data-resident-id="label.id" :data-world-x="label.worldX" :data-world-y="label.worldY" :data-facing="label.facing" :class="{ 'is-offscreen': label.offscreen, 'card-below': label.y < 115, 'edge-left': label.x < 120, 'edge-right': label.x > (host?.clientWidth ?? 960) - 120 }" :style="label.offscreen ? { left: `${label.x}px`, top: `${label.y}px`, height: '28px' } : { left: `${label.bodyX}px`, top: `${label.bodyY}px`, height: `${label.bodyHeight}px` }" :aria-label="`${label.name}，${label.role}，${label.action}${label.offscreen ? '，在画面外，点击查看' : '，点击查看故事'}`" @mouseenter="hoveredId = label.id" @mouseleave="hoveredId = null" @focus="focusedId = label.id" @blur="focusedId = null" @click="openResident(label.id)">
          <span v-if="label.offscreen" class="resident-edge-avatar" aria-hidden="true">{{ label.direction }} {{ label.name.slice(0, 1) }}</span>
          <span v-if="showCard(label)" role="tooltip" class="resident-card"><strong>{{ label.name }}</strong><span class="resident-role">{{ label.role }}</span><span class="resident-action">{{ label.action }}</span></span>
        </button>
        <button v-if="label.emoji && !label.offscreen" type="button" class="resident-label resident-topic" :class="{ 'card-below': label.y < 140, 'edge-left': label.x < 140, 'edge-right': label.x > (host?.clientWidth ?? 960) - 140 }" :style="{ left: `${label.x}px`, top: `${label.y}px` }" :aria-label="`${label.name}正在交谈，查看对话`" @mouseenter="topicHoveredId = label.id" @mouseleave="topicHoveredId = null" @focus="topicFocusedId = label.id" @blur="topicFocusedId = null" @click="openConversation(label.conversationId!)">
          <span class="resident-status" aria-hidden="true"><span class="resident-topic-emoji">{{ label.emoji }}</span></span>
          <span v-if="showDialogue(label)" role="tooltip" class="resident-card resident-dialogue"><span v-for="(line, index) in label.dialogue" :key="index" class="dialogue-line"><strong>{{ line.name }}</strong>{{ line.text }}</span></span>
          <span v-else-if="textBubbles && label.speech && !suppressHover" class="resident-speech">{{ label.speech }}</span>
        </button>
      </template>
    </div>
    <p v-if="failed" class="companion-scene__fallback">画面暂时没有加载成功，居民的生活仍会保存。刷新页面再看看。</p>
    <div class="companion-scene__roster" aria-label="小街居民位置">
      <button v-for="resident in residents" :key="resident.id" type="button" @click="emit('select-resident', resident.id)">{{ resident.name }} · {{ resident.action }}</button>
    </div>
    <span class="companion-scene__credit">人物素材 · LimeZu</span>
  </div>
</template>
<style scoped>
.companion-scene { position: relative; width: 100%; overflow: hidden; border: 1px solid #d4dac7; border-radius: 22px; background: #e9e9d8; }
.companion-scene__canvas { position: relative; width: 100%; aspect-ratio: 1.86; }
.companion-scene__canvas :deep(canvas) { position: absolute; inset: 0; display: block; margin: 0 !important; image-rendering: pixelated; }
.companion-scene__labels { position: absolute; inset: 0; pointer-events: none; overflow: hidden; }
.resident-label { position: absolute; transform: translate(-50%, 0); pointer-events: auto; appearance: none; padding: 0; margin: 0; border: 0; background: none; color: #354d3c; cursor: pointer; font: 11px/1.3 system-ui, sans-serif; white-space: nowrap; }
.resident-status { position: relative; display: inline-flex; align-items: center; justify-content: center; min-width: 32px; min-height: 30px; box-sizing: border-box; padding: 3px 5px; border: 2px solid #75786b; border-radius: 2px; background: #fff5df; font-size: 16px; line-height: 1; box-shadow: inset 0 -2px #e5d9bc, 2px 2px #43513c30; }
.resident-status::after { content: ''; position: absolute; right: 7px; bottom: -6px; width: 5px; height: 6px; background: #fff5df; border-right: 2px solid #75786b; border-bottom: 2px solid #75786b; clip-path: polygon(0 0, 100% 0, 100% 66%, 66% 66%, 66% 100%, 0 100%); }
.resident-topic-emoji { filter: saturate(.82); }
.resident-topic:hover .resident-status, .resident-topic:focus-visible .resident-status { background: #fff9ea; border-color: #59674f; }
.resident-label:focus-visible { outline: 2px solid #917d52; border-radius: 50%; outline-offset: 3px; }
.resident-direction { font-size: 13px; color: #63765d; margin-right: 2px; }
.resident-card { position: absolute; z-index: 3; bottom: calc(100% + 5px); left: 50%; transform: translateX(-50%); display: grid; grid-template-columns: auto auto; align-items: baseline; gap: 4px 9px; width: max-content; max-width: 160px; padding: 9px 11px; background: #fff9edf5; border: 1px solid #dedcc8; border-radius: 8px; color: #46573f; box-shadow: 0 4px 12px #2b42241a; white-space: normal; text-align: left; pointer-events: none; }
.resident-card strong { font-size: 12px; font-weight: 600; }
.resident-role { color: #858770; font-size: 10px; }
.resident-action { grid-column: 1 / -1; font-size: 11px; line-height: 1.4; color: #747e68; }
.edge-left .resident-card, .edge-left .resident-speech { left: 0; transform: none; }
.edge-right .resident-card, .edge-right .resident-speech { left: auto; right: 0; transform: none; }
.resident-label:has(.resident-card) { z-index: 5; }
.resident-person { width: 36px; border-radius: 9px; }
.resident-person:focus-visible { outline: 2px solid #f0dfac; border-radius: 9px; }
.resident-topic { z-index: 2; padding: 4px 5px 8px; min-width: 44px; min-height: 44px; }
.resident-topic:has(.resident-dialogue)::before { content: ""; position: absolute; bottom: 100%; left: -112px; width: 250px; height: 8px; pointer-events: auto; }
.resident-topic.card-below:has(.resident-dialogue)::before { bottom: auto; top: 100%; }
.resident-edge-avatar { display: grid; place-items: center; width: 29px; height: 28px; color: #586c50; background: #ecefdfed; border: 1px solid #d5dbc7; border-radius: 50%; font-size: 11px; }
.resident-dialogue { display: flex; flex-direction: column; gap: 9px; width: 236px; max-width: 236px; max-height: 190px; overflow-y: auto; pointer-events: auto; cursor: pointer; background: #fff8e9; border: 2px solid #858976; border-radius: 2px; box-shadow: inset 0 -2px #e9ddc2, 3px 3px #43513c24; }
.dialogue-line { display: block; font-size: 12px; line-height: 1.6; }
.dialogue-line strong { display: block; margin-bottom: 2px; color: #7b856a; font-size: 10px; font-weight: 500; }
.card-below .resident-card, .card-below .resident-speech { bottom: auto; top: calc(100% + 5px); }
.resident-speech { position: absolute; bottom: calc(100% + var(--speech-offset, 57px)); left: 50%; transform: translateX(-50%); max-width: 170px; width: max-content; white-space: normal; background: #fff8e9; color: #4c5a49; font-size: 11px; line-height: 1.5; padding: 7px 10px; border: 2px solid #858976; border-radius: 2px; box-shadow: 3px 3px #43513c24; }
.companion-scene__credit { position: absolute; right: 15px; bottom: 9px; color: #818773; font-size: 9px; pointer-events: none; }
.companion-scene__fallback { padding: 24px; color: #67705d; }
.companion-scene__roster { display: flex; flex-wrap: wrap; gap: 6px; padding: 0 18px 27px; }
.companion-scene__roster button { border: 1px solid #cfd6bf; border-radius: 20px; padding: 5px 10px; background: #f3f3e6; color: #5c6855; font-size: 11px; cursor: pointer; }
.companion-scene__roster button:hover { background: #fff9e9; }
.companion-scene__roster button:focus-visible { outline: 2px solid #7b9474; outline-offset: 2px; }
</style>
