import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useTownNpcStore } from './town-npc.store'
import type { TownNpcsResponse, TownNpcView } from './town-npc.types'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))

const notFound = { status: 404, code: 'NOT_FOUND', message: 'not found' }

function npc(overrides: Partial<TownNpcView> = {}): TownNpcView {
  return {
    code: 'KE_YUN',
    displayName: '柯云',
    layer: 2,
    sprite: 'c07',
    dimension: 'KNOWLEDGE',
    interests: { KNOWLEDGE: 0.4, HEALTH: 0.1, CAREER: 0.2, RELATIONSHIP: 0.2, WELLBEING: 0.1 },
    affinityToPlayer: 0.42,
    mood: { valence: 0.3, energy: 0.6 },
    schedule: [{ startHour: 9, endHour: 12, place: 'academy', activity: 'reading' }],
    talkingPoints: [{ factId: '01J000000000000000000000', text: '听说小吉最近老往健身房跑', hops: 2, salience: 0.61 }],
    ...overrides,
  }
}

describe('town-npc store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    api.get.mockReset()
  })

  it('starts with an empty roster and zeroed budget before any load', () => {
    const store = useTownNpcStore()
    expect(store.npcs).toEqual([])
    expect(store.budget).toEqual({ limit: 0, used: 0 })
    expect(store.loading).toBe(false)
    expect(store.error).toBe('')
  })

  it('loads GET /town/npcs and exposes the roster + budget', async () => {
    const response: TownNpcsResponse = { npcs: [npc()], initiativeBudget: { limit: 3, used: 1 } }
    api.get.mockResolvedValue(response)
    const store = useTownNpcStore()
    await store.load()
    expect(api.get).toHaveBeenCalledWith('/town/npcs')
    expect(store.npcs).toEqual([npc()])
    expect(store.budget).toEqual({ limit: 3, used: 1 })
    expect(store.loading).toBe(false)
    expect(store.error).toBe('')
  })

  it('keeps the town playable but explains a missing NPC service', async () => {
    api.get.mockRejectedValue(notFound)
    const store = useTownNpcStore()
    await store.load()
    expect(store.npcs).toEqual([])
    expect(store.budget).toEqual({ limit: 0, used: 0 })
    expect(store.error).toContain('居民服务还未连接')
  })

  it('surfaces a real error message for a non-404 failure', async () => {
    api.get.mockRejectedValue({ status: 500, code: 'BOOM', message: 'server exploded' })
    const store = useTownNpcStore()
    await store.load()
    expect(store.error).toBe('server exploded')
  })

  it('byCode finds a loaded NPC and returns null for an unknown code', async () => {
    api.get.mockResolvedValue({ npcs: [npc(), npc({ code: 'LU_XIA', displayName: '陆夏' })], initiativeBudget: { limit: 3, used: 0 } })
    const store = useTownNpcStore()
    await store.load()
    expect(store.byCode('LU_XIA')?.displayName).toBe('陆夏')
    expect(store.byCode('NOBODY')).toBeNull()
  })

  it('talkingPoints fetches the per-NPC endpoint and returns its points', async () => {
    api.get.mockResolvedValue({ code: 'KE_YUN', displayName: '柯云', points: [{ factId: 'f1', text: '听说...', hops: 1, salience: 0.5 }] })
    const store = useTownNpcStore()
    const points = await store.talkingPoints('KE_YUN')
    expect(api.get).toHaveBeenCalledWith('/town/npc/KE_YUN/talking-points')
    expect(points).toEqual([{ factId: 'f1', text: '听说...', hops: 1, salience: 0.5 }])
  })

  it('talkingPoints tolerates a 404 (unknown NPC, or endpoint not deployed) by returning an empty list', async () => {
    api.get.mockRejectedValue(notFound)
    const store = useTownNpcStore()
    const points = await store.talkingPoints('GHOST')
    expect(points).toEqual([])
  })

  it('talkingPoints rethrows a non-404 error rather than silently swallowing it', async () => {
    api.get.mockRejectedValue({ status: 500, code: 'BOOM', message: 'server exploded' })
    const store = useTownNpcStore()
    await expect(store.talkingPoints('KE_YUN')).rejects.toEqual({ status: 500, code: 'BOOM', message: 'server exploded' })
  })
})

describe('consumeInitiative（护栏 A）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    api.post.mockReset()
  })

  it('把服务端返回的权威额度写回 store', async () => {
    api.post.mockResolvedValue({ limit: 3, used: 2 })
    const store = useTownNpcStore()
    const budget = await store.consumeInitiative()

    expect(api.post).toHaveBeenCalledWith('/town/initiative/consume', {})
    expect(budget).toEqual({ limit: 3, used: 2 })
    expect(store.budget).toEqual({ limit: 3, used: 2 })
  })

  it('端点还没上线时保持本地额度不变——宁可放宽，也不要让 NPC 一句话都不说', async () => {
    api.post.mockRejectedValue(notFound)
    const store = useTownNpcStore()
    store.budget = { limit: 3, used: 1 }

    await expect(store.consumeInitiative()).resolves.toEqual({ limit: 3, used: 1 })
  })
})
