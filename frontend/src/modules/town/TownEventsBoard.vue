<script setup lang="ts">
import { onMounted } from 'vue'
import { CalendarDays, X } from 'lucide-vue-next'
import { eventTitle, useTownEventsStore } from './town-events'
const emit = defineEmits<{ close: []; visit: [venue: string] }>()
const store = useTownEventsStore()
const placeNames: Record<string, string> = { plaza: '日光广场', park: '树荫公园', cafe: '街角咖啡馆' }
const time = (value: string) => new Date(value).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
onMounted(() => { void store.load() })
</script>
<template>
  <section class="town-events-board" aria-label="小镇活动公告" @pointerdown.stop @keydown.esc.stop="emit('close')">
    <header><h2><CalendarDays :size="18" />镇上的邀约</h2><button type="button" aria-label="关闭活动公告" @click="emit('close')"><X :size="18" /></button></header>
    <p class="intro">相聚只为聊聊，不打卡，也不计分。</p>
    <p v-if="store.loading" role="status">正在看看公告栏…</p>
    <div v-else-if="store.error" role="alert"><p>{{ store.error }}</p><button type="button" @click="store.load">重试</button></div>
    <p v-else-if="!store.events.length" class="empty-events">今天没有安排聚会。居民各自忙着，也欢迎随时在路上打个招呼。</p>
    <ul v-else><li v-for="event in store.events" :key="event.publicId">
      <strong>{{ eventTitle(event.kind) }}</strong><p>{{ event.hostName }} · {{ placeNames[event.venue] ?? '镇上' }} · {{ time(event.startsAt) }}</p>
      <button type="button" @click="emit('visit', event.venue)">去这里走走</button>
    </li></ul>
  </section>
</template>
<style scoped>
.town-events-board { box-sizing: border-box; width: 100%; max-width: 380px; padding: 20px; background: var(--surface, #fff9ee); color: var(--ink, #34483b); border: 1px solid var(--border, #dccdb5); border-radius: 18px; box-shadow: var(--shadow); }
header,h2 { display: flex; align-items: center; gap: 8px; } header { justify-content: space-between; } h2 { margin: 0; font-size: 18px; } button { padding: 8px 12px; border-radius: 8px; background: var(--surface-muted); color: inherit; border: 1px solid var(--border); cursor: pointer; } .intro,p { font-size: 13px; line-height: 1.7; } .intro { color: var(--muted); } ul { list-style: none; padding: 0; display: grid; gap: 12px; } li { padding-block: 12px; border-top: 1px solid var(--border); } .empty-events { padding: 16px 0; }
</style>
