import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useAuthStore } from '../auth/auth.store'
import { api, type ApiError } from '../../shared/api/client'
import { notifyDataChanged, onDataChanged } from '../../shared/data-sync'
import { randomUUID } from '../../shared/uuid'

export type ChecklistTask = {
  publicId: string; taskPublicId: string; taskTitle: string; status: string
  localDate: string; plannedStartAt: string; updatedAt: string
}
type EventResult = { scheduleStatus: string; eventPublicId: string }
export function dateInZone(date: Date, timezone: string) {
  return new Intl.DateTimeFormat('en-CA', { timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(date)
}

export function useChecklist() {
  const auth = useAuthStore()
  const tasks = ref<ChecklistTask[]>([])
  const tab = ref<'today' | 'all'>('today')
  const title = ref('')
  const loading = ref(true)
  const saving = ref(false)
  const pending = ref(new Set<string>())
  const error = ref('')
  const loadError = ref('')
  const notice = ref('')
  const last = ref<{ schedule: string; event: string } | null>(null)
  const undoing = ref(false)
  const now = ref(new Date())
  const timezone = computed(() => auth.user?.timezone || Intl.DateTimeFormat().resolvedOptions().timeZone)
  const today = computed(() => dateInZone(now.value, timezone.value))
  const orderKey = `better-self:checklist-order:${auth.user?.publicId ?? 'guest'}`
  const order = ref<string[]>([])
  try {
    const saved = JSON.parse(localStorage.getItem(orderKey) || '[]')
    if (Array.isArray(saved)) order.value = saved.filter(item => typeof item === 'string')
  } catch { /* Invalid or unavailable storage does not prevent using the list. */ }
  const active = computed(() => tasks.value.filter(task => ['PLANNED', 'IN_PROGRESS'].includes(task.status)
    && (tab.value === 'all' || task.localDate <= today.value)).sort((a, b) => {
      const ai = order.value.indexOf(a.publicId), bi = order.value.indexOf(b.publicId)
      return (ai < 0 ? Number.MAX_SAFE_INTEGER : ai) - (bi < 0 ? Number.MAX_SAFE_INTEGER : bi)
        || a.plannedStartAt.localeCompare(b.plannedStartAt)
    }))
  const completed = computed(() => tasks.value.filter(task => task.status === 'DONE'
    && (tab.value === 'all' || dateInZone(new Date(task.updatedAt || task.plannedStartAt), timezone.value) === today.value)))
  const keys = new Map<string, string>()
  let requestVersion = 0
  let disposed = false
  async function load() {
    const version = ++requestVersion
    now.value = new Date()
    try {
      const result = await api.get<ChecklistTask[]>('/task-schedules')
      if (!disposed && version === requestVersion) { tasks.value = result; loadError.value = '' }
    } catch {
      if (!disposed && version === requestVersion) loadError.value = '清单暂时无法加载，请重试。'
    } finally { if (!disposed && version === requestVersion) loading.value = false }
  }
  function headers(intent: string) {
    if (!keys.has(intent)) keys.set(intent, randomUUID())
    return { 'Idempotency-Key': keys.get(intent)! }
  }
  function changed() { notifyDataChanged(['tasks', 'today', 'insights', 'attributes', 'profile', 'partners']) }
  async function add() {
    if (saving.value || !title.value.trim()) return false
    const text = title.value.trim()
    const date = dateInZone(new Date(), timezone.value)
    const intent = `add:${date}:${text}`
    saving.value = true
    error.value = ''
    try {
      await api.post('/tasks/quick', { title: text, localDate: date }, headers(intent))
      keys.delete(intent)
      title.value = ''
      notice.value = '已添加'
      changed()
      return true
    } catch (caught) {
      error.value = (caught as ApiError)?.code === 'INVALID_TASK_TITLE' ? '请输入 1 到 160 字的任务名称。' : '添加未确认，请重试；你的输入已保留。'
      return false
    } finally { saving.value = false }
  }
  async function act(task: ChecklistTask, eventType: 'COMPLETED' | 'CANCELLED' | 'DEFERRED', extra: Record<string, unknown> = {}) {
    if (pending.value.has(task.publicId) || !['PLANNED', 'IN_PROGRESS'].includes(task.status)) return
    const intent = `${task.publicId}:${eventType}:${JSON.stringify(extra)}`
    pending.value.add(task.publicId)
    error.value = ''
    try {
      const result = await api.post<EventResult>(`/task-schedules/${task.publicId}/events`, { eventType, ...extra }, headers(intent))
      task.status = result.scheduleStatus
      task.updatedAt = new Date().toISOString()
      last.value = { schedule: task.publicId, event: result.eventPublicId }
      notice.value = eventType === 'COMPLETED' ? '已完成' : eventType === 'CANCELLED' ? '已删除本次任务' : '已移到明天'
      keys.delete(intent)
      changed()
    } catch { error.value = '操作未确认，请重试。' }
    finally { pending.value.delete(task.publicId) }
  }
  function tomorrow(task: ChecklistTask) {
    // Resolve 09:00 tomorrow in the account's timezone, including daylight-saving offsets.
    const next = new Date(`${today.value}T12:00:00Z`)
    next.setUTCDate(next.getUTCDate() + 1)
    const nextDate = next.toISOString().slice(0, 10)
    let instant = new Date(`${nextDate}T09:00:00Z`)
    for (let i = 0; i < 3; i++) {
      const local = new Intl.DateTimeFormat('sv-SE', { timeZone: timezone.value, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).format(instant)
      const offset = new Date(local.replace(' ', 'T') + 'Z').getTime() - instant.getTime()
      instant = new Date(new Date(`${nextDate}T09:00:00Z`).getTime() - offset)
    }
    return act(task, 'DEFERRED', { deferredStartAt: instant.toISOString() })
  }
  async function undo() {
    if (!last.value || undoing.value) return
    const target = last.value
    const intent = `undo:${target.event}`
    undoing.value = true
    error.value = ''
    try {
      await api.post(`/task-schedules/${target.schedule}/events/${target.event}/reverse`, undefined, headers(intent))
      if (last.value === target) last.value = null
      notice.value = '已撤销'
      keys.delete(intent)
      changed()
    } catch { error.value = '撤销未确认，请重试。' }
    finally { undoing.value = false }
  }
  async function rename(task: ChecklistTask, value: string) {
    if (pending.value.has(task.publicId) || !value.trim()) return false
    pending.value.add(task.publicId)
    error.value = ''
    try {
      await api.patch(`/tasks/${task.taskPublicId}`, { title: value.trim() })
      tasks.value.filter(item => item.taskPublicId === task.taskPublicId).forEach(item => { item.taskTitle = value.trim() })
      changed()
      return true
    } catch { error.value = '名称未保存，请重试。'; return false }
    finally { pending.value.delete(task.publicId) }
  }
  function move(id: string, target: string) {
    const ids = active.value.map(task => task.publicId)
    const from = ids.indexOf(id), to = ids.indexOf(target)
    if (from < 0 || to < 0 || from === to) return
    ids.splice(from, 1)
    ids.splice(to, 0, id)
    order.value = [...ids, ...order.value.filter(item => !ids.includes(item))]
    try { localStorage.setItem(orderKey, JSON.stringify(order.value)) } catch { /* Session order remains usable. */ }
  }
  const stopSync = onDataChanged(['tasks', 'today'], load)
  let timer: ReturnType<typeof setInterval>
  onMounted(() => {
    void load()
    window.addEventListener('focus', load)
    timer = setInterval(() => { now.value = new Date() }, 60000)
  })
  onBeforeUnmount(() => { disposed = true; stopSync(); window.removeEventListener('focus', load); clearInterval(timer) })
  return { tasks, tab, title, loading, saving, pending, error, loadError, notice, last, undoing, today, active, completed, load, add, act, tomorrow, undo, rename, move }
}
