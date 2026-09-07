import { computed, ref } from 'vue'
import { companionApi } from './companion.api'
import { randomUUID } from '../../shared/uuid'
import type { IntentInput, IntentKind, Snapshot, World } from './companion.types'

/** A view of the saved world. Only the server decides actions, memories and outcomes. */
export function useCompanionWorld() {
  const world = ref<World | null>(null)
  const loaded = ref(false)
  const loading = ref(false)
  const busy = ref(false)
  const error = ref('')
  const feedback = ref('')
  let disposed = false
  let refreshing = false
  let pendingIntent: IntentInput | null = null
  let lastIntentId: string | null = null
  function accept(snapshot: Snapshot) {
    if (disposed) return
    // Concurrent reads must never replace a newer authoritative revision.
    if (!world.value || (snapshot.world && (world.value.id !== snapshot.world.id || snapshot.world.revision >= world.value.revision))) world.value = snapshot.world
    if (lastIntentId) { const latest = world.value?.intents.find(intent => intent.id === lastIntentId); if (latest?.feedback) feedback.value = latest.feedback }
    loaded.value = true
  }
  async function load(advance = false) {
    if (refreshing || disposed) return
    refreshing = true
    if (!loaded.value) loading.value = true
    try { accept(await (advance && world.value ? companionApi.advance() : companionApi.load())); error.value = '' }
    catch (caught) { if (!disposed) error.value = (caught as Error)?.message || '暂时没有连上小街。稍后可以重试。' }
    finally { refreshing = false; loading.value = false }
  }
  async function mutate(action: () => Promise<Snapshot>) {
    if (busy.value || disposed) return false
    busy.value = true
    error.value = ''
    try { accept(await action()); return true }
    catch (caught) { if (!disposed) error.value = (caught as Error)?.message || '这次安排还没有确认，请重试。'; return false }
    finally { busy.value = false }
  }
  async function join(name: string, timezone: string) { return mutate(() => companionApi.join(name, timezone)) }
  async function intend(kind: IntentKind, extra: { taskId?: string; durationMinutes?: number; text?: string; priority?: 'explicit' | 'passing' } = {}) {
    if (busy.value || disposed) return false
    const priority = extra.priority || (kind === 'flowers' ? 'passing' : 'explicit')
    // Retry an unconfirmed request with its original id, including across a transient network error.
    const candidate = { kind, priority, ...extra }
    if (!pendingIntent || JSON.stringify({ ...pendingIntent, id: undefined }) !== JSON.stringify(candidate)) pendingIntent = { ...candidate, id: randomUUID() } as IntentInput
    const input = pendingIntent
    const saved = await mutate(() => companionApi.intend(input))
    if (saved) { pendingIntent = null; lastIntentId = input.id; feedback.value = world.value?.intents.find(item => item.id === input.id)?.feedback || '想法已经捎给小人了。' }
    return saved
  }
  async function cancel(id: string) {
    const saved = await mutate(() => companionApi.cancel(id))
    if (saved) { lastIntentId = null; feedback.value = '已经收回这个安排。' }
    return saved
  }
  const activeIntents = computed(() => world.value?.intents.filter(item => ['pending', 'active'].includes(item.status)) ?? [])
  return { world, loaded, loading, busy, error, feedback, activeIntents, load, join, intend, cancel, dispose: () => { disposed = true } }
}

export function focusRemaining(endsAt: string, now: number, startedAt?: string) {
  const remaining = Math.max(0, Math.ceil((Date.parse(endsAt) - now) / 1000))
  // A response can arrive between UI clock ticks; never display longer than the saved session.
  return startedAt ? Math.min(remaining, Math.max(0, Math.ceil((Date.parse(endsAt) - Date.parse(startedAt)) / 1000))) : remaining
}
export function clockText(seconds: number) { return `${Math.floor(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}` }
