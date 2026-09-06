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
