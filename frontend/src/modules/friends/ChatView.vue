<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowLeft, Send, Smile } from 'lucide-vue-next'
import { api } from '../../shared/api/client'
import { useAuthStore } from '../auth/auth.store'
import EmojiPicker from './EmojiPicker.vue'
import ConversationRail from './ConversationRail.vue'
import EmojiText from './EmojiText.vue'
import { chatTimeLabel as messageTime, friendInitial, useChatThread } from './friends.logic'

const props = defineProps<{ publicId: string }>()

const auth = useAuthStore()
const { messages, peerName, loading, error, busy, draft, open, startPoll, stopPoll, send: submit, insertEmoji } = useChatThread()
const peerLevel = ref(1)
const showEmoji = ref(false)
const listElement = ref<HTMLElement | null>(null)

const myName = computed(() => auth.user?.displayName ?? '我')
const myInitial = computed(() => friendInitial(myName.value))
const peerInitial = computed(() => friendInitial(peerName.value))

function scrollToBottom() {
  if (listElement.value) listElement.value.scrollTop = listElement.value.scrollHeight
}

async function send() {
  await submit()
  showEmoji.value = false
  await nextTick()
  scrollToBottom()
}

function keydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    send()
  }
}

onMounted(async () => {
  const summary = await api.get<{ publicId: string; displayName: string; overallLevel: number }>(`/friends/${props.publicId}/summary`).catch(() => null)
  if (summary) peerLevel.value = summary.overallLevel
  await open('single', props.publicId, summary?.displayName ?? '')
  await nextTick()
  scrollToBottom()
  startPoll()
})

watch(messages, () => {
  void nextTick().then(scrollToBottom)
})

onBeforeUnmount(stopPoll)
</script>

<template>
  <div class="chat-shell">
    <ConversationRail />
  <section class="page page--talk chat-page">
    <header class="page-head chat-head">
      <RouterLink class="back-link" to="/friends/chat"><ArrowLeft :size="17" />返回会话</RouterLink>
      <div class="chat-identity">
        <span class="chat-avatar" aria-hidden="true">{{ peerInitial }}</span>
        <div>
          <p class="eyebrow">与好友对话<template v-if="peerName"> · LV.{{ peerLevel }} 成长者</template></p>
          <h1>{{ loading ? '正在打开会话…' : peerName }}</h1>
        </div>
      </div>
    </header>

    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <div ref="listElement" class="message-list" aria-live="polite">
      <p v-if="loading" class="empty">正在读取消息…</p>
      <template v-else-if="messages.length">
        <article v-for="message in messages" :key="message.publicId" class="msg-row" :class="{ mine: message.fromMe }">
          <span class="msg-avatar" :class="{ mine: message.fromMe }" aria-hidden="true">{{ message.fromMe ? myInitial : peerInitial }}</span>
          <div class="msg-main">
            <span class="msg-name">{{ message.fromMe ? myName : peerName }}</span>
            <div class="msg-bubble">
              <EmojiText :text="message.body" />
            </div>
            <time class="msg-time">{{ messageTime(message.createdAt) }}</time>
          </div>
        </article>
      </template>
      <div v-else class="empty">
        <Smile :size="26" />
        <h3>还没有消息</h3>
        <p>打个招呼，开始这段同行。</p>
      </div>
    </div>

    <div class="composer">
      <div v-if="showEmoji" class="picker-wrap">
        <EmojiPicker @pick="insertEmoji" />
      </div>
      <div class="composer-row">
        <button type="button" class="icon-button emoji-toggle" :class="{ active: showEmoji }" :aria-pressed="showEmoji" aria-label="选择表情" @click="showEmoji = !showEmoji"><Smile :size="18" /></button>
        <textarea v-model="draft" maxlength="1000" rows="2" placeholder="输入消息，Enter 发送" aria-label="消息内容" @keydown="keydown"></textarea>
        <button class="primary icon-button" type="button" :disabled="busy || !draft.trim()" aria-label="发送消息" @click="send"><Send :size="18" /></button>
      </div>
    </div>
  </section>
  </div>
</template>

<style scoped>
.chat-shell { display: flex; gap: 24px; align-items: flex-start; width: min(100%, 1264px); margin: 0 auto; padding: 0 32px; }
.chat-shell > .page { flex: 1; min-width: 0; padding-inline: 0; }
@media (max-width: 1099px) { .chat-shell { display: block; padding: 0; } .chat-shell > .page { padding-inline: 18px; } }

.chat-head { flex-direction: column; align-items: flex-start; gap: 16px; }
.back-link { display: inline-flex; align-items: center; gap: 6px; color: var(--muted); text-decoration: none; font-size: 13px; font-weight: 700; }
.back-link:hover { color: var(--primary); }
.chat-identity { display: flex; align-items: center; gap: 14px; }
.chat-avatar { width: 58px; height: 58px; display: grid; place-items: center; border-radius: 19px 19px 19px 6px; background: linear-gradient(145deg, var(--primary), color-mix(in srgb, var(--primary) 72%, var(--amber))); color: white; font-size: 24px; font-weight: 900; box-shadow: 0 11px 24px color-mix(in srgb, var(--primary) 20%, transparent); }
.chat-identity h1 { margin: 4px 0 0; }
.message-list { height: min(56vh, 520px); overflow-y: auto; display: grid; gap: 14px; align-content: start; padding: 18px 14px; border: 1px solid var(--border); border-radius: calc(var(--radius) + 4px); background: color-mix(in srgb, var(--surface) 88%, var(--canvas)); box-shadow: var(--shadow-soft); }
.msg-row { width: 100%; display: flex; gap: 10px; align-items: flex-start; }
.msg-row.mine { flex-direction: row-reverse; }
.msg-avatar { flex: none; width: 40px; height: 40px; display: grid; place-items: center; border-radius: 12px 12px 12px 4px; background: linear-gradient(145deg, var(--accent), color-mix(in srgb, var(--accent) 72%, var(--amber))); color: white; font-size: 17px; font-weight: 900; box-shadow: 0 7px 16px color-mix(in srgb, var(--accent) 18%, transparent); }
.msg-avatar.mine { background: linear-gradient(145deg, var(--primary), color-mix(in srgb, var(--primary) 72%, var(--amber))); box-shadow: 0 7px 16px color-mix(in srgb, var(--primary) 18%, transparent); }
.msg-main { flex: 1 1 0; min-width: 0; max-width: 100%; display: flex; flex-direction: column; align-items: flex-start; gap: 4px; }
.msg-row.mine .msg-main { align-items: flex-end; }
.msg-name { color: var(--muted); font-size: 12px; font-weight: 700; padding: 0 2px; }
.msg-bubble { min-width: 0; max-width: 70%; max-width: min(70%, 560px); padding: 9px 13px; border: 1px solid var(--border); border-radius: 4px 14px 14px 14px; background: var(--surface); color: var(--ink); box-shadow: var(--shadow-soft); overflow-wrap: break-word; }
.msg-row.mine .msg-bubble { border-color: color-mix(in srgb, var(--primary) 36%, var(--border)); border-radius: 14px 4px 14px 14px; background: linear-gradient(145deg, var(--primary-soft), color-mix(in srgb, var(--primary-soft) 60%, var(--surface))); }
.msg-time { color: var(--muted); font-size: 11px; padding: 0 2px; }
.empty { border: 1px dashed var(--border); border-radius: var(--radius); padding: 30px 20px; display: grid; place-items: center; justify-items: center; gap: 7px; color: var(--muted); text-align: center; }
.empty h3 { margin: 0; color: var(--ink); font-size: 16px; }
.empty p { margin: 0; font-size: 13px; }
.empty svg { color: var(--primary); }
.composer { position: sticky; bottom: 0; margin-top: 14px; display: grid; gap: 10px; }
.picker-wrap { justify-self: start; }
.composer-row { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; gap: 9px; align-items: end; padding: 10px; border: 1px solid var(--border); border-radius: calc(var(--radius) + 4px); background: var(--surface); box-shadow: var(--shadow-soft); }
.composer-row textarea { min-height: 44px; max-height: 120px; resize: vertical; }
.emoji-toggle.active { background: var(--primary-soft); color: var(--primary-strong); }
.chat-page { max-width: 1060px; }
.chat-head::before { display: none; }
.chat-head > :first-child { flex: none; }
.message-list { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-panel); padding: 24px; min-height: 45vh; }
.msg-avatar, .chat-avatar { border-radius: 50%; box-shadow: none; }
.composer { background: var(--canvas); padding-block: 10px; }
@media (max-width:760px) { .composer { bottom: calc(72px + env(safe-area-inset-bottom)); } .message-list { padding: 14px; } }
</style>
