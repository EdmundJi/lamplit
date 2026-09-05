import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SettingsView from './SettingsView.vue'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))

describe('privacy settings', () => {
  const storage = new Map<string, string>()

  beforeEach(() => {
    storage.clear()
    Object.defineProperty(window, 'localStorage', {
      value: {
        getItem: vi.fn((key: string) => storage.get(key) ?? null),
        setItem: vi.fn((key: string, value: string) => storage.set(key, value)),
        removeItem: vi.fn((key: string) => storage.delete(key)),
        clear: vi.fn(() => storage.clear()),
      },
      configurable: true,
    })
    document.documentElement.removeAttribute('data-theme')
    document.documentElement.removeAttribute('data-accent')
    document.documentElement.removeAttribute('data-density')
    document.documentElement.removeAttribute('data-motion')
    document.documentElement.removeAttribute('data-radius')
    api.get.mockReset().mockImplementation((path: string) => {
      if (path === '/me/preferences') return Promise.resolve({ aiRetentionDays: 30 })
      if (path === '/me/notifications') return Promise.resolve([{ channel: 'EMAIL', enabled: true, maxPerDay: 2 }])
      return Promise.resolve({ status: 'COOLING_OFF', processAfter: '2026-08-07T00:00:00Z' })
    })
    api.post.mockReset().mockResolvedValue({ status: 'CANCELLED' })
    api.put.mockReset().mockResolvedValue({ channel: 'EMAIL', enabled: false, maxPerDay: 2 })
  })

  it('shows and cancels cooling-off deletion and persists notification toggles', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: SettingsView }, { path: '/auth', component: { template: '<div />' } }] })
    await router.push('/'); await router.isReady()
    const wrapper = mount(SettingsView, { global: { plugins: [createPinia(), router] } })
    await flushPromises()
    expect(wrapper.text()).toContain('7 天冷静期')
    await wrapper.get('input[aria-label="启用邮件提醒"]').setValue(false); await flushPromises()
    expect(api.put).toHaveBeenCalledWith('/me/notifications/EMAIL', { enabled: false, maxPerDay: 2 })
    await wrapper.findAll('button').find(button => button.text().includes('撤销注销'))!.trigger('click')
    expect(api.post).toHaveBeenCalledWith('/privacy/deletion/cancel')
  })

  it('applies appearance preferences locally', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: SettingsView }, { path: '/auth', component: { template: '<div />' } }] })
    await router.push('/')
    await router.isReady()
    const wrapper = mount(SettingsView, { global: { plugins: [createPinia(), router] } })
    await flushPromises()

    await wrapper.findAll('button').find(button => button.text().includes('深色'))!.trigger('click')
    await wrapper.get('button[aria-label="青瓷微风"]').trigger('click')
    await wrapper.findAll('button').find(button => button.text().includes('紧凑'))!.trigger('click')
    await wrapper.findAll('button').find(button => button.text().includes('关闭'))!.trigger('click')

    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(document.documentElement.dataset.accent).toBe('ocean')
    expect(document.documentElement.dataset.density).toBe('compact')
    expect(document.documentElement.dataset.motion).toBe('off')
    expect(storage.get('better-self:appearance')).toContain('"accent":"ocean"')
  })
})
