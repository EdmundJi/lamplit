import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CompanionView from './CompanionView.vue'
import { useTownUi } from './town-ui.store'
const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), delete: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))
const actor = { id: 'self', name: '我', role: 'user', place: 'cafe', label: '安静读书', activity: 'study', x: 1, y: 1, until: '' }
function snapshot() {
  return { joined: true, world: { id: 'world', name: '梧桐小街', timezone: 'Asia/Shanghai', revision: 1, joinedAt: '2026-09-08T00:00:00Z', updatedAt: '2026-09-08T00:00:00Z', weather: 'sunny', period: 'morning', avatar: actor,
    residents: [{ ...actor, id: 'owner', name: '阿禾', role: '店主' }], intents: [{ id: 'focus-1', taskId: 'todo-1', kind: 'focus', priority: 'explicit', status: 'active', feedback: '开始了', createdAt: '2026-09-08T00:00:00Z' }],
    memories: [{ id: 'memory', ownerId: 'owner', sourceId: 'student', sourceType: 'heard', text: '听说海报快画好了。', at: '2026-09-08T00:00:00Z' }], diary: [], offlineSummary: null,
    locations: [{ id: 'cafe', kind: 'cafe', ownerId: null }, { id: 'shop', kind: 'shop', ownerId: null }, { id: 'home-owner', kind: 'home', ownerId: 'owner' }],
    rooms: [{ id: 'home-owner-room-owner', buildingId: 'home-owner', kind: 'bedroom', residentIds: ['owner'] }],
    positions: [{ id: 'shop-workbench', place: 'shop', kind: 'workbench', ownerId: null, capacity: 1, occupantIds: [], waitingIds: [], condition: 'usable' }],
    objects: [{ id: 'shop-toolkit', kind: 'tool', place: 'shop', label: '旧工具箱', state: '少了一把螺丝刀', projectId: null, ownerId: 'owner', holderId: null }],
    focus: { taskId: 'todo-1', startedAt: '2026-09-08T00:00:00Z', endsAt: '2026-09-08T00:00:01Z' } } }
}
let wrapper: ReturnType<typeof mount> | undefined
beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  vi.useFakeTimers(); vi.setSystemTime(new Date('2026-09-08T00:00:00Z'))
  api.get.mockReset().mockImplementation((path: string) => Promise.resolve(path === '/town/companion' ? snapshot() : path.startsWith('/task-schedules') ? [{ publicId: 'schedule-1', taskPublicId: 'todo-1', taskTitle: '复习一章', plannedStartAt: '2026-09-08T00:00:00Z', status: 'PLANNED' }] : []))
  api.post.mockReset().mockImplementation((path: string) => Promise.resolve(path === '/town/companion/advance' ? snapshot() : { scheduleStatus: 'DONE', eventPublicId: 'event-1' }))
})
afterEach(() => { wrapper?.unmount(); vi.useRealTimers() })
async function render() { wrapper = mount(CompanionView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } }); await flushPromises(); return wrapper }
/** CompanionScene itself is mounted by TownStage now (docked strip and fullscreen /town share one
 * instance) - selecting a resident from outside the scene now goes through the same shared store
 * TownStage's own scene click handlers write to, instead of a button the scene used to render. */
async function selectResident(id: string) { useTownUi().selectedResident = id; await flushPromises() }
describe('companion page task boundary', () => {
  it('requires a user click to complete a real task after a focus deadline', async () => {
    const view = await render()
    await vi.advanceTimersByTimeAsync(2000)
    expect(view.get('[role="timer"]').text()).toBe('00:00')
    expect(api.post.mock.calls.some(call => call[0].includes('/events'))).toBe(false)
    await view.findAll('button').find(button => button.text().includes('我已完成这项 Todo'))!.trigger('click'); await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/task-schedules/schedule-1/events', { eventType: 'COMPLETED' }, expect.objectContaining({ 'Idempotency-Key': expect.any(String) }))
  })
  it('restores the explicit completion action from saved focus history after a reload', async () => {
    const saved = snapshot()
    const finished = { ...saved, world: { ...saved.world, focus: null, intents: saved.world.intents.map(intent => ({ ...intent, status: 'done' })) } }
    api.get.mockImplementation((path: string) => Promise.resolve(path === '/town/companion' ? finished : path.startsWith('/task-schedules') ? [{ publicId: 'schedule-1', taskPublicId: 'todo-1', taskTitle: '复习一章', plannedStartAt: '2026-09-08T00:00:00Z', status: 'PLANNED' }] : []))
    api.post.mockResolvedValue(finished)
    const view = await render()
    expect(view.get('.finished-focus').text()).toContain('复习一章')
    expect(view.get('.finished-focus').text()).toContain('我已完成这项 Todo')
    expect(api.post.mock.calls.some(call => call[0].includes('/events'))).toBe(false)
  })

  it('can put away active focus controls while the saved timer keeps running', async () => {
    const view = await render()
    expect(view.find('.focus-card').exists()).toBe(true)
    await view.get('.focus-toggle').trigger('click')
    expect(view.get('.focus-toggle').attributes('aria-expanded')).toBe('false')
    expect(view.find('.focus-card').exists()).toBe(false)
    expect(view.find('[role="timer"]').exists()).toBe(true)
    await view.get('.focus-toggle').trigger('click')
    expect(view.get('.focus-toggle').attributes('aria-expanded')).toBe('true')
    expect(view.find('.focus-card').exists()).toBe(true)
    await view.get('button[aria-label="收起专注"]').trigger('click')
    expect(view.find('.focus-card').exists()).toBe(false)
    expect(api.post.mock.calls.some(call => call[0].includes('/intents'))).toBe(false)
  })

  it('keeps text bubbles off by default and stores only the explicit UI preference', async () => {
    const view = await render()
    const ui = useTownUi()
    expect(ui.textBubbles).toBe(false)
    await view.get('[aria-label="打开文字气泡"]').trigger('click')
    expect(ui.textBubbles).toBe(true)
    expect(localStorage.getItem('better-self:town-text-bubbles:guest')).toBe('on')
    expect(api.post.mock.calls.some(call => call[0].includes('/intents'))).toBe(false)
    expect(view.find('.neighbors').exists()).toBe(false)
  })

  it('browses real public places, rooms and residents without writing a life command', async () => {
    const view = await render()
    await view.get('button[aria-label="查看地点与居民"]').trigger('click')
    expect(view.get('[data-testid="place-browser"]').isVisible()).toBe(true)
    const shop = view.get('button[data-place-id="shop"]')
    await shop.trigger('click')
    expect(useTownUi().selectedPlace).toBe('shop')
    expect(view.get('.place-now').text()).toContain('工具台')
    expect(view.get('.place-now').text()).toContain('旧工具箱')
    expect(api.post.mock.calls.some(call => call[0].includes('/intents'))).toBe(false)
  })

  it('opens the whole conversation directly and keeps the control panels mutually exclusive', async () => {
    const base = snapshot()
    const saved = { ...base, world: { ...base.world, conversations: [{ id: 'talk', place: 'cafe', topicId: 'poster', participantIds: ['owner', 'artist'], status: 'active', turns: [{ speakerId: 'owner', text: '窗边可以留一张桌子。', at: '2026-09-08T00:00:00Z' }, { speakerId: 'artist', text: '我把海报带过去。', at: '2026-09-08T00:00:01Z' }, { speakerId: 'owner', text: '那我给你准备一杯水。', at: '2026-09-08T00:00:02Z' }] }] } }
    api.get.mockImplementation((path: string) => Promise.resolve(path === '/town/companion' ? saved : []))
    api.post.mockResolvedValue(saved)
    const view = await render()
    expect(view.find('.focus-card').exists()).toBe(true)
    await view.get('.scene-note').trigger('click')
    expect(view.find('.focus-card').exists()).toBe(false)
    expect(view.findAll('.conversation-panel .conversation-turn')).toHaveLength(3)
    expect(view.findAll('.conversation-participants button')).toHaveLength(2)
    await view.get('.conversation-participants button').trigger('click')
    expect(view.find('.conversation-panel').exists()).toBe(false)
    expect(view.get('.person-panel h2').text()).toBe('阿禾')
    expect(api.post.mock.calls.some(call => call[0].includes('/intents'))).toBe(false)
  })

  it('sends a free inner thought and priority to the authoritative intention endpoint', async () => {
    const view = await render()
    await view.get('#inner-thought').setValue('我现在想去咖啡馆看看海报')
    await view.get('select[aria-label="念头优先级"]').setValue('explicit')
    api.post.mockResolvedValue(snapshot())
    await view.get('.thought-dock form').trigger('submit'); await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/town/companion/intents', expect.objectContaining({ kind: 'thought', text: '我现在想去咖啡馆看看海报', priority: 'explicit', id: expect.any(String) }))
    expect((view.get('#inner-thought').element as HTMLInputElement).value).toBe('')
  })

  it('connects a saved project story to a real visit intention', async () => {
    const base = snapshot()
    const saved = { ...base, world: { ...base.world, projects: [{ id: 'poster', title: '读书会的海报', description: '知夏想给晚会画一张海报。', place: 'cafe', ownerId: 'artist', status: 'active', progress: 25, contributors: ['owner'], objectKind: 'poster' }], events: [{ id: 'event', at: '2026-09-08T00:00:00Z', type: 'create', place: 'cafe', actorIds: ['owner'], text: '阿禾给知夏留了一张靠窗的桌子。', projectId: 'poster' }] } }
    api.get.mockImplementation((path: string) => Promise.resolve(path === '/town/companion' ? saved : []))
    api.post.mockResolvedValue(saved)
    const view = await render()
    await view.findAll('button').find(button => button.text().includes('小街正在发生'))!.trigger('click')
    await view.get('.project-story').trigger('click')
    expect(view.get('.story-timeline').text()).toContain('阿禾给知夏留了一张靠窗的桌子')
    await view.findAll('button').find(button => button.text().includes('我也想过去看看'))!.trigger('click'); await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/town/companion/intents', expect.objectContaining({ kind: 'thought', text: '我想去咖啡馆看看读书会的海报', priority: 'explicit' }))
  })

  it('shows a changed opinion with the actual memories that support it', async () => {
    const base = snapshot()
    const saved = { ...base, world: { ...base.world, projects: [{ id: 'wish-135', title: '窗边的读书晚会' }], residentStates: [{ id: 'owner', goal: 'wish-135', thought: '先把书摆好', relationships: {}, plan: null }], memories: [{ id: 'seen', ownerId: 'owner', sourceId: 'artist', sourceType: 'observed', text: '知夏把画好的海报带进了咖啡馆。', at: '2026-09-08T00:00:00Z' }, { id: 'opinion', ownerId: 'owner', sourceId: 'owner', sourceType: 'reflection', text: '原来大家真的愿意一起准备这场读书会。', evidenceIds: ['seen'], at: '2026-09-08T00:01:00Z' }] } }
    saved.world.memories.push({ ...saved.world.memories[1], id: 'opinion-copy' })
    api.get.mockImplementation((path: string) => Promise.resolve(path === '/town/companion' ? saved : []))
    api.post.mockResolvedValue(saved)
    const view = await render()
    await selectResident('owner')
    expect(view.get('.personal-plan').text()).toContain('窗边的读书晚会')
    expect(view.get('.personal-plan').text()).not.toContain('wish-135')
    expect(view.findAll('.opinions article')).toHaveLength(1)
    expect(view.get('.opinions').text()).toContain('现在的看法')
    expect(view.get('.opinions').text()).toContain('原来大家真的愿意')
    expect(view.get('.evidence').text()).toContain('知夏把画好的海报')
    expect(view.get('.evidence').text()).toContain('亲身经历')
  })

  it('shows a resident-owned through-line and a real paused action without promising a recovery', async () => {
    const base = snapshot()
    const saved = { ...base, world: { ...base.world,
      residents: [{ ...base.world.residents[0], role: '受托照看吧台', activity: 'tend', label: '替阿禾照看吧台' }, { ...actor, id: 'student', name: '小川', role: '备考邻居' }],
      residentStates: [{ id: 'owner', goal: 'old-project', thought: '先照应一下柜台。', occupation: '暂时帮邻居照看咖啡馆', careerIntent: { id: 'career-owner', purpose: '试着把咖啡馆经营下去', status: 'active' }, lifeIntent: { id: 'life-owner', purpose: '把招贴慢慢改完', status: 'active' }, suspendedAction: { plan: { id: 'old-plan', action: 'create', place: 'cafe', reason: '把窗边那张招贴改完' } }, relationships: { student: 4 }, plan: { id: 'service', action: 'tend', place: 'cafe', targetId: null, reason: '柜台前有人在等', startedAt: '2026-09-08T00:00:00Z', endsAt: '2026-09-08T00:01:00Z' } }],
    } }
    api.get.mockImplementation((path: string) => Promise.resolve(path === '/town/companion' ? saved : []))
    api.post.mockResolvedValue(saved)
    const view = await render()
    await selectResident('owner')
    expect(view.get('.personal-plan').text()).toContain('长期想走的方向')
    expect(view.get('.personal-plan').text()).toContain('试着把咖啡馆经营下去')
    expect(view.get('.personal-plan').text()).toContain('眼前在惦记')
    expect(view.get('.personal-plan').text()).toContain('把招贴慢慢改完')
    expect(view.get('.paused-action').text()).toBe('暂放着：把窗边那张招贴改完')
    expect(view.get('.paused-action').text()).not.toContain('会回来')
    expect(view.get('.person-identity').text()).toContain('受托照看吧台')
    expect(view.get('.current-action').text()).toContain('替阿禾照看吧台')
    expect(view.get('.relationship-list').text()).toContain('有点疏远')
  })

  it('does not repeat an unchanged career direction as a second current-life paragraph', async () => {
    const base = snapshot()
    const saved = { ...base, world: { ...base.world, residentStates: [{
      id: 'owner', goal: null, thought: '先歇一会儿。', occupation: '照看花草和邻里的小事',
      careerIntent: { id: 'career-owner', purpose: '照看花草和邻里的小事', status: 'active' },
      lifeIntent: { id: 'life-owner', purpose: '照看花草和  邻里的小事', status: 'active' },
      relationships: {}, plan: null,
    }] } }
    api.get.mockImplementation((path: string) => Promise.resolve(path === '/town/companion' ? saved : []))
    api.post.mockResolvedValue(saved)
    const view = await render()
    await selectResident('owner')
    expect(view.get('.personal-plan').text()).toContain('长期想走的方向')
    expect(view.get('.personal-plan').text()).not.toContain('眼前在惦记')
    expect(view.find('.life-thread').exists()).toBe(false)
  })

  it('exposes memory provenance and allows quiet mode to hide the detail', async () => {
    const view = await render()
    await selectResident('owner')
    expect(view.get('.memory-list').text()).toContain('转述')
    expect(view.get('.memory-list').text()).toContain('听说海报快画好了')
    await view.findAll('button').find(button => button.text() === '安静模式')!.trigger('click')
    expect(view.find('.memory-list').exists()).toBe(false)
    expect(view.find('.thought-dock').exists()).toBe(false)
  })
})
