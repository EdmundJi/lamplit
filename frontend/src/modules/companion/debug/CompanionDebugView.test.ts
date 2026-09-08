import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CompanionDebugView from './CompanionDebugView.vue'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), delete: vi.fn() }))
vi.mock('../../../shared/api/client', () => ({ api }))

function residentState(overrides: Record<string, unknown> = {}) {
  return {
    id: 'owner', mood: '有点得意', goal: null, thought: '想认真做完', desiredAction: null, positionId: 'cafe-worktable',
    energy: 46.2, social: 96.3, curiosity: 12.2,
    extroversion: 78, conscientiousness: 91, sensitivity: 48, volatility: 45, personalitySeeded: true,
    revision: 3, lastSocialAt: '2026-09-08T09:12:05Z', lastReflectionAt: '2026-09-08T09:10:17Z',
    plan: { id: 'p1', action: 'help', place: 'garden', targetId: 'wish-1', reason: '答应过的帮忙', startedAt: '2026-09-08T09:00:00Z', endsAt: '2026-09-08T09:20:00Z' },
    relationships: { student: 80, artist: 60, gardener: 40 },
    affectionExpressed: { student: true },
    ...overrides,
  }
}

function world(overrides: Record<string, unknown> = {}) {
  return {
    id: 'world', name: '梧桐小街', timezone: 'Asia/Shanghai', revision: 42, joinedAt: '2026-09-08T00:00:00Z', updatedAt: '2026-09-08T09:12:00Z',
    weather: 'sunny', period: 'afternoon', offlineSummary: null,
    avatar: { id: 'self', name: '阿旭', role: '小街住民', place: 'cafe', activity: 'study', label: '安静读书', x: 1, y: 1, until: '' },
    residents: [
      { id: 'owner', name: '阿禾', role: '咖啡馆店主', place: 'garden', activity: 'help', label: '在花园帮忙', x: 1, y: 1, until: '' },
      { id: 'student', name: '小川', role: '备考邻居', place: 'cafe', activity: 'study', label: '在看书', x: 1, y: 1, until: '' },
      { id: 'artist', name: '知夏', role: '插画师', place: 'cafe', activity: 'draw', label: '在画画', x: 1, y: 1, until: '' },
      { id: 'gardener', name: '青叔', role: '园艺爱好者', place: 'garden', activity: 'garden', label: '在浇水', x: 1, y: 1, until: '' },
    ],
    intents: [], diary: [], focus: null,
    simulationVersion: 5, intentRevision: 8, eventSequence: 30, modelSequence: 74, lastEncounterSlot: 12,
    simulatedAt: '2026-09-08T09:12:00Z', modelRequestedAt: '2026-09-08T09:11:54Z', modelBudgetDay: '2026-09-08',
    modelCallsToday: 45, modelFailuresToday: 3, modelConsecutiveFailures: 0, modelRetryAfter: null,
    modelConversationsEnabled: true, modelStatus: '青叔刚接着说了一句',
    residentStates: [
      residentState(),
      residentState({ id: 'student', extroversion: 20, conscientiousness: 78, sensitivity: 70, volatility: 32, relationships: { owner: 55 }, affectionExpressed: {} }),
      residentState({ id: 'artist', extroversion: 62, conscientiousness: 25, sensitivity: 88, volatility: 72, relationships: {}, affectionExpressed: {} }),
      residentState({ id: 'gardener', extroversion: 42, conscientiousness: 60, sensitivity: 28, volatility: 18, relationships: {}, affectionExpressed: {} }),
      { id: 'self', mood: '如常', goal: null, thought: null, desiredAction: null, positionId: 'cafe-worktable', energy: 70, social: 60, curiosity: 60, extroversion: 0, conscientiousness: 0, sensitivity: 0, volatility: 0, personalitySeeded: false, revision: 1, lastSocialAt: null, lastReflectionAt: null, plan: null, relationships: {}, affectionExpressed: {} },
    ],
    projects: [{ id: 'wish-1', title: '给花盆画一个新名字', kind: 'shared', place: 'garden', ownerId: 'owner', status: 'active', progress: 40, needed: 2, contributors: [], objectKind: 'flowers', description: '' }],
    conversations: [{ id: 'talk-1', place: 'cafe', topicId: 'wish-1', participantIds: ['student', 'artist'], status: 'active', startedAt: '2026-09-08T09:00:00Z', updatedAt: '2026-09-08T09:01:00Z', turns: [{ speakerId: 'student', text: '这个名字有点意思。', at: '2026-09-08T09:00:30Z' }, { speakerId: 'artist', text: '我也这么觉得。', at: '2026-09-08T09:00:45Z' }] }],
    events: [],
    objects: [],
    locations: [
      { id: 'street', kind: 'street', ownerId: null }, { id: 'cafe', kind: 'cafe', ownerId: null }, { id: 'garden', kind: 'garden', ownerId: null },
      { id: 'home-owner', kind: 'home', ownerId: 'owner' },
    ],
    positions: [
      { id: 'cafe-worktable', place: 'cafe', kind: 'table', ownerId: null, capacity: 4, occupantIds: ['self', 'student'] },
    ],
    memories: [
      { id: 'm1', ownerId: 'owner', sourceId: 'owner', sourceType: 'seed', at: '2026-09-01T00:00:00Z', text: '搬来前就认识大家。', topicId: undefined, importance: 6 },
      { id: 'm2', ownerId: 'owner', sourceId: 'student', sourceType: 'observed', at: '2026-09-08T09:00:00Z', text: '我看见小川在花园帮了一把。', topicId: 'wish-1', importance: 7 },
      { id: 'm3', ownerId: 'student', sourceId: 'student', sourceType: 'observed', at: '2026-09-08T09:00:05Z', text: '我在花园做了一点事，挺开心。', topicId: 'wish-1', importance: 6 },
      { id: 'm4', ownerId: 'owner', sourceId: 'owner', sourceType: 'reflection', at: '2026-09-08T09:05:00Z', text: '大家真的愿意一起帮忙。', importance: 8 },
    ],
    ...overrides,
  }
}

let wrapper: ReturnType<typeof mount> | undefined
beforeEach(() => {
  vi.useFakeTimers(); vi.setSystemTime(new Date('2026-09-08T09:12:10Z'))
  api.get.mockReset().mockImplementation((path: string) => Promise.resolve(path === '/town/companion' ? { joined: true, world: world() } : []))
})
afterEach(() => { wrapper?.unmount(); vi.useRealTimers() })
async function render() { wrapper = mount(CompanionDebugView); await flushPromises(); return wrapper }

describe('companion debug view', () => {
  it('fetches the same read-only companion snapshot the normal page uses', async () => {
    await render()
    expect(api.get).toHaveBeenCalledWith('/town/companion')
    expect(api.post).not.toHaveBeenCalled()
  })

  it('shows each resident\'s personality drift from their seed values, including a negative drift', async () => {
    const view = await render()
    const text = view.text()
    expect(text).toContain('85 → 91')
    expect(text).toContain('+6')
    const table = view.get('.grid-table')
    expect(table.text()).not.toContain('阿旭')
  })

  it('renders the relationship matrix asymmetrically - A about B need not equal B about A', async () => {
    const view = await render()
    const matrix = view.get('.matrix')
    const rows = matrix.findAll('tbody tr')
    const ownerRow = rows.find(row => row.text().startsWith('阿禾'))!
    const studentRow = rows.find(row => row.text().startsWith('小川'))!
    expect(ownerRow.text()).toContain('80')
    expect(studentRow.text()).toContain('55')
    expect(ownerRow.text()).not.toBe(studentRow.text())
  })

  it('marks a relationship that has actually been said out loud', async () => {
    const view = await render()
    const matrix = view.get('.matrix')
    expect(matrix.find('.expressed').exists()).toBe(true)
  })

  it('groups memories by owner and surfaces the same event as remembered by different people', async () => {
    const view = await render()
    expect(view.text()).toContain('同源记忆对照')
    const group = view.get('.topic-group')
    expect(group.text()).toContain('阿禾')
    expect(group.text()).toContain('小川')
    expect(group.text()).toContain('我看见小川在花园帮了一把')
    expect(group.text()).toContain('我在花园做了一点事')
  })

  it('shows current position, plan and active conversation for a resident', async () => {
    const view = await render()
    const rows = view.findAll('.section:nth-of-type(4) tbody tr')
    const ownerRow = rows.find(row => row.text().includes('阿禾'))!
    expect(ownerRow.text()).toContain('help')
    expect(ownerRow.text()).toContain('答应过的帮忙')
    const studentRow = rows.find(row => row.text().includes('小川'))!
    expect(studentRow.text()).toContain('知夏')
    await studentRow.get('button').trigger('click')
    expect(view.get('.conversation-detail').text()).toContain('这个名字有点意思')
  })

  it('surfaces model usage and backoff status', async () => {
    const view = await render()
    const text = view.text()
    expect(text).toContain('45')
    expect(text).toContain('未退避')
  })

  it('shows a live countdown while a model backoff is active', async () => {
    api.get.mockImplementation((path: string) => Promise.resolve(path === '/town/companion' ? { joined: true, world: world({ modelRetryAfter: '2026-09-08T09:12:20Z' }) } : []))
    const view = await render()
    expect(view.text()).toContain('退避中')
  })
})
