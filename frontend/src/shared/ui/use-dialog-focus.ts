import { nextTick, onBeforeUnmount, watch } from 'vue'

/** Focus containment and return for a single active dialog in a view. */
export function useDialogFocus(active: () => boolean, selector: string, close: () => void) {
  let previous: HTMLElement | null = null
  let overflow = ''
  let locked = false
  const focusable = 'button:not(:disabled), a[href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), summary, [tabindex="0"]'
  function keydown(event: KeyboardEvent) {
    if (!active()) return
    if (event.key === 'Escape') { event.preventDefault(); close(); return }
    if (event.key !== 'Tab') return
    const dialog = document.querySelector<HTMLElement>(selector)
    const items = Array.from(dialog?.querySelectorAll<HTMLElement>(focusable) ?? []).filter(item => item.getClientRects().length)
    if (!items.length) { event.preventDefault(); dialog?.focus(); return }
    const first = items[0], last = items[items.length - 1]
    if (event.shiftKey && (document.activeElement === first || !dialog?.contains(document.activeElement))) { event.preventDefault(); last.focus() }
    else if (!event.shiftKey && (document.activeElement === last || !dialog?.contains(document.activeElement))) { event.preventDefault(); first.focus() }
  }
  function restore() {
    if (!locked) return
    document.body.style.overflow = overflow
    locked = false
    if (previous?.isConnected) previous.focus()
    previous = null
  }
  const stop = watch(active, async open => {
    if (!open) { restore(); return }
    previous = document.activeElement as HTMLElement | null
    overflow = document.body.style.overflow
    locked = true
    document.body.style.overflow = 'hidden'
    await nextTick()
    if (!active()) return
    document.querySelector<HTMLElement>(selector)?.querySelector<HTMLElement>(focusable)?.focus()
  })
  document.addEventListener('keydown', keydown)
  onBeforeUnmount(() => { stop(); document.removeEventListener('keydown', keydown); restore() })
}
