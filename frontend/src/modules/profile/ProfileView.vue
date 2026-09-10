<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { Award, BarChart3, CalendarDays, Check, ChevronRight, Coins, Settings, Sparkles, Target, Users, X } from 'lucide-vue-next'
import { onDataChanged } from '../../shared/data-sync'
import { growthIcon } from '../achievements/achievement.types'
import EmptyState from '../../shared/ui/EmptyState.vue'
import RivePet from '../partners/RivePet.vue'
import { avatarInitial, memberSinceLabel, petProgressPercent, useProfile, usePrivacy, useTitles } from './profile.logic'

const { profile, loading, error, load: loadProfile } = useProfile()
const { titles, busy: titleBusy, feedback: titleFeedback, heldTitles, load: loadTitles, equip: equipTitle, unequip: unequipTitle } = useTitles(profile)
const { busy: privacyBusy, feedback: privacyFeedback, toggleSoloGrowth } = usePrivacy(profile)

const showAllTitles = ref(false)
const visibleTitles = computed(() => (showAllTitles.value ? titles.value : heldTitles.value))
const initial = computed(() => avatarInitial(profile.value?.displayName ?? ''))
const memberSince = computed(() => (profile.value ? memberSinceLabel(profile.value.createdAt) : ''))
const petProgress = computed(() => petProgressPercent(profile.value?.selectedPet))

async function load(showLoading = true) {
  await loadProfile(showLoading)
  if (!error.value) await loadTitles()
}

const stopDataSync = onDataChanged(['tasks', 'partners'], () => load(false))

onMounted(() => load(true))
onBeforeUnmount(stopDataSync)
</script>

<template>
  <section class="page profile-page">
    <header class="page-head">
      <div><h1>个人</h1></div>
      <RouterLink class="secondary button" to="/settings"><Settings :size="17" />设置</RouterLink>
    </header>

    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-else-if="loading" class="loading-state" role="status">正在整理你的个人档案…</div>
    <template v-else-if="profile">
      <section class="identity-head">
        <div class="avatar-shell" :data-frame="profile.equippedTitle?.frameStyle ?? 'default'">
          <div class="avatar" aria-hidden="true">{{ initial }}</div>
        </div>
        <div class="identity-copy">
          <h2>{{ profile.displayName }}<span v-if="profile.equippedTitle" class="equipped-title"><component :is="growthIcon(profile.equippedTitle.graphicKey)" :size="15" />{{ profile.equippedTitle.name }}</span></h2>
          <p class="muted">LV.{{ profile.overallLevel }} 成长者 · {{ profile.age }} 岁 · {{ memberSince }}加入</p>
        </div>
        <dl class="stats identity-stats">
          <div><dt>累计经验</dt><dd>{{ profile.totalExperience }}</dd></div>
          <div><dt>有效行动</dt><dd>{{ profile.effectiveActions }}</dd></div>
          <div><dt>当前金币</dt><dd>{{ profile.wallet.coinBalance }}</dd></div>
        </dl>
      </section>

      <section class="privacy-band band" aria-labelledby="solo-growth-title">
        <div class="privacy-copy">
          <h2 id="solo-growth-title" class="setting-title">独自升级</h2>
          <p>开启后，其他用户无法通过邮箱搜索或添加你；现有好友关系不受影响。</p>
        </div>
        <div class="privacy-control">
          <span>{{ profile.soloGrowth ? '已从好友搜索中隐藏' : '可通过注册邮箱找到' }}</span>
          <button
            type="button"
            class="privacy-switch"
            role="switch"
            :aria-checked="profile.soloGrowth"
            :aria-label="profile.soloGrowth ? '关闭独自升级' : '开启独自升级'"
            :disabled="privacyBusy"
            @click="toggleSoloGrowth"
          ><span aria-hidden="true" /></button>
        </div>
        <p v-if="privacyFeedback" class="privacy-feedback" role="status" aria-live="polite">{{ privacyFeedback }}</p>
      </section>

      <section class="titles-band band" aria-labelledby="titles-title">
        <div class="section-title titles-head">
          <h2 id="titles-title">称号</h2>
          <div class="titles-tools">
            <span class="titles-count"><Award :size="17" />{{ heldTitles.length }} / {{ titles.length }}</span>
            <button v-if="titles.length" type="button" class="secondary titles-toggle" :aria-expanded="showAllTitles" @click="showAllTitles = !showAllTitles">
              {{ showAllTitles ? '只看已获得' : `查看全部 ${titles.length} 个` }}
            </button>
          </div>
        </div>
        <p v-if="titleFeedback" class="title-feedback" role="status" aria-live="polite">{{ titleFeedback }}</p>
        <div v-if="visibleTitles.length" class="title-grid">
          <article v-for="title in visibleTitles" :key="title.code" class="title-item" :class="{ held: title.held, equipped: title.equipped }">
            <span class="title-icon"><component :is="growthIcon(title.graphicKey)" :size="20" /></span>
            <span class="title-copy"><strong>{{ title.name }}</strong><small>{{ title.description }}</small></span>
            <button v-if="title.equipped" type="button" class="icon-button title-action" title="卸下称号" aria-label="卸下称号" :disabled="Boolean(titleBusy)" @click="unequipTitle"><X :size="17" /></button>
            <button v-else-if="title.held" type="button" class="icon-button title-action" :title="`佩戴${title.name}`" :aria-label="`佩戴${title.name}`" :disabled="Boolean(titleBusy)" @click="equipTitle(title)"><Check :size="17" /></button>
            <span v-else class="locked-label">未获得</span>
          </article>
        </div>
        <EmptyState v-else sprite="rabbit_baby_white_idle_1" description="完成行动后，称号会出现在这里。" />
      </section>

      <div class="profile-grid">
        <section class="pet-section" aria-labelledby="profile-pet-title">
          <h2 id="profile-pet-title" class="section-title">当前伙伴</h2>
          <div class="pet-stage">
            <RivePet :species-code="profile.selectedPet.speciesCode" :name="profile.selectedPet.name" :disabled="true" />
          </div>
          <div class="pet-details">
            <div><strong>{{ profile.selectedPet.name }}</strong><span>{{ profile.selectedPet.speciesName }} · {{ profile.selectedPet.breed }} · {{ profile.selectedPet.furColor }}</span></div>
            <b>LV.{{ profile.selectedPet.level }}</b>
          </div>
          <div class="pet-affection"><div><span>好感度</span><strong>{{ profile.selectedPet.affection }} / {{ profile.selectedPet.nextLevelAffection }}</strong></div><div class="progress"><span :style="{ width: `${petProgress}%` }"></span></div></div>
          <RouterLink class="pet-link" to="/partners">管理 {{ profile.petCount }} 位伙伴<ChevronRight :size="17" /></RouterLink>
        </section>

        <section class="quick-section" aria-labelledby="quick-title">
          <h2 id="quick-title" class="section-title">快速前往</h2>
          <nav class="quick-links" aria-label="个人页快捷入口">
            <RouterLink to="/today"><span class="quick-icon"><CalendarDays :size="20" /></span><span><strong>今日行动</strong><small>查看今天要完成的任务</small></span><ChevronRight :size="17" /></RouterLink>
            <RouterLink to="/goals"><span class="quick-icon"><Target :size="20" /></span><span><strong>目标与任务</strong><small>调整目标和周期任务</small></span><ChevronRight :size="17" /></RouterLink>
            <RouterLink to="/attributes"><span class="quick-icon"><BarChart3 :size="20" /></span><span><strong>成长属性</strong><small>查看智力等五项属性</small></span><ChevronRight :size="17" /></RouterLink>
            <RouterLink to="/insights"><span class="quick-icon"><Sparkles :size="20" /></span><span><strong>个人洞察</strong><small>回看行动趋势与徽章</small></span><ChevronRight :size="17" /></RouterLink>
            <RouterLink to="/friends"><span class="quick-icon"><Users :size="20" /></span><span><strong>好友</strong><small>添加好友，看看彼此的成长</small></span><ChevronRight :size="17" /></RouterLink>
          </nav>
        </section>
      </div>

      <section class="account-band band" aria-labelledby="account-title">
        <h2 id="account-title" class="section-title">账户信息</h2>
        <dl>
          <div><dt>名称</dt><dd>{{ profile.displayName }}</dd></div>
          <div><dt>年龄</dt><dd>{{ profile.age }} 岁</dd></div>
          <div><dt>出生日期</dt><dd>{{ profile.birthDate }}</dd></div>
          <div><dt>邮箱</dt><dd>{{ profile.email }}</dd></div>
          <div><dt>时区</dt><dd>{{ profile.timezone }}</dd></div>
          <div><dt>金币累计获得</dt><dd><Coins :size="15" />{{ profile.wallet.lifetimeCoins }}</dd></div>
        </dl>
      </section>
    </template>
  </section>
</template>

<style scoped>
.loading-state { min-height: 420px; display: grid; place-items: center; color: var(--muted); }
.identity-head { display: flex; align-items: center; gap: 20px; padding: 4px 0 28px; }
.avatar-shell { width: 64px; aspect-ratio: 1; flex: none; display: grid; place-items: center; padding: 3px; border: 1px solid transparent; border-radius: var(--radius-panel) var(--radius-panel) var(--radius-panel) var(--radius); }
.avatar { width: 100%; height: 100%; display: grid; place-items: center; border-radius: var(--radius-panel) var(--radius-panel) var(--radius-panel) var(--radius); background: linear-gradient(145deg, var(--primary), color-mix(in srgb, var(--primary) 72%, var(--amber))); color: white; font-size: 22px; font-weight: 900; }
.avatar-shell[data-frame='emerald'], .avatar-shell[data-frame='green'] { border-color: var(--accent); box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 14%, transparent); }
.avatar-shell[data-frame='sky'] { border-color: var(--dim-relationship); box-shadow: 0 0 0 3px color-mix(in srgb, var(--dim-relationship) 14%, transparent); }
.avatar-shell[data-frame='ember'] { border-color: var(--amber); box-shadow: 0 0 0 3px color-mix(in srgb, var(--amber) 15%, transparent); }
.avatar-shell[data-frame='gold'] { border-color: var(--tone-gold); box-shadow: 0 0 0 3px color-mix(in srgb, var(--tone-gold) 15%, transparent); }
.avatar-shell[data-frame='violet'] { border-color: var(--tone-violet); box-shadow: 0 0 0 3px color-mix(in srgb, var(--tone-violet) 15%, transparent); }
.avatar-shell[data-frame='rose'] { border-color: var(--tone-rose); box-shadow: 0 0 0 3px color-mix(in srgb, var(--tone-rose) 15%, transparent); }
.avatar-shell[data-frame='rainbow'] { border-color: var(--primary); box-shadow: 3px 3px 0 var(--accent), -3px -3px 0 var(--amber); }
.identity-copy { min-width: 0; flex: 1 1 auto; }
.identity-copy h2 { margin: 0; display: flex; align-items: baseline; flex-wrap: wrap; gap: 10px; font-size: 22px; }
.identity-copy p { margin: 6px 0 0; }
.equipped-title { width: fit-content; min-height: 24px; display: inline-flex; align-items: center; gap: 5px; padding: 0 8px; border: 1px solid color-mix(in srgb, var(--primary) 26%, var(--border)); border-radius: var(--radius); background: var(--primary-soft); color: var(--primary); font-size: 12px; font-weight: 800; }
.identity-stats { flex: none; }
.privacy-band { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 10px 24px; padding: 22px 0; }
.privacy-copy { min-width: 0; }
.setting-title { margin: 0 0 3px; font-size: 16px; font-weight: 650; }
.privacy-copy p:last-child { margin: 0; color: var(--muted); font-size: 12px; line-height: 1.6; }
.privacy-control { display: flex; align-items: center; justify-content: flex-end; gap: 12px; }
.privacy-control > span { color: var(--muted); font-size: 12px; white-space: nowrap; }
.privacy-switch { position: relative; flex: 0 0 auto; width: 48px; height: 28px; min-height: 28px; padding: 0; border: 1px solid var(--border); border-radius: 999px; background: var(--surface-muted); }
.privacy-switch > span { position: absolute; top: 3px; left: 3px; width: 20px; height: 20px; border-radius: 50%; background: var(--surface); transition: transform var(--motion-fast) var(--ease); }
.privacy-switch[aria-checked='true'] { border-color: var(--accent); background: var(--accent); }
.privacy-switch[aria-checked='true'] > span { transform: translateX(20px); }
.privacy-switch:disabled { cursor: wait; opacity: .65; }
.privacy-feedback { grid-column: 2; margin: 0; color: var(--primary); font-size: 12px; text-align: right; }
.stats { display: flex; flex-wrap: wrap; gap: 0; margin: 0; padding: 0; }
.stats > div { display: flex; flex-direction: column; gap: 4px; padding: 0 20px; }
.stats > div:first-child { padding-left: 0; }
.stats > div + div { border-left: 1px solid var(--border); }
.stats dt { margin: 0; font-size: 12px; color: var(--muted); }
.stats dd { margin: 0; font-size: 20px; font-weight: 600; font-variant-numeric: tabular-nums; color: var(--ink); }
.profile-grid { display: grid; grid-template-columns: minmax(340px, .85fr) minmax(0, 1fr); gap: 28px; padding: 28px 0; }
.titles-band { padding-top: 28px; }
.titles-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.titles-head h2 { margin: 0; font: inherit; color: inherit; }
.titles-tools { display: flex; align-items: center; gap: 10px; }
.titles-count { display: inline-flex; align-items: center; gap: 6px; color: var(--primary); font-weight: 700; font-size: 13px; }
.titles-toggle { min-height: 34px; padding: 0 12px; font-size: 13px; }
.title-feedback { margin: -4px 0 12px; color: var(--primary); font-size: 13px; }
.title-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 9px; }
.title-item { min-width: 0; min-height: 88px; display: grid; grid-template-columns: 38px minmax(0, 1fr) auto; align-items: center; gap: 10px; padding: 11px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); opacity: .66; }
.title-item.held { opacity: 1; }
.title-item.equipped { border-color: color-mix(in srgb, var(--primary) 40%, var(--border)); background: color-mix(in srgb, var(--primary-soft) 42%, var(--surface)); box-shadow: inset 3px 0 var(--primary); }
.title-icon { width: 36px; height: 36px; display: grid; place-items: center; border-radius: var(--radius); background: var(--surface-muted); color: var(--muted); }
.title-item.held .title-icon { color: var(--primary); background: var(--primary-soft); }
.title-copy { min-width: 0; display: grid; gap: 4px; }
.title-copy small { color: var(--muted); font-size: 11px; line-height: 1.4; }
.title-action { width: 36px; height: 36px; min-width: 36px; min-height: 36px; flex-basis: 36px; border: 1px solid var(--border); color: var(--primary); }
.locked-label { color: var(--muted); font-size: 11px; white-space: nowrap; }
.pet-section, .quick-section { min-width: 0; }
.pet-stage { position: relative; height: 250px; display: grid; place-items: center; border: 1px solid color-mix(in srgb, var(--accent) 25%, var(--border)); border-radius: var(--radius-scene) var(--radius-scene) var(--radius-card) var(--radius-card); background: linear-gradient(180deg, color-mix(in srgb, var(--accent) 10%, var(--surface)), color-mix(in srgb, var(--amber) 9%, var(--surface))); overflow: hidden; }
.pet-stage :deep(.rive-pet) { position: absolute; inset: 0; width: min(100%, 310px); height: 230px; margin: auto; }
.pet-details { display: flex; justify-content: space-between; align-items: start; gap: 12px; padding: 13px 2px 8px; }
.pet-details > div { display: grid; gap: 3px; }
.pet-details span { color: var(--muted); font-size: 12px; }
.pet-details b { color: var(--primary); }
.pet-affection { display: grid; gap: 7px; padding: 8px 2px 14px; }
.pet-affection > div:first-child { display: flex; justify-content: space-between; color: var(--muted); font-size: 12px; }
.pet-affection strong { color: var(--ink); }
.pet-link { min-height: 42px; display: flex; align-items: center; justify-content: space-between; padding: 0 12px; border-top: 1px solid var(--border); color: var(--primary); text-decoration: none; font-weight: 700; }
.quick-links { display: grid; gap: 8px; }
.quick-links a { min-height: 74px; display: grid; grid-template-columns: 20px minmax(0, 1fr) 20px; align-items: center; gap: 14px; padding: 11px 13px; border: 1px solid var(--border); border-radius: var(--radius); background: color-mix(in srgb, var(--surface) 90%, transparent); color: var(--ink); text-decoration: none; }
.quick-links a > span:nth-child(2) { min-width: 0; display: grid; gap: 3px; }
.quick-links small { color: var(--muted); font-size: 12px; }
.quick-links > a > svg { color: var(--muted); }
.quick-icon { width: 20px; height: 20px; display: grid; place-items: center; color: var(--muted); }
.account-band dl { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); margin: 0; border-top: 1px solid var(--border); }
.account-band dl div { min-width: 0; padding: 14px 12px; border-bottom: 1px solid var(--border); }
.account-band dt { color: var(--muted); font-size: 12px; }
.account-band dd { display: flex; align-items: center; gap: 6px; margin: 6px 0 0; overflow-wrap: anywhere; font-weight: 700; }
@media (prefers-reduced-motion: no-preference) {
  .avatar-shell { animation: avatar-arrive var(--motion-medium) var(--ease) both; }
  .quick-links a, .pet-stage, .title-item { animation: profile-enter var(--motion-medium) var(--ease) both; }
  .quick-links a { transition: border-color var(--motion-fast) var(--ease); }
  .quick-links a:hover { border-color: color-mix(in srgb, var(--primary) 28%, var(--border)); }
  .quick-links a:nth-child(2) { animation-delay: 55ms; }
  .quick-links a:nth-child(3) { animation-delay: 110ms; }
  .quick-links a:nth-child(4) { animation-delay: 165ms; }
}
@keyframes avatar-arrive { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: translateY(0); } }
@keyframes profile-enter { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: translateY(0); } }
.title-item, .pet-stage { border-radius: var(--radius-panel); }
@media (max-width: 940px) { .profile-grid { grid-template-columns: 1fr; } .title-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 620px) { .identity-head { flex-direction: column; align-items: flex-start; gap: 14px; } .identity-copy h2 { font-size: 20px; } .privacy-band { grid-template-columns: 1fr; } .privacy-control { justify-content: space-between; } .privacy-feedback { grid-column: 1; text-align: left; } .title-grid { grid-template-columns: 1fr; } .account-band dl { grid-template-columns: 1fr; } }
</style>
