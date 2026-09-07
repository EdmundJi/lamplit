<script setup lang="ts">
import { CONFIDANT_MAX_LENGTH } from './social.types'
defineProps<{ draft: string; sending: boolean; canSend: boolean; error: string; feedback: string }>()
defineEmits<{ 'update:draft': [value: string]; send: [] }>()
</script>

<template>
  <form class="confidant-composer" @submit.prevent="$emit('send')">
    <label>
      <strong>写给树洞笔友</strong>
      <textarea :value="draft" :maxlength="CONFIDANT_MAX_LENGTH" :disabled="sending" rows="5"
        placeholder="把想说的话慢慢写下来"
        @input="$emit('update:draft', ($event.target as HTMLTextAreaElement).value)" />
    </label>
    <p>这是私密通信，不会进入镇上的传闻。回信最早隔天送达，不会即时回复。</p>
    <small>{{ draft.length }} / {{ CONFIDANT_MAX_LENGTH }} 字 · 关闭信箱后草稿不会保留</small>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="feedback" role="status">{{ feedback }}</p>
    <button class="primary" type="submit" :disabled="!canSend">{{ sending ? '正在寄出…' : '寄出信件' }}</button>
  </form>
</template>

<style scoped>
.confidant-composer, label { display: grid; gap: 10px; min-width: 0; }
textarea { box-sizing: border-box; width: 100%; resize: vertical; padding: 10px; border: 1px solid var(--border, #ccc); border-radius: 8px; color: inherit; background: var(--surface, white); font: inherit; }
p { margin: 0; line-height: 1.6; }
small { color: var(--muted, #666); }
button { justify-self: start; }
</style>
