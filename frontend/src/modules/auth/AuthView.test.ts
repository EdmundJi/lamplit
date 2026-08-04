import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AuthView from './AuthView.vue'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))

describe('authentication', () => {
  beforeEach(() => { api.get.mockReset(); api.post.mockReset() })

  it('allows an underage birth date and requires three independent consents', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/auth', component: AuthView }, { path: '/onboarding', component: { template: '<div />' } }] })
    await router.push('/auth'); await router.isReady()
    const wrapper = mount(AuthView, { global: { plugins: [createPinia(), router] } })
    await wrapper.get('[role=tablist] button:nth-child(2)').trigger('click')
    await wrapper.get('#email').setValue('learner@example.test')
    await wrapper.get('#password').setValue('Correct-Horse-Battery-2026!')
    await wrapper.get('#name').setValue('学习者')
    await wrapper.get('#birth').setValue('2015-01-01')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.get('[role=alert]').text()).toContain('三项同意')
    const consents = wrapper.findAll('fieldset input[type=checkbox]')
    expect(consents).toHaveLength(3)
    for (const consent of consents) await consent.setValue(true)
    api.post.mockResolvedValue({}); api.get.mockResolvedValue({ publicId: 'u', role: 'USER' })
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/auth/register', expect.objectContaining({
      birthDate: '2015-01-01',
      consents: { terms: '2026-07', privacy: '2026-07', ai: '2026-07' },
    }))
  })

  it('requires MFA before an administrator enters the console', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/auth', component: AuthView },
        { path: '/today', component: { template: '<div />' } },
        { path: '/admin', component: { template: '<div />' } },
      ],
    })
    await router.push('/auth'); await router.isReady()
    api.post
      .mockResolvedValueOnce({ status: 'MFA_PENDING' })
      .mockResolvedValueOnce({ publicId: 'admin', email: 'admin@example.test', displayName: 'Admin', timezone: 'Asia/Shanghai', role: 'ADMIN' })
    const wrapper = mount(AuthView, { global: { plugins: [createPinia(), router] } })

    await wrapper.get('#email').setValue('admin@example.test')
    await wrapper.get('#password').setValue('Correct-Horse-Battery-2026!')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('管理员验证')

    await wrapper.get('#mfa-code').setValue('123456')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.post).toHaveBeenLastCalledWith('/auth/mfa/verify', { email: 'admin@example.test', password: 'Correct-Horse-Battery-2026!', code: '123456' })
    expect(router.currentRoute.value.path).toBe('/admin')
  })
})
