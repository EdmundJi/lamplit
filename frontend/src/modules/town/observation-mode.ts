/**
 * Pure camera-path math for 观察模式 (plan.md M2-6 / D4): the camera detaches from the player
 * and cruises slowly between points of interest. This module only computes *where* the camera
 * should be and *when* — `town.engine.ts` is the one that owns an actual Phaser camera and
 * calls `camera.pan(leg.x, leg.y, leg.panMs)` / `camera.zoomTo(leg.zoom, ...)` off the back of
 * this. No Phaser import here, so it stays unit-testable in jsdom.
 *
 * Usage pattern for the engine: call `nextLeg(itinerary, elapsedMs)` every frame. When the
 * returned `(legIndex, phase)` changes from the previous frame's, that's the edge to kick off a
 * new `camera.pan`/`camera.zoomTo` tween toward `leg`; `progress` is provided for anything that
 * wants to render a "cruising..." indicator, but isn't required to drive the pan itself since
 * Phaser's own tween owns that interpolation.
 */

export type PointOfInterest = {
  x: number
  y: number
  /** Per-point zoom override; falls back to the config's `defaultZoom` when omitted. */
  zoom?: number
}

export type ObservationConfig = {
  /** How long the camera lingers at each point once it arrives. */
  holdMs?: number
  /** How long the glide to each point takes. */
  panMs?: number
  /** Zoom level used for points that don't specify their own. */
  defaultZoom?: number
}

export type ObservationLeg = {
  x: number
  y: number
  zoom: number
  holdMs: number
  panMs: number
}

/**
 * This is a demo-recording tool first — favour slow and calm over snappy. A 6s dwell plus a 4s
 * glide per point (10s/leg) means a 3-point itinerary loops in exactly 30s, matching the
 * product's own acceptance bar (plan.md intro: "能录一段 30 秒长镜头").
 */
export const DEFAULT_HOLD_MS = 6_000
export const DEFAULT_PAN_MS = 4_000
export const DEFAULT_ZOOM = 1

/** Builds a cyclic itinerary from a list of points of interest. Never mutates `points`. */
export function buildItinerary(points: PointOfInterest[], config: ObservationConfig = {}): ObservationLeg[] {
  const holdMs = config.holdMs ?? DEFAULT_HOLD_MS
  const panMs = config.panMs ?? DEFAULT_PAN_MS
  const defaultZoom = config.defaultZoom ?? DEFAULT_ZOOM
  return points.map(point => ({
    x: point.x,
    y: point.y,
    zoom: point.zoom ?? defaultZoom,
    holdMs,
    panMs,
  }))
}

export type ObservationPhase = 'panning' | 'holding'

export type ItineraryPosition = {
  /** Index into `itinerary` of the leg currently being panned to / held at. */
  legIndex: number
  leg: ObservationLeg
  phase: ObservationPhase
  /** 0-1 progress through the current phase (pan or hold) of `leg`. */
  progress: number
}

/**
 * Given the itinerary and elapsed time since the observation loop started, returns which leg is
 * current and how far through its pan/hold phase we are. Pure function of `elapsedMs` — no
 * internal accumulation, so there is no drift no matter how long the loop runs.
 *
 * - Empty itinerary -> `null` (nothing to observe).
 * - Single-point itinerary -> never pans (there's nowhere else to go); holds at that one point
 *   forever, looping its progress every `holdMs` purely for cosmetic purposes.
 * - Otherwise cycles: pan to leg 0, hold, pan to leg 1, hold, ... wrapping back to leg 0.
 */
export function nextLeg(itinerary: ObservationLeg[], elapsedMs: number): ItineraryPosition | null {
  if (itinerary.length === 0) return null

  if (itinerary.length === 1) {
    const leg = itinerary[0]
    const holdMs = leg.holdMs
    const progress = holdMs > 0 ? wrap(elapsedMs, holdMs) / holdMs : 1
    return { legIndex: 0, leg, phase: 'holding', progress }
  }

  const totalCycle = itinerary.reduce((sum, leg) => sum + leg.panMs + leg.holdMs, 0)
  if (totalCycle <= 0) {
    // Degenerate config (every leg has zero pan/hold time): sit at leg 0 rather than divide by zero.
    return { legIndex: 0, leg: itinerary[0], phase: 'holding', progress: 1 }
  }

  let t = wrap(elapsedMs, totalCycle)
  for (let i = 0; i < itinerary.length; i++) {
    const leg = itinerary[i]
    const legTotal = leg.panMs + leg.holdMs
    if (t < legTotal) {
      if (t < leg.panMs) {
        return { legIndex: i, leg, phase: 'panning', progress: leg.panMs > 0 ? t / leg.panMs : 1 }
      }
      const holdT = t - leg.panMs
      return { legIndex: i, leg, phase: 'holding', progress: leg.holdMs > 0 ? holdT / leg.holdMs : 1 }
    }
    t -= legTotal
  }

  // Unreachable in practice (t < totalCycle by construction) — fall back to the last leg rather
  // than throwing, so a floating-point edge case never crashes the render loop.
  const last = itinerary.length - 1
  return { legIndex: last, leg: itinerary[last], phase: 'holding', progress: 1 }
}

/** Positive-modulo: keeps negative `elapsedMs` (e.g. a countdown-in) from producing negative time. */
function wrap(value: number, modulus: number): number {
  const m = value % modulus
  return m < 0 ? m + modulus : m
}
