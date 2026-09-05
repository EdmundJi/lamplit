import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SettingsPanel from './SettingsPanel.vue'
import { worldBridgeKey } from '../panel.types'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn() }))
vi.mock('../../../../shared/api/client', () => ({ api }))
vi.mock('../../../../shared/uuid', () => ({ randomUUID: () => 'uuid-1' }))
vi.mock('../../../../app/router', () => ({ router: { push: vi.fn() } }))

function mockData() {
  api.get.mockReset().mockImplementation((path: string) => {
    if (path === '/me/preferences') return Promise.resolve({ aiRetentionDays: 30 })
    if (path === '/me/notifications') return Promise.resolve([{ channel: 'EMAIL', enabled: true, maxPerDay: 2 }])
    return Promise.resolve(null)
  })
}

describe('SettingsPanel', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockData()
    api.post.mockReset()
    api.put.mockReset()
  })

  it('mounts without a bridge and disables the run-mode toggle', async () => {
    const wrapper = mount(SettingsPanel)
    await flushPromises()
    const toggle = wrapper.get('.run-toggle')
    expect((toggle.element as HTMLButtonElement).disabled).toBe(true)
  })

  it('switches the theme through the appearance store', async () => {
    const wrapper = mount(SettingsPanel)
    await flushPromises()
    const darkButton = wrapper.findAll('.theme-row button').find(button => button.text().includes('深色'))!
    await darkButton.trigger('click')
    expect(darkButton.attributes('aria-checked')).toBe('true')
  })

  it('toggles run mode through the bridge when one is provided', async () => {
    const setRunMode = vi.fn()
    const wrapper = mount(SettingsPanel, { global: { provide: { [worldBridgeKey]: { emit: vi.fn(), runMode: false, setRunMode } } } })
    await flushPromises()
    await wrapper.get('.run-toggle').trigger('click')
    expect(setRunMode).toHaveBeenCalledWith(true)
  })

  it('toggles a notification channel from the notifications tab', async () => {
    api.put.mockResolvedValue({ channel: 'EMAIL', enabled: false, maxPerDay: 2 })
    const wrapper = mount(SettingsPanel)
    await flushPromises()
    await wrapper.findAll('.tab-row button').find(button => button.text() === '通知与数据')!.trigger('click')
    await wrapper.get('input[aria-label="启用邮件提醒"]').setValue(false)
    await flushPromises()
    expect(api.put).toHaveBeenCalledWith('/me/notifications/EMAIL', { enabled: false, maxPerDay: 2 })
  })

  it('requires a second confirmation before requesting account deletion', async () => {
    api.post.mockResolvedValue({ status: 'COOLING_OFF', processAfter: '2026-09-12T00:00:00Z' })
    const emit = vi.fn()
    const wrapper = mount(SettingsPanel, { global: { provide: { [worldBridgeKey]: { emit, runMode: false, setRunMode: vi.fn() } } } })
    await flushPromises()
    await wrapper.findAll('.tab-row button').find(button => button.text() === '账户')!.trigger('click')

    await wrapper.get('.account-tab .danger').trigger('click')
    expect(api.post).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('无法恢复')

    await wrapper.get('.account-tab .danger').trigger('click')
    await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/privacy/deletion')
    expect(wrapper.text()).toContain('冷静期')
    expect(emit).toHaveBeenCalledWith({ type: 'toast', text: '已请求注销，7 天内可撤销' })
  })
})
