import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import Button from './Button.vue'

afterEach(() => {
  document.documentElement.removeAttribute('data-theme')
})

describe('Button', () => {
  it('defaults to the primary variant and forwards click events', async () => {
    const wrapper = mount(Button, { slots: { default: '保存' } })
    expect(wrapper.classes()).toContain('primary')
    expect(wrapper.classes()).toContain('button')
    expect(wrapper.text()).toBe('保存')
    await wrapper.trigger('click')
    expect(wrapper.emitted('click')).toHaveLength(1)
  })

  it.each(['secondary', 'danger'] as const)('maps variant="%s" onto the matching global.css class', variant => {
    const wrapper = mount(Button, { props: { variant }, slots: { default: '操作' } })
    expect(wrapper.classes()).toContain(variant)
  })

  it('renders icon variant as an accessible icon-button', () => {
    const wrapper = mount(Button, { props: { variant: 'icon', ariaLabel: '关闭' } })
    expect(wrapper.classes()).toContain('icon-button')
    expect(wrapper.classes()).not.toContain('icon')
    expect(wrapper.attributes('aria-label')).toBe('关闭')
  })

  it('disables the control and stops emitting clicks', async () => {
    const wrapper = mount(Button, { props: { disabled: true } })
    expect(wrapper.attributes('disabled')).toBeDefined()
    await wrapper.trigger('click')
    // Native <button disabled> does not dispatch click at all — nothing should be emitted.
    expect(wrapper.emitted('click')).toBeUndefined()
  })

  it('renders identically regardless of the active theme (styling is token-driven, not prop-driven)', () => {
    document.documentElement.dataset.theme = 'light'
    const light = mount(Button, { slots: { default: '保存' } }).html()
    document.documentElement.dataset.theme = 'dark'
    const dark = mount(Button, { slots: { default: '保存' } }).html()
    expect(dark).toBe(light)
  })

  it('never hardcodes a color, radius or motion duration — everything comes from tokens.css', () => {
    const source = readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'Button.vue'), 'utf-8')
    expect(source).not.toMatch(/#[0-9a-fA-F]{3,8}\b/)
    expect(source).not.toMatch(/border-radius\s*:\s*\d+px/)
    expect(source).not.toMatch(/\b\d+m?s\b/)
  })
})
