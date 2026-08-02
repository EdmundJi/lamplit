import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useDesktopPetStore } from './desktop-pet.store'

describe('desktop pet store', () => {
  const saved = new Map<string, string>()

  beforeEach(() => {
    saved.clear()
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      value: {
        getItem: vi.fn((key: string) => saved.get(key) ?? null),
        setItem: vi.fn((key: string, value: string) => saved.set(key, value)),
        removeItem: vi.fn((key: string) => saved.delete(key)),
      },
    })
  })

  it('keeps only the latest configured pet', () => {
    const store = useDesktopPetStore(createPinia())
    store.hydrate()

    store.setPet('pet-one')
    store.setPet('pet-two')

    expect(store.petPublicId).toBe('pet-two')
    expect(saved.get('better-self:desktop-pet')).toBe('pet-two')
  })

  it('restores and clears the persisted desktop pet', () => {
    saved.set('better-self:desktop-pet', 'pet-saved')
    const store = useDesktopPetStore(createPinia())

    store.hydrate()
    expect(store.petPublicId).toBe('pet-saved')

    store.clear()
    expect(store.petPublicId).toBeNull()
    expect(saved.has('better-self:desktop-pet')).toBe(false)
  })
})
