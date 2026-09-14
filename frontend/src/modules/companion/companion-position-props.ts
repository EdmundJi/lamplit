/**
 * Furniture-level "in use" props, derived purely from server state already on the wire (a
 * resident's `positionId` + `activity`/`action`, plus `cafeOpen`) - no new backend call, and no
 * client-side guessing about what someone is doing. Distinct from the seated hand-held icon drawn
 * per-frame in companion-scene.ts's update() (a tiny book/mug that follows a cafe-seat or
 * home-desk occupant's hand): that one already exists and is left untouched. This derivation marks
 * the *position* itself - an open book left on an academy desk, a bed's covers, the espresso
 * machine's steam - for the places that hand-prop does not reach, and for furniture whose "in use"
 * state should read even when nobody is looking at a particular resident's hands.
 *
 * Kept free of any Phaser import (like companion-geometry.ts) so it stays a small, pure, easily
 * unit-tested function: given a snapshot, return the complete list of props that should exist right
 * now. The caller (CompanionStreetScene) diffs this list against what it last drew, the same
 * signature-diff pattern updateProjects() already uses for project shelf art, so props only appear
 * or disappear on an actual change - never recreated every frame.
 */
import { CAFE_SERVICE, POSITION_SLOTS } from './companion-art'
import { visibleActivity } from './companion-geometry'
import type { SceneResident, SceneSnapshot } from './companion-scene'

export type PositionUseProp =
  | { positionId: string; prop: 'book'; x: number; y: number }
  | { positionId: string; prop: 'blanket'; x: number; y: number }
  | { positionId: string; prop: 'steam'; x: number; y: number }

/**
 * Positions that already show a seated hand-held prop (companion-scene.ts's update(): a cafe seat
 * via `cafeSeatAt`, or a home desk via `positionId.endsWith('-desk')`). The book-on-the-table prop
 * below deliberately skips these - the point is to fill the gap at furniture that has no hand-prop
 * of its own (the academy's three desks chief among them), not to draw a second book next to the
 * one already in someone's hands.
 */
function hasHandProp(positionId: string) {
  return positionId === 'cafe-worktable' || positionId.startsWith('cafe-window') || (positionId.startsWith('home-') && positionId.endsWith('-desk'))
}

/** A book laid a little above the resident's own foot anchor reads as "on the table/desk in front
 * of them" without needing to know which way they are facing - the same modest offset the cafe
 * tables' always-on coffee_cup prop already uses (`table.y - 14`, companion-stage.ts). */
const BOOK_OFFSET_Y = 14

export function positionUseProps(snapshot: SceneSnapshot): PositionUseProp[] {
  const props: PositionUseProp[] = []
  const byPosition = new Map<string, SceneResident[]>()
  for (const resident of snapshot.residents) {
    if (!resident.positionId) continue
    const list = byPosition.get(resident.positionId)
    if (list) list.push(resident); else byPosition.set(resident.positionId, [resident])
  }
  for (const [positionId, occupants] of byPosition) {
    const slots = POSITION_SLOTS[positionId]
    if (!slots?.length) continue
    // Stable, deterministic order (by id) rather than the residents array's own order, which can
    // reshuffle between polls without any real change in who is sitting where.
    occupants.slice().sort((a, b) => a.id.localeCompare(b.id)).forEach((resident, index) => {
      const slot = slots[Math.min(index, slots.length - 1)]!
      const mode = visibleActivity(resident.activity, resident.action, resident.objectKind)
      if (positionId.endsWith('-bed')) {
        // Occupying a bed position is not, by itself, "asleep": a resident on a short daytime
        // `rest` also claims their own bed position (see companion-scene.ts's `knownPositionId`
        // branch) but is drawn sitting, not lying down. A cover drawn under a sitting figure reads
        // as a stray mark at their feet, not a made bed - so this only fires once the resident is
        // actually visibly lying down (`mode === 'sleep'`, the native sleep pose).
        if (mode === 'sleep') props.push({ positionId, prop: 'blanket', x: slot.x, y: slot.y })
        return
      }
      if (hasHandProp(positionId)) return
      if (mode === 'read') props.push({ positionId, prop: 'book', x: slot.x, y: slot.y - BOOK_OFFSET_Y })
    })
  }
  // The steam wisp needs the cafe to actually be open (never inferred from the client's own clock -
  // see CompanionScene.vue's cafeOpen prop, sourced from the authoritative world.cafeStatus) and
  // someone actually working the machine right now, not merely standing at the counter between
  // orders (serve/wait/handover/assist are the surrounding, not-yet-making-it moments).
  const cafeOpen = snapshot.cafeOpen ?? true
  if (cafeOpen && snapshot.residents.some(resident => resident.positionId === 'cafe-counter' && ['tend', 'prepare'].includes(resident.activity ?? ''))) {
    props.push({ positionId: 'cafe-counter', prop: 'steam', x: CAFE_SERVICE.machine.x, y: CAFE_SERVICE.machine.y })
  }
  return props
}
