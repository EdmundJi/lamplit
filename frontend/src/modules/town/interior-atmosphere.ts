import type Phaser from 'phaser'
import type { RoomMapData } from './map-loader'

/** Local fixture light, kept below actors and furniture. No whole-screen haze or white blooms. */
export function createInteriorAtmosphere(scene: Phaser.Scene, room: RoomMapData) {
  const objects: Phaser.GameObjects.GameObject[] = []
  const ground = scene.add.graphics().setDepth(3)
  objects.push(ground)
  const warmPool = (x: number, y: number, width: number, height: number, strength: number) => {
    for (let i = 8; i >= 1; i--) {
      ground.fillStyle(0xf3cf91, strength / 8).fillEllipse(x, y, width * i / 8, height * i / 8)
    }
  }
  if (room.id === 'home-living-room') {
    const lamp = room.furniture.find(piece => piece.frame === 'floor_lamp_1')
    if (lamp) {
      warmPool(lamp.x + 57, lamp.y + 30, 230, 165, .27)
      const glow = scene.add.graphics().setDepth(lamp.depth ?? lamp.y + 1)
      objects.push(glow)
      for (let i = 5; i >= 1; i--) glow.fillStyle(0xffdfa2, .025).fillCircle(lamp.x, lamp.y - 52, i * 7)
      glow.fillStyle(0xffe7b4, .30).fillEllipse(lamp.x, lamp.y - 51, 12, 5)
    }
    const seat = room.furniture.find(piece => piece.frame === 'loveseat_wood')
    if (seat) warmPool(seat.x + 7, seat.y + 43, 190, 112, .13)
  } else if (room.id === 'academy-study') {
    // Use the real desk grid: quieter pools separate places to read without inventing windows.
    for (const seat of room.seats) warmPool(seat.x, seat.y + 20, 93, 70, .10)
  }
  return { destroy() { objects.forEach(object => object.destroy()) } }
}
