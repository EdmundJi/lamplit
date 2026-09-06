import type Phaser from 'phaser'

export type CafePoint = { x: number; y: number }
export type CafeLifeAnchors = {
  door: CafePoint; table: CafePoint; hostSeat: CafePoint; guestSeat: CafePoint
  alley: CafePoint; passerExit: CafePoint; reader: CafePoint; plant: CafePoint
}
export const CAFE_LIFE_ANCHORS: CafeLifeAnchors = {
  door: { x: 560, y: 340 }, table: { x: 440, y: 414 }, hostSeat: { x: 400, y: 425 },
  guestSeat: { x: 480, y: 425 }, alley: { x: 688, y: 266 }, passerExit: { x: 925, y: 475 },
  reader: { x: 770, y: 405 }, plant: { x: 300, y: 382 },
}
export const CAFE_PLAYER_TEXTURE = 'cafe-person-4'
export const CAFE_DURATION_MS = 30_000
const sheets = ['c03', 'c08', 'c12', 'c17', 'c01']
const directions = { right: 0, up: 1, left: 2, down: 3 } as const
export type CafeDirection = keyof typeof directions
export function preloadCafeLife(scene: Phaser.Scene) {
  sheets.forEach((name, i) => scene.load.spritesheet(`cafe-person-${i}`, `/assets/town/characters/${name}.png`, { frameWidth: 32, frameHeight: 64 }))
}
/** Absolute-time sprite sampling keeps pause, scrubbing and replay deterministic. */
export function poseCafePerson(sprite: Phaser.GameObjects.Sprite, ms: number, direction: CafeDirection, walking = false, reading = false) {
  const columns = Math.floor((sprite.texture.getSourceImage() as HTMLImageElement).width / 32)
  sprite.setFrame((reading ? 7 : walking ? 2 : 1) * columns + (reading ? 0 : directions[direction] * 6) + Math.floor(ms / (walking ? 115 : 240)) % 6)
}
const clamp = (v: number) => Math.max(0, Math.min(1, v))
const lerp = (a: CafePoint, b: CafePoint, t: number): CafePoint => ({ x: a.x + (b.x - a.x) * clamp(t), y: a.y + (b.y - a.y) * clamp(t) })
const between = (t: number, a: number, b: number) => t >= a && t < b
export type CafeLifeSnapshot = {
  elapsedMs: number; phase: string; host: CafePoint & { action: string }; passer: CafePoint & { action: string }
  cups: { x: number; y: number; location: 'hand' | 'table' }[]
  reader: { action: string }; gardener: { action: string }; wetSoil: boolean; chairPulledOut: boolean; bubble: string | null
}
export function createCafeLife(scene: Phaser.Scene, overrides: Partial<CafeLifeAnchors> = {}) {
  const a = { ...CAFE_LIFE_ANCHORS, ...overrides }
  const owned: Phaser.GameObjects.GameObject[] = []
  const keep = <T extends Phaser.GameObjects.GameObject>(o: T): T => { owned.push(o); return o }
  const shadows = Array.from({ length: 4 }, () => keep(scene.add.ellipse(0, 0, 29, 9, 0x372924, .2)))
  const people = sheets.slice(0, 4).map((_, i) => keep(scene.add.sprite(0, 0, `cafe-person-${i}`).setOrigin(.5, 1).setScale(1.4)))
  const [host, passer, reader, gardener] = people
  const propLayers = Array.from({ length: 4 }, () => keep(scene.add.graphics()))
  let props = propLayers[0]
  const soil = keep(scene.add.ellipse(a.plant.x, a.plant.y - 3, 25, 8, 0x493628).setDepth(a.plant.y + 1).setAlpha(0))
  const bubble = keep(scene.add.text(0, 0, '', { fontFamily: 'sans-serif', fontSize: '14px', color: '#514238', backgroundColor: '#fff4d9', padding: { x: 12, y: 8 }, align: 'center' }).setOrigin(.5, 1).setDepth(2000))
  let labels = true
  let snapshot: CafeLifeSnapshot
  function place(sprite: Phaser.GameObjects.Sprite, index: number, p: CafePoint, t: number, dir: CafeDirection, walk = false, read = false, seated = false) {
    sprite.setPosition(p.x, p.y + (seated ? 8 : 0)).setDepth(p.y + 2)
    poseCafePerson(sprite, t * 1000, dir, walk, read)
    // Crop straight standing legs when seated; bent knees are drawn below at seat height.
    if (seated) sprite.setCrop(0, 0, 32, 54)
    else sprite.setCrop()
    shadows[index].setPosition(p.x, p.y - 2).setDepth(p.y - 1).setScale(seated ? .75 : 1)
  }
  function cup(x: number, y: number, t: number, color: number) {
    props.fillStyle(0x44352c, .14).fillEllipse(x + 2, y + 6, 17, 5)
    props.fillStyle(0xf8e9ca).fillEllipse(x, y + 4, 17, 5)
    props.fillStyle(color).fillRoundedRect(x - 5, y - 6, 10, 10, 2)
    props.lineStyle(2, color).strokeCircle(x + 6, y - 2, 3)
    props.fillStyle(0x6b4229).fillEllipse(x, y - 5, 8, 3)
    props.lineStyle(1, 0xfff4dc, .5)
    for (let i = 0; i < 2; i++) { const rise = (t * 10 + i * 8) % 18; props.beginPath(); props.moveTo(x - 2 + i * 5, y - 10 - rise); props.lineTo(x + Math.sin(t * 2 + i) * 2 + i * 4, y - 15 - rise); props.strokePath() }
  }
  function update(elapsedMs: number) {
    const t = Math.max(0, elapsedMs / 1000)
    const walkStart = { x: a.table.x + 46, y: 466 }
    let hp = a.door, hostAction = '端着两杯咖啡出门', hd: CafeDirection = 'down', hw = false
    if (t < 1.5) hp = lerp({ x: a.door.x, y: a.door.y - 12 }, a.door, t / 1.5)
    else if (t < 5.5) { hp = lerp(a.door, walkStart, (t - 1.5) / 4); hw = true; hd = 'down' }
    else if (t < 7) { hp = lerp(walkStart, a.hostSeat, (t - 5.5) / 1.5); hw = true; hd = 'left' }
    else if (t < 21.5) { hp = a.hostSeat; hd = 'right'; hostAction = t < 9 ? '坐下，把第二杯放在对面' : t < 16 ? '留着对面的咖啡，望向路口' : t < 19 ? '看着熟人挥手走远' : '把没动过的第二杯挪回身边' }
    else if (t < 23) { hp = lerp(a.hostSeat, { x: 385, y: 452 }, (t - 21.5) / 1.5); hd = 'down'; hw = true; hostAction = '起身离开，杯子留在桌上' }
    else if (t < 28.5) { hp = lerp({ x: 385, y: 452 }, { x: 172, y: 470 }, (t - 23) / 5.5); hd = 'left'; hw = true; hostAction = '慢慢走向街角' }
    else { hp = { x: 172, y: 470 }; hd = 'up'; hostAction = '停在花店橱窗前' }
    if (between(t, 10, 12.5)) hostAction = '端起自己的杯子，喝一小口'
    place(host, 0, hp, t, hd, hw, false, between(t, 8, 21.5))
    let pp = a.alley, pd: CafeDirection = 'down', pw = false, passerAction = '从小巷走来'
    if (t >= 3 && t < 7) { pp = lerp(a.alley, { x: 688, y: 462 }, (t - 3) / 4); pw = true }
    else if (t >= 7 && t < 13) { pp = lerp({ x: 688, y: 462 }, { x: 470, y: 462 }, (t - 7) / 6); pd = 'left'; pw = true }
    else if (t >= 13 && t < 16) { pp = { x: 470, y: 462 }; pd = 'left'; passerAction = '停下，向朋友挥手' }
    else if (t >= 16) { pp = lerp({ x: 470, y: 462 }, a.passerExit, (t - 16) / 10); pd = 'right'; pw = t < 26; passerAction = '挥手告别，继续赶路' }
    place(passer, 1, pp, t, pd, pw)
    passer.setAlpha(t < 1 ? 0 : t > 25 ? 1 - clamp((t - 25) / 1) : 1)
    shadows[1].setAlpha(passer.alpha * .8)
    place(reader, 2, a.reader, t, 'left', false, true, true)
    const gp = { x: a.plant.x - 31, y: a.plant.y + 27 }
    place(gardener, 3, gp, t, 'right')
    gardener.setAngle(between(t % 12, 2, 7) ? 5 : 0)
    soil.setAlpha(t >= 3 ? .9 : 0)
    propLayers.forEach(layer => layer.clear())
    propLayers[0].setDepth(t < 7 ? hp.y + 3 : Math.max(hp.y, a.table.y) + 3)
    propLayers[1].setDepth(pp.y + 3)
    propLayers[2].setDepth(a.reader.y + 3)
    propLayers[3].setDepth(gp.y + 3)
    props = propLayers[0]
    const carry = t < 9
    const ownTable = { x: a.table.x - 15, y: a.table.y - 16 }
    let own = t < 7 ? { x: hp.x - 8, y: hp.y - 31 } : lerp({ x: a.hostSeat.x - 8, y: a.hostSeat.y - 31 }, ownTable, (t - 7) / 2)
    if (between(t, 10, 12.5)) { const sip = t < 11 ? t - 10 : t < 11.5 ? 1 : 12.5 - t; own = lerp(ownTable, { x: hp.x + 8, y: hp.y - 39 }, sip) }
    const opposite = { x: a.table.x + 19, y: a.table.y - 19 }
    const second = t < 7 ? { x: hp.x + 12, y: hp.y - 31 } : t < 9 ? lerp({ x: a.hostSeat.x + 12, y: a.hostSeat.y - 31 }, opposite, (t - 7) / 2) : t < 19 ? opposite : lerp(opposite, { x: a.table.x - 11, y: a.table.y - 31 }, (t - 19) / 2)
    if (between(t, 8, 21.5)) { props.lineStyle(6, 0x514d57); props.lineBetween(hp.x, hp.y - 7, hp.x + 12, hp.y - 7); props.lineBetween(hp.x + 12, hp.y - 7, hp.x + 12, hp.y + 1) }
    if (t < 9) { props.lineStyle(4, 0xd8aa8d); props.lineBetween(hp.x - 9, hp.y - 33, own.x, own.y + 2); props.lineBetween(hp.x + 9, hp.y - 33, second.x, second.y + 2) }
    cup(own.x, own.y, t, 0xe5bb72); cup(second.x, second.y, t + .5, 0xb97a60)
    // Arm reaches toward the spare cup; the cup itself changes position and stays there.
    if (between(t, 19, 21)) { props.lineStyle(5, 0xd8aa8d); props.lineBetween(hp.x + 9, hp.y - 25, second.x - 4, second.y + 2) }
    props = propLayers[1]
    if (between(t, 13, 16)) { props.lineStyle(5, 0xd3a381); props.lineBetween(pp.x - 10, pp.y - 33, pp.x - 18, pp.y - 43 - Math.sin(t * 9) * 4) }
    // A physical open book and a gently turning page remain readable without text labels.
    props = propLayers[2]
    props.lineStyle(6, 0x514d57).lineBetween(a.reader.x, a.reader.y - 7, a.reader.x - 12, a.reader.y - 7).lineBetween(a.reader.x - 12, a.reader.y - 7, a.reader.x - 12, a.reader.y + 1)
    const bx = a.reader.x - 11, by = a.reader.y - 24
    props.fillStyle(0x735548).fillRect(bx - 13, by - 7, 25, 15)
    props.fillStyle(0xf2dfb4).fillTriangle(bx - 12, by - 7, bx, by - 4, bx, by + 6).fillTriangle(bx, by - 4, bx + 11, by - 7, bx, by + 6)
    props.lineStyle(1, 0xaa9272).lineBetween(bx, by - 4, bx, by + 6)
    if (t % 7 > 5.8) { props.fillStyle(0xffedc7); props.fillTriangle(bx, by - 4, bx + Math.sin(t * 5) * 10, by - 14, bx, by + 6) }
    props = propLayers[3]
    const pouring = between(t % 12, 2, 7)
    props.fillStyle(0x789a94).fillRoundedRect(gp.x + 8, gp.y - 26, 14, 12, 3)
    props.lineStyle(3, 0x789a94).lineBetween(gp.x + 20, gp.y - 23, gp.x + 29, gp.y - (pouring ? 19 : 30)).strokeCircle(gp.x + 9, gp.y - 26, 5)
    if (pouring) for (let i = 0; i < 5; i++) { const fall = (t * 20 + i * 4) % 17; props.fillStyle(0xb7d9dc, .8).fillEllipse(gp.x + 28 + fall * .2, gp.y - 17 + fall, 2, 3) }
    let words: string | null = null
    if (between(t, 13.3, 16)) words = '我先去学院啦。'
    if (between(t, 19.7, 22)) words = '这杯留给晚一点的自己。'
    bubble.setText(words ?? '').setPosition(words?.startsWith('我') ? pp.x : hp.x, words?.startsWith('我') ? pp.y - 75 : hp.y - 75).setVisible(labels && words !== null)
    snapshot = { elapsedMs: Math.round(t * 1000), phase: t < 7 ? 'arrival' : t < 13 ? 'waiting' : t < 19 ? 'greeting' : t < 23 ? 'spare-cup' : 'afterglow', host: { ...hp, action: hostAction }, passer: { ...pp, action: passerAction }, cups: [own, second].map((p, i) => ({ ...p, location: carry || (i === 0 && between(t, 10, 12.5)) ? 'hand' : 'table' })), reader: { action: t % 7 > 5.8 ? '翻过一页书' : '坐着读书' }, gardener: { action: pouring ? '倾斜水壶浇花，水滴落入土里' : '扶着水壶歇一会儿' }, wetSoil: t >= 3, chairPulledOut: t >= 21.5, bubble: labels ? words : null }
  }
  update(0)
  return { update, reset: () => update(0), destroy: () => owned.forEach(o => o.destroy()), setLabelsVisible: (visible: boolean) => { labels = visible; update(snapshot.elapsedMs) }, snapshot: () => structuredClone(snapshot) }
}
