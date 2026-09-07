<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { api } from '../../shared/api/client'
import { supportsStory, type StoryAction, type TownStoryView } from './town-stories'

const props = defineProps<{ npcCode: string; disabled?: boolean }>()
const expanded = ref(false)
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const story = ref<TownStoryView | null>(null)
let generation = 0

async function load() {
  const token = ++generation
  const code = props.npcCode
  loading.value = true
  error.value = ''
  try {
    const result = await api.get<TownStoryView>(`/town/stories/${encodeURIComponent(code)}`)
    if (token === generation) story.value = result
  } catch {
    if (token === generation) error.value = '暂时没能翻到这段故事，请再试一次。'
  } finally { if (token === generation) loading.value = false }
}
function toggle() {
  expanded.value = !expanded.value
  if (expanded.value && !story.value && !loading.value) void load()
}
async function advance(action: StoryAction) {
  if (busy.value || props.disabled || !story.value) return
  const token = generation
  const code = props.npcCode
  const expectedStage = story.value.stage
  const expectedRevision = story.value.revision
  busy.value = true
  error.value = ''
  try {
    const result = await api.post<TownStoryView>(`/town/stories/${encodeURIComponent(code)}/advance`,
      { action, expectedStage, expectedRevision })
    if (token === generation) story.value = result
  } catch {
    if (token === generation) error.value = '这一段还没确认保存。再点一次即可继续，不会跳过故事。'
  } finally { if (token === generation) busy.value = false }
}
watch(() => props.npcCode, () => {
  generation++
  expanded.value = false
  story.value = null
  loading.value = false
  busy.value = false
  error.value = ''
})
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <section v-if="supportsStory(npcCode)" class="town-story" aria-label="邻居的小故事">
    <button class="story-toggle" type="button" :aria-expanded="expanded" @click="toggle">
      {{ expanded ? '收起小故事' : story?.completedAt ? '看看留下的回忆' : `${npcCode === 'GUIDE' ? '小助' : '邮递员'}的小故事` }}
    </button>
    <div v-if="expanded" class="story-content" aria-live="polite">
      <p v-if="loading" role="status">正在翻到上次停下的地方…</p>
      <template v-else-if="story">
        <h3>{{ story.title }}</h3>
        <p class="story-heading">{{ story.heading }}<span v-if="story.paused"> · 暂时停在这里</span></p>
        <p>{{ story.body }}</p>
        <p v-if="story.completedAt" class="story-note">这段回忆已留下，随时可以再读。</p>
        <template v-else>
          <p class="story-note">可以一次读一小段，也可以接着听。下次回来会留在这里。</p>
          <div class="story-actions">
            <button v-for="action in story.actions" :key="action.code" type="button"
              :disabled="busy || disabled" @click="advance(action.code)">{{ action.label }}</button>
          </div>
          <p v-if="busy" role="status">正在记下这一段…</p>
        </template>
      </template>
      <p v-if="error" class="story-error" role="alert">{{ error }} <button v-if="!story" type="button" @click="load">重试</button></p>
    </div>
  </section>
</template>

<style scoped>
.town-story { border-top: 1px solid var(--border); padding-top: 8px; }
button { cursor: pointer; color: var(--ink); }
.story-toggle { background: transparent; border: 0; padding: 4px 0; color: var(--primary); font-size: 12px; }
.story-content { max-height: min(300px, 42dvh); overflow-y: auto; padding: 8px 2px; }
h3 { font-size: 14px; margin: 0 0 6px; }
p { margin: 6px 0; font-size: 13px; line-height: 1.65; overflow-wrap: anywhere; }
.story-heading,.story-note { color: var(--muted); font-size: 12px; }
.story-actions { display: flex; flex-wrap: wrap; gap: 6px; }
.story-actions button,.story-error button { min-height: 32px; padding: 6px 9px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface-muted); font-size: 12px; }
button:disabled { opacity: 0.55; cursor: default; }
.story-error { color: var(--danger); }
</style>
