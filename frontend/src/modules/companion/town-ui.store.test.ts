import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { useTownUi } from './town-ui.store'
import { useAuthStore } from '../auth/auth.store'

beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()) })

describe('shared /town UI state', () => {
  it('keeps text bubbles off by default and persists the explicit toggle per guest/account key', () => {
    const ui = useTownUi()
    expect(ui.textBubbles).toBe(false)
    ui.toggleTextBubbles()
    expect(ui.textBubbles).toBe(true)
    expect(localStorage.getItem('better-self:town-text-bubbles:guest')).toBe('on')
    ui.toggleTextBubbles()
    expect(localStorage.getItem('better-self:town-text-bubbles:guest')).toBe('off')
  })
  it('turning text bubbles on always clears quiet mode', () => {
    const ui = useTownUi()
    ui.quiet = true
    ui.toggleTextBubbles()
    expect(ui.quiet).toBe(false)
  })
  it('forwards the fullscreen scene\'s click handlers only while CompanionView has registered them', () => {
    const ui = useTownUi()
    expect(ui.fullscreenHandlers.selectResident).toBeUndefined()
    const seen: string[] = []
    const unregister = ui.registerFullscreenHandlers({ selectResident: id => seen.push(id) })
    ui.fullscreenHandlers.selectResident?.('owner')
    expect(seen).toEqual(['owner'])
    unregister()
    expect(ui.fullscreenHandlers.selectResident).toBeUndefined()
  })
  it('clears private selections and sound when the signed-in account changes', () => {
    const auth = useAuthStore()
    auth.user = { publicId: 'one', email: 'one@example.test', displayName: '甲', timezone: 'Asia/Shanghai', role: 'USER' }
    const ui = useTownUi()
    ui.selectedResident = 'owner'
    ui.selectedPlace = 'home-owner'
    ui.soundEnabled = true
    auth.user = { publicId: 'two', email: 'two@example.test', displayName: '乙', timezone: 'Asia/Shanghai', role: 'USER' }
    expect(ui.selectedResident).toBeNull()
    expect(ui.selectedPlace).toBe('')
    expect(ui.soundEnabled).toBe(false)
  })
})
