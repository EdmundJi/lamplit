import { computed, ref } from 'vue'
import { defineStore, storeToRefs } from 'pinia'
import { companionApi } from './companion.api'
import { randomUUID } from '../../shared/uuid'
import { onDataChanged } from '../../shared/data-sync'
import type { IntentInput, IntentKind, Snapshot, World, WorldEvent } from './companion.types'

/**
 * The town's one authoritative world, shared by every consumer instead of each fetching and
 * polling its own copy - the always-on street strip and the full /town page both read and act on
 * the same Pinia singleton. Only the server decides actions, memories and outcomes.
 */
export const useTownWorld = defineStore('town-world', () => {
  const world = ref<World | null>(null)
  const loaded = ref(false)
  const loading = ref(false)
  const busy = ref(false)
  const error = ref('')
  const feedback = ref('')
  let refreshing = false
  let pendingIntent: IntentInput | null = null
  let lastIntentId: string | null = null

  // Causal feedback for the street strip: fire once per world event the instant it first appears,
  // never replaying history on the initial load or after switching to a different world entirely.
  const eventHandlers = new Set<(event: WorldEvent) => void>()
  let seenEventIds: Set<string> | null = null
  function emitNewEvents(nextWorld: World | null) {
    const events = nextWorld?.events ?? []
    const sameWorld = seenEventIds !== null && world.value?.id === nextWorld?.id
    if (!sameWorld) { seenEventIds = new Set(events.map(event => event.id)); return }
    const fresh = events.filter(event => !seenEventIds!.has(event.id))
    seenEventIds = new Set(events.map(event => event.id))
    for (const event of fresh) for (const handler of eventHandlers) handler(event)
  }
  function onWorldEvent(handler: (event: WorldEvent) => void) {
    eventHandlers.add(handler)
    return () => eventHandlers.delete(handler)
  }

  function accept(snapshot: Snapshot) {
    // Concurrent reads must never replace a newer authoritative revision.
    if (!world.value || (snapshot.world && (world.value.id !== snapshot.world.id || snapshot.world.revision >= world.value.revision))) {
      emitNewEvents(snapshot.world)
      world.value = snapshot.world
    }
    if (lastIntentId) { const latest = world.value?.intents.find(intent => intent.id === lastIntentId); if (latest?.feedback) feedback.value = latest.feedback }
    loaded.value = true
  }
  async function load(advance = false) {
    if (refreshing) return
    refreshing = true
    if (!loaded.value) loading.value = true
    try { accept(await (advance && world.value ? companionApi.advance() : companionApi.load())); error.value = '' }
    catch (caught) { error.value = (caught as Error)?.message || '暂时没有连上小街。稍后可以重试。' }
    finally { refreshing = false; loading.value = false }
  }
  async function mutate(action: () => Promise<Snapshot>) {
    if (busy.value) return false
    busy.value = true
    error.value = ''
    try { accept(await action()); return true }
    catch (caught) { error.value = (caught as Error)?.message || '这次安排还没有确认，请重试。'; return false }
    finally { busy.value = false }
  }
  async function join(name: string, timezone: string) { return mutate(() => companionApi.join(name, timezone)) }
  async function intend(kind: IntentKind, extra: { taskId?: string; durationMinutes?: number; text?: string; priority?: 'explicit' | 'passing' } = {}) {
    if (busy.value) return false
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

  // Lifecycle: polling, paused while the tab is hidden and refreshed the instant it becomes
  // visible again, plus a same-page nudge whenever today's tasks change. Reference-counted so
  // several consumers (the street strip, /town itself) sharing this one store only ever run one
  // timer between them - the last one to stop() actually tears it down.
  let timer: ReturnType<typeof setInterval> | null = null
  let intervalMs = 15000
  let consumers = 0
  let stopDataSync: (() => void) | null = null
  function poll() { if (typeof document === 'undefined' || !document.hidden) void load(true) }
  function onVisibilityChange() { if (!document.hidden) void load(true) }
  function armTimer() {
    if (timer) clearInterval(timer)
    timer = setInterval(poll, intervalMs)
  }
  function start(options: { intervalMs?: number } = {}) {
    consumers++
    if (options.intervalMs) intervalMs = options.intervalMs
    if (timer) return // already running - start() is idempotent, consumers only tracks stop()
    armTimer()
    if (typeof document !== 'undefined') document.addEventListener('visibilitychange', onVisibilityChange)
    stopDataSync = onDataChanged(['today', 'tasks'], () => { if (timer) void load(true) })
  }
  function setInterval_(ms: number) {
    intervalMs = ms
    if (timer) armTimer()
  }
  function stop() {
    if (consumers > 0) consumers--
    if (consumers > 0) return
    if (timer) { clearInterval(timer); timer = null }
    if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', onVisibilityChange)
    stopDataSync?.(); stopDataSync = null
  }

  return {
    world, loaded, loading, busy, error, feedback, activeIntents, load, join, intend, cancel,
    start, stop, setInterval: setInterval_, onWorldEvent,
    // The world is shared and outlives any single consumer; a caller unmounting no longer tears
    // down data the street strip (or another view) may still be showing.
    dispose: () => undefined,
  }
})

/**
 * Back-compat view for existing callers (CompanionView, and this file's own tests): reads the
 * same shared Pinia singleton as useTownWorld(), not an independent instance, so every consumer
 * sees one authoritative world. storeToRefs() keeps the state genuinely destructurable - plain
 * property access on a Pinia setup store auto-unwraps refs (`store.world` is already a
 * `World | null`, not a `Ref`), which would silently break every existing `const { world } =
 * useCompanionWorld()` call site expecting a real ref to read `.value` off.
 */
export function useCompanionWorld() {
  const store = useTownWorld()
  const refs = storeToRefs(store)
  return { ...refs, load: store.load, join: store.join, intend: store.intend, cancel: store.cancel, start: store.start, stop: store.stop, setInterval: store.setInterval, dispose: store.dispose }
}

export function focusRemaining(endsAt: string, now: number, startedAt?: string) {
  const remaining = Math.max(0, Math.ceil((Date.parse(endsAt) - now) / 1000))
  // A response can arrive between UI clock ticks; never display longer than the saved session.
  return startedAt ? Math.min(remaining, Math.max(0, Math.ceil((Date.parse(endsAt) - Date.parse(startedAt)) / 1000))) : remaining
}
export function clockText(seconds: number) { return `${Math.floor(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}` }
