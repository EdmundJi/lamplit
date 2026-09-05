/**
 * Pure helpers for plan.md §3.3 (相遇对话按亲密度分档) and 护栏 A (全镇每日主动性预算).
 * No Phaser import — `town.engine.ts` drives the actual bubble sprites off these decisions.
 */
import type { InitiativeBudget, NpcTalkingPoint } from './town-npc.types'

export type BubbleTier = 'high' | 'mid' | 'low'

/** high >= 0.6 (stop, chatty), mid >= 0.3 (stop, brief), else low (nod-and-pass only). */
export function bubbleTierFor(affinity: number): BubbleTier {
  if (affinity >= 0.6) return 'high'
  if (affinity >= 0.3) return 'mid'
  return 'low'
}

/**
 * Which talking points actually get played for an encounter at this tier. `points` is expected
 * pre-sorted salience-descending (as `GET /town/npcs` returns it) so slicing keeps the strongest
 * ones. High affinity plays up to 3 (plan.md: "依次播 2~3 条" — the range comes from however many
 * of the NPC's at-most-3 points are on hand; when only 1-2 exist, that's all there is to play).
 * Mid affinity plays exactly 1. Low affinity plays none — just a wordless/greeting bubble.
 */
export function pointsToPlay(tier: BubbleTier, points: NpcTalkingPoint[]): NpcTalkingPoint[] {
  switch (tier) {
    case 'high':
      return points.slice(0, Math.min(3, points.length))
    case 'mid':
      return points.slice(0, Math.min(1, points.length))
    case 'low':
      return []
  }
}

/** 小助 always wins contention for the last remaining slot of the daily initiative budget. */
export const GUIDE_CODE = 'GUIDE'

/**
 * Whether `npcCode` is allowed to spend a slot of the shared daily initiative budget right now.
 * Once the budget is exhausted (`used >= limit`), nobody may initiate — not even 小助. Below
 * that, 小助 may always take the next slot, but any other NPC is held back from the very last
 * remaining slot so that 小助 keeps priority over it (plan.md §2.4: "小助优先，其余抢剩余额度").
 */
export function canInitiate(budget: InitiativeBudget, npcCode: string): boolean {
  if (budget.used >= budget.limit) return false
  if (npcCode === GUIDE_CODE) return true
  return budget.used < budget.limit - 1
}

/**
 * Spends one slot for `npcCode`, returning a new budget object (never mutates `budget`). A call
 * that `canInitiate` would refuse is a no-op and returns `budget` unchanged, so callers can't
 * accidentally push `used` past `limit` by skipping the check.
 */
export function consume(budget: InitiativeBudget, npcCode: string): InitiativeBudget {
  if (!canInitiate(budget, npcCode)) return budget
  return { limit: budget.limit, used: budget.used + 1 }
}
