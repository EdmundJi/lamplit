import { motionAllowed, motionDuration } from './motion'

/**
 * Release behaviour for sliders whose values are only meaningful on a tick:
 * available minutes, weekly frequency, completion ratio. The drag itself is
 * continuous; the release carries a little momentum and then lands on a tick.
 */
export type DragSample = { value: number; time: number }

/** Units per millisecond over the tail of the drag, so an early pause is ignored. */
export function releaseVelocity(samples: DragSample[], window = 120) {
  const last = samples[samples.length - 1]
  if (!last) return 0
  const recent = samples.filter(sample => last.time - sample.time <= window)
  const first = recent[0]
  if (!first || first === last || last.time === first.time) return 0
  return (last.value - first.value) / (last.time - first.time)
}

/**
 * Momentum may carry the value one tick past where the finger left it, never
 * further: a flick should feel answered, not like the control got away.
 */
export function projectRelease(value: number, velocity: number, step: number, min: number, max: number, carry = 90) {
  const projected = value + velocity * carry
  const limited = Math.max(value - step, Math.min(value + step, projected))
  const snapped = min + Math.round((limited - min) / step) * step
  return Math.max(min, Math.min(max, snapped))
}

export function snapToStep(value: number, step: number, min: number, max: number) {
  return projectRelease(value, 0, step, min, max)
}

/**
 * Shared glide: animates a plain number from `from` to `to` with the same
 * cubic-out feel every draggable control lands with. `onFrame` fires with
 * every intermediate value (already eased, not rounded — callers decide
 * whether their domain wants integers); `onDone` fires exactly once, always
 * with `to`, whether or not any frames played. When motion is off (or the
 * two values already match) it resolves straight to `onDone` with no frames,
 * so "no transition" and "already there" behave identically for callers.
 * Returns a cancel function — call it to stop mid-flight (e.g. a new drag
 * interrupting an in-progress release glide).
 */
export function glide(
  from: number,
  to: number,
  onFrame: (value: number) => void,
  onDone: (value: number) => void,
  duration = motionDuration('medium'),
): () => void {
  if (from === to || !motionAllowed()) {
    onDone(to)
    return () => {}
  }
  let frame = 0
  let began = 0
  let cancelled = false
  const tick = (now: number) => {
    if (cancelled) return
    began ||= now
    const progress = Math.min(1, (now - began) / duration)
    const eased = 1 - (1 - progress) ** 3
    onFrame(from + (to - from) * eased)
    if (progress < 1) frame = requestAnimationFrame(tick)
    else onDone(to)
  }
  frame = requestAnimationFrame(tick)
  return () => {
    cancelled = true
    cancelAnimationFrame(frame)
  }
}
