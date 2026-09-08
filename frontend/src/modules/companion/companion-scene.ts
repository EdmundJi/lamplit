import Phaser from 'phaser'
import { companionPath } from './companion-navigation'
import { dominantDirection, stepTowardPoint, type Direction4 } from '../../shared/scene/walkers'
import { conversationEmoji, residentStatus } from './companion-presentation'
import { buildCompanionStage } from './companion-stage'
import { ACTION_FRAME, RESIDENT_ART, RESIDENT_ACTIONS, isArtAction } from './companion-art'

export interface SceneResident { id: string; name: string; role?: string; location: string; action: string; activity?: string; destination?: string; objectKind?: string }
export interface SceneProject { id: string; title: string; place: string; status: string; progress: number; objectKind: string }
export interface SceneConversation { id: string; place: string; status: string; topicId?: string; participantIds?: string[]; turns: { speakerId: string; text: string; at: string; emoji?: string | null }[] }
export interface SceneObject { id: string; kind: string; place: string; label: string; state: string; projectId: string | null }
export interface SceneLabel { id: string; name: string; x: number; y: number; selected: boolean; speechOffset: number; speech?: string; action: string; role: string; emoji: string; hovered?: boolean; bodyX: number; bodyY: number; bodyHeight: number; conversationId?: string; dialogue?: { name: string; text: string }[]; offscreen?: boolean; direction?: string }
export interface SceneSnapshot { residents: SceneResident[]; weather: 'clear' | 'rain'; minutes: number; selectedResidentId?: string; selectedPlace?: string; overview?: boolean; projects?: SceneProject[]; conversations?: SceneConversation[]; objects?: SceneObject[] }
const W = 960, H = 640
const PALETTE = [0x688b82, 0xbd8765, 0x8185a4, 0xceaa65, 0x889b69]
export function scenePlace(location: string) {
  return (['home', 'cafe', 'garden', 'street'] as const).find(key => location === key || location.startsWith(`${key}.`) || location.startsWith(`${key}/`)) ?? 'street'
}
export function visibleActivity(activity = '', action = '', objectKind?: string) {
  if (['create', 'help'].includes(activity)) return objectKind === 'flowers' ? 'garden' : objectKind === 'tea' ? 'drink' : 'create'
  if (['home', 'rest'].includes(activity)) return 'rest'
  if (['focus', 'study', 'read'].includes(activity)) return 'read'
  if (['water', 'drink'].includes(activity)) return 'drink'
  if (['observe', 'flowers', 'invite', 'celebrate', 'talk', 'walk', 'travel'].includes(activity)) return 'idle'
  const text = activity + ' ' + action
  if (/sleep|睡|入眠/.test(text)) return 'sleep'
  if (/rest|休息|歇一会/.test(text)) return 'rest'
  if (/drink|喝|饮|茶歇/.test(text)) return 'drink'
  if (/garden|tend|plant|花|园艺|种植|照料|浇水/.test(text)) return 'garden'
  if (/create|help|创作|帮忙|海报|画画|绘|合作/.test(text)) return 'create'
  if (/focus|study|read|学|读|专注|备考/.test(text)) return 'read'
  return 'idle'
}
export function conversationPosition(place: string, index: number) {
  const center = ({ home: { x: 194, y: 311 }, cafe: { x: 554, y: 314 }, garden: { x: 814, y: 412 }, street: { x: 480, y: 402 } })[scenePlace(place)]
  return { x: center.x + (index % 2 ? 21 : -21), y: center.y + Math.floor(index / 2) * 32 }
}
export function residentPosition(location: string, index: number, activity = '', action = '') {
  const place = scenePlace(location), slot = index % 5
  if (place === 'home' && visibleActivity(activity, action) === 'sleep') return [{ x: 108, y: 237 }, { x: 148, y: 237 }, { x: 252, y: 223 }, { x: 296, y: 223 }][Math.max(0, slot - 1)]!
  if (place === 'home' && slot === 0 && visibleActivity(activity, action) === 'rest') return { x: 128, y: 300 }
  if (place === 'cafe' && ['read', 'create', 'rest', 'drink'].includes(visibleActivity(activity, action))) {
    const seats = [{ x: 440, y: 289 }, { x: 528, y: 289 }, { x: 616, y: 289 }, { x: 474, y: 319 }, { x: 668, y: 319 }]
    return seats[slot]!
  }
  if (place === 'garden' && /garden|tend|plant|flowers|grow|花|园艺|种植|照料|浇水/i.test(activity + action)) {
    // The native stream lands about 50px to the right and 10px below the feet.
    // Keep its whole silhouette inside the default camera, including the last resident.
    const plots = [{ x: 770, y: 276 }, { x: 849, y: 276 }, { x: 770, y: 356 }, { x: 849, y: 356 }, { x: 842, y: 421 }]
    return plots[slot]!
  }
  if (place === 'home' && /focus|study|read|专注|学习|读书/i.test(activity + action) && slot === 0) return { x: 270, y: 327 }
  if (place === 'home') return { x: 112 + slot * 44, y: 320 }
  if (place === 'cafe') return { x: 423 + slot * 60, y: 320 }
  if (place === 'garden') return { x: 754 + slot * 36, y: 442 }
  return { x: 260 + slot * 95, y: 401 + slot % 2 * 9 }
}
type Actor = { mode: string; sleeping: boolean; conversationId?: string; seatIndex?: number; facing: Direction4; hovered?: boolean; root: Phaser.GameObjects.Container; sprite?: Phaser.GameObjects.Sprite; label: Phaser.GameObjects.Text; activity: Phaser.GameObjects.Text; location: string; action: string; sheet: string; target: { x: number; y: number }; path: { x: number; y: number }[] }

/** Animation projects server state. It never chooses a resident's next activity or destination. */
export class CompanionStreetScene extends Phaser.Scene {
  private actors = new Map<string, Actor>()
  private shade!: Phaser.GameObjects.Rectangle
  private rain!: Phaser.GameObjects.Graphics
  private indoorLight!: Phaser.GameObjects.Graphics
  private selection!: Phaser.GameObjects.Ellipse
  private projectLayer!: Phaser.GameObjects.Container
  private projectSignature = ''
  private placeSelection!: Phaser.GameObjects.Rectangle
  private ready = false
  private viewport = { width: 960, height: 516, density: 1 }
  private cameraSignature = ''
  private cameraCenter = { x: 490, y: 286 }
  private nextLabelsAt = 0
  constructor(private snapshot: () => SceneSnapshot, private select: (id: string) => void, private selectProject: (id: string) => void = () => {}, private onLabels: (labels: SceneLabel[]) => void = () => {}) { super('companion-street') }
  resizeViewport(width: number, height: number, density: number) {
    this.viewport = { width, height, density }
    if (this.ready) this.frameCamera(true)
  }
  private frameCamera(force = false) {
    const state = this.snapshot(), { width, height, density } = this.viewport
    const compact = width < 600
    const signature = `${width}:${height}:${density}:${state.overview}:${compact ? state.selectedResidentId + ':' + state.selectedPlace : ''}`
    if (!force && this.cameraSignature === signature) return
    this.cameraSignature = signature
    let frame = state.overview ? { x: 0, y: 0, w: 960, h: 640 } : compact ? { x: 330, y: 90, w: 445, h: 370 } : { x: 48, y: 22, w: 884, h: 475 }
    let center = { x: frame.x + frame.w / 2, y: frame.y + frame.h / 2 }
    if (compact && !state.overview) {
      const target = state.selectedResidentId ? this.actors.get(state.selectedResidentId)?.root : undefined
      const place = state.selectedPlace ? ({ home: { x: 210, y: 245 }, cafe: { x: 560, y: 250 }, garden: { x: 810, y: 330 }, street: { x: 500, y: 370 } } as Record<string, { x: number; y: number }>)[state.selectedPlace] : undefined
      if (target || place) { const point = target ?? place!; center = { x: Math.max(225, Math.min(735, point.x)), y: Math.max(210, Math.min(410, point.y - 35)) } }
    }
    const camera = this.cameras.main
    camera.setViewport(0, 0, Math.round(width * density), Math.round(height * density))
    camera.setZoom(Math.min(width / frame.w, height / frame.h) * density)
    camera.centerOn(center.x, center.y)
    camera.roundPixels = true
    this.cameraCenter = center
  }
  preload() {
    this.load.atlas('town', '/assets/town/town-atlas.png', '/assets/town/town-atlas.json')
    this.load.atlas('companion', '/assets/town/companion-atlas.png', '/assets/town/companion-atlas.json')
    this.load.atlas('interior', '/assets/town/interior-atlas.png', '/assets/town/interior-atlas.json')
    for (const n of RESIDENT_ART) {
      const base = `/assets/town/characters/c${String(n).padStart(2, '0')}`
      this.load.spritesheet(`companion-${n}`, `${base}.png`, { frameWidth: 32, frameHeight: 64 })
      this.load.spritesheet(`companion-${n}-actions`, `${base}-actions.png`, { frameWidth: ACTION_FRAME.width, frameHeight: ACTION_FRAME.height })
    }
  }
  create() {
    this.cameras.main.setBackgroundColor('#78857a')
    if (this.textures.exists('town') && this.textures.exists('interior')) buildCompanionStage(this)
    else {
      this.add.rectangle(480, 320, W, H, 0xb8bda7)
      this.add.text(480, 190, '街景素材尚未生成 · 居民生活仍在继续', { fontSize: '16px', color: '#56604e' }).setOrigin(.5)
    }
    this.placeSelection = this.add.rectangle(0, 0, 240, 200, 0xffdf9b, .04).setStrokeStyle(2, 0xffdf9b, .8).setVisible(false).setDepth(700)
    this.selection = this.add.ellipse(0, 0, 46, 17, 0xffdf9b, .18).setStrokeStyle(2, 0xffe7b2, .9).setVisible(false)
    this.shade = this.add.rectangle(0, 0, W, H, 0x192644, 1).setAlpha(0).setOrigin(0).setDepth(800)
    this.indoorLight = this.add.graphics().setDepth(805)
    this.indoorLight.fillStyle(0xffdfaa, .12).fillRect(81, 139, 253, 191).fillRect(385, 107, 349, 223)
    this.indoorLight.fillStyle(0xffd89a, .1).fillEllipse(272, 263, 103, 64).fillEllipse(528, 242, 267, 91)
    this.rain = this.add.graphics().setDepth(950)
    this.projectLayer = this.add.container(0, 0).setDepth(290)
    this.ready = true
    this.sync()
  }
  private updateProjects(state: SceneSnapshot) {
    const signature = JSON.stringify([state.projects, state.objects])
    if (signature === this.projectSignature) return
    this.projectSignature = signature
    this.projectLayer.removeAll(true)
    const objects = state.objects?.length ? state.objects : (state.projects ?? []).filter(p => p.progress > 0).map(p => ({ id: p.id, kind: p.objectKind, place: p.place, label: p.title, state: p.status, projectId: p.id }))
    objects.forEach((object, index) => {
      const project = state.projects?.find(p => p.id === object.projectId)
      const progress = project?.progress ?? 100
      const kind = object.kind.toLowerCase()
      const x = /poster|海报/.test(kind) ? 714 : /flower|花/.test(kind) ? 578 : /book|书/.test(kind) ? 528 : 455 + index % 2 * 56
      const y = /poster|海报/.test(kind) ? 319 : /flower|花/.test(kind) ? 241 : 243
      const g = this.add.graphics()
      this.projectLayer.add(g)
      const art = (atlas: string, frame: string, px: number, py: number, scale = 1) => {
        if (!this.textures.exists(atlas) || !this.textures.get(atlas).has(frame)) return
        this.projectLayer.add(this.add.image(px, py, atlas, frame).setOrigin(.5, 1).setScale(scale))
      }
      if (/poster|海报/.test(kind)) {
        art('companion', progress > 25 ? 'project_poster' : 'project_poster_blank', x, y)
      } else if (/flower|花/.test(kind)) {
        art('interior', 'plant_1', x, y, .5)
        if (progress > 40) art('town', 'flowers_3', x, y - 18, .5)
      } else if (/book|书/.test(kind)) {
        for (let i = 0; i < Math.max(1, Math.min(4, Math.ceil(progress / 25))); i++) art('interior', 'book_1', x - 10 + i * 7, y, .35)
      } else {
        art('interior', 'coffee_table_wood', x, y + 10, .65)
        for (let i = 0; i < 3; i++) art('interior', 'coffee_cup', x - 10 + i * 10, y - 8, .5)
      }
      const hit = this.add.zone(x, y - 30, 54, 74).setInteractive({ useHandCursor: true })
      hit.on('pointerdown', () => this.selectProject(object.projectId ?? object.id))
      this.projectLayer.add(hit)
      if (project?.status === 'celebrating' || project?.status === 'ready') {
        g.lineStyle(1, 0x80725b).lineBetween(335, 355, 585, 367)
        for (let i = 0; i < 9; i++) { g.fillStyle(0xffd895, .14).fillCircle(340 + i * 30, 357 + i, 8); g.fillStyle(0xffdc97).fillCircle(340 + i * 30, 357 + i, 2) }
      }
    })
  }
  sync() {
    if (!this.ready) return
    const state = this.snapshot()
    const ids = new Set(state.residents.map(r => r.id))
    for (const [id, actor] of this.actors) if (!ids.has(id)) { actor.root.destroy(); this.actors.delete(id) }
    state.residents.forEach((resident, index) => {
      const visibleMode = visibleActivity(resident.activity, resident.action, resident.objectKind)
      const atDesk = ['read', 'create', 'rest', 'drink'].includes(visibleMode) && (resident.location === 'cafe' || ['read', 'create'].includes(visibleMode))
      // The server describes a travelling actor as "walk"; destination belongs to its travel plan.
      const travelling = Boolean(resident.destination) && (resident.activity === 'walk' || resident.activity === 'travel')
      const location = travelling ? resident.destination! : resident.location
      let actor = this.actors.get(resident.id)
      const usedSeats = new Set([...this.actors.entries()].filter(([id, other]) => id !== resident.id && scenePlace(other.location) === scenePlace(location)).map(([, other]) => other.seatIndex).filter(value => value !== undefined))
      let seatIndex = atDesk ? (actor?.seatIndex !== undefined && scenePlace(actor.location) === scenePlace(location) ? actor.seatIndex : [0, 1, 2, 3, 4].find(seat => !usedSeats.has(seat)) ?? index) : undefined
      let target = residentPosition(location, seatIndex ?? index, resident.activity, resident.action)
      if (travelling) target = ({ home: { x: 204, y: 350 }, cafe: { x: 535, y: 350 }, garden: { x: 768, y: 396 }, street: { x: 480, y: 396 } })[scenePlace(location)]
      const conversation = !travelling ? state.conversations?.find(c => c.status === 'active' && scenePlace(c.place) === scenePlace(location) && (c.participantIds?.includes(resident.id) || c.turns.some(t => t.speakerId === resident.id))) : undefined
      const participants = conversation?.participantIds ?? [...new Set(conversation?.turns.map(t => t.speakerId) ?? [])]
      if (conversation) { target = conversationPosition(location, participants.indexOf(resident.id)); seatIndex = undefined }
      if (!actor) {
        const root = this.add.container(target.x, target.y)
        root.add(this.add.ellipse(0, -1, 29, 9, 0x4c5444, .2))
        const sheet = `companion-${RESIDENT_ART[index % RESIDENT_ART.length]}`
        let sprite: Phaser.GameObjects.Sprite | undefined
        if (this.textures.exists(sheet)) {
          const columns = Math.floor((this.textures.get(sheet).getSourceImage() as HTMLImageElement).width / 32)
          // Same LimeZu layout as town/engine/npcs: 4 directions × 6 frames, idle row 1, walk row 2.
          for (const [direction, directionIndex] of Object.entries({ right: 0, up: 1, left: 2, down: 3 })) {
            for (const [action, row] of [['idle', 1], ['walk', 2]] as const) {
              const key = `${sheet}-${action}-${direction}`
              if (!this.anims.exists(key)) this.anims.create({ key, frames: this.anims.generateFrameNumbers(sheet, { start: row * columns + directionIndex * 6, end: row * columns + directionIndex * 6 + 5 }), frameRate: action === 'walk' ? 9 : 6, repeat: -1 })
            }
          }
          if (!this.anims.exists(`${sheet}-read`)) this.anims.create({ key: `${sheet}-read`, frames: this.anims.generateFrameNumbers(sheet, { start: 7 * columns, end: 7 * columns + 5 }), frameRate: 5, repeat: -1 })
          for (const [name, row, start, count] of [['sleep', 3, 0, 6], ['sit', 4, 0, 6]] as const) {
            const key = `${sheet}-${name}`
            if (!this.anims.exists(key)) this.anims.create({ key, frames: this.anims.generateFrameNumbers(sheet, { start: row * columns + start, end: row * columns + start + count - 1 }), frameRate: name === 'sleep' ? 2 : 5, repeat: -1 })
          }
          if (this.textures.exists(`${sheet}-actions`)) for (const [name, action] of Object.entries(RESIDENT_ACTIONS)) {
            const key = `${sheet}-${name}`
            if (!this.anims.exists(key)) this.anims.create({ key, frames: action.frames.map(frame => ({ key: `${sheet}-actions`, frame })), frameRate: action.frameRate, repeat: -1 })
          }
          sprite = this.add.sprite(0, 0, sheet).setOrigin(.5, 1)
          root.add(sprite)
        } else {
          root.add(this.add.rectangle(0, -18, 19, 25, PALETTE[index % 5]).setStrokeStyle(2, 0xfff3dc))
          root.add(this.add.circle(0, -37, 10, 0xe9bf97))
        }
        const label = this.add.text(0, index % 2 === 0 ? 11 : 28, resident.name, { fontFamily: 'system-ui', fontSize: '11px', color: '#f6e9ca', backgroundColor: '#465c50', resolution: 2, padding: { x: 6, y: 3 } }).setOrigin(.5)
        const activity = this.add.text(0, -86, '', { fontFamily: 'system-ui', fontSize: '12px', color: '#455047', backgroundColor: '#fff3d8', resolution: 2, wordWrap: { width: 190, useAdvancedWrap: true }, align: 'center', padding: { x: 6, y: 4 } }).setOrigin(.5)
        label.setVisible(false); activity.setVisible(false);
        root.add([label, activity]); root.setSize(64, 100).setInteractive(new Phaser.Geom.Rectangle(-32, -80, 64, 110), Phaser.Geom.Rectangle.Contains)
        root.on('pointerdown', () => this.select(resident.id))
        root.on('pointerover', () => { if (actor) actor.hovered = true }); root.on('pointerout', () => { if (actor) actor.hovered = false })
        actor = { mode: 'idle', sleeping: false, root, sprite, sheet, label, activity, location, action: resident.action, target, path: [], facing: 'down', seatIndex }
        this.actors.set(resident.id, actor)
      }
      if (actor.target.x !== target.x || actor.target.y !== target.y) actor.path = companionPath(actor.root, target)
      actor.mode = travelling ? 'idle' : visibleMode
      actor.conversationId = conversation?.id
      actor.location = location
      actor.seatIndex = seatIndex
      actor.target = target; actor.action = resident.action; actor.label.setText(resident.name)
      actor.activity.setText(resident.action)
    })
    const night = state.minutes < 360 || state.minutes >= 1140
    this.shade.setAlpha(night ? .28 : state.weather === 'rain' ? .1 : 0)
    this.indoorLight.setVisible(night)
    this.updateProjects(state)
    this.frameCamera()
  }
  update(time: number, delta: number) {
    const state = this.snapshot()
    const bounds = ({ home: [208, 220, 264, 240], cafe: [560, 207, 360, 270], garden: [845, 371, 156, 315], street: [460, 408, 860, 100] } as Record<string, number[]>)[state.selectedPlace ?? '']
    this.placeSelection?.setVisible(Boolean(bounds))
    if (bounds) this.placeSelection.setPosition(bounds[0]!, bounds[1]!).setSize(bounds[2]!, bounds[3]!)
    const selected = state.selectedResidentId ? this.actors.get(state.selectedResidentId) : undefined
    this.selection?.setVisible(Boolean(selected))
    if (selected) this.selection.setPosition(selected.root.x, selected.root.y - 1).setDepth(selected.root.y - 1)
    // Dialogue is a short DOM caption; the complete exchange lives in the story panel.
    const active = [...(state.conversations ?? [])].reverse().find(c => c.status === 'active' && c.turns.length)
    const recentTurns = active?.turns.slice(-2) ?? []
    const turn = recentTurns[Math.floor(time / 6500) % Math.max(1, recentTurns.length)]
    for (const actor of this.actors.values()) {
      while (actor.path[0] && Phaser.Math.Distance.Between(actor.root.x, actor.root.y, actor.path[0].x, actor.path[0].y) < .5) actor.path.shift()
      const waypoint = actor.path[0]
      const from = { x: actor.root.x, y: actor.root.y }
      if (waypoint) {
        const next = stepTowardPoint(from, waypoint, 72, Math.min(delta, 50))
        actor.facing = dominantDirection(next.x - from.x, next.y - from.y, actor.facing)
        actor.root.setPosition(next.x, next.y)
      }
      actor.root.setDepth(actor.root.y)
      const peers = actor.conversationId ? [...this.actors.values()].filter(other => other !== actor && other.conversationId === actor.conversationId) : []
      const chatting = peers.length > 0 && !waypoint && peers.every(peer => !peer.path.length)
      if (chatting) actor.facing = dominantDirection(peers[0]!.root.x - actor.root.x, peers[0]!.root.y - actor.root.y, actor.facing)
      actor.sleeping = !waypoint && !actor.conversationId && actor.mode === 'sleep' && scenePlace(actor.location) === 'home'
      const mode = waypoint || actor.conversationId ? 'idle' : actor.mode
      const nativeAction = isArtAction(mode) && this.anims.exists(`${actor.sheet}-${mode}`)
      const animation = waypoint ? `walk-${actor.facing}` : actor.sleeping ? 'sleep' : nativeAction ? mode : mode === 'read' ? 'read' : mode === 'rest' ? 'sit' : `idle-${actor.facing}`
      // Sleep row is only a head. Its lower edge sits on the actual pillow above the blanket.
      actor.sprite?.setPosition(0, actor.sleeping ? -40 : 0)
        .setOrigin(nativeAction ? ACTION_FRAME.originX : .5, nativeAction ? ACTION_FRAME.originY : 1)
        .setFlipX(false).play(`${actor.sheet}-${animation}`, true)

    }
    if (time >= this.nextLabelsAt) {
      this.nextLabelsAt = time + 80
      const zoom = this.cameras.main.zoom / this.viewport.density
      const labels: SceneLabel[] = []
      for (const [id, actor] of this.actors) {
        const x = (actor.root.x - this.cameraCenter.x) * zoom + this.viewport.width / 2 + 17
        const bodyOffset = actor.sleeping ? -68 : 0
        const bodyHeight = actor.sleeping ? 31 : 60
        const y = (actor.root.y + bodyOffset - this.cameraCenter.y) * zoom + this.viewport.height / 2 - bodyHeight * zoom - 20
        const offscreen = x < 18 || x > this.viewport.width - 18 || y < 38 || y > this.viewport.height - 27
        const direction = x < 18 ? '‹' : x > this.viewport.width - 18 ? '›' : y < 38 ? '⌃' : '⌄'
        const resident = state.residents.find(r => r.id === id)
        const speech = turn?.speakerId === id && !actor.path.length && [...this.actors.values()].filter(other => other.conversationId === actor.conversationId).every(other => !other.path.length) ? turn.text.slice(0, 23) + (turn.text.length > 23 ? '…' : '') : undefined
        const status = residentStatus(resident?.activity ?? '', actor.action, Boolean(actor.path.length), Boolean(speech))
        const topic = speech ? conversationEmoji(turn?.emoji) : ''
        const dialogue = speech ? recentTurns.map(line => ({ name: state.residents.find(r => r.id === line.speakerId)?.name ?? '邻居', text: line.text })) : undefined
        labels.push({ id, name: actor.label.text, role: resident?.role === 'user' ? '你的小人' : resident?.role ?? '小街邻居', x: Math.max(20, Math.min(this.viewport.width - 20, x)), y: Math.max(68, Math.min(this.viewport.height - 29, y)), selected: actor === selected, speechOffset: 8, speech: offscreen ? undefined : speech, action: status.shortAction, emoji: topic, conversationId: speech ? active?.id : undefined, dialogue, bodyX: x - 17, bodyY: y + 20, bodyHeight: bodyHeight * zoom, hovered: actor.hovered, offscreen, direction: offscreen ? direction : undefined })
      }
      // Edge pins remain individually reachable when several residents are outside the close view.
      for (const edge of ['‹', '›', '⌃', '⌄']) {
        const pins = labels.filter(label => label.offscreen && label.direction === edge).sort((a, b) => edge === '‹' || edge === '›' ? a.y - b.y : a.x - b.x)
        const vertical = edge === '‹' || edge === '›'
        const minimum = vertical ? 68 : 24, maximum = vertical ? this.viewport.height - 29 : this.viewport.width - 24
        pins.forEach((pin, index) => {
          const coordinate = vertical ? pin.y : pin.x
          const previous = index ? (vertical ? pins[index - 1]!.y : pins[index - 1]!.x) : minimum - 34
          const next = Math.max(minimum, Math.min(maximum - (pins.length - 1 - index) * 34, Math.max(coordinate, previous + 34)))
          if (vertical) pin.y = next; else pin.x = next
        })
      }
      this.onLabels(labels)
    }
    if (!this.rain) return
    this.rain.clear()
    if (this.snapshot().weather === 'rain') {
      this.rain.lineStyle(1, 0xeaf3e9, .48)
      for (let i = 0; i < 85; i++) {
        const x = (i * 137 + time * .025) % W, y = (i * 79 + time * .19) % H
        if ((x >= 80 && x <= 336 && y >= 108 && y <= 338) || (x >= 384 && x <= 736 && y >= 76 && y <= 338)) continue
        this.rain.lineBetween(x, y, x - 5, y + 13)
      }
    }
  }
}
