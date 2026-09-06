import type { Direction4 } from './walkers'

export type PresencePayload = {
  x: number
  y: number
  facing: Direction4
  scene: string
}

export type PresenceData = {
  x: number
  y: number
  facing: string
  scene: string
  updatedAt?: string
  stale?: boolean
}

type Point = { x: number; y: number }

/**
 * Threshold for teleporting vs walking: if distance exceeds this (about one viewport),
 * fade out/in; otherwise walk smoothly.
 */
const TELEPORT_THRESHOLD = 800

/**
 * Determines whether a position change should teleport (fade) instead of walk.
 */
export function shouldTeleport(from: Point, to: Point): boolean {
  const distance = Math.hypot(to.x - from.x, to.y - from.y)
  return distance > TELEPORT_THRESHOLD
}

/**
 * Extracts walk target from presence data; returns null if presence is missing or stale.
 */
export function presenceTarget(presence: PresenceData | null | undefined): Point & { facing: string } | null {
  if (!presence || presence.stale) return null
  return { x: presence.x, y: presence.y, facing: presence.facing }
}

/**
 * Reconciles client-sent presence with server-returned presence.
 * Server may clamp coordinates or reject the update; client adopts server's decision.
 */
export function reconcilePresence(
  sent: PresencePayload,
  received: PresenceData
): PresenceData {
  // Server is authoritative: always use what it returned
  // (it may have clamped coordinates to valid bounds)
  return received
}

/**
 * Creates a throttled presence reporter that batches updates and reports at most once per interval.
 * - Calls the report callback immediately on first update after idle period
 * - Throttles subsequent updates within the interval
 * - flush() sends pending update immediately (for visibility/unload)
 */
export function createPresenceReporter(
  onReport: (payload: PresencePayload) => void,
  throttleMs: number
): {
  update: (payload: PresencePayload) => void
  flush: () => void
  clear: () => void
} {
  let pending: PresencePayload | null = null
  let lastReportTime = 0
  let timerId: ReturnType<typeof setTimeout> | null = null

  function doReport(payload: PresencePayload) {
    onReport(payload)
    lastReportTime = Date.now()
    pending = null
    if (timerId !== null) {
      clearTimeout(timerId)
      timerId = null
    }
  }

  return {
    clear() {
      if (timerId !== null) clearTimeout(timerId)
      timerId = null
      pending = null
      lastReportTime = 0
    },
    update(payload: PresencePayload) {
      const now = Date.now()
      const elapsed = now - lastReportTime

      if (elapsed >= throttleMs) {
        // Enough time passed: report immediately
        doReport(payload)
      } else {
        // Within throttle window: queue and schedule
        pending = payload
        if (timerId === null) {
          const remaining = throttleMs - elapsed
          timerId = setTimeout(() => {
            if (pending) doReport(pending)
          }, remaining)
        }
      }
    },

    flush() {
      if (timerId !== null) {
        clearTimeout(timerId)
        timerId = null
      }
      if (pending) {
        onReport(pending)
        pending = null
        lastReportTime = Date.now()
      }
    },
  }
}
