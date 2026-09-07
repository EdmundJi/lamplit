import { flushPromises, mount } from '@vue/test-utils'
import { CalendarCheck } from 'lucide-vue-next'
import { describe, expect, it } from 'vitest'
import { h } from 'vue'
import WorldPanel from './WorldPanel.vue'
import type { WorldPanelDef } from './panel.types'

const stubBody = { render: () => h('p', { class: 'stub-body' }, '今天的内容') }
const def: WorldPanelDef = {
  key: 'today',
  title: '今天',
  subtitle: '先看看今天要做的这一件事',
  icon: CalendarCheck,
  // `__esModule` makes Vue's defineAsyncComponent unwrap `.default`, same as a real dynamic import.
  loader: () => Promise.resolve({ __esModule: true, default: stubBody }),
  size: 'compact',
  fullPage: '/today',
}

describe('WorldPanel', () => {
  it('removes the input-blocking dialog role while retained as an inactive window', async () => {
    const wrapper = mount(WorldPanel, { props: { def, x: 0, y: 0, z: 1, active: false } })
    await flushPromises()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    await wrapper.setProps({ active: true })
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    wrapper.unmount()
  })
  it('shows the title/subtitle and lazy-loads the panel body', async () => {
    const wrapper = mount(WorldPanel, { props: { def, x: 10, y: 20, z: 1 } })
    expect(wrapper.text()).toContain('今天')
    expect(wrapper.text()).toContain('先看看今天要做的这一件事')
    await flushPromises()
    expect(wrapper.find('.stub-body').text()).toBe('今天的内容')
  })

  it('positions itself with the given x/y/z', () => {
    const wrapper = mount(WorldPanel, { props: { def, x: 40, y: 55, z: 3 } })
    const style = wrapper.get('.world-panel').attributes('style') ?? ''
    expect(style).toContain('left: 40px')
    expect(style).toContain('top: 55px')
    expect(style).toContain('z-index: 3')
  })

  it('emits minimize and close from the header buttons', async () => {
    const wrapper = mount(WorldPanel, { props: { def, x: 0, y: 0, z: 1 } })
    await wrapper.get('button[aria-label="最小化"]').trigger('click')
    await wrapper.get('button[aria-label="关闭"]').trigger('click')
    expect(wrapper.emitted('minimize')).toHaveLength(1)
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('emits focus when the window is interacted with', async () => {
    const wrapper = mount(WorldPanel, { props: { def, x: 0, y: 0, z: 1 } })
    await wrapper.get('.world-panel').trigger('pointerdown')
    expect(wrapper.emitted('focus')).toBeTruthy()
  })

  it('dragging the header emits move with the pointer delta, clamped to the viewport', async () => {
    const wrapper = mount(WorldPanel, { props: { def, x: 100, y: 100, z: 1 } }, )
    const head = wrapper.get('.world-panel-head').element
    head.dispatchEvent(new MouseEvent('pointerdown', { clientX: 200, clientY: 200 }))
    window.dispatchEvent(new MouseEvent('pointermove', { clientX: 230, clientY: 260 }))
    window.dispatchEvent(new MouseEvent('pointerup'))
    await flushPromises()
    const moves = wrapper.emitted('move') as [number, number][]
    expect(moves.length).toBeGreaterThan(0)
    // moved +30 on x and +60 on y from the drag start.
    expect(moves.at(-1)).toEqual([130, 160])
  })
})
