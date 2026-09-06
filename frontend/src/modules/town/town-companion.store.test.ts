import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../shared/api/client'
import { useTownCompanionStore } from './town-companion.store'

vi.mock('../../shared/api/client', () => ({ api: { get: vi.fn(), post: vi.fn() } }))

const pet = { publicId: 'pet-a', speciesCode: 'DOG', speciesName: '狗', name: '豆包', breed: '柴犬', furColor: '棕色', level: 1, affection: 1, nextLevelAffection: 10, selected: true }
const profile = (selectedPet = pet) => ({ wallet: { coinBalance: 0, lifetimeCoins: 0 }, pets: [selectedPet], selectedPet, shopItems: [] })

describe('town companion store', () => {
  const saved = new Map<string, string>()
  beforeEach(() => {
    saved.clear()
    vi.clearAllMocks()
    Object.defineProperty(window, 'localStorage', { configurable: true, value: {
      getItem: vi.fn((key: string) => saved.get(key) ?? null),
      setItem: vi.fn((key: string, value: string) => saved.set(key, value)),
      removeItem: vi.fn((key: string) => saved.delete(key)),
    } })
  })

  it('restores an outing only for the same user and selected pet', async () => {
    saved.set('better-self:town-companion:user-a:pet-a', 'following')
    vi.mocked(api.get).mockResolvedValue(profile())
    const store = useTownCompanionStore(createPinia())
    await store.load('user-a')
    expect(store.mode).toBe('following')

    vi.mocked(api.get).mockRejectedValueOnce(new Error('offline'))
    await store.load('user-b')
    expect(store.pet).toBeNull()
    expect(store.mode).toBe('home')
    expect(store.error).toContain('无法加载')
  })

  it('brings the former pet home when the selected pet changes', async () => {
    vi.mocked(api.get).mockResolvedValueOnce(profile())
    const store = useTownCompanionStore(createPinia())
    await store.load('user-a')
    expect(store.startWalk()).toBe(true)
    const next = { ...pet, publicId: 'pet-b', name: '团子' }
    vi.mocked(api.get).mockResolvedValueOnce(profile(next))
    await store.load('user-a')
    expect(saved.has('better-self:town-companion:user-a:pet-a')).toBe(false)
    expect(store.pet?.publicId).toBe('pet-b')
    expect(store.mode).toBe('home')
  })

  it('does not restore a stale outing for a newly selected pet', async () => {
    const next = { ...pet, publicId: 'pet-b', name: '团子' }
    saved.set('better-self:town-companion:user-a:pet-b', 'roaming')
    vi.mocked(api.get).mockResolvedValueOnce(profile()).mockResolvedValueOnce(profile(next))
    const store = useTownCompanionStore(createPinia())
    await store.load('user-a')
    await store.load('user-a')
    expect(store.pet?.publicId).toBe('pet-b')
    expect(store.mode).toBe('home')
    expect(saved.has('better-self:town-companion:user-a:pet-b')).toBe(false)
  })

  it('keeps an in-memory walk across a same-pet refresh when storage is blocked', async () => {
    vi.mocked(api.get).mockResolvedValue(profile())
    const store = useTownCompanionStore(createPinia())
    await store.load('user-a')
    store.startWalk()
    Object.defineProperty(window, 'localStorage', { configurable: true, get: () => { throw new Error('blocked') } })
    await store.load('user-a')
    expect(store.mode).toBe('following')
  })

  it('clears only in-memory account data and ignores a late profile response', async () => {
    let resolveLoad!: (value: ReturnType<typeof profile>) => void
    vi.mocked(api.get).mockImplementationOnce(() => new Promise(resolve => { resolveLoad = resolve }))
    const store = useTownCompanionStore(createPinia())
    const loading = store.load('user-a')
    store.clearUser()
    resolveLoad(profile())
    await loading
    expect(store.userId).toBeNull()
    expect(store.pet).toBeNull()
    expect(store.mode).toBe('home')
  })

  it('keeps only the newest async response', async () => {
    let resolveFirst!: (value: ReturnType<typeof profile>) => void
    vi.mocked(api.get).mockImplementationOnce(() => new Promise(resolve => { resolveFirst = resolve }))
    vi.mocked(api.get).mockResolvedValueOnce(profile({ ...pet, publicId: 'pet-b' }))
    const store = useTownCompanionStore(createPinia())
    const first = store.load('user-a')
    await store.load('user-b')
    resolveFirst(profile())
    await first
    expect(store.userId).toBe('user-b')
    expect(store.pet?.publicId).toBe('pet-b')
  })

  it('only rewards a deliberate stroke and exposes failures', async () => {
    vi.mocked(api.get).mockResolvedValue(profile())
    vi.mocked(api.post).mockResolvedValue({ pet: { ...pet, affection: 3 } })
    const store = useTownCompanionStore(createPinia())
    await store.load('user-a')
    expect(store.startWalk()).toBe(true)
    expect(vi.mocked(api.post)).not.toHaveBeenCalled()
    await expect(store.stroke()).resolves.toBe(true)
    expect(api.post).toHaveBeenCalledWith('/partners/pets/pet-a/interact')
  })

  it('ignores a stroke response that arrives after the user changes', async () => {
    let resolveStroke!: (value: { pet: typeof pet }) => void
    vi.mocked(api.get).mockResolvedValueOnce(profile()).mockResolvedValueOnce(profile({ ...pet, publicId: 'pet-b' }))
    vi.mocked(api.post).mockImplementationOnce(() => new Promise(resolve => { resolveStroke = resolve }))
    const store = useTownCompanionStore(createPinia())
    await store.load('user-a')
    const stroking = store.stroke()
    await store.load('user-b')
    resolveStroke({ pet: { ...pet, affection: 99 } })
    await expect(stroking).resolves.toBe(false)
    expect(store.userId).toBe('user-b')
    expect(store.pet?.publicId).toBe('pet-b')
    expect(store.pet?.affection).toBe(1)
    expect(store.busy).toBe(false)
    expect(store.error).toBe('')
  })
})
