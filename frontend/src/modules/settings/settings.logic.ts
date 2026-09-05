import { ref } from 'vue'
import { api } from '../../shared/api/client'
import { randomUUID } from '../../shared/uuid'

export type Preferences = { aiRetentionDays: number }
export type NotificationChannel = { channel: string; enabled: boolean; maxPerDay: number }
export type ExportJob = { publicId: string; status: string }
export type Deletion = { status: string; processAfter?: string } | null

export const retentionOptions = [7, 30, 90]

/** 通知渠道的中文名与说明，后端只传渠道代码。 */
export const channelLabels: Record<string, { label: string; hint: string }> = {
  EMAIL: { label: '邮件提醒', hint: '发到你的注册邮箱' },
  IN_APP: { label: '站内消息', hint: '进入应用时看到' },
  WEB_PUSH: { label: '浏览器推送', hint: '需要浏览器授权' },
}

export function channelLabel(channel: string) {
  return channelLabels[channel]?.label ?? channel
}

export function channelHint(channel: string) {
  return channelLabels[channel]?.hint ?? '提醒渠道'
}

/** AI 保留天数、通知渠道、数据导出与账户注销，整页与面板共用同一份状态与写操作。 */
export function useSettingsData() {
  const prefs = ref<Preferences | null>(null)
  const notifications = ref<NotificationChannel[]>([])
  const deletion = ref<Deletion>(null)
  const exportJob = ref<ExportJob | null>(null)
  const loading = ref(true)
  const error = ref('')

  async function load() {
    loading.value = true
    error.value = ''
    try {
      const [loadedPrefs, loadedNotifications, loadedDeletion] = await Promise.all([
        api.get<Preferences>('/me/preferences'),
        api.get<NotificationChannel[]>('/me/notifications'),
        api.get<Deletion>('/privacy/deletion').catch(() => null),
      ])
      prefs.value = loadedPrefs
      notifications.value = loadedNotifications
      deletion.value = loadedDeletion
    } catch {
      error.value = '设置暂时无法加载'
    } finally {
      loading.value = false
    }
  }

  async function setRetention(days: number) {
    await api.put('/privacy/ai/retention', { days })
    if (prefs.value) prefs.value.aiRetentionDays = days
  }

  async function toggleNotification(item: NotificationChannel) {
    const previous = item.enabled
    item.enabled = !previous
    try {
      Object.assign(item, await api.put<NotificationChannel>(`/me/notifications/${item.channel}`, { enabled: item.enabled, maxPerDay: item.maxPerDay }))
    } catch {
      item.enabled = previous
      error.value = '通知设置未保存，请重试'
    }
  }

  async function createExport() {
    exportJob.value = await api.post<ExportJob>('/privacy/exports', undefined, { 'Idempotency-Key': randomUUID() })
  }

  async function requestDeletion() {
    deletion.value = await api.post<Deletion>('/privacy/deletion')
  }

  async function cancelDeletion() {
    deletion.value = await api.post<Deletion>('/privacy/deletion/cancel')
  }

  return { prefs, notifications, deletion, exportJob, loading, error, load, setRetention, toggleNotification, createExport, requestDeletion, cancelDeletion }
}
