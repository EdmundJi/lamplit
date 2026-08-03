import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AttributesView from './AttributesView.vue'

const api = vi.hoisted(() => ({ get: vi.fn() }))
const chart = vi.hoisted(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))
vi.mock('echarts/core', () => ({ init: vi.fn(() => chart), use: vi.fn() }))
vi.mock('echarts/charts', () => ({ RadarChart: {} }))
vi.mock('echarts/components', () => ({ LegendComponent: {}, TooltipComponent: {} }))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))

describe('Growth attributes', () => {
  beforeEach(() => {
    chart.setOption.mockClear()
    api.get.mockReset().mockResolvedValue({
      totalExperience: 75,
      overallLevel: 1,
      attributes: [
        { code: 'KNOWLEDGE', name: '智力', dimensionName: '知识', description: '学习与理解', experience: 40, level: 1, radarScore: 44, currentLevelExperience: 0, nextLevelExperience: 100, experienceToNextLevel: 60 },
        { code: 'HEALTH', name: '体力', dimensionName: '健康', description: '身体照顾', experience: 35, level: 1, radarScore: 42, currentLevelExperience: 0, nextLevelExperience: 100, experienceToNextLevel: 65 },
      ],
    })
  })

  it('renders attribute values and initializes the radar chart', async () => {
    const wrapper = mount(AttributesView)
    await flushPromises()
    expect(api.get).toHaveBeenCalledWith('/insights/attributes')
    expect(wrapper.findAll('.attribute-card')).toHaveLength(2)
    expect(wrapper.text()).toContain('智力')
    expect(wrapper.text()).toContain('75')
    expect(chart.setOption).toHaveBeenCalledOnce()
  })
})
