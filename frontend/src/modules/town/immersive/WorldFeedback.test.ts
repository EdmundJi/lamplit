import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import WorldFeedback from './WorldFeedback.vue'

describe('WorldFeedback', () => {
  it('shows a toast event as plain text', async () => {
    const wrapper = mount(WorldFeedback)
    wrapper.vm.handle({ type: 'toast', text: '已经保存' })
    await nextTick()
    expect(wrapper.text()).toContain('已经保存')
    expect(wrapper.get('.feedback-item').classes()).toContain('is-toast')
  })

  it('gives celebrate a readable line, using the resident name when given', async () => {
    const wrapper = mount(WorldFeedback)
    wrapper.vm.handle({ type: 'celebrate', publicId: 'me' }, '小明')
    await nextTick()
    expect(wrapper.text()).toContain('小明完成了一件事')
  })

  it('falls back to a generic line when celebrate has no resident name', async () => {
    const wrapper = mount(WorldFeedback)
    wrapper.vm.handle({ type: 'celebrate', publicId: 'me' })
    await nextTick()
    expect(wrapper.text()).toContain('完成了一件事')
  })

  it('gives focus a readable line', async () => {
    const wrapper = mount(WorldFeedback)
    wrapper.vm.handle({ type: 'focus', publicId: 'me' })
    await nextTick()
    expect(wrapper.text()).toContain('镜头带你过去了')
  })

  it('open/close events produce no visible feedback', async () => {
    const wrapper = mount(WorldFeedback)
    wrapper.vm.handle({ type: 'open', panel: 'today' })
    wrapper.vm.handle({ type: 'close' })
    await nextTick()
    expect(wrapper.findAll('.feedback-item')).toHaveLength(0)
  })

  it('result() shows a success or failure line with the matching kind', async () => {
    const wrapper = mount(WorldFeedback)
    wrapper.vm.result(true, '开始奔跑')
    wrapper.vm.result(false, '还没到时候')
    await nextTick()
    const rendered = wrapper.findAll('.feedback-item')
    expect(rendered).toHaveLength(2)
    expect(rendered[0].classes()).toContain('is-result')
    expect(rendered[1].classes()).toContain('is-error')
  })

  it('an empty message is not shown', async () => {
    const wrapper = mount(WorldFeedback)
    wrapper.vm.result(true, '')
    await nextTick()
    expect(wrapper.findAll('.feedback-item')).toHaveLength(0)
  })

  it('clicking an item dismisses it immediately', async () => {
    const wrapper = mount(WorldFeedback)
    wrapper.vm.push('toast', '点一下就没了')
    await nextTick()
    await wrapper.get('.feedback-item').trigger('click')
    expect(wrapper.findAll('.feedback-item')).toHaveLength(0)
  })

  it('auto-dismisses after its timeout', async () => {
    vi.useFakeTimers()
    const wrapper = mount(WorldFeedback)
    wrapper.vm.push('toast', '过一会儿就没了')
    await nextTick()
    expect(wrapper.findAll('.feedback-item')).toHaveLength(1)
    vi.advanceTimersByTime(3300)
    await nextTick()
    expect(wrapper.findAll('.feedback-item')).toHaveLength(0)
    vi.useRealTimers()
  })
})
