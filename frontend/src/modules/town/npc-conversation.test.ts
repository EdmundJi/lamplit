import { describe, expect, it } from 'vitest'
import { conversationDeparture } from './npc-conversation'
import type { TownNpcView } from './town-npc.types'
const npc = { code: 'LIN', layer: 2, schedule: [{ startHour: 0, endHour: 24, place: 'academy', activity: 'reading' }] } as TownNpcView
describe('NPC local conversation cadence', () => {
  it('gives the user time to start and does not arbitrarily interrupt first-layer support', () => {
    expect(conversationDeparture(npc, 600, 14000, 'cafe')).toBeNull()
    expect(conversationDeparture({ ...npc, layer: 1 }, 600, 999999, 'cafe')).toBeNull()
  })
  it('explains a changed errand before leaving', () => {
    expect(conversationDeparture(npc, 600, 16000, 'cafe')).toContain('学院')
    expect(conversationDeparture(npc, 600, 30000, 'academy')).toBeNull()
    expect(conversationDeparture(npc, 600, 180000, 'academy')).toContain('手边的事')
  })
})
