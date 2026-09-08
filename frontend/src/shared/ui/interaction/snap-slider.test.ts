import { describe, expect, it } from 'vitest'
import { projectRelease, releaseVelocity, snapToStep } from './snap-slider'

describe('snap slider release', () => {
  it('measures speed over the tail of the drag, ignoring an earlier pause', () => {
    const samples = [{ value: 10, time: 0 }, { value: 10, time: 400 }, { value: 30, time: 500 }]
    expect(releaseVelocity(samples)).toBeCloseTo(0.2)
    expect(releaseVelocity([{ value: 10, time: 0 }])).toBe(0)
    expect(releaseVelocity([])).toBe(0)
  })

  it('lets a flick carry one tick further, never more', () => {
    expect(projectRelease(42, 0.2, 5, 10, 90)).toBe(45)
    expect(projectRelease(42, 4, 5, 10, 90)).toBe(45)
    expect(projectRelease(42, -4, 5, 10, 90)).toBe(35)
  })

  it('lands on a tick inside the range', () => {
    expect(snapToStep(43, 5, 10, 90)).toBe(45)
    expect(snapToStep(42, 5, 10, 90)).toBe(40)
    expect(projectRelease(90, 4, 5, 10, 90)).toBe(90)
    expect(projectRelease(10, -4, 5, 10, 90)).toBe(10)
  })

  it('keeps ticks aligned to the minimum, not to zero', () => {
    expect(snapToStep(4, 3, 1, 10)).toBe(4)
    expect(snapToStep(5, 3, 1, 10)).toBe(4)
  })
})
