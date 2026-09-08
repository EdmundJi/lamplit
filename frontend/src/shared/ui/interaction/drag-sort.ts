/**
 * Geometry for pointer reordering, kept free of the DOM so the awkward parts
 * (which row the pointer is over, how far the rest of the list has to move)
 * can be reasoned about and tested on their own.
 */
export type RowBox = { top: number; height: number }

export type DragPlan = {
  /** Index the dragged row would land on if released now. */
  to: number
  /** Vertical shift for every row, including the dragged one. */
  offsets: number[]
  /** Where the dragged row rests relative to where it started. */
  rest: number
}

export function clampDrag(boxes: RowBox[], from: number, delta: number) {
  const first = boxes[0]
  const last = boxes[boxes.length - 1]
  const row = boxes[from]
  return Math.max(first.top - row.top, Math.min(last.top + last.height - row.height - row.top, delta))
}

/** How far a neighbour travels when it swaps with the dragged row. */
function advance(boxes: RowBox[], from: number) {
  const after = boxes[from + 1]
  const before = boxes[from - 1]
  if (after) return after.top - boxes[from].top
  if (before) return boxes[from].top - before.top
  return boxes[from].height
}

/** Where the dragged row would come to rest in a given slot. Moving down it
 * lines up with the bottom of the row it passed, moving up with the top. */
function restIn(boxes: RowBox[], from: number, slot: number) {
  const target = boxes[slot]
  return (slot > from ? target.top + target.height - boxes[from].height : target.top) - boxes[from].top
}

export function planDrag(boxes: RowBox[], from: number, rawDelta: number): DragPlan {
  const delta = clampDrag(boxes, from, rawDelta)
  // The row joins the slot it is closest to, so a half-row drag is enough to swap.
  let to = from
  let nearest = Math.abs(delta)
  boxes.forEach((_, slot) => {
    const distance = Math.abs(restIn(boxes, from, slot) - delta)
    if (distance < nearest) {
      nearest = distance
      to = slot
    }
  })
  const step = advance(boxes, from)
  const offsets = boxes.map((_, index) => {
    if (index === from) return delta
    if (index > from && index <= to) return -step
    if (index < from && index >= to) return step
    return 0
  })
  return { to, offsets, rest: restIn(boxes, from, to) }
}
