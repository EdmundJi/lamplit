<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ChevronRight, MailCheck, MailPlus, MessageCircle, Send, UserPlus, Users, X } from 'lucide-vue-next'
import { onDataChanged } from '../../shared/data-sync'
import { friendInitial as initial, memberSinceLabel, useFriendDirectory } from './friends.logic'

const { list, loading, busy, error, feedback, load, sendRequest: submitRequest, accept, reject, remove } = useFriendDirectory()
const email = ref('')
const showAdd = ref(false)
const emailInput = ref<HTMLInputElement | null>(null)

async function toggleAdd() {
  showAdd.value = !showAdd.value
  if (!showAdd.value) return
  await nextTick()
  emailInput.value?.focus()
}

async function sendRequest() {
  const ok = await submitRequest(email.value)
  if (ok) {
    email.value = ''
    showAdd.value = false
  }
}

const stopDataSync = onDataChanged('social', () => load(false))

onMounted(() => load(true))
onBeforeUnmount(stopDataSync)
</script>

<template>
  <section class="page friends-page">
    <header class="page-head">
      <div>
        <p class="eyebrow">同行的伙伴</p>
        <h1>好友</h1>
        <p class="page-description">各自向前，也在彼此的生活里留一盏灯。</p>
      </div>
      <div class="page-head-actions">
        <span v-if="list.friends.length" class="friend-count"><Users :size="16" /><span>{{ list.friends.length }} 位好友</span></span>
        <button class="primary" type="button" :aria-expanded="showAdd" aria-controls="add-friend-panel" @click="toggleAdd">
          <UserPlus :size="16" />{{ showAdd ? '收起添加' : '添加好友' }}
        </button>
      </div>
    </header>

    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-if="feedback" class="feedback-banner" data-tone="support" role="status" aria-live="polite">
      <MailCheck :size="19" /><p>{{ feedback }}</p>
    </div>

    <form v-if="showAdd" id="add-friend-panel" class="band add-friend-band" @submit.prevent="sendRequest">
      <div class="section-title">
        <div><p class="eyebrow">添加好友</p><h2>通过注册邮箱找到对方</h2></div>
        <button class="icon-button" type="button" aria-label="收起添加好友" @click="showAdd = false"><X :size="18" /></button>
      </div>
      <div class="add-friend-row">
        <label class="field"><span class="sr-only">好友邮箱</span><input ref="emailInput" v-model="email" type="email" placeholder="输入对方的注册邮箱" maxlength="254" /></label>
        <button class="primary" :disabled="busy" type="submit"><Send :size="16" />发送申请</button>
      </div>
      <p class="add-friend-note">对方同意后，你们可以互相查看今日行动、等级、宠物、徽章和成长雷达。</p>
    </form>

    <p v-if="loading" class="empty">正在整理好友列表…</p>
    <template v-else>
      <section v-if="list.incoming.length" class="band" aria-labelledby="incoming-title">
        <div class="section-title">
          <div><p class="eyebrow">待你决定</p><h2 id="incoming-title">收到的申请</h2></div>
          <span class="section-count">{{ list.incoming.length }}</span>
        </div>
        <div class="request-list">
          <article v-for="item in list.incoming" :key="item.publicId" class="friend-card incoming-card">
            <span class="friend-avatar" aria-hidden="true">{{ initial(item.displayName) }}</span>
            <div class="friend-copy">
              <strong>{{ item.displayName }}</strong>
              <small>LV.{{ item.overallLevel }} 成长者 · {{ memberSinceLabel(item.memberSince) }}加入</small>
            </div>
            <div class="request-actions">
              <button class="primary" :disabled="busy" type="button" @click="accept(item)"><MailCheck :size="16" />接受</button>
              <button class="secondary" :disabled="busy" type="button" aria-label="拒绝申请" @click="reject(item)"><X :size="16" />拒绝</button>
            </div>
          </article>
        </div>
      </section>

      <section v-if="list.outgoing.length" class="band" aria-labelledby="outgoing-title">
        <div class="section-title">
          <div><p class="eyebrow">等待回应</p><h2 id="outgoing-title">发出的申请</h2></div>
          <span class="section-count">{{ list.outgoing.length }}</span>
        </div>
        <div class="request-list">
          <article v-for="item in list.outgoing" :key="item.publicId" class="friend-card outgoing-card">
            <span class="friend-avatar" aria-hidden="true">{{ initial(item.displayName) }}</span>
            <div class="friend-copy">
              <strong>{{ item.displayName }}</strong>
              <small>LV.{{ item.overallLevel }} 成长者 · {{ memberSinceLabel(item.memberSince) }}加入</small>
            </div>
            <button class="secondary" :disabled="busy" type="button" aria-label="取消申请" @click="remove(item)">取消申请</button>
          </article>
        </div>
      </section>

      <section class="band" aria-labelledby="friends-title">
        <div class="section-title">
          <div><p class="eyebrow">成长中的朋友</p><h2 id="friends-title">好友列表</h2></div>
          <span class="section-count">{{ list.friends.length }}</span>
        </div>
        <div v-if="list.friends.length" class="friend-grid">
          <article v-for="item in list.friends" :key="item.publicId" class="friend-card friend-row">
            <RouterLink class="friend-link" :to="`/friends/${item.publicId}`">
              <span class="friend-avatar" aria-hidden="true">{{ initial(item.displayName) }}</span>
              <div class="friend-copy">
                <strong>{{ item.displayName }}</strong>
                <small>LV.{{ item.overallLevel }} 成长者 · {{ memberSinceLabel(item.memberSince) }}加入</small>
              </div>
              <ChevronRight :size="17" />
            </RouterLink>
            <RouterLink class="chat-entry" :to="`/friends/${item.publicId}/chat`" :aria-label="`给${item.displayName}发消息`" title="发消息"><MessageCircle :size="17" /></RouterLink>
          </article>
        </div>
        <div v-else class="empty">
          <MailPlus :size="26" />
          <h3>还没有好友</h3>
          <p>点右上角的「添加好友」，输入对方的注册邮箱，发送第一份同行邀请。</p>
        </div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.page-head-actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.friend-count { display: inline-flex; align-items: center; gap: 8px; padding: 8px 13px; border: 1px solid var(--border); border-radius: 999px; background: var(--surface); color: var(--primary); font-size: 13px; font-weight: 700; }
.add-friend-band { display: grid; gap: 13px; }
.section-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 4px; color: var(--primary); }
.section-title h2 { margin: 0; font-size: 18px; color: var(--ink); }
.section-count { padding: 3px 9px; border-radius: 999px; background: var(--surface-muted); color: var(--muted); font-size: 12px; font-weight: 800; }
.add-friend-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 10px; align-items: center; }
.add-friend-note { margin: 0; color: var(--muted); font-size: 12px; line-height: 1.6; }
.request-list { display: grid; gap: 9px; margin-top: 10px; }
.friend-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; margin-top: 10px; }
.friend-card { min-width: 0; display: grid; gap: 0; padding: 0; border: 1px solid var(--border); border-radius: var(--radius); background: color-mix(in srgb, var(--surface) 90%, transparent); overflow: hidden; }
.incoming-card, .outgoing-card { grid-template-columns: 44px minmax(0, 1fr) auto; align-items: center; gap: 12px; padding: 13px 15px; }
.friend-row { grid-template-columns: minmax(0, 1fr) auto; }
.friend-link { min-width: 0; display: grid; grid-template-columns: 46px minmax(0, 1fr) auto; align-items: center; gap: 13px; padding: 13px 0 13px 15px; color: var(--ink); text-decoration: none; transition: background-color var(--motion-fast) var(--ease); }
.friend-link:hover { background: color-mix(in srgb, var(--primary) 4%, var(--surface)); }
.friend-link > svg { color: var(--muted); }
.chat-entry { width: 46px; min-height: 100%; display: grid; place-items: center; border-left: 1px solid var(--border); color: var(--primary); text-decoration: none; transition: background-color var(--motion-fast) var(--ease); }
.chat-entry:hover { background: var(--primary-soft); }
.friend-avatar { width: 44px; height: 44px; display: grid; place-items: center; border-radius: var(--radius-panel) var(--radius-panel) var(--radius-panel) var(--radius); background: linear-gradient(145deg, var(--primary), color-mix(in srgb, var(--primary) 72%, var(--amber))); color: white; font-size: 19px; font-weight: 900; }
.friend-copy { min-width: 0; display: grid; gap: 4px; }
.friend-copy strong { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 15px; }
.friend-copy small { color: var(--muted); font-size: 12px; }
.incoming-card .friend-avatar { background: linear-gradient(145deg, var(--accent), color-mix(in srgb, var(--accent) 72%, var(--amber))); }
.request-actions { display: flex; gap: 8px; }
.empty { border: 1px dashed var(--border); border-radius: var(--radius); padding: 34px 20px; display: grid; place-items: center; justify-items: center; gap: 7px; color: var(--muted); text-align: center; }
.empty h3 { margin: 0; color: var(--ink); font-size: 16px; }
.empty p { margin: 0; font-size: 13px; }
.empty svg { color: var(--primary); }
@media (prefers-reduced-motion: no-preference) {
  .friend-card { animation: friend-enter var(--motion-medium) var(--ease) both; }
  .friend-card:nth-child(2) { animation-delay: 50ms; }
  .friend-card:nth-child(3) { animation-delay: 100ms; }
  .friend-card:nth-child(4) { animation-delay: 150ms; }
}
@keyframes friend-enter { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: translateY(0); } }
@media (max-width: 720px) {
  .friend-grid { grid-template-columns: 1fr; }
  .add-friend-row { grid-template-columns: 1fr; }
  .incoming-card { grid-template-columns: 44px minmax(0, 1fr); }
  .outgoing-card { grid-template-columns: 44px minmax(0, 1fr); }
  .request-actions { grid-column: 1 / -1; display: flex; }
  .request-actions .primary, .request-actions .secondary { flex: 1; }
  .outgoing-card > .secondary { grid-column: 1 / -1; width: 100%; }
}
.friend-card { border-radius: var(--radius-panel); }
.friend-avatar { border-radius: 50%; background: var(--primary-soft); color: var(--primary-strong); }
.add-friend-band { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-panel); padding: 24px; margin-bottom: 24px; }
.friend-link { padding-block: 20px; }
.friend-grid { gap: 16px; }
@media (max-width: 760px) { .add-friend-band { padding: 18px; } }
</style>
