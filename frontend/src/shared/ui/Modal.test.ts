import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import { nextTick } from 'vue'
import Modal from './Modal.vue'

afterEach(() => {
  document.documentElement.removeAttribute('data-theme')
  document.body.style.overflow = ''
})

describe('Modal', () => {
  it('renders nothing while closed', () => {
    const wrapper = mount(Modal, { props: { open: false, title: '确认' } })
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.find('.dialog-backdrop').exists()).toBe(false)
  })

  it('carries correct dialog semantics when open, labelled by its title', () => {
    const wrapper = mount(Modal, { props: { open: true, title: '删除目标' }, slots: { default: '确认删除？' } })
    const dialog = wrapper.get('[role="dialog"]')
    expect(dialog.attributes('aria-modal')).toBe('true')
    const labelledby = dialog.attributes('aria-labelledby')!
    expect(wrapper.get(`#${labelledby}`).text()).toBe('删除目标')
    expect(wrapper.text()).toContain('确认删除？')
  })

  it('falls back to an explicit aria-label when a custom header slot replaces the title', () => {
    const wrapper = mount(Modal, {
      props: { open: true, ariaLabel: '自定义面板' },
      slots: { header: '<strong>自定义头部</strong>' },
    })
    const dialog = wrapper.get('[role="dialog"]')
    expect(dialog.attributes('aria-label')).toBe('自定义面板')
    expect(dialog.attributes('aria-labelledby')).toBeUndefined()
  })

  it('emits close on backdrop click, close-button click and Escape', async () => {
    const wrapper = mount(Modal, { props: { open: true, title: '面板' }, attachTo: document.body })
    await wrapper.get('.dialog-backdrop').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)

    await wrapper.get('.ui-modal-close').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(2)

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await nextTick()
    expect(wrapper.emitted('close')).toHaveLength(3)
    wrapper.unmount()
  })

  it('ignores backdrop, Escape and the close button while busy', async () => {
    const wrapper = mount(Modal, { props: { open: true, title: '面板', busy: true }, attachTo: document.body })
    await wrapper.get('.dialog-backdrop').trigger('click')
    await wrapper.get('.ui-modal-close').trigger('click')
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await nextTick()
    expect(wrapper.emitted('close')).toBeUndefined()
    expect(wrapper.get('.ui-modal-close').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('honors closeOnBackdrop=false', async () => {
    const wrapper = mount(Modal, { props: { open: true, title: '面板', closeOnBackdrop: false }, attachTo: document.body })
    await wrapper.get('.dialog-backdrop').trigger('click')
    expect(wrapper.emitted('close')).toBeUndefined()
    wrapper.unmount()
  })

  it('moves focus into the dialog on open and restores it on close', async () => {
    const opener = document.createElement('button')
    document.body.appendChild(opener)
    opener.focus()
    expect(document.activeElement).toBe(opener)

    const wrapper = mount(Modal, { props: { open: false, title: '面板' }, attachTo: document.body })
    await wrapper.setProps({ open: true })
    await nextTick()
    await nextTick()
    expect(document.activeElement?.closest('.ui-modal')).toBeTruthy()

    await wrapper.setProps({ open: false })
    await nextTick()
    expect(document.activeElement).toBe(opener)

    wrapper.unmount()
    opener.remove()
  })

  it('renders the same dialog markup regardless of the active theme', () => {
    document.documentElement.dataset.theme = 'light'
    const light = mount(Modal, { props: { open: true, title: '面板' }, slots: { default: '内容' } }).html()
    document.documentElement.dataset.theme = 'dark'
    const dark = mount(Modal, { props: { open: true, title: '面板' }, slots: { default: '内容' } }).html()
    expect(dark).toBe(light)
  })

  it('never hardcodes a color, radius or motion duration — everything comes from tokens.css', () => {
    const source = readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'Modal.vue'), 'utf-8')
    const style = source.slice(source.indexOf('<style'))
    expect(style).not.toMatch(/#[0-9a-fA-F]{3,8}\b/)
    expect(style).not.toMatch(/border-radius\s*:\s*\d+px/)
    expect(style.match(/\b\d+m?s\b/g)).toBeNull()
  })
})
