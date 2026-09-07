<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import TownConfidantComposer from './TownConfidantComposer.vue'
import { useTownSocialStore } from './social.store'
import { letterKindLabels, type TownLetterKind } from './social.types'

const emit = defineEmits<{ 'unread-change': [count: number] }>()
const { letters, unreadCount, loaded, loading, loadError, reading, readErrors,
  draft, sending, sendError, feedback, canSend, refresh, markRead, send, dispose } = useTownSocialStore()
const filter = ref<TownLetterKind | 'ALL'>('ALL')
const opened = ref<string | null>(null)
const composing = ref(false)
const id = useId()
const visibleLetters = computed(() => letters.value.filter(item => filter.value === 'ALL' || item.kind === filter.value))
const selected = computed(() => visibleLetters.value.find(item => item.publicId === opened.value))
watch(filter, () => { opened.value = null })
watch([loaded, unreadCount], () => { if (loaded.value) emit('unread-change', unreadCount.value) })

function openLetter(publicId: string) {
  opened.value = opened.value === publicId ? null : publicId
  if (opened.value) void markRead(publicId)
}
function deliveryLabel(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '送达时间待确认' : date.toLocaleString('zh-CN')
}
defineExpose({ refresh })
onMounted(refresh)
onBeforeUnmount(dispose)
</script>

<template>
  <section class="town-mailbox" aria-label="小镇信箱">
    <header>
      <h2>小镇信箱</h2>
      <span v-if="loaded" role="status">{{ unreadCount }} 封未读</span>
      <button class="secondary" type="button" :disabled="loading || reading.size > 0" @click="refresh">收取信件</button>
      <button class="secondary" type="button" :aria-expanded="composing" :aria-controls="`${id}-composer`" @click="composing = !composing">
        {{ composing ? '收起信纸' : '写给树洞' }}
      </button>
    </header>
    <TownConfidantComposer v-show="composing" :id="`${id}-composer`" v-model:draft="draft"
      :sending="sending" :can-send="canSend" :error="sendError" :feedback="feedback" @send="send" />
    <nav aria-label="信件分类">
      <button class="secondary" type="button" :aria-pressed="filter === 'ALL'" @click="filter = 'ALL'">全部</button>
      <button v-for="(label, kind) in letterKindLabels" :key="kind" class="secondary" type="button"
        :aria-pressed="filter === kind" @click="filter = kind">{{ label }}</button>
    </nav>
    <p v-if="loading" role="status">邮递员正在整理信件…</p>
    <p v-if="loadError" role="alert">{{ loadError }} <button class="secondary" type="button" :disabled="loading" @click="refresh">重试收信</button></p>
    <template v-if="!loading && loaded">
      <p v-if="!visibleLetters.length">{{ filter === 'ALL' ? '信箱还是空的，过些时候再来看看。' : `还没有${letterKindLabels[filter]}。` }}</p>
      <ul v-else class="letter-list">
        <li v-for="letter in visibleLetters" :key="letter.publicId">
          <button class="letter-summary" type="button" :aria-expanded="opened === letter.publicId"
            :aria-controls="`${id}-letter-${letter.publicId}`" @click="openLetter(letter.publicId)">
            <strong>{{ letterKindLabels[letter.kind] }}</strong>
            <span>{{ letter.senderKind === 'CONFIDANT' ? '树洞笔友' : letter.senderName || '镇上居民' }}</span>
            <span>{{ letter.readAt ? '已读' : '未读' }}</span>
            <time :datetime="letter.deliverAt">{{ deliveryLabel(letter.deliverAt) }}</time>
          </button>
          <article v-if="selected?.publicId === letter.publicId" :id="`${id}-letter-${letter.publicId}`" class="letter-body" aria-label="信件正文">
            <p>{{ letter.body }}</p>
            <small v-if="reading.has(letter.publicId)" role="status">正在保存已读状态…</small>
            <div v-if="readErrors[letter.publicId]" role="alert">
              {{ readErrors[letter.publicId] }}
              <button class="secondary" type="button" :disabled="reading.has(letter.publicId)" @click="markRead(letter.publicId)">重试标已读</button>
            </div>
          </article>
        </li>
      </ul>
    </template>
  </section>
</template>

<style scoped>
.town-mailbox { display: grid; gap: 14px; width: 100%; min-width: 0; color: var(--ink, #26382d); }
header, nav { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
h2 { margin: 0; font-size: 16px; }
button { cursor: pointer; }
button:disabled { cursor: default; opacity: .6; }
button[aria-pressed='true'] { outline: 2px solid var(--primary, #438060); outline-offset: -2px; }
.letter-list { display: grid; gap: 10px; list-style: none; padding: 0; margin: 0; max-height: 55vh; overflow-y: auto; }
li { border: 1px solid var(--border, #ccc); border-radius: 8px; overflow: hidden; }
.letter-summary { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; width: 100%; padding: 12px; text-align: left; color: inherit; background: var(--surface, white); border: 0; font: inherit; }
time { font-size: 12px; color: var(--muted, #666); }
.letter-body { padding: 12px; background: var(--surface-muted, #f5f6f2); }
.letter-body p { white-space: pre-wrap; overflow-wrap: anywhere; margin: 0 0 10px; line-height: 1.8; }
</style>
