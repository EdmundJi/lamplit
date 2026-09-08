import { nextTick } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useDragSort } from './use-drag-sort'

function buildList(ids: string[]) {
  const container = document.createElement('ul')
  container.innerHTML = ids.map(id => `<li data-sort-id="${id}"><button class="handle"></button></li>`).join('')
  document.body.append(container)
  container.querySelectorAll('li').forEach((row, index) => {
    row.getBoundingClientRect = () => ({ top: index * 64, height: 64 }) as DOMRect
  })
  return container
}

function drag(container: HTMLElement, id: string, to: number, start: (id: string, event: PointerEvent) => void) {
  const handle = container.querySelector<HTMLElement>(`[data-sort-id="${id}"] .handle`)!
  handle.addEventListener('pointerdown', event => start(id, event as PointerEvent))
  handle.dispatchEvent(new MouseEvent('pointerdown', { clientY: 0, bubbles: true }))
  handle.dispatchEvent(new MouseEvent('pointermove', { clientY: to }))
  return handle
}

afterEach(() => { document.body.innerHTML = '' })

describe('useDragSort', () => {
  it('carries the row and slides its neighbour aside', () => {
    const container = buildList(['a', 'b', 'c'])
    const move = vi.fn()
    const { start, draggingId } = useDragSort({ container: () => container, move })
    drag(container, 'a', 70, start)
    const rows = container.querySelectorAll<HTMLElement>('li')
    expect(draggingId.value).toBe('a')
    expect(rows[0].style.transform).toBe('translateY(70px)')
    expect(rows[1].style.transform).toBe('translateY(-64px)')
    expect(rows[2].style.transform).toBe('')
  })

  it('commits the new order and drops every offset on release', async () => {
    const container = buildList(['a', 'b', 'c'])
    const move = vi.fn()
    const { start, draggingId } = useDragSort({ container: () => container, move })
    const handle = drag(container, 'a', 70, start)
    handle.dispatchEvent(new MouseEvent('pointerup'))
    expect(move).toHaveBeenCalledWith('a', 'b')
    expect(draggingId.value).toBeNull()
    await nextTick()
    expect(container.querySelector<HTMLElement>('[data-sort-id="c"]')!.style.transform).toBe('')
  })

  it('leaves the order alone when the row never passes a neighbour', () => {
    const container = buildList(['a', 'b', 'c'])
    const move = vi.fn()
    const { start } = useDragSort({ container: () => container, move })
    const handle = drag(container, 'a', 12, start)
    handle.dispatchEvent(new MouseEvent('pointerup'))
    expect(move).not.toHaveBeenCalled()
  })

  it('abandons the drag on Escape', () => {
    const container = buildList(['a', 'b', 'c'])
    const move = vi.fn()
    const { start, draggingId } = useDragSort({ container: () => container, move })
    drag(container, 'a', 70, start)
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(move).not.toHaveBeenCalled()
    expect(draggingId.value).toBeNull()
    expect(container.querySelector<HTMLElement>('[data-sort-id="b"]')!.style.transform).toBe('')
  })

  it('ignores a list with nothing to reorder', () => {
    const container = buildList(['a'])
    const move = vi.fn()
    const { start, draggingId } = useDragSort({ container: () => container, move })
    drag(container, 'a', 70, start)
    expect(draggingId.value).toBeNull()
  })
})
