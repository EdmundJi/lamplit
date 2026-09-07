export type IntentKind = 'focus' | 'rest' | 'walk' | 'home' | 'flowers' | 'water' | 'thought'
export type Actor = { id: string; name: string; role: string; place: 'home' | 'cafe' | 'street' | 'garden'; activity: string; label: string; x: number; y: number; until: string }
export type Intent = { id: string; kind: IntentKind; priority: 'explicit' | 'passing'; status: 'pending' | 'active' | 'done' | 'cancelled'; feedback: string; createdAt: string; taskId?: string | null; text?: string; resolvedKind?: string }
export type Memory = { id: string; ownerId: string; sourceId: string; sourceType: 'seed' | 'observed' | 'heard' | 'reflection'; topicId?: string; evidenceIds?: string[]; importance?: number; at: string; text: string }
export type World = {
  id: string; name: string; timezone: string; joinedAt: string; updatedAt: string; revision: number
  weather: 'sunny' | 'rain'; period: 'morning' | 'afternoon' | 'evening' | 'night'
  avatar: Actor; residents: Actor[]; intents: Intent[]; diary: { id: string; at: string; text: string }[]
  simulationVersion?: number; residentStates?: ResidentState[]; projects?: Project[]; conversations?: Conversation[]; events?: WorldEvent[]; objects?: WorldObject[]
  memories: Memory[]; focus: { taskId: string; startedAt: string; endsAt: string } | null; offlineSummary: string | null
}
export type Snapshot = { joined: boolean; world: World | null }
export type IntentInput = { id: string; kind: IntentKind; priority: 'explicit' | 'passing'; taskId?: string; durationMinutes?: number; text?: string }
export type ResidentState = {
  id: string; energy: number; social: number; curiosity: number; mood: string; goal: string; thought: string
  plan: { id: string; action: string; place: string; targetId: string | null; reason: string; startedAt: string; endsAt: string } | null
  relationships: Record<string, number>; revision: number
}
export type Project = { id: string; title: string; kind: string; place: string; ownerId: string; status: 'idea' | 'active' | 'ready' | 'celebrating'; progress: number; needed: number; contributors: string[]; objectKind: 'poster' | 'flowers' | 'books' | 'tea'; description: string }
export type Conversation = { id: string; place: string; participantIds: string[]; topicId: string; startedAt: string; updatedAt: string; status: 'active' | 'ended'; turns: { speakerId: string; text: string; at: string; emoji?: string | null }[] }
export type WorldEvent = { id: string; at: string; type: string; place: string; actorIds: string[]; text: string; projectId: string | null }
export type WorldObject = { id: string; kind: string; place: string; label: string; state: string; projectId: string | null }
