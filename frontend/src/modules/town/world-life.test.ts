import { describe, expect, it } from 'vitest'
import { weatherForDate } from './world-life'

describe('town weather uses the server calendar date', () => {
  it('matches epoch-day modulo five without depending on the browser timezone', () => {
    expect(weatherForDate('1970-01-01')).toBe('rain')
    expect(weatherForDate('1970-01-02')).toBe('clear')
    expect(weatherForDate('1969-12-27')).toBe('rain')
    expect(weatherForDate('invalid')).toBe('clear')
  })
})

import { minuteInZone, townTime } from './world-life'
import { paletteForTime, seasonForMonth } from './atmosphere'

describe('continuous player timezone environment clock', () => {
  it('uses the player calendar month across UTC month boundaries', () => {
    const time = Date.parse('2026-08-31T17:30:30.500Z')
    expect(townTime(time, 'Asia/Shanghai')).toEqual({ minutes: 90 + 30 / 60 + 500 / 60000, month: 9 })
    expect(townTime(time, 'America/Los_Angeles').month).toBe(8)
  })

  it('preserves fractional dawn/dusk progression instead of pinning every day to noon', () => {
    const early = townTime(Date.parse('2026-09-06T10:00:00Z'), 'Asia/Shanghai')
    const later = townTime(Date.parse('2026-09-06T10:30:00Z'), 'Asia/Shanghai')
    expect(early.minutes).toBe(1080)
    expect(later.minutes).toBe(1110)
    expect(paletteForTime(later.minutes, seasonForMonth(later.month)).lightIntensity).toBeGreaterThan(paletteForTime(early.minutes, seasonForMonth(early.month)).lightIntensity)
  })

  it('manual preview retains the calendar and leaves authoritative NPC time unchanged; null restores live time', () => {
    const epoch = Date.parse('2026-09-06T10:30:00Z'), zone = 'Asia/Shanghai'
    expect(townTime(epoch, zone, true)).toEqual({ minutes: 1320, month: 9 })
    expect(townTime(epoch, zone, false)).toEqual({ minutes: 720, month: 9 })
    expect(minuteInZone(epoch, zone)).toBe(1110)
    expect(townTime(epoch + 60000, zone, null).minutes).toBe(1111)
  })
})
