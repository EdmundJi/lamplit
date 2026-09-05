<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref } from 'vue'
import { Award, Check, Coins, EyeOff, UserRound, X } from 'lucide-vue-next'
import { onDataChanged } from '../../../../shared/data-sync'
import { growthIcon } from '../../../achievements/achievement.types'
import { avatarInitial, useProfile, usePrivacy, useTitles } from '../../../profile/profile.logic'
import { worldBridgeKey } from '../panel.types'
import { openFullPage } from './shared'

const FULL_PAGE = '/profile'
type Tab = 'overview' | 'titles'

const bridge = inject(worldBridgeKey, undefined)

const { profile, loading, error, load: loadProfile } = useProfile()
const { titles, busy: titleBusy, feedback: titleFeedback, heldTitles, load: loadTitles, equip, unequip } = useTitles(profile)
const { busy: privacyBusy, feedback: privacyFeedback, toggleSoloGrowth } = usePrivacy(profile)

const tab = ref<Tab>('overview')
const showAllTitles = ref(false)
const visibleTitles = computed(() => (showAllTitles.value ? titles.value : heldTitles.value))
const initial = computed(() => avatarInitial(profile.value?.displayName ?? ''))

async function equipTitle(title: (typeof titles.value)[number]) {
  await equip(title)
  bridge?.emit({ type: 'toast', text: titleFeedback.value })
}

async function load(showLoading = true) {
  await loadProfile(showLoading)
  if (!error.value) await loadTitles()
}

const stopDataSync = onDataChanged(['tasks', 'partners', 'profile'], () => load(false))

onMounted(() => load(true))
onBeforeUnmount(stopDataSync)
</script>

<template>
  <section class="world-panel profile-panel">
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="loading" class="empty">正在整理个人资料…</p>
    <template v-else-if="profile">
      <div class="identity">
        <span class="avatar" aria-hidden="true">{{ initial }}</span>
        <div>
          <strong>{{ profile.displayName }} · LV.{{ profile.overallLevel }}</strong>
          <span class="muted">累计经验 {{ profile.totalExperience }}</span>
        </div>
      </div>

      <nav class="tab-row" role="tablist" aria-label="个人面板分区">
        <button type="button" role="tab" :aria-selected="tab === 'overview'" :class="{ active: tab === 'overview' }" @click="tab = 'overview'">概览</button>
        <button type="button" role="tab" :aria-selected="tab === 'titles'" :class="{ active: tab === 'titles' }" @click="tab = 'titles'">称号</button>
      </nav>

      <section v-if="tab === 'overview'" class="tab-panel">
        <div class="stat-row">
          <span><Coins :size="14" />{{ profile.wallet.coinBalance }} 金币</span>
          <span v-if="profile.equippedTitle">称号：{{ profile.equippedTitle.name }}</span>
        </div>
        <button class="secondary privacy-toggle" type="button" role="switch" :aria-checked="profile.soloGrowth" :disabled="privacyBusy" @click="toggleSoloGrowth">
          <EyeOff :size="14" />{{ profile.soloGrowth ? '已隐藏，点击允许被找到' : '可被找到，点击隐藏' }}
        </button>
        <p v-if="privacyFeedback" class="muted feedback-line">{{ privacyFeedback }}</p>
      </section>

      <section v-else class="tab-panel">
        <div class="titles-head">
          <span class="titles-count"><Award :size="14" />{{ heldTitles.length }} / {{ titles.length }}</span>
          <button type="button" class="secondary titles-toggle" :aria-expanded="showAllTitles" @click="showAllTitles = !showAllTitles">
            {{ showAllTitles ? '只看已获得' : '查看全部' }}
          </button>
        </div>
        <p v-if="titleFeedback" class="muted feedback-line">{{ titleFeedback }}</p>
        <ul v-if="visibleTitles.length" class="title-list">
          <li v-for="title in visibleTitles" :key="title.code" class="title-row" :class="{ held: title.held, equipped: title.equipped }">
            <span class="title-icon"><component :is="growthIcon(title.graphicKey)" :size="15" /></span>
            <span class="title-copy"><strong>{{ title.name }}</strong><small>{{ title.description }}</small></span>
            <button v-if="title.equipped" type="button" class="icon-button" title="卸下称号" aria-label="卸下称号" :disabled="Boolean(titleBusy)" @click="unequip"><X :size="14" /></button>
            <button v-else-if="title.held" type="button" class="icon-button" :title="`佩戴${title.name}`" :aria-label="`佩戴${title.name}`" :disabled="Boolean(titleBusy)" @click="equipTitle(title)"><Check :size="14" /></button>
            <span v-else class="locked-label">未获得</span>
          </li>
        </ul>
        <p v-else class="empty">完成行动后，称号会出现在这里。</p>
      </section>
    </template>
    <div v-else class="empty">
      <UserRound :size="20" />
      <p>个人资料暂时不可用。</p>
    </div>

    <footer class="panel-footer">
      <button class="secondary" type="button" @click="openFullPage(bridge, FULL_PAGE)">打开完整页面</button>
    </footer>
  </section>
</template>

<style scoped>
.world-panel { display: grid; gap: 12px; width: 100%; color: var(--ink); }
.identity { display: flex; align-items: center; gap: 10px; }
.avatar { width: 40px; height: 40px; display: grid; place-items: center; border-radius: 50%; background: var(--primary-soft); color: var(--primary-strong); flex: none; font-weight: 800; }
.identity strong { display: block; font-size: 13px; }
.muted { color: var(--muted); font-size: 11px; }
.tab-row { display: grid; grid-auto-flow: column; gap: 3px; padding: 3px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface-muted); }
.tab-row button { min-width: 0; min-height: 28px; border: 0; border-radius: calc(var(--radius) - 2px); background: transparent; color: var(--muted); font-size: 12px; }
.tab-row button.active { background: var(--surface); color: var(--primary-strong); font-weight: 700; box-shadow: var(--shadow-soft); }
.tab-panel { display: grid; gap: 8px; }
.stat-row { display: flex; align-items: center; gap: 14px; font-size: 12px; color: var(--ink); }
.stat-row span { display: inline-flex; align-items: center; gap: 5px; }
.privacy-toggle { justify-content: flex-start; font-size: 12px; }
.feedback-line { margin: -2px 0 0; color: var(--primary); }
.titles-head { display: flex; align-items: center; gap: 8px; }
.titles-count { display: inline-flex; align-items: center; gap: 5px; color: var(--primary); font-weight: 700; font-size: 12px; }
.titles-toggle { margin-left: auto; min-height: 28px; padding: 0 10px; font-size: 11px; }
.title-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 7px; max-height: 260px; overflow-y: auto; }
.title-row { display: grid; grid-template-columns: 28px minmax(0, 1fr) auto; align-items: center; gap: 8px; padding: 8px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); opacity: .66; }
.title-row.held { opacity: 1; }
.title-row.equipped { border-color: color-mix(in srgb, var(--primary) 40%, var(--border)); background: color-mix(in srgb, var(--primary-soft) 42%, var(--surface)); }
.title-icon { width: 26px; height: 26px; display: grid; place-items: center; border-radius: var(--radius); background: var(--surface-muted); color: var(--muted); }
.title-row.held .title-icon { color: var(--primary); background: var(--primary-soft); }
.title-copy { min-width: 0; display: grid; gap: 2px; }
.title-copy strong { font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.title-copy small { color: var(--muted); font-size: 10px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.title-row .icon-button { width: 28px; height: 28px; min-width: 28px; min-height: 28px; }
.locked-label { color: var(--muted); font-size: 10px; white-space: nowrap; }
.panel-footer { display: flex; justify-content: flex-end; }
</style>
