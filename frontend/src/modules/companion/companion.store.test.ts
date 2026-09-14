import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { companionApi } from './companion.api'
import { clockText, focusRemaining, useCompanionWorld, useTownWorld } from './companion.store'
import { notifyDataChanged } from '../../shared/data-sync'
import type { Snapshot, World } from './companion.types'
import { useAuthStore } from '../auth/auth.store'
vi.mock('./companion.api', () => ({ companionApi: { load: vi.fn(), join: vi.fn(), advance: vi.fn(), intend: vi.fn(), cancel: vi.fn() } }))
const snapshot = (revision = 1): Snapshot => ({ joined: true, world: { id: 'world-a', revision, intents: [], focus: null, residents: [], memories: [], diary: [] } as unknown as World })
beforeEach(() => { vi.resetAllMocks(); setActivePinia(createPinia()) })
describe('authoritative companion world', () => {
  it('does not replace a newer intent result with a stale in-flight snapshot', async () => {
    let finishRead!: (value: Snapshot) => void
    vi.mocked(companionApi.load).mockImplementation(() => new Promise(resolve => { finishRead = resolve }))
    vi.mocked(companionApi.intend).mockResolvedValue(snapshot(4))
    const town = useCompanionWorld()
    const read = town.load()
    await town.intend('walk')
    finishRead(snapshot(2)); await read
    expect(town.world.value?.revision).toBe(4)
  })
  it('accepts a joined:false snapshot as authoritative and clears a previously loaded world', async () => {
    vi.mocked(companionApi.load).mockResolvedValueOnce(snapshot(2)).mockResolvedValueOnce({ joined: false, world: null })
    const town = useCompanionWorld()
    await town.load()
    expect(town.world.value?.id).toBe('world-a')
    await town.load()
    expect(town.world.value).toBeNull()
    expect(town.loaded.value).toBe(true)
  })
  it('retries an unconfirmed thought with the same id', async () => {
    vi.mocked(companionApi.intend).mockRejectedValueOnce(new Error('断线')).mockResolvedValueOnce(snapshot())
    const town = useCompanionWorld()
    expect(await town.intend('flowers')).toBe(false)
    expect(await town.intend('flowers')).toBe(true)
    const calls = vi.mocked(companionApi.intend).mock.calls
    expect(calls[0][0].id).toBe(calls[1][0].id)
    expect(calls[0][0].priority).toBe('passing')
    expect(town.error.value).toBe('')
  })
  it('only saves a focus intention and never marks the real task completed', async () => {
    vi.mocked(companionApi.intend).mockResolvedValue(snapshot())
    const town = useCompanionWorld()
    await town.intend('focus', { taskId: 'private-task', durationMinutes: 25 })
    expect(companionApi.intend).toHaveBeenCalledWith(expect.objectContaining({ kind: 'focus', priority: 'explicit', taskId: 'private-task', durationMinutes: 25 }))
    expect(focusRemaining('2026-09-08T00:00:00Z', Date.parse('2026-09-08T00:01:00Z'))).toBe(0)
    expect(companionApi.advance).not.toHaveBeenCalled()
  })
  it('cancellation uses the authoritative response, never a locally invented outcome', async () => {
    const before = snapshot(); before.world!.intents = [{ id: 'thought', kind: 'flowers', priority: 'passing', status: 'pending', feedback: '', createdAt: '' }]
    vi.mocked(companionApi.load).mockResolvedValue(before)
    vi.mocked(companionApi.cancel).mockRejectedValueOnce(new Error('暂时离线')).mockResolvedValueOnce(snapshot(2))
    const town = useCompanionWorld(); await town.load()
    await town.cancel('thought')
    expect(town.activeIntents.value).toHaveLength(1)
    await town.cancel('thought')
    expect(town.activeIntents.value).toHaveLength(0)
  })
  it('replaces the initial thought acknowledgement with its later actual outcome', async () => {
    let thoughtId = ''
    vi.mocked(companionApi.intend).mockImplementation(async input => {
      thoughtId = input.id
      const state = snapshot()
      state.world!.intents = [{ id: input.id, kind: 'thought', priority: 'passing', status: 'pending', feedback: '先把这个念头放在心里。', createdAt: '' }]
      return state
    })
    const town = useCompanionWorld()
    await town.intend('thought', { text: '想看看花', priority: 'passing' })
    const result = snapshot(2)
    result.world!.intents = [{ id: thoughtId, kind: 'thought', priority: 'passing', status: 'done', feedback: '已经到花园，看看刚开的花。', createdAt: '' }]
    vi.mocked(companionApi.advance).mockResolvedValue(result)
    await town.load(true)
    expect(town.feedback.value).toBe('已经到花园，看看刚开的花。')
  })

  it('dispose is a no-op: the shared world keeps whatever an in-flight read installs', async () => {
    // useCompanionWorld() now returns the same shared Pinia store every other consumer (the
    // street strip, /town) reads too, so one caller "leaving" must never blank data those other
    // consumers may still be showing.
    let finishRead!: (value: Snapshot) => void
    vi.mocked(companionApi.load).mockImplementation(() => new Promise(resolve => { finishRead = resolve }))
    const town = useCompanionWorld(); const read = town.load(); town.dispose(); finishRead(snapshot()); await read
    expect(town.world.value?.id).toBe('world-a')
  })
  it('formats elapsed deadlines without negative or restarted timers', () => {
    expect(clockText(focusRemaining('2026-09-08T00:25:00Z', Date.parse('2026-09-08T00:00:00Z')))).toBe('25:00')
    expect(clockText(focusRemaining('2026-09-08T00:25:00Z', Date.parse('2026-09-07T23:59:59Z'), '2026-09-08T00:00:00Z'))).toBe('25:00')
    expect(clockText(focusRemaining('2026-09-08T00:25:00Z', Date.parse('2026-09-09T00:00:00Z')))).toBe('00:00')
  })
  it('shares one authoritative world between every consumer sharing the active Pinia instance', async () => {
    vi.mocked(companionApi.load).mockResolvedValue(snapshot(7))
    const streetStrip = useCompanionWorld()
    const townPage = useCompanionWorld()
    await streetStrip.load()
    // A second, independent useCompanionWorld() call sees the same load - not a copy that still
    // needs its own fetch.
    expect(townPage.world.value?.revision).toBe(7)
    expect(townPage.world.value).toBe(streetStrip.world.value)
    // The raw store instance (what the future street-strip lifecycle owner reaches for directly)
    // is that same one world too.
    expect(useTownWorld().world).toBe(streetStrip.world.value)
  })
  it('clears the previous account world and ignores its in-flight response when accounts switch', async () => {
    const auth = useAuthStore()
    auth.user = { publicId: 'account-a', email: 'a@example.test', displayName: '甲', timezone: 'Asia/Shanghai', role: 'USER' }
    const town = useCompanionWorld()
    vi.mocked(companionApi.load).mockResolvedValueOnce(snapshot(3))
    await town.load()
    expect(town.world.value?.id).toBe('world-a')

    let finishOld!: (value: Snapshot) => void
    vi.mocked(companionApi.advance).mockImplementationOnce(() => new Promise(resolve => { finishOld = resolve }))
    const oldRead = town.load(true)
    auth.user = { publicId: 'account-b', email: 'b@example.test', displayName: '乙', timezone: 'Asia/Shanghai', role: 'USER' }
    expect(town.world.value).toBeNull()
    expect(town.loaded.value).toBe(false)
    finishOld(snapshot(9))
    await oldRead
    expect(town.world.value).toBeNull()

    vi.mocked(companionApi.load).mockResolvedValueOnce({ joined: false, world: null })
    await town.load()
    expect(town.loaded.value).toBe(true)
    expect(town.world.value).toBeNull()
  })
})

describe('shared world lifecycle: polling, visibility and data-sync', () => {
  let town!: ReturnType<typeof useTownWorld>
  beforeEach(async () => {
    vi.mocked(companionApi.advance).mockResolvedValue(snapshot())
    vi.mocked(companionApi.load).mockResolvedValue(snapshot())
    vi.useFakeTimers()
    town = useTownWorld()
    await town.load() // seed a world so every poll tick below is a clean, isolated advance() call
    vi.mocked(companionApi.advance).mockClear()
  })
  afterEach(() => {
    // Real listeners were registered on the shared document/window - always tear them down, even
    // if an assertion above already failed, or a later test's timers would fire into this one's
    // (now-stale) closures too.
    town.stop(); town.stop(); town.stop()
    vi.useRealTimers()
  })
  it('polls on an interval only while start()ed, and stop() ends it', async () => {
    town.start({ intervalMs: 1000 })
    await vi.advanceTimersByTimeAsync(1000)
    expect(companionApi.advance).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(2000)
    expect(companionApi.advance).toHaveBeenCalledTimes(3)
    town.stop()
    await vi.advanceTimersByTimeAsync(5000)
    expect(companionApi.advance).toHaveBeenCalledTimes(3)
  })
  it('forgets a world the server says was never joined, so the very next poll shows the join screen instead of erroring forever', async () => {
    // A world this tab remembers can vanish server-side (dev reset, account deleted) - the
    // previous behaviour kept retrying advance() against a world.value that was never coming
    // back, showing "先搬进小街吧" every single poll until a manual page reload.
    vi.mocked(companionApi.advance).mockRejectedValueOnce({ status: 409, code: 'COMPANION_NOT_JOINED', message: '先搬进小街吧' })
    vi.mocked(companionApi.load).mockClear()
    town.start({ intervalMs: 1000 })
    await vi.advanceTimersByTimeAsync(1000)
    expect(town.world).toBeNull()
    expect(town.error).toBe('')
    await vi.advanceTimersByTimeAsync(1000)
    expect(companionApi.load).toHaveBeenCalledTimes(1)
  })
  it('start() is idempotent: several consumers share one timer, and only the last stop() ends it', async () => {
    town.start({ intervalMs: 1000 })
    town.start({ intervalMs: 1000 }) // a second consumer - must not double the timer
    await vi.advanceTimersByTimeAsync(1000)
    expect(companionApi.advance).toHaveBeenCalledTimes(1)
    town.stop() // one consumer leaves - the other keeps it running
    await vi.advanceTimersByTimeAsync(1000)
    expect(companionApi.advance).toHaveBeenCalledTimes(2)
    town.stop() // the last consumer leaves - now it really stops
    await vi.advanceTimersByTimeAsync(3000)
    expect(companionApi.advance).toHaveBeenCalledTimes(2)
  })
  it('skips a scheduled poll while the tab is hidden', async () => {
    town.start({ intervalMs: 1000 })
    vi.stubGlobal('document', { ...document, hidden: true })
    await vi.advanceTimersByTimeAsync(1000)
    expect(companionApi.advance).not.toHaveBeenCalled()
    vi.unstubAllGlobals()
  })
  it('refreshes immediately on visibilitychange once the tab becomes visible again', async () => {
    town.start({ intervalMs: 60000 })
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(0) // flushes the microtask load(true) kicks off
    expect(companionApi.advance).toHaveBeenCalledTimes(1)
  })
  it('setInterval(ms) re-arms an already-running timer at the new cadence', async () => {
    town.start({ intervalMs: 1000 })
    await vi.advanceTimersByTimeAsync(1000)
    expect(companionApi.advance).toHaveBeenCalledTimes(1)
    town.setInterval(5000)
    await vi.advanceTimersByTimeAsync(1000) // the old 1s cadence must no longer fire
    expect(companionApi.advance).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(4000)
    expect(companionApi.advance).toHaveBeenCalledTimes(2)
  })
  it('refreshes right away when today\'s tasks change elsewhere on the page, but only once started', async () => {
    notifyDataChanged('tasks')
    await vi.advanceTimersByTimeAsync(0)
    expect(companionApi.advance).not.toHaveBeenCalled()
    town.start({ intervalMs: 60000 })
    notifyDataChanged(['tasks'])
    await vi.advanceTimersByTimeAsync(0)
    expect(companionApi.advance).toHaveBeenCalledTimes(1)
    notifyDataChanged(['today'])
    await vi.advanceTimersByTimeAsync(0)
    expect(companionApi.advance).toHaveBeenCalledTimes(2)
    notifyDataChanged(['goals']) // unrelated area - no refresh
    await vi.advanceTimersByTimeAsync(0)
    expect(companionApi.advance).toHaveBeenCalledTimes(2)
  })
})
