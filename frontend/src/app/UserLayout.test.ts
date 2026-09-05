import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import UserLayout from './UserLayout.vue'
import { useAuthStore } from '../modules/auth/auth.store'

vi.mock('../shared/ui/WelcomeGuide.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../modules/partners/DesktopPet.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../shared/ui/GlobalUnreadBar.vue', () => ({ default: { template: '<div />' } }))

const routerLinkStub = {
  props: ['to'],
  template: '<a><slot /></a>',
}

async function mountLayout(pinia = createPinia(), path = '/today') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  })
  await router.push(path)
  await router.isReady()
  return mount(UserLayout, {
    global: { plugins: [pinia, router], stubs: { RouterLink: routerLinkStub, RouterView: true } },
  })
}

describe('UserLayout', () => {
  beforeEach(() => {
    Object.defineProperty(window, 'localStorage', {
      value: { getItem: vi.fn(() => 'dismissed'), setItem: vi.fn(), removeItem: vi.fn() },
      configurable: true,
    })
  })

  it('uses the first Chinese character from the display name as the brand mark', async () => {
    const pinia = createPinia()
    const auth = useAuthStore(pinia)
    auth.user = { publicId: 'u1', email: 'yang@example.test', displayName: '杨旭光', timezone: 'Asia/Shanghai', role: 'USER' }

    const wrapper = await mountLayout(pinia)

    expect(wrapper.get('.account-avatar').text()).toBe('杨')
    expect(wrapper.find('.brand-mark svg').exists()).toBe(true)
  })

  it('uses the first two visible Latin characters from the display name as the brand mark', async () => {
    const pinia = createPinia()
    const auth = useAuthStore(pinia)
    auth.user = { publicId: 'u2', email: 'james@example.test', displayName: 'James', timezone: 'Asia/Shanghai', role: 'USER' }

    const wrapper = await mountLayout(pinia)

    expect(wrapper.get('.account-avatar').text()).toBe('Ja')
  })

  it('keeps every desktop destination available from the mobile navigation', async () => {
    const wrapper = await mountLayout()

    const desktopLabels = wrapper.findAll('.sidebar nav a').map(link => link.text())
    expect(desktopLabels).toEqual(['今日', '目标', 'AI 助手', '属性', '洞察', '小镇', '伙伴', '好友'])
    expect(wrapper.findAll('.sidebar-account a')).toHaveLength(2)
    expect(wrapper.findAll('.mobile-nav a').map(link => link.text())).toEqual(['今日', '目标', '小镇'])

    await wrapper.get('.mobile-nav button').trigger('click')

    expect(wrapper.findAll('.mobile-more-links a').map(link => link.text())).toEqual(['AI 助手', '属性', '洞察', '伙伴', '好友', '个人', '设置'])
  })

  it('marks more as active for a destination inside the overflow navigation', async () => {
    const wrapper = await mountLayout(createPinia(), '/friends')

    expect(wrapper.get('.mobile-nav button').classes()).toContain('is-active')
  })
})
