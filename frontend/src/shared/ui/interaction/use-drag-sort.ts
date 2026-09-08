import { nextTick, ref } from 'vue'
import { motionAllowed } from './motion'
import { planDrag, type DragPlan, type RowBox } from './drag-sort'

/**
 * Pointer reordering for a keyed list. Rows stay in normal flow and are only
 * translated, so a drag never disturbs layout and works the same for mouse,
 * pen and touch. The list commits through the caller's `move`; this composable
 * owns nothing but the motion.
 */
export function useDragSort(options: {
  container: () => HTMLElement | null | undefined
  move: (id: string, targetId: string) => void
}) {
  const draggingId = ref<string | null>(null)
  let rows: HTMLElement[] = []
  let boxes: RowBox[] = []
  let from = -1
  let startY = 0
  let plan: DragPlan | null = null
  let handle: HTMLElement | null = null
  let pointerId = -1

  function paint(offsets: number[], live: boolean) {
    rows.forEach((row, index) => {
      // The dragged row tracks the pointer exactly; its neighbours ease aside.
      const eased = live && index !== from && motionAllowed()
      row.style.transition = eased ? 'transform var(--motion-fast) cubic-bezier(.2,.8,.2,1)' : 'none'
      row.style.transform = offsets[index] ? `translateY(${offsets[index]}px)` : ''
    })
  }

  function onMove(event: PointerEvent) {
    if (event.pointerId !== pointerId || from < 0) return
    plan = planDrag(boxes, from, event.clientY - startY)
    paint(plan.offsets, true)
  }

  function onKey(event: KeyboardEvent) {
    if (event.key === 'Escape') {
      plan = null
      finish()
    }
  }

  async function settle(id: string, residual: number) {
    await nextTick()
    const rendered = options.container()?.querySelectorAll<HTMLElement>('[data-sort-id]')
    const row = Array.from(rendered ?? []).find(candidate => candidate.dataset.sortId === id)
    if (!row || !residual || !motionAllowed()) return
    row.style.transition = 'none'
    row.style.transform = `translateY(${residual}px)`
    void row.offsetHeight // Commit the starting offset before animating away from it.
    requestAnimationFrame(() => {
      row.style.transition = 'transform var(--motion-medium) cubic-bezier(.2,.9,.25,1)'
      row.style.transform = ''
      row.addEventListener('transitionend', () => { row.style.transition = '' }, { once: true })
    })
  }

  function finish() {
    const id = draggingId.value
    if (!id) return
    handle?.releasePointerCapture?.(pointerId)
    handle?.removeEventListener('pointermove', onMove)
    handle?.removeEventListener('pointerup', finish)
    handle?.removeEventListener('pointercancel', finish)
    window.removeEventListener('keydown', onKey)
    options.container()?.classList.remove('is-sorting')
    const landing = plan
    const residual = landing ? landing.offsets[from] - landing.rest : 0
    paint(boxes.map(() => 0), false)
    rows.forEach(row => { row.style.transition = '' })
    draggingId.value = null
    handle = null
    from = -1
    plan = null
    const target = landing && rows[landing.to]?.dataset.sortId
    if (target && target !== id) options.move(id, target)
    void settle(id, residual)
  }

  function start(id: string, event: PointerEvent) {
    const container = options.container()
    if (!container || event.button > 0 || draggingId.value) return
    rows = Array.from(container.querySelectorAll<HTMLElement>('[data-sort-id]'))
    from = rows.findIndex(row => row.dataset.sortId === id)
    if (from < 0 || rows.length < 2) return
    boxes = rows.map(row => {
      const rect = row.getBoundingClientRect()
      return { top: rect.top, height: rect.height }
    })
    event.preventDefault()
    startY = event.clientY
    pointerId = event.pointerId
    handle = event.currentTarget as HTMLElement
    handle.setPointerCapture?.(pointerId)
    handle.addEventListener('pointermove', onMove)
    handle.addEventListener('pointerup', finish)
    handle.addEventListener('pointercancel', finish)
    window.addEventListener('keydown', onKey)
    container.classList.add('is-sorting')
    draggingId.value = id
  }

  return { draggingId, start }
}
