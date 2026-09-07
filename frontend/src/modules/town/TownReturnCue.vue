<script setup lang="ts">
import { computed, ref } from 'vue'
const props = defineProps<{ planned: number; done: number; unread: number; night?: boolean }>()
const emit = defineEmits<{ today: []; mail: [] }>()
const dismissed = ref(false)
const text = computed(() => props.unread > 0 ? `信箱里有 ${props.unread} 条未读消息。` : props.planned > props.done ? `今天还有 ${props.planned - props.done} 件计划，可以先选一件。` : props.done > 0 ? `今天已完成 ${props.done} 件事。回家休息一会儿吧。` : props.night ? '夜深了，可以安静待着，也可以记下一个小计划。' : '欢迎回家。记下一件想做的小事，或者随便走走。')
</script>
<template><aside v-if="!dismissed" class="town-return-cue" aria-label="回到小镇"><p>{{ text }}</p><div><button type="button" @click="props.unread ? emit('mail') : emit('today'); dismissed = true">{{ props.unread ? '打开信箱' : '打开手账' }}</button><button type="button" @click="dismissed = true">随便走走</button></div></aside></template>
<style scoped>
.town-return-cue { background: #fff9ecef; color: #355b44; border: 1px solid #d8cfb8; border-radius: 12px; padding: 12px 16px; width: fit-content; max-width: calc(100% - 32px); }
p { margin: 0 0 8px; font-size: 13px; } div { display: flex; gap: 8px; } button { font-size: 12px; min-height: 32px; background: #e9eddf; color: #355b44; }
</style>
