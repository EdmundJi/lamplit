import { describe, expect, it } from 'vitest'
import { ref } from 'vue'
import { activityDurationLabel, sceneProjection, stableProjection } from './companion.presentation'

describe('activityDurationLabel: "已经 N 分钟" from the backend\'s activitySince', () => {
  const now = Date.parse('2026-09-14T09:00:00Z')
  it('floors whole minutes since the current activity started', () => {
    expect(activityDurationLabel('2026-09-14T08:47:30Z', now)).toBe('已经 12 分钟')
    expect(activityDurationLabel('2026-09-14T08:59:59Z', now)).toBe('已经 0 分钟')
    expect(activityDurationLabel('2026-09-14T09:00:00Z', now)).toBe('已经 0 分钟')
  })
  it('shows nothing without a real activitySince - null, undefined, or an unparseable value', () => {
    expect(activityDurationLabel(null, now)).toBeUndefined()
    expect(activityDurationLabel(undefined, now)).toBeUndefined()
    expect(activityDurationLabel('not-a-date', now)).toBeUndefined()
  })
  it('never shows a negative duration if the local clock briefly runs ahead of the server', () => {
    expect(activityDurationLabel('2026-09-14T09:05:00Z', now)).toBe('已经 0 分钟')
  })
})

describe('saved-world drawing projections', () => {
  it('keeps the same scene input when only server revision or memories change', () => {
    const world = ref({ revision: 1, residents: [{ id: 'artist', place: 'cafe', action: 'paint' }], memories: ['旧事'] })
    const actors = stableProjection(() => world.value.residents.map(actor => ({ ...actor })))
    const before = actors.value
    world.value = { revision: 2, residents: [{ id: 'artist', place: 'cafe', action: 'paint' }], memories: ['旧事', '听到的一句话'] }
    expect(actors.value).toBe(before)
  })
  it('updates only when an actual drawing input changes', () => {
    const world = ref({ residents: [{ id: 'artist', place: 'street', destination: 'cafe' }] })
    const actors = stableProjection(() => world.value.residents.map(actor => ({ ...actor })))
    const before = actors.value
    world.value.residents[0].destination = 'garden'
    expect(actors.value).not.toBe(before)
    expect(actors.value[0].destination).toBe('garden')
  })
  it('carries the backend room id into scene input', () => {
    const world = ref<any>({ avatar: { id: 'self', name: '我', place: 'home-self', activity: 'rest', label: '歇着' }, residents: [{ id: 'weaver', name: '阿满', role: '做手工的人', place: 'home-artist', activity: 'observe', label: '看看屋里' }], residentStates: [{ id: 'weaver', roomId: 'home-artist-room-weaver' }] })
    expect(sceneProjection(() => world.value).sceneActors.value.find(actor => actor.id === 'weaver')?.roomId).toBe('home-artist-room-weaver')
  })
})
