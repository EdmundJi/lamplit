import { afterEach, describe, expect, it, vi } from 'vitest'
import { disclose } from './disclose'

type FakeAnimation = { onfinish?: () => void; oncancel?: () => void }

function buildDetails(openHeight = 200) {
  const details = document.createElement('details')
  details.innerHTML = '<summary>已完成 3 项</summary><ul><li>写下第一件事</li></ul>'
  document.body.append(details)
  const summary = details.querySelector('summary')!
  summary.getBoundingClientRect = () => ({ height: 40 }) as DOMRect
  details.getBoundingClientRect = () => ({ height: details.open ? openHeight : 40 }) as DOMRect
  const animations: FakeAnimation[] = []
  details.animate = vi.fn(() => {
    const animation: FakeAnimation = {}
    animations.push(animation)
    return animation as unknown as Animation
  }) as unknown as typeof details.animate
  ;(disclose.mounted as (element: HTMLDetailsElement) => void)(details)
  return { details, summary, animations }
}

function click(summary: HTMLElement) {
  summary.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
}

afterEach(() => { document.body.innerHTML = '' })

describe('v-disclose', () => {
  it('opens immediately and grows from the summary to the content height', () => {
    const { details, summary, animations } = buildDetails()
    click(summary)
    expect(details.open).toBe(true)
    expect(details.animate).toHaveBeenCalledWith({ height: ['40px', '200px'] }, expect.objectContaining({ duration: 260 }))
    animations[0].onfinish?.()
    expect(details.style.height).toBe('')
    expect(details.style.overflow).toBe('')
  })

  it('stays open until the collapse finishes, then closes', () => {
    const { details, summary, animations } = buildDetails()
    click(summary)
    animations[0].onfinish?.()
    click(summary)
    expect(details.animate).toHaveBeenLastCalledWith({ height: ['200px', '40px'] }, expect.anything())
    expect(details.open).toBe(true)
    animations[1].onfinish?.()
    expect(details.open).toBe(false)
  })

  it('leaves panels that never changed the box to the browser', () => {
    const { details, summary } = buildDetails(40)
    click(summary)
    expect(details.open).toBe(true)
    expect(details.animate).not.toHaveBeenCalled()
  })

  it('keeps the native jump when motion is turned down', () => {
    document.documentElement.dataset.motion = 'off'
    const { details, summary } = buildDetails()
    click(summary)
    expect(details.animate).not.toHaveBeenCalled()
    delete document.documentElement.dataset.motion
    void details
  })
})
