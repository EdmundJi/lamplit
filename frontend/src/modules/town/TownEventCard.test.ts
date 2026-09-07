import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import TownEventCard from './TownEventCard.vue'
import TownEventInvitation from './TownEventInvitation.vue'
import type { TownEventView } from './town-events'
const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))
const event: TownEventView = { publicId: 'event', kind: 'PARK_WALK', venue: 'park', hostName: '安禾', startsAt: '2026-09-07T18:00:00+08:00', endsAt: '2026-09-07T20:00:00+08:00', dimension: null, phase: 'UPCOMING', response: 'UNDECIDED', attendedAt: null }
beforeEach(() => { vi.resetAllMocks() })
function button(w: ReturnType<typeof mount>, label: string) { return w.findAll('button').find(b => b.text() === label)! }
describe('structured event journey', () => {
  it('keeps RSVP distinct from attendance and preserves local town time', async () => {
    api.post.mockResolvedValue({ ...event, response: 'GOING' })
    const w = mount(TownEventCard, { props: { event } })
    expect(w.text()).toContain('9月7日 18:00')
    await button(w, '想去坐坐').trigger('click'); await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/town/events/event/response', { response: 'GOING' })
    expect(w.text()).not.toContain('你记下了这次相聚')
    expect(button(w, '我参加了，留下回忆')).toBeUndefined()
    await button(w, '去这里走走').trigger('click')
    expect(w.emitted('visit')).toEqual([['park']]); w.unmount()
  })
  it('does not claim success on failure and excludes ended/cancelled actions', async () => {
    api.post.mockRejectedValue(new Error('offline'))
    const w = mount(TownEventCard, { props: { event: { ...event, phase: 'ONGOING' } } })
    await button(w, '我参加了，留下回忆').trigger('click'); await flushPromises()
    expect(w.get('[role="alert"]').text()).toContain('保存结果暂未确认')
    expect(w.emitted('updated')).toBeUndefined()
    await w.setProps({ event: { ...event, phase: 'ENDED' } })
    expect(w.findAll('button')).toHaveLength(0)
    expect(w.text()).toContain('没有留下参加记录也没关系')
    await w.setProps({ event: { ...event, phase: 'CANCELLED' } })
    expect(w.findAll('button')).toHaveLength(0); w.unmount()
  })
  it('updates time-bound actions using the server clock while the card stays open', async () => {
    vi.useFakeTimers()
    try {
      const w = mount(TownEventCard, { props: { event: { ...event, serverTime: '2026-09-07T17:59:59+08:00' } } })
      expect(button(w, '我参加了，留下回忆')).toBeUndefined()
      await vi.advanceTimersByTimeAsync(1500)
      expect(button(w, '我参加了，留下回忆')).toBeDefined()
      await vi.advanceTimersByTimeAsync(2 * 60 * 60 * 1000)
      expect(button(w, '我参加了，留下回忆')).toBeUndefined()
      expect(w.text()).toContain('已经结束'); w.unmount()
    } finally { vi.useRealTimers() }
  })
  it('uses the invitation identifier and ignores a late earlier detail response', async () => {
    let resolve!: (value: TownEventView) => void
    api.get.mockImplementationOnce(() => new Promise(r => { resolve = r })).mockResolvedValueOnce({ ...event, publicId: 'new', hostName: '新主办人' })
    const w = mount(TownEventInvitation, { props: { eventId: 'old' } })
    await w.setProps({ eventId: 'new' }); await flushPromises()
    resolve(event); await flushPromises()
    expect(api.get).toHaveBeenCalledWith('/town/events/new')
    expect(w.text()).toContain('新主办人'); expect(w.text()).not.toContain('安禾'); w.unmount()
  })
})
