<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ArrowLeft, Send, Smile, Users } from 'lucide-vue-next'
import { api, type ApiError } from '../../shared/api/client'
import { notifyDataChanged } from '../../shared/data-sync'
import { useAuthStore } from '../auth/auth.store'
import EmojiPicker from './EmojiPicker.vue'
import EmojiText from './EmojiText.vue'
import type { GroupMember, GroupMessage } from './friends.types'

const props = defineProps<{ publicId: string }>()

const auth = useAuthStore()
const messages = ref<GroupMessage[]>([])
const groupName = ref('')
const members = ref<GroupMember[]>([])
const draft = ref('')
const showEmoji = ref(false)
const loading = ref(true)
const error = ref('')
const busy = ref(false)
const listElement = ref<HTMLElement | null>(null)
let pollTimer: number | undefined

const myName = computed(() => auth.user?.displayName ?? '我')
const myInitial = computed(() => {
  const characters = Array.from(myName.value.trim())
  if (!characters.length) return '好'
  if (/\p{Script=Han}/u.test(characters[0])) return characters[0]
  return characters[0].toUpperCase()
})
const memberOf = computed(() => new Map(members.value.map(member => [member.publicId, member])))

function initialOf(name: string) {
  const characters = Array.from(name.trim())
  if (!characters.length) return '好'
  if (/\p{Script=Han}/u.test(characters[0])) return characters[0]
  return characters[0].toUpperCase()
}

function messageTime(value: string) {
  const date = new Date(value)
  const now = new Date()
  const sameDay = date.getFullYear() === now.getFullYear()
    && date.getMonth() === now.getMonth()
    && date.getDate() === now.getDate()
  if (sameDay) {
    return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(date)
  }
  return new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(date)
}

function scrollToBottom() {
  if (listElement.value) listElement.value.scrollTop = listElement.value.scrollHeight
}

async function loadMessages() {
  return api.get<GroupMessage[]>(`/friends/groups/${encodeURIComponent(props.publicId)}/messages`)
}

async function load() {
  error.value = ''
  try {
    const [group, loaded] = await Promise.all([
      api.get<{ publicId: string; name: string; members: GroupMember[] }>(`/friends/groups/${encodeURIComponent(props.publicId)}`),
      loadMessages(),
    ])
    groupName.value = group.name
    members.value = group.members
    messages.value = loaded
    await api.post<unknown>(`/friends/groups/${encodeURIComponent(props.publicId)}/read`)
    await nextTick()
    scrollToBottom()
  } catch (err) {
    error.value = (err as Partial<ApiError> | null)?.code === 'GROUP_NOT_FOUND'
      ? '群聊不存在或你不在群内'
      : '消息暂时无法加载'
  } finally {
    loading.value = false
  }
}

async function poll() {
  if (document.hidden) return
  try {
    const incoming = await loadMessages()
    if (incoming.length > messages.value.length) {
      messages.value = incoming
      await api.post<unknown>(`/friends/groups/${encodeURIComponent(props.publicId)}/read`)
      await nextTick()
      scrollToBottom()
    }
  } catch {
    // 轮询失败静默
  }
}

async function send() {
  const body = draft.value.trim()
  if (!body) return
  busy.value = true
  error.value = ''
  try {
    await api.post<GroupMessage>(`/friends/groups/${encodeURIComponent(props.publicId)}/messages`, { body })
    draft.value = ''
    showEmoji.value = false
    messages.value = await loadMessages()
    notifyDataChanged('social')
    await nextTick()
    scrollToBottom()
  } catch (err) {
    error.value = (err as Partial<ApiError> | null)?.code === 'INVALID_MESSAGE_BODY'
      ? '消息内容不能为空或超过 1000 个字符'
      : '发送失败，请稍后重试'
  } finally {
    busy.value = false
  }
}

function insertEmoji(char: string) {
  draft.value += char
}

function keydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    send()
  }
}

onMounted(async () => {
  await load()
  pollTimer = window.setInterval(poll, 4000)
})

onBeforeUnmount(() => {
  if (pollTimer) window.clearInterval(pollTimer)
})
</script>

<template>
  <section class="page chat-page">
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
          <span class="msg-avatar" :class="{ mine: message.fromMe }" aria-hidden="true">{{ message.fromMe ? myInitial : initialOf(message.senderName) }}</span>
          <div class="msg-main">
            <span class="msg-name">{{ message.fromMe ? myName : message.senderName }}</span>
            <div class="msg-bubble">
              <EmojiText :text="message.body" />
            </div>
            <time class="msg-time">{{ messageTime(message.createdAt) }}</time>
          </div>
        </article>
      </template>
      <div v-else class="empty">
        <Smile :size="26" />
        <h3>群里还没有消息</h3>
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
</template>

<style scoped>
.chat-head { flex-direction: column; align-items: flex-start; gap: 16px; }
.back-link { display: inline-flex; align-items: center; gap: 6px; color: var(--muted); text-decoration: none; font-size: 13px; font-weight: 700; }
.back-link:hover { color: var(--primary); }
.chat-identity { display: flex; align-items: center; gap: 14px; }
.group-avatar { width: 58px; height: 58px; display: grid; place-items: center; border-radius: 19px 19px 19px 6px; background: linear-gradient(145deg, var(--accent), color-mix(in srgb, var(--accent) 72%, var(--amber))); color: white; box-shadow: 0 11px 24px color-mix(in srgb, var(--accent) 20%, transparent); }
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
</style>
