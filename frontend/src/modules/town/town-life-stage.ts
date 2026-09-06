import type Phaser from 'phaser'
import type { Point, Rect } from './collision'
import { hashString } from './building-kit'

export type TownLifeFacility = {
  id: 'coffee' | 'reading' | 'watering' | 'records'
  label: string
  /** A free standing point beside the furniture, usable by the existing travel/interact system. */
  point: Point
}

/** A pedestrian terrace attached to the existing café. The origin is its north-edge centre. */
export function createTownLifeStage(scene: Phaser.Scene, origin: Point) {
  const { x, y } = origin
  const p = (dx: number, dy: number): Point => ({ x: x + dx, y: y + dy })
  const anchors = {
    door: p(0, -52), table: p(-65, 88), chair: p(-103, 102), otherChair: p(-27, 102),
    readingTable: p(112, 119), reader: p(150, 132), plant: p(-225, 92), gardener: p(-247, 116),
    entry: p(-285, 149), passerPause: p(-10, 148), exit: p(290, 149),
    records: { ...p(245, 70), actionPoint: p(245,91) },
    coffee: { ...p(-65,88), actionPoint: p(-24,85) },
    planter: { ...p(-225,92), actionPoint: p(-247,116) },
    books: { ...p(162,66), actionPoint: p(162,86) },
    bookshelf: p(162, 66), playerSpawn: p(-175, 160),
  }
  const bounds: Rect = { x: x - 320, y, width: 640, height: 204 }
  const objects: Phaser.GameObjects.GameObject[] = []
  const obstacles: Rect[] = []
  const rect = (dx: number, dy: number, width: number, height: number) => {
    const r = { x: x + dx, y: y + dy, width, height }; obstacles.push(r); return r
  }
  const addImage = (dx: number, dy: number, texture: string, frame: string, scale = 1, tint = 0xffffff) => {
    const image = scene.add.image(x + dx, y + dy, texture, frame).setOrigin(.5, 1).setScale(scale).setTint(tint).setDepth(y + dy)
    objects.push(image); return image
  }
  // Reuse the town's actual sidewalk tiles and world-grid alignment. There is no
  // enclosing platform border: north and south continue directly into the existing pavement.
  const floor = scene.add.renderTexture(x - 320, y, 640, 204).setOrigin(0).setDepth(2)
  objects.push(floor)
  floor.beginDraw()
  const firstColumn = Math.floor((x - 320) / 32)
  const lastColumn = Math.ceil((x + 320) / 32)
  for(let row=Math.floor(y/32);row<Math.ceil((y+204)/32);row++) {
    for(let column=firstColumn;column<lastColumn;column++) {
      const noise=hashString(`${column}:${row}`)
      floor.batchDrawFrame('town',`sidewalk_${25 + noise % 4}`,column*32-(x-320),row*32-y)
    }
  }
  floor.endDraw()
  const curb=scene.add.graphics().setDepth(3);objects.push(curb)
  for(const dx of [-320,317]) {
    curb.fillStyle(0xb3b6ae).fillRect(x+dx,y,3,160)
    curb.fillStyle(0x7a817c,.55).fillRect(x+dx+2,y+3,1,154)
  }
  // West-side traffic stops at low timber bollards; the broad middle gap stays walkable.
  for(const dy of [20,64,194]) {
    const bollard = scene.add.graphics().setDepth(y+dy);objects.push(bollard)
    bollard.fillStyle(0x615b43).fillRect(x-316,y+dy-16,8,18)
    bollard.fillStyle(0xc4ae79).fillRect(x-315,y+dy-15,6,3)
    rect(-318,dy-2,12,7)
  }
  // Low planting encloses the space without obscuring the people.
  addImage(-240,27,'town','flowerbush_4',1,0xe1d4b0);rect(-287,16,94,15)
  addImage(255,27,'town','flowerbush_5',1,0xe1d4b0);rect(208,16,94,15)
  addImage(-245,194,'town','flowerbush_1',1,0xd0c8a0);rect(-277,183,64,15)
  addImage(248,194,'town','flowerbush_2',1,0xd0c8a0);rect(216,183,64,15)
  // A familiar atlas tree gives the terrace a leafy edge, not a new backdrop or building.
  addImage(-292,83,'town','tree_2',.88,0xdbd0ae);rect(-300,76,16,10)
  addImage(296,104,'town','tree_1',.86,0xdbd0ae);rect(288,97,16,10)
  addImage(-225,92,'interior','plant_2',.95,0xded6b6);rect(-238,82,26,12)
  // Actual café furniture replaces the former bench-as-table placeholders.
  function table(dx:number,dy:number) {
    const shade=scene.add.ellipse(x+dx,y+dy+2,74,18,0x515640,.17).setDepth(y+dy-15);objects.push(shade)
    const top=scene.add.graphics().setDepth(y+dy);objects.push(top)
    top.fillStyle(0x66543a).fillRect(x+dx-23,y+dy-13,5,15).fillRect(x+dx+18,y+dy-13,5,15)
    top.fillStyle(0x8e6a42).fillEllipse(x+dx,y+dy-13,68,25)
    top.fillStyle(0xc89e65).fillEllipse(x+dx,y+dy-17,68,23)
    top.fillStyle(0xddba7f).fillEllipse(x+dx,y+dy-20,63,17)
    top.lineStyle(1,0xad8854,.7).lineBetween(x+dx-22,y+dy-23,x+dx+22,y+dy-23).lineBetween(x+dx-28,y+dy-18,x+dx+28,y+dy-18)
    rect(dx-32,dy-14,64,18)
  }
  table(-65,88);table(112,119)
  const lanterns = scene.add.graphics().setDepth(y+124);objects.push(lanterns)
  for (const [lx,ly] of [[x-86,y+62],[x+96,y+93]]) {
    lanterns.fillStyle(0x554932).fillRect(lx-5,ly-12,10,15)
    lanterns.fillStyle(0xf5cf85).fillRect(lx-3,ly-10,6,10)
    lanterns.lineStyle(2,0x6b5a3e).strokeRect(lx-5,ly-12,10,15)
  }
  const hostChair=addImage(-103,109,'interior','cafe_chair',1,0xe0d1ac).setDepth(y+99)
  addImage(-27,109,'interior','cafe_chair',1,0xe0d1ac).setFlipX(true).setDepth(y+99)
  addImage(150,139,'interior','cafe_chair',1,0xe0d1ac).setFlipX(true).setDepth(y+129)
  addImage(75,139,'interior','cafe_chair',1,0xe0d1ac).setDepth(y+129)
  rect(-122,100,29,20);rect(-38,100,24,13);rect(139,130,24,13);rect(64,130,24,13)
  // A reachable lending shelf and listening cabinet give background residents specific places to use.
  addImage(162,66,'interior','bookshelf_2',.8,0xe0d4b7);rect(141,56,42,12)
  addImage(245,70,'interior','cafe_cabinet',.62,0xe0d4b7);rect(215,59,60,13)
  const record=scene.add.graphics().setDepth(y+71);objects.push(record)
  record.fillStyle(0x544936).fillRect(x+226,y+44,37,11)
  record.fillStyle(0xbe9761).fillRect(x+227,y+43,35,6)
  record.fillStyle(0x454a40).fillEllipse(x+239,y+45,16,6)
  record.fillStyle(0xd3bb7b).fillRect(x+238,y+44,3,2)
  record.lineStyle(2,0xd3c6a3).lineBetween(x+257,y+43,x+247,y+46)
  addImage(37,30,'interior','plant_1',.65,0xd3d4ac);rect(27,24,20,8)
  // One warm lamp and its low ground spill connect this area with the existing café entrance.
  addImage(-163,37,'town','lamp_5',.9,0xe4d8b4);rect(-170,32,14,9)
  const light=scene.add.graphics().setDepth(3);objects.push(light)
  light.fillStyle(0xffd58e,.10).fillEllipse(x-117,y+45,193,73)
  light.fillStyle(0xffdfa1,.07).fillEllipse(x-64,y+101,181,59)
  const lamp=scene.add.graphics().setDepth(y+38);objects.push(lamp)
  lamp.fillStyle(0xffd697,.10).fillCircle(x-163,y-76,23)
  lamp.fillStyle(0xffd99b,.72).fillRect(x-166,y-82,6,10)
  // Low-key facility labels use the same typography as existing town plates.
  for(const [dx,dy,label] of [[162,14,'街角借书'],[245,25,'今日唱片']] as const) {
    const labelObject=scene.add.text(x+dx,y+dy,label,{fontFamily:'"PingFang SC",sans-serif',fontSize:'10px',color:'#5c593e',backgroundColor:'#e5d8b5',padding:{x:4,y:2}}).setOrigin(.5,1).setDepth(y+dy+1)
    objects.push(labelObject)
  }
  const facilities: TownLifeFacility[] = [
    {id:'coffee',label:'喝杯咖啡',point:p(-24,85)},
    {id:'reading',label:'借一本书',point:p(162,86)},
    {id:'watering',label:'照料花草',point:p(-247,116)},
    {id:'records',label:'听一张唱片',point:p(245,91)},
  ]
  let chairPull=0
  return { origin, bounds, anchors, obstacles, facilities,
    setChairPulledOut(value:boolean) {chairPull=value?1:0},
    update(elapsedMs:number) {
      lamp.setAlpha(.91+Math.sin(elapsedMs/2600)*.06)
      hostChair.setPosition(x-103-chairPull*7,y+109+chairPull*7).setDepth(y+99+chairPull*7)
    },
    destroy() {objects.forEach(object=>object.destroy())},
  }
}
export type TownLifeStage = ReturnType<typeof createTownLifeStage>
