import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '../auth/auth.store'
import { useTownEventsStore } from './town-events'
const api = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))
beforeEach(() => { setActivePinia(createPinia()); vi.resetAllMocks() })
describe('personal event feed account isolation', () => {
  it('clears old participation immediately and ignores an old in-flight response after account change', async () => {
    const auth = useAuthStore()
    auth.user = { publicId: 'a', displayName: 'A', email: '', timezone: 'Asia/Shanghai', role: 'USER' }
    const events = useTownEventsStore()
    let resolve!: (data: unknown) => void
    api.get.mockImplementationOnce(() => new Promise(r => { resolve = r }))
    const request = events.load()
    events.events = [{ publicId: 'old', hostName: 'A', kind: 'WALK', venue: 'park', startsAt: '', endsAt: null, dimension: null, response: 'GOING' }]
    auth.user = { ...auth.user, publicId: 'b' }
    expect(events.events).toEqual([])
    api.get.mockResolvedValueOnce([])
    await events.load()
    resolve([{ publicId: 'private-old-result' }]); await request
    expect(events.events).toEqual([])
    expect(events.loading).toBe(false)
  })
})
