<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { CheckCircle2, ShieldCheck, Sparkles, Sprout } from 'lucide-vue-next'
import { useAuthStore } from './auth.store'
import { api, type ApiError } from '../../shared/api/client'

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

const adult = computed(() => {
  if (!form.birthDate) return false
  const born = new Date(`${form.birthDate}T00:00:00`)
  const threshold = new Date()
  threshold.setFullYear(threshold.getFullYear() - 18)
  return born <= threshold
})

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
      await router.push(window.betterSelfDesktop?.isDesktopApp ? '/desktop-pet' : '/today')
    } else if (mode.value === 'mfa') {
      await auth.verifyAdminMfa(form.email, form.password, form.mfaCode)
      await router.push('/admin')
    } else if (mode.value === 'forgot') {
      await api.post('/auth/password/forgot', { email: form.email })
      error.value = '如账户存在，重置方式已发送。'
    } else {
      if (!adult.value) throw new Error('仅面向年满 18 岁的用户')
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
        <span class="seal">HI</span>
        <span>Better Self</span>
      </div>
      <div class="intro-copy">
        <p class="eyebrow">个人成长操作台</p>
        <h1>更好的自己</h1>
        <p class="intro-lead">今天不必完美，向前一点就很好。</p>
        <p class="intro-detail">把长期目标放回今天，用一件可以完成的小事，慢慢建立属于你的节奏。</p>
      </div>

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
            <label for="email">邮箱</label>
            <input id="email" v-model="form.email" type="email" autocomplete="email" :readonly="mode === 'mfa'" required>
          </div>
          <div v-if="mode !== 'forgot'" class="field">
            <label for="password">密码</label>
            <input id="password" v-model="form.password" type="password" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" :readonly="mode === 'mfa'" minlength="12" required>
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
              <small v-if="form.birthDate && !adult" class="inline-error">需年满 18 岁</small>
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
.seal { width: 42px; height: 42px; display: grid; place-items: center; border-radius: 8px; background: #b94f3b; color: white; font-size: 16px; font-weight: 900; line-height: 1; box-shadow: 0 12px 26px rgb(106 53 32 / 20%); }
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
.panel-card { position: relative; width: min(100%, 420px); padding: 25px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface); box-shadow: var(--shadow); }
.panel-card::before { content: ""; position: absolute; inset: 0 0 auto; height: 4px; border-radius: 8px 8px 0 0; background: var(--primary); }
.mode { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 5px; padding: 4px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface-muted); margin-bottom: 22px; }
.mode button { min-width: 0; border: 0; background: transparent; color: var(--muted); }
.mode button[aria-selected=true] { background: var(--surface); color: var(--primary-strong); font-weight: 800; box-shadow: var(--shadow-soft); }
.panel-head { display: grid; grid-template-columns: 36px minmax(0, 1fr); gap: 11px; align-items: start; margin-bottom: 18px; }
.panel-head > svg, .mfa-icon { width: 36px; height: 36px; display: grid; place-items: center; border-radius: 8px; color: var(--primary); background: var(--primary-soft); }
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
.inline-error { color: var(--danger); font-size: 12px; }
@media (prefers-reduced-motion: no-preference) {
  .seal { animation: seal-enter var(--motion-slow) ease-out both; }
  .journey-track::after { animation: journey-fill 3.8s ease-in-out infinite; }
  .journey span::before { animation: dot-pulse 3.8s ease-in-out infinite; animation-delay: calc(var(--i) * 240ms); }
  .auth-panel { animation: auth-panel-enter var(--motion-medium) ease-out both; }
  .mode button[aria-selected=true] { animation: tab-settle var(--motion-medium) ease-out; }
}
@keyframes seal-enter { from { opacity: 0; transform: translateY(8px) rotate(-8deg); } to { opacity: 1; transform: translateY(0) rotate(0); } }
@keyframes auth-panel-enter { from { opacity: 0; transform: translateX(12px); } to { opacity: 1; transform: translateX(0); } }
@keyframes journey-fill { 0% { transform: translateX(-105%); } 55%, 100% { transform: translateX(270%); } }
@keyframes dot-pulse { 0%, 80%, 100% { transform: scale(1); background: #f6d79c; } 18% { transform: scale(1.22); background: #5d8c70; } }
@keyframes tab-settle { from { box-shadow: inset 0 -8px color-mix(in srgb, var(--primary) 12%, transparent); } to { box-shadow: var(--shadow-soft); } }
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
</style>
