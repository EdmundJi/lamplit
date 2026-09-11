<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowLeft, Send, Smile, Users } from 'lucide-vue-next'
import { useAuthStore } from '../auth/auth.store'
import EmptyState from '../../shared/ui/EmptyState.vue'
import EmojiPicker from './EmojiPicker.vue'
import ConversationRail from './ConversationRail.vue'
import EmojiText from './EmojiText.vue'
import { chatTimeLabel as messageTime, friendInitial as initialOf, useChatThread, useGroupInfo } from './friends.logic'

const props = defineProps<{ publicId: string }>()

const auth = useAuthStore()
const { messages, loading, error, busy, draft, open, startPoll, stopPoll, send: submit, insertEmoji } = useChatThread()
const { groupName, members, load: loadGroupInfo } = useGroupInfo()
const showEmoji = ref(false)
const listElement = ref<HTMLElement | null>(null)

const myName = computed(() => auth.user?.displayName ?? '我')
const myInitial = computed(() => initialOf(myName.value))
const memberOf = computed(() => new Map(members.value.map(member => [member.publicId, member])))

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
  await Promise.all([loadGroupInfo(props.publicId), open('group', props.publicId, '')])
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
        <span class="group-avatar" aria-hidden="true"><Users :size="22" /></span>
        <div>
          <p class="eyebrow">群聊 · {{ members.length }} 人</p>
          <h1>{{ loading ? '正在打开群聊…' : groupName }}</h1>
        </div>
      </div>
    </header>

    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <div ref="listElement" class="message-list" aria-live="polite">
      <p v-if="loading" class="empty">正在读取消息…</p>
      <template v-else-if="messages.length">
        <article v-for="message in messages" :key="message.publicId" class="msg-row" :class="{ mine: message.fromMe }">
          <span class="msg-avatar" :class="{ mine: message.fromMe }" aria-hidden="true">{{ message.fromMe ? myInitial : initialOf(message.senderName ?? '') }}</span>
          <div class="msg-main">
            <span class="msg-name">{{ message.fromMe ? myName : message.senderName }}</span>
            <div class="msg-bubble">
              <EmojiText :text="message.body" />
            </div>
            <time class="msg-time">{{ messageTime(message.createdAt) }}</time>
          </div>
        </article>
      </template>
      <EmptyState v-else sprite="duck_brown_idle_1" title="群里还没有消息" description="打个招呼，开始这段同行。" />
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
.group-avatar { width: 58px; height: 58px; display: grid; place-items: center; border-radius: var(--radius-panel) var(--radius-panel) var(--radius-panel) var(--radius); background: linear-gradient(145deg, var(--accent), color-mix(in srgb, var(--accent) 72%, var(--amber))); color: white; }
.chat-identity h1 { margin: 4px 0 0; }
.message-list { height: min(56vh, 520px); overflow-y: auto; display: grid; gap: 14px; align-content: start; padding: 18px 14px; border: 1px solid var(--border); border-radius: calc(var(--radius) + 4px); background: color-mix(in srgb, var(--surface) 88%, var(--canvas)); }
.msg-row { width: 100%; display: flex; gap: 10px; align-items: flex-start; }
.msg-row.mine { flex-direction: row-reverse; }
.msg-avatar { flex: none; width: 40px; height: 40px; display: grid; place-items: center; border-radius: 12px 12px 12px 4px; background: linear-gradient(145deg, var(--accent), color-mix(in srgb, var(--accent) 72%, var(--amber))); color: white; font-size: 17px; font-weight: 900; }
.msg-avatar.mine { background: linear-gradient(145deg, var(--primary), color-mix(in srgb, var(--primary) 72%, var(--amber))); }
.msg-main { flex: 1 1 0; min-width: 0; max-width: 100%; display: flex; flex-direction: column; align-items: flex-start; gap: 4px; }
.msg-row.mine .msg-main { align-items: flex-end; }
.msg-name { color: var(--muted); font-size: 12px; font-weight: 700; padding: 0 2px; }
.msg-bubble { min-width: 0; max-width: 70%; max-width: min(70%, 560px); padding: 9px 13px; border: 1px solid var(--border); border-radius: var(--radius) var(--radius-panel) var(--radius-panel) var(--radius-panel); background: var(--surface); color: var(--ink); overflow-wrap: break-word; }
.msg-row.mine .msg-bubble { border-color: color-mix(in srgb, var(--primary) 36%, var(--border)); border-radius: var(--radius-panel) var(--radius) var(--radius-panel) var(--radius-panel); background: linear-gradient(145deg, var(--primary-soft), color-mix(in srgb, var(--primary-soft) 60%, var(--surface))); }
.msg-time { color: var(--muted); font-size: 11px; padding: 0 2px; }
.composer { position: sticky; bottom: 0; margin-top: 14px; display: grid; gap: 10px; }
.picker-wrap { justify-self: start; }
.composer-row { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; gap: 9px; align-items: end; padding: 10px; border: 1px solid var(--border); border-radius: calc(var(--radius) + 4px); background: var(--surface); }
.composer-row textarea { min-height: 44px; max-height: 120px; resize: vertical; }
.emoji-toggle.active { background: var(--primary-soft); color: var(--primary-strong); }
.chat-page { max-width: 1060px; }
.chat-head::before { display: none; }
.chat-head > :first-child { flex: none; }
.message-list { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-panel); padding: 24px; min-height: 45vh; }
.msg-avatar, .chat-avatar { border-radius: 50%; }
.composer { background: var(--canvas); padding-block: 10px; }
@media (max-width:760px) { .composer { bottom: calc(72px + env(safe-area-inset-bottom)); } .message-list { padding: 14px; } }
</style>
