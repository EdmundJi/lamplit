import type { Memory } from '../companion.types'
import type { DebugResidentState } from './companion-debug.types'

/**
 * Mirrors `Personality.INITIAL` in backend/.../town/companion/domain/Personality.java - the
 * one-time seed table a resident's four personality fields are filled from the first time they
 * are ever read (`Personality.of`). Kept here purely so this page can show "initial -> current";
 * the backend field itself (`personalitySeeded` + the mutable ints on `ResidentState`) is the
 * source of truth and this batch does not touch the domain code. If that table ever changes,
 * this one goes stale silently - there is no wire field for "what I started at", by design (the
 * backend deliberately does not carry a second copy of the seed forward). Update both by hand.
 */
export const INITIAL_PERSONALITY: Record<string, [number, number, number, number]> = {
  owner: [78, 85, 48, 45],
  student: [20, 78, 70, 32],
  artist: [62, 25, 88, 72],
  gardener: [42, 60, 28, 18],
}

export const PERSONALITY_DIMENSIONS = [
  { key: 'extroversion', label: '外向' },
  { key: 'conscientiousness', label: '尽责' },
  { key: 'sensitivity', label: '敏感' },
  { key: 'volatility', label: '易感' },
] as const

export type PersonalityDrift = { key: string; label: string; initial: number | null; current: number; delta: number | null }

/** One row per personality dimension for a single resident, current value against its seed. A
 * resident absent from INITIAL_PERSONALITY (the user's own avatar, "self") has no seed to compare
 * against - `initial`/`delta` come back null rather than guessing at neutral 50, so the UI can say
 * "no baseline" instead of drawing a fake arrow. */
export function personalityDrift(state: DebugResidentState): PersonalityDrift[] {
  const initial = INITIAL_PERSONALITY[state.id]
  return PERSONALITY_DIMENSIONS.map((dim, index) => {
    const current = state[dim.key] as number
    const base = initial ? initial[index] : null
    return { key: dim.key, label: dim.label, initial: base, current, delta: base === null ? null : Math.round((current - base) * 10) / 10 }
  })
}

/** All memories for one owner, most recent first. */
export function memoriesByOwner(memories: Memory[]): Map<string, Memory[]> {
  const map = new Map<string, Memory[]>()
  for (const memory of memories) {
    const list = map.get(memory.ownerId)
    if (list) list.push(memory); else map.set(memory.ownerId, [memory])
  }
  for (const list of map.values()) list.sort((a, b) => Date.parse(b.at) - Date.parse(a.at))
  return map
}

export type SharedMemoryGroup = { topicId: string; latestAt: string; entries: Memory[] }

/** The most interesting question this page can answer: the same event, remembered differently.
 * Groups memories that share a topicId across more than one owner (a single-owner topic - nobody
 * else was there, or nobody else's version was worth writing down - is not a divergence to show).
 * Sorted newest-topic-first so a fresh divergence surfaces without scrolling. */
export function sharedMemoryTopics(memories: Memory[]): SharedMemoryGroup[] {
  const byTopic = new Map<string, Memory[]>()
  for (const memory of memories) {
    if (!memory.topicId) continue
    const list = byTopic.get(memory.topicId)
    if (list) list.push(memory); else byTopic.set(memory.topicId, [memory])
  }
  const groups: SharedMemoryGroup[] = []
  for (const [topicId, entries] of byTopic) {
    if (new Set(entries.map(entry => entry.ownerId)).size < 2) continue
    const sorted = entries.slice().sort((a, b) => Date.parse(b.at) - Date.parse(a.at))
    groups.push({ topicId, latestAt: sorted[0].at, entries: sorted })
  }
  return groups.sort((a, b) => Date.parse(b.latestAt) - Date.parse(a.latestAt))
}

export type RelationshipCell = { value: number | null; expressed: boolean }

/** The private, asymmetric relationship grid: matrix[from].get(to) is how `from` privately feels
 * about `to`, and it is never assumed to equal matrix[to].get(from) - that is the entire point of
 * modelling it this way rather than as one shared "closeness" number per pair. `expressed` is a
 * one-way flag too: `from` said it out loud to/about `to`, not that `to` reciprocates or even
 * knows. Diagonal (a resident's opinion of themself) is left out of each row. */
export function relationshipMatrix(states: DebugResidentState[], ids: string[]): Map<string, Map<string, RelationshipCell>> {
  const matrix = new Map<string, Map<string, RelationshipCell>>()
  for (const from of ids) {
    const state = states.find(s => s.id === from)
    const row = new Map<string, RelationshipCell>()
    for (const to of ids) {
      if (to === from) continue
      row.set(to, { value: state?.relationships?.[to] ?? null, expressed: Boolean(state?.affectionExpressed?.[to]) })
    }
    matrix.set(from, row)
  }
  return matrix
}

/** Seconds left in a model-call backoff window, 0 once it has passed or there isn't one. */
export function backoffRemainingSeconds(retryAfter: string | null | undefined, now: number): number {
  if (!retryAfter) return 0
  return Math.max(0, Math.round((Date.parse(retryAfter) - now) / 1000))
}
