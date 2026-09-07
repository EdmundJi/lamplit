import { describe, expect, it, vi } from 'vitest'
vi.mock('phaser', () => ({ default: { Scene: class {} } }))
import { residentPosition, scenePlace, visibleActivity, conversationPosition } from './companion-scene'

describe('authoritative activity presentation', () => {
  it('places a focused resident at a visible desk and a gardener next to a planted bed', () => {
    expect(residentPosition('cafe', 0, 'focus')).toEqual({ x: 440, y: 289 })
    expect(residentPosition('home', 0, 'read')).toEqual({ x: 270, y: 327 })
    const garden = residentPosition('garden', 4, 'garden')
    expect(garden.x).toBeGreaterThan(790)
    expect(garden.y).toBeLessThan(410)
  })
  it('gives four NPCs distinct sleeping corners and the avatar a living room seat', () => {
    const positions = Array.from({ length: 4 }, (_, index) => residentPosition('home', index + 1, 'sleep'))
    expect(new Set(positions.map(p => `${p.x}:${p.y}`)).size).toBe(4)
    expect(residentPosition('home', 0, 'rest')).toEqual({ x: 128, y: 300 })
  })
  it('uses distinct visible actions and close conversation positions', () => {
    expect(visibleActivity('sleep', '睡着了')).toBe('sleep')
    expect(visibleActivity('create', '制作海报')).toBe('create')
    expect(visibleActivity('help', '帮忙')).toBe('create')
    expect(visibleActivity('drink', '喝水')).toBe('drink')
    expect(visibleActivity('garden', '浇花')).toBe('garden')
    expect(visibleActivity('help', '帮忙准备读书小聚', 'poster')).toBe('create')
    expect(visibleActivity('create', '给花写一首诗', 'poster')).toBe('create')
    expect(visibleActivity('create', '带一株新芽回家', 'flowers')).toBe('garden')
    expect(visibleActivity('observe', '看看新开的花')).toBe('idle')
    expect(residentPosition('cafe', 1, 'rest')).toEqual({ x: 528, y: 289 })
    expect(conversationPosition('cafe', 1).x - conversationPosition('cafe', 0).x).toBe(42)
  })
  it('maps subplaces while keeping unknown server places safely on the shared street', () => {
    expect(scenePlace('cafe/window')).toBe('cafe')
    expect(scenePlace('home.desk')).toBe('home')
    expect(scenePlace('unavailable')).toBe('street')
  })
})
