<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { CheckCircle2, ShieldCheck, Sparkles, Sprout } from 'lucide-vue-next'
import { useAuthStore } from './auth.store'
import { api, type ApiError } from '../../shared/api/client'
import GrowthScene from '../../shared/ui/GrowthScene.vue'

const mode = ref<'login' | 'register' | 'forgot' | 'mfa'>('login')
const busy = ref(false)
const error = ref('')
const form = reactive({
  email: '',
  password: '',
  mfaCode: '',
  displayName: '',
  birthDate: '',
  timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
  terms: false,
  privacy: false,
  ai: false,
})
const auth = useAuthStore()
const router = useRouter()
const journeyDots = ['目标', '今日', '专注', '记录', '洞察']

async function submit() {
  error.value = ''
  busy.value = true
  try {
    if (mode.value === 'login') {
      const result = await auth.login(form.email, form.password)
      if ('status' in result) {
        form.mfaCode = ''
        mode.value = 'mfa'
        return
      }
      await router.push(result.role !== 'USER'
        ? '/admin'
        : window.betterSelfDesktop?.isDesktopApp ? '/desktop-pet' : '/today')
    } else if (mode.value === 'mfa') {
      await auth.verifyAdminMfa(form.email, form.password, form.mfaCode)
      await router.push('/admin')
    } else if (mode.value === 'forgot') {
      await api.post('/auth/password/forgot', { email: form.email })
      error.value = '如账户存在，重置方式已发送。'
    } else {
      if (!form.terms || !form.privacy || !form.ai) throw new Error('请分别确认三项同意')
      await api.post('/auth/register', {
        email: form.email,
        password: form.password,
        displayName: form.displayName,
        birthDate: form.birthDate,
        timezone: form.timezone,
        consents: { terms: '2026-07', privacy: '2026-07', ai: '2026-07' },
      })
      await auth.load()
      await router.push('/onboarding')
    }
  } catch (e) {
    error.value = (e as ApiError).message || (e as Error).message
  } finally {
    busy.value = false
  }
}

function backToLogin() {
  mode.value = 'login'
  form.mfaCode = ''
  error.value = ''
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-intro">
      <div class="brand-line">
        <span class="seal"><Sprout :size="24" /></span>
        <span>更好的自己 · Better Self</span>
      </div>
      <div class="intro-copy">
        <p class="eyebrow">一座由日常行动建成的小镇</p>
        <h1>更好的自己</h1>
        <p class="intro-lead">让每一步，<br>长成看得见的生活。</p>
        <p class="intro-detail">把长期目标放回今天，用一件可以完成的小事，慢慢建立属于你的节奏。</p>
      </div>

      <GrowthScene class="auth-scene" />
      <div class="comfort-note">
        <span><Sprout :size="23" /></span>
        <p><strong>每一个被记录的小行动</strong>都在认真回应你的努力。</p>
      </div>

      <div class="journey" aria-label="系统功能路径">
        <div class="journey-track" />
        <span v-for="(dot, index) in journeyDots" :key="dot" :style="{ '--i': index }">{{ dot }}</span>
      </div>
    </section>

    <section class="auth-panel">
      <div class="panel-card">
        <div v-if="mode !== 'mfa'" class="mode" role="tablist">
          <button @click="mode = 'login'" :aria-selected="mode === 'login'">登录</button>
          <button @click="mode = 'register'" :aria-selected="mode === 'register'">注册</button>
        </div>
        <div v-else class="mfa-head">
          <div class="mfa-icon"><ShieldCheck :size="20" /></div>
          <p class="eyebrow">管理员验证</p>
          <h2>输入动态验证码</h2>
          <p>管理员账号需要通过 MFA 后才会签发会话。</p>
        </div>

        <div v-if="mode !== 'mfa'" class="panel-head">
          <Sparkles :size="18" />
          <div>
            <p class="eyebrow">{{ mode === 'login' ? '欢迎回来' : mode === 'register' ? '创建账户' : '找回访问' }}</p>
            <h2>{{ mode === 'login' ? '接着完成今天的一小步' : mode === 'register' ? '从舒服的节奏开始' : '重新找回你的空间' }}</h2>
          </div>
        </div>

        <form class="stack" @submit.prevent="submit">
          <p v-if="error" :class="error.includes('已发送') ? 'success-note' : 'error'" role="alert">
            <CheckCircle2 v-if="error.includes('已发送')" :size="16" />
            {{ error }}
          </p>
          <div class="field">
            <label for="email">{{ mode === 'login' ? '邮箱或管理员账号' : '邮箱' }}</label>
            <input
              id="email"
              v-model="form.email"
              :type="mode === 'login' || mode === 'mfa' ? 'text' : 'email'"
              :autocomplete="mode === 'login' ? 'username' : 'email'"
              :readonly="mode === 'mfa'"
              required
            >
          </div>
          <div v-if="mode !== 'forgot'" class="field">
            <label for="password">密码</label>
            <input
              id="password"
              v-model="form.password"
              type="password"
              :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
              :readonly="mode === 'mfa'"
              :minlength="mode === 'register' ? 12 : undefined"
              required
            >
          </div>
          <div v-if="mode === 'mfa'" class="field">
            <label for="mfa-code">动态验证码</label>
            <input id="mfa-code" v-model="form.mfaCode" inputmode="numeric" autocomplete="one-time-code" maxlength="8" required>
          </div>

          <template v-if="mode === 'register'">
            <div class="field">
              <label for="name">称呼</label>
              <input id="name" v-model="form.displayName" required maxlength="80">
            </div>
            <div class="field">
              <label for="birth">出生日期</label>
              <input id="birth" v-model="form.birthDate" type="date" required>
            </div>
            <fieldset>
              <legend>同意与隐私</legend>
              <label><input v-model="form.terms" type="checkbox"> 我同意服务条款</label>
              <label><input v-model="form.privacy" type="checkbox"> 我同意隐私政策</label>
              <label><input v-model="form.ai" type="checkbox"> 我了解 AI 使用说明</label>
            </fieldset>
          </template>

          <button class="primary submit-button" :disabled="busy">
            {{ busy ? '处理中…' : mode === 'login' ? '登录' : mode === 'register' ? '创建账户' : mode === 'mfa' ? '验证并进入后台' : '发送重置方式' }}
          </button>
          <button v-if="mode === 'login'" type="button" class="link" @click="mode = 'forgot'">忘记密码</button>
          <button v-if="mode === 'mfa'" type="button" class="link" @click="backToLogin">返回登录</button>
          <button v-if="mode === 'forgot'" type="button" class="link" @click="backToLogin">返回登录</button>
        </form>
      </div>
    </section>
  </main>
</template>

<style scoped>
.auth-page { min-height: 100vh; display: grid; grid-template-columns: minmax(430px, 1fr) minmax(390px, 500px); background: var(--canvas); }
.auth-intro { position: relative; isolation: isolate; overflow: hidden; min-height: 100vh; display: flex; flex-direction: column; justify-content: space-between; gap: 30px; padding: 54px 64px; background: #efbd72; color: #432f25; }
.auth-intro::before { content: ""; position: absolute; z-index: -1; inset: 0 0 0 56%; background: #df7f60; clip-path: polygon(34% 0, 100% 0, 100% 100%, 0 100%); opacity: .72; }
.auth-intro::after { content: ""; position: absolute; z-index: -1; left: 64px; right: 64px; bottom: 38px; height: 1px; background: rgb(91 55 34 / 20%); }
.brand-line { display: inline-flex; align-items: center; gap: 12px; color: #694936; font-size: 13px; font-weight: 800; letter-spacing: 0; text-transform: uppercase; }
.seal { width: 42px; height: 42px; display: grid; place-items: center; border-radius: var(--radius-card); background: #b94f3b; color: white; font-size: 16px; font-weight: 900; line-height: 1; }
.intro-copy { max-width: 610px; }
.auth-intro .eyebrow { color: #8f4433; }
.intro-copy h1 { margin: 12px 0 15px; max-width: 9em; font-family: ui-rounded, "SF Pro Rounded", "PingFang SC", sans-serif; font-size: 64px; line-height: 1.02; letter-spacing: 0; }
.intro-lead { margin: 0 0 10px; color: #53382b; font-size: 23px; font-weight: 750; line-height: 1.45; }
.intro-detail { max-width: 500px; margin: 0; color: #6e4d3a; line-height: 1.8; }
.comfort-note { width: min(100%, 520px); display: grid; grid-template-columns: 44px minmax(0, 1fr); align-items: center; gap: 13px; padding: 15px 0; border-top: 1px solid rgb(91 55 34 / 24%); border-bottom: 1px solid rgb(91 55 34 / 24%); }
.comfort-note > span { width: 42px; height: 42px; display: grid; place-items: center; border-radius: 50%; background: #f8dfaa; color: #47725a; }
.comfort-note p { margin: 0; color: #6e4d3a; line-height: 1.6; }
.comfort-note strong { display: block; color: #432f25; }
.journey { position: relative; display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 8px; max-width: 620px; padding: 20px 0 12px; }
.journey-track { position: absolute; left: 7%; right: 7%; top: 38px; height: 2px; background: rgb(91 55 34 / 20%); overflow: hidden; }
.journey-track::after { content: ""; display: block; width: 38%; height: 100%; background: #47725a; }
.journey span { position: relative; display: grid; justify-items: center; gap: 9px; color: #674736; font-size: 12px; font-weight: 800; }
.journey span::before { content: ""; width: 13px; height: 13px; border: 2px solid #744c36; border-radius: 50%; background: #f6d79c; box-shadow: 0 0 0 6px rgb(255 245 220 / 28%); }
.auth-panel { display: grid; place-items: center; overflow-y: auto; padding: 34px 40px; background: color-mix(in srgb, var(--surface) 44%, var(--canvas)); }
.panel-card { position: relative; width: min(100%, 420px); padding: 25px; border: 1px solid var(--border); border-radius: var(--radius-card); background: var(--surface); }
.panel-card::before { content: ""; position: absolute; inset: 0 0 auto; height: 4px; border-radius: var(--radius-card) var(--radius-card) 0 0; background: var(--primary); }
.mode { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 5px; padding: 4px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface-muted); margin-bottom: 22px; }
.mode button { min-width: 0; border: 0; background: transparent; color: var(--muted); }
.mode button[aria-selected=true] { background: var(--surface); color: var(--primary-strong); font-weight: 800; }
.panel-head { display: grid; grid-template-columns: 36px minmax(0, 1fr); gap: 11px; align-items: start; margin-bottom: 18px; }
.panel-head > svg, .mfa-icon { width: 36px; height: 36px; display: grid; place-items: center; border-radius: var(--radius-card); color: var(--primary); background: var(--primary-soft); }
.panel-head h2 { margin: 0; font-family: ui-rounded, "SF Pro Rounded", "PingFang SC", sans-serif; font-size: 22px; line-height: 1.28; letter-spacing: 0; }
.mfa-head { margin-bottom: 22px; }
.mfa-head h2 { margin: 8px 0 0; font-size: 24px; line-height: 1.2; }
.mfa-head p:last-child { margin: 8px 0 0; color: var(--muted); line-height: 1.6; }
input[readonly] { background: var(--surface-muted); color: var(--muted); }
fieldset { border: 1px solid var(--border); border-radius: var(--radius); display: grid; gap: 10px; padding: 14px; background: color-mix(in srgb, var(--surface-muted) 48%, var(--surface)); }
fieldset label { font-size: 14px; }
.submit-button { width: 100%; margin-top: 2px; }
.link { justify-self: center; background: none; color: var(--primary-strong); }
.success-note { display: inline-flex; align-items: center; gap: 7px; margin: 0; color: var(--accent-strong); background: color-mix(in srgb, var(--accent) 10%, var(--surface)); border: 1px solid color-mix(in srgb, var(--accent) 24%, var(--border)); border-radius: var(--radius); padding: 11px 12px; }
@media (prefers-reduced-motion: no-preference) {
  .seal { animation: seal-enter var(--motion-medium) var(--ease) both; }
  .journey-track::after { animation: journey-fill 3.8s var(--ease) infinite; }
  .journey span::before { animation: dot-pulse 3.8s var(--ease) infinite; animation-delay: calc(var(--i) * 240ms); }
  .auth-panel { animation: auth-panel-enter var(--motion-medium) var(--ease) both; }
}
@keyframes seal-enter { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: translateY(0); } }
@keyframes auth-panel-enter { from { opacity: 0; transform: translateX(4px); } to { opacity: 1; transform: translateX(0); } }
@keyframes journey-fill { 0% { transform: translateX(-105%); } 55%, 100% { transform: translateX(270%); } }
@keyframes dot-pulse { 0%, 80%, 100% { transform: scale(1); background: #f6d79c; } 18% { transform: scale(1.22); background: #5d8c70; } }
@media (max-width: 920px) {
  .auth-page { grid-template-columns: 1fr; }
  .auth-intro { min-height: auto; padding: 30px 24px; }
  .auth-intro::before { inset: 0 0 0 68%; }
  .auth-intro::after { left: 24px; right: 24px; }
  .intro-copy h1 { font-size: 44px; }
  .intro-lead { font-size: 19px; }
  .comfort-note { display: none; }
  .auth-panel { padding: 26px 18px 38px; }
}
@media (max-width: 520px) {
  .auth-intro { gap: 22px; }
  .brand-line { font-size: 12px; }
  .intro-copy h1 { font-size: 38px; }
  .journey { grid-template-columns: repeat(5, 1fr); gap: 2px; }
  .journey span { font-size: 10px; }
  .panel-card { padding: 20px; }
}
.auth-page { grid-template-columns: minmax(0, 1.12fr) minmax(390px, .88fr); }
.auth-intro { padding: 44px 56px 30px; background: var(--forest); color: var(--on-forest); gap: 24px; justify-content: flex-start; }
.auth-intro::before, .auth-intro::after, .panel-card::before { display: none; }
.brand-line { color: #d3dfce; letter-spacing: .08em; font-weight: 500; font-size: 12px; }
.seal { background: var(--sun); color: var(--forest); border-radius: var(--radius-card) var(--radius-card) var(--radius) var(--radius); }
.intro-copy { position: relative; z-index: 1; margin-top: 40px; }
.auth-intro .eyebrow { color: #b1c3a7; font-size: 11px; }
.intro-copy h1 { font-size: 17px; line-height: 1.5; font-weight: 500; margin: 10px 0 16px; }
.intro-lead { font-size: clamp(34px, 3.6vw, 54px); font-weight: 650; line-height: 1.35; letter-spacing: -1px; color: var(--on-forest); margin-bottom: 20px; }
.intro-detail { max-width: 360px; color: #b8cdbb; font-size: 13px; }
.auth-scene { width: min(100%, 540px); align-self: center; margin-top: -30px; }
.comfort-note { display: none; }
.journey { width: 100%; margin-top: auto; padding-top: 8px; }
.journey-track { top: 18px; background: #63806a; }
.journey-track::after { background: var(--sun); animation: none; }
.journey span { color: #c0d1bd; font-size: 10px; font-weight: 500; }
.journey span::before { width: 8px; height: 8px; background: var(--sun); border-color: var(--sun); box-shadow: 0 0 0 4px #426047; animation: none; }
.auth-panel { background: var(--canvas); padding: 48px; }
.panel-card { border: 0; padding: 0; border-radius: 0; background: transparent; max-width: 360px; }
.panel-head { margin-block: 32px 24px; }
.panel-head h2 { font-size: 26px; line-height: 1.5; }
.mode { background: transparent; border: 0; border-bottom: 1px solid var(--border); padding: 0; border-radius: 0; gap: 24px; }
.mode button { border-radius: 0; padding-bottom: 14px; }
.mode button[aria-selected=true] { background: transparent; box-shadow: 0 2px 0 var(--primary); }
@media (max-width: 920px) {
 .auth-page { grid-template-columns: 1fr; }
 .auth-intro { min-height: auto; padding: 26px; gap: 14px; }
 .intro-copy { margin-top: 10px; }
 .intro-lead { font-size: 34px; }
 .intro-detail, .journey, .auth-scene { display: none; }
 .intro-copy h1 { margin-bottom: 8px; }
 .auth-panel { padding: 32px 24px 48px; }
}
</style>
