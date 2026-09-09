export type IntentKind = 'focus' | 'rest' | 'walk' | 'home' | 'flowers' | 'water' | 'thought'
// The backend's TownPlaces now hands out per-resident homes ("home-owner", "home-self", ...)
// alongside the three shared places, so this is no longer a closed set of four literals.
export type Actor = { id: string; name: string; role: string; place: string; activity: string; label: string; x: number; y: number; until: string }
/** A place a resident can be: the three shared places (street/cafe/garden), or one resident's own
 * home. `ownerId` is null for a shared place. Mirrors CompanionWorld.Location on the backend. */
export type Location = { id: string; kind: string; ownerId: string | null }
/** A specific spot inside a location - a bed, a window seat, a shared table. `ownerId` null means
 * anyone can sit; `occupantIds.length` is capped by `capacity`. Mirrors CompanionWorld.Position. */
export type Position = { id: string; place: string; kind: string; ownerId: string | null; capacity: number; occupantIds: string[] }
export type Intent = { id: string; kind: IntentKind; priority: 'explicit' | 'passing'; status: 'pending' | 'active' | 'done' | 'cancelled'; feedback: string; createdAt: string; taskId?: string | null; text?: string; resolvedKind?: string }
export type Memory = { id: string; ownerId: string; sourceId: string; sourceType: 'seed' | 'observed' | 'heard' | 'reflection'; topicId?: string; evidenceIds?: string[]; importance?: number; at: string; text: string }
export type World = {
  id: string; name: string; timezone: string; joinedAt: string; updatedAt: string; revision: number
  weather: 'sunny' | 'rain'; period: 'morning' | 'afternoon' | 'evening' | 'night'
  avatar: Actor; residents: Actor[]; intents: Intent[]; diary: { id: string; at: string; text: string }[]
  simulationVersion?: number; residentStates?: ResidentState[]; projects?: Project[]; conversations?: Conversation[]; events?: WorldEvent[]; objects?: WorldObject[]
  cafeOpenMinute?: number; cafeCloseMinute?: number; cafeStatus?: 'open' | 'closing' | 'closed'; cafeStatusChangedAt?: string | null
  // Structure/ownership only, from TownPlaces; may be absent on a save the backend hasn't repaired
  // yet (seeded lazily on the next advance()), so the frontend must keep working without them.
  locations?: Location[]; positions?: Position[]
  memories: Memory[]; focus: { taskId: string; startedAt: string; endsAt: string } | null; offlineSummary: string | null
}
export type Snapshot = { joined: boolean; world: World | null }
export type IntentInput = { id: string; kind: IntentKind; priority: 'explicit' | 'passing'; taskId?: string; durationMinutes?: number; text?: string }
export type ResidentState = {
  id: string; energy: number; social: number; curiosity: number; mood: string; goal: string; thought: string
  /** A resident's own, longer-lived direction. It is intentionally separate from the short plan
   * displayed in the street: an interruption should not make a whole day look like a new life. */
  lifeIntent?: { id: string; goalId?: string | null; purpose?: string | null; status?: string | null; formedAt?: string | null; updatedAt?: string | null } | null
  /** A livelihood direction that survives the short project or action currently occupying them. */
  careerIntent?: { id: string; goalId?: string | null; purpose?: string | null; status?: string | null; formedAt?: string | null; updatedAt?: string | null; lastActedAt?: string | null } | null
  /** A concrete action that was set aside. It is a record of what happened, not a promise that the
   * resident will necessarily return to it. */
  suspendedAction?: { plan?: { id?: string; action?: string; place?: string; targetId?: string | null; reason?: string; startedAt?: string; endsAt?: string } | null; desiredAction?: string | null; desiredDurationSeconds?: number | null; pausedAt?: string | null } | null
  /** An evolving self-description of work, distinct from the public-facing actor role. */
  occupation?: string | null
  livelihoodPressure?: number
  // The position (bed, desk, plot, ...) this resident currently holds, if any; null/absent while
  // travelling or on a save from before the two-layer place model.
  positionId?: string | null
  plan: { id: string; action: string; place: string; targetId: string | null; reason: string; startedAt: string; endsAt: string } | null
  relationships: Record<string, number>; revision: number
}
export type Project = { id: string; title: string; kind: string; place: string; ownerId: string; status: 'idea' | 'active' | 'ready' | 'celebrating'; progress: number; needed: number; contributors: string[]; objectKind: 'poster' | 'flowers' | 'books' | 'tea'; description: string }
export type Conversation = { id: string; place: string; participantIds: string[]; topicId: string; startedAt: string; updatedAt: string; status: 'active' | 'ended'; turns: { speakerId: string; text: string; at: string; emoji?: string | null }[] }
export type WorldEvent = { id: string; at: string; type: string; place: string; actorIds: string[]; text: string; projectId: string | null }
export type WorldObject = { id: string; kind: string; place: string; label: string; state: string; projectId: string | null }
