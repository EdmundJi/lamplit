<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { BellRing, X } from 'lucide-vue-next'
import { api } from '../api/client'
import type { UnreadSummary } from '../../modules/friends/friends.types'

const router = useRouter()
const route = useRoute()

const totalUnread = ref(0)
const label = ref('')
let timer: number | undefined
let suppressed = false

async function poll() {
  if (document.hidden || suppressed) return
  try {
    const summary = await api.get<UnreadSummary>('/friends/unread-summary')
    totalUnread.value = summary.totalUnread
    label.value = summary.displayName ?? ''
  } catch {
    // 静默
  }
}

function openLatest() {
  api.get<UnreadSummary>('/friends/unread-summary').then(summary => {
    if (!summary.publicId || !summary.kind) return
    suppressed = true
    totalUnread.value = 0
    router.push(summary.kind === 'group'
      ? `/friends/groups/${summary.publicId}`
      : `/friends/${summary.publicId}/chat`)
  })
}

function dismiss() {
  suppressed = true
  totalUnread.value = 0
}

onMounted(() => {
  poll()
  timer = window.setInterval(poll, 6000)
})

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<template>
  <Transition name="unread-bar">
    <button
      v-if="totalUnread > 0"
      class="global-unread-bar"
      type="button"
      role="status"
      aria-live="polite"
      @click="openLatest"
    >
      <BellRing :size="16" />
      <span>你有 {{ totalUnread }} 条新消息<template v-if="label">，来自 {{ label }}</template></span>
      <span class="unread-bar-action">去看看</span>
      <span class="unread-bar-close" role="button" aria-label="关闭提示" @click.stop="dismiss"><X :size="14" /></span>
    </button>
  </Transition>
</template>

<style scoped>
.global-unread-bar { position: fixed; top: 14px; left: 50%; transform: translateX(-50%); z-index: 90; min-height: 46px; display: flex; align-items: center; gap: 10px; padding: 0 10px 0 16px; border: 1px solid color-mix(in srgb, var(--primary) 40%, var(--border)); border-radius: 999px; background: var(--surface); color: var(--ink); box-shadow: var(--shadow); font-size: 13px; font-weight: 700; cursor: pointer; }
.global-unread-bar > svg { color: var(--primary); }
.unread-bar-action { padding: 5px 12px; border-radius: 999px; background: var(--primary); color: white; font-size: 12px; }
.unread-bar-close { width: 26px; height: 26px; display: grid; place-items: center; border-radius: 50%; color: var(--muted); }
.unread-bar-close:hover { background: var(--surface-muted); color: var(--ink); }
@media (prefers-reduced-motion: no-preference) {
  .unread-bar-enter-active { transition: transform var(--motion-medium) ease, opacity var(--motion-medium) ease; }
  .unread-bar-leave-active { transition: transform var(--motion-fast) ease, opacity var(--motion-fast) ease; }
  .unread-bar-enter-from, .unread-bar-leave-to { opacity: 0; transform: translate(-50%, -12px); }
}
</style>
