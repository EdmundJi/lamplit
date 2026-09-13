import type Phaser from 'phaser'
import { CAFE_TABLES, CAFE_WINDOW_SEATS, CAFE_WINDOW_TABLES, CAFE_ROOM, CAFE_WINDOW_ROOM, CAFE_SERVICE, COMPANION_WORLD_SIZE, HOME_ROOMS, GARDEN_OFFSET_X, ACADEMY_ROOM, GYM_ROOM, BOARD_AREA } from './companion-art'

/** A small cutaway street, using the locally licensed LimeZu furniture at one human scale. */
export function buildCompanionStage(scene: Phaser.Scene) {
  const objects: Phaser.GameObjects.GameObject[] = []
  // Every small in-canvas place name ("点单"/"取餐"/"门前花园", numbered doorsteps, "慢慢咖啡") drawn
  // by label() below, kept separately so CompanionStreetScene can hide them all together for the
  // docked street strip (see SceneSnapshot.chrome) without touching the rest of the stage.
  const signage: Phaser.GameObjects.Text[] = []
  const graphics = (depth: number) => { const g = scene.add.graphics().setDepth(depth); objects.push(g); return g }
  const ground = graphics(-200), structure = graphics(-50)
  const rect = (x: number, y: number, w: number, h: number, color: number, alpha = 1, g = ground) => g.fillStyle(color, alpha).fillRect(x, y, w, h)
  const image = (x: number, y: number, frame: string, scale = 1, atlas = 'interior', depth = y) => {
    if (!scene.textures.exists(atlas) || !scene.textures.get(atlas).has(frame)) return undefined
    const sprite = scene.add.image(x, y, atlas, frame).setOrigin(.5, 1).setScale(scale).setDepth(depth)
    objects.push(sprite); return sprite
  }
  const tile = (x: number, y: number, w: number, h: number, frame: string, atlas = 'interior', tint = 0xffffff, alpha = 1) => {
    for (let yy = y; yy < y + h; yy += 32) for (let xx = x; xx < x + w; xx += 32) {
      const sprite = image(xx + 16, yy + 32, frame, 1, atlas, -100)?.setTint(tint).setAlpha(alpha)
      // Floors and paths must never spill beyond their footprint.
      if (sprite) sprite.setCrop(0, 0, Math.min(32, x + w - xx), Math.min(32, y + h - yy))
    }
  }
  const label = (x: number, y: number, text: string, small = false) => {
    const t = scene.add.text(x, y, text, { fontFamily: 'system-ui', fontSize: small ? '8px' : '10px', resolution: 3, color: '#4f594b', backgroundColor: '#e8e3d0', padding: { x: 5, y: 2 } }).setOrigin(.5).setDepth(750)
    objects.push(t); signage.push(t)
  }
  const plant = (x: number, y: number, frame: string, scale = 1) => image(x >= 736 ? x + GARDEN_OFFSET_X : x, y, frame, scale, 'town')?.setTint(0xb8c0a5)
  const path = (x: number, y: number, w: number, h: number) => {
    // Let connected patches merge; outlining every rectangle draws false seams at junctions.
    rect(x, y, w, h, 0xc5bfa8)
    tile(x, y, w, h, 'sidewalk_25', 'town', 0xddd5bd, .32)
  }
  rect(0, 0, COMPANION_WORLD_SIZE.width, COMPANION_WORLD_SIZE.height, 0x96a486)
  // Sparse ground texture and grouped planting leave people as the highest contrast detail.
  for (let i = 0; i < 430; i++) rect(i * 137 % COMPANION_WORLD_SIZE.width, i * 97 % 768, 2, 1, i % 3 ? 0xc2c8a7 : 0x788e71, .3)
  path(32, 364, 816, 96)
  path(816, 424, 32, 168)
  path(816, 560, 256, 32)
  path(1040, 424, 32, 168)
  path(752 + GARDEN_OFFSET_X, 224, 40, 236)
  path(48, 424, 32, 312)
  // Widened to also pass under fixer's new house doorstep (x:1186-1218) - kept identical to the
  // matching walkable rect in companion-navigation.ts so the drawn path and the collision box
  // never disagree.
  path(48, 708, 1170, 32)
  path(656, 424, 32, 312)
  // A shallow curb gives the street an edge without walling it off in hedges.
  rect(82, 459, 572, 4, 0xe0d6bd, 1, structure)
  for (const home of Object.values(HOME_ROOMS)) path(home.door.x - 16, home.y + home.h, 32, home.y < 400 ? 34 : 24)
  path(CAFE_ROOM.doorX - 16, 332, 32, 32)
  // East wing, past the old x=1248 edge: a single vertical corridor connects academy's own
  // doorway, through the open-air board plaza, into the gym's doorway, down to the same bottom
  // path fixer's house already uses - see ACADEMY_ROOM/GYM_ROOM/BOARD_AREA in companion-art.ts.
  // The bottom path itself is extended flush to the new world edge (old end 1218 -> new end 1548).
  path(1218, 708, 330, 32)
  path(ACADEMY_ROOM.doorX - 16, ACADEMY_ROOM.y + ACADEMY_ROOM.h, 32, BOARD_AREA.y - (ACADEMY_ROOM.y + ACADEMY_ROOM.h))
  path(BOARD_AREA.x, BOARD_AREA.y, BOARD_AREA.w, BOARD_AREA.h)
  path(GYM_ROOM.doorX - 16, BOARD_AREA.y + BOARD_AREA.h, 32, GYM_ROOM.y - (BOARD_AREA.y + BOARD_AREA.h))
  path(GYM_ROOM.doorX - 16, GYM_ROOM.y + GYM_ROOM.h, 32, 24)

  function room(x: number, y: number, w: number, h: number, doorX: number | undefined, warm = false, openRight = false, openLeftUntil = y) {
    rect(x + 8, y + 12, w + 2, h + 1, 0x52634d, .23)
    rect(x, y + 32, w, h - 32, warm ? 0xc6b491 : 0xc4b99d)
    tile(x, y + 32, w, h - 32, 'floor_1', 'interior', 0xf2e4c7, .23)
    for (let xx = x; xx < x + w; xx += 32) image(xx + 16, y + 32, 'wall_2', 1, 'interior', y + 33)?.setTint(0xece4d0)
    // Cream plaster, a darker foundation, and a cut front wall with an open doorway.
    rect(x - 6, openLeftUntil, 7, y + h + 7 - openLeftUntil, 0x7f806c, 1, structure)
    if (!openRight) rect(x + w - 1, y, 7, h + 7, 0x7f806c, 1, structure)
    rect(x - 6, y - 5, w + 12, 6, 0xe8dfc5, 1, structure)
    rect(x - 6, openLeftUntil, 3, y + h - openLeftUntil, 0xc9bfa4, 1, structure)
    if (!openRight) rect(x + w + 3, y, 3, h, 0xd9cfb4, 1, structure)
    const front = graphics(y + h + 5)
    for (const [left, width] of (doorX === undefined ? [[x - 6, w + 12]] : [[x - 6, doorX - 16 - (x - 6)], [doorX + 16, x + w + 6 - (doorX + 16)]])) {
      rect(left!, y + h - 4, width!, 11, 0x8f8a73, 1, front)
      rect(left!, y + h - 4, width!, 3, 0xe7ddc2, 1, front)
    }
    if (doorX !== undefined) {
      rect(doorX - 16, y + h - 2, 32, 6, 0xdfd1b1, 1, structure)
      image(doorX, y + h + 15, 'doormat_1', .7)?.setTint(0xc8b99d)
    }
  }
  // Only four bed colours exist in the licensed set; fixer reuses ochre (already reused for self)
  // rather than needing a fifth colour. Cycled with modulo (not a direct index) so HOME_ROOMS
  // growing past six entries - the next home added for the 25-person roster - still gets a real
  // bed colour instead of `beds[index]` running off the end of this list into undefined.
  const beds = ['home_bed_ochre', 'home_bed_blue', 'home_bed_lilac', 'home_bed_green', 'home_bed_blue', 'home_bed_ochre']
  Object.entries(HOME_ROOMS).forEach(([id, home], index) => {
    const { x, y, w, h, door } = home
    room(x, y, w, h, door.x, true)
    // Numbered doorsteps stay tied to stable places when residents change careers.
    label(x + w / 2, y - 15, `${index + 1} 号小屋`, true)
    image(x + 32, y + 129, beds[index % beds.length]!, .875, 'companion')
    image(x + w - 35, y + 168, 'cafe_table', .75)
    image(x + w - 72, y + 168, 'chair_2', 1, 'interior', y + 167)?.setFlipX(true)
    image(x + 37, y + h - 19, 'rug_pattern_2', .85, 'interior', -75)?.setTint(0xc9bb9b)
    if (id === 'owner') {
      image(x + w - 31, y + 149, 'coffee_cup', .7, 'interior', y + 169)
      image(x + 30, y + 181, 'plant_1', .5)
    } else if (id === 'student') {
      image(x + w - 20, y + 147, 'office_lamp', .65, 'companion', y + 169)
    } else if (id === 'artist') {
      image(x + w - 31, y + 138, 'project_poster', .55, 'companion')
      // Moved from (x+32,y+182) into the open gap between the two beds, which weaver's own bed
      // and desk/chair below would otherwise have crowded it into.
      image(x + 69, y + 98, 'plant_2', .5)
      // 阿满 (weaver) shares this room as a flat-mate: her own bed and desk, at hand-picked spots
      // (POSITION_SLOTS['home-weaver-bed'/'home-weaver-desk']) clear of both 知夏's furniture and
      // this room's collision obstacles, and drawn with a different bed colour and a different
      // desk piece (desk_1 instead of the shared cafe_table) so it reads as two people sharing on
      // purpose, not the old "everyone sleeps in one bed" bug. Smaller scale than 知夏's own set -
      // this room only has one spare corner - the bed kept in the same y-band as her bed image so
      // the sprite's own pixels stay below the top wall instead of poking through it, and the
      // chair/desk pair sitting side by side (not stacked) on the rug below her own bed, in the
      // narrow column left of 知夏's own chair/table instead of behind or on top of them.
      image(x + 104, y + 118, 'home_bed_green', .65, 'companion')
      image(x + 22, y + 178, 'chair_2', .5, 'interior', y + 177)?.setFlipX(true)
      image(x + 42, y + 178, 'desk_1', .5, 'interior', y + 178)
    } else if (id === 'gardener') {
      image(x + w - 36, y + 145, 'plant_1', .5, 'interior', y + 169)
      image(x + 41, y + 181, 'plant_3', .6)
    } else if (id === 'fixer') {
      // 周野 repairs things - bicycles, furniture, lamps - so his room gets a wall cabinet for
      // tools instead of the generic decorative plant/lamp/poster the other homes use.
      image(x + w - 24, y + 148, 'wall_cabinet_1', .6, 'interior', y + 169)
      image(x + 30, y + 181, 'plant_1', .5)
    } else {
      image(x + w - 41, y + 150, 'coffee_cup', .65, 'interior', y + 169)
      image(x + 33, y + 182, 'plant_1', .5)
    }
  })

  room(CAFE_ROOM.x, CAFE_ROOM.y, CAFE_ROOM.w, CAFE_ROOM.h, CAFE_ROOM.doorX, false, true)
  room(CAFE_WINDOW_ROOM.x, CAFE_WINDOW_ROOM.y, CAFE_WINDOW_ROOM.w, CAFE_WINDOW_ROOM.h, undefined, false, false, 332)
  label(624, 27, '慢慢咖啡')
  // A back worktop and a customer counter enclose an actual staff aisle, open at the left.
  image(734, 80, 'cafe_counter', 1.3)
  image(730, 55, 'cafe_espresso', 1, 'interior', 81)
  image(790, 59, 'cafe_moka', .8, 'interior', 81)
  image(605, 78, 'fridge_1', .55)
  image(734, 178, 'cafe_counter', 1.4)
  image(671, 153, 'cafe_pastries', .8, 'interior', 179)
  image(800, 160, 'coffee_cup', .75, 'interior', 179)
  image(409, 98, 'office_books', .6, 'companion')
  image(411, 315, 'plant_2', .65)
  // Minimal counter signs make the order/pickup relationship readable without floor arrows.
  label(CAFE_SERVICE.order.x, 185, '点单', true)
  label(CAFE_SERVICE.pickup.x, 185, '取餐', true)
  const sideChair = (seat: { x: number; y: number; facing: 'left' | 'right' }) => {
    image(seat.x, seat.y - 3, 'chair_2', 1, 'interior', seat.y - 1)?.setFlipX(seat.facing === 'right')
  }
  for (const table of CAFE_TABLES) {
    image(table.x, table.y, 'cafe_table', 1, 'interior', table.y - 1)
    table.seats.forEach(sideChair)
    image(table.x, table.y - 14, 'coffee_cup', .6, 'interior', table.y)
  }
  // Six native vertical tables touch end-to-end against the window wall. The same-facing
  // chairs belong to independent capacity-one positions, separate from the discussion tables.
  // Use only the licensed tabletop region, excluding the legs that otherwise create a gap
  // between modules. Six 69px panels share a continuous edge and a pair of end supports.
  rect(975, 104, 47, 417, 0x756858, 1, structure)
  rect(977, 106, 43, 413, 0xcab18d, 1, structure)
  for (const table of CAFE_WINDOW_TABLES) {
    image(table.x, table.y - 69, 'dining_table_1', 1, 'interior', table.y - 21)
      ?.setOrigin(.5, 0).setCrop(0, 0, 60, 76).setScale(.75, 69 / 76)
  }
  rect(980, 521, 4, 5, 0x756858, 1, structure)
  rect(1013, 521, 4, 5, 0x756858, 1, structure)
  CAFE_WINDOW_SEATS.forEach(sideChair)
  rect(1017, 76, 8, 452, 0x7d806c, 1, structure)
  rect(1019, 78, 4, 448, 0xb8cfca, 1, structure)
  for (const y of [150, 223, 292, 361, 430, 499]) rect(1019, y, 4, 2, 0xe5dac0, 1, structure)

  label(853 + GARDEN_OFFSET_X, 211, '门前花园')
  // The public bench is separate from the working plot.
  for (const [x, y, frame] of [[823, 287, 'farm_lettuce'], [885, 287, 'farm_carrots'], [823, 366, 'farm_tomatoes'], [885, 366, 'farm_lettuce']] as const) {
    rect(x + GARDEN_OFFSET_X - 26, y - 37, 52, 39, 0x7f7256)
    tile(x + GARDEN_OFFSET_X - 24, y - 35, 48, 35, 'farm_soil', 'companion')
    image(x + GARDEN_OFFSET_X, y, frame, .9, 'companion')?.setTint(0xd3d0ac)
  }
  path(801 + GARDEN_OFFSET_X, 390, 104, 63)
  image(849 + GARDEN_OFFSET_X, 433, 'gardenbench_1', 1, 'town')?.setTint(0xd2c5a7)
  plant(918, 440, 'flowers_3', .7)
  for (const x of [290, 500]) image(x, 448, 'gardenbench_1', 1, 'town')?.setTint(0xd2c5a7)
  plant(357, 454, 'flowerbush_4', .65)
  plant(565, 456, 'flowerbush_4', .65)
  image(362, 359, 'lamp_5', .7, 'town')
  image(796 + GARDEN_OFFSET_X, 457, 'lamp_5', .7, 'town')
  // Grouped foliage frames corners and open lawn, rather than repeated hedge borders.
  // Two entries that used to sit in the open lawn below-right of the garden ([928,667,1.35] tree,
  // [781,738,.8] tree) were removed/moved - that lawn is now fixer's house footprint (HOME_ROOMS.
  // fixer). [781,738] is nudged to clear open ground instead of grazing the new building's corner.
  for (const [x, y, scale] of [[26, 164, 1], [357, 93, .75], [936, 181, 1.2], [20, 352, 1], [947, 380, 1], [17, 614, 1.2], [718, 608, .95], [700, 700, .8]] as const) plant(x, y, 'tree_2', scale)
  // Two entries that used to sit in the same now-built-on lawn ([789,657,.8], [883,726,1]) were
  // dropped rather than relocated - they were background filler, not load-bearing detail.
  for (const [x, y, scale] of [[37, 44, .8], [75, 39, .9], [111, 47, .7], [755, 88, .8], [794, 96, 1], [839, 87, .8], [899, 53, 1], [936, 51, .8], [34, 487, .8], [747, 665, .9]] as const) plant(x, y, 'bush_2', scale)
  // The flowerbush and the third (purely decorative, unpositioned) garden bench that used to sit
  // here were dropped for the same reason - fixer's house now occupies that ground.
  // The chicken survives, moved up next to the farm beds it always belonged near.
  const chicken = image(1195, 405, 'farm_chicken_0', 1, 'companion')
  if (chicken) scene.tweens.add({ targets: chicken, y: 403, duration: 1100, yoyo: true, repeat: -1 })

  // --- East wing: academy, board plaza, gym - the three places past the old x=1248 canvas edge
  // (see ACADEMY_ROOM/GYM_ROOM/BOARD_AREA in companion-art.ts). Academy and gym reuse the same
  // room() footprint/wall style as the homes and cafe above; the board is a small open-air plaza,
  // not a walled room, the same way the garden's public bench sits on open paving.
  room(ACADEMY_ROOM.x, ACADEMY_ROOM.y, ACADEMY_ROOM.w, ACADEMY_ROOM.h, ACADEMY_ROOM.doorX)
  label(ACADEMY_ROOM.x + ACADEMY_ROOM.w / 2, ACADEMY_ROOM.y - 15, '学院')
  image(ACADEMY_ROOM.x + 60, ACADEMY_ROOM.y + 118, 'bookshelf_1', .9)
  image(ACADEMY_ROOM.x + ACADEMY_ROOM.w - 50, ACADEMY_ROOM.y + 118, 'bookshelf_2', .9)
  image(ACADEMY_ROOM.x + ACADEMY_ROOM.w / 2, ACADEMY_ROOM.y + 90, 'office_board', .75, 'companion')
  image(ACADEMY_ROOM.x + ACADEMY_ROOM.w / 2, ACADEMY_ROOM.y + 165, 'study_desk_front', .85)
  image(ACADEMY_ROOM.x + ACADEMY_ROOM.w / 2 - 30, ACADEMY_ROOM.y + 168, 'chair_1', .85, 'interior', ACADEMY_ROOM.y + 167)?.setFlipX(true)
  image(ACADEMY_ROOM.x + 40, ACADEMY_ROOM.y + 198, 'office_books', .45, 'companion')

  room(GYM_ROOM.x, GYM_ROOM.y, GYM_ROOM.w, GYM_ROOM.h, GYM_ROOM.doorX)
  label(GYM_ROOM.x + GYM_ROOM.w / 2, GYM_ROOM.y - 15, '健身房')
  image(GYM_ROOM.x + GYM_ROOM.w / 2, GYM_ROOM.y + 90, 'gymmirror_1', .85)
  image(GYM_ROOM.x + 55, GYM_ROOM.y + 100, 'gymrack_1', .8)
  image(GYM_ROOM.x + 90, GYM_ROOM.y + 170, 'gym_elliptical_1', .8)
  image(GYM_ROOM.x + GYM_ROOM.w - 40, GYM_ROOM.y + 150, 'gymbike_1', .8)
  image(GYM_ROOM.x + GYM_ROOM.w - 70, GYM_ROOM.y + 205, 'gym_yoga_mat', .85)
  image(GYM_ROOM.x + 150, GYM_ROOM.y + 205, 'gymplate_1', .8)

  label(BOARD_AREA.x + BOARD_AREA.w / 2, BOARD_AREA.y - 15, '公告板')
  image(BOARD_AREA.x + 70, BOARD_AREA.y + 70, 'board_2', 1)
  image(BOARD_AREA.x + 140, BOARD_AREA.y + 70, 'board_2', 1)
  image(BOARD_AREA.x + 66, BOARD_AREA.y + 52, 'notice_1', .8)
  image(BOARD_AREA.x + 136, BOARD_AREA.y + 55, 'notice_1', .8)
  image(BOARD_AREA.x + 100, BOARD_AREA.y + 120, 'bench_1', .9)
  image(BOARD_AREA.x + 180, BOARD_AREA.y + 40, 'lamp_5', .7, 'town')

  // A small amount of non-resident ambient life on the street (docs/01: "少量生活动作，让它适合放
  // 在旁边长时间陪伴"). Purely decorative - nothing here is ever a target for pathfinding or a
  // click - and deliberately slow/quiet: the bar is "sits next to someone studying for hours
  // without pulling the eye", not a lively scene.
  if (scene.textures.exists('town') && scene.textures.get('town').has('pigeon_1')) {
    if (!scene.anims.exists('street-pigeon-peck')) {
      scene.anims.create({ key: 'street-pigeon-peck', frames: [1, 2, 3, 4, 5, 6].map(n => ({ key: 'town', frame: `pigeon_${n}` })), frameRate: 3, repeat: -1 })
    }
    // Well clear of the benches (x 290/500) and the lamp (362,359) so nothing overlaps.
    for (const [x, y] of [[140, 412], [612, 420]] as const) {
      const pigeon = scene.add.sprite(x, y, 'town', 'pigeon_1').setScale(.55).setDepth(y)
      objects.push(pigeon)
      pigeon.play('street-pigeon-peck')
      // A slow, occasional few-pixel hop rather than a walk cycle - present, not attention-grabbing.
      scene.tweens.add({ targets: pigeon, x: x + 16, duration: 4800 + Math.random() * 2200, delay: Math.random() * 4000, yoyo: true, repeat: -1, ease: 'Sine.easeInOut' })
    }
  }
  // A couple of leaves drifting over open lawn - cheap graphics, no extra art asset needed.
  for (const [x, y] of [[905, 300], [1005, 350]] as const) {
    const leaf = scene.add.graphics().setDepth(150)
    leaf.fillStyle(0x8a9a5b, .8).fillEllipse(0, 0, 5, 3)
    leaf.setPosition(x, y)
    objects.push(leaf)
    scene.tweens.add({ targets: leaf, x: x + 26, y: y + 34, angle: 40, duration: 9000 + Math.random() * 3000, delay: Math.random() * 5000, yoyo: true, repeat: -1, ease: 'Sine.easeInOut' })
  }
  return { destroy: () => objects.forEach(o => o.destroy()), signage }
}
