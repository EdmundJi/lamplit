/**
 * Pure scene-geometry helpers - place resolution, seat/bed/desk lookup, free-standing spread and
 * rain-shelter math. Deliberately free of any Phaser import (unlike companion-scene.ts, which
 * pulls in the real `Phaser.Scene` runtime): TownStage.vue - mounted synchronously by every
 * UserLayout, never deferred - needs `residentTarget`/`scenePlace` for its docked-strip camera
 * target and place label, but must not drag the whole Phaser chunk into that first-paint bundle
 * merely to read these two small, pure computations. companion-scene.ts re-exports everything
 * here so its own internals (and existing test imports) are unaffected.
 */
import type { Point } from '../../shared/scene/collision'
import { nearestStandable } from '../../shared/scene/collision'
import { COMPANION_COLLISION, freeStandPosition } from './companion-navigation'
import { POSITION_SLOTS, CAFE_SERVICE, CAFE_SEATS, GARDEN_OFFSET_X, gardenY, HOME_ROOMS, CAFE_ROOM, CAFE_WINDOW_ROOM, ACADEMY_ROOM, GYM_ROOM, SHOP_ROOM, PLACE_FRAMES, STAGE_PLACES } from './companion-art'
import type { SceneResident } from './companion-scene'

type RainShelter = { x: number; y: number; width: number; height: number }
// These use the shared stage geometry rather than a second set of hand-tuned rain rectangles.
// The cafe frame includes its wall/roof margin; homes are the real room footprints.
const RAIN_SHELTERS: RainShelter[] = [
  ...Object.values(HOME_ROOMS).map(room => ({ x: room.x - 6, y: room.y - 5, width: room.w + 12, height: room.h + 12 })),
  { x: CAFE_ROOM.x - 6, y: CAFE_ROOM.y - 5, width: CAFE_ROOM.w + 12, height: CAFE_ROOM.h + 12 },
  { x: CAFE_WINDOW_ROOM.x - 6, y: CAFE_WINDOW_ROOM.y - 5, width: CAFE_WINDOW_ROOM.w + 12, height: CAFE_WINDOW_ROOM.h + 12 },
  // The academy and gym are real rooms too (east wing, past the old x=1248 edge) - roofed the same
  // way as every other room above so rain does not fall through their walls.
  { x: ACADEMY_ROOM.x - 6, y: ACADEMY_ROOM.y - 5, width: ACADEMY_ROOM.w + 12, height: ACADEMY_ROOM.h + 12 },
  { x: GYM_ROOM.x - 6, y: GYM_ROOM.y - 5, width: GYM_ROOM.w + 12, height: GYM_ROOM.h + 12 },
  { x: SHOP_ROOM.x - 6, y: SHOP_ROOM.y - 5, width: SHOP_ROOM.w + 12, height: SHOP_ROOM.h + 12 },
]
function inside(rect: RainShelter, x: number, y: number) { return x >= rect.x && x <= rect.x + rect.width && y >= rect.y && y <= rect.y + rect.height }
/** A rain streak is omitted when any of its short diagonal would land inside a roofed room. */
export function rainFallsOutside(x: number, y: number) {
  const samples = [[x, y], [x - 2.5, y + 6.5], [x - 5, y + 13]]
  return !RAIN_SHELTERS.some(rect => samples.some(([px, py]) => inside(rect, px!, py!)))
}
// Every real place id STAGE_PLACES knows about except the virtual 'avatar' entry ("wherever the
// avatar currently is" is not something a `location` string ever names).
const SCENE_PLACE_IDS = Object.keys(STAGE_PLACES).filter(id => id !== 'avatar')
const warnedScenePlaceIds = new Set<string>()
/**
 * Resolve any server-given `location` string to its real visual place. Public buildings keep their
 * own bucket so a resident at the academy, gym, board or shop actually appears inside it rather
 * than being folded onto the old street strip. Reads STAGE_PLACES - the same
 * registry TownStage's nav/camera already uses - instead of keeping a second, independent list of
 * known place ids: adding a building means adding one STAGE_PLACES entry, not editing this
 * function too. A truly unrecognised id (a building the backend grew that this table has never
 * heard of - the docs/05 "wardrobe drawn as a table" class of bug) still has to land somewhere
 * walkable, so it falls back to 'street' like before, but now says so once per id in dev instead
 * of pretending the mapping was intentional.
 */
export function scenePlace(location: string) {
  // A subplace hangs off its place id with '.', '/' or '-' - only 'home' actually uses this today
  // (the backend hands out per-resident "home-owner", "home-self", ...) but every id is matched
  // the same generic way for whichever building grows one next.
  const matchedId = SCENE_PLACE_IDS.find(id => location === id || location.startsWith(`${id}.`) || location.startsWith(`${id}/`) || location.startsWith(`${id}-`))
  if (matchedId) return STAGE_PLACES[matchedId]!.scenePlace
  if (import.meta.env.DEV && !warnedScenePlaceIds.has(location)) {
    warnedScenePlaceIds.add(location)
    console.warn(`[town] 位置 "${location}" 不在地点登记表（STAGE_PLACES）里，暂时按门前小街处理`)
  }
  return 'street'
}
const warnedMissingPositionSlots = new Set<string>()
export function homeId(location: string) { return location.match(/^home[-./](.+)$/)?.[1] }
export function homeRoom(location: string) { return HOME_ROOMS[homeId(location) ?? ''] }
const warnedLegacyHomeIds = new Set<string>()
/**
 * Fallback room for a home `location` with no room of its own: either a flat old-save "home" (no
 * per-resident id at all) or a resident id HOME_ROOMS has never been given geometry for. Cycles
 * through whichever rooms actually exist (`Object.keys(HOME_ROOMS)`, currently the same six names
 * this used to hard-code, 'weaver' still deliberately absent - she shares 'artist's room) instead
 * of a fixed name list, so a newly registered home is picked up automatically; a genuinely new,
 * still-unregistered home still warns once per `location` in dev rather than silently reusing
 * someone else's bedroom with no trace in the console.
 */
function legacyHomeRoom(index: number, location: string) {
  const ids = Object.keys(HOME_ROOMS)
  const fallbackId = ids[Math.max(0, index) % ids.length]!
  if (import.meta.env.DEV && !warnedLegacyHomeIds.has(location)) {
    warnedLegacyHomeIds.add(location)
    console.warn(`[town] "${location}" 还没有登记专属房间，暂时借用 ${fallbackId} 的家`)
  }
  return HOME_ROOMS[fallbackId]!
}
export function placeFrame(location: string) {
  const ownHome = homeRoom(location)
  return ownHome ?? PLACE_FRAMES[scenePlace(location)]
}
export function placeCenter(location: string) {
  const frame = placeFrame(location)
  return { x: frame.x + frame.w / 2, y: frame.y + frame.h / 2 }
}
export function visibleActivity(activity = '', action = '', objectKind?: string) {
  if (['create', 'help'].includes(activity)) return objectKind === 'flowers' ? 'garden' : objectKind === 'tea' ? 'drink' : 'create'
  // Service at the counter (tend/prepare - actually making the drink) borrows the drink action
  // sheet as its visible beat: docs/01 wants "等待... 你能看见他在弄" and there is no bespoke pour
  // animation, but reusing the existing raise-a-cup frames at the counter (facing 'up', see
  // update()) reads as him working the machine instead of standing idle for the whole wait.
  // serve/wait/handover/assist are the surrounding, not-yet-making-it moments and stay idle.
  if (['tend', 'prepare'].includes(activity)) return 'drink'
  if (['serve', 'wait', 'handover', 'assist'].includes(activity)) return 'idle'
  if (['home', 'rest'].includes(activity)) return 'rest'
  if (['focus', 'study', 'read'].includes(activity)) return 'read'
  if (activity === 'work') return 'read'
  if (activity === 'make') return objectKind === 'flowers' ? 'garden' : 'create'
  if (['water', 'drink'].includes(activity)) return 'drink'
  if (['observe', 'flowers', 'invite', 'celebrate', 'talk', 'walk', 'travel'].includes(activity)) return 'idle'
  const text = activity + ' ' + action
  if (/\b(tend|serve|prepare)\b|吧台|柜台|热饮|咖啡/.test(text)) return 'idle'
  if (/sleep|睡|入眠/.test(text)) return 'sleep'
  if (/rest|休息|歇一会/.test(text)) return 'rest'
  if (/drink|喝|饮|茶歇/.test(text)) return 'drink'
  if (/garden|tend|plant|花|园艺|种植|照料|浇水/.test(text)) return 'garden'
  if (/create|help|创作|帮忙|海报|画画|绘|合作/.test(text)) return 'create'
  if (/focus|study|read|学|读|专注|备考/.test(text)) return 'read'
  return 'idle'
}
/**
 * Whether a resident currently taking their turn in an active conversation should show their
 * speech bubble right now. Gated only on this resident's own arrival at their target - never on
 * whether some other actor has also stopped moving. The previous version additionally required
 * every actor sharing (`this.actors.values()...filter(other => other.conversationId ===
 * actor.conversationId)`) - including, when a speaker's own conversationId was momentarily unset,
 * every OTHER actor in town that also lacked one - to have an empty path before showing anyone's
 * bubble. Acquiring (or changing) a backend positionId gives a resident a new authoritative
 * target, which differs from their previous place+index guess and starts a short walk to settle
 * into it; that walk alone was enough to blank a conversation partner's speech bubble the whole
 * town over, for as long as the positionId walk took. A resident is "speaking" purely on their
 * own terms now.
 */
export function isSpeaking(residentId: string, turnSpeakerId: string | undefined, hasArrived: boolean) {
  return turnSpeakerId === residentId && hasArrived
}
export function conversationPosition(place: string, index: number) {
  return conversationPositionInRoom(place, index)
}
/** Keep a home conversation inside the server-described room. Public places still use their
 * shared conversation anchor; a house is not one such anchor just because all homes render with
 * the scenePlace value `home`. */
export function conversationPositionInRoom(place: string, index: number, roomId?: string | null) {
  if (scenePlace(place) === 'home' && roomId) {
    const anchor = residentPosition(place, index, 'observe', '', undefined, 0, undefined, [], roomId)
    return { x: anchor.x + (index % 2 ? 16 : -16), y: anchor.y }
  }
  const center = scenePlace(place) === 'cafe' ? CAFE_SERVICE.conversation : placeCenter(place)
  return { x: center.x + (index % 2 ? 21 : -21), y: center.y + Math.floor(index / 2) * 32 }
}
/**
 * Where a resident's feet land. The backend's positionId (a specific bed, desk seat or garden
 * plot - see TownPlaces.java) is authoritative once POSITION_SLOTS knows a pixel for it and for
 * `occupantIndex` within it. Failing that, a handful of activities that visibly sit someone down
 * at real furniture (sleeping, resting on the sofa, reading/creating/drinking at the cafe desks,
 * tending a garden plot) still get their old furniture-anchored spot. Everyone else - which is
 * now the common case, since "能站的地方都能去" (docs/04-decisions.md) - free-stands: `residentId`
 * (never `index`, which shifts as other residents come and go) seeds a stable pixel inside the
 * place's walkable area, spread apart from `occupied` (every other resident already settled
 * there). Callers that omit `residentId` (unit tests, or an old caller) still get a valid,
 * reachable point - just keyed off `location`+`index` instead of a real resident identity.
 */
export function residentPosition(location: string, index: number, activity = '', action = '', positionId?: string | null, occupantIndex = 0, residentId?: string, occupied: Point[] = [], roomId?: string | null) {
  if (positionId) {
    const slots = POSITION_SLOTS[positionId]
    if (slots?.length) return slots[Math.min(Math.max(0, occupantIndex), slots.length - 1)]!
    // A real backend positionId with no pixel registered yet - either an old save with a stale id,
    // or (the case task 1 cares about) a position the artist genuinely has not placed. Either way
    // it must be visible in dev, not just silently fall through to the place+index guess below.
    if (import.meta.env.DEV && !warnedMissingPositionSlots.has(positionId)) {
      warnedMissingPositionSlots.add(positionId)
      console.warn(`[town] 位置 id "${positionId}" 在 POSITION_SLOTS 里还没有像素落点，暂时按处境猜一个位置`)
    }
  }
  const place = scenePlace(location), slot = index % 5
  // Only resolved for a home location - computing it unconditionally (as before) would run
  // legacyHomeRoom's dev warning for every cafe/garden/street call too, even though its result is
  // never used outside the two 'home' branches below.
  const room = place === 'home' ? (homeRoom(location) ?? legacyHomeRoom(index, location)) : undefined
  // A shared house is one building in `location`, but the server's room id distinguishes each
  // bedroom from the common room and bathroom. When no scarce position is occupied, retain that
  // distinction visually with the already-authored furniture anchors instead of spreading every
  // flatmate across the whole house as if their room did not exist.
  if (room && roomId) {
    const residentRoom = roomId.match(/^home-[^/]+-room-(.+)$/)?.[1]
    if (residentRoom) {
      if (visibleActivity(activity, action) === 'sleep') return POSITION_SLOTS[`home-${residentRoom}-bed`]?.[0] ?? room.bed
      return POSITION_SLOTS[`home-${residentRoom}-desk`]?.[0] ?? room.anchor
    }
    if (roomId.endsWith('-common')) {
      const slots = POSITION_SLOTS[`${location}-table`]
      if (!slots?.length) return room.anchor
      const available = slots.find(slot => !occupied.some(point => point.x === slot.x && point.y === slot.y))
      return available ?? slots[index % slots.length]!
    }
    if (roomId.endsWith('-bathroom')) return POSITION_SLOTS[`${location}-bathroom`]?.[0] ?? room.anchor
  }
  if (room && visibleActivity(activity, action) === 'sleep') return room.bed
  if (room && visibleActivity(activity, action) === 'rest') return room.anchor
  if (place === 'cafe' && activity === 'wait') return CAFE_SERVICE.waiting[index % CAFE_SERVICE.waiting.length]!
  if (place === 'cafe' && ['read', 'create', 'rest', 'drink'].includes(visibleActivity(activity, action))) {
    // Four discussion seats and six independently occupied window seats.
    const seat = CAFE_SEATS[index % CAFE_SEATS.length]!
    return { x: seat.x, y: seat.y }
  }
  if (place === 'garden' && /garden|tend|plant|flowers|grow|花|园艺|种植|照料|浇水/i.test(activity + action)) {
    // The native stream lands about 50px to the right and 10px below the feet.
    // Keep its whole silhouette inside the default camera, including the last resident.
    const plots = [{ x: 770, y: 276 }, { x: 849, y: 276 }, { x: 770, y: 356 }, { x: 849, y: 356 }, { x: 842, y: 421 }]
    return { x: plots[slot]!.x + GARDEN_OFFSET_X, y: gardenY(plots[slot]!.y) }
  }
  if (room && /focus|study|read|work|make|专注|学习|读书|工作|制作/i.test(activity + action)) return room.desk
  return freeStandPosition(place === 'home' ? location : place, residentId ?? `${location}#${index}`, occupied)
}
/**
 * Where a resident is currently headed - travelling toward a destination's own door/entry, or
 * settled at their resolved seat/bed/desk/free-stand spot. Mirrors sync()'s own per-resident
 * target computation (the `travelling` branch and the `residentPosition()` call below it) so a
 * caller outside the running scene - the docked strip's "follow the avatar" camera target - can
 * point at exactly the same spot the actor is walking to/standing at, without needing the live
 * Phaser actor map. Approximates `occupantIndex` (real slot-sharing resolution only exists inside
 * the scene's actor loop) - fine for a camera target, which does not need seat-exact precision.
 */
/**
 * The single pixel a traveller is walking toward: a home's own front door, the cafe's public entry,
 * or the middle of any other place. Extracted so exactly one definition of "where does this trip end"
 * exists, because three separate things need it and a disagreement between any two of them is a
 * visible bug: the scene walks a body to it, the backend's TownDistances table was measured between
 * these exact points, and companion-walk-parity.test.ts re-measures them to prove the two have not
 * drifted apart. An earlier round used the garden frame's bottom edge in one place and its centre in
 * another, and 12 of 28 distances quietly disagreed by up to 23px.
 *
 * The generic case is snapped to standable ground, which is not a nicety: the garden frame's own
 * centre sits inside one of its raised beds, so the raw centre is unreachable, `companionPath`
 * returned an empty route for every trip to the garden, and anybody heading there never walked at
 * all - they stood still until the backend flipped their place and then appeared inside. Homes and
 * the cafe name a real doorway already and are left exactly as they are.
 */
export function travelAnchor(location: string) {
  const place = scenePlace(location)
  const publicDoor = place === 'cafe' ? CAFE_SERVICE.entry
    : place === 'academy' ? { x: ACADEMY_ROOM.doorX, y: ACADEMY_ROOM.y + ACADEMY_ROOM.h + 16 }
      : place === 'gym' ? { x: GYM_ROOM.doorX, y: GYM_ROOM.y + GYM_ROOM.h + 16 }
        : place === 'shop' ? { x: SHOP_ROOM.doorX, y: SHOP_ROOM.y + SHOP_ROOM.h + 16 }
          : undefined
  const door = homeRoom(location)?.door ?? publicDoor
  return door ?? nearestStandable(placeCenter(location), COMPANION_COLLISION)
}
export function residentTarget(actor: SceneResident, positionId?: string | null, occupantIndex = 0) {
  const travelling = Boolean(actor.destination) && (actor.activity === 'walk' || actor.activity === 'travel')
  const location = travelling ? actor.destination! : actor.location
  if (travelling) return travelAnchor(location)
  return residentPosition(location, 0, actor.activity, actor.action, positionId, occupantIndex, actor.id, [], actor.roomId)
}
