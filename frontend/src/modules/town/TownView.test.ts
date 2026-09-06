import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TownView from './TownView.vue'
import { useTownNpcStore } from './town-npc.store'
import { worldBridgeKey } from './immersive/panel.types'
import type { TownTravel, TownNearby } from './town.engine'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
const router = vi.hoisted(() => ({ push: vi.fn() }))
const engine = vi.hoisted(() => ({
  game: { setNight: vi.fn(), focus: vi.fn(), destroy: vi.fn(), applyModel: vi.fn(), celebrate: vi.fn(), enterAcademy: vi.fn(), exitAcademy: vi.fn(), setRun: vi.fn(), setObservation: vi.fn(), applyNpcs: vi.fn(), travelTo: vi.fn(), exitRoom: vi.fn(), cancelTravel: vi.fn(), interactNearby: vi.fn(), applyEvents: vi.fn() },
  handlers: {
    onRoomChange: undefined as undefined | ((room: string | null) => void),
    onTravelChange: undefined as undefined | ((status: TownTravel | null) => void),
    onNearbyChange: undefined as undefined | ((value: TownNearby | null) => void),
    onInteriorInteract: undefined as undefined | ((action: string, id: string) => void),
    onSelect: undefined as undefined | ((id: string | null) => void),
    onAcademyChange: undefined as undefined | ((inside: boolean) => void),
  },
  createTownGame: vi.fn(),
}))
vi.mock('../../shared/api/client', () => ({ api }))
vi.mock('../../app/router', () => ({ router }))
vi.mock('vue-router', async importOriginal => ({ ...await importOriginal<typeof import('vue-router')>(), useRouter: () => router }))
vi.mock('./immersive/panels/TodayPanel.vue', () => ({ default: { template: '<div data-testid="today-panel">镇内任务</div>' } }))
vi.mock('./immersive/panels/PartnersPanel.vue', () => ({ default: { template: '<div data-testid="partners-panel">镇内伙伴</div>' } }))
vi.mock('./immersive/panels/AiPanel.vue', () => ({ default: { template: '<div data-testid="ai-panel">镇内对话</div>' } }))
vi.mock('./town.engine', () => ({
  createTownGame: engine.createTownGame.mockImplementation(async (
    _el: HTMLElement,
    _model: unknown,
    handlers: typeof engine.handlers,
  ) => {
    Object.assign(engine.handlers, handlers)
    return engine.game
  }),
}))

describe('Town view', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.spyOn(useTownNpcStore(), 'load').mockResolvedValue(undefined)
    for (const fn of Object.values(engine.game)) fn.mockClear()
    router.push.mockReset()
    // GET /town 404s here so the store exercises its legacy multi-request fallback.
    api.get.mockReset().mockImplementation(async (path: string) => {
      if (path === '/town') throw { status: 404, code: 'NOT_FOUND', message: 'not found' }
      if (path === '/me/profile') return { publicId: 'me', displayName: '我', overallLevel: 5, totalExperience: 500, longestStreak: 12 }
      if (path === '/insights/attributes') return { attributes: [{ code: 'KNOWLEDGE', experience: 50 }] }
      if (path.startsWith('/task-schedules')) return [{ status: 'DONE' }]
      if (path === '/town/events') return [{ publicId: 'event-1', kind: 'TEA', venue: 'cafe', hostName: '邻居', startsAt: '2026-09-06T10:00:00', endsAt: null, dimension: null }]
      if (path === '/friends') return { friends: [{ publicId: 'f1', status: 'ACCEPTED' }], incoming: [], outgoing: [] }
      if (path === '/friends/unread-summary') return { totalUnread: 2, kind: 'single', publicId: 'f1', displayName: '阿强' }
      if (path === '/friends/f1') return { publicId: 'f1', displayName: '阿强', overallLevel: 2, totalExperience: 60, longestStreak: 0, attributes: [{ code: 'HEALTH', experience: 30 }], todayTasks: [] }
      if (path === '/town/npc/GUIDE/messages') return []
      if (path === '/town/npc/POSTMAN/messages') return []
      if (path === '/town/reflection/latest') throw { status: 404, code: 'NOT_FOUND', message: 'not found' }
      throw new Error(path)
    })
  })

  it('boots the engine with residents and shows the owner on selection', async () => {
    const wrapper = mount(TownView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    expect(engine.createTownGame).toHaveBeenCalled()
    const model = engine.createTownGame.mock.calls.at(-1)?.[1] as { residents: { publicId: string }[] }
    expect(model.residents.map(item => item.publicId)).toEqual(['me', 'f1'])
    expect(wrapper.text()).toContain('2 户人家')

    engine.handlers.onSelect?.('f1')
    await flushPromises()
    expect(wrapper.text()).toContain('阿强')
    expect(wrapper.text()).toContain('健康')
    expect(wrapper.text()).toContain('今天慢慢来，也可以歇一歇')

    engine.handlers.onSelect?.('academy')
    await flushPromises()
    expect(wrapper.text()).toContain('1 位邻居完成了任务')

    const before = engine.game.setNight.mock.calls.length
    await wrapper.findAll('button').find(button => /切到白天|切到夜晚/.test(button.text()))!.trigger('click')
    expect(engine.game.setNight.mock.calls.length).toBe(before + 1)
    wrapper.unmount()
    expect(engine.game.destroy).toHaveBeenCalled()
  })

  it('renders the NpcDialogue for the guide when selected, with the status-based opener', async () => {
    const wrapper = mount(TownView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()

    engine.handlers.onSelect?.('npc:assistant')
    await flushPromises()

    expect(wrapper.find('.npc-dialogue').exists()).toBe(true)
    expect(wrapper.get('[role="dialog"]').attributes('aria-label')).toContain('小助')
    // reflection endpoint 404s in this test, so the opener falls back to the status-based line.
    expect(wrapper.get('.npc-textbox').text().length).toBeGreaterThan(0)
    wrapper.unmount()
  })

  it('renders the NpcDialogue for the postman when selected, with the unread copy as opener', async () => {
    const wrapper = mount(TownView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()

    engine.handlers.onSelect?.('npc:postman')
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '和邮递员聊聊')!.trigger('click')
    await flushPromises()

    expect(wrapper.find('.npc-dialogue').exists()).toBe(true)
    expect(wrapper.get('.npc-textbox').text()).toContain('2 封新信')
    wrapper.unmount()
  })

  it('closing the dialogue hides the entire panel and reopening restores it', async () => {
    const wrapper = mount(TownView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    engine.handlers.onSelect?.('npc:postman')
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '和邮递员聊聊')!.trigger('click')
    await flushPromises()
    await wrapper.get('.npc-close').trigger('click')
    await flushPromises()
    expect(wrapper.find('.town-panel').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('小镇现状')
    await wrapper.get('[aria-controls="town-info-panel"]').trigger('click')
    expect(wrapper.find('.npc-dialogue').exists()).toBe(true)
    wrapper.unmount()
  })

  it('closes the default panel without opening a replacement on empty selection', async () => {
    const wrapper = mount(TownView, { global: { stubs: { RouterLink: true } } })
    await flushPromises()
    await wrapper.get('[aria-label="关闭面板"]').trigger('click')
    engine.handlers.onSelect?.(null)
    await flushPromises()
    expect(wrapper.find('.town-panel').exists()).toBe(false)
    expect(wrapper.get('[aria-controls="town-info-panel"]').attributes('aria-expanded')).toBe('false')
    await wrapper.get('[aria-controls="town-info-panel"]').trigger('click')
    expect(wrapper.get('.town-panel').text()).toContain('小镇现状')
    await wrapper.get('.town-panel').trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('.town-panel').exists()).toBe(false)
    wrapper.unmount()
  })

  it.each(['home', 'academy', 'gym', 'cafe', 'park'])('walks to %s and waits for engine room state', async place => {
    const wrapper = mount(TownView, { global: { stubs: { RouterLink: true } } })
    await flushPromises()
    const ids = ['home', 'academy', 'gym', 'cafe', 'park']
    await wrapper.get('.town-places').findAll('button')[ids.indexOf(place)]!.trigger('click')
    expect(engine.game.travelTo).toHaveBeenCalledWith(place)
    expect(wrapper.find('.town-panel').exists()).toBe(false)
    expect(wrapper.findAll('button').some(btn => btn.text() === '回到小镇')).toBe(false)
    if (place !== 'park') {
      engine.handlers.onRoomChange?.(place)
      await flushPromises()
      await wrapper.findAll('button').find(btn => btn.text() === '回到小镇')!.trigger('click')
      expect(engine.game.exitRoom).toHaveBeenCalledTimes(1)
      engine.handlers.onRoomChange?.(null)
      await flushPromises()
      expect(wrapper.findAll('button').some(btn => btn.text() === '回到小镇')).toBe(false)
    }
    wrapper.unmount()
  })

  it('shows navigation feedback, cancellation and nearby interaction', async () => {
    const wrapper = mount(TownView, { global: { stubs: { RouterLink: true } } })
    await flushPromises()
    engine.handlers.onTravelChange?.({ place: 'cafe', label: '咖啡馆', phase: 'walking' })
    await flushPromises()
    expect(wrapper.get('.town-travel-status').text()).toContain('正在走向咖啡馆')
    await wrapper.findAll('button').find(btn => btn.text() === '取消前往')!.trigger('click')
    expect(engine.game.cancelTravel).toHaveBeenCalledOnce()
    for (const [phase, copy] of [['arrived', '已到达咖啡馆'], ['blocked', '路被挡住了']] as const) {
      engine.handlers.onTravelChange?.({ place: 'cafe', label: '咖啡馆', phase })
      await flushPromises()
      expect(wrapper.get('.town-travel-status').text()).toContain(copy)
    }
    engine.handlers.onTravelChange?.(null)
    engine.handlers.onNearbyChange?.({ id: 'cafe', label: '咖啡馆', action: '进入' })
    await flushPromises()
    expect(wrapper.find('.town-travel-status').exists()).toBe(false)
    await wrapper.findAll('button').find(btn => btn.text().includes('进入 · 咖啡馆'))!.trigger('click')
    expect(engine.game.interactNearby).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it.each([['今天', 'today'], ['伙伴', 'partners'], ['AI 助手', 'ai']])('opens %s inside the town', async (label, key) => {
    const wrapper = mount(TownView, { global: { stubs: { RouterLink: true } } })
    await flushPromises()
    await wrapper.get('.town-places').findAll('button').find(btn => btn.text() === label)!.trigger('click')
    await flushPromises()
    expect(wrapper.find(`[data-testid="${key}-panel"]`).exists()).toBe(true)
    expect(router.push).not.toHaveBeenCalled()
    await wrapper.get('[aria-label="关闭面板"]').trigger('click')
    await wrapper.get('[aria-controls="town-info-panel"]').trigger('click')
    await flushPromises()
    expect(wrapper.find(`[data-testid="${key}-panel"]`).exists()).toBe(true)
    wrapper.unmount()
  })

  it('opens desk and pet furniture actions in existing town panels', async () => {
    const wrapper = mount(TownView, { global: { stubs: { RouterLink: true } } })
    await flushPromises()
    engine.handlers.onInteriorInteract?.('home.open-desk', 'desk')
    await flushPromises()
    expect(wrapper.find('[data-testid="today-panel"]').exists()).toBe(true)
    engine.handlers.onInteriorInteract?.('home.open-pet-house', 'pet')
    await flushPromises()
    expect(wrapper.find('[data-testid="partners-panel"]').exists()).toBe(true)
    expect(router.push).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('loads events into the engine and walks to an invitation venue', async () => {
    const wrapper = mount(TownView, { global: { stubs: { RouterLink: true } } })
    await flushPromises()
    expect(engine.game.applyEvents).toHaveBeenCalledWith(expect.arrayContaining([expect.objectContaining({ publicId: 'event-1' })]))
    await wrapper.get('.town-places').findAll('button').find(btn => btn.text() === '活动')!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.town-events-board').exists()).toBe(true)
    await wrapper.get('[aria-label="关闭活动公告"]').trigger('click')
    expect(wrapper.find('.town-panel').exists()).toBe(false)
    await wrapper.get('[aria-controls="town-info-panel"]').trigger('click')
    await flushPromises()
    await wrapper.get('.town-events-board').findAll('button').find(btn => btn.text() === '去这里走走')!.trigger('click')
    expect(engine.game.travelTo).toHaveBeenCalledWith('cafe')
    expect(wrapper.find('.town-panel').exists()).toBe(false)
    wrapper.unmount()
  })

  it('handles registered travel and cafe AI actions through the shared bridge', async () => {
    const wrapper = mount(TownView, { global: { stubs: { RouterLink: true } } })
    await flushPromises()
    const bridge = (wrapper.vm.$ as unknown as { provides: Record<symbol, import('./immersive/panel.types').WorldBridge> }).provides[worldBridgeKey]!
    await bridge.run('world.go-home')
    expect(engine.game.travelTo).toHaveBeenCalledWith('home')
    await bridge.run('cafe.open-ai')
    await flushPromises()
    expect(wrapper.find('[data-testid="ai-panel"]').exists()).toBe(true)
    expect(router.push).not.toHaveBeenCalled()
    wrapper.unmount()
  })

})
