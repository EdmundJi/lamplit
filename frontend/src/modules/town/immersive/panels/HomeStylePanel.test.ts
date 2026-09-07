import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { useAuthStore } from '../../../auth/auth.store'
import { currentHomePreference, readHomePreference } from '../../home-style'
import HomeStylePanel from './HomeStylePanel.vue'
beforeEach(() => localStorage.clear())
function render() {
  const pinia = createPinia(); setActivePinia(pinia)
  useAuthStore().user = { publicId: 'alice', email: '', displayName: 'A', timezone: 'Asia/Shanghai', role: 'USER' }
  return mount(HomeStylePanel, { global: { plugins: [pinia] } })
}
describe('HomeStylePanel', () => {
  it('previews, saves and returns to persisted colors when closed without saving', async () => {
    const w = render()
    await w.findAll('.styles button')[1]!.trigger('click')
    expect(currentHomePreference('alice').style).toBe('meadow')
    expect(readHomePreference('alice').style).toBe('original')
    await w.get('.save').trigger('click')
    expect(readHomePreference('alice').style).toBe('meadow')
    await w.findAll('.styles button')[2]!.trigger('click')
    w.unmount()
    expect(currentHomePreference('alice').style).toBe('meadow')
  })
  it('makes reset a preview until saved and allows cancel', async () => {
    const w = render()
    await w.findAll('.styles button')[2]!.trigger('click'); await w.get('.save').trigger('click')
    await w.findAll('.actions button')[0]!.trigger('click')
    expect(currentHomePreference('alice').style).toBe('original')
    expect(readHomePreference('alice').style).toBe('dusk')
    await w.findAll('.actions button')[1]!.trigger('click')
    expect(currentHomePreference('alice').style).toBe('dusk')
    expect(w.text()).toContain('当前浏览器')
    w.unmount()
  })
})
