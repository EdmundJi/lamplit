import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import UserLayout from './UserLayout.vue'
import { useWorkspaceModeStore } from '../shared/ui/workspace-mode.store'
import { useAuthStore } from '../modules/auth/auth.store'

vi.mock('../shared/ui/WelcomeGuide.vue', () => ({ default: { name: 'WelcomeGuide', template: '<div />' } }))
vi.mock('../modules/partners/DesktopPet.vue', () => ({ default: { name: 'DesktopPet', template: '<div />' } }))
vi.mock('../shared/ui/GlobalUnreadBar.vue', () => ({ default: { name: 'GlobalUnreadBar', template: '<div />' } }))
// The always-on street strip mounts a real Phaser scene through TownStage; UserLayout's own tests
// only need to know it is present (or absent) at the right times, not exercise the scene itself.
vi.mock('../modules/companion/TownStage.vue', () => ({ default: { name: 'TownStage', template: '<div class="town-stage-stub" />' } }))
vi.mock('../shared/ui/OperationGuideBar.vue', () => ({
  default: { name: 'OperationGuideBar', template: '<div />' },
  // Real resolveGuide is route-keyed too; the mock only needs to distinguish
  // "/town" (no guide, per the real map) from an ordinary route that has one.
  resolveGuide: (path: string) => (path === '/town' ? null : { label: '测试指南', steps: ['一', '二', '三'] }),
}))

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

  it('switches to a quiet checklist shell and can restore the growth shell', async () => {
    const pinia = createPinia()
    const wrapper = await mountLayout(pinia)
    await wrapper.get('.mode-switch').trigger('click')
    expect(useWorkspaceModeStore(pinia).minimal).toBe(true)
    expect(wrapper.find('.sidebar').exists()).toBe(false)
    expect(wrapper.find('.mobile-nav').exists()).toBe(false)
    // The minimal topbar is a narrowed *instance* of .workspace-topbar (same class, reused
    // verbatim for height/padding/border), not a parallel bar - so growth-only content (the
    // breadcrumb, guide and unread-message controls) is what should be absent, not the class.
    expect(wrapper.find('.workspace-context').exists()).toBe(false)
    expect(wrapper.find('[aria-label="使用提示"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="消息中心"]').exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'DesktopPet' }).exists()).toBe(false)
    expect(wrapper.get('.minimal-topbar.workspace-topbar').text()).toContain('我的清单')
    await wrapper.get('.minimal-topbar button').trigger('click')
    expect(useWorkspaceModeStore(pinia).minimal).toBe(false)
    expect(wrapper.find('.sidebar').exists()).toBe(true)
    wrapper.unmount()
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

    const desktopLabels = wrapper.findAll('.sidebar nav a strong').map(link => link.text())
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

  it('keeps /town as a quiet workspace: no welcome dialog, guide bar, desktop pet or unread bar', async () => {
    Object.defineProperty(window, 'localStorage', {
      value: { getItem: vi.fn(() => null), setItem: vi.fn(), removeItem: vi.fn() },
      configurable: true,
    })
    const pinia = createPinia()
    const auth = useAuthStore(pinia)
    auth.user = { publicId: 'u3', email: 'ta@example.test', displayName: '阿禾', timezone: 'Asia/Shanghai', role: 'USER' }

    const wrapper = await mountLayout(pinia, '/town')

    expect(wrapper.findComponent({ name: 'WelcomeGuide' }).exists()).toBe(false)
    expect(wrapper.find('[aria-label="使用提示"]').exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'OperationGuideBar' }).exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'DesktopPet' }).exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'GlobalUnreadBar' }).exists()).toBe(false)
    wrapper.unmount()
  })

  it('keeps the street strip slot and TownStage mounted outside /town, visible and unhidden', async () => {
    Object.defineProperty(window, 'localStorage', {
      value: { getItem: vi.fn(() => null), setItem: vi.fn(), removeItem: vi.fn() },
      configurable: true,
    })
    const pinia = createPinia()
    const auth = useAuthStore(pinia)
    auth.user = { publicId: 'u5', email: 'ta@example.test', displayName: '阿禾', timezone: 'Asia/Shanghai', role: 'USER' }

    const wrapper = await mountLayout(pinia, '/today')

    expect(wrapper.find('#town-strip-slot').exists()).toBe(true)
    expect(wrapper.find('#town-strip-slot').attributes('hidden')).toBeUndefined()
    expect(wrapper.findComponent({ name: 'TownStage' }).exists()).toBe(true)
    wrapper.unmount()
  })

  it('keeps the strip slot in the DOM but hidden while /town itself is open', async () => {
    Object.defineProperty(window, 'localStorage', {
      value: { getItem: vi.fn(() => null), setItem: vi.fn(), removeItem: vi.fn() },
      configurable: true,
    })
    const pinia = createPinia()
    const auth = useAuthStore(pinia)
    auth.user = { publicId: 'u6', email: 'ta@example.test', displayName: '阿禾', timezone: 'Asia/Shanghai', role: 'USER' }

    const wrapper = await mountLayout(pinia, '/town')

    expect(wrapper.find('#town-strip-slot').exists()).toBe(true)
    expect(wrapper.find('#town-strip-slot').attributes('hidden')).toBe('')
    expect(wrapper.findComponent({ name: 'TownStage' }).exists()).toBe(true)
    wrapper.unmount()
  })

  it('docks the same companion into the minimal-checklist rail instead of the street strip (左清单右小镇)', async () => {
    const pinia = createPinia()
    const auth = useAuthStore(pinia)
    auth.user = { publicId: 'u7', email: 'ta@example.test', displayName: '阿禾', timezone: 'Asia/Shanghai', role: 'USER' }
    useWorkspaceModeStore(pinia).setMinimal(true)

    const wrapper = await mountLayout(pinia, '/today')

    expect(wrapper.find('#town-strip-slot').exists()).toBe(false)
    expect(wrapper.find('#town-minimal-slot').exists()).toBe(true)
    expect(wrapper.find('#town-minimal-slot').attributes('hidden')).toBeUndefined()
    expect(wrapper.findComponent({ name: 'TownStage' }).exists()).toBe(true)
    wrapper.unmount()
  })

  it('keeps the minimal rail slot in the DOM but hidden while /town itself is open', async () => {
    const pinia = createPinia()
    const auth = useAuthStore(pinia)
    auth.user = { publicId: 'u8', email: 'ta@example.test', displayName: '阿禾', timezone: 'Asia/Shanghai', role: 'USER' }
    useWorkspaceModeStore(pinia).setMinimal(true)

    const wrapper = await mountLayout(pinia, '/town')

    expect(wrapper.find('#town-minimal-slot').exists()).toBe(true)
    expect(wrapper.find('#town-minimal-slot').attributes('hidden')).toBe('')
    expect(wrapper.findComponent({ name: 'TownStage' }).exists()).toBe(true)
    wrapper.unmount()
  })

  it('shows the welcome dialog, desktop pet and unread bar on an ordinary route, and the guide popover on demand', async () => {
    Object.defineProperty(window, 'localStorage', {
      value: { getItem: vi.fn(() => null), setItem: vi.fn(), removeItem: vi.fn() },
      configurable: true,
    })
    const pinia = createPinia()
    const auth = useAuthStore(pinia)
    auth.user = { publicId: 'u4', email: 'ta@example.test', displayName: '阿禾', timezone: 'Asia/Shanghai', role: 'USER' }

    const wrapper = await mountLayout(pinia, '/today')

    expect(wrapper.findComponent({ name: 'WelcomeGuide' }).exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'DesktopPet' }).exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'GlobalUnreadBar' }).exists()).toBe(true)

    const trigger = wrapper.get('[aria-label="使用提示"]')
    expect(wrapper.findComponent({ name: 'OperationGuideBar' }).exists()).toBe(false)
    expect(trigger.attributes('aria-expanded')).toBe('false')

    await trigger.trigger('click')
    expect(trigger.attributes('aria-expanded')).toBe('true')
    expect(wrapper.findComponent({ name: 'OperationGuideBar' }).exists()).toBe(true)

    await trigger.trigger('click')
    expect(wrapper.findComponent({ name: 'OperationGuideBar' }).exists()).toBe(false)
    wrapper.unmount()
  })

  it('closes the guide popover on Escape', async () => {
    Object.defineProperty(window, 'localStorage', {
      value: { getItem: vi.fn(() => 'dismissed'), setItem: vi.fn(), removeItem: vi.fn() },
      configurable: true,
    })
    const wrapper = await mountLayout(createPinia(), '/today')
    const trigger = wrapper.get('[aria-label="使用提示"]')

    await trigger.trigger('click')
    expect(wrapper.findComponent({ name: 'OperationGuideBar' }).exists()).toBe(true)

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await wrapper.vm.$nextTick()
    expect(wrapper.findComponent({ name: 'OperationGuideBar' }).exists()).toBe(false)
    wrapper.unmount()
  })
})
