import { describe, expect, it } from 'vitest'
import { ref } from 'vue'
import { stableProjection } from './companion.presentation'

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
})
