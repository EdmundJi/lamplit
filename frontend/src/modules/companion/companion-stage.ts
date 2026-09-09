import type Phaser from 'phaser'
import { CAFE_TABLES, CAFE_WINDOW_SEATS, CAFE_WINDOW_TABLES, CAFE_ROOM, CAFE_WINDOW_ROOM, CAFE_SERVICE, COMPANION_WORLD_SIZE, HOME_ROOMS, GARDEN_OFFSET_X } from './companion-art'

/** A small cutaway street, using the locally licensed LimeZu furniture at one human scale. */
export function buildCompanionStage(scene: Phaser.Scene) {
  const objects: Phaser.GameObjects.GameObject[] = []
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
    objects.push(t)
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
  path(48, 708, 640, 32)
  path(656, 424, 32, 312)
  // A shallow curb gives the street an edge without walling it off in hedges.
  rect(82, 459, 572, 4, 0xe0d6bd, 1, structure)
  for (const home of Object.values(HOME_ROOMS)) path(home.door.x - 16, home.y + home.h, 32, home.y < 400 ? 34 : 24)
  path(CAFE_ROOM.doorX - 16, 332, 32, 32)

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
  const beds = ['home_bed_ochre', 'home_bed_blue', 'home_bed_lilac', 'home_bed_green', 'home_bed_blue']
  Object.entries(HOME_ROOMS).forEach(([id, home], index) => {
    const { x, y, w, h, door } = home
    room(x, y, w, h, door.x, true)
    // Numbered doorsteps stay tied to stable places when residents change careers.
    label(x + w / 2, y - 15, `${index + 1} 号小屋`, true)
    image(x + 32, y + 129, beds[index]!, .875, 'companion')
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
      image(x + 32, y + 182, 'plant_2', .5)
    } else if (id === 'gardener') {
      image(x + w - 36, y + 145, 'plant_1', .5, 'interior', y + 169)
      image(x + 41, y + 181, 'plant_3', .6)
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
  for (const [x, y, scale] of [[26, 164, 1], [357, 93, .75], [936, 181, 1.2], [20, 352, 1], [947, 380, 1], [17, 614, 1.2], [718, 608, .95], [928, 667, 1.35], [781, 738, .8]] as const) plant(x, y, 'tree_2', scale)
  for (const [x, y, scale] of [[37, 44, .8], [75, 39, .9], [111, 47, .7], [755, 88, .8], [794, 96, 1], [839, 87, .8], [899, 53, 1], [936, 51, .8], [34, 487, .8], [747, 665, .9], [789, 657, .8], [883, 726, 1]] as const) plant(x, y, 'bush_2', scale)
  plant(818, 573, 'flowerbush_4', .8)
  image(849 + GARDEN_OFFSET_X, 626, 'gardenbench_1', 1, 'town')?.setTint(0xd2c5a7)
  const chicken = image(839 + GARDEN_OFFSET_X, 541, 'farm_chicken_0', 1, 'companion')
  if (chicken) scene.tweens.add({ targets: chicken, y: 539, duration: 1100, yoyo: true, repeat: -1 })
  return { destroy: () => objects.forEach(o => o.destroy()) }
}
