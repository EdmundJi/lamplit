import { afterEach, describe, expect, it, vi } from 'vitest'
import { coverRadius, radialReveal, revealOrigin } from './radial-reveal'

afterEach(() => {
  delete (document as { startViewTransition?: unknown }).startViewTransition
  delete document.documentElement.dataset.motion
  vi.restoreAllMocks()
})

describe('radial reveal', () => {
  it('starts from the pointer when there is one', () => {
    expect(revealOrigin({ clientX: 40, clientY: 90 } as MouseEvent)).toEqual({ x: 40, y: 90 })
  })

  it('falls back to the middle of the control for keyboard activation', () => {
    const button = document.createElement('button')
    button.getBoundingClientRect = () => ({ left: 10, top: 20, width: 40, height: 20 }) as DOMRect
    const event = { clientX: 0, clientY: 0, currentTarget: button } as unknown as MouseEvent
    expect(revealOrigin(event)).toEqual({ x: 30, y: 30 })
  })

  it('covers the page from any corner', () => {
    expect(coverRadius({ x: 0, y: 0 }, 300, 400)).toBeCloseTo(500)
    expect(coverRadius({ x: 300, y: 400 }, 300, 400)).toBeCloseTo(500)
  })

  it('applies the change directly when the browser has no view transitions', async () => {
    const apply = vi.fn()
    await radialReveal(null, apply)
    expect(apply).toHaveBeenCalledOnce()
  })

  it('applies the change directly when motion is turned down', async () => {
    document.documentElement.dataset.motion = 'reduced'
    const start = vi.fn()
    ;(document as { startViewTransition?: unknown }).startViewTransition = start
    const apply = vi.fn()
    await radialReveal(null, apply)
    expect(apply).toHaveBeenCalledOnce()
    expect(start).not.toHaveBeenCalled()
  })

  it('expands the new appearance from the press once the transition is ready', async () => {
    const animate = vi.fn()
    Object.defineProperty(document.documentElement, 'animate', { value: animate, configurable: true })
    const apply = vi.fn()
    ;(document as { startViewTransition?: unknown }).startViewTransition = (callback: () => void) => {
      callback()
      return { ready: Promise.resolve(), finished: Promise.resolve() }
    }
    await radialReveal({ clientX: 12, clientY: 34 } as MouseEvent, apply)
    expect(apply).toHaveBeenCalledOnce()
    const [frames, options] = animate.mock.calls[0]
    expect((frames as { clipPath: string[] }).clipPath[0]).toBe('circle(0px at 12px 34px)')
    expect(options).toMatchObject({ pseudoElement: '::view-transition-new(root)' })
  })
})
