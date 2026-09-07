import type PhaserNs from 'phaser'
import type { FurnitureItem } from '../town-furniture'
import type { TownSceneInstance } from './scene-core'
import { neighbourHouse, NEIGHBOUR_HOUSES, NEIGHBOURHOOD_LANES, NEIGHBOURHOOD_ENTRY } from '../neighbourhood-layout'

/** Human-scale cottages reuse native, licensed modular exterior tiles without resizing. */
export function drawNeighbourhood(scene: TownSceneInstance) {
  const { runtime } = scene
  scene.entrances.set('neighbourhood', NEIGHBOURHOOD_ENTRY)
  scene.buildingCenters.set('neighbourhood', { x: 1296, y: 1632 })
  scene.plate(224, 1210, '↓ 邻里住宅区', '#fff8e8', '#526748')
  scene.plate(680, 1254, '书香 · 花邮 · 晚风  /  每一扇门后，都有自己的日常', '#fff8e8', '#526748')
  for (const house of NEIGHBOUR_HOUSES) {
    const { x, baseY, palette } = house
    const id = `neighbour:${house.code}`
    scene.add.image(x, baseY, 'town', `condo_${palette * 4 + 1}`).setOrigin(0, 1).setDepth(baseY - 3)
    const roofHeight = [192, 128, 96][house.row]!
    scene.add.image(x, baseY - 96, 'town', `roof_${palette * 2 + 2}`).setOrigin(0, 1)
      .setCrop(0, 192 - roofHeight, 224, roofHeight).setDepth(baseY - 3)
    scene.entrances.set(id, house.door)
    scene.buildingCenters.set(id, { x: x + 112, y: baseY - 128 })
    scene.plate(x + 112, baseY - 104 - roofHeight, `${house.label} · ${house.address}`, '#443c32', '#fff7e8').setData('neighbour-code', house.code)
    scene.plate(x + 160, baseY - 12, house.code === 'GUIDE' || house.code === 'POSTMAN' ? '服务站门口' : '家门口', '#fff8e8', '#526748')
    const zone = scene.add.zone(x, baseY - 288, 224, 320).setOrigin(0).setInteractive({ useHandCursor: true })
    zone.on('pointerup', (pointer: PhaserNs.Input.Pointer) => {
      if (runtime.isWorldPointer(pointer) && scene.dragStart && !scene.dragged) scene.travelToPlace(id)
    })
    // Keep mailbox, planting and seating to the garden edge, clear of the door approach.
    const gardenX = x + 242
    scene.add.image(gardenX, baseY - 8, 'town', `flowerbush_${1 + palette}`).setOrigin(0.5, 1).setDepth(baseY - 8)
    scene.add.image(x + 24, baseY + 8, 'town', 'mailbox_1').setOrigin(.5, 1).setDepth(baseY + 8)
    scene.glow(x + 160, baseY - 48, 176, 132, .3)
    const personal: Record<string, string[]> = { KE_YUN: ['book_1', 'plant_1'], LU_XIA: ['gymplate_1', 'exercise_ball_1'],
      SHEN_MU: ['globe_1', 'plant_2'], WEN_QING: ['plant_3', 'side_table_round'], AN_HE: ['plant_1', 'plant_2', 'plant_3'], JI_MAI: ['bar_stool_1', 'plant_2'] }
    for (const [index, frame] of (personal[house.code] ?? []).entries()) {
      scene.add.image(x + 60 + index * 32, baseY + 2, 'interior', frame).setOrigin(.5, 1).setDepth(baseY + 2)
    }
    if (house.row > 0) {
      scene.add.image(x + 40, baseY - 224, 'town', `tree_${1 + palette}`).setOrigin(.5,1).setDepth(baseY - 300)
      scene.add.image(x + 172, baseY - 242, 'town', `bush_${1 + palette}`).setOrigin(.5,1).setDepth(baseY - 300)
    }
  }
  for (let row = 0; row < 3; row++) {
    const laneY = 1600 + row * 448
    scene.plate(224, laneY - 28, NEIGHBOURHOOD_LANES[row]!, '#fff8e8', '#526748')
    for (const x of [288, 2560]) {
      scene.add.image(x, laneY + 96, 'town', 'lamp_5').setOrigin(.5, 1).setDepth(laneY + 96)
      scene.glow(x, laneY + 36, 180, 160, .4)
    }
    for (let column = 0; column < 7; column++) {
      const x = 370 + column * 320
      scene.add.image(x, laneY + 198, 'town', `tree_${1 + (column + row) % 5}`).setOrigin(.5, 1).setDepth(laneY + 198)
      scene.add.image(x + 100, laneY + 160, 'town', `flowers_${1 + (column + row) % 5}`).setOrigin(.5, 1).setDepth(laneY + 160)
    }
  }
  refreshNeighbourLabels(scene)
  // Small shared destinations make the end of a lane worth walking to.
  for (const [y, title, flower] of [[1990, '花邮小院 · 坐一会儿', 'flowercart_1'], [2438, '晚风花园 · 等一位邻居', 'flowers_4']] as const) {
    scene.plate(2416, y - 120, title, '#fff8e8', '#526748')
    scene.add.image(2400, y - 20, 'town', flower).setOrigin(.5, 1).setDepth(y - 20)
    scene.add.image(2500, y - 32, 'town', 'flowerbush_2').setOrigin(.5, 1).setDepth(y - 32)
    scene.add.image(2296, y - 32, 'town', 'flowerbush_4').setOrigin(.5, 1).setDepth(y - 32)
  }
}

/** Reflect migrations and renamed visitors without moving anyone's stable address. */
export function refreshNeighbourLabels(scene: TownSceneInstance) {
  for (const object of scene.children.list) {
    const code = object.getData?.('neighbour-code') as string | undefined
    if (!code) continue
    const house = neighbourHouse(code)
    if (!house) continue
    const npc = scene.runtime.townNpcRoster.find(item => item.code === code)
    const title = code === 'GUIDE' || code === 'POSTMAN' ? house.label
      : code === 'TRAVELER' ? npc ? `旅人客舍 · ${npc.displayName}` : '旅人客舍 · 暂无旅人'
      : npc ? `${npc.displayName}的家` : house.label
    ;(object as PhaserNs.GameObjects.Text).setText(`${title} · ${house.address}`)
  }
}

/** Included in the same registry used by furniture navigation and arrival actions. */
export function neighbourhoodFurniture(): FurnitureItem[] {
  return [1990, 2438].map(y => ({ id: `neighbour-bench-${y}`, x: 2320, y: y + 64,
    frame: 'gardenbench_1', interactive: true, interactionType: 'sit',
    collision: { width: 96, height: 18, offsetX: 0, offsetY: 0 } }))
}
