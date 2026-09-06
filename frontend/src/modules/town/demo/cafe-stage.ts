import type Phaser from 'phaser'

export type CafePoint = { x: number; y: number }
export const CAFE_SIZE = { width: 960, height: 640 }
export const CAFE_ANCHORS = {
  door: { x: 560, y: 340 }, table: { x: 440, y: 414 },
  chair: { x: 400, y: 425 }, otherChair: { x: 480, y: 425 },
  alley: { x: 688, y: 266 }, passerExit: { x: 900, y: 475 },
  reader: { x: 770, y: 405 }, plant: { x: 300, y: 382 },
  playerSpawn: { x: 180, y: 505 }, readingTable: { x: 735, y: 390 },
} satisfies Record<string, CafePoint>
export const CAFE_WALK_BOUNDS = { x: 85, y: 350, width: 810, height: 220 }
export const CAFE_OBSTACLES = [
  { x: 414, y: 403, width: 52, height: 20 },
  { x: 707, y: 378, width: 52, height: 20 },
  { x: 285, y: 366, width: 30, height: 22 },
  { x: 182, y: 350, width: 56, height: 31 },
  { x: 382, y: 421, width: 31, height: 23 },
  { x: 469, y: 421, width: 23, height: 14 },
  { x: 758, y: 399, width: 24, height: 14 },
  { x: 688, y: 399, width: 24, height: 14 },
  { x: 800, y: 361, width: 79, height: 13 },
  { x: 737, y: 329, width: 27, height: 14 },
  { x: 609, y: 358, width: 47, height: 15 },
]

/** A human-scale, fixed-camera street corner. All scenery shares actor feet-depth. */
export function buildCafeStage(scene: Phaser.Scene) {
  const all: Phaser.GameObjects.GameObject[] = []
  const g = scene.add.graphics().setDepth(-100)
  all.push(g)
  const rect = (x: number, y: number, w: number, h: number, c: number, a = 1) => {
    g.fillStyle(c, a).fillRect(x, y, w, h)
  }
  const image = (x: number, y: number, key: string, frame: string, scale = 1, tint = 0xffffff, depth = y) => {
    const s = scene.add.image(x, y, key, frame).setOrigin(.5, 1).setScale(scale).setTint(tint).setDepth(depth)
    if (key === 'town' && /^(tree|bush|flower)/.test(frame) && s.postFX) {
      // Preserve the atlas shading while taking the spring-green edge off late-day foliage.
      const grade = s.postFX.addColorMatrix()
      grade.saturate(-.42)
    }
    all.push(s)
    return s
  }
  const text = (x: number, y: number, value: string, size: number, color: string, depth = 300) => {
    const t = scene.add.text(x, y, value, { fontFamily: 'Georgia, "PingFang SC", serif', fontSize: `${size}px`, color, resolution: 2 }).setOrigin(.5).setDepth(depth)
    all.push(t)
    return t
  }
  // Muted purple distance, warm limestone foreground; no large road or lawn competes with people.
  rect(0, 0, 960, 640, 0x777c7c)
  rect(0, 0, 960, 90, 0x9c9291)
  // Roof silhouettes and a few upper windows suggest a town beyond this one corner.
  for (const [x,y,w,h] of [[12,42,152,83],[156,64,130,57],[315,36,121,87],[461,57,153,67],[703,29,135,99],[867,53,110,70]]) {
    rect(x,y,w,h,0x858582);rect(x-4,y,w+8,5,0x777c79)
    for(let wx=x+18;wx<x+w-10;wx+=38)rect(wx,y+19,12,18,0xa99e8b,.45)
  }
  rect(0, 70, 960, 250, 0x747775)
  rect(0, 310, 960, 330, 0xb7aa90)
  rect(0, 488, 960, 152, 0x9a9587)
  rect(0, 489, 960, 6, 0xd0bea0)
  rect(0, 495, 960, 4, 0x7d7f74)
  // Long, subdued shadows establish the low sun and the ground plane.
  g.fillStyle(0x53635e,.12).fillPoints([{x:220,y:330},{x:610,y:330},{x:665,y:384},{x:263,y:384}],true)
  g.fillStyle(0x526453,.10).fillEllipse(174,354,130,37)
  g.fillStyle(0x526453,.10).fillEllipse(862,351,120,34)
  // Offset paving joints with sparse, deterministic chips.
  for (let y = 325; y < 490; y += 26) {
    rect(0, y, 960, 1, 0xa99a80, .65)
    for (let x = (y % 52 === 13 ? 0 : 28); x < 960; x += 58) rect(x, y, 1, 26, 0xa99a80, .55)
  }
  for (let i = 0; i < 95; i++) {
    const x = (i * 137 + 39) % 960, y = 322 + (i * 43) % 305
    rect(x, y, 2 + i % 4, 1, i % 2 ? 0xd1c4a8 : 0x7d8275, .35)
  }
  // A compressed side street leads the eye into the inhabited foreground.
  rect(622, 80, 119, 245, 0x465751)
  rect(633, 152, 96, 194, 0x677268)
  for (let y = 188; y < 342; y += 28) rect(638, y, 89, 1, 0xaaa48c, .35)
  rect(636, 330, 97, 18, 0xafa78d)
  rect(622,109,14,225,0x394e47);rect(729,102,25,234,0x495f53)
  for(let y=161;y<331;y+=25) { rect(623,y,11,2,0x738076);rect(730,y+8,23,2,0x808778) }
  rect(646,155,64,78,0x5e6c67);rect(650,159,56,71,0x858677)
  rect(656,163,44,42,0xab9e7e);rect(657,209,42,15,0x6e796c)
  rect(640,230,73,7,0x686e62)
  // The alley has a small step and a deep shadow at its distant end.
  rect(638,249,87,5,0x6e756d);rect(638,254,87,4,0x949486)
  // Neighbouring residences are deliberately lower contrast than the café.
  rect(-20, 116, 226, 205, 0x8a8c82)
  rect(-20, 106, 231, 12, 0x5d6d68)
  rect(-20, 124, 226, 6, 0xb4ac91)
  rect(754, 97, 227, 231, 0x92948a)
  rect(747, 86, 225, 14, 0x5b716c)
  rect(754, 108, 225, 6, 0xbcb39a)
  for (const [x,y] of [[43,160],[133,160],[795,137],[885,137],[795,238],[885,238]]) {
    rect(x-3,y-3,44,59,0x67706b); rect(x,y,38,53,0xb4aaa0)
    rect(x+4,y+4,30,40,0x7d8e87); rect(x+18,y+2,3,48,0xd0bea0)
    rect(x-5,y+51,49,6,0xc0b296)
  }
  rect(84,252,58,69,0x626f67);rect(91,258,44,63,0x897e69)
  // Weathered shutters and limestone courses keep the side houses in the same pixel language.
  for(const [x,y] of [[43,160],[133,160],[795,137],[885,137],[795,238],[885,238]]) {
    for(const dx of [-17,40]) {rect(x+dx,y,12,51,0x6c8072);for(let sy=4;sy<49;sy+=6)rect(x+dx+2,y+sy,8,2,0x8b9780)}
    rect(x+4,y+30,30,3,0xc2b69a)
  }
  for(const [bx,by,bw] of [[0,289,204],[757,301,205]]) {
    rect(bx,by,bw,28,0x858578)
    for(let yy=by;yy<by+28;yy+=9) {rect(bx,yy,bw,2,0xa4a08b);for(let xx=bx+(yy%2)*13;xx<bx+bw;xx+=30)rect(xx,yy,2,9,0x9d9a85)}
  }
  rect(194,130,4,186,0x626d63);rect(192,308,9,6,0x697264)
  rect(759,116,4,179,0x687569)
  for(let i=0;i<55;i++) {const xx=(i*73)%194;const yy=139+(i*31)%149;rect(xx,yy,4+(i%3)*2,2,0xbab09a,.24)}
  // Café: low eaves, warm storefront, narrow roof band instead of a giant roof.
  rect(217, 152, 410, 180, 0x605b51)
  rect(222, 149, 397, 177, 0xc0a27d)
  rect(222, 142, 397, 10, 0xe0c294)
  rect(212, 111, 420, 32, 0x4d6862)
  for (let y = 115; y < 140; y += 7) rect(217,y,410,2,0x749080)
  rect(212, 139, 420, 8, 0x3d544e)
  rect(230, 305, 382, 21, 0x947655)
  for(let y=308;y<326;y+=8) {rect(230,y,382,2,0xb69771);for(let x=232+(y%16)*2;x<610;x+=37)rect(x,y,2,8,0x755c45)}
  rect(222,150,10,153,0xdcc29b);rect(608,150,11,176,0x957952)
  rect(617,152,10,174,0x78694f)
  rect(230,148,382,5,0x9b825f)
  // Plaster imperfections and sill highlights remain quieter than the actors.
  for (let i=0;i<44;i++) rect(230+(i*67)%378,158+(i*31)%150,7,2,0xa78b6e,.32)
  rect(251, 188, 235, 113, 0x634f40)
  rect(259, 194, 219, 97, 0xd59a53)
  rect(264, 199, 209, 68, 0xf4c877)
  rect(264,219,101,47,0xe5b268)
  rect(373,219,100,47,0xf9d791)
  rect(270,201,93,13,0xffe5a6)
  rect(379,201,87,13,0xffefbf)
  rect(269, 203, 199, 15, 0xffedbb, .85)
  // Visible interior shelves, cups and pendant lights.
  rect(266,250,205,5,0x96683e)
  for(let i=0;i<9;i++){rect(277+i*21,239,9,10,0xf5dfaa);rect(286+i*21,241,3,5,0xbc8857)}
  rect(266,275,205,15,0xab7648)
  for(let x=270;x<471;x+=18)rect(x,279,2,11,0x815e3e)
  // Espresso machine, jar lids, and a bread tray are silhouettes visible through the glazing.
  rect(275,255,37,18,0x746c55);rect(278,252,30,5,0xd1c29b)
  rect(279,258,18,7,0x514f44);rect(282,267,9,6,0xf7dfac);rect(300,258,4,11,0xd9c99a)
  rect(326,264,54,10,0xc58e4f);rect(323,271,60,3,0x805d3d)
  for(let i=0;i<4;i++) {rect(329+i*13,259,10,10,0xeac282);rect(331+i*13,257,6,3,0xf5d499)}
  for(let i=0;i<3;i++){rect(405+i*19,255,12,18,0xc28e55);rect(406+i*19,253,10,3,0x796040);rect(408+i*19,260,6,7,0xe7cb94)}
  for(const x of [302,424]) { rect(x,196,2,20,0x695940); rect(x-10,215,22,5,0x8a7047);rect(x-6,220,14,4,0xffe4a3) }
  for(const x of [258,367,474]) rect(x,191,6,105,0x816247)
  rect(250,296,239,7,0xe1c291)
  // Door glass and lower panel; threshold opens directly onto the playable apron.
  rect(526,190,68,134,0x654f3f);rect(532,196,56,125,0xa67c51)
  rect(538,202,44,76,0xe7b771);rect(542,205,36,68,0xffd890);rect(545,208,30,18,0xffebb5)
  rect(538,286,44,28,0x88633f);rect(574,276,4,5,0xf9d89a)
  rect(526,324,68,6,0xe0c9a3);rect(520,330,80,8,0x9b896d)
  // Forest-green canvas awning; restrained cream stripes.
  g.fillStyle(0x3c4336,.48).fillPoints([{x:244,y:188},{x:604,y:188},{x:600,y:204},{x:248,y:204}],true)
  g.fillStyle(0x6d8c70).fillPoints([{x:250,y:169},{x:596,y:169},{x:604,y:180},{x:241,y:180}],true)
  rect(241,173,363,17,0x496b5d)
  for(let x=245;x<603;x+=36) rect(x,174,14,15,0xc6bc97)
  rect(241,187,363,6,0x34574b)
  for(let x=244;x<601;x+=18)rect(x,192,13,3,0x456753)
  rect(251,170,343,2,0xa5b48e)
  text(420, 160, '暮色  ·  COFFEE', 18, '#594331', 305)
  // Warm light spills follow the pavement plane, never over the characters.
  const light = scene.add.graphics().setDepth(-70)
  all.push(light)
  light.fillStyle(0xffd08a,.16).fillEllipse(390,340,330,105)
  light.fillStyle(0xffd799,.23).fillPoints([{x:537,y:330},{x:582,y:330},{x:626,y:390},{x:510,y:390}],true)
  light.fillStyle(0xf8d28c,.12).fillPoints([{x:266,y:325},{x:472,y:325},{x:520,y:446},{x:367,y:458}],true)
  light.fillStyle(0xffdea0,.08).fillEllipse(443,413,177,57)
  // Entrance details make the place feel used.
  image(560,350,'interior','doormat_1',1.35,0xd7c7a5,349)
  image(210,381,'town','flowerbush_4',.85,0xa2a785)
  image(299,384,'interior','plant_2',.85,0xc7c394)
  image(605,339,'interior','plant_1',.8,0xc5c59a)
  image(253,318,'town','flowers_2',.64,0xdac09d,318)
  // Climbing leaves soften the hard junction between the café and the lane.
  rect(615,196,2,103,0x7b8058)
  for(let i=0;i<15;i++){const yy=202+i*6;rect(609+(i%2)*8,yy,8,4,i%3?0x8a9263:0xa1a578)}
  const wire = scene.add.graphics().setDepth(302);all.push(wire)
  wire.lineStyle(1,0x5a6253,.8).beginPath().moveTo(613,187).lineTo(670,209).lineTo(728,216).lineTo(786,199).strokePath()
  for(const [x,y] of [[625,192],[650,201],[677,210],[705,214],[734,214],[761,206]]) {
    wire.fillStyle(0xffdc93,.07).fillCircle(x,y+5,11)
    wire.fillStyle(0xffda92,.95).fillRect(x-2,y+3,4,6)
  }
  const board = scene.add.graphics().setDepth(370);all.push(board)
  board.fillStyle(0x695c45).fillRect(616,322,5,48).fillRect(646,322,5,48)
  board.fillStyle(0xb39565).fillRect(612,321,43,36)
  board.fillStyle(0x354c42).fillRect(615,324,37,29)
  text(634,333,'TODAY',7,'#eddbac',371);text(634,345,'手冲 · 可颂',7,'#eddbac',371)
  // Two differently oriented tables keep a clear silhouette for each small activity.
  for(const [x,y] of [[440,414],[735,390]]) {
    const shadow = scene.add.ellipse(x,y+3,72,20,0x4d5348,.19).setDepth(y-20);all.push(shadow)
    const table = scene.add.graphics().setDepth(y);all.push(table)
    table.fillStyle(0x665b43).fillRect(x-22,y-11,5,13).fillRect(x+18,y-11,5,13)
    table.fillStyle(0x76563a).fillEllipse(x,y-14,68,25)
    table.fillStyle(0xbf9460).fillEllipse(x,y-18,68,23)
    table.fillStyle(0xd7b57b).fillEllipse(x,y-20,64,18)
    table.lineStyle(1,0xa78454,.6).lineBetween(x-22,y-23,x+23,y-23).lineBetween(x-29,y-18,x+29,y-18)
  }
  const mainChair = image(400,432,'interior','cafe_chair',1,0xc1b390,421)
  image(480,432,'interior','cafe_chair',1,0xc1b390,421).setFlipX(true)
  image(770,411,'interior','cafe_chair',1,0xc1b390,394).setFlipX(true)
  image(700,411,'interior','cafe_chair',1,0xc1b390,394)
  image(840,371,'town','gardenbench_1',.85,0xb3b19a)
  // Midground trees describe the lane; closest canopy only frames the outer corners.
  image(166,336,'town','tree_2',1.55,0xe0c3a1,335)
  image(854,331,'town','tree_1',1.45,0xd9c4a6,330)
  image(751,340,'town','tree_3',.68,0xdac298,339)
  image(101,454,'town','lamp_5',1.05,0xb1b1a3,454)
  const glow = scene.add.graphics().setDepth(455);all.push(glow)
  glow.fillStyle(0xffd090,.06).fillCircle(101,329,31)
  glow.fillStyle(0xffd090,.11).fillCircle(101,329,17)
  glow.fillStyle(0xffdfa3,.9).fillRect(98,324,6,10)
  image(14,682,'town','tree_2',3.4,0x96a07e,1000)
  image(953,691,'town','tree_1',3.2,0x92a083,1000)
  // Branch shadows connect the framing trees to the street instead of leaving a blank foreground.
  g.fillStyle(0x536353,.13).fillPoints([{x:0,y:552},{x:79,y:561},{x:332,y:640},{x:221,y:640}],true)
  g.fillStyle(0x536353,.10).fillEllipse(218,595,280,69)
  g.fillStyle(0x536353,.09).fillEllipse(795,605,298,82)
  g.fillStyle(0x536353,.12).fillPoints([{x:960,y:563},{x:912,y:554},{x:703,y:640},{x:798,y:640}],true)
  // Thin worn joints make this a quiet pedestrian lane, with a small cobbled gutter.
  for(let y=512;y<640;y+=42) {
    rect(0,y,960,1,0xb7ae96,.20)
    for(let x=(y%84===8?0:51);x<960;x+=104)rect(x,y,1,41,0x7f8778,.16)
  }
  for(let x=0;x<960;x+=18){rect(x,502,16,7,0xb9b098,.55);rect(x+2,503,11,2,0xcebea1,.35)}
  // Irregular edge planting, fallen leaves, and a drain anchor the street surface.
  image(81,589,'town','flowerbush_1',1.35,0x9f9e7e,590)
  image(886,594,'town','bush_2',1.8,0x8c997b,595)
  for(let i=0;i<16;i++) rect(121+(i*167)%710,513+(i*37)%93,4,2,i%2?0xafa078:0xc6b18a,.75)
  rect(782,490,48,8,0x68716a);for(let x=786;x<828;x+=6)rect(x,491,2,6,0x969684)
  const mist = scene.add.graphics().setDepth(-60);all.push(mist)
  mist.fillStyle(0xe9cca1,.045).fillRect(0,0,960,640)
  return { anchors: CAFE_ANCHORS, obstacles: CAFE_OBSTACLES, walkBounds: CAFE_WALK_BOUNDS,
    update: (elapsedMs: number) => {
      glow.setAlpha(.9 + Math.sin(elapsedMs / 2400) * .07)
      const pull = Math.max(0, Math.min(1, (elapsedMs - 21500) / 650))
      mainChair.setPosition(400 - 8 * pull, 432 + 8 * pull).setDepth(421 + 8 * pull)
    },
    destroy: () => all.forEach(o => o.destroy()) }
}
