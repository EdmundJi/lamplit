import { describe, expect, it } from 'vitest'
import { clampDrag, planDrag } from './drag-sort'

const rows = [0, 64, 128, 192].map(top => ({ top, height: 64 }))

describe('drag sort geometry', () => {
  it('keeps a row inside the list no matter how far the pointer travels', () => {
    expect(clampDrag(rows, 0, -500)).toBe(0)
    expect(clampDrag(rows, 0, 500)).toBe(192)
    expect(clampDrag(rows, 3, -500)).toBe(-192)
  })

  it('holds its place until the row passes a neighbour centre', () => {
    expect(planDrag(rows, 0, 20).to).toBe(0)
    expect(planDrag(rows, 0, 40).to).toBe(1)
    expect(planDrag(rows, 0, 150).to).toBe(2)
  })

  it('moves only the rows between the old and the new position', () => {
    expect(planDrag(rows, 0, 70).offsets).toEqual([70, -64, 0, 0])
    expect(planDrag(rows, 3, -70).offsets).toEqual([0, 0, 64, -70])
  })

  it('rests on the slot it landed on, in both directions', () => {
    expect(planDrag(rows, 0, 70).rest).toBe(64)
    expect(planDrag(rows, 3, -70).rest).toBe(-64)
    expect(planDrag(rows, 1, 5).rest).toBe(0)
  })

  it('lines a taller row up with the bottom of the row it passed', () => {
    const mixed = [{ top: 0, height: 100 }, { top: 100, height: 60 }]
    expect(planDrag(mixed, 0, 90).rest).toBe(60)
  })
})
