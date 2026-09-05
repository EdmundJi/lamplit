import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ProfilePanel from './ProfilePanel.vue'
import { worldBridgeKey } from '../panel.types'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn() }))
vi.mock('../../../../shared/api/client', () => ({ api }))
vi.mock('../../../../app/router', () => ({ router: { push: vi.fn() } }))

const profile = {
  displayName: '林九',
  overallLevel: 6,
  totalExperience: 980,
  wallet: { coinBalance: 240, lifetimeCoins: 900 },
  petCount: 1,
  soloGrowth: false,
  equippedTitle: { code: 'T1', name: '早行者', description: '', graphicType: 'LUCIDE' as const, graphicKey: '', frameStyle: '', held: true, equipped: true, acquiredAt: null },
}
const titles = [
  profile.equippedTitle,
  { code: 'T2', name: '稳步成长者', description: '稳定积累行动', graphicType: 'LUCIDE' as const, graphicKey: '', frameStyle: 'gold', held: true, equipped: false, acquiredAt: null },
]

function mockData() {
  api.get.mockImplementation((path: string) => (path === '/me/profile' ? Promise.resolve({ ...profile }) : Promise.resolve(titles)))
}

describe('ProfilePanel', () => {
  beforeEach(() => {
    api.get.mockReset()
    api.patch.mockReset()
    api.delete.mockReset()
    mockData()
  })

  it('mounts without a bridge and shows real profile data', async () => {
    const wrapper = mount(ProfilePanel)
    await flushPromises()
    expect(wrapper.text()).toContain('林九')
    expect(wrapper.text()).toContain('早行者')
  })

  it('toggles solo-growth privacy from the overview tab', async () => {
    api.patch.mockResolvedValueOnce({ soloGrowth: true })
    const wrapper = mount(ProfilePanel)
    await flushPromises()
    await wrapper.get('.privacy-toggle').trigger('click')
    await flushPromises()
    expect(api.patch).toHaveBeenCalledWith('/me/privacy', { soloGrowth: true })
    expect(wrapper.text()).toContain('已进入独自升级模式')
  })

  it('equips a held title from the titles tab and notifies through the bridge', async () => {
    api.patch.mockResolvedValueOnce([{ ...titles[1], equipped: true }, { ...titles[0], equipped: false }])
    const emit = vi.fn()
    const wrapper = mount(ProfilePanel, { global: { provide: { [worldBridgeKey]: { emit, runMode: false, setRunMode: vi.fn() } } } })
    await flushPromises()

    await wrapper.findAll('.tab-row button').find(button => button.text() === '称号')!.trigger('click')
    await wrapper.get('.titles-toggle').trigger('click')
    await wrapper.get('[aria-label="佩戴稳步成长者"]').trigger('click')
    await flushPromises()

    expect(api.patch).toHaveBeenCalledWith('/titles/equipped', { code: 'T2' })
    expect(emit).toHaveBeenCalledWith({ type: 'toast', text: '已佩戴「稳步成长者」' })
  })

  it('unequips the currently equipped title', async () => {
    api.delete.mockResolvedValueOnce(titles.map(title => ({ ...title, equipped: false })))
    const wrapper = mount(ProfilePanel)
    await flushPromises()
    await wrapper.findAll('.tab-row button').find(button => button.text() === '称号')!.trigger('click')
    await wrapper.get('[aria-label="卸下称号"]').trigger('click')
    await flushPromises()
    expect(api.delete).toHaveBeenCalledWith('/titles/equipped')
    expect(wrapper.text()).toContain('已卸下称号')
  })

  it('shows a readable message on failure', async () => {
    api.get.mockRejectedValue(new Error('network'))
    const wrapper = mount(ProfilePanel)
    await flushPromises()
    expect(wrapper.get('.error').text()).toContain('暂时无法加载')
  })
})
