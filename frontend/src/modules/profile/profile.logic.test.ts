import { flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { avatarInitial, petProgressPercent, useTitles, usePrivacy, type Profile } from './profile.logic'

const api = vi.hoisted(() => ({ get: vi.fn(), patch: vi.fn(), delete: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))
const notifyDataChanged = vi.hoisted(() => vi.fn())
vi.mock('../../shared/data-sync', () => ({ notifyDataChanged, onDataChanged: vi.fn(() => () => undefined) }))

describe('avatarInitial', () => {
  it('upper-cases the first character', () => {
    expect(avatarInitial('yang')).toBe('Y')
  })

  it('falls back when the name is blank', () => {
    expect(avatarInitial('  ')).toBe('我')
  })
})

describe('petProgressPercent', () => {
  it('is 0 without a pet', () => {
    expect(petProgressPercent(undefined)).toBe(0)
  })

  it('computes affection progress toward the next level', () => {
    expect(petProgressPercent({ affection: 10, nextLevelAffection: 20 } as never)).toBe(50)
  })
})

function makeProfile(overrides: Partial<Profile> = {}) {
  return ref<Profile | null>({
    publicId: 'user-1', email: 'a@b.com', displayName: '杨旭光', birthDate: '2000-01-01', age: 26, timezone: 'Asia/Shanghai',
    createdAt: '2026-01-01T00:00:00Z', overallLevel: 1, totalExperience: 0, effectiveActions: 0,
    wallet: { coinBalance: 0, lifetimeCoins: 0 }, selectedPet: {} as never, petCount: 0, soloGrowth: false, equippedTitle: null,
    ...overrides,
  })
}

describe('useTitles', () => {
  beforeEach(() => {
    api.get.mockReset()
    api.patch.mockReset()
    api.delete.mockReset()
    notifyDataChanged.mockReset()
  })

  it('equips a title and mirrors it onto the profile ref', async () => {
    const profile = makeProfile()
    const title = { code: 'T1', name: '早行者', description: '', graphicType: 'LUCIDE' as const, graphicKey: '', frameStyle: 'gold', held: true, equipped: false, acquiredAt: null }
    api.patch.mockResolvedValue([{ ...title, equipped: true }])
    const titles = useTitles(profile)
    await titles.equip(title)
    await flushPromises()
    expect(profile.value?.equippedTitle?.code).toBe('T1')
    expect(titles.feedback.value).toContain('早行者')
    expect(notifyDataChanged).toHaveBeenCalledWith('achievements')
  })

  it('unequips a title and clears it from the profile ref', async () => {
    const profile = makeProfile({ equippedTitle: { code: 'T1', name: '早行者', description: '', graphicType: 'LUCIDE', graphicKey: '', frameStyle: 'gold', held: true, equipped: true, acquiredAt: null } })
    api.delete.mockResolvedValue([])
    const titles = useTitles(profile)
    await titles.unequip()
    expect(profile.value?.equippedTitle).toBeNull()
    expect(titles.feedback.value).toBe('已卸下称号')
  })
})

describe('usePrivacy', () => {
  beforeEach(() => {
    api.patch.mockReset()
    notifyDataChanged.mockReset()
  })

  it('flips solo growth and reports a Chinese status message', async () => {
    const profile = makeProfile({ soloGrowth: false })
    api.patch.mockResolvedValue({ soloGrowth: true })
    const privacy = usePrivacy(profile)
    await privacy.toggleSoloGrowth()
    expect(profile.value?.soloGrowth).toBe(true)
    expect(privacy.feedback.value).toBe('已进入独自升级模式')
    expect(notifyDataChanged).toHaveBeenCalledWith(['profile', 'social'])
  })
})
