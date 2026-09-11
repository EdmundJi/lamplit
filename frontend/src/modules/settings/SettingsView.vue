<script setup lang="ts">
import { computed, onMounted, ref, type Component } from 'vue'
import { AlertTriangle, Download, LogOut, Monitor, Moon, PanelsTopLeft, RotateCcw, Sparkles, Sun, Trash2, Zap } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../auth/auth.store'
import {
  accentOptions,
  densityOptions,
  motionOptions,
  radiusOptions,
  themeOptions,
  useAppearanceStore,
  type AccentTone,
  type Density,
  type MotionLevel,
  type RadiusStyle,
  type ThemeMode,
} from '../../shared/ui/appearance.store'
import { radialReveal } from '../../shared/ui/interaction/radial-reveal'
import SegmentedControl from '../../shared/ui/interaction/SegmentedControl.vue'
import { channelHint, channelLabel, retentionOptions, useSettingsData } from './settings.logic'

const { prefs, notifications, deletion, exportJob, error, load, setRetention, toggleNotification, createExport, requestDeletion, cancelDeletion } = useSettingsData()
import { useWorkspaceModeStore } from '../../shared/ui/workspace-mode.store'
const mode = useWorkspaceModeStore()
const auth = useAuthStore()
const router = useRouter()
const appearance = useAppearanceStore()
const currentAccent = computed(() => accentOptions.find(option => option.value === appearance.accent) ?? accentOptions[0])
const confirmingDeletion = ref(false)

appearance.hydrate()

const themeIcons: Record<ThemeMode, Component> = { light: Sun, dark: Moon, system: Monitor }
const themeSegmentOptions = themeOptions.map(option => ({ ...option, icon: themeIcons[option.value] }))
const densitySegmentOptions = densityOptions.map(option => ({ ...option, icon: PanelsTopLeft }))
const motionSegmentOptions = motionOptions.map(option => ({ ...option, icon: Zap }))
const retentionSegmentOptions = retentionOptions.map(days => ({ value: String(days), label: `${days} 天` }))

// Every appearance control repaints the page; the new look grows out of the
// button that caused it, so the change reads as an answer to that press.
function restyle(event: Event, change: () => void) {
  void radialReveal(event, () => { change(); appearance.apply() })
}

// Same full-shell repaint as switching theme; reuse the same reveal so the
// two kinds of "everything just changed" moments feel like one thing.
function switchWorkspaceMode(event: Event) {
  void radialReveal(event, () => {
    mode.setMinimal(!mode.minimal)
    void router.push('/today')
  })
}

onMounted(() => load())

async function retention(days: number) {
  await setRetention(days)
}

async function confirmRequestDeletion() {
  await requestDeletion()
  confirmingDeletion.value = false
}

async function logout() {
  await auth.logout()
  router.push('/auth')
}

function replayWelcome() {
  if (auth.user?.publicId) window.localStorage.removeItem(`better-self:welcome:${auth.user.publicId}`)
  window.dispatchEvent(new CustomEvent('better-self:show-welcome'))
}
</script>

<template>
  <section class="page settings-page">
    <header class="page-head">
      <div>
        <h1>设置</h1>
      </div>
      <button class="secondary" @click="logout">
        <LogOut :size="17" />
        退出
      </button>
    </header>

    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <section class="band">
      <h2 class="section-title">使用模式</h2>
      <p>极简模式只保留执行清单。切换模式不会改变已有任务，偏好保存在当前设备。</p>
      <button class="secondary" :aria-pressed="mode.minimal" @click="switchWorkspaceMode($event)">{{ mode.minimal ? '切换成长模式' : '切换极简清单' }}</button>
    </section>

    <section class="band appearance-section">
      <h2 class="section-title">外观风格</h2>

      <div class="appearance-grid">
        <fieldset class="option-group">
          <legend>主题</legend>
          <SegmentedControl
            :model-value="appearance.theme"
            :options="themeSegmentOptions"
            label="主题"
            @update:model-value="(value, event) => restyle(event!, () => appearance.setTheme(value as ThemeMode))"
          />
        </fieldset>

        <fieldset class="option-group visual-group">
          <legend>视觉风格</legend>
          <div class="swatches">
            <button
              v-for="option in accentOptions"
              :key="option.value"
              type="button"
              :aria-label="option.label"
              :aria-pressed="appearance.accent === option.value"
              :style="{ '--preview-primary': option.swatch, '--preview-accent': option.accent, '--preview-surface': option.surface }"
              @click="restyle($event, () => appearance.setAccent(option.value as AccentTone))"
            >
              <span class="theme-preview" aria-hidden="true">
                <span class="preview-sidebar" />
                <span class="preview-content"><i /><i /><i /></span>
              </span>
              <span class="swatch-copy"><strong>{{ option.label }}</strong><small>{{ option.description }}</small></span>
            </button>
          </div>
        </fieldset>

        <fieldset class="option-group">
          <legend>界面密度</legend>
          <SegmentedControl
            :model-value="appearance.density"
            :options="densitySegmentOptions"
            label="界面密度"
            @update:model-value="(value, event) => restyle(event!, () => appearance.setDensity(value as Density))"
          />
        </fieldset>

        <fieldset class="option-group">
          <legend>动画强度</legend>
          <SegmentedControl
            :model-value="appearance.motion"
            :options="motionSegmentOptions"
            label="动画强度"
            @update:model-value="(value, event) => restyle(event!, () => appearance.setMotion(value as MotionLevel))"
          />
        </fieldset>

        <fieldset class="option-group">
          <legend>圆角风格</legend>
          <SegmentedControl
            :model-value="appearance.radius"
            :options="radiusOptions"
            label="圆角风格"
            @update:model-value="(value, event) => restyle(event!, () => appearance.setRadius(value as RadiusStyle))"
          />
        </fieldset>

        <div class="appearance-actions">
          <div class="current-style">
            <span :style="{ background: currentAccent.swatch }" aria-hidden="true" />
            <div><small>当前风格</small><strong>{{ currentAccent.label }}</strong></div>
          </div>
          <button type="button" class="secondary" @click="restyle($event, appearance.reset)">
            <RotateCcw :size="17" />
            恢复默认
          </button>
          <button type="button" class="secondary" @click="replayWelcome">
            <Sparkles :size="17" />
            查看欢迎介绍
          </button>
        </div>
      </div>
    </section>

    <section v-if="prefs" class="band">
      <h2 class="section-title">AI 数据保留</h2>
      <p class="muted">对话到期后自动删除；安全事件按独立政策最小化保留。</p>
      <div class="actions">
        <SegmentedControl
          :model-value="String(prefs.aiRetentionDays)"
          :options="retentionSegmentOptions"
          label="AI 数据保留天数"
          @update:model-value="value => retention(Number(value))"
        />
      </div>
    </section>

    <section class="band">
      <h2 class="section-title">通知</h2>
      <p class="muted">选择在哪里收到提醒。每个渠道每天都有条数上限，不会连续打扰。</p>
      <div v-for="n in notifications" :key="n.channel" class="setting-row">
        <div>
          <strong>{{ channelLabel(n.channel) }}</strong>
          <small>{{ channelHint(n.channel) }} · 每日最多 {{ n.maxPerDay }} 条</small>
        </div>
        <input :checked="n.enabled" type="checkbox" :aria-label="`启用${channelLabel(n.channel)}`" @change="toggleNotification(n)">
      </div>
    </section>

    <section class="band">
      <h2 class="section-title">数据导出</h2>
      <p class="muted">导出文件保留 24 小时，下载链接有效 15 分钟。</p>
      <div class="actions">
        <button class="secondary" @click="createExport">
          <Download :size="17" />
          创建导出
        </button>
        <a v-if="exportJob?.status === 'READY'" class="primary button" :href="`/api/v1/privacy/exports/${exportJob.publicId}/content`">下载 ZIP</a>
      </div>
    </section>

    <section class="band danger-zone">
      <h2 class="section-title">注销账户</h2>
      <template v-if="deletion?.status === 'COOLING_OFF'">
        <p>账户处于 7 天冷静期。计划处理时间：{{ new Date(deletion.processAfter!).toLocaleString() }}</p>
        <button class="secondary" @click="cancelDeletion">撤销注销</button>
      </template>
      <template v-else-if="confirmingDeletion">
        <p class="danger-warning"><AlertTriangle :size="16" />请求后会立即暂停通知与好友可见性；7 天冷静期内可以撤销，超过后账户与全部数据将被永久删除，无法恢复。</p>
        <div class="actions">
          <button class="secondary" @click="confirmingDeletion = false">取消</button>
          <button class="danger" @click="confirmRequestDeletion">
            <Trash2 :size="17" />
            确认请求注销
          </button>
        </div>
      </template>
      <template v-else>
        <p class="muted">请求后会立即暂停通知与业务访问，7 天内可以撤销。</p>
        <button class="danger" @click="confirmingDeletion = true">
          <Trash2 :size="17" />
          请求注销
        </button>
      </template>
    </section>
  </section>
</template>

<style scoped>
.appearance-section {
  padding-top: 24px;
  padding-bottom: 26px;
}

.appearance-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px 16px;
}

.option-group {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
}

.visual-group,
.appearance-actions {
  grid-column: 1 / -1;
}

.option-group legend {
  margin-bottom: 8px;
  color: var(--muted);
  font-size: 13px;
  font-weight: 700;
}

.swatches {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(154px, 1fr));
  gap: 10px;
}

.swatches button {
  min-width: 0;
  min-height: 142px;
  display: grid;
  grid-template-rows: 82px auto;
  gap: 10px;
  border: 1px solid var(--border);
  background: var(--surface);
  color: var(--ink);
  padding: 9px;
  text-align: left;
}

.theme-preview {
  width: 100%;
  height: 82px;
  display: grid;
  grid-template-columns: 26px minmax(0, 1fr);
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--preview-primary) 22%, var(--border));
  border-radius: var(--radius);
  background: var(--preview-surface);
}

.preview-sidebar { background: var(--preview-primary); }
.preview-content { display: grid; align-content: center; gap: 7px; padding: 10px; }
.preview-content i { display: block; height: 8px; border-radius: var(--radius); background: color-mix(in srgb, var(--preview-primary) 24%, white); }
.preview-content i:first-child { width: 64%; height: 11px; background: var(--preview-primary); }
.preview-content i:last-child { width: 46%; background: var(--preview-accent); }
.swatch-copy { min-width: 0; display: grid; gap: 3px; }
.swatch-copy strong { overflow-wrap: anywhere; font-size: 13px; }
.swatch-copy small {
  color: var(--muted);
  font-size: 11px;
  line-height: 1.35;
  white-space: normal;
}

.swatches button[aria-pressed='true'] {
  border-color: var(--primary);
  background: color-mix(in srgb, var(--primary-soft) 48%, var(--surface));
  box-shadow: inset 0 -3px 0 var(--primary);
}

.swatches button[aria-pressed='true'] .swatch-copy strong {
  color: var(--primary-strong);
  font-weight: 800;
}

.appearance-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 9px;
}

.current-style {
  flex: 1 1 160px;
  min-height: var(--control);
  display: flex;
  align-items: center;
  gap: 9px;
  color: var(--ink);
}
.current-style > span { width: 30px; height: 30px; flex: 0 0 auto; border: 3px solid var(--surface); border-radius: 50%; }
.current-style div { display: grid; gap: 1px; }
.current-style small { color: var(--muted); font-size: 10px; }
.current-style strong { font-size: 13px; }

.setting-row {
  min-height: 60px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  border-bottom: 1px solid var(--border);
}

.setting-row small {
  display: block;
  color: var(--muted);
  margin-top: 4px;
}

.setting-row input[type='checkbox'] { width: 18px; height: 18px; }

.button {
  display: inline-flex;
  min-height: var(--control);
  align-items: center;
  padding: 0 14px;
  border-radius: var(--radius);
  text-decoration: none;
}

.danger-zone {
  color: var(--danger);
}

.danger-warning { display: flex; align-items: flex-start; gap: 8px; color: var(--danger); }
.danger-warning svg { flex: none; margin-top: 2px; }

@media (prefers-reduced-motion: no-preference) {
  .appearance-section {
    animation: settings-rise var(--motion-medium) var(--ease) both;
  }
}

@keyframes settings-rise {
  from { opacity: 0; transform: translateY(4px); }
  to { opacity: 1; transform: translateY(0); }
}

@media (max-width: 840px) {
  .appearance-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 520px) {
  .appearance-actions {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    align-items: stretch;
  }

  .appearance-actions .secondary {
    width: 100%;
    min-width: 0;
  }

  .swatches {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .swatches button { min-height: 132px; grid-template-rows: 72px auto; }
  .theme-preview { height: 72px; }
  .current-style { grid-column: 1 / -1; }
}
.swatches button { border-radius: var(--radius-panel); }
.theme-preview { border-radius: var(--radius-card); }
.settings-page .danger-zone { border-color: color-mix(in srgb, var(--danger) 35%, var(--border)); }
</style>
