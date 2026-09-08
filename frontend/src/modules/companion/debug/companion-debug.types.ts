import type { Conversation, ResidentState, World } from '../companion.types'

/**
 * `CompanionService.view()` serializes the whole `CompanionWorld` (see backend
 * town/companion/domain/CompanionWorld.java), but `companion.types.ts` only ever grew the fields
 * the normal `/town` page reads - the four personality dimensions and the model-usage bookkeeping
 * never made it in. Rather than edit that shared file (out of bounds for this batch - the
 * companion page just landed), this debug-only module reads the extra fields straight off the
 * same JSON response. Confirmed against a live `GET /town/companion` on a running dev server
 * (2026-09-08): every field below was present on the actual response, not just the Java source.
 */
export type DebugResidentState = ResidentState & {
  /** Writable per-resident personality (see backend Personality.java) - 0-100, 50 neutral. Nudged
   * slowly out of reflect() by experience; this is the whole reason this page exists. */
  extroversion: number
  conscientiousness: number
  sensitivity: number
  volatility: number
  /** False only for a resident whose personality has never been read once (self-heals to the
   * initial table on first read) - not meaningful once true, but present on the wire. */
  personalitySeeded: boolean
  /** Private per-resident flag: has THIS resident ever let their fondness for that id show in
   * something they said out loud. Never implies the feeling is mutual or known to the other side. */
  affectionExpressed: Record<string, boolean>
  lastSocialAt?: string | null
  lastReflectionAt?: string | null
}

/** `mode` ("rules" vs "model") isn't in the shared Conversation type either - same reasoning as
 * DebugResidentState above. */
export type DebugConversation = Conversation & { mode?: string }

export type DebugWorld = Omit<World, 'residentStates' | 'conversations'> & {
  residentStates: DebugResidentState[]
  conversations?: DebugConversation[]
  modelStatus: string
  modelBudgetDay: string
  modelCallsToday: number
  modelFailuresToday: number
  modelConsecutiveFailures: number
  modelRetryAfter: string | null
  modelConversationsEnabled: boolean
  modelSequence: number
  eventSequence: number
  intentRevision: number
  modelRequestedAt: string | null
  simulatedAt: string | null
  lastEncounterSlot: number
}
