<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ChevronRight, MessageCircle, MessagesSquare, Plus, Users, UsersRound, X } from 'lucide-vue-next'
import { onDataChanged } from '../../shared/data-sync'
import EmojiText from './EmojiText.vue'
import { friendInitial as initial, chatTimeLabel as timeLabel, useConversations, useGroupComposer, type ConversationRow } from './friends.logic'

const router = useRouter()
const { conversations, loading, error, load } = useConversations()
const {
  creating: groupCreating, friends: groupFriends, groupName, selected: groupSelected, busy: groupBusy,
  error: groupError, feedback: groupFeedback, open: openGroupCreate, close: closeGroupCreate,
  toggleMember: toggleGroupMember, create: submitGroupCreate,
} = useGroupComposer()

function targetOf(row: ConversationRow) {
  return row.kind === 'group' ? `/friends/groups/${row.peerPublicId}` : `/friends/${row.peerPublicId}/chat`
}

async function createGroup() {
  const publicId = await submitGroupCreate()
  if (publicId) {
    await load(false)
    router.push(`/friends/groups/${publicId}`)
  }
}

const stopDataSync = onDataChanged('social', () => load(false))

onMounted(() => load(true))
onBeforeUnmount(stopDataSync)
</script>

<template>
  <section class="page conversations-page">
    <header class="page-head">
      <div>
        <p class="eyebrow">同行的回音</p>
        <h1>消息</h1>
      </div>
      <div class="head-actions">
        <div v-if="conversations.length" class="conversation-count"><MessagesSquare :size="16" /><span>{{ conversations.length }} 个会话</span></div>
        <button class="primary create-group-button" type="button" @click="openGroupCreate"><Plus :size="16" />发起群聊</button>
      </div>
    </header>

    <p v-if="error || groupError" class="error" role="alert">{{ error || groupError }}</p>
    <div v-if="groupFeedback" class="feedback-banner" data-tone="support" role="status" aria-live="polite"><MessagesSquare :size="19" /><p>{{ groupFeedback }}</p></div>

    <form v-if="groupCreating" class="band group-create" @submit.prevent="createGroup">
      <div class="section-title">
        <div><p class="eyebrow">群聊</p><h2>选择好友发起群聊（最多 10 人）</h2></div>
        <button type="button" class="icon-button" aria-label="关闭" @click="closeGroupCreate"><X :size="18" /></button>
      </div>
      <div class="field"><label for="group-name">群聊名称</label><input id="group-name" v-model="groupName" maxlength="80" placeholder="例如：周末学习小组" /></div>
      <div class="member-pick">
        <label v-for="item in groupFriends" :key="item.publicId" class="member-option" :class="{ checked: groupSelected.has(item.publicId) }">
          <input type="checkbox" :checked="groupSelected.has(item.publicId)" :disabled="!groupSelected.has(item.publicId) && groupSelected.size >= 9" @change="toggleGroupMember(item.publicId)" />
          <span class="member-avatar" aria-hidden="true">{{ initial(item.displayName) }}</span>
          <span class="member-copy"><strong>{{ item.displayName }}</strong><small>LV.{{ item.overallLevel }} 成长者</small></span>
          <span v-if="groupSelected.has(item.publicId)" class="member-check">已选</span>
        </label>
        <p v-if="!groupFriends.length" class="muted">还没有好友，先去添加好友吧。</p>
      </div>
      <div class="actions"><button class="primary" type="submit" :disabled="groupBusy">创建群聊（{{ groupSelected.size }} / 9）</button></div>
    </form>

    <p v-if="loading" class="empty">正在整理会话…</p>
    <template v-else>
      <section v-if="conversations.length" class="conversation-list">
        <RouterLink v-for="item in conversations" :key="`${item.kind}-${item.peerPublicId}`" class="conversation-card" :to="targetOf(item)">
          <span class="conversation-avatar" :class="{ group: item.kind === 'group' }" aria-hidden="true">
            <UsersRound v-if="item.kind === 'group'" :size="20" />
            <template v-else>{{ initial(item.displayName) }}</template>
          </span>
          <div class="conversation-copy">
            <div class="conversation-line">
              <strong>{{ item.displayName }}</strong>
              <span v-if="item.kind === 'group'" class="conversation-level">{{ item.memberCount }} 人</span>
              <span v-else class="conversation-level">LV.{{ item.level }}</span>
              <time v-if="item.lastMessageAt">{{ timeLabel(item.lastMessageAt) }}</time>
            </div>
            <div class="conversation-preview">
              <span v-if="item.kind === 'single' && item.lastMessageFromMe" class="preview-me">我:</span>
              <EmojiText v-if="item.lastMessage" :text="item.lastMessage" />
              <span v-else class="preview-muted">还没有消息</span>
            </div>
          </div>
          <div class="conversation-side">
            <span v-if="item.unreadCount" class="unread-badge" :aria-label="`${item.unreadCount} 条未读`">{{ item.unreadCount }}</span>
            <ChevronRight :size="17" />
          </div>
        </RouterLink>
      </section>
      <div v-else class="empty">
        <MessageCircle :size="26" />
        <h3>还没有会话</h3>
        <p>在好友列表里找到同行的人，发第一句问候，或发起一个群聊。</p>
      </div>
    </template>
  </section>
</template>

<style scoped>
.head-actions { display: flex; align-items: center; gap: 10px; }
.conversation-count { display: inline-flex; align-items: center; gap: 8px; padding: 8px 13px; border: 1px solid var(--border); border-radius: 999px; background: var(--surface); color: var(--primary); font-size: 13px; font-weight: 700; }
.create-group-button { display: inline-flex; align-items: center; gap: 7px; }
.section-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; color: var(--primary); }
.section-title h2 { margin: 0; font-size: 18px; color: var(--ink); }
.group-create { display: grid; gap: 14px; }
.member-pick { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 9px; }
.member-option { min-width: 0; display: grid; grid-template-columns: 20px 42px minmax(0, 1fr) auto; align-items: center; gap: 10px; padding: 11px 13px; border: 1px solid var(--border); border-radius: var(--radius); background: color-mix(in srgb, var(--surface) 90%, transparent); cursor: pointer; }
.member-option.checked { border-color: color-mix(in srgb, var(--primary) 42%, var(--border)); background: color-mix(in srgb, var(--primary-soft) 55%, var(--surface)); }
.member-option input { accent-color: var(--primary); }
.member-avatar { width: 40px; height: 40px; display: grid; place-items: center; border-radius: var(--radius-card) var(--radius-card) var(--radius-card) var(--radius); background: linear-gradient(145deg, var(--primary), color-mix(in srgb, var(--primary) 72%, var(--amber))); color: white; font-size: 17px; font-weight: 900; }
.member-copy { min-width: 0; display: grid; gap: 3px; }
.member-copy strong { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 14px; }
.member-copy small { color: var(--muted); font-size: 12px; }
.member-check { padding: 2px 8px; border-radius: 999px; background: var(--primary); color: white; font-size: 11px; font-weight: 800; }
.conversation-list { display: grid; gap: 10px; }
.conversation-card { min-width: 0; display: grid; grid-template-columns: 48px minmax(0, 1fr) auto; align-items: center; gap: 13px; padding: 14px 15px; border: 1px solid var(--border); border-radius: var(--radius); background: color-mix(in srgb, var(--surface) 90%, transparent); color: var(--ink); text-decoration: none; transition: border-color var(--motion-fast) var(--ease); }
.conversation-card:hover { border-color: color-mix(in srgb, var(--primary) 28%, var(--border)); }
.conversation-avatar { width: 46px; height: 46px; display: grid; place-items: center; border-radius: var(--radius-panel) var(--radius-panel) var(--radius-panel) var(--radius); background: linear-gradient(145deg, var(--primary), color-mix(in srgb, var(--primary) 72%, var(--amber))); color: white; font-size: 19px; font-weight: 900; }
.conversation-avatar.group { background: linear-gradient(145deg, var(--accent), color-mix(in srgb, var(--accent) 72%, var(--amber))); }
.conversation-copy { min-width: 0; display: grid; gap: 5px; }
.conversation-line { min-width: 0; display: flex; align-items: baseline; gap: 8px; }
.conversation-line strong { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 15px; }
.conversation-level { flex: none; padding: 1px 7px; border-radius: 999px; background: var(--surface-muted); color: var(--muted); font-size: 11px; font-weight: 800; }
.conversation-line time { margin-left: auto; color: var(--muted); font-size: 11px; white-space: nowrap; }
.conversation-preview { min-width: 0; display: flex; gap: 5px; align-items: baseline; color: var(--muted); font-size: 13px; line-height: 1.5; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.conversation-preview :deep(.emoji-img) { width: 1.15em; height: 1.15em; vertical-align: -0.2em; }
.preview-me { flex: none; color: var(--primary); font-weight: 700; }
.preview-muted { color: var(--muted); }
.conversation-side { display: grid; gap: 8px; justify-items: end; color: var(--muted); }
.unread-badge { min-width: 20px; height: 20px; display: grid; place-items: center; padding: 0 6px; border-radius: 999px; background: var(--primary); color: white; font-size: 11px; font-weight: 800; }
.empty { border: 1px dashed var(--border); border-radius: var(--radius); padding: 34px 20px; display: grid; place-items: center; justify-items: center; gap: 7px; color: var(--muted); text-align: center; }
.empty h3 { margin: 0; color: var(--ink); font-size: 16px; }
.empty p { margin: 0; font-size: 13px; }
.empty svg { color: var(--primary); }
@media (prefers-reduced-motion: no-preference) {
  .conversation-card, .member-option { animation: conversation-enter var(--motion-medium) var(--ease) both; }
  .conversation-card:nth-child(2), .member-option:nth-child(2) { animation-delay: 50ms; }
  .conversation-card:nth-child(3), .member-option:nth-child(3) { animation-delay: 100ms; }
  .conversation-card:nth-child(4), .member-option:nth-child(4) { animation-delay: 150ms; }
}
@keyframes conversation-enter { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: translateY(0); } }
@media (max-width: 720px) {
  .member-pick { grid-template-columns: 1fr; }
  .head-actions { width: 100%; justify-content: space-between; gap: 8px; }
}
@media (max-width: 420px) {
  .head-actions { display: grid; grid-template-columns: 1fr; align-items: stretch; }
  .conversation-count, .create-group-button { width: 100%; justify-content: center; }
}
.conversation-list { gap: 0; background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-panel); overflow: hidden; }
.conversation-card { border: 0; border-bottom: 1px solid var(--border); border-radius: 0; padding: 22px; background: transparent; }
.conversation-card:last-child { border-bottom: 0; }
.member-avatar { border-radius: 50%; background: var(--primary-soft); color: var(--primary-strong); }
.group-create { padding: 24px; margin-bottom: 24px; border: 1px solid var(--border); border-radius: var(--radius-panel); background: var(--surface); }
</style>
