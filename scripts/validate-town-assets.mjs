#!/usr/bin/env node
// Uses the production parser and collision helpers; no browser/server/e2e needed.
// node scripts/validate-town-assets.mjs [asset-directory]
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const require = createRequire(path.join(root, 'frontend/package.json'))
const ts = require('typescript')
const source = fs.readFileSync(path.join(root, 'frontend/src/modules/town/map-loader.ts'), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText
const { parseRoomMap, collidesAt, doorAt, interactableAt, evaluateSlots } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)
const assets = path.resolve(process.argv[2] ?? path.join(root, 'frontend/public/assets/town'))
const atlas = JSON.parse(fs.readFileSync(path.join(assets, 'interior-atlas.json'), 'utf8')).frames
const actions = { 'public-gym': 'gym.open-attributes', 'cafe-interior': 'cafe.open-goals' }
for (const id of ['home-living-room', 'academy-study', ...Object.keys(actions)]) {
  const room = parseRoomMap(JSON.parse(fs.readFileSync(path.join(assets, 'maps', `${id}.json`), 'utf8')))
  assert.equal(room.id, id)
  const used = [...room.layers.floor.flat(), ...room.layers.walls.flat(), ...room.furniture.map(p => p.frame), ...room.slots.flatMap(s => s.frames)].filter(Boolean)
  for (const frame of used) assert.ok(atlas[frame], `${id}: missing atlas frame ${frame}`)
  if (!actions[id]) { console.log(`PASS ${id}: parser + all frame references`); continue }
  const width = room.cols * room.tileSize, height = room.rows * room.tileSize
  const blocked = (x, y) => x < 7 || x > width - 7 || y < 10 || y > height || collidesAt(room, x, y, 14, 10)
  const { x: sx, y: sy } = room.spawn
  assert.ok(!blocked(sx, sy), `${id}: spawn blocks the player's 14x10 feet`)
  assert.ok(!doorAt(room, sx, sy), `${id}: spawn immediately exits`)
  // Four-pixel flood fill with one-pixel edge checks cannot tunnel through a thin collider.
  const queue = [[sx, sy]], visited = new Set([`${sx},${sy}`])
  for (let i = 0; i < queue.length; i++) {
    const [x, y] = queue[i]
    for (const [dx, dy] of [[4, 0], [-4, 0], [0, 4], [0, -4]]) {
      const nx = x + dx, ny = y + dy, key = `${nx},${ny}`
      if (visited.has(key)) continue
      if ([1, 2, 3, 4].some(t => blocked(x + dx * t / 4, y + dy * t / 4))) continue
      visited.add(key); queue.push([nx, ny])
    }
  }
  for (const door of room.doors) {
    assert.ok(queue.some(([x, y]) => doorAt(room, x, y)?.id === door.id), `${id}: unreachable exit`)
    assert.ok(door.spawn && !blocked(door.spawn.x, door.spawn.y) && !doorAt(room, door.spawn.x, door.spawn.y), `${id}: invalid door entry spawn`)
  }
  const interactive = room.furniture.filter(p => p.interactive)
  assert.deepEqual(interactive.map(p => p.interactive.actionId), [actions[id]])
  for (const p of room.furniture) {
    const frame = atlas[p.frame].sourceSize
    const w = p.displayWidth ?? frame.w, h = p.displayHeight ?? frame.h
    const left = p.x - w * (p.originX ?? .5), top = p.y - h * (p.originY ?? 1)
    assert.ok(left >= 0 && top >= 0 && left + w <= width && top + h <= height, `${id}/${p.id}: art outside room`)
    if (!p.interactive) continue
    const hit = p.interactive.hit
    assert.deepEqual(hit, { x: left, y: top, w, h }, `${id}/${p.id}: hit box differs from art`)
    assert.equal(interactableAt(room, left + w / 2, top + h / 2)?.id, p.id)
    assert.ok(queue.some(([x, y]) => x >= left - 12 && x <= left + w + 12 && y >= p.y + 10 && y <= p.y + 32), `${id}/${p.id}: cannot approach interactive furniture`)
  }
  // Every non-perimeter collider must sit inside a furniture base and stop at its feet.
  for (const rect of room.collisions.slice(5)) {
    assert.ok(room.furniture.some(p => {
      const w = atlas[p.frame].sourceSize.w, h = atlas[p.frame].sourceSize.h
      return rect.y + rect.h === p.y && rect.x >= p.x - w / 2 && rect.x + rect.w <= p.x + w / 2 && rect.y >= p.y - h
    }), `${id}: detached furniture collider ${JSON.stringify(rect)}`)
  }
  for (const item of evaluateSlots(room, { healthDone: 999 })) {
    const { w, h } = atlas[item.frame].sourceSize
    assert.ok(item.x - w / 2 >= 32 && item.x + w / 2 <= width - 32 && item.y - h >= 96 && item.y <= height - 24, `${id}: max progress slot outside floor`)
  }
  console.log(`PASS ${id}: parser, frames, spawn, door path, reachable action, sprite hit boxes, furniture footprints (${queue.length} walkable samples)`)
}
