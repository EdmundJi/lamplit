import type { ObjectDirective } from 'vue'
import { motionAllowed, motionDuration, motionEasing } from './motion'

/**
 * `v-disclose` on a native <details>: the panel grows from the summary to its
 * measured content height instead of snapping open, and collapses the same way.
 * The element stays a real <details>, so semantics, keyboard and screen-reader
 * behaviour are untouched, and quiet motion settings get the native jump back.
 */
type DiscloseElement = HTMLDetailsElement & { _disclose?: () => void }

function animateHeight(details: HTMLDetailsElement, from: number, to: number, done: () => void) {
  details.style.overflow = 'hidden'
  details.style.height = `${from}px`
  const animation = details.animate(
    { height: [`${from}px`, `${to}px`] },
    { duration: motionDuration('medium'), easing: motionEasing() },
  )
  const settle = () => {
    details.style.overflow = ''
    details.style.height = ''
    done()
  }
  animation.onfinish = settle
  animation.oncancel = settle
}

export const disclose: ObjectDirective<DiscloseElement> = {
  mounted(details) {
    const summary = details.querySelector('summary')
    if (!summary) return
    const onClick = (event: MouseEvent) => {
      if (!motionAllowed() || typeof details.animate !== 'function') return
      const collapsed = summary.getBoundingClientRect().height
      const expanded = details.getBoundingClientRect().height
      event.preventDefault()
      if (details.open) {
        // Absolutely positioned panels never changed the box; leave them alone.
        if (expanded <= collapsed) { details.open = false; return }
        animateHeight(details, expanded, collapsed, () => { details.open = false })
        return
      }
      details.open = true
      const opened = details.getBoundingClientRect().height
      if (opened <= collapsed) return
      animateHeight(details, collapsed, opened, () => {})
    }
    summary.addEventListener('click', onClick)
    details._disclose = () => summary.removeEventListener('click', onClick)
  },
  unmounted(details) {
    details._disclose?.()
    details._disclose = undefined
  },
}
