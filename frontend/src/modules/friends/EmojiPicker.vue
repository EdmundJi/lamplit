<script setup lang="ts">
import { EMOJIS } from './emojis'

const emit = defineEmits<{
  pick: [char: string]
}>()

function pick(char: string) {
  emit('pick', char)
}
</script>

<template>
  <div class="emoji-picker" role="group" aria-label="选择表情">
    <button
      v-for="emoji in EMOJIS"
      :key="emoji.char"
      type="button"
      class="emoji-item"
      :aria-label="`表情 ${emoji.char}`"
      @click="pick(emoji.char)"
    >
      <img :src="`/assets/emojis/${emoji.file}`" :alt="emoji.char" loading="lazy" />
    </button>
  </div>
</template>

<style scoped>
.emoji-picker { display: grid; grid-template-columns: repeat(8, 1fr); gap: 4px; max-height: 220px; overflow-y: auto; padding: 10px; border: 1px solid var(--border); border-radius: var(--radius); background: color-mix(in srgb, var(--surface) 96%, var(--canvas)); box-shadow: var(--shadow-soft); }
.emoji-item { min-width: 0; aspect-ratio: 1; display: grid; place-items: center; padding: 4px; border: 0; border-radius: 8px; background: transparent; cursor: pointer; }
.emoji-item:hover { background: var(--surface-muted); }
.emoji-item img { width: 26px; height: 26px; pointer-events: none; }
</style>
