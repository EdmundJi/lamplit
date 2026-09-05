import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AttributesPanel from './AttributesPanel.vue'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../../../../shared/api/client', () => ({ api }))
vi.mock('../../../../app/router', () => ({ router: { push: vi.fn() } }))

describe('AttributesPanel', () => {
  beforeEach(() => {
    api.get.mockReset()
  })

  it('mounts without a bridge and renders real attribute data with its source description', async () => {
    api.get.mockResolvedValue({
      totalExperience: 320,
      overallLevel: 4,
      attributes: [{ code: 'KNOWLEDGE', name: '智力', dimensionName: '知识', description: '来自学习类任务的完成记录', experience: 80, level: 2, radarScore: 44, currentLevelExperience: 0, nextLevelExperience: 100, experienceToNextLevel: 20 }],
    })
    const wrapper = mount(AttributesPanel)
    await flushPromises()
    expect(wrapper.text()).toContain('LV.4')
    expect(wrapper.text()).toContain('智力')
    expect(wrapper.text()).toContain('来自学习类任务的完成记录')
    expect(wrapper.get('.attribute-row .progress > span').attributes('style')).toContain('width: 80%')
  })

  it('shows an empty state with no attribute experience', async () => {
    api.get.mockResolvedValue({ totalExperience: 0, overallLevel: 1, attributes: [] })
    const wrapper = mount(AttributesPanel)
    await flushPromises()
    expect(wrapper.text()).toContain('完成任务后')
  })

  it('shows a readable message on failure', async () => {
    api.get.mockRejectedValue(new Error('network'))
    const wrapper = mount(AttributesPanel)
    await flushPromises()
    expect(wrapper.get('.error').text()).toContain('暂时无法加载')
  })
})
