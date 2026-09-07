import { beforeEach, describe, expect, it, vi } from 'vitest'
import { companionApi } from './companion.api'
import { clockText, focusRemaining, useCompanionWorld } from './companion.store'
import type { Snapshot, World } from './companion.types'
vi.mock('./companion.api', () => ({ companionApi: { load: vi.fn(), join: vi.fn(), advance: vi.fn(), intend: vi.fn(), cancel: vi.fn() } }))
const snapshot = (revision = 1): Snapshot => ({ joined: true, world: { id: 'world-a', revision, intents: [], focus: null, residents: [], memories: [], diary: [] } as unknown as World })
beforeEach(() => vi.resetAllMocks())
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

  it('does not install a response after the page is disposed', async () => {
    let finishRead!: (value: Snapshot) => void
    vi.mocked(companionApi.load).mockImplementation(() => new Promise(resolve => { finishRead = resolve }))
    const town = useCompanionWorld(); const read = town.load(); town.dispose(); finishRead(snapshot()); await read
    expect(town.world.value).toBeNull()
  })
  it('formats elapsed deadlines without negative or restarted timers', () => {
    expect(clockText(focusRemaining('2026-09-08T00:25:00Z', Date.parse('2026-09-08T00:00:00Z')))).toBe('25:00')
    expect(clockText(focusRemaining('2026-09-08T00:25:00Z', Date.parse('2026-09-07T23:59:59Z'), '2026-09-08T00:00:00Z'))).toBe('25:00')
    expect(clockText(focusRemaining('2026-09-08T00:25:00Z', Date.parse('2026-09-09T00:00:00Z')))).toBe('00:00')
  })
})
