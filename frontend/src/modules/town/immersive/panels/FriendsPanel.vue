<script setup lang="ts">
import { computed, inject, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ArrowLeft, Check, MessageCircle, Plus, Send, UserPlus, Users, UsersRound, X } from 'lucide-vue-next'
import { onDataChanged } from '../../../../shared/data-sync'
import EmojiPicker from '../../../friends/EmojiPicker.vue'
import EmojiText from '../../../friends/EmojiText.vue'
import {
  chatTimeLabel,
  friendInitial,
  useChatThread,
  useConversations,
  useFriendDirectory,
  useGroupComposer,
  type ConversationRow,
} from '../../../friends/friends.logic'
import { worldBridgeKey } from '../panel.types'
import { openFullPage } from './shared'
import { TownMailbox } from '../../social'

const FULL_PAGE = '/friends/chat'

const bridge = inject(worldBridgeKey, undefined)
const channel = ref<'mailbox' | 'chat'>('mailbox')
watch(channel, value => {
  if (value === 'chat') void convs.load(true)
  else {
    chat.stopPoll()
    view.value = 'list'
    emojiOpen.value = false
  }
})

// 三个子视图：list | directory | chat
const view = ref<'list' | 'directory' | 'chat'>('list')
const emojiOpen = ref(false)

// 好友目录（申请）
const directory = useFriendDirectory()
const requestEmail = ref('')

// 会话列表
const convs = useConversations()

// 聊天线程
const chat = useChatThread()

// 群聊创建
const groupComposer = useGroupComposer()

const listElement = ref<HTMLElement | null>(null)

async function scrollToBottom() {
  await nextTick()
  if (listElement.value) listElement.value.scrollTop = listElement.value.scrollHeight
}

watch(() => chat.messages.value.length, () => {
  if (view.value === 'chat') void scrollToBottom()
})

async function openDirectory() {
  view.value = 'directory'
  await directory.load(true)
}

function backToList() {
  view.value = 'list'
  requestEmail.value = ''
  emojiOpen.value = false
  chat.stopPoll()
  void convs.load(false)
}

async function sendFriendRequest() {
  const ok = await directory.sendRequest(requestEmail.value)
  if (ok) {
    requestEmail.value = ''
    bridge?.emit({ type: 'toast', text: directory.feedback.value })
  }
}

async function acceptRequest(item: { publicId: string; displayName: string; overallLevel: number; memberSince: string; status: 'PENDING' | 'ACCEPTED'; direction: 'INCOMING' | 'OUTGOING'; createdAt: string }) {
  await directory.accept(item)
  if (directory.feedback.value) bridge?.emit({ type: 'toast', text: directory.feedback.value })
}

async function rejectRequest(item: { publicId: string; displayName: string; overallLevel: number; memberSince: string; status: 'PENDING' | 'ACCEPTED'; direction: 'INCOMING' | 'OUTGOING'; createdAt: string }) {
  await directory.reject(item)
  if (directory.feedback.value) bridge?.emit({ type: 'toast', text: directory.feedback.value })
}

async function cancelRequest(item: { publicId: string; displayName: string; overallLevel: number; memberSince: string; status: 'PENDING' | 'ACCEPTED'; direction: 'INCOMING' | 'OUTGOING'; createdAt: string }) {
  await directory.remove(item)
  if (directory.feedback.value) bridge?.emit({ type: 'toast', text: directory.feedback.value })
}

async function removeFriend(item: { publicId: string; displayName: string; overallLevel: number; memberSince: string; status: 'PENDING' | 'ACCEPTED'; direction: 'INCOMING' | 'OUTGOING'; createdAt: string }) {
  await directory.remove(item)
  if (directory.feedback.value) bridge?.emit({ type: 'toast', text: directory.feedback.value })
}

async function openConversation(row: ConversationRow) {
  view.value = 'chat'
  emojiOpen.value = false
  await chat.open(row.kind, row.peerPublicId, row.displayName)
  chat.startPoll()
  await scrollToBottom()
}

async function sendMessage() {
  await chat.send()
  if (!chat.error.value) {
    emojiOpen.value = false
    await scrollToBottom()
  }
}

function keydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    void sendMessage()
  }
}

function insertEmoji(char: string) {
  chat.insertEmoji(char)
}

// 创建群聊流程
async function startGroupCreation() {
  await groupComposer.open()
}

function toggleGroupMember(publicId: string) {
  groupComposer.toggleMember(publicId)
}

async function createGroup() {
  const publicId = await groupComposer.create()
  if (publicId) {
    bridge?.emit({ type: 'toast', text: groupComposer.feedback.value })
    view.value = 'list'
    await convs.load(false)
  }
}

const stopDataSync = onDataChanged('social', () => {
  if (channel.value !== 'chat') return
  void convs.load(false)
  if (view.value === 'directory') void directory.load(false)
})

onBeforeUnmount(() => {
  chat.stopPoll()
  stopDataSync()
})
</script>

<template>
  <section class="world-panel friends-panel">
    <nav class="panel-actions" aria-label="邮递员服务">
      <button class="secondary compact" type="button" :aria-pressed="channel === 'mailbox'" @click="channel = 'mailbox'">小镇信箱</button>
      <button class="secondary compact" type="button" :aria-pressed="channel === 'chat'" @click="channel = 'chat'">好友聊天</button>
    </nav>
    <TownMailbox v-if="channel === 'mailbox'" @unread-change="bridge?.emit({ type: 'mail-count', count: $event })" />
    <template v-else>
    <!-- 会话列表 -->
    <template v-if="view === 'list'">
      <div class="panel-actions">
        <button class="secondary compact" type="button" @click="openDirectory">
          <UserPlus :size="14" />管理好友
        </button>
        <button class="secondary compact" type="button" @click="startGroupCreation">
          <Users :size="14" />创建群聊
        </button>
      </div>
      <p v-if="convs.error.value" class="error" role="alert">{{ convs.error.value }}</p>
      <p v-if="convs.loading.value" class="empty">正在整理会话…</p>
      <template v-else-if="convs.conversations.value.length">
        <div class="conversation-list">
          <button
            v-for="item in convs.conversations.value"
            :key="`${item.kind}-${item.peerPublicId}`"
            type="button"
            class="conversation-row"
            @click="openConversation(item)"
          >
            <span class="avatar" :class="{ group: item.kind === 'group' }" aria-hidden="true">
              <UsersRound v-if="item.kind === 'group'" :size="16" />
              <template v-else>{{ friendInitial(item.displayName) }}</template>
            </span>
            <span class="conversation-copy">
              <span class="conversation-line">
                <strong>{{ item.displayName }}</strong>
                <time v-if="item.lastMessageAt">{{ chatTimeLabel(item.lastMessageAt) }}</time>
              </span>
              <span class="conversation-preview">
                <EmojiText v-if="item.lastMessage" :text="item.lastMessage" />
                <span v-else class="muted">还没有消息</span>
              </span>
            </span>
            <span v-if="item.unreadCount" class="unread-badge">{{ item.unreadCount > 99 ? '99+' : item.unreadCount }}</span>
          </button>
        </div>
      </template>
      <div v-else class="empty">
        <MessageCircle :size="20" />
        <p>还没有会话，找到好友后发第一句问候吧。</p>
      </div>
    </template>

    <!-- 好友目录（申请管理） -->
    <template v-else-if="view === 'directory'">
      <header class="sub-head">
        <button class="icon-button" type="button" aria-label="返回会话列表" @click="backToList"><ArrowLeft :size="15" /></button>
        <strong>好友管理</strong>
      </header>

      <form class="add-friend-form" @submit.prevent="sendFriendRequest">
        <label class="field compact">
          <span class="field-label">添加好友</span>
          <input v-model="requestEmail" type="email" placeholder="输入对方的注册邮箱" maxlength="254" />
        </label>
        <button class="primary compact" type="submit" :disabled="directory.busy.value || !requestEmail.trim()">
          <Send :size="14" />发送
        </button>
      </form>

      <p v-if="directory.error.value" class="error" role="alert">{{ directory.error.value }}</p>
      <p v-if="directory.loading.value" class="empty">正在加载…</p>

      <div v-else class="directory-scroll">
        <section v-if="directory.list.value.incoming.length" class="directory-section">
          <h3 class="section-title">收到的申请 <span class="count-badge">{{ directory.list.value.incoming.length }}</span></h3>
          <div class="friend-list">
            <article v-for="item in directory.list.value.incoming" :key="item.publicId" class="friend-card">
              <span class="friend-avatar" aria-hidden="true">{{ friendInitial(item.displayName) }}</span>
              <div class="friend-copy">
                <strong>{{ item.displayName }}</strong>
                <small>LV.{{ item.overallLevel }}</small>
              </div>
              <div class="friend-actions">
                <button class="primary icon-button compact" type="button" :disabled="directory.busy.value" @click="acceptRequest(item)">
                  <Check :size="14" />
                </button>
                <button class="secondary icon-button compact" type="button" :disabled="directory.busy.value" @click="rejectRequest(item)">
                  <X :size="14" />
                </button>
              </div>
            </article>
          </div>
        </section>

        <section v-if="directory.list.value.outgoing.length" class="directory-section">
          <h3 class="section-title">已发出的申请 <span class="count-badge">{{ directory.list.value.outgoing.length }}</span></h3>
          <div class="friend-list">
            <article v-for="item in directory.list.value.outgoing" :key="item.publicId" class="friend-card">
              <span class="friend-avatar" aria-hidden="true">{{ friendInitial(item.displayName) }}</span>
              <div class="friend-copy">
                <strong>{{ item.displayName }}</strong>
                <small>等待接受</small>
              </div>
              <button class="secondary compact" type="button" :disabled="directory.busy.value" @click="cancelRequest(item)">
                取消
              </button>
            </article>
          </div>
        </section>

        <section v-if="directory.list.value.friends.length" class="directory-section">
          <h3 class="section-title">好友列表 <span class="count-badge">{{ directory.list.value.friends.length }}</span></h3>
          <div class="friend-list">
            <article v-for="item in directory.list.value.friends" :key="item.publicId" class="friend-card">
              <span class="friend-avatar" aria-hidden="true">{{ friendInitial(item.displayName) }}</span>
              <div class="friend-copy">
                <strong>{{ item.displayName }}</strong>
                <small>LV.{{ item.overallLevel }}</small>
              </div>
              <button class="secondary compact" type="button" :disabled="directory.busy.value" @click="removeFriend(item)">
                删除
              </button>
            </article>
          </div>
        </section>
      </div>
    </template>

    <!-- 聊天 -->
    <template v-else-if="view === 'chat'">
      <header class="sub-head">
        <button class="icon-button" type="button" aria-label="返回会话列表" @click="backToList"><ArrowLeft :size="15" /></button>
        <strong>{{ chat.peerName.value }}</strong>
      </header>
      <p v-if="chat.error.value" class="error" role="alert">{{ chat.error.value }}</p>
      <div ref="listElement" class="chat-body" aria-live="polite">
        <p v-if="chat.loading.value" class="empty">正在读取消息…</p>
        <template v-else-if="chat.messages.value.length">
          <article v-for="message in chat.messages.value" :key="message.publicId" class="msg-row" :class="{ mine: message.fromMe }">
            <span v-if="!message.fromMe && message.senderName" class="msg-name">{{ message.senderName }}</span>
            <div class="msg-bubble"><EmojiText :text="message.body" /></div>
            <time>{{ chatTimeLabel(message.createdAt) }}</time>
          </article>
        </template>
        <div v-else class="empty">还没有消息，打个招呼开始这段同行。</div>
      </div>
      <div v-if="emojiOpen" class="emoji-panel">
        <EmojiPicker @select="insertEmoji" />
      </div>
      <form class="chat-composer" @submit.prevent="sendMessage">
        <button class="secondary icon-button compact" type="button" :aria-pressed="emojiOpen" @click="emojiOpen = !emojiOpen">
          😊
        </button>
        <label class="sr-only" for="friends-panel-input">输入消息</label>
        <textarea
          id="friends-panel-input"
          v-model="chat.draft.value"
          maxlength="1000"
          rows="1"
          placeholder="Enter 发送"
          :disabled="chat.busy.value"
          @input="chat.saveDraft()"
          @keydown="keydown"
        ></textarea>
        <button class="primary icon-button compact" type="submit" :disabled="chat.busy.value || !chat.draft.value.trim()" aria-label="发送消息">
          <Send :size="16" />
        </button>
      </form>
    </template>

    <!-- 创建群聊弹窗式覆盖 -->
    <div v-if="groupComposer.creating.value" class="modal-overlay" @click.self="groupComposer.close()">
      <div class="modal-card">
        <header class="modal-head">
          <strong>创建群聊</strong>
          <button class="icon-button" type="button" aria-label="关闭" @click="groupComposer.close()"><X :size="16" /></button>
        </header>
        <p v-if="groupComposer.error.value" class="error" role="alert">{{ groupComposer.error.value }}</p>
        <form class="modal-body" @submit.prevent="createGroup">
          <label class="field">
            <span class="field-label">群聊名称</span>
            <input v-model="groupComposer.groupName.value" type="text" placeholder="给群聊起个名字" maxlength="50" required />
          </label>
          <fieldset class="member-picker">
            <legend class="field-label">选择好友（最多 9 位）</legend>
            <div class="member-list">
              <label
                v-for="friend in groupComposer.friends.value"
                :key="friend.publicId"
                class="member-option"
                :class="{ selected: groupComposer.selected.value.has(friend.publicId) }"
              >
                <input
                  type="checkbox"
                  :checked="groupComposer.selected.value.has(friend.publicId)"
                  :disabled="!groupComposer.selected.value.has(friend.publicId) && groupComposer.selected.value.size >= 9"
                  @change="toggleGroupMember(friend.publicId)"
                />
                <span class="friend-avatar mini" aria-hidden="true">{{ friendInitial(friend.displayName) }}</span>
                <span>{{ friend.displayName }}</span>
              </label>
            </div>
          </fieldset>
          <div class="modal-actions">
            <button class="secondary" type="button" @click="groupComposer.close()">取消</button>
            <button class="primary" type="submit" :disabled="groupComposer.busy.value || !groupComposer.groupName.value.trim() || !groupComposer.selected.value.size">
              <Plus :size="14" />创建
            </button>
          </div>
        </form>
      </div>
    </div>

    <footer class="panel-footer">
      <span v-if="convs.totalUnread.value && view === 'list'" class="muted">{{ convs.totalUnread.value }} 条未读</span>
      <span v-else />
      <button class="secondary compact" type="button" @click="openFullPage(bridge, FULL_PAGE)">完整页面</button>
    </footer>
    </template>
  </section>
</template>

<style scoped>
.world-panel { display: grid; gap: 10px; width: 100%; color: var(--ink); position: relative; }
.panel-actions { display: flex; gap: 6px; flex-wrap: wrap; }
.conversation-list { display: grid; gap: 6px; max-height: 340px; overflow-y: auto; }
.conversation-row { display: grid; grid-template-columns: 34px minmax(0, 1fr) auto; align-items: center; gap: 9px; padding: 8px 10px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); color: var(--ink); text-align: left; transition: background 0.15s; }
.conversation-row:hover { background: var(--surface-muted); }
.avatar { width: 32px; height: 32px; display: grid; place-items: center; border-radius: 50%; background: linear-gradient(145deg, var(--primary), color-mix(in srgb, var(--primary) 72%, var(--amber))); color: white; font-size: 13px; font-weight: 800; flex: none; }
.avatar.group { background: linear-gradient(145deg, var(--accent), color-mix(in srgb, var(--accent) 72%, var(--amber))); }
.conversation-copy { min-width: 0; display: grid; gap: 2px; }
.conversation-line { display: flex; align-items: baseline; gap: 6px; min-width: 0; }
.conversation-line strong { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; }
.conversation-line time { margin-left: auto; font-size: 10px; color: var(--muted); white-space: nowrap; }
.conversation-preview { font-size: 12px; color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.conversation-preview :deep(.emoji-img) { width: 1.1em; height: 1.1em; vertical-align: -0.2em; }
.unread-badge { min-width: 18px; height: 18px; display: grid; place-items: center; padding: 0 5px; border-radius: 999px; background: var(--primary); color: white; font-size: 10px; font-weight: 800; }

.sub-head { display: flex; align-items: center; gap: 8px; }
.sub-head strong { font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.add-friend-form { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px; align-items: end; }
.field { display: grid; gap: 4px; }
.field.compact { gap: 3px; }
.field-label { font-size: 11px; font-weight: 600; color: var(--muted); }
.field input, .field textarea { border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); color: var(--ink); padding: 7px 9px; font-size: 13px; }
button.compact { font-size: 12px; height: 30px; padding: 0 10px; }
button.icon-button.compact { width: 30px; height: 30px; padding: 0; }

.directory-scroll { max-height: 380px; overflow-y: auto; display: grid; gap: 14px; }
.directory-section { display: grid; gap: 8px; }
.section-title { font-size: 12px; font-weight: 700; color: var(--muted); display: flex; align-items: center; gap: 6px; }
.count-badge { display: inline-grid; place-items: center; min-width: 18px; height: 18px; padding: 0 5px; border-radius: 999px; background: var(--primary-soft); color: var(--primary-strong); font-size: 10px; font-weight: 800; }
.friend-list { display: grid; gap: 6px; }
.friend-card { display: grid; grid-template-columns: 28px minmax(0, 1fr) auto; align-items: center; gap: 8px; padding: 7px 9px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); }
.friend-avatar { width: 28px; height: 28px; display: grid; place-items: center; border-radius: 50%; background: linear-gradient(145deg, var(--primary), color-mix(in srgb, var(--primary) 72%, var(--amber))); color: white; font-size: 12px; font-weight: 800; }
.friend-avatar.mini { width: 24px; height: 24px; font-size: 11px; }
.friend-copy { min-width: 0; display: grid; gap: 1px; }
.friend-copy strong { font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.friend-copy small { font-size: 11px; color: var(--muted); }
.friend-actions { display: flex; gap: 4px; }

.chat-body { min-height: 220px; max-height: 300px; overflow-y: auto; display: grid; gap: 8px; align-content: start; padding: 10px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); }
.msg-row { display: grid; gap: 2px; justify-items: start; }
.msg-row.mine { justify-items: end; }
.msg-name { font-size: 10px; color: var(--muted); }
.msg-bubble { max-width: 80%; padding: 7px 10px; border: 1px solid var(--border); border-radius: 4px 12px 12px 12px; background: var(--surface-muted); font-size: 12px; overflow-wrap: anywhere; }
.msg-row.mine .msg-bubble { border-radius: 12px 4px 12px 12px; background: color-mix(in srgb, var(--primary-soft) 65%, var(--surface)); }
.msg-row time { font-size: 10px; color: var(--muted); }
.emoji-panel { max-height: 180px; overflow-y: auto; padding: 8px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); }
.chat-composer { display: grid; grid-template-columns: 30px minmax(0, 1fr) 30px; gap: 6px; align-items: end; }
.chat-composer textarea { min-height: 36px; max-height: 80px; resize: vertical; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); color: var(--ink); padding: 7px 9px; font-size: 13px; }

.modal-overlay { position: fixed; inset: 0; background: color-mix(in srgb, var(--ink) 30%, transparent); display: grid; place-items: center; z-index: 100; }
.modal-card { width: min(420px, 90vw); max-height: 80vh; display: grid; grid-template-rows: auto auto minmax(0, 1fr); background: var(--surface); border: 1px solid var(--border); border-radius: 8px; box-shadow: 0 8px 24px color-mix(in srgb, var(--ink) 20%, transparent); }
.modal-head { display: flex; align-items: center; justify-content: space-between; padding: 12px 14px; border-bottom: 1px solid var(--border); }
.modal-head strong { font-size: 14px; }
.modal-body { padding: 14px; display: grid; gap: 12px; overflow-y: auto; }
.member-picker { border: none; padding: 0; margin: 0; display: grid; gap: 6px; }
.member-picker legend { margin-bottom: 2px; }
.member-list { display: grid; gap: 4px; max-height: 200px; overflow-y: auto; }
.member-option { display: grid; grid-template-columns: auto 24px minmax(0, 1fr); align-items: center; gap: 8px; padding: 6px 8px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); font-size: 12px; cursor: pointer; transition: background 0.15s; }
.member-option:hover { background: var(--surface-muted); }
.member-option.selected { background: var(--primary-soft); border-color: var(--primary); }
.member-option input[type="checkbox"] { cursor: pointer; }
.modal-actions { display: flex; gap: 8px; justify-content: flex-end; }

.panel-footer { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.muted { color: var(--muted); font-size: 12px; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0,0,0,0); }
</style>
