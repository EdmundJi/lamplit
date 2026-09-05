import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TownView from './TownView.vue'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
const router = vi.hoisted(() => ({ push: vi.fn() }))
const engine = vi.hoisted(() => ({
  game: { setNight: vi.fn(), focus: vi.fn(), destroy: vi.fn(), applyModel: vi.fn(), celebrate: vi.fn(), enterAcademy: vi.fn(), exitAcademy: vi.fn() },
  handlers: {
    onSelect: undefined as undefined | ((id: string | null) => void),
    onAcademyChange: undefined as undefined | ((inside: boolean) => void),
  },
  createTownGame: vi.fn(),
}))
vi.mock('../../shared/api/client', () => ({ api }))
vi.mock('../../app/router', () => ({ router }))
vi.mock('./town.engine', () => ({
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

describe('Town view', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    for (const fn of Object.values(engine.game)) fn.mockClear()
    router.push.mockReset()
    // GET /town 404s here so the store exercises its legacy multi-request fallback.
    api.get.mockReset().mockImplementation(async (path: string) => {
      if (path === '/town') throw { status: 404, code: 'NOT_FOUND', message: 'not found' }
      if (path === '/me/profile') return { publicId: 'me', displayName: '我', overallLevel: 5, totalExperience: 500, longestStreak: 12 }
      if (path === '/insights/attributes') return { attributes: [{ code: 'KNOWLEDGE', experience: 50 }] }
      if (path.startsWith('/task-schedules')) return [{ status: 'DONE' }]
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
    expect(wrapper.text()).toContain('今天还没开张')

    engine.handlers.onSelect?.('academy')
    await flushPromises()
    expect(wrapper.text()).toContain('1 位邻居完成了任务')

    const before = engine.game.setNight.mock.calls.length
    await wrapper.find('button.secondary').trigger('click')
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
  })

  it('renders the NpcDialogue for the postman when selected, with the unread copy as opener', async () => {
    const wrapper = mount(TownView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()

    engine.handlers.onSelect?.('npc:postman')
    await flushPromises()

    expect(wrapper.find('.npc-dialogue').exists()).toBe(true)
    expect(wrapper.get('.npc-textbox').text()).toContain('2 封新信')
  })

  it('closing the dialogue clears the selection', async () => {
    const wrapper = mount(TownView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    engine.handlers.onSelect?.('npc:postman')
    await flushPromises()
    await wrapper.get('.npc-close').trigger('click')
    await flushPromises()
    expect(wrapper.find('.npc-dialogue').exists()).toBe(false)
  })

  it('the 去学院 button enters the academy interior instead of only panning', async () => {
    const wrapper = mount(TownView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()

    const academyButton = wrapper.findAll('button.secondary').find(btn => btn.text().includes('去学院'))
    expect(academyButton).toBeTruthy()
    await academyButton!.trigger('click')

    expect(engine.game.enterAcademy).toHaveBeenCalledTimes(1)
    expect(engine.game.focus).not.toHaveBeenCalledWith('academy')

    // The engine reports the transition back through onAcademyChange; the button relabels and
    // now exits instead.
    engine.handlers.onAcademyChange?.(true)
    await flushPromises()
    const backButton = wrapper.findAll('button.secondary').find(btn => btn.text().includes('回到小镇'))
    expect(backButton).toBeTruthy()
    await backButton!.trigger('click')
    expect(engine.game.exitAcademy).toHaveBeenCalledTimes(1)
  })
})
