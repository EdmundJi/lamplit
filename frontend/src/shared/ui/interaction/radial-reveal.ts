import { motionAllowed } from './motion'

/**
 * Appearance changes repaint the whole page. Expanding the new look from the
 * control the user actually pressed keeps that repaint readable as a
 * consequence of the click, instead of a full-window flash.
 */
type Origin = { x: number; y: number }

type ViewTransitionDocument = Document & {
  startViewTransition?: (callback: () => void | Promise<void>) => { ready: Promise<void>; finished: Promise<void> }
}

export function revealOrigin(source: Event | null): Origin {
  const pointer = source as MouseEvent | null
  if (pointer && (pointer.clientX || pointer.clientY)) return { x: pointer.clientX, y: pointer.clientY }
  // Keyboard activation reports 0,0; fall back to the middle of the pressed control.
  const element = (source?.currentTarget ?? source?.target) as Element | null
  const rect = element?.getBoundingClientRect?.()
  if (rect && (rect.width || rect.height)) return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 }
  return { x: window.innerWidth / 2, y: window.innerHeight / 2 }
}

/** Distance from the origin to the furthest viewport corner, so the circle covers the page. */
export function coverRadius(origin: Origin, width = window.innerWidth, height = window.innerHeight) {
  return Math.hypot(Math.max(origin.x, width - origin.x), Math.max(origin.y, height - origin.y))
}

export async function radialReveal(source: Event | null, apply: () => void) {
  const owner = document as ViewTransitionDocument
  if (!motionAllowed() || typeof owner.startViewTransition !== 'function') {
    apply()
    return
  }
  const origin = revealOrigin(source)
  const transition = owner.startViewTransition(() => { apply() })
  try {
    await transition.ready
  } catch {
    return // A transition replaced by a newer one still applied the change.
  }
  // Web Animations is what draws the circle; without it the change simply lands.
  document.documentElement.animate?.(
    {
      clipPath: [
        `circle(0px at ${origin.x}px ${origin.y}px)`,
        `circle(${coverRadius(origin)}px at ${origin.x}px ${origin.y}px)`,
      ],
    },
    { duration: 560, easing: 'cubic-bezier(.22,.75,.2,1)', pseudoElement: '::view-transition-new(root)' },
  )
}
