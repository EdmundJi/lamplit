import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import TownReturnCue from './TownReturnCue.vue'
describe('return cue', () => {
  it('prioritizes actual unread mail and opens it on explicit choice', async () => {
    const wrapper = mount(TownReturnCue, { props: { planned: 3, done: 1, unread: 2 } })
    expect(wrapper.text()).toContain('2 条未读')
    await wrapper.find('button').trigger('click')
    expect(wrapper.emitted('mail')).toHaveLength(1)
    expect(wrapper.find('aside').exists()).toBe(false)
  })
  it('uses remaining real tasks and lets a quiet visit dismiss the cue', async () => {
    const wrapper = mount(TownReturnCue, { props: { planned: 3, done: 1, unread: 0 } })
    expect(wrapper.text()).toContain('还有 2 件计划')
    await wrapper.findAll('button')[1]!.trigger('click')
    expect(wrapper.emitted('today')).toBeUndefined()
    expect(wrapper.find('aside').exists()).toBe(false)
  })
  it('does not claim past activity when there is no task history', () => {
    const wrapper = mount(TownReturnCue, { props: { planned: 0, done: 0, unread: 0, night: true } })
    expect(wrapper.text()).toContain('夜深了')
    expect(wrapper.text()).not.toContain('昨天')
  })
})
