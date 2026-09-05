import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { shouldTeleport, presenceTarget, reconcilePresence, createPresenceReporter } from './presence'
import type { PresencePayload, PresenceData } from './presence'

describe('shouldTeleport', () => {
  it('returns false when distance is under threshold (800px)', () => {
    expect(shouldTeleport({ x: 0, y: 0 }, { x: 100, y: 100 })).toBe(false)
    expect(shouldTeleport({ x: 0, y: 0 }, { x: 500, y: 0 })).toBe(false)
    expect(shouldTeleport({ x: 100, y: 200 }, { x: 300, y: 400 })).toBe(false)
  })

  it('returns true when distance exceeds threshold', () => {
    expect(shouldTeleport({ x: 0, y: 0 }, { x: 900, y: 0 })).toBe(true)
    expect(shouldTeleport({ x: 0, y: 0 }, { x: 600, y: 600 })).toBe(true)
    expect(shouldTeleport({ x: 100, y: 100 }, { x: 1000, y: 100 })).toBe(true)
  })

  it('handles boundary case near threshold', () => {
    expect(shouldTeleport({ x: 0, y: 0 }, { x: 799, y: 0 })).toBe(false)
    expect(shouldTeleport({ x: 0, y: 0 }, { x: 801, y: 0 })).toBe(true)
  })
})

describe('presenceTarget', () => {
  it('returns null when presence is null or undefined', () => {
    expect(presenceTarget(null)).toBeNull()
    expect(presenceTarget(undefined)).toBeNull()
  })

  it('returns null when presence is stale', () => {
    const stalePresence: PresenceData = {
      x: 100,
      y: 200,
      facing: 'down',
      scene: 'town',
      stale: true,
    }
    expect(presenceTarget(stalePresence)).toBeNull()
  })

  it('returns position when presence is valid', () => {
    const validPresence: PresenceData = {
      x: 150,
      y: 250,
      facing: 'right',
      scene: 'town',
      stale: false,
    }
    const result = presenceTarget(validPresence)
    expect(result).toEqual({ x: 150, y: 250, facing: 'right' })
  })

  it('returns position when presence has no stale field (defaults to not stale)', () => {
    const presence: PresenceData = {
      x: 300,
      y: 400,
      facing: 'left',
      scene: 'town',
    }
    const result = presenceTarget(presence)
    expect(result).toEqual({ x: 300, y: 400, facing: 'left' })
  })
})

describe('reconcilePresence', () => {
  it('adopts server-returned coordinates (server is authoritative)', () => {
    const sent: PresencePayload = { x: 9999, y: 9999, facing: 'down', scene: 'town' }
    const received: PresenceData = { x: 500, y: 300, facing: 'down', scene: 'town', updatedAt: '2026-09-05T10:00:00Z' }

    const result = reconcilePresence(sent, received)
    expect(result).toEqual(received)
  })

  it('preserves server metadata fields', () => {
    const sent: PresencePayload = { x: 100, y: 200, facing: 'right', scene: 'town' }
    const received: PresenceData = { x: 100, y: 200, facing: 'right', scene: 'town', updatedAt: '2026-09-05T10:05:00Z', stale: false }

    const result = reconcilePresence(sent, received)
    expect(result.updatedAt).toBe('2026-09-05T10:05:00Z')
    expect(result.stale).toBe(false)
  })
})

describe('createPresenceReporter', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('reports immediately on first update after idle period', () => {
    const onReport = vi.fn()
    const reporter = createPresenceReporter(onReport, 3000)

    const payload: PresencePayload = { x: 100, y: 200, facing: 'down', scene: 'town' }
    reporter.update(payload)

    expect(onReport).toHaveBeenCalledTimes(1)
    expect(onReport).toHaveBeenCalledWith(payload)
  })

  it('throttles subsequent updates within interval', () => {
    const onReport = vi.fn()
    const reporter = createPresenceReporter(onReport, 3000)

    const payload1: PresencePayload = { x: 100, y: 200, facing: 'down', scene: 'town' }
    const payload2: PresencePayload = { x: 150, y: 250, facing: 'right', scene: 'town' }
    const payload3: PresencePayload = { x: 200, y: 300, facing: 'left', scene: 'town' }

    reporter.update(payload1)
    expect(onReport).toHaveBeenCalledTimes(1)
    expect(onReport).toHaveBeenLastCalledWith(payload1)

    // Within throttle window
    vi.advanceTimersByTime(1000)
    reporter.update(payload2)
    expect(onReport).toHaveBeenCalledTimes(1) // Still only first call

    vi.advanceTimersByTime(1000)
    reporter.update(payload3)
    expect(onReport).toHaveBeenCalledTimes(1) // Still only first call

    // Complete throttle window
    vi.advanceTimersByTime(1000)
    expect(onReport).toHaveBeenCalledTimes(2)
    expect(onReport).toHaveBeenLastCalledWith(payload3) // Last pending update
  })

  it('reports immediately after throttle interval completes', () => {
    const onReport = vi.fn()
    const reporter = createPresenceReporter(onReport, 3000)

    const payload1: PresencePayload = { x: 100, y: 200, facing: 'down', scene: 'town' }
    const payload2: PresencePayload = { x: 200, y: 300, facing: 'up', scene: 'town' }

    reporter.update(payload1)
    expect(onReport).toHaveBeenCalledTimes(1)

    // Wait for throttle interval to complete
    vi.advanceTimersByTime(3000)

    reporter.update(payload2)
    expect(onReport).toHaveBeenCalledTimes(2)
    expect(onReport).toHaveBeenLastCalledWith(payload2)
  })

  it('flush sends pending update immediately', () => {
    const onReport = vi.fn()
    const reporter = createPresenceReporter(onReport, 3000)

    const payload1: PresencePayload = { x: 100, y: 200, facing: 'down', scene: 'town' }
    const payload2: PresencePayload = { x: 150, y: 250, facing: 'right', scene: 'town' }

    reporter.update(payload1)
    expect(onReport).toHaveBeenCalledTimes(1)

    vi.advanceTimersByTime(500)
    reporter.update(payload2)
    expect(onReport).toHaveBeenCalledTimes(1) // Still throttled

    reporter.flush()
    expect(onReport).toHaveBeenCalledTimes(2)
    expect(onReport).toHaveBeenLastCalledWith(payload2)
  })

  it('flush does nothing when no pending update', () => {
    const onReport = vi.fn()
    const reporter = createPresenceReporter(onReport, 3000)

    reporter.flush()
    expect(onReport).not.toHaveBeenCalled()

    const payload: PresencePayload = { x: 100, y: 200, facing: 'down', scene: 'town' }
    reporter.update(payload)
    expect(onReport).toHaveBeenCalledTimes(1)

    reporter.flush()
    expect(onReport).toHaveBeenCalledTimes(1) // No additional call
  })

  it('clears scheduled timer on flush', () => {
    const onReport = vi.fn()
    const reporter = createPresenceReporter(onReport, 3000)

    const payload1: PresencePayload = { x: 100, y: 200, facing: 'down', scene: 'town' }
    const payload2: PresencePayload = { x: 150, y: 250, facing: 'right', scene: 'town' }

    reporter.update(payload1)
    vi.advanceTimersByTime(1000)
    reporter.update(payload2)

    reporter.flush()
    expect(onReport).toHaveBeenCalledTimes(2)

    // Advance past original scheduled time - should not trigger again
    vi.advanceTimersByTime(3000)
    expect(onReport).toHaveBeenCalledTimes(2)
  })

  it('only keeps the latest pending update', () => {
    const onReport = vi.fn()
    const reporter = createPresenceReporter(onReport, 3000)

    const payload1: PresencePayload = { x: 100, y: 200, facing: 'down', scene: 'town' }
    const payload2: PresencePayload = { x: 150, y: 250, facing: 'right', scene: 'town' }
    const payload3: PresencePayload = { x: 200, y: 300, facing: 'left', scene: 'town' }
    const payload4: PresencePayload = { x: 250, y: 350, facing: 'up', scene: 'town' }

    reporter.update(payload1)
    expect(onReport).toHaveBeenCalledTimes(1)

    vi.advanceTimersByTime(500)
    reporter.update(payload2)

    vi.advanceTimersByTime(500)
    reporter.update(payload3)

    vi.advanceTimersByTime(500)
    reporter.update(payload4)

    // Only the latest (payload4) should be reported
    vi.advanceTimersByTime(1500)
    expect(onReport).toHaveBeenCalledTimes(2)
    expect(onReport).toHaveBeenLastCalledWith(payload4)
  })
})
