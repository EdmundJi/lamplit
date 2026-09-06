import { positionAt, dayPlanFallback } from './day-plan'
import type { TownNpcView } from './town-npc.types'

export type ConversationNotice = { npcCode: string; name: string; phase: 'talking' | 'leaving' | 'ended'; reason?: string; npcInitiated?: boolean }
const places: Record<string, string> = { academy: '学院', gym: '健身房', cafe: '咖啡馆', park: '公园', plaza: '广场', home: '家里', street: '街上' }

/** Presentation-only interruptions; never alter the persisted itinerary or social facts. */
export function conversationDeparture(npc: TownNpcView | null, minute: number, elapsedMs: number, initialPlace: string): string | null {
  if (!npc || npc.layer !== 2 || elapsedMs < 15_000) return null
  const plan = npc.dayPlan ?? dayPlanFallback(npc.schedule)
  const position = positionAt(plan, minute)
  const next = position.kind === 'WALKING' ? position.toPlace : position.place
  if (next !== initialPlace || (position.kind === 'WALKING' && elapsedMs >= 45_000)) {
    return `我得继续往${places[next] ?? '前面'}走了，等忙完再聊。`
  }
  const errand = plan.errands.find(e => minute >= e.startMinute && minute < e.endMinute)
  if (errand?.priority === 2 && elapsedMs >= 30_000) return `那边的活动还在等我，我先去${places[next] ?? '忙一会儿'}，回头见。`
  if (elapsedMs >= 180_000) return '我得接着忙手边的事啦，咱们下次再慢慢聊。'
  return null
}
