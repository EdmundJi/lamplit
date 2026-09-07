import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia } from 'pinia'
import MementosPanel from './MementosPanel.vue'
import { notifyDataChanged } from '../../../../shared/data-sync'
const api = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('../../../../shared/api/client', () => ({ api }))
const earned = { code: 'first', name: '第一步', body: '完成了第一次真实行动。', triggerText: '完成一次行动', iconKey: 'Leaf', tone: 'green', earned: true, earnedAt: '2026-09-05T17:00:00Z' }
const wrappers: ReturnType<typeof mount>[] = []
function render() { const w = mount(MementosPanel, { global: { plugins: [createPinia()] } }); wrappers.push(w); return w }
beforeEach(() => { api.get.mockReset() })
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()) })
describe('MementosPanel', () => {
  it('shows only actual earned records and their date, and reloads the same wall after reopening', async () => {
    api.get.mockResolvedValue([earned, { ...earned, code: 'future', name: '尚未获得', earned: false }])
    const w = render(); await flushPromises()
    expect(w.text()).toContain('2026年9月6日')
    expect(w.text()).toContain(earned.body)
    expect(w.text()).not.toContain('尚未获得')
    expect(w.findAll('.keepsake')).toHaveLength(1)
    w.unmount()
    const reopened = render(); await flushPromises()
    expect(reopened.text()).toContain(earned.body)
    expect(api.get).toHaveBeenCalledWith('/achievements')
  })
  it('keeps the empty wall welcoming and invents no records', async () => {
    api.get.mockResolvedValue([])
    const w = render(); await flushPromises()
    expect(w.text()).toContain('墙上还留着空位')
    expect(w.findAll('.keepsake')).toHaveLength(0)
  })
  it('allows retry after loading fails', async () => {
    api.get.mockRejectedValueOnce(new Error('offline')).mockResolvedValue([earned])
    const w = render(); await flushPromises()
    expect(w.get('[role="alert"]').text()).toContain('暂时没能')
    await w.get('button').trigger('click'); await flushPromises()
    expect(w.find('[role="alert"]').exists()).toBe(false)
    expect(w.text()).toContain(earned.body)
  })
  it('does not let an old response overwrite a later growth refresh', async () => {
    let finish!: (x: unknown) => void
    api.get.mockImplementationOnce(() => new Promise(resolve => { finish = resolve })).mockResolvedValue([earned])
    const w = render()
    notifyDataChanged('achievements'); await flushPromises()
    finish([]); await flushPromises()
    expect(w.text()).toContain(earned.body)
  })
})
