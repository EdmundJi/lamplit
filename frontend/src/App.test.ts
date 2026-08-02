import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import App from './App.vue'

describe('App', () => {
  it('renders the product name', () => {
    expect(mount(App, { global: { stubs: { RouterView: true } } }).text()).toContain('更好的自己')
  })
})
