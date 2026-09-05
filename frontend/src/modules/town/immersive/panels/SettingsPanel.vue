<script setup lang="ts">
import { inject, onMounted, ref } from 'vue'
import { AlertTriangle, Download, Footprints, Moon, Sun, Monitor, Trash2 } from 'lucide-vue-next'
import {
  accentOptions, densityOptions, motionOptions, radiusOptions, themeOptions, useAppearanceStore,
} from '../../../../shared/ui/appearance.store'
import { channelHint, channelLabel, retentionOptions, useSettingsData } from '../../../settings/settings.logic'
import { worldBridgeKey } from '../panel.types'
import { openFullPage } from './shared'

const FULL_PAGE = '/settings'
type Tab = 'appearance' | 'notifications' | 'account'

const themeIcons = { light: Sun, dark: Moon, system: Monitor } as const

const bridge = inject(worldBridgeKey, undefined)
const appearance = useAppearanceStore()
appearance.hydrate()

const { prefs, notifications, deletion, exportJob, error, load, setRetention, toggleNotification, createExport, requestDeletion, cancelDeletion } = useSettingsData()

const tab = ref<Tab>('appearance')
const confirmingDeletion = ref(false)

onMounted(() => load())

function toggleRunMode() {
  if (!bridge) return
  bridge.setRunMode(!bridge.runMode)
}

async function retention(days: number) {
  await setRetention(days)
}

async function exportData() {
  await createExport()
  bridge?.emit({ type: 'toast', text: '已创建数据导出，24 小时内在完整设置页下载' })
}

async function confirmRequestDeletion() {
  await requestDeletion()
  confirmingDeletion.value = false
  bridge?.emit({ type: 'toast', text: '已请求注销，7 天内可撤销' })
}

async function cancel() {
  await cancelDeletion()
  bridge?.emit({ type: 'toast', text: '已撤销注销请求' })
}
</script>

<template>
  <section class="world-panel settings-panel">
    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <nav class="tab-row" role="tablist" aria-label="设置面板分区">
      <button type="button" role="tab" :aria-selected="tab === 'appearance'" :class="{ active: tab === 'appearance' }" @click="tab = 'appearance'">外观</button>
      <button type="button" role="tab" :aria-selected="tab === 'notifications'" :class="{ active: tab === 'notifications' }" @click="tab = 'notifications'">通知与数据</button>
      <button type="button" role="tab" :aria-selected="tab === 'account'" :class="{ active: tab === 'account' }" @click="tab = 'account'">账户</button>
    </nav>

    <section v-if="tab === 'appearance'" class="tab-panel">
      <div class="setting-block">
        <p class="eyebrow">主题</p>
        <div class="theme-row" role="radiogroup" aria-label="主题">
          <button
            v-for="option in themeOptions"
            :key="option.value"
            type="button"
            role="radio"
            :aria-checked="appearance.theme === option.value"
            :class="{ active: appearance.theme === option.value }"
            @click="appearance.setTheme(option.value)"
          >
            <component :is="themeIcons[option.value]" :size="14" />
            {{ option.label }}
          </button>
        </div>
      </div>

      <div class="setting-block">
        <p class="eyebrow">视觉风格</p>
        <div class="accent-row" role="radiogroup" aria-label="主题色">
          <button
            v-for="option in accentOptions"
            :key="option.value"
            type="button"
            role="radio"
            class="swatch"
            :aria-checked="appearance.accent === option.value"
            :class="{ active: appearance.accent === option.value }"
            :aria-label="option.label"
            :title="option.label"
            :style="{ background: option.swatch }"
            @click="appearance.setAccent(option.value)"
          />
        </div>
      </div>

      <div class="setting-block">
        <p class="eyebrow">界面密度</p>
        <div class="theme-row" role="radiogroup" aria-label="界面密度">
          <button v-for="option in densityOptions" :key="option.value" type="button" role="radio" :aria-checked="appearance.density === option.value" :class="{ active: appearance.density === option.value }" @click="appearance.setDensity(option.value)">{{ option.label }}</button>
        </div>
      </div>

      <div class="setting-block">
        <p class="eyebrow">动画强度</p>
        <div class="theme-row" role="radiogroup" aria-label="动画强度">
          <button v-for="option in motionOptions" :key="option.value" type="button" role="radio" :aria-checked="appearance.motion === option.value" :class="{ active: appearance.motion === option.value }" @click="appearance.setMotion(option.value)">{{ option.label }}</button>
        </div>
      </div>

      <div class="setting-block">
        <p class="eyebrow">圆角风格</p>
        <div class="theme-row" role="radiogroup" aria-label="圆角风格">
          <button v-for="option in radiusOptions" :key="option.value" type="button" role="radio" :aria-checked="appearance.radius === option.value" :class="{ active: appearance.radius === option.value }" @click="appearance.setRadius(option.value)">{{ option.label }}</button>
        </div>
      </div>

      <div class="setting-block">
        <p class="eyebrow">小镇里的节奏</p>
        <button
          class="secondary run-toggle"
          type="button"
          role="switch"
          :aria-checked="bridge?.runMode ?? false"
          :disabled="!bridge"
          @click="toggleRunMode"
        >
          <Footprints :size="14" />
          {{ bridge ? (bridge.runMode ? '跑步模式已开启' : '跑步模式已关闭') : '跑步模式（需要在小镇中打开）' }}
        </button>
      </div>
    </section>

    <section v-else-if="tab === 'notifications'" class="tab-panel">
      <div v-if="prefs" class="setting-block">
        <p class="eyebrow">AI 数据保留</p>
        <div class="theme-row">
          <button v-for="d in retentionOptions" :key="d" type="button" :class="{ active: prefs.aiRetentionDays === d }" @click="retention(d)">{{ d }} 天</button>
        </div>
      </div>

      <div class="setting-block">
        <p class="eyebrow">通知渠道</p>
        <div v-for="n in notifications" :key="n.channel" class="notification-row">
          <div>
            <strong>{{ channelLabel(n.channel) }}</strong>
            <small>{{ channelHint(n.channel) }} · 每日最多 {{ n.maxPerDay }} 条</small>
          </div>
          <input :checked="n.enabled" type="checkbox" :aria-label="`启用${channelLabel(n.channel)}`" @change="toggleNotification(n)">
        </div>
      </div>

      <div class="setting-block">
        <p class="eyebrow">数据导出</p>
        <button class="secondary" type="button" @click="exportData"><Download :size="14" />创建导出</button>
        <a v-if="exportJob?.status === 'READY'" class="primary button export-link" :href="`/api/v1/privacy/exports/${exportJob.publicId}/content`">下载 ZIP</a>
      </div>
    </section>

    <section v-else class="tab-panel account-tab">
      <template v-if="deletion?.status === 'COOLING_OFF'">
        <p>账户处于 7 天冷静期，计划处理时间：{{ new Date(deletion.processAfter!).toLocaleDateString() }}</p>
        <button class="secondary" type="button" @click="cancel">撤销注销</button>
      </template>
      <template v-else-if="confirmingDeletion">
        <p class="danger-warning"><AlertTriangle :size="14" />请求后会立即暂停通知与好友可见性；7 天内可撤销，超过后账户与数据将被永久删除，无法恢复。</p>
        <div class="account-actions">
          <button class="secondary" type="button" @click="confirmingDeletion = false">取消</button>
          <button class="danger" type="button" @click="confirmRequestDeletion"><Trash2 :size="14" />确认请求注销</button>
        </div>
      </template>
      <template v-else>
        <p class="muted">请求后会立即暂停通知与业务访问，7 天内可以撤销。</p>
        <button class="danger" type="button" @click="confirmingDeletion = true"><Trash2 :size="14" />请求注销</button>
      </template>
    </section>

    <footer class="panel-footer">
      <button class="secondary" type="button" @click="openFullPage(bridge, FULL_PAGE)">打开完整页面</button>
    </footer>
  </section>
</template>

<style scoped>
.world-panel { display: grid; gap: 12px; width: 100%; color: var(--ink); }
.tab-row { display: grid; grid-auto-flow: column; gap: 3px; padding: 3px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface-muted); }
.tab-row button { min-width: 0; min-height: 28px; border: 0; border-radius: calc(var(--radius) - 2px); background: transparent; color: var(--muted); font-size: 11px; padding: 0 4px; }
.tab-row button.active { background: var(--surface); color: var(--primary-strong); font-weight: 700; box-shadow: var(--shadow-soft); }
.tab-panel { display: grid; gap: 14px; max-height: 380px; overflow-y: auto; }
.setting-block { display: grid; gap: 8px; }
.eyebrow { margin: 0; color: var(--muted); font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: .02em; }
.theme-row { display: flex; flex-wrap: wrap; gap: 6px; }
.theme-row button { min-height: 32px; padding: 0 8px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); color: var(--muted); font-size: 12px; display: inline-flex; align-items: center; justify-content: center; gap: 5px; }
.theme-row button.active { border-color: var(--primary); color: var(--primary-strong); background: var(--primary-soft); }
.accent-row { display: flex; gap: 8px; }
.swatch { width: 26px; height: 26px; min-height: 0; border-radius: 50%; border: 2px solid transparent; padding: 0; }
.swatch.active { border-color: var(--ink); }
.run-toggle { width: 100%; justify-content: flex-start; font-size: 12px; }
.muted { color: var(--muted); font-size: 12px; margin: 0; }
.notification-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 8px 0; border-bottom: 1px solid var(--border); font-size: 12px; }
.notification-row small { display: block; color: var(--muted); margin-top: 2px; }
.notification-row input { width: 16px; height: 16px; }
.export-link { margin-left: 8px; min-height: 32px; padding: 0 12px; font-size: 12px; }
.account-tab { font-size: 12px; color: var(--danger); }
.danger-warning { display: flex; align-items: flex-start; gap: 7px; color: var(--danger); }
.danger-warning svg { flex: none; margin-top: 2px; }
.account-actions { display: flex; gap: 8px; }
.panel-footer { display: flex; justify-content: flex-end; }
</style>
