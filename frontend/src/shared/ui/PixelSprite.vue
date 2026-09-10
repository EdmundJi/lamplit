<script lang="ts">
/**
 * The product's only illustration language: a single frame cut from the
 * licensed town atlas (or the emotes sheet), rendered crisp at an integer
 * scale. Used for small in-content marks — empty states, section markers,
 * achievement icons — never as background texture or decoration.
 *
 * This plain <script> block (as opposed to <script setup> below) is module
 * scope, evaluated once per import: the atlas fetch and its cache live here
 * so every PixelSprite instance on the page shares one network request.
 */
export type Frame = { x: number; y: number; w: number; h: number }
type Atlas = { frames: Record<string, { frame: Frame }>; meta: { size: { w: number; h: number } } }

const TOWN_ATLAS_URL = '/assets/town/town-atlas.json'
const TOWN_IMAGE_URL = '/assets/town/town-atlas.png'
const EMOTES_IMAGE_URL = '/assets/town/emotes.png'
// emotes.png has no JSON atlas (see scripts/build-town-assets.py) — it's a
// plain 32×32 grid, addressed here as 'emote-<index>' left-to-right, top-to-bottom.
const EMOTE_CELL = 32
const EMOTE_SHEET_SIZE = 320
const EMOTE_COLUMNS = EMOTE_SHEET_SIZE / EMOTE_CELL

let atlasPromise: Promise<Atlas | null> | null = null

function loadTownAtlas(): Promise<Atlas | null> {
  if (!atlasPromise) {
    atlasPromise = fetch(TOWN_ATLAS_URL)
      .then(response => (response.ok ? response.json() as Promise<Atlas> : null))
      .catch(() => null)
  }
  return atlasPromise
}

function emoteFrame(name: string): Frame | null {
  const match = /^emote-(\d+)$/.exec(name)
  if (!match) return null
  const index = Number(match[1])
  const count = EMOTE_COLUMNS * EMOTE_COLUMNS
  if (!Number.isInteger(index) || index < 0 || index >= count) return null
  return {
    x: (index % EMOTE_COLUMNS) * EMOTE_CELL,
    y: Math.floor(index / EMOTE_COLUMNS) * EMOTE_CELL,
    w: EMOTE_CELL,
    h: EMOTE_CELL,
  }
}
</script>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  name: string
  scale?: number
  source?: 'town' | 'emotes'
  alt?: string
}>(), { scale: 2, source: 'town' })

const frame = ref<Frame | null>(null)
const sheetSize = ref<{ w: number; h: number } | null>(null)

async function resolveFrame() {
  if (props.source === 'emotes') {
    frame.value = emoteFrame(props.name)
    sheetSize.value = { w: EMOTE_SHEET_SIZE, h: EMOTE_SHEET_SIZE }
    return
  }
  const atlas = await loadTownAtlas()
  frame.value = atlas?.frames[props.name]?.frame ?? null
  sheetSize.value = atlas?.meta?.size ?? null
}

onMounted(resolveFrame)
watch(() => [props.name, props.source], resolveFrame)

const imageUrl = computed(() => (props.source === 'emotes' ? EMOTES_IMAGE_URL : TOWN_IMAGE_URL))

const spriteStyle = computed(() => {
  const f = frame.value
  if (!f) return {}
  const scale = props.scale ?? 2
  const style: Record<string, string> = {
    width: `${f.w * scale}px`,
    height: `${f.h * scale}px`,
    backgroundImage: `url(${imageUrl.value})`,
    backgroundPosition: `-${f.x * scale}px -${f.y * scale}px`,
  }
  const sheet = sheetSize.value
  if (sheet) style.backgroundSize = `${sheet.w * scale}px ${sheet.h * scale}px`
  return style
})
</script>

<template>
  <div
    class="pixel-sprite"
    :class="{ 'pixel-sprite--empty': !frame }"
    :style="spriteStyle"
    :role="alt ? 'img' : undefined"
    :aria-label="alt || undefined"
    :aria-hidden="alt ? undefined : 'true'"
  />
</template>

<style scoped>
.pixel-sprite {
  display: inline-block;
  flex: none;
  background-repeat: no-repeat;
  image-rendering: pixelated;
}
.pixel-sprite--empty {
  width: 0;
  height: 0;
}
</style>
