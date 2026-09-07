<script setup lang="ts">
import { Sparkles } from 'lucide-vue-next'
defineProps<{ title: string; editing: boolean; disabled: boolean }>()
defineEmits<{ load: [] }>()
</script>
<template>
  <aside class="ai-draft-notice" role="status" aria-live="polite">
    <strong><Sparkles :size="14" />有一份新的 AI 草案</strong>
    <p>{{ title }}</p>
    <p v-if="editing">当前编辑会保留。先保存或取消这次编辑，再载入新草案。</p>
    <button v-else type="button" class="secondary" :disabled="disabled" @click="$emit('load')">载入 AI 草案</button>
  </aside>
</template>
<style scoped>
.ai-draft-notice { display: grid; gap: 7px; padding: 12px; border: 1px solid color-mix(in srgb, var(--primary) 35%, var(--border)); border-radius: 9px; background: var(--primary-soft); }
.ai-draft-notice strong { display: flex; align-items: center; gap: 6px; color: var(--primary); font-size: 12px; }
.ai-draft-notice p { margin: 0; font-size: 12px; line-height: 1.5; color: var(--muted); overflow-wrap: anywhere; }
.ai-draft-notice button { justify-self: start; font-size: 12px; }
</style>
