/** Runtime-scoped memory survives room changes without storing NPC dialogue in browser storage. */
type Memory = { lines: Map<string, number>; actors: Map<string, number>; nextEncounter: number }
const memories = new WeakMap<object, Memory>()
function memory(owner: object): Memory {
  let state = memories.get(owner)
  if (!state) { state = { lines: new Map(), actors: new Map(), nextEncounter: 0 }; memories.set(owner, state) }
  return state
}

/** Repeated ambient information is quiet for 20 minutes, including across speech channels. */
export function freshSocialLine(owner: object, actor: string, lines: string[], now = Date.now()): string | undefined {
  const state = memory(owner)
  for (const [key, until] of state.lines) if (until <= now) state.lines.delete(key)
  const text = lines.find(line => line.trim() && !state.lines.has(`${actor}:${line.trim()}`))
  if (text) state.lines.set(`${actor}:${text.trim()}`, now + 20 * 60_000)
  return text
}

/** Leave room for walking: a town-wide pause and a longer pause per participant. */
export function allowSocialEncounter(owner: object, a: string, b: string, now = Date.now()): boolean {
  const state = memory(owner)
  if (now < state.nextEncounter || now < (state.actors.get(a) ?? 0) || now < (state.actors.get(b) ?? 0)) return false
  state.nextEncounter = now + 30_000
  state.actors.set(a, now + 120_000)
  state.actors.set(b, now + 120_000)
  return true
}
