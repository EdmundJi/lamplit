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

/**
 * M7-8 气泡排版兜底. A bubble's box in world/camera space — `x`/`y` is its top-left corner, same
 * convention Phaser's own bounds use, so `town.engine.ts` can hand this straight to a container.
 */
export type BubbleBox = {
  id: string
  x: number
  y: number
  width: number
  height: number
}

export type CameraRect = { x: number; y: number; width: number; height: number }

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

/** Clamps a box fully inside `camera` (never partially off-screen). Degenerate case (the box is
 * wider/taller than the camera itself) still returns something on-screen rather than negative
 * size math: it pins to the camera's near edge. */
function clampToCamera(box: BubbleBox, camera: CameraRect): BubbleBox {
  const maxX = camera.x + Math.max(0, camera.width - box.width)
  const maxY = camera.y + Math.max(0, camera.height - box.height)
  return { ...box, x: clamp(box.x, camera.x, maxX), y: clamp(box.y, camera.y, maxY) }
}

function overlaps(a: BubbleBox, b: BubbleBox): boolean {
  return a.x < b.x + b.width && a.x + a.width > b.x && a.y < b.y + b.height && a.y + a.height > b.y
}

/**
 * Pure layout solver for on-screen speech bubbles (plan.md §3.5/§2.9: "超出镜头就夹回来，互相
 * 重叠就往上摞"). Two invariants hold on the output:
 *  - every box is fully inside `camera`;
 *  - no two boxes overlap each other, unless the camera is simply too small to fit them all (in
 *    that degenerate case a box that's already pinned to the camera's top edge is left as-is
 *    rather than being pushed off-screen to "fix" the overlap).
 *
 * Order matters for a stable result: bubbles are resolved in the order given, and a later bubble
 * yields to an earlier one by stacking upward — so callers should pass bubbles in a consistent
 * order (e.g. by npc code, or by whichever appeared first) if they want reproducible stacking.
 * Never mutates the input array or its boxes.
 */
export function layoutBubbles(bubbles: BubbleBox[], camera: CameraRect): BubbleBox[] {
  const placed: BubbleBox[] = []
  for (const bubble of bubbles) {
    let box = clampToCamera(bubble, camera)
    // 只要还跟已经摆好的气泡重叠，就把它抬高一整个自身高度，直到不再重叠或者已经顶到镜头顶
    // 端为止。步数上限按"相机能塞下多少层气泡"算，保证只要相机装得下这条重叠链就一定能收
    // 敛，不会因为链条比气泡数量长就提前放弃（也不会真的死循环）。
    const maxSteps = Math.ceil(camera.height / Math.max(box.height, 1)) + placed.length
    for (let step = 0; step < maxSteps && placed.some(p => overlaps(box, p)); step++) {
      const raised = box.y - box.height
      if (raised < camera.y) {
        box = { ...box, y: camera.y }
        break
      }
      box = { ...box, y: raised }
    }
    placed.push(box)
  }
  return placed
}
