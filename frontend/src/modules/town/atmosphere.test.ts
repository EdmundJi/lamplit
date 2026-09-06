import { describe, expect, it } from 'vitest'
import {
  approach,
  lightTargetAlpha,
  minutesOfDay,
  paletteForTime,
  particleBudget,
  seasonForMonth,
  seasonTint,
  TownAtmosphere,
} from './atmosphere'

describe('TownAtmosphere weather getter (M7-9 依赖它判断该不该躲雨)', () => {
  it('defaults to clear and reflects setWeather without needing attach()', () => {
    const atmosphere = new TownAtmosphere({ worldWidth: 100, worldHeight: 100, groundY: 50 })
    expect(atmosphere.getWeather()).toBe('clear')
    atmosphere.setWeather('rain')
    expect(atmosphere.getWeather()).toBe('rain')
    atmosphere.setWeather('snow')
    expect(atmosphere.getWeather()).toBe('snow')
  })
})

describe('minutesOfDay', () => {
  it('converts a Date to minutes since midnight', () => {
    expect(minutesOfDay(new Date(2026, 0, 1, 0, 0, 0))).toBe(0)
    expect(minutesOfDay(new Date(2026, 0, 1, 6, 30, 0))).toBe(390)
    expect(minutesOfDay(new Date(2026, 0, 1, 23, 59, 0))).toBe(1439)
  })
})

describe('paletteForTime', () => {
  it('deep night is blue-dominant with a strong overlay and full light intensity', () => {
    const p = paletteForTime(120) // 02:00
    const b = p.overlayColor & 0xff
    const r = (p.overlayColor >> 16) & 0xff
    expect(b).toBeGreaterThan(r)
    expect(p.overlayAlpha).toBeGreaterThan(0.4)
    expect(p.lightIntensity).toBeCloseTo(1, 1)
  })

  it('dusk is orange-dominant (red channel clearly ahead of blue)', () => {
    const p = paletteForTime(18 * 60 + 30) // 18:30
    const r = (p.overlayColor >> 16) & 0xff
    const b = p.overlayColor & 0xff
    expect(r).toBeGreaterThan(b + 50)
    expect(p.overlayAlpha).toBeGreaterThan(0)
    expect(p.overlayAlpha).toBeLessThan(0.6)
  })

  it('dawn is cool white: bright but blue channel leads red', () => {
    const p = paletteForTime(6 * 60 + 30) // 06:30
    const r = (p.overlayColor >> 16) & 0xff
    const b = p.overlayColor & 0xff
    expect(b).toBeGreaterThan(r)
    expect(r).toBeGreaterThan(100) // still bright, not dark blue like midnight
  })

  it('midday has no overlay and no lights on', () => {
    const p = paletteForTime(13 * 60)
    expect(p.overlayAlpha).toBe(0)
    expect(p.lightIntensity).toBe(0)
  })

  it('wraps around midnight continuously (23:59 close to 00:00)', () => {
    const before = paletteForTime(23 * 60 + 59)
    const after = paletteForTime(0)
    expect(Math.abs(before.overlayAlpha - after.overlayAlpha)).toBeLessThan(0.05)
    expect(Math.abs(before.lightIntensity - after.lightIntensity)).toBeLessThan(0.05)
  })

  it('summer dusk stays lighter than winter dusk at the same clock time', () => {
    const summer = paletteForTime(18 * 60, 'summer')
    const winter = paletteForTime(18 * 60, 'winter')
    expect(summer.overlayAlpha).toBeLessThan(winter.overlayAlpha)
    expect(summer.lightIntensity).toBeLessThan(winter.lightIntensity)
  })
})

describe('seasonForMonth', () => {
  it('maps months to the expected season', () => {
    expect(seasonForMonth(1)).toBe('winter')
    expect(seasonForMonth(3)).toBe('spring')
    expect(seasonForMonth(5)).toBe('spring')
    expect(seasonForMonth(6)).toBe('summer')
    expect(seasonForMonth(8)).toBe('summer')
    expect(seasonForMonth(9)).toBe('autumn')
    expect(seasonForMonth(11)).toBe('autumn')
    expect(seasonForMonth(12)).toBe('winter')
    expect(seasonForMonth(2)).toBe('winter')
  })

  it('wraps out-of-range months', () => {
    expect(seasonForMonth(13)).toBe(seasonForMonth(1))
    expect(seasonForMonth(0)).toBe(seasonForMonth(12))
  })
})

describe('seasonTint', () => {
  it('gives every season a distinct, very subtle tint', () => {
    const seasons = ['spring', 'summer', 'autumn', 'winter'] as const
    const tints = seasons.map(seasonTint)
    for (const tint of tints) expect(tint.alpha).toBeLessThanOrEqual(0.08)
    const colors = new Set(tints.map(t => t.color))
    expect(colors.size).toBe(seasons.length)
  })
})

describe('particleBudget', () => {
  it('is zero for clear weather regardless of quality', () => {
    expect(particleBudget('clear', 'full')).toBe(0)
    expect(particleBudget('clear', 'reduced')).toBe(0)
    expect(particleBudget('clear', 'off')).toBe(0)
  })

  it('is zero for any weather when quality is off (reduced-motion degrade)', () => {
    expect(particleBudget('rain', 'off')).toBe(0)
    expect(particleBudget('snow', 'off')).toBe(0)
  })

  it('caps rain and snow counts, full quality allowing more than reduced', () => {
    expect(particleBudget('rain', 'full')).toBeGreaterThan(particleBudget('rain', 'reduced'))
    expect(particleBudget('snow', 'full')).toBeGreaterThan(particleBudget('snow', 'reduced'))
    expect(particleBudget('rain', 'reduced')).toBeGreaterThan(0)
    expect(particleBudget('snow', 'reduced')).toBeGreaterThan(0)
  })
})

describe('approach', () => {
  it('does nothing over zero elapsed time', () => {
    expect(approach(10, 100, 0, 200)).toBe(10)
  })

  it('jumps straight to target when smoothing is disabled', () => {
    expect(approach(10, 100, 16, 0)).toBe(100)
  })

  it('moves toward the target without ever overshooting it', () => {
    const next = approach(0, 100, 50, 200)
    expect(next).toBeGreaterThan(0)
    expect(next).toBeLessThan(100)
  })

  it('gets arbitrarily close to the target given enough elapsed time', () => {
    const next = approach(0, 100, 10_000, 200)
    expect(next).toBeCloseTo(100, 1)
  })

  it('works symmetrically approaching from above', () => {
    const next = approach(100, 0, 50, 200)
    expect(next).toBeLessThan(100)
    expect(next).toBeGreaterThan(0)
  })
})

describe('lightTargetAlpha', () => {
  it('lamps shine at full computed intensity', () => {
    expect(lightTargetAlpha('lamp', { lightIntensity: 0.8 })).toBeCloseTo(0.8)
  })

  it('windows are dimmer than lamps at the same intensity', () => {
    const lamp = lightTargetAlpha('lamp', { lightIntensity: 0.8 })
    const window = lightTargetAlpha('window', { lightIntensity: 0.8 })
    expect(window).toBeLessThan(lamp)
    expect(window).toBeGreaterThan(0)
  })

  it('both are off at zero intensity', () => {
    expect(lightTargetAlpha('lamp', { lightIntensity: 0 })).toBe(0)
    expect(lightTargetAlpha('window', { lightIntensity: 0 })).toBe(0)
  })
})
