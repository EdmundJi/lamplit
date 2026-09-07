/**
 * `positionAt` — the pure function CONTRACT-M7.md §2 requires both frontend and backend to
 * implement identically. The nightly job uses its backend twin to derive encounters; this
 * module's copy is what `town.engine.ts` calls every frame to decide where to draw an NPC.
 * Same input, same output on both sides — that's the whole point (plan.md §3.5: "两边算出来的
 * 必须是同一份"), so resist the urge to "improve" this beyond what the contract specifies.
 *
 * No Phaser import — this is math only, `town.engine.ts` owns turning the result into a sprite
 * position.
 */
import type { NpcActivity, NpcDayPlan, NpcPlace, NpcScheduleSlot } from './town-npc.types'

export const MINUTES_PER_DAY = 1440

/** Positive modulo for minute-of-day math (CONTRACT-M7.md §2): a negative or >=1440 minute
 * (DST edges, a caller doing its own day-rollover arithmetic, ...) still lands in [0,1440). */
export function floorMod(value: number, modulus: number): number {
  const m = value % modulus
  return m < 0 ? m + modulus : m
}

function clamp01(value: number): number {
  if (value < 0) return 0
  if (value > 1) return 1
  return value
}

export type NpcPositionAt =
  | { kind: 'AT'; place: NpcPlace; activity: NpcActivity }
  | { kind: 'WALKING'; fromPlace: NpcPlace; toPlace: NpcPlace; progress: number; activity: 'walking' }

/**
 * CONTRACT-M7.md §2: given a day-plan and any minute-of-day, returns whether the NPC is
 * sitting at a place or mid-commute between two. `minuteOfDay` is normalized with `floorMod`
 * first, so callers never need to pre-clamp it themselves.
 *
 * Never returns `undefined`: a well-formed `dayPlan` (errands ∪ legs covers 0-1440 with no
 * holes, per CONTRACT-M7.md §1) always has exactly one matching errand or leg, but a malformed
 * or partial one (e.g. hand-built in a test, or a not-yet-fully-generated plan) falls back to
 * "wherever the last known errand left them", and finally to `home`/`idle` if there's nothing
 * at all — the frontend must never crash just because a day-plan came back incomplete.
 */
export function positionAt(dayPlan: NpcDayPlan, minuteOfDay: number): NpcPositionAt {
  const minute = floorMod(minuteOfDay, MINUTES_PER_DAY)

  const errand = dayPlan.errands.find(e => minute >= e.startMinute && minute < e.endMinute)
  if (errand) return { kind: 'AT', place: errand.place, activity: errand.activity }

  const leg = dayPlan.legs.find(l => minute >= l.departMinute && minute < l.arriveMinute)
  if (leg) {
    const span = leg.arriveMinute - leg.departMinute
    const progress = span > 0 ? clamp01((minute - leg.departMinute) / span) : 1
    return { kind: 'WALKING', fromPlace: leg.fromPlace, toPlace: leg.toPlace, progress, activity: 'walking' }
  }

  const lastErrand = dayPlan.errands[dayPlan.errands.length - 1]
  if (lastErrand) return { kind: 'AT', place: lastErrand.place, activity: lastErrand.activity }
  return { kind: 'AT', place: 'home', activity: 'idle' }
}

/**
 * Synthesizes an equivalent `NpcDayPlan` from the legacy hour-based `schedule` for backends
 * that haven't shipped M7 yet (`TownNpcView.dayPlan` is optional — see town-npc.types.ts).
 * `legs` comes back empty: with no real commute data to derive one from, every slot boundary
 * becomes an instant jump rather than a fabricated walk, which is exactly what the old
 * "站在原地换气泡" rendering already did — this just gives the renderer one code path
 * (`positionAt`) to call regardless of which shape the backend sent.
 *
 * Zero-length slots (`startHour === endHour`) are dropped, matching `npc-placement.ts`'s
 * `activeSlot` treatment of them as never-active.
 */
export function dayPlanFallback(schedule: NpcScheduleSlot[], date = ''): NpcDayPlan {
  const errands = schedule
    .filter(slot => slot.endHour > slot.startHour)
    .map(slot => ({
      place: slot.place,
      activity: slot.activity,
      startMinute: slot.startHour * 60,
      endMinute: slot.endHour * 60,
      priority: 1 as const,
      origin: 'RHYTHM' as const,
    }))
  return { date, errands, legs: [] }
}
