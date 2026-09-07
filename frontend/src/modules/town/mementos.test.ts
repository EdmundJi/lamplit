import { describe, expect, it } from 'vitest'
import { earnedMementos, mementoDate } from './mementos'
import type { Achievement } from '../achievements/achievement.types'
const record = (code: string, earned: boolean, earnedAt: string | null): Achievement => ({ code, earned, earnedAt, name: code, body: '', triggerText: '', category: 'ACTION', iconKey: 'Leaf', tone: 'green' })
describe('persisted home mementos', () => {
  it('only displays earned records, newest first, without mutating the response or inventing missing dates', () => {
    const items = [record('old', true, '2025-01-01T00:00:00Z'), record('locked', false, null), record('undated', true, null), record('new', true, '2026-09-06T00:00:00Z')]
    expect(earnedMementos(items).map(x => x.code)).toEqual(['new', 'old', 'undated'])
    expect(items[0]!.code).toBe('old')
    expect(mementoDate(null, 'Asia/Shanghai')).toBe('日期未记录')
    expect(mementoDate('broken', 'Asia/Shanghai')).toBe('日期未记录')
  })
  it('uses the town timezone even across a date boundary', () => {
    expect(mementoDate('2026-09-05T17:00:00Z', 'Asia/Shanghai')).toBe('2026年9月6日')
    expect(mementoDate('2026-09-05T17:00:00Z', 'America/Los_Angeles')).toBe('2026年9月5日')
  })
})
