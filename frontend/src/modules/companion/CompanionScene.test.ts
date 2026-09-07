import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
vi.mock('phaser', () => ({ default: { AUTO: 0, Scale: { NONE: 0, NO_CENTER: 0 }, Game: class {
  canvas = document.createElement('canvas'); scale = { resize: vi.fn() }; events = { once: vi.fn() }; destroy() {}
} } }))
vi.mock('./companion-scene', () => ({ CompanionStreetScene: class {
  constructor(_snapshot: unknown, _select: unknown, _project: unknown, labels: (value: unknown[]) => void) {
    labels([{ id: 'ahe', name: '阿禾', role: '店主', action: '正在聊天', emoji: '🎨', conversationId: 'chat-1', dialogue: [{ name: '阿禾', text: '今天把书留在窗边，等阿川带来他刚画好的海报。' }, { name: '阿川', text: '我刚画完，准备拿过去。' }], bodyX: 113, bodyY: 170, bodyHeight: 60, x: 130, y: 150, selected: false, speechOffset: 8, speech: '今天把书留在窗边。' }])
  }
  resizeViewport() {} sync() {}
} }))
import CompanionScene from './CompanionScene.vue'
beforeEach(() => { vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} }) })
describe('quiet scene pins', () => {
  it('keeps names and dialogue hidden while exposing meaningful accessible labels', async () => {
    const wrapper = mount(CompanionScene, { props: { residents: [] } }); await flushPromises()
    expect(wrapper.get('.resident-status').text()).toBe('🎨')
    expect(wrapper.get('.resident-person').attributes('aria-label')).toContain('阿禾，店主，正在聊天')
    expect(wrapper.find('.resident-card').exists()).toBe(false)
    expect(wrapper.find('.resident-speech').exists()).toBe(false)
    await wrapper.setProps({ textBubbles: true })
    expect(wrapper.get('.resident-speech').text()).toBe('今天把书留在窗边。')
    wrapper.unmount()
  })
  it('offers the same short profile on hover and keyboard focus, then opens the full story on click', async () => {
    const wrapper = mount(CompanionScene, { props: { residents: [] } }); await flushPromises()
    const pin = wrapper.get('.resident-person')
    await pin.trigger('mouseenter')
    expect(wrapper.get('[role=tooltip]').text()).toBe('阿禾店主正在聊天')
    expect(wrapper.get('[role=tooltip]').text()).not.toContain('窗边')
    await pin.trigger('mouseleave'); expect(wrapper.find('[role=tooltip]').exists()).toBe(false)
    await pin.trigger('focus'); expect(wrapper.find('[role=tooltip]').exists()).toBe(true)
    await pin.trigger('blur'); expect(wrapper.find('[role=tooltip]').exists()).toBe(false)
    await pin.trigger('focus'); await pin.trigger('click')
    expect(wrapper.emitted('select-resident')?.[0]).toEqual(['ahe'])
    expect(wrapper.find('[role=tooltip]').exists()).toBe(false)
    wrapper.unmount()
  })
  it('keeps topic dialogue and character profile separate and dismisses both for a fixed panel', async () => {
    const wrapper = mount(CompanionScene, { props: { residents: [] } }); await flushPromises()
    expect(wrapper.get('.resident-person').text()).toBe('')
    const topic = wrapper.get('.resident-topic')
    await topic.trigger('mouseenter')
    expect(wrapper.get('[role=tooltip]').text()).toContain('今天把书留在窗边，等阿川带来他刚画好的海报。')
    expect(wrapper.get('[role=tooltip]').text()).toContain('我刚画完，准备拿过去。')
    await wrapper.setProps({ suppressHover: true }); expect(wrapper.find('[role=tooltip]').exists()).toBe(false)
    await wrapper.setProps({ suppressHover: false }); expect(wrapper.find('[role=tooltip]').exists()).toBe(false)
    await topic.trigger('focus'); await topic.trigger('click')
    expect(wrapper.emitted('select-conversation')?.[0]).toEqual(['chat-1'])
    expect(wrapper.find('[role=tooltip]').exists()).toBe(false)
    wrapper.unmount()
  })

})
