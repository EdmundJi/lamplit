import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import Skeleton from './Skeleton.vue'

afterEach(() => {
  document.documentElement.removeAttribute('data-theme')
})

describe('Skeleton', () => {
  it('is the first skeleton in the codebase and defaults to a single labelled text line', () => {
    const wrapper = mount(Skeleton)
    const line = wrapper.get('.skeleton')
    expect(line.classes()).toContain('skeleton--text')
    const region = wrapper.get('.skeleton-lines')
    expect(region.attributes('role')).toBe('status')
    expect(region.attributes('aria-label')).toBe('正在加载')
    expect(wrapper.findAll('.skeleton')).toHaveLength(1)
  })

  it('draws the requested number of text lines, shortening the last one', () => {
    const wrapper = mount(Skeleton, { props: { lines: 3 } })
    const lines = wrapper.findAll('.skeleton--text')
    expect(lines).toHaveLength(3)
    expect((lines[0].attributes('style') ?? '')).toContain('100%')
    expect((lines[2].attributes('style') ?? '')).toContain('72%')
  })

  it('renders circle and block variants with their own default sizing', () => {
    const circle = mount(Skeleton, { props: { variant: 'circle' } }).get('.skeleton')
    expect(circle.classes()).toContain('skeleton--circle')
    expect(circle.attributes('style')).toContain('40px')

    const block = mount(Skeleton, { props: { variant: 'block' } }).get('.skeleton')
    expect(block.classes()).toContain('skeleton--block')
    expect(block.attributes('style')).toContain('96px')
  })

  it('accepts explicit width/height overrides', () => {
    const wrapper = mount(Skeleton, { props: { variant: 'block', width: '240px', height: '120px' } })
    const style = wrapper.get('.skeleton').attributes('style') ?? ''
    expect(style).toContain('240px')
    expect(style).toContain('120px')
  })

  it('renders the same markup regardless of the active theme', () => {
    document.documentElement.dataset.theme = 'light'
    const light = mount(Skeleton, { props: { variant: 'block', lines: 2 } }).html()
    document.documentElement.dataset.theme = 'dark'
    const dark = mount(Skeleton, { props: { variant: 'block', lines: 2 } }).html()
    expect(dark).toBe(light)
  })

  it('never hardcodes a color or motion duration — the shimmer and surface come from tokens.css', () => {
    const source = readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'Skeleton.vue'), 'utf-8')
    const style = source.slice(source.indexOf('<style'))
    expect(style).not.toMatch(/#[0-9a-fA-F]{3,8}\b/)
    expect(style).not.toMatch(/animation:\s*skeleton-shimmer\s+\d+m?s/)
  })
})
