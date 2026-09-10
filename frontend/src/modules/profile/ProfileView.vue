<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { Award, BarChart3, CalendarDays, Check, ChevronRight, Coins, EyeOff, PawPrint, Settings, Sparkles, Target, UserRound, Users, X } from 'lucide-vue-next'
import { onDataChanged } from '../../shared/data-sync'
import { growthIcon } from '../achievements/achievement.types'
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
      <div><p class="eyebrow">你的成长档案</p><h1>个人</h1></div>
      <RouterLink class="secondary button" to="/settings"><Settings :size="17" />设置</RouterLink>
    </header>

    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-else-if="loading" class="loading-state" role="status">正在整理你的个人档案…</div>
    <template v-else-if="profile">
      <section class="identity-band">
        <div class="avatar-shell" :data-frame="profile.equippedTitle?.frameStyle ?? 'default'">
          <div class="avatar" aria-hidden="true">{{ initial }}</div>
        </div>
        <div class="identity-copy">
          <p class="eyebrow">LV.{{ profile.overallLevel }} 成长者</p>
          <h2>{{ profile.displayName }}</h2>
          <span v-if="profile.equippedTitle" class="equipped-title"><component :is="growthIcon(profile.equippedTitle.graphicKey)" :size="15" />{{ profile.equippedTitle.name }}</span>
          <p>{{ profile.age }} 岁 · {{ profile.timezone }} · {{ memberSince }}加入</p>
        </div>
        <div class="identity-stats">
          <div><strong>{{ profile.totalExperience }}</strong><span>累计经验</span></div>
          <div><strong>{{ profile.effectiveActions }}</strong><span>有效行动</span></div>
          <div><strong>{{ profile.wallet.coinBalance }}</strong><span>当前金币</span></div>
        </div>
      </section>

      <section class="privacy-band band" aria-labelledby="solo-growth-title">
        <div class="privacy-copy">
          <span class="privacy-icon" aria-hidden="true"><EyeOff :size="21" /></span>
          <div>
            <p class="eyebrow">好友隐私</p>
            <h2 id="solo-growth-title">独自升级</h2>
            <p>开启后，其他用户无法通过邮箱搜索或添加你；现有好友关系不受影响。</p>
          </div>
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
        <div class="section-title">
          <div><p class="eyebrow">个人称号</p><h2 id="titles-title">你的行动留下了这些名字</h2></div>
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
        <p v-else class="empty">完成行动后，称号会出现在这里。</p>
      </section>

      <div class="profile-grid">
        <section class="pet-section" aria-labelledby="profile-pet-title">
          <div class="section-title"><div><p class="eyebrow">当前伙伴</p><h2 id="profile-pet-title">{{ profile.selectedPet.name }}正在陪着你</h2></div><PawPrint :size="21" /></div>
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
          <div class="section-title"><div><p class="eyebrow">快速前往</p><h2 id="quick-title">继续今天的成长</h2></div><Sparkles :size="21" /></div>
          <nav class="quick-links" aria-label="个人页快捷入口">
            <RouterLink to="/today"><span class="quick-icon coral"><CalendarDays :size="20" /></span><span><strong>今日行动</strong><small>查看今天要完成的任务</small></span><ChevronRight :size="17" /></RouterLink>
            <RouterLink to="/goals"><span class="quick-icon amber"><Target :size="20" /></span><span><strong>目标与任务</strong><small>调整目标和周期任务</small></span><ChevronRight :size="17" /></RouterLink>
            <RouterLink to="/attributes"><span class="quick-icon green"><BarChart3 :size="20" /></span><span><strong>成长属性</strong><small>查看智力等五项属性</small></span><ChevronRight :size="17" /></RouterLink>
            <RouterLink to="/insights"><span class="quick-icon blue"><Sparkles :size="20" /></span><span><strong>个人洞察</strong><small>回看行动趋势与徽章</small></span><ChevronRight :size="17" /></RouterLink>
            <RouterLink to="/friends"><span class="quick-icon violet"><Users :size="20" /></span><span><strong>好友</strong><small>添加好友，看看彼此的成长</small></span><ChevronRight :size="17" /></RouterLink>
          </nav>
        </section>
      </div>

      <section class="account-band band" aria-labelledby="account-title">
        <div class="section-title"><div><p class="eyebrow">账户信息</p><h2 id="account-title">基本资料</h2></div><UserRound :size="21" /></div>
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
.identity-band { min-height: 174px; display: grid; grid-template-columns: 92px minmax(220px, 1fr) minmax(380px, 1.2fr); align-items: center; gap: 22px; padding: 24px 0 28px; border-top: 1px solid var(--border); border-bottom: 1px solid var(--border); }
.avatar-shell { width: 92px; aspect-ratio: 1; display: grid; place-items: center; padding: 3px; border: 1px solid transparent; border-radius: var(--radius-scene) var(--radius-scene) var(--radius-scene) var(--radius-card); }
.avatar { width: 100%; height: 100%; display: grid; place-items: center; border-radius: var(--radius-scene) var(--radius-scene) var(--radius-scene) var(--radius); background: linear-gradient(145deg, var(--primary), color-mix(in srgb, var(--primary) 72%, var(--amber))); color: white; font-size: 34px; font-weight: 900; }
.avatar-shell[data-frame='emerald'], .avatar-shell[data-frame='green'] { border-color: var(--accent); box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 14%, transparent); }
.avatar-shell[data-frame='sky'] { border-color: var(--dim-relationship); box-shadow: 0 0 0 3px color-mix(in srgb, var(--dim-relationship) 14%, transparent); }
.avatar-shell[data-frame='ember'] { border-color: var(--amber); box-shadow: 0 0 0 3px color-mix(in srgb, var(--amber) 15%, transparent); }
.avatar-shell[data-frame='gold'] { border-color: var(--tone-gold); box-shadow: 0 0 0 3px color-mix(in srgb, var(--tone-gold) 15%, transparent); }
.avatar-shell[data-frame='violet'] { border-color: var(--tone-violet); box-shadow: 0 0 0 3px color-mix(in srgb, var(--tone-violet) 15%, transparent); }
.avatar-shell[data-frame='rose'] { border-color: var(--tone-rose); box-shadow: 0 0 0 3px color-mix(in srgb, var(--tone-rose) 15%, transparent); }
.avatar-shell[data-frame='rainbow'] { border-color: var(--primary); box-shadow: 3px 3px 0 var(--accent), -3px -3px 0 var(--amber); }
.identity-copy h2 { margin: 0; font-size: 28px; }
.identity-copy > p:last-child { margin: 8px 0 0; color: var(--muted); font-size: 13px; }
.equipped-title { width: fit-content; min-height: 28px; display: inline-flex; align-items: center; gap: 6px; margin-top: 7px; padding: 0 8px; border: 1px solid color-mix(in srgb, var(--primary) 26%, var(--border)); border-radius: var(--radius); background: var(--primary-soft); color: var(--primary); font-size: 12px; font-weight: 800; }
.identity-stats { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); border: 1px solid var(--border); border-radius: var(--radius); background: color-mix(in srgb, var(--surface) 88%, transparent); overflow: hidden; }
.identity-stats div { min-height: 86px; display: grid; align-content: center; gap: 5px; padding: 14px; border-right: 1px solid var(--border); }
.identity-stats div:last-child { border-right: 0; }
.identity-stats strong { color: var(--primary); font-size: 23px; }
.identity-stats span { color: var(--muted); font-size: 12px; }
.privacy-band { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 10px 24px; padding: 22px 0; }
.privacy-copy { min-width: 0; display: grid; grid-template-columns: 42px minmax(0, 1fr); align-items: center; gap: 12px; }
.privacy-icon { width: 42px; height: 42px; display: grid; place-items: center; border-radius: var(--radius); background: color-mix(in srgb, var(--accent) 12%, var(--surface)); color: var(--accent); }
.privacy-copy h2 { margin: 2px 0 3px; font-size: 18px; }
.privacy-copy p:last-child { margin: 0; color: var(--muted); font-size: 12px; line-height: 1.6; }
.privacy-control { display: flex; align-items: center; justify-content: flex-end; gap: 12px; }
.privacy-control > span { color: var(--muted); font-size: 12px; white-space: nowrap; }
.privacy-switch { position: relative; flex: 0 0 auto; width: 48px; height: 28px; min-height: 28px; padding: 0; border: 1px solid var(--border); border-radius: 999px; background: var(--surface-muted); }
.privacy-switch > span { position: absolute; top: 3px; left: 3px; width: 20px; height: 20px; border-radius: 50%; background: var(--surface); transition: transform var(--motion-fast) var(--ease); }
.privacy-switch[aria-checked='true'] { border-color: var(--accent); background: var(--accent); }
.privacy-switch[aria-checked='true'] > span { transform: translateX(20px); }
.privacy-switch:disabled { cursor: wait; opacity: .65; }
.privacy-feedback { grid-column: 2; margin: 0; color: var(--primary); font-size: 12px; text-align: right; }
.profile-grid { display: grid; grid-template-columns: minmax(340px, .85fr) minmax(0, 1fr); gap: 28px; padding: 28px 0; }
.titles-band { padding-top: 28px; }
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
.section-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; color: var(--primary); }
.section-title h2 { margin: 0; color: var(--ink); font-size: 18px; }
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
.quick-links a { min-height: 74px; display: grid; grid-template-columns: 42px minmax(0, 1fr) 20px; align-items: center; gap: 12px; padding: 11px 13px; border: 1px solid var(--border); border-radius: var(--radius); background: color-mix(in srgb, var(--surface) 90%, transparent); color: var(--ink); text-decoration: none; }
.quick-links a > span:nth-child(2) { min-width: 0; display: grid; gap: 3px; }
.quick-links small { color: var(--muted); font-size: 12px; }
.quick-links > a > svg { color: var(--muted); }
.quick-icon { width: 40px; height: 40px; display: grid; place-items: center; border-radius: var(--radius-panel); }
.quick-icon.coral { color: var(--primary); background: var(--primary-soft); }
.quick-icon.amber { color: var(--amber); background: color-mix(in srgb, var(--amber) 12%, var(--surface)); }
.quick-icon.green { color: var(--accent); background: color-mix(in srgb, var(--accent) 12%, var(--surface)); }
.quick-icon.blue { color: var(--dim-relationship); background: color-mix(in srgb, var(--dim-relationship) 12%, var(--surface)); }
.quick-icon.violet { color: var(--tone-violet); background: color-mix(in srgb, var(--tone-violet) 12%, var(--surface)); }
.account-band dl { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); margin: 0; border-top: 1px solid var(--border); }
.account-band dl div { min-width: 0; padding: 14px 12px; border-bottom: 1px solid var(--border); }
.account-band dt { color: var(--muted); font-size: 12px; }
.account-band dd { display: flex; align-items: center; gap: 6px; margin: 6px 0 0; overflow-wrap: anywhere; font-weight: 700; }
@media (prefers-reduced-motion: no-preference) {
  .avatar-shell { animation: avatar-arrive var(--motion-medium) var(--ease) both; }
  .identity-stats div, .quick-links a, .pet-stage, .title-item { animation: profile-enter var(--motion-medium) var(--ease) both; }
  .quick-links a { transition: border-color var(--motion-fast) var(--ease); }
  .quick-links a:hover { border-color: color-mix(in srgb, var(--primary) 28%, var(--border)); }
  .quick-links a:nth-child(2) { animation-delay: 55ms; }
  .quick-links a:nth-child(3) { animation-delay: 110ms; }
  .quick-links a:nth-child(4) { animation-delay: 165ms; }
}
@keyframes avatar-arrive { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: translateY(0); } }
@keyframes profile-enter { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: translateY(0); } }
@media (max-width: 940px) { .identity-band { grid-template-columns: 82px minmax(0, 1fr); } .identity-stats { grid-column: 1 / -1; } .profile-grid { grid-template-columns: 1fr; } .title-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 620px) { .identity-band { grid-template-columns: 68px minmax(0, 1fr); gap: 14px; } .avatar-shell { width: 68px; border-radius: var(--radius-scene) var(--radius-scene) var(--radius-scene) var(--radius-card); } .avatar { border-radius: var(--radius-panel) var(--radius-panel) var(--radius-panel) var(--radius); font-size: 25px; } .identity-copy h2 { font-size: 23px; } .identity-stats { grid-template-columns: repeat(3, minmax(0, 1fr)); } .identity-stats div { padding: 10px; } .identity-stats strong { font-size: 19px; } .privacy-band { grid-template-columns: 1fr; } .privacy-control { justify-content: space-between; } .privacy-feedback { grid-column: 1; text-align: left; } .title-grid { grid-template-columns: 1fr; } .account-band dl { grid-template-columns: 1fr; } }
.profile-page { max-width: 1264px; }
.identity-band { padding: 30px; border-radius: var(--radius-scene); background: var(--forest); border: 0; color: var(--on-forest); margin-bottom: 28px; }
.identity-band .eyebrow, .identity-band .identity-copy p, .identity-band .identity-stats span { color: var(--nav-ink); }
.identity-band .identity-copy h2 { color: var(--on-forest); }
.identity-band .identity-stats strong { color: var(--sun); font-weight: 600; }
.identity-band .avatar { background: var(--sun); color: var(--forest); }
.identity-band .avatar-shell { border-color: var(--nav-card-border); }
.title-item, .pet-stage { border-radius: var(--radius-panel); }
.profile-grid > section { padding: 22px; border: 1px solid var(--border); background: var(--surface); border-radius: var(--radius-panel); }
@media (max-width:760px) { .identity-band { padding: 22px; } .profile-grid > section { padding: 16px; } }
</style>
