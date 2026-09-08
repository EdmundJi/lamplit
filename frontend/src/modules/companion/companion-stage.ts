import type Phaser from 'phaser'
import { CAFE_DESK_X } from './companion-art'

/** A roofless, walkable small street. Objects use the four locally licensed LimeZu packs. */
export function buildCompanionStage(scene: Phaser.Scene) {
  const objects: Phaser.GameObjects.GameObject[] = []
  const g = scene.add.graphics().setDepth(-200)
  const rect = (x: number, y: number, w: number, h: number, color: number, alpha = 1) => g.fillStyle(color, alpha).fillRect(x, y, w, h)
  const image = (x: number, y: number, frame: string, scale = 1, atlas = 'interior', depth = y) => {
    if (!scene.textures.exists(atlas) || !scene.textures.get(atlas).has(frame)) return undefined
    const sprite = scene.add.image(x, y, atlas, frame).setOrigin(.5, 1).setScale(scale).setDepth(depth)
    objects.push(sprite); return sprite
  }
  const tile = (x: number, y: number, w: number, h: number, frame: string, atlas = 'interior', tint = 0xffffff) => {
    for (let yy = y; yy < y + h; yy += 32) for (let xx = x; xx < x + w; xx += 32) image(xx + 16, yy + 32, frame, 1, atlas, -100)?.setTint(tint)
  }
  const label = (x: number, y: number, text: string) => scene.add.text(x, y, text, { fontFamily: 'system-ui', fontSize: '10px', resolution: 3, color: '#425b45', backgroundColor: '#eef0dd', padding: { x: 5, y: 2 } }).setOrigin(.5).setDepth(750)
  // Exterior: dense planted edges, a horizontal public street and a quieter garden branch.
  rect(0, 0, 960, 640, 0x8da578)
  for (let i = 0; i < 1600; i++) { const x = i * 137 % 960, y = i * 97 % 640; rect(x, y, 2 + i % 3, 2, i % 3 ? 0xa2b28a : 0x6d8e64, .4) }
  tile(32, 364, 896, 96, 'sidewalk_25', 'town', 0xd8d1b6)
  tile(736, 172, 64, 384, 'sidewalk_25', 'town', 0xd8d1b6)
  tile(160, 332, 64, 32, 'sidewalk_25', 'town')
  tile(512, 332, 64, 32, 'sidewalk_25', 'town')
  for (let x = 48; x < 936; x += 32) if (x < 728 || x > 805) image(x, 476, 'bush_1', .85, 'town', 465)
  // The pair of cutaway rooms deliberately expose useful objects and their shared aisle.
  function room(x: number, y: number, w: number, h: number, warm: boolean) {
    rect(x + 8, y + 11, w + 4, h + 6, 0x3a4c3d, .2)
    tile(x, y + 32, w, h - 32, 'floor_1', 'interior', warm ? 0xffebc4 : 0xe8e8d0)
    for (let xx = x; xx < x + w; xx += 32) image(xx + 16, y + 32, warm ? 'wall_1' : 'wall_2', 1, 'interior', y + 33)
    rect(x - 5, y, 6, h, 0x697267); rect(x + w - 1, y, 6, h, 0x697267)
    rect(x - 5, y - 4, w + 10, 5, 0xe4d3b0)
    rect(x, y + h, w, 6, 0xb49d79)
    rect(x, y + h + 6, w, 3, 0xe7d1aa)
  }
  room(80, 108, 256, 224, true)
  room(384, 76, 352, 256, false)
  label(204, 92, '归家小屋')
  label(560, 61, '慢慢咖啡')
  // Two small sleeping corners flank a shared bookshelf, with living space below.
  image(108, 222, 'home_bed_blue', .88, 'companion', 222)
  image(148, 222, 'home_bed_ochre', .88, 'companion', 222)
  image(252, 208, 'home_bed_green', .88, 'companion', 208)
  image(296, 208, 'home_bed_lilac', .88, 'companion', 208)
  rect(184, 142, 7, 78, 0xbca786); rect(185, 142, 5, 5, 0xe4d1ac)
  image(212, 191, 'bookshelf_home_1', .52)
  image(208, 235, 'plant_1', .55)
  image(129, 312, 'rug_pattern_1', .77, 'interior', -70)
  image(128, 290, 'sofa_1', .56)
  image(190, 285, 'coffee_table_wood', .48)
  image(190, 273, 'coffee_cup', .65, 'interior', 286)
  image(273, 286, 'office_desk', .88, 'companion')
  image(288, 266, 'office_lamp', .8, 'companion', 287)
  image(259, 256, 'book_1', .3, 'interior', 287)
  // Native size (not shrunk to .8): a scaled-down chair fits entirely inside the
  // seated resident's own 32px-wide, 64px-tall silhouette and disappears behind them.
  // At native size its back rises a few pixels above the resident's head instead.
  image(270, 291, 'office_chair', 1, 'companion')
  image(103, 312, 'plant_1', .76)
  image(314, 315, 'floor_lamp_1', .72)

  image(204, 355, 'doormat_1', .95)
  // Interior pack: counter, espresso, cups, pastry case and living shelves.
  image(577, 174, 'cafe_counter', 1.55)
  image(641, 151, 'cafe_espresso', 1.05, 'interior', 175)
  image(550, 155, 'cafe_pastries', .85, 'interior', 175)
  image(453, 169, 'fridge_1', .6)
  image(691, 170, 'plant_3', .95)
  image(562, 111, 'cafe_cabinet', 1.3)
  image(651, 118, 'notice_1', .7)
  // Office pack: a communal long desk with lamps and varied chairs, clearly usable seats.
  for (const x of CAFE_DESK_X) {
    image(x, 253, 'office_table', .82, 'companion')
    // A small stack of books resting on the desk, not a shelf-sized prop: book_1 is a
    // tall side-on spine stack (20x62), and at the old .75 scale it read as a stray pole.
    image(x - 13, 224, 'book_1', .3, 'interior', 254)
    image(x + 17, 223, 'office_lamp', .7, 'companion', 254)
    // Native size, see the matching comment by the home desk's chair above.
    image(x, 255, 'office_chair', 1, 'companion')
  }
  image(704, 271, 'office_books', .8, 'companion')
  image(404, 311, 'plant_2', .75)
  image(535, 353, 'doormat_1', 1.2)
  // Farm pack: tilled beds, real crop crates and a small chicken near the garden gate.
  label(850, 216, '门前花园')
  for (const [x, y, frame] of [[823, 287, 'farm_lettuce'], [885, 287, 'farm_carrots'], [823, 366, 'farm_tomatoes'], [885, 366, 'farm_lettuce']] as const) {
    tile(x - 24, y - 35, 32, 32, 'farm_soil', 'companion')
    image(x, y, frame, .9, 'companion')
  }
  image(859, 570, 'gardenbench_1', .9, 'town')
  image(892, 445, 'flowers_3', .8, 'town')
  const chicken = image(846, 523, 'farm_chicken_0', 1.1, 'companion')
  if (chicken) scene.tweens.add({ targets: chicken, y: 521, duration: 1100, yoyo: true, repeat: -1 })
  image(859, 613, 'flowerbush_4', 1.1, 'town')
  label(441, 439, '门前小街')
  // A little lower courtyard makes the visible world feel larger than a single room.
  tile(96, 516, 256, 64, 'sidewalk_25', 'town', 0xd4ccb0)
  image(184, 555, 'gardenbench_1', 1, 'town')
  image(309, 562, 'flowercart_1', .7, 'town')
  image(454, 573, 'flowerbush_4', 1, 'town')
  image(520, 562, 'gardenbench_1', 1, 'town')
  for (const [x, y, scale] of [[26, 160, 1.1], [355, 136, .85], [930, 178, 1.3], [29, 340, 1.1], [949, 380, 1.15], [31, 600, 1.4], [662, 598, 1.2], [920, 642, 1.5]] as const) image(x, y, 'tree_2', scale, 'town')?.setTint(0xd3d6b8)
  for (let x = 44; x < 930; x += 41) image(x, 46, 'bush_2', 1.25, 'town')
  image(364, 362, 'lamp_5', .8, 'town')
  image(746, 462, 'lamp_5', .8, 'town')
  const lights = scene.add.graphics().setDepth(810)
  for (const [x, y] of [[364, 268], [746, 366], [455, 139], [596, 139]]) { lights.fillStyle(0xffd892, .035).fillCircle(x!, y!, 35); lights.fillStyle(0xffd892, .07).fillCircle(x!, y!, 18) }
  return { destroy: () => objects.forEach(o => o.destroy()) }
}
