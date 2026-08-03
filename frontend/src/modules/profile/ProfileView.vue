<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { BarChart3, CalendarDays, ChevronRight, Coins, PawPrint, Settings, Sparkles, Target, UserRound, Users } from 'lucide-vue-next'
import { api } from '../../shared/api/client'
import RivePet from '../partners/RivePet.vue'
import type { Pet, Wallet } from '../partners/partner.types'

type Profile = {
  publicId: string
  email: string
  displayName: string
  birthDate: string
  age: number
  timezone: string
  createdAt: string
  overallLevel: number
  totalExperience: number
  effectiveActions: number
  wallet: Wallet
  selectedPet: Pet
  petCount: number
}

const profile = ref<Profile | null>(null)
const loading = ref(true)
const error = ref('')
const initial = computed(() => {
  const name = profile.value?.displayName.trim() ?? ''
  return Array.from(name)[0]?.toUpperCase() ?? '我'
})
const memberSince = computed(() => profile.value
  ? new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' }).format(new Date(profile.value.createdAt))
  : '')
const petProgress = computed(() => {
  const pet = profile.value?.selectedPet
  if (!pet) return 0
  return Math.min(100, Math.round(pet.affection * 100 / Math.max(1, pet.nextLevelAffection)))
})

onMounted(async () => {
  try {
    profile.value = await api.get<Profile>('/me/profile')
  } catch {
    error.value = '个人资料暂时无法加载'
  } finally {
    loading.value = false
  }
})
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
        <div class="avatar" aria-hidden="true">{{ initial }}</div>
        <div class="identity-copy">
          <p class="eyebrow">LV.{{ profile.overallLevel }} 成长者</p>
          <h2>{{ profile.displayName }}</h2>
          <p>{{ profile.age }} 岁 · {{ profile.timezone }} · {{ memberSince }}加入</p>
        </div>
        <div class="identity-stats">
          <div><strong>{{ profile.totalExperience }}</strong><span>累计经验</span></div>
          <div><strong>{{ profile.effectiveActions }}</strong><span>有效行动</span></div>
          <div><strong>{{ profile.wallet.coinBalance }}</strong><span>当前金币</span></div>
        </div>
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
            <RouterLink to="/goals"><span class="quick-icon amber"><Target :size="20" /></span><span><strong>目标与计划</strong><small>调整目标、周计划和任务</small></span><ChevronRight :size="17" /></RouterLink>
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
.avatar { width: 88px; aspect-ratio: 1; display: grid; place-items: center; border-radius: 26px 26px 26px 8px; background: linear-gradient(145deg, var(--primary), color-mix(in srgb, var(--primary) 72%, var(--amber))); color: white; font-size: 34px; font-weight: 900; box-shadow: 0 14px 30px color-mix(in srgb, var(--primary) 24%, transparent); }
.identity-copy h2 { margin: 0; font-size: 28px; }
.identity-copy > p:last-child { margin: 8px 0 0; color: var(--muted); font-size: 13px; }
.identity-stats { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); border: 1px solid var(--border); border-radius: var(--radius); background: color-mix(in srgb, var(--surface) 88%, transparent); overflow: hidden; box-shadow: var(--shadow-soft); }
.identity-stats div { min-height: 86px; display: grid; align-content: center; gap: 5px; padding: 14px; border-right: 1px solid var(--border); }
.identity-stats div:last-child { border-right: 0; }
.identity-stats strong { color: var(--primary); font-size: 23px; }
.identity-stats span { color: var(--muted); font-size: 12px; }
.profile-grid { display: grid; grid-template-columns: minmax(340px, .85fr) minmax(0, 1fr); gap: 28px; padding: 28px 0; }
.pet-section, .quick-section { min-width: 0; }
.section-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; color: var(--primary); }
.section-title h2 { margin: 0; color: var(--ink); font-size: 18px; }
.pet-stage { position: relative; height: 250px; display: grid; place-items: center; border: 1px solid color-mix(in srgb, var(--accent) 25%, var(--border)); border-radius: 68px 68px 8px 8px; background: linear-gradient(180deg, color-mix(in srgb, var(--accent) 10%, var(--surface)), color-mix(in srgb, var(--amber) 9%, var(--surface))); overflow: hidden; }
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
.quick-links a { min-height: 74px; display: grid; grid-template-columns: 42px minmax(0, 1fr) 20px; align-items: center; gap: 12px; padding: 11px 13px; border: 1px solid var(--border); border-radius: var(--radius); background: color-mix(in srgb, var(--surface) 90%, transparent); color: var(--ink); text-decoration: none; box-shadow: var(--shadow-soft); }
.quick-links a > span:nth-child(2) { min-width: 0; display: grid; gap: 3px; }
.quick-links small { color: var(--muted); font-size: 12px; }
.quick-links > a > svg { color: var(--muted); }
.quick-icon { width: 40px; height: 40px; display: grid; place-items: center; border-radius: 13px; }
.quick-icon.coral { color: var(--primary); background: var(--primary-soft); }
.quick-icon.amber { color: var(--amber); background: color-mix(in srgb, var(--amber) 12%, var(--surface)); }
.quick-icon.green { color: var(--accent); background: color-mix(in srgb, var(--accent) 12%, var(--surface)); }
.quick-icon.blue { color: #3f7f8f; background: color-mix(in srgb, #3f7f8f 12%, var(--surface)); }
.quick-icon.violet { color: #70517a; background: color-mix(in srgb, #70517a 12%, var(--surface)); }
.account-band dl { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); margin: 0; border-top: 1px solid var(--border); }
.account-band dl div { min-width: 0; padding: 14px 12px; border-bottom: 1px solid var(--border); }
.account-band dt { color: var(--muted); font-size: 12px; }
.account-band dd { display: flex; align-items: center; gap: 6px; margin: 6px 0 0; overflow-wrap: anywhere; font-weight: 700; }
@media (prefers-reduced-motion: no-preference) {
  .avatar { animation: avatar-arrive var(--motion-slow) cubic-bezier(.2,.8,.2,1) both; }
  .identity-stats div, .quick-links a, .pet-stage { animation: profile-enter var(--motion-medium) ease-out both; }
  .quick-links a { transition: transform var(--motion-fast) ease, border-color var(--motion-fast) ease, box-shadow var(--motion-fast) ease; }
  .quick-links a:hover { transform: translateX(4px); border-color: color-mix(in srgb, var(--primary) 28%, var(--border)); box-shadow: var(--shadow); }
  .quick-links a:nth-child(2) { animation-delay: 55ms; }
  .quick-links a:nth-child(3) { animation-delay: 110ms; }
  .quick-links a:nth-child(4) { animation-delay: 165ms; }
}
@keyframes avatar-arrive { from { opacity: 0; transform: rotate(-7deg) scale(.92); } to { opacity: 1; transform: rotate(0) scale(1); } }
@keyframes profile-enter { from { opacity: 0; transform: translateY(7px); } to { opacity: 1; transform: translateY(0); } }
@media (max-width: 940px) { .identity-band { grid-template-columns: 82px minmax(0, 1fr); } .identity-stats { grid-column: 1 / -1; } .profile-grid { grid-template-columns: 1fr; } }
@media (max-width: 620px) { .identity-band { grid-template-columns: 68px minmax(0, 1fr); gap: 14px; } .avatar { width: 66px; border-radius: 20px 20px 20px 7px; font-size: 25px; } .identity-copy h2 { font-size: 23px; } .identity-stats { grid-template-columns: repeat(3, minmax(0, 1fr)); } .identity-stats div { padding: 10px; } .identity-stats strong { font-size: 19px; } .account-band dl { grid-template-columns: 1fr; } }
</style>
