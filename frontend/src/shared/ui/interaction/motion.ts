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
