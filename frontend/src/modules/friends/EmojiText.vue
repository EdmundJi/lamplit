<script setup lang="ts">
import { EMOJI_PATTERN, emojiFile } from './emojis'

const props = defineProps<{ text: string }>()

const parts = props.text.split(new RegExp(`(${EMOJI_PATTERN.source})`, 'gu'))
</script>

<template>
  <span class="emoji-text">
    <template v-for="(part, index) in parts" :key="index">
      <template v-if="index % 2 === 0">{{ part }}</template>
      <img v-else class="emoji-img" :src="`/assets/emojis/${emojiFile(part)}`" :alt="part" loading="lazy" />
    </template>
  </span>
</template>

<style scoped>
.emoji-text { display: inline; overflow-wrap: anywhere; }
.emoji-img { width: 1.3em; height: 1.3em; vertical-align: -0.25em; margin: 0 1px; }
</style>
