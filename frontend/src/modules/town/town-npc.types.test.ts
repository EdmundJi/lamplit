import { describe, expect, it } from 'vitest'
import type {
  InitiativeBudget,
  NpcActivity,
  NpcPlace,
  NpcScheduleSlot,
  NpcTalkingPoint,
  TownNpcLayer,
  TownNpcsResponse,
  TownNpcView,
} from './town-npc.types'

// This file is type-only at runtime, so these tests exist to pin the literal unions from
// CONTRACT.md §6 to a concrete list: if someone silently drops or renames a value, this fails
// loudly instead of only showing up as a type error somewhere unrelated.

const PLACES: NpcPlace[] = ['home', 'academy', 'gym', 'cafe', 'park', 'plaza', 'street']
const ACTIVITIES: NpcActivity[] = [
  'idle',
  'walking',
  'reading',
  'sit',
  'phone',
  'watering',
  'chopping',
  'fishing',
  'harvesting',
  'digging',
]
const LAYERS: TownNpcLayer[] = [1, 2, 3]

describe('town-npc.types', () => {
  it('enumerates exactly the 7 CONTRACT.md §6 place codes', () => {
    expect(PLACES).toHaveLength(7)
    expect(new Set(PLACES).size).toBe(7)
  })

  it('enumerates exactly the 10 CONTRACT.md §6 activity codes', () => {
    expect(ACTIVITIES).toHaveLength(10)
    expect(new Set(ACTIVITIES).size).toBe(10)
  })

  it('has exactly 3 NPC layers', () => {
    expect(LAYERS).toEqual([1, 2, 3])
  })

  it('accepts a fully-formed schedule slot', () => {
    const slot: NpcScheduleSlot = { startHour: 9, endHour: 12, place: 'academy', activity: 'reading' }
    expect(PLACES).toContain(slot.place)
    expect(ACTIVITIES).toContain(slot.activity)
  })

  it('accepts a fully-formed talking point', () => {
    const point: NpcTalkingPoint = { factId: '01J000000000000000000000', text: '听说小吉最近老往健身房跑', hops: 2, salience: 0.61 }
    expect(point.hops).toBeGreaterThanOrEqual(0)
    expect(point.salience).toBeGreaterThanOrEqual(0)
    expect(point.salience).toBeLessThanOrEqual(1)
  })

  it('accepts a fully-formed NPC view and initiative budget, matching the §6 sample payload', () => {
    const budget: InitiativeBudget = { limit: 3, used: 0 }
    const npc: TownNpcView = {
      code: 'KE_YUN',
      displayName: '柯云',
      layer: 2,
      sprite: 'c07',
      dimension: 'KNOWLEDGE',
      interests: { KNOWLEDGE: 0.4, HEALTH: 0.1, CAREER: 0.2, RELATIONSHIP: 0.2, WELLBEING: 0.1 },
      affinityToPlayer: 0.42,
      mood: { valence: 0.3, energy: 0.6 },
      schedule: [
        { startHour: 6, endHour: 9, place: 'home', activity: 'idle' },
        { startHour: 9, endHour: 12, place: 'academy', activity: 'reading' },
      ],
      talkingPoints: [{ factId: '01J000000000000000000000', text: '听说小吉最近老往健身房跑', hops: 2, salience: 0.61 }],
    }
    const response: TownNpcsResponse = { npcs: [npc], initiativeBudget: budget }
    expect(response.npcs[0].code).toBe('KE_YUN')
    expect(response.initiativeBudget.limit).toBe(3)
  })

  it('allows layer 1/3 NPCs to have a null dimension', () => {
    const guide: TownNpcView = {
      code: 'GUIDE',
      displayName: '小助',
      layer: 1,
      sprite: 'npc_scout',
      dimension: null,
      interests: {},
      affinityToPlayer: 0,
      mood: { valence: 0, energy: 0 },
      schedule: [{ startHour: 0, endHour: 24, place: 'academy', activity: 'idle' }],
      talkingPoints: [],
    }
    expect(guide.dimension).toBeNull()
    expect(guide.talkingPoints).toHaveLength(0)
  })
})
