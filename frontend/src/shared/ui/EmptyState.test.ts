import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import EmptyState from './EmptyState.vue'

afterEach(() => {
  document.documentElement.removeAttribute('data-theme')
})

describe('EmptyState', () => {
  it('stays the plain .empty class so it keeps working alongside the raw markup pages already use', () => {
    const wrapper = mount(EmptyState, { slots: { default: '正在加载…' } })
    expect(wrapper.classes()).toEqual(['empty'])
    expect(wrapper.text()).toBe('正在加载…')
  })

  it('renders title, description and slotted icon/actions together', () => {
    const wrapper = mount(EmptyState, {
      props: { title: '今天还没有任务', description: '可以从目标页安排一项小行动。' },
      slots: {
        icon: '<svg data-test-icon></svg>',
        actions: '<button type="button">前往目标</button>',
      },
    })
    expect(wrapper.get('h3').text()).toBe('今天还没有任务')
    expect(wrapper.get('p').text()).toBe('可以从目标页安排一项小行动。')
    expect(wrapper.find('[data-test-icon]').exists()).toBe(true)
    expect(wrapper.get('.empty-icon').attributes('aria-hidden')).toBe('true')
    expect(wrapper.get('.empty-actions button').text()).toBe('前往目标')
  })

  it('omits optional pieces that were not given', () => {
    const wrapper = mount(EmptyState, { props: { title: '还没有内容' } })
    expect(wrapper.find('p').exists()).toBe(false)
    expect(wrapper.find('.empty-icon').exists()).toBe(false)
    expect(wrapper.find('.empty-actions').exists()).toBe(false)
  })

  it('renders the same markup regardless of the active theme', () => {
    document.documentElement.dataset.theme = 'light'
    const light = mount(EmptyState, { props: { title: '空', description: '暂无数据' } }).html()
    document.documentElement.dataset.theme = 'dark'
    const dark = mount(EmptyState, { props: { title: '空', description: '暂无数据' } }).html()
    expect(dark).toBe(light)
  })

  it('never hardcodes a color, radius or motion duration — everything comes from tokens.css', () => {
    const source = readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'EmptyState.vue'), 'utf-8')
    const style = source.slice(source.indexOf('<style'))
    expect(style).not.toMatch(/#[0-9a-fA-F]{3,8}\b/)
    expect(style).not.toMatch(/border-radius\s*:\s*\d+px/)
    expect(style.match(/\b\d+m?s\b/g)).toBeNull()
  })
})
