import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { WorldActionContext, WorldAction } from './world-actions'
import {
  builtinWorldActions,
  clearWorldActions,
  getWorldAction,
  registerWorldActions,
  resolveWorldAction,
  runWorldAction,
  worldActionsFor,
} from './world-actions'

function stubBridge() {
  return {
    emit: vi.fn(),
    runMode: false,
    setRunMode: vi.fn(),
    run: vi.fn(),
  }
}

function stubContext(over: Partial<WorldActionContext> = {}): WorldActionContext {
  return {
    bridge: stubBridge(),
    anchor: null,
    selfPublicId: 'me',
    night: false,
    insideAcademy: false,
    refresh: vi.fn(),
    ...over,
  }
}

describe('world-actions registry', () => {
  beforeEach(() => {
    clearWorldActions()
  })

  it('filters by anchor: anchor-specific actions only show at their anchor, global ones show everywhere', () => {
    registerWorldActions([
      { id: 'a.home', label: 'home only', domain: 'today', anchors: ['home'], run: () => ({ ok: true }) },
      { id: 'a.global', label: 'global', domain: 'world', run: () => ({ ok: true }) },
    ])
    expect(worldActionsFor({ anchor: 'home' }).map(a => a.id).sort()).toEqual(['a.global', 'a.home'])
    expect(worldActionsFor({ anchor: 'academy' }).map(a => a.id)).toEqual(['a.global'])
    expect(worldActionsFor({ anchor: null }).map(a => a.id)).toEqual(['a.global'])
  })

  it('filters by domain regardless of anchor', () => {
    registerWorldActions([
      { id: 'a.one', label: 'one', domain: 'goals', run: () => ({ ok: true }) },
      { id: 'a.two', label: 'two', domain: 'friends', run: () => ({ ok: true }) },
    ])
    expect(worldActionsFor({ domain: 'goals' }).map(a => a.id)).toEqual(['a.one'])
  })

  it('re-registering the same id overwrites the previous definition', () => {
    registerWorldActions([{ id: 'a.dup', label: 'first', domain: 'world', run: () => ({ ok: true }) }])
    registerWorldActions([{ id: 'a.dup', label: 'second', domain: 'world', run: () => ({ ok: true }) }])
    expect(getWorldAction('a.dup')?.label).toBe('second')
  })

  it('resolveWorldAction surfaces the unavailable reason instead of hiding the action', () => {
    const action: WorldAction = {
      id: 'a.locked',
      label: '锁着的能力',
      domain: 'world',
      available: () => ({ ok: false, reason: '还没到时候' }),
      run: () => ({ ok: true }),
    }
    const resolved = resolveWorldAction(action, stubContext())
    expect(resolved.availability).toEqual({ ok: false, reason: '还没到时候' })
    expect(resolved.label).toBe('锁着的能力')
  })

  it('resolveWorldAction resolves function-valued label/hint/confirm against the context', () => {
    const action: WorldAction = {
      id: 'a.dynamic',
      label: ctx => (ctx.night ? '切到白天' : '切到夜晚'),
      hint: ctx => (ctx.night ? '现在是晚上' : '现在是白天'),
      confirm: ctx => (ctx.night ? '' : '确定要现在切换吗？'),
      domain: 'world',
      run: () => ({ ok: true }),
    }
    const day = resolveWorldAction(action, stubContext({ night: false }))
    expect(day.label).toBe('切到夜晚')
    expect(day.confirmText).toBe('确定要现在切换吗？')

    const night = resolveWorldAction(action, stubContext({ night: true }))
    expect(night.label).toBe('切到白天')
    expect(night.confirmText).toBeNull() // 空字符串等于"这次不需要确认"
  })

  it('runWorldAction runs a registered action and replays its events through bridge.emit', async () => {
    registerWorldActions([
      {
        id: 'a.celebrate',
        label: 'celebrate',
        domain: 'world',
        run: () => ({ ok: true, message: '完成了', events: [{ type: 'toast', text: '完成了' }] }),
      },
    ])
    const ctx = stubContext()
    const result = await runWorldAction('a.celebrate', ctx)
    expect(result).toEqual({ ok: true, message: '完成了', events: [{ type: 'toast', text: '完成了' }] })
    expect(ctx.bridge.emit).toHaveBeenCalledWith({ type: 'toast', text: '完成了' })
  })

  it('runWorldAction fails without calling run() when the action does not exist', async () => {
    const ctx = stubContext()
    const result = await runWorldAction('a.missing', ctx)
    expect(result.ok).toBe(false)
    expect(result.message).toBeTruthy()
    expect(ctx.bridge.emit).not.toHaveBeenCalled()
  })

  it('runWorldAction fails without calling run() when available() rejects it', async () => {
    const run = vi.fn(() => ({ ok: true }))
    registerWorldActions([
      { id: 'a.blocked', label: 'blocked', domain: 'world', available: () => ({ ok: false, reason: '条件不满足' }), run },
    ])
    const result = await runWorldAction('a.blocked', stubContext())
    expect(result).toEqual({ ok: false, message: '条件不满足' })
    expect(run).not.toHaveBeenCalled()
  })

  it('runWorldAction awaits an async run() and still replays its events', async () => {
    registerWorldActions([
      {
        id: 'a.async',
        label: 'async',
        domain: 'world',
        run: async () => {
          await Promise.resolve()
          return { ok: true, events: [{ type: 'close' }] }
        },
      },
    ])
    const ctx = stubContext()
    const result = await runWorldAction('a.async', ctx)
    expect(result.ok).toBe(true)
    expect(ctx.bridge.emit).toHaveBeenCalledWith({ type: 'close' })
  })
})

describe('builtinWorldActions', () => {
  it('registers a distinctive set of world-level ids, including one open-panel action per anchored panel', () => {
    const ids = builtinWorldActions().map(a => a.id)
    expect(ids).toEqual(expect.arrayContaining([
      'world.toggle-night', 'world.toggle-run', 'world.go-home', 'world.toggle-academy', 'world.refresh',
      'world.open-panel:today', 'world.open-panel:ai', 'world.open-panel:friends', 'world.open-panel:insights',
    ]))
    // M5-4 之后 9 个面板全部有 anchor，每个面板都会生成一个"打开 XX"的能力。
    expect(ids.filter(id => id.startsWith('world.open-panel:'))).toHaveLength(10)
  })

  it('go-home is unavailable without a self resident, and available once there is one', () => {
    const goHome = builtinWorldActions().find(a => a.id === 'world.go-home')!
    expect(goHome.available?.(stubContext({ selfPublicId: null }))).toEqual({ ok: false, reason: expect.any(String) })
    expect(goHome.available?.(stubContext({ selfPublicId: 'me' }))).toEqual({ ok: true })
  })

  it('go-home requests actual travel to the house', async () => {
    const goHome = builtinWorldActions().find(a => a.id === 'world.go-home')!
    const result = await goHome.run(stubContext({ selfPublicId: 'me' }))
    expect(result.events).toEqual([{ type: 'travel', place: 'home' }])
  })

  it('toggle-academy enters and leaves without an extra confirmation', async () => {
    const toggle = builtinWorldActions().find(a => a.id === 'world.toggle-academy')!
    const entering = resolveWorldAction(toggle, stubContext({ insideAcademy: false }))
    expect(entering.confirmText).toBeNull()
    expect(entering.label).toContain('学院')

    const leaving = resolveWorldAction(toggle, stubContext({ insideAcademy: true }))
    expect(leaving.confirmText).toBeNull()

    const result = await toggle.run(stubContext({ insideAcademy: true }))
    expect(result.events).toEqual([{ type: 'academy', value: false }])
  })

  it('toggle-run flips bridge.setRunMode based on the current runMode', async () => {
    const toggle = builtinWorldActions().find(a => a.id === 'world.toggle-run')!
    const ctx = stubContext({ bridge: { ...stubBridge(), runMode: false } })
    const result = await toggle.run(ctx)
    expect(ctx.bridge.setRunMode).toHaveBeenCalledWith(true)
    expect(result.ok).toBe(true)
  })

  it('refresh awaits ctx.refresh() before reporting success', async () => {
    const refreshAction = builtinWorldActions().find(a => a.id === 'world.refresh')!
    let resolved = false
    const refresh = vi.fn(async () => { await Promise.resolve(); resolved = true })
    const result = await refreshAction.run(stubContext({ refresh }))
    expect(resolved).toBe(true)
    expect(result.ok).toBe(true)
  })

  it('open-panel actions emit an open event for their own panel key', () => {
    const openToday = builtinWorldActions().find(a => a.id === 'world.open-panel:today')!
    expect(openToday.anchors).toEqual(['home'])
    const result = openToday.run(stubContext()) as { events?: unknown[] }
    expect(result.events).toEqual([{ type: 'open', panel: 'today' }])
  })

  // M3-4: 书桌 / 成就墙 / 宠物窝——interior.scene.ts 点击对应家具/宠物时调用的正是这三个 id。
  it('registers the home room\'s three interactive-furniture actions (M3-4)', () => {
    const ids = builtinWorldActions().map(a => a.id)
    expect(ids).toEqual(expect.arrayContaining(['home.open-desk', 'home.open-achievement-wall', 'home.open-pet-house']))
  })

  it('the desk opens the today panel', async () => {
    const desk = builtinWorldActions().find(a => a.id === 'home.open-desk')!
    const result = await desk.run(stubContext())
    expect(result.events).toEqual([{ type: 'open', panel: 'today' }])
  })

  it('the achievement wall opens persisted mementos', async () => {
    const wall = builtinWorldActions().find(a => a.id === 'home.open-achievement-wall')!
    const result = await wall.run(stubContext())
    expect(result.events).toEqual([{ type: 'open', panel: 'mementos' }])
  })

  it('the pet house opens the partners panel (where the existing Rive pet UI lives)', async () => {
    const petHouse = builtinWorldActions().find(a => a.id === 'home.open-pet-house')!
    const result = await petHouse.run(stubContext())
    expect(result.events).toEqual([{ type: 'open', panel: 'partners' }])
  })
})


describe('place activity handoff', () => {
  it.each([
    ['academy.prepare-focus', 'today'], ['home.review-today', 'today'],
    ['cafe.open-ai', 'ai'], ['park.open-companion', 'partners'],
  ])('%s opens the existing business panel without recording progress', async (id, panel) => {
    registerWorldActions(builtinWorldActions())
    const ctx = stubContext()
    const result = await runWorldAction(id, ctx)
    expect(result.ok).toBe(true)
    expect(ctx.bridge.emit).toHaveBeenCalledExactlyOnceWith({ type: 'open', panel })
  })
})
