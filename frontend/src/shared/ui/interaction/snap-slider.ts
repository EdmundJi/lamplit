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
