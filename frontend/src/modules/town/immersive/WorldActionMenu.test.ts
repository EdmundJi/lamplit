import { mount } from '@vue/test-utils'
import { Home, Moon } from 'lucide-vue-next'
import { describe, expect, it, vi } from 'vitest'
import WorldActionMenu from './WorldActionMenu.vue'
import type { ResolvedWorldAction } from './world-actions'

function action(over: Partial<ResolvedWorldAction> & Pick<ResolvedWorldAction, 'id' | 'label'>): ResolvedWorldAction {
  return {
    hint: '',
    domain: 'world',
    availability: { ok: true },
    confirmText: null,
    ...over,
  }
}

describe('WorldActionMenu', () => {
  it('renders every action as a focusable button, keyboard reachable via Tab', () => {
    const actions = [
      action({ id: 'a', label: '切到夜晚', icon: Moon }),
      action({ id: 'b', label: '回到我家', icon: Home }),
    ]
    const wrapper = mount(WorldActionMenu, { props: { actions } })
    const buttons = wrapper.findAll('button[role="menuitem"]')
    expect(buttons).toHaveLength(2)
    expect(buttons[0].text()).toContain('切到夜晚')
    expect(buttons[1].text()).toContain('回到我家')
  })

  it('clicking an available action emits run with its id', async () => {
    const actions = [action({ id: 'a', label: '刷新小镇' })]
    const wrapper = mount(WorldActionMenu, { props: { actions } })
    await wrapper.get('button[role="menuitem"]').trigger('click')
    expect(wrapper.emitted('run')).toEqual([['a']])
  })

  it('shows the unavailable reason instead of hiding the action, and does not emit run when clicked', async () => {
    const actions = [action({ id: 'a', label: '进入学院', availability: { ok: false, reason: '还没到时候' } })]
    const wrapper = mount(WorldActionMenu, { props: { actions } })
    const button = wrapper.get('button[role="menuitem"]')
    expect(button.attributes('aria-disabled')).toBe('true')
    expect(wrapper.text()).toContain('还没到时候')
    await button.trigger('click')
    expect(wrapper.emitted('run')).toBeFalsy()
  })

  it('an action with confirmText asks first, and only emits run after a second click on 确认', async () => {
    const actions = [action({ id: 'a', label: '回到小镇', confirmText: '确定要离开学院吗？' })]
    const wrapper = mount(WorldActionMenu, { props: { actions } })
    await wrapper.get('button[role="menuitem"]').trigger('click')
    expect(wrapper.emitted('run')).toBeFalsy()
    expect(wrapper.text()).toContain('确定要离开学院吗？')

    await wrapper.get('.world-action-confirm-buttons button:last-child').trigger('click')
    expect(wrapper.emitted('run')).toEqual([['a']])
  })

  it('取消 dismisses the confirm prompt without emitting run', async () => {
    const actions = [action({ id: 'a', label: '回到小镇', confirmText: '确定吗？' })]
    const wrapper = mount(WorldActionMenu, { props: { actions } })
    await wrapper.get('button[role="menuitem"]').trigger('click')
    expect(wrapper.find('.world-action-confirm').exists()).toBe(true)

    await wrapper.get('.world-action-confirm-buttons button:first-child').trigger('click')
    expect(wrapper.find('.world-action-confirm').exists()).toBe(false)
    expect(wrapper.emitted('run')).toBeFalsy()
  })

  it('Escape emits close and does not bubble to the document', async () => {
    const documentHandler = vi.fn()
    document.addEventListener('keydown', documentHandler)
    const actions = [action({ id: 'a', label: '刷新小镇' })]
    const wrapper = mount(WorldActionMenu, { props: { actions }, attachTo: document.body })
    await wrapper.get('.world-action-menu').trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('close')).toBeTruthy()
    document.removeEventListener('keydown', documentHandler)
    wrapper.unmount()
  })

  it('the header close button also emits close', async () => {
    const wrapper = mount(WorldActionMenu, { props: { actions: [] } })
    await wrapper.get('button[aria-label="关闭动作菜单"]').trigger('click')
    expect(wrapper.emitted('close')).toBeTruthy()
  })
})
