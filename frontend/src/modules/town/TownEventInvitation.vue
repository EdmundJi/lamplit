<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { api } from '../../shared/api/client'
import TownEventCard from './TownEventCard.vue'
import type { TownEventView } from './town-events'
const props = defineProps<{ eventId: string }>()
const emit = defineEmits<{ visit: [place: string] }>()
const event = ref<TownEventView | null>(null)
const loading = ref(false)
const error = ref('')
let generation = 0
async function load() {
  const token = ++generation
  loading.value = true; error.value = ''; event.value = null
  try {
    const result = await api.get<TownEventView>(`/town/events/${encodeURIComponent(props.eventId)}`)
    if (token === generation) event.value = result
  } catch { if (token === generation) error.value = '暂时无法查看这场活动，它也可能已不再提供。' }
  finally { if (token === generation) loading.value = false }
}
watch(() => props.eventId, load, { immediate: true })
onBeforeUnmount(() => { generation++ })
</script>
<template>
  <section aria-label="请柬对应的活动">
    <p v-if="loading" role="status">正在查看活动最新安排…</p>
    <p v-else-if="error" role="alert">{{ error }} <button type="button" @click="load">重新查看</button></p>
    <template v-else-if="event">
      <TownEventCard :event="event" @updated="event = $event" @visit="emit('visit', $event)" />
      <button class="refresh-event" type="button" @click="load">刷新活动安排</button>
    </template>
  </section>
</template>
<style scoped>.refresh-event { margin-top: 8px; padding: 8px 12px; color: var(--muted); background: transparent; border: 1px solid var(--border); border-radius: 8px; }</style>
