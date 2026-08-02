import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import UserLayout from './UserLayout.vue'
import { useAuthStore } from '../modules/auth/auth.store'

vi.mock('../shared/ui/WelcomeGuide.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../modules/partners/DesktopPet.vue', () => ({ default: { template: '<div />' } }))

const routerLinkStub = {
  props: ['to'],
  template: '<a><slot /></a>',
}

describe('UserLayout', () => {
  beforeEach(() => {
    Object.defineProperty(window, 'localStorage', {
      value: { getItem: vi.fn(() => 'dismissed'), setItem: vi.fn(), removeItem: vi.fn() },
      configurable: true,
    })
  })

  it('uses the first Chinese character from the display name as the brand mark', () => {
    const pinia = createPinia()
    const auth = useAuthStore(pinia)
    auth.user = { publicId: 'u1', email: 'yang@example.test', displayName: '杨旭光', timezone: 'Asia/Shanghai', role: 'USER' }

    const wrapper = mount(UserLayout, {
      global: { plugins: [pinia], stubs: { RouterLink: routerLinkStub, RouterView: true } },
    })

    expect(wrapper.get('.brand-mark').text()).toBe('杨')
  })

  it('uses the first two visible Latin characters from the display name as the brand mark', () => {
    const pinia = createPinia()
    const auth = useAuthStore(pinia)
    auth.user = { publicId: 'u2', email: 'james@example.test', displayName: 'James', timezone: 'Asia/Shanghai', role: 'USER' }

    const wrapper = mount(UserLayout, {
      global: { plugins: [pinia], stubs: { RouterLink: routerLinkStub, RouterView: true } },
    })

    expect(wrapper.get('.brand-mark').text()).toBe('Ja')
  })
})
