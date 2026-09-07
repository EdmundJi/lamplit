import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import NeighbourSchedule from './NeighbourSchedule.vue'
import type { TownNpcView } from '../town-npc.types'

const neighbour = {
  code: 'KE_YUN', displayName: '柯云', layer: 2, schedule: [],
  sprite: 'c01', dimension: 'KNOWLEDGE', interests: {}, affinityToPlayer: 0,
  mood: { valence: 0, energy: 0.5 }, talkingPoints: [],
  dayPlan: { date: '2026-09-07', errands: [
    { place: 'home', activity: 'idle', startMinute: 0, endMinute: 598, priority: 1, origin: 'RHYTHM' },
    { place: 'academy', activity: 'reading', startMinute: 600, endMinute: 720, priority: 1, origin: 'RHYTHM' },
    { place: 'home', activity: 'idle', startMinute: 722, endMinute: 1440, priority: 1, origin: 'RHYTHM' },
  ], legs: [
    { fromPlace: 'home', toPlace: 'academy', departMinute: 598, arriveMinute: 600 },
    { fromPlace: 'academy', toPlace: 'home', departMinute: 720, arriveMinute: 722 },
  ] },
} as TownNpcView

describe('NeighbourSchedule truthful destinations', () => {
  it('navigates to the actual public venue and states the planned departure', async () => {
    const wrapper = mount(NeighbourSchedule, { props: { npcs: [neighbour], time: '10:30' } })
    expect(wrapper.text()).toContain('在学院')
    expect(wrapper.text()).toContain('计划待到 12:00')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('visit')).toEqual([['academy']])
  })
  it('shows a future stop separately from the current home and permits early arrival', () => {
    const wrapper = mount(NeighbourSchedule, { props: { npcs: [neighbour], time: '09:00' } })
    expect(wrapper.text()).toContain('在家里')
    expect(wrapper.text()).toContain('下一站 10:00 · 学院')
    expect(wrapper.get('button').text()).toBe('先去下一站')
  })
  it('does not direct users to an old venue after the neighbour returns home', () => {
    const wrapper = mount(NeighbourSchedule, { props: { npcs: [neighbour], time: '23:00' } })
    expect(wrapper.text()).toContain('今天没有更多外出安排')
    expect(wrapper.findAll('button')).toHaveLength(1)
    expect(wrapper.get('button').text()).toBe('去他家门口')
    expect(wrapper.text()).toContain('多数邻居在家休息')
  })
})
