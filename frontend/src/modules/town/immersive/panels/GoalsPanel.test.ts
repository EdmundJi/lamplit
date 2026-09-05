import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import GoalsPanel from './GoalsPanel.vue'
import { worldBridgeKey } from '../panel.types'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), patch: vi.fn() }))
vi.mock('../../../../shared/api/client', () => ({ api }))
vi.mock('../../../../app/router', () => ({ router: { push: vi.fn() } }))

const dimensions = [{ publicId: 'dimension-1', code: 'KNOWLEDGE', name: '知识' }]
const goal = { publicId: 'goal-1', dimensionPublicId: 'dimension-1', title: '学会弹吉他', description: '', startDate: '2026-01-01', endDate: '2026-12-31', status: 'ACTIVE' }

function standardGet(path: string) {
  if (path === '/goals') return Promise.resolve([{ ...goal }])
  if (path === '/dimensions') return Promise.resolve(dimensions)
  if (path === '/tasks') return Promise.resolve([])
  return Promise.resolve([])
}

function mountPanel(bridge?: Record<string, unknown>) {
  return mount(GoalsPanel, { global: { provide: bridge ? { [worldBridgeKey]: bridge } : {} } })
}

describe('GoalsPanel', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    api.get.mockReset().mockImplementation(standardGet)
    api.post.mockReset()
    api.patch.mockReset()
  })

  it('mounts without a bridge and shows an empty state', async () => {
    api.get.mockImplementation((path: string) => path === '/goals' ? Promise.resolve([]) : standardGet(path))
    const wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.text()).toContain('还没有进行中的目标')
  })

  it('lists active goals and completes one', async () => {
    api.post.mockResolvedValueOnce({ publicId: 'goal-1', status: 'COMPLETED' })
    const emit = vi.fn()
    const wrapper = mount(GoalsPanel, { global: { provide: { [worldBridgeKey]: { emit, runMode: false, setRunMode: vi.fn() } } } })
    await flushPromises()
    expect(wrapper.text()).toContain('学会弹吉他')

    await wrapper.get('.goal-actions button.secondary').trigger('click')
    await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/goals/goal-1/complete')
    expect(emit).toHaveBeenCalledWith({ type: 'celebrate', publicId: 'goal-1' })
  })

  it('shows a readable message on failure', async () => {
    api.get.mockRejectedValue(new Error('network'))
    const wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.get('.error').text()).toContain('暂时无法加载')
  })

  it('creates a new goal directly inside the panel', async () => {
    api.post.mockImplementation((path: string) => path === '/goals'
      ? Promise.resolve({ ...goal, publicId: 'goal-new', title: '新的目标' })
      : Promise.resolve({}))
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('.footer-primary button.primary').trigger('click')
    await wrapper.get('#panel-goal-title').setValue('新的目标')
    await wrapper.get('.goals-panel-drawer').trigger('submit')
    await flushPromises()

    expect(api.post).toHaveBeenCalledWith('/goals', expect.objectContaining({ title: '新的目标' }))
    expect(wrapper.find('.goals-panel-drawer').exists()).toBe(false)
  })

  it('adds a periodic task under an active goal', async () => {
    api.post.mockImplementation((path: string) => path.startsWith('/task-presets/refresh')
      ? Promise.reject(new Error('unused'))
      : Promise.resolve({ publicId: 'task-1' }))
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('.footer-primary button.secondary').trigger('click')
    await flushPromises()
    await wrapper.get('#panel-task-title').setValue('晚间拉伸')
    await wrapper.get('.goals-panel-drawer').trigger('submit')
    await flushPromises()

    expect(api.post).toHaveBeenCalledWith('/tasks', expect.objectContaining({ goalPublicId: 'goal-1', title: '晚间拉伸' }))
  })

  it('edits an existing goal through PATCH instead of creating a new one', async () => {
    api.patch.mockResolvedValueOnce({ ...goal, title: '更新后的目标' })
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('.goal-actions button[aria-label="编辑目标"]').trigger('click')
    await wrapper.get('#panel-goal-title').setValue('更新后的目标')
    await wrapper.get('.goals-panel-drawer').trigger('submit')
    await flushPromises()

    expect(api.patch).toHaveBeenCalledWith('/goals/goal-1', expect.objectContaining({ title: '更新后的目标' }))
    expect(api.post).not.toHaveBeenCalledWith('/goals', expect.anything())
    expect(wrapper.text()).toContain('目标已更新')
  })
})
