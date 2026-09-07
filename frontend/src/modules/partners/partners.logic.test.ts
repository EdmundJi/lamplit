import { beforeEach, describe, expect, it, vi } from 'vitest'
import { usePartnerProfile } from './partners.logic'
import type { PartnerProfile, Pet } from './partner.types'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
const notify = vi.hoisted(() => vi.fn())
vi.mock('../../shared/api/client', () => ({ api }))
vi.mock('../../shared/data-sync', () => ({ notifyDataChanged: notify }))

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const pet: Pet = { publicId: 'a', name: '豆豆', speciesCode: 'CAT', speciesName: '猫', breed: '橘猫', furColor: '橘', level: 2, affection: 30, nextLevelAffection: 50, selected: true }
const other: Pet = { ...pet, publicId: 'b', name: '花花', selected: false }
const profile: PartnerProfile = { selectedPet: pet, pets: [pet, other], wallet: { coinBalance: 100, lifetimeCoins: 100 }, shopItems: [] }

describe('partner selection', () => {
  beforeEach(() => {
    api.get.mockReset().mockResolvedValue(profile)
    api.post.mockReset()
    notify.mockReset()
  })

  it('catches failures, retains confirmed selection, unlocks and supports retry', async () => {
    const store = usePartnerProfile()
    await store.load()
    api.post.mockRejectedValueOnce(new Error('private server message')).mockResolvedValueOnce({ ...other, selected: true })
    expect(await store.selectPet(other)).toBe(false)
    expect(store.error.value).toBe('伙伴切换失败，请重试')
    expect(store.feedback.value).toBe('')
    expect(store.busy.value).toBe(false)
    expect(store.selectedPet.value?.publicId).toBe('a')
    expect(notify).not.toHaveBeenCalled()
    expect(await store.selectPet(other)).toBe(true)
    expect(store.profile.value?.pets.map(p => p.selected)).toEqual([false, true])
    expect(store.selectedPet.value?.publicId).toBe('b')
    expect(store.error.value).toBe('')
    expect(notify).toHaveBeenCalledWith(['partners', 'profile'])
  })

  it('deduplicates selection, excludes other mutations and suppresses refresh while POST is pending', async () => {
    const store = usePartnerProfile()
    await store.load()
    const pending = deferred<Pet>()
    api.post.mockReturnValueOnce(pending.promise)
    const selection = store.selectPet(other)
    expect(await store.selectPet(other)).toBe(false)
    expect(await store.interact('a')).toBeNull()
    expect(await store.savePet('edit', pet)).toBe(false)
    await store.load(false)
    expect(api.get).toHaveBeenCalledTimes(1)
    expect(api.post).toHaveBeenCalledTimes(1)
    pending.resolve({ ...other, selected: true })
    await selection
    expect(store.busy.value).toBe(false)
  })

  it.each(['resolve', 'reject'] as const)('ignores a pre-selection refresh that later %ss', async result => {
    const store = usePartnerProfile()
    await store.load()
    const pending = deferred<PartnerProfile>()
    api.get.mockReturnValueOnce(pending.promise)
    const staleLoad = store.load()
    api.post.mockResolvedValueOnce({ ...other, selected: true })
    await store.selectPet(other)
    if (result === 'resolve') pending.resolve(profile)
    else pending.reject(new Error('old failure'))
    await staleLoad
    expect(store.selectedPet.value?.publicId).toBe('b')
    expect(store.error.value).toBe('')
    expect(store.loading.value).toBe(false)
  })

  it('only applies the latest profile refresh', async () => {
    const store = usePartnerProfile()
    const older = deferred<PartnerProfile>()
    api.get.mockReturnValueOnce(older.promise).mockResolvedValueOnce({ ...profile, selectedPet: other })
    const first = store.load()
    await store.load(false)
    older.resolve(profile)
    await first
    expect(store.selectedPet.value?.publicId).toBe('b')
    expect(store.loading.value).toBe(false)
  })
})
