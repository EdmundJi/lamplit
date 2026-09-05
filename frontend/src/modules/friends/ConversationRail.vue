<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { MessagesSquare, UsersRound } from 'lucide-vue-next'
import { onDataChanged } from '../../shared/data-sync'
import EmojiText from './EmojiText.vue'
import { friendInitial as initial, useConversations, type ConversationRow } from './friends.logic'

const { conversations: rows, loading, load } = useConversations()
const route = useRoute()

/** Desktop keeps the conversation list beside the thread; mobile hides it and navigates instead. */
const activePath = computed(() => route.path)

function targetOf(row: ConversationRow) {
  return row.kind === 'group' ? `/friends/groups/${row.peerPublicId}` : `/friends/${row.peerPublicId}/chat`
}

const stopDataSync = onDataChanged(['social'], () => load(false))
onMounted(() => load(true))
onBeforeUnmount(stopDataSync)
</script>

<template>
  <aside class="conversation-rail" aria-label="会话列表">
    <header>
      <p class="eyebrow">消息</p>
      <RouterLink to="/friends/chat">全部会话</RouterLink>
    </header>
    <p v-if="loading" class="rail-hint">正在读取会话…</p>
    <p v-else-if="!rows.length" class="rail-hint">还没有会话。</p>
    <nav v-else>
      <RouterLink
        v-for="row in rows"
        :key="`${row.kind}-${row.peerPublicId}`"
        :to="targetOf(row)"
        :class="{ current: activePath === targetOf(row) }"
      >
        <span class="rail-avatar" :class="{ group: row.kind === 'group' }" aria-hidden="true">
          <UsersRound v-if="row.kind === 'group'" :size="17" />
          <template v-else>{{ initial(row.displayName) }}</template>
        </span>
        <span class="rail-copy">
          <strong>{{ row.displayName }}</strong>
          <small><EmojiText :text="row.lastMessage || '还没有消息'" /></small>
        </span>
        <span v-if="row.unreadCount" class="rail-unread">{{ row.unreadCount > 99 ? '99+' : row.unreadCount }}</span>
        <MessagesSquare v-else :size="15" class="rail-icon" />
      </RouterLink>
    </nav>
  </aside>
</template>

<style scoped>
.conversation-rail { display: none; }
@media (min-width: 1100px) {
  .conversation-rail { display: block; width: 268px; flex: none; align-self: start; position: sticky; top: 24px; padding: 14px; border: 1px solid var(--border); border-radius: var(--radius-panel); background: var(--surface); max-height: calc(100vh - 140px); overflow-y: auto; }
}
.conversation-rail header { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; margin-bottom: 10px; }
.conversation-rail header .eyebrow { margin: 0; }
.conversation-rail header a { color: var(--muted); font-size: 12px; text-decoration: none; }
.conversation-rail header a:hover { color: var(--primary); }
.rail-hint { margin: 0; color: var(--muted); font-size: 13px; }
.conversation-rail nav { display: grid; gap: 4px; }
.conversation-rail nav a { display: grid; grid-template-columns: 34px minmax(0, 1fr) auto; align-items: center; gap: 10px; padding: 9px 10px; border-radius: var(--radius); color: var(--ink); text-decoration: none; }
.conversation-rail nav a:hover { background: var(--surface-muted); }
.conversation-rail nav a.current { background: var(--primary-soft); }
.rail-avatar { width: 34px; height: 34px; display: grid; place-items: center; border-radius: 12px; background: var(--surface-muted); color: var(--primary-strong); font-weight: 700; font-size: 14px; }
.rail-avatar.group { background: var(--primary-soft); }
.rail-copy { display: grid; gap: 2px; min-width: 0; }
.rail-copy strong { font-size: 14px; font-weight: 650; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rail-copy small { color: var(--muted); font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rail-unread { min-width: 22px; height: 22px; padding: 0 6px; display: grid; place-items: center; border-radius: 999px; background: var(--danger); color: #fff; font-size: 11px; font-weight: 700; }
.rail-icon { color: var(--border); }
</style>
