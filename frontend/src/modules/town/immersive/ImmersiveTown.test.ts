import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { CalendarCheck, MessageCircle, Sparkles, Target, TrendingUp } from 'lucide-vue-next'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { h } from 'vue'
import ImmersiveTown from './ImmersiveTown.vue'
import type { WorldPanelDef } from './panel.types'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
const routerMock = vi.hoisted(() => ({ push: vi.fn() }))
const engine = vi.hoisted(() => ({
  game: {
    setRun: vi.fn(), focus: vi.fn(), destroy: vi.fn(), applyModel: vi.fn(), celebrate: vi.fn(),
    setNight: vi.fn(), enterAcademy: vi.fn(), exitAcademy: vi.fn(),
  },
  handlers: {
    onSelect: undefined as undefined | ((id: string | null) => void),
    onAcademyChange: undefined as undefined | ((inside: boolean) => void),
  },
  createTownGame: vi.fn(),
}))

vi.mock('../../../shared/api/client', () => ({ api }))
vi.mock('vue-router', () => ({ useRouter: () => routerMock }))
vi.mock('../town.engine', () => ({
  createTownGame: engine.createTownGame.mockImplementation(async (
    _el: HTMLElement,
    _model: unknown,
    handlers: { onSelect?: (id: string | null) => void; onAcademyChange?: (inside: boolean) => void },
  ) => {
    engine.handlers.onSelect = handlers.onSelect
    engine.handlers.onAcademyChange = handlers.onAcademyChange
    return engine.game
  }),
}))

function stubDef(over: Partial<WorldPanelDef> & Pick<WorldPanelDef, 'key' | 'title' | 'icon'>): WorldPanelDef {
  return {
    subtitle: '',
    // `__esModule` makes Vue's defineAsyncComponent unwrap `.default`, same as a real dynamic import.
    loader: () => Promise.resolve({ __esModule: true, default: { render: () => h('p', `${over.key} 面板内容`) } }),
    size: 'compact',
    fullPage: `/${over.key}`,
    ...over,
  }
}

vi.mock('./panels/manifest', () => ({
  worldPanels: [
    stubDef({ key: 'today', title: '今天', icon: CalendarCheck, anchor: 'home' }),
    stubDef({ key: 'goals', title: '目标', icon: Target }),
    stubDef({ key: 'ai', title: 'AI 助手', icon: Sparkles, anchor: 'npc:assistant' }),
    stubDef({ key: 'friends', title: '好友消息', icon: MessageCircle, anchor: 'npc:postman' }),
    stubDef({ key: 'insights', title: '洞察', icon: TrendingUp, anchor: 'academy' }),
  ],
}))

describe('ImmersiveTown', () => {
  const saved = new Map<string, string>()

  beforeEach(() => {
    saved.clear()
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      value: {
        getItem: (key: string) => saved.get(key) ?? null,
        setItem: (key: string, value: string) => saved.set(key, value),
        removeItem: (key: string) => saved.delete(key),
      },
    })
    setActivePinia(createPinia())
    for (const fn of Object.values(engine.game)) fn.mockClear()
    engine.createTownGame.mockClear()
    routerMock.push.mockReset()
    api.get.mockReset().mockImplementation(async (path: string) => {
      if (path === '/town') throw { status: 404, code: 'NOT_FOUND', message: 'not found' }
      if (path === '/me/profile') return { publicId: 'me', displayName: '我', overallLevel: 5, totalExperience: 500, longestStreak: 12 }
      if (path === '/insights/attributes') return { attributes: [] }
      if (path.startsWith('/task-schedules')) return []
      if (path === '/friends') return { friends: [], incoming: [], outgoing: [] }
      if (path === '/friends/unread-summary') return { totalUnread: 0 }
      throw new Error(path)
    })
  })

  async function mountShell() {
    const wrapper = mount(ImmersiveTown, { attachTo: document.body })
    await flushPromises()
    return wrapper
  }

  function dockButton(wrapper: Awaited<ReturnType<typeof mountShell>>, text: string) {
    const found = wrapper.findAll('.dock-button').find(button => button.text().includes(text))
    if (!found) throw new Error(`no dock button labelled "${text}"`)
    return found
  }

  it('boots the engine and opens a panel from the dock without leaving the page', async () => {
    const wrapper = await mountShell()
    expect(engine.createTownGame).toHaveBeenCalled()

    await dockButton(wrapper, '目标').trigger('click')
    expect(wrapper.find('.world-panel').exists()).toBe(true)
    expect(wrapper.text()).toContain('目标')
    wrapper.unmount()
  })

  it('walking up to an anchor auto-opens the panel the manifest maps to it', async () => {
    const wrapper = await mountShell()

    engine.handlers.onSelect?.('academy')
    await flushPromises()
    expect(wrapper.text()).toContain('洞察')

    engine.handlers.onSelect?.('npc:assistant')
    await flushPromises()
    expect(wrapper.text()).toContain('AI 助手')

    engine.handlers.onSelect?.('npc:postman')
    await flushPromises()
    expect(wrapper.text()).toContain('好友消息')

    // Walking to a plain neighbour (not self, not an NPC/academy) opens nothing extra.
    const before = wrapper.findAll('.world-panel').length
    engine.handlers.onSelect?.('some-neighbour')
    await flushPromises()
    expect(wrapper.findAll('.world-panel')).toHaveLength(before)

    // Walking home (the self resident) opens "today", which is anchored to 'home'.
    engine.handlers.onSelect?.('me')
    await flushPromises()
    expect(wrapper.text()).toContain('今天')
    wrapper.unmount()
  })

  it('pressing R toggles run mode, calls TownGame.setRun and persists the choice', async () => {
    const wrapper = await mountShell()
    expect(engine.game.setRun).toHaveBeenLastCalledWith(false)

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'r' }))
    await flushPromises()
    expect(engine.game.setRun).toHaveBeenLastCalledWith(true)
    expect(JSON.parse(saved.get('better-self:town-immersive') ?? '{}').runMode).toBe(true)

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'R' }))
    await flushPromises()
    expect(engine.game.setRun).toHaveBeenLastCalledWith(false)
    wrapper.unmount()
  })

  it('the run-toggle button in the HUD does the same thing as the shortcut', async () => {
    const wrapper = await mountShell()
    await wrapper.get('.run-toggle').trigger('click')
    expect(engine.game.setRun).toHaveBeenLastCalledWith(true)
    expect(wrapper.get('.run-toggle').attributes('aria-pressed')).toBe('true')
    wrapper.unmount()
  })

  it('restores previously open panels, their positions and run mode on the next visit', async () => {
    saved.set('better-self:town-immersive', JSON.stringify({
      runMode: true,
      openPanels: ['goals'],
      positions: { goals: { x: 77, y: 88 } },
    }))
    const wrapper = await mountShell()
    const panel = wrapper.get('.world-panel')
    const style = panel.attributes('style') ?? ''
    expect(style).toContain('left: 77px')
    expect(style).toContain('top: 88px')
    expect(engine.game.setRun).toHaveBeenLastCalledWith(true)
    wrapper.unmount()
  })

  it('Escape closes the topmost open window first, then exits immersive mode on the next press', async () => {
    const wrapper = await mountShell()
    await dockButton(wrapper, '目标').trigger('click')
    expect(wrapper.find('.world-panel').exists()).toBe(true)

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()
    expect(wrapper.find('.world-panel').exists()).toBe(false)
    expect(routerMock.push).not.toHaveBeenCalled()

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()
    expect(routerMock.push).toHaveBeenCalledWith('/town')
    wrapper.unmount()
  })

  function menuButton(wrapper: Awaited<ReturnType<typeof mountShell>>, text: string) {
    const found = wrapper.findAll('.world-action-item').find(button => button.text().includes(text))
    if (!found) throw new Error(`no action menu item labelled "${text}"`)
    return found
  }

  it('walking up to an NPC pops the world action menu with its panel-open action plus the global ones', async () => {
    const wrapper = await mountShell()
    expect(wrapper.find('.world-action-menu').exists()).toBe(false)

    engine.handlers.onSelect?.('npc:assistant')
    await flushPromises()
    expect(wrapper.find('.world-action-menu').exists()).toBe(true)
    expect(wrapper.text()).toContain('打开AI 助手')
    expect(wrapper.text()).toContain('回到我家')
    wrapper.unmount()
  })

  it('running "回到我家" from the action menu focuses the self resident and shows feedback', async () => {
    const wrapper = await mountShell()
    engine.handlers.onSelect?.('npc:assistant')
    await flushPromises()

    await menuButton(wrapper, '回到我家').trigger('click')
    await flushPromises()
    expect(engine.game.focus).toHaveBeenCalledWith('me')
    expect(wrapper.text()).toContain('回家了')
    wrapper.unmount()
  })

  it('running "切到夜晚" calls TownGame.setNight and toggling again calls it with the opposite value', async () => {
    const wrapper = await mountShell()
    engine.handlers.onSelect?.('npc:assistant')
    await flushPromises()

    const nightButtonText = wrapper.text().includes('切到夜晚') ? '切到夜晚' : '切到白天'
    await menuButton(wrapper, nightButtonText).trigger('click')
    await flushPromises()
    const firstCall = engine.game.setNight.mock.calls.at(-1)?.[0]
    expect(typeof firstCall).toBe('boolean')

    const secondLabel = firstCall ? '切到白天' : '切到夜晚'
    await menuButton(wrapper, secondLabel).trigger('click')
    await flushPromises()
    expect(engine.game.setNight).toHaveBeenLastCalledWith(!firstCall)
    wrapper.unmount()
  })

  it('entering the academy needs no confirmation, but leaving it asks first', async () => {
    const wrapper = await mountShell()
    engine.handlers.onSelect?.('academy')
    await flushPromises()

    await menuButton(wrapper, '去成长学院').trigger('click')
    await flushPromises()
    expect(engine.game.enterAcademy).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('回到小镇')

    // 再次点击"回到小镇"：先展示确认文案，还不应该调用 exitAcademy。
    await menuButton(wrapper, '回到小镇').trigger('click')
    await flushPromises()
    expect(engine.game.exitAcademy).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('确定要离开学院吗？')

    await wrapper.get('.world-action-confirm-buttons button:last-child').trigger('click')
    await flushPromises()
    expect(engine.game.exitAcademy).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('Escape closes the action menu first, ahead of panels and exiting immersive mode', async () => {
    const wrapper = await mountShell()
    await dockButton(wrapper, '目标').trigger('click')
    engine.handlers.onSelect?.('npc:assistant')
    await flushPromises()
    expect(wrapper.find('.world-action-menu').exists()).toBe(true)
    expect(wrapper.find('.world-panel').exists()).toBe(true)

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()
    expect(wrapper.find('.world-action-menu').exists()).toBe(false)
    expect(wrapper.find('.world-panel').exists()).toBe(true)
    expect(routerMock.push).not.toHaveBeenCalled()

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()
    expect(wrapper.find('.world-panel').exists()).toBe(false)
    expect(routerMock.push).not.toHaveBeenCalled()

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()
    expect(routerMock.push).toHaveBeenCalledWith('/town')
    wrapper.unmount()
  })
})
