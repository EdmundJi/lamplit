import { describe, expect, it, vi } from 'vitest'
import { encouragement, encouragements } from './encouragement'

describe('encouragement library', () => {
  it('keeps a broad phrase bank for every product moment', () => {
    for (const messages of Object.values(encouragements)) expect(messages.length).toBeGreaterThanOrEqual(8)
  })

  it('does not repeat the same phrase twice in a row', () => {
    vi.spyOn(Math, 'random').mockReturnValue(0)
    expect(encouragement('taskCompleted')).not.toBe(encouragement('taskCompleted'))
  })
})
