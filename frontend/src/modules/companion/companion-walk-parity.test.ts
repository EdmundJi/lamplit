import { describe, expect, it } from 'vitest'
import { readFileSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { WALK_PIXELS_PER_SECOND } from './companion-art'
import { travelAnchor } from './companion-geometry'
import { companionPath } from './companion-navigation'

/**
 * "两边不许漂移" guard (see the sibling backend work on TownDistances.java): a resident's travel
 * plan is timed by the backend as realPixelDistance / WALK_PIXELS_PER_SECOND, and the frontend
 * actually moves that resident at the very same WALK_PIXELS_PER_SECOND (companion-scene.ts's
 * stepTowardPoint call). If the two constants, or the two distance tables, ever disagree, the
 * resident either stands at the door waiting out a countdown that outlives their real walk (the
 * original "罚站" bug), or gets teleported in before they have actually arrived. This file
 * recomputes real pathfinding distances between every location the backend tracks and checks
 * them - and the shared speed constant - against what's baked into TownDistances.java.
 *
 * This test intentionally does NOT skip when TownDistances.java is missing: a guard that quietly
 * disappears the moment its target does not exist yet is not a guard (see this repo's own history
 * of exactly that mistake). It fails loudly instead, naming the missing file, until the backend
 * half of this change lands.
 */

const HERE = dirname(fileURLToPath(import.meta.url))
const TOWN_DISTANCES_JAVA = resolve(HERE, '../../../../backend/src/main/java/com/betterself/growth/town/companion/domain/TownDistances.java')

// Not a local list: exactly what the scene walks a body to, and exactly what TownDistances.java's
// numbers were measured between. An earlier round of this test wrote its own anchors in prose
// ("the garden frame's bottom-center") and 12 of the 28 pairs silently disagreed by up to 23px -
// which read as hand-measurement drift in the Java table and was nothing of the kind.
const ANCHORS: Record<string, { x: number; y: number }> = Object.fromEntries(
  [
    'street', 'cafe', 'garden', 'academy', 'gym', 'board', 'shop',
    'home-owner', 'home-student', 'home-artist', 'home-gardener', 'home-self', 'home-fixer',
    'home-barista', 'home-botanist', 'home-messenger', 'home-baker', 'home-florist',
    'home-scholar', 'home-tailor', 'home-masseur', 'home-broker', 'home-trader',
  ]
    .map(place => [place, travelAnchor(place)]),
)

/** Real walked distance between two points, using the actual collision-aware pathfinder - never
 * the straight-line distance, which would be wrong whenever a wall or a table is in the way.
 * companionPath() returns the route *without* its own starting point, so the start has to be
 * spliced back on before summing segment lengths, or a same-room straight walk (no intermediate
 * waypoints at all) would sum to zero. */
function walkedDistance(from: { x: number; y: number }, to: { x: number; y: number }): number {
  const points = [from, ...companionPath(from, to)]
  let total = 0
  for (let i = 1; i < points.length; i++) total += Math.hypot(points[i]!.x - points[i - 1]!.x, points[i]!.y - points[i - 1]!.y)
  return total
}

type ParsedDistances = { pixelsPerSecond: number | undefined; pairs: Map<string, number> }

/**
 * Best-effort parser for TownDistances.java's distance table. The exact Java shape is being
 * written concurrently by another agent and was not available while this test was written, so
 * this looks for a couple of reasonably-shaped literal patterns rather than assuming one exact
 * layout:
 *   - a three-argument entry naming both locations and the distance: `"home-owner", "cafe", 537`
 *     (covers a custom `dist("a", "b", 537)`-style helper or `Map.entry`-of-arrays call)
 *   - a pairKey-style single string key (mirrors the frontend's own `pairKey()` in
 *     shared/scene/walkers.ts): `"home-owner|cafe", 537`
 * If TownDistances.java's real shape doesn't match either pattern once it lands, this parser
 * needs a small update to match it - the test below fails loudly (not silently) in that case by
 * asserting the parsed pair count is non-zero, rather than passing on an empty, vacuous table.
 */
function parseTownDistances(source: string): ParsedDistances {
  const pixelsPerSecondMatch = source.match(/WALK_PIXELS_PER_SECOND\s*=\s*(\d+)/)
  const pairs = new Map<string, number>()
  const LOC = '[a-zA-Z][a-zA-Z-]*'
  const threeArg = new RegExp(`"(${LOC})"\\s*,\\s*"(${LOC})"\\s*,\\s*(\\d+)`, 'g')
  const pipedKey = new RegExp(`"(${LOC})\\|(${LOC})"\\s*,\\s*(\\d+)`, 'g')
  for (const re of [threeArg, pipedKey]) {
    for (const match of source.matchAll(re)) {
      const [, a, b, distance] = match
      if (!a || !b || !distance) continue
      // Only keep pairs where both sides are locations this test actually knows an anchor for -
      // a nested unrelated numeric literal elsewhere in the file (an id, a port, a version) could
      // otherwise coincidentally match the loose LOC pattern.
      if (!(a in ANCHORS) || !(b in ANCHORS)) continue
      pairs.set(a < b ? `${a}|${b}` : `${b}|${a}`, Number(distance))
    }
  }
  return { pixelsPerSecond: pixelsPerSecondMatch ? Number(pixelsPerSecondMatch[1]) : undefined, pairs }
}

describe('frontend/backend walk-speed and distance parity ("两边不许漂移")', () => {
  if (process.env.TOWN_PRINT_DISTANCES === '1') {
    it('prints the complete collision-aware Java distance table for map handoff', () => {
      const names = Object.keys(ANCHORS).sort()
      for (let i = 0; i < names.length; i++) for (let j = i + 1; j < names.length; j++) {
        const a = names[i]!, b = names[j]!
        const there = walkedDistance(ANCHORS[a]!, ANCHORS[b]!)
        const back = walkedDistance(ANCHORS[b]!, ANCHORS[a]!)
        console.log(`link("${a}", "${b}", ${Math.round((there + back) / 2)});`)
      }
    })
  }
  it('has the backend TownDistances.java file to compare against', () => {
    expect(
      existsSync(TOWN_DISTANCES_JAVA),
      `TownDistances.java 还不存在 (expected at ${TOWN_DISTANCES_JAVA}). ` +
        '这道防漂移测试只有一半能落地：前端的 WALK_PIXELS_PER_SECOND 已经改好，但后端那半 ' +
        '(把像素距离换算成秒数的 TownDistances.java) 还没写。这不是可以跳过的情况——先让后端那个 ' +
        'agent 把文件落地，再重跑这个测试。',
    ).toBe(true)
  })

  it('keeps WALK_PIXELS_PER_SECOND identical on both sides', () => {
    const source = readFileSync(TOWN_DISTANCES_JAVA, 'utf-8')
    const { pixelsPerSecond } = parseTownDistances(source)
    expect(
      pixelsPerSecond,
      'Could not find a `WALK_PIXELS_PER_SECOND = <number>` literal in TownDistances.java - ' +
        'either the backend named its constant differently (update the regex in ' +
        'parseTownDistances above to match), or it is missing entirely.',
    ).toBeDefined()
    expect(pixelsPerSecond).toBe(WALK_PIXELS_PER_SECOND)
  })

  it('every distance in TownDistances.java matches the real pathfinder within rounding tolerance', () => {
    const source = readFileSync(TOWN_DISTANCES_JAVA, 'utf-8')
    const { pairs } = parseTownDistances(source)
    // A parser that silently found nothing is exactly the "guard that quietly disappears" failure
    // mode this whole test exists to avoid - fail loudly instead of vacuously passing an empty
    // `for` loop below.
    expect(
      pairs.size,
      'Parsed zero location-pair distances out of TownDistances.java. Either the file does not ' +
        'yet contain a distance table, or its literal shape does not match either pattern ' +
        'parseTownDistances() looks for - update that parser to match the real shape before ' +
        'trusting this test\'s green/red result.',
    ).toBeGreaterThan(0)
    expect(pairs.size, 'TownDistances must cover every pair of drawable places').toBe(Object.keys(ANCHORS).length * (Object.keys(ANCHORS).length - 1) / 2)

    const mismatches: string[] = []
    for (const [key, backendDistance] of pairs) {
      const [a, b] = key.split('|') as [string, string]
      // Both directions, because the pathfinder is mildly direction-dependent: the route there and
      // the route back can differ by up to 23px (measured across this whole table). The stored
      // number is the mean of the two, so neither direction matches it exactly and the tolerance
      // below is half that measured spread plus a pixel of rounding - derived from the pathfinder's
      // own behaviour, not widened until the numbers fit. At 32px/s the whole spread is worth 0.7
      // seconds of a walk.
      const there = walkedDistance(ANCHORS[a]!, ANCHORS[b]!)
      const back = walkedDistance(ANCHORS[b]!, ANCHORS[a]!)
      const worst = Math.max(Math.abs(there - backendDistance), Math.abs(back - backendDistance))
      if (worst > 13) {
        mismatches.push(`${key}: backend=${backendDistance}px frontend=${there.toFixed(1)}/${back.toFixed(1)}px`)
      }
    }
    expect(mismatches, mismatches.join('\n')).toEqual([])
  })
})
