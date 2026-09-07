<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ place: string | null }>()
const emit = defineEmits<{ action: [id: string] }>()
const activities: Record<string, { title: string; text: string; label: string; action: string }> = {
  home: { title: '回到自己的节奏', text: '整理今天的记录，看看已经走过的一小步。', label: '回看今天', action: 'home.review-today' },
  academy: { title: '在学院，专心做一件事', text: '从今天的任务里选一件，再点击“专注执行”开始。', label: '选一件事，开始专注', action: 'academy.prepare-focus' },
  cafe: { title: '在桌边，把想法理清楚', text: '和小助聊一个想法，再把合适的下一步加入已有计划。', label: '整理我的下一步', action: 'cafe.open-ai' },
  park: { title: '走慢一点也很好', text: '看看树影，陪伙伴待一会儿。休息不用完成任务。', label: '看看同行伙伴', action: 'park.open-companion' },
}
const activity = computed(() => props.place ? activities[props.place] : undefined)
</script>
<template><aside v-if="activity" class="town-place-activity"><strong>{{ activity.title }}</strong><p>{{ activity.text }}</p><button type="button" @click="emit('action', activity.action)">{{ activity.label }}</button></aside></template>
<style scoped>
aside { padding: 12px 14px; border: 1px solid #d1c7b1; border-radius: 12px; background: #fff9eced; color: #355b44; width: 260px; max-width: calc(100vw - 32px); } strong { font-size: 13px; } p { font-size: 12px; line-height: 1.7; margin: 6px 0; } button { background: #e3ead9; color: #355b44; font-size: 12px; min-height: 36px; }
</style>
