import { describe, expect, it } from 'vitest'
import { NAV_ITEMS, placeForPath } from './nav'

describe('nav -> stage place bindings', () => {
  it('maps every route to the same place its sidebar item names', () => {
    // 'avatar' is the one virtual place: /today follows wherever the avatar actually is, rather
    // than a fixed street target (see companion-art.ts's STAGE_PLACES.avatar).
    expect(placeForPath('/today')).toBe('avatar')
    expect(placeForPath('/goals')).toBe('board')
    expect(placeForPath('/attributes')).toBe('home')
    expect(placeForPath('/friends')).toBe('cafe')
    expect(placeForPath('/friends/chat')).toBe('cafe')
    expect(placeForPath('/partners')).toBe('garden')
  })
  it('falls back to street for /town itself (no place - it is the town) and unknown paths', () => {
    expect(placeForPath('/town')).toBe('street')
    expect(placeForPath('/nowhere')).toBe('street')
  })
  it('every nav item with a mobile slot has a label', () => {
    for (const item of NAV_ITEMS) expect(item.label.length).toBeGreaterThan(0)
  })
})
