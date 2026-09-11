/**
 * Interaction motion honours two switches at once: the user's appearance setting
 * and the operating system preference. `reduced` already removes animations in
 * global.css, so these richer interactions treat it the same as `off` and land
 * on their final state immediately instead of playing a shortened version.
 */
export function motionAllowed() {
  if (typeof document === 'undefined' || typeof window === 'undefined') return false
  const level = document.documentElement.dataset.motion
  if (level === 'reduced' || level === 'off') return false
  return !window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches
}

const FALLBACK_MS: Record<'fast' | 'medium' | 'slow', number> = { fast: 140, medium: 260, slow: 520 }

/**
 * The handful of call sites that need a plain number — Web Animations durations,
 * requestAnimationFrame easing — read the current `--motion-*` token through here
 * instead of hardcoding milliseconds, so tokens.css stays the single source of
 * truth even where CSS's own `var()` can't reach.
 */
export function motionDuration(token: 'fast' | 'medium' | 'slow') {
  if (typeof document === 'undefined') return FALLBACK_MS[token]
  const raw = getComputedStyle(document.documentElement).getPropertyValue(`--motion-${token}`)
  const parsed = parseFloat(raw)
  return Number.isFinite(parsed) ? parsed : FALLBACK_MS[token]
}

const FALLBACK_EASE = 'cubic-bezier(.4,0,.2,1)'

/**
 * Same idea as motionDuration: the handful of call sites that hand an easing
 * string to Web Animations (rather than plain CSS `var(--ease)`) read the
 * token here, so tokens.css stays the one place the curve is tuned.
 */
export function motionEasing() {
  if (typeof document === 'undefined') return FALLBACK_EASE
  const raw = getComputedStyle(document.documentElement).getPropertyValue('--ease').trim()
  return raw || FALLBACK_EASE
}
