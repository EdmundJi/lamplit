import Phaser from 'phaser'
import { resolveMove } from '../../shared/scene/collision'
import { companionPath, COMPANION_COLLISION } from './companion-navigation'
import { dominantDirection, stepTowardPoint, type Direction4 } from '../../shared/scene/walkers'
import { conversationEmoji, residentStatus } from './companion-presentation'
import { motionAllowed } from '../../shared/ui/interaction/motion'
import { buildCompanionStage } from './companion-stage'
import { positionUseProps, type PositionUseProp } from './companion-position-props'
import { ACTION_FRAME, RESIDENT_ART, RESIDENT_ACTIONS, isArtAction, COMPANION_WORLD_SIZE, HOME_ROOMS, CAFE_ROOM, CAFE_WINDOW_ROOM, CAFE_SERVICE, CAFE_SEATS, cafeSeatAt, GARDEN_OFFSET_X, POSITION_SLOTS, WALK_PIXELS_PER_SECOND, ACADEMY_ROOM, GYM_ROOM, SHOP_ROOM, gardenY, sleepSpriteOffset } from './companion-art'
import { shift } from './town-layout'
// scenePlace/residentPosition/residentTarget/etc. live in companion-geometry.ts, which has no
// Phaser import - see that file's own header comment for why. companion-scene.ts (this file, the
// real Phaser.Scene) re-exports them below so every existing import of './companion-scene' -
// TownStage.vue's old value import included - keeps working; TownStage.vue itself has since moved
// to importing straight from companion-geometry so mounting it never pulls Phaser in.
import { scenePlace, placeFrame, placeCenter, homeRoom, homeId, visibleActivity, isSpeaking, conversationPosition, conversationPositionInRoom, residentPosition, residentTarget, rainFallsOutside, travelAnchor } from './companion-geometry'
export { scenePlace, visibleActivity, isSpeaking, conversationPosition, conversationPositionInRoom, residentPosition, residentTarget, rainFallsOutside }

export interface SceneResident { id: string; name: string; role?: string; location: string; action: string; activity?: string; destination?: string; objectKind?: string; positionId?: string | null; roomId?: string | null }
export interface SceneProject { id: string; title: string; place: string; status: string; progress: number; objectKind: string }
export interface SceneConversation { id: string; place: string; status: string; topicId?: string; participantIds?: string[]; turns: { speakerId: string; text: string; at: string; emoji?: string | null }[] }
export interface SceneObject { id: string; kind: string; place: string; label: string; state: string; projectId: string | null }
export interface SceneLabel { worldX?: number; worldY?: number; facing?: Direction4; id: string; name: string; x: number; y: number; selected: boolean; speechOffset: number; speech?: string; action: string; role: string; emoji: string; hovered?: boolean; bodyX: number; bodyY: number; bodyHeight: number; conversationId?: string; dialogue?: { name: string; text: string }[]; offscreen?: boolean; direction?: string }
/** Keep the canvas edge legible in a populated town. The resident roster remains the complete,
 * accessible index; on the map, retain a selected resident plus one representative per edge. */
export function visibleSceneLabels(labels: SceneLabel[]) {
  const represented = new Set<string>()
  return labels.filter(label => {
    if (!label.offscreen || label.selected) return true
    const edge = label.direction ?? ''
    if (represented.has(edge)) return false
    represented.add(edge)
    return true
  })
}
// cafeOpen mirrors CompanionScene.vue's own prop of the same name: authoritative world.cafeStatus,
// never inferred from the client's clock - see the night window lighting in sync().
// 'docked' is the always-on street strip: a short, wide window that pans/zooms to a fixed world
// point per place instead of framing overview/selection/hover like the full /town page ('auto',
// the pre-existing behaviour, unchanged below).
// chrome: false hides the DOM name-tag/offscreen-indicator layer (CompanionScene.vue's own
// template) *and* the in-canvas signage text (buildCompanionStage's `signage`, toggled in sync()
// below) - the docked street strip's own quiet, low-clutter mode. Defaults to true (unchanged).
export interface SceneSnapshot { residents: SceneResident[]; weather: 'clear' | 'rain'; minutes: number; selectedResidentId?: string; selectedPlace?: string; overview?: boolean; projects?: SceneProject[]; conversations?: SceneConversation[]; objects?: SceneObject[]; cafeOpen?: boolean; chrome?: boolean; cameraMode?: 'auto' | 'docked'; cameraTarget?: { x: number; y: number }; dockedFrameHeight?: number }
const W = COMPANION_WORLD_SIZE.width, H = COMPANION_WORLD_SIZE.height
const PALETTE = [0x688b82, 0xbd8765, 0x8185a4, 0xceaa65, 0x889b69]
type Actor = { mode: string; sleeping: boolean; conversationId?: string; seatIndex?: number; positionId?: string; slot?: number; facing: Direction4; hovered?: boolean; root: Phaser.GameObjects.Container; sprite?: Phaser.GameObjects.Sprite; heldProp: Phaser.GameObjects.Graphics; label: Phaser.GameObjects.Text; activity: Phaser.GameObjects.Text; location: string; action: string; sheet: string; target: { x: number; y: number }; path: { x: number; y: number }[] }

/** Animation projects server state. It never chooses a resident's next activity or destination. */
export class CompanionStreetScene extends Phaser.Scene {
  private actors = new Map<string, Actor>()
  private stage?: { destroy: () => void; signage: Phaser.GameObjects.Text[] }
  private shade!: Phaser.GameObjects.Rectangle
  private rain!: Phaser.GameObjects.Graphics
  // Per-home window light, keyed by the same id as HOME_ROOMS/homeId() - lit after dark only for
  // a home with someone actually in it and awake, dark again once everyone there is asleep (or
  // the home is empty). Separate rectangles (rather than one shared graphics object) are what let
  // each home be driven independently instead of every window turning on together at dusk.
  private homeLights = new Map<string, Phaser.GameObjects.Rectangle>()
  // The cafe's own light is gated on the authoritative cafeStatus the caller already computes for
  // the soundscape (world.cafeStatus, never the client's own clock guess) - see CompanionScene.vue.
  private cafeLights: (Phaser.GameObjects.Rectangle | Phaser.GameObjects.Ellipse)[] = []
  private publicLights: Phaser.GameObjects.Rectangle[] = []
  private selection!: Phaser.GameObjects.Ellipse
  private projectLayer!: Phaser.GameObjects.Container
  private projectSignature = ''
  // Furniture-level "in use" props (companion-position-props.ts): an open book on an academy desk,
  // a made-up bed, the espresso machine's steam - diffed the same way as project shelf art
  // (projectSignature above), so they only redraw when the derived state actually changes. Kept as
  // plain objects (not a Container) so each can carry its own `setDepth(y)` and sort naturally
  // against residents/furniture, the same convention companion-stage.ts's own props use - a
  // Container would instead render its whole contents as one block at the container's own depth.
  private positionPropObjects: Phaser.GameObjects.GameObject[] = []
  private positionPropSignature = ''
  private positionPropTweens: Phaser.Tweens.Tween[] = []
  private placeSelection!: Phaser.GameObjects.Rectangle
  private cafeSelection!: Phaser.GameObjects.Graphics
  private ready = false
  private viewport = { width: 960, height: 516, density: 1 }
  private cameraSignature = ''
  private cameraCenter = { x: 490, y: 286 }
  // True once the camera has ever framed a real (non-zero) viewport. The host element can report
  // 0x0 for a frame or two before layout settles (especially once it is a short docked strip
  // rather than the full /town canvas); that first real measurement should land the camera
  // directly, never pan in from wherever the zero-size placeholder happened to centre it.
  private everFramed = false
  private nextLabelsAt = 0
  constructor(private snapshot: () => SceneSnapshot, private select: (id: string) => void, private selectProject: (id: string) => void = () => {}, private onLabels: (labels: SceneLabel[]) => void = () => {}) { super('companion-street') }
  resizeViewport(width: number, height: number, density: number) {
    this.viewport = { width, height, density }
    if (this.ready) this.frameCamera(true)
  }
  private frameCamera(force = false) {
    const state = this.snapshot(), { width, height, density } = this.viewport
    const compact = width < 600
    const docked = state.cameraMode === 'docked'
    const dockedTarget = state.cameraTarget
    const dockedFrameHeight = state.dockedFrameHeight ?? 176
    const signature = docked
      ? `docked:${width}:${height}:${density}:${dockedTarget?.x ?? ''}:${dockedTarget?.y ?? ''}:${dockedFrameHeight}`
      : `${width}:${height}:${density}:${state.overview}:${state.selectedResidentId ?? ''}:${state.selectedPlace ?? ''}`
    if (!force && this.cameraSignature === signature) return
    this.cameraSignature = signature
    const camera = this.cameras.main
    const viewportValid = width > 0 && height > 0
    // Land instantly the first time there is a real viewport to frame, or whenever the viewer has
    // asked for reduced/no motion; otherwise ease in - see motionAllowed().
    const instant = !this.everFramed || !motionAllowed()
    if (viewportValid) this.everFramed = true
    if (docked && dockedTarget && viewportValid) {
      const zoom = (height / dockedFrameHeight) * density
      const halfWorldWidth = (width / height) * dockedFrameHeight / 2
      const halfWorldHeight = dockedFrameHeight / 2
      // A wide, short strip (or a narrow world) can ask to see more world width than the world
      // actually has. The usual clamp - pull the centre in just far enough that neither edge
      // shows past the world - degenerates the moment the visible span exceeds W or H: both
      // Math.min bounds collapse below the Math.max floor, so Math.max always wins and pins the
      // centre to one fixed edge-safe point while the *opposite* edge still runs past the world.
      // Falling back to the world's own centre here is what actually keeps both edges in bounds
      // when the requested frame simply cannot fit without cropping.
      const center = {
        x: halfWorldWidth * 2 >= W ? W / 2 : Math.max(halfWorldWidth, Math.min(W - halfWorldWidth, dockedTarget.x)),
        y: halfWorldHeight * 2 >= H ? H / 2 : Math.max(halfWorldHeight, Math.min(H - halfWorldHeight, dockedTarget.y)),
      }
      camera.setViewport(0, 0, Math.round(width * density), Math.round(height * density))
      camera.roundPixels = true
      if (instant) { camera.setZoom(zoom); camera.centerOn(center.x, center.y) }
      else {
        if (camera.zoom !== zoom) camera.zoomTo(zoom, 320, 'Cubic.easeOut')
        camera.pan(center.x, center.y, 320, 'Cubic.easeOut', true)
      }
      this.cameraCenter = center
      return
    }
    // The non-compact default frame keeps its original 48px left margin (it used to read as the
    // literal `{ x: 48, w: 1168 }` when the world was 1248px wide) but now derives its width from
    // COMPANION_WORLD_SIZE (`W`) instead of repeating that old world's number, so widening the
    // world (east wing: academy/gym/board) widens the default view by the same amount instead of
    // leaving it stuck framing only the old world's mid-section. `eastMargin` is a small clearance
    // past the east wing's own right wall (ACADEMY_ROOM/GYM_ROOM both end at x=1528 - see
    // companion-art.ts) rather than a hand-fit width: enough that the whole east wing is inside the
    // frame with a few px to spare, whatever COMPANION_WORLD_SIZE grows to next.
    const eastMargin = 16
    const core = shift('cafe', 0, 0), eastEdge = Math.max(GYM_ROOM.x + GYM_ROOM.w, ACADEMY_ROOM.x + ACADEMY_ROOM.w, SHOP_ROOM.x + SHOP_ROOM.w)
    let frame = state.overview ? { x: 0, y: 0, w: W, h: H } : compact ? { x: 470 + core.x - 384, y: core.y - 12, w: 445, h: 370 } : { x: core.x - 336, y: core.y - 12, w: eastEdge + eastMargin - (core.x - 336), h: 480 }
    if (!state.overview && state.selectedPlace && !state.selectedResidentId) {
      const selected = placeFrame(state.selectedPlace)
      frame = { x: selected.x - 12, y: Math.max(0, selected.y - 12), w: selected.w + 24, h: selected.h + 24 }
    }
    if (!state.overview && state.selectedResidentId) frame = compact ? { x: 0, y: 0, w: 445, h: 370 } : { x: 0, y: 0, w: 580, h: 440 }
    let center = { x: frame.x + frame.w / 2, y: frame.y + frame.h / 2 }
    if (!state.overview && (state.selectedResidentId || (compact && state.selectedPlace))) {
      const target = state.selectedResidentId ? this.actors.get(state.selectedResidentId)?.root : undefined
      const place = state.selectedPlace ? placeCenter(state.selectedPlace) : undefined
      if (target || place) {
        const point = target ?? place!
        center = {
          x: Math.max(frame.w / 2, Math.min(W - frame.w / 2, point.x)),
          y: Math.max(frame.h / 2, Math.min(H - frame.h / 2, point.y - 35)),
        }
      }
    }
    const fit = Math.min(width / frame.w, height / frame.h)
    // A frame fitted on one axis shows more world on the other; keep that extra span inside the
    // world instead of centring it past an edge (the wide default frame used to show sky above y=0).
    if (!state.overview && viewportValid) {
      const half = { x: width / fit / 2, y: height / fit / 2 }
      center = {
        x: half.x * 2 >= W ? W / 2 : Math.max(half.x, Math.min(W - half.x, center.x)),
        y: half.y * 2 >= H ? H / 2 : Math.max(half.y, Math.min(H - half.y, center.y)),
      }
    }
    camera.setViewport(0, 0, Math.round(width * density), Math.round(height * density))
    camera.setZoom(fit * density)
    camera.centerOn(center.x, center.y)
    camera.roundPixels = true
    this.cameraCenter = center
  }
  preload() {
    this.load.atlas('town', '/assets/town/town-atlas.png', '/assets/town/town-atlas.json')
    this.load.atlas('companion', '/assets/town/companion-atlas.png', '/assets/town/companion-atlas.json')
    this.load.atlas('interior', '/assets/town/interior-atlas.png', '/assets/town/interior-atlas.json')
    // Generated by scripts/build-town-map.py from town-layout.json; the scene still works without it.
    this.load.tilemapTiledJSON('town-map', '/assets/town/maps/town.tmj')
    this.load.image('town-tiles', '/assets/town/maps/town-tiles.png')
    for (const n of RESIDENT_ART) {
      const base = `/assets/town/characters/${typeof n === 'number' ? `c${String(n).padStart(2, '0')}` : n}`
      this.load.spritesheet(`companion-${n}`, `${base}.png`, { frameWidth: 32, frameHeight: 64 })
      if (typeof n === 'number' && [1, 3, 6, 9, 12].includes(n)) this.load.spritesheet(`companion-${n}-actions`, `${base}-actions.png`, { frameWidth: ACTION_FRAME.width, frameHeight: ACTION_FRAME.height })
    }
  }
  /** Tiled ground layers under everything else, plus the generated planting, when the map exists. */
  private buildGround() {
    if (!this.cache.tilemap.exists('town-map') || !this.textures.exists('town-tiles')) return undefined
    const map = this.make.tilemap({ key: 'town-map' })
    const tiles = map.addTilesetImage('town-tiles', 'town-tiles')
    if (!tiles) return undefined
    ;['ground', 'paths', 'water', 'fences'].forEach((name, index) => map.createLayer(name, tiles, 0, 0)?.setDepth(-300 + index))
    const decor = (map.getObjectLayer('decor')?.objects ?? []).map(object => ({
      x: object.x ?? 0, y: object.y ?? 0, frame: object.name,
      scale: Number((object.properties as { name: string; value: unknown }[] | undefined)?.find(p => p.name === 'scale')?.value ?? 1),
    }))
    return { tiledGround: true, decor }
  }
  create() {
    this.cameras.main.setBackgroundColor('#78857a')
    if (this.textures.exists('town') && this.textures.exists('interior')) this.stage = buildCompanionStage(this, this.buildGround())
    else {
      this.add.rectangle(W / 2, H / 2, W, H, 0xb8bda7)
      this.add.text(480, 190, '街景素材尚未生成 · 居民生活仍在继续', { fontSize: '16px', color: '#56604e' }).setOrigin(.5)
    }
    this.placeSelection = this.add.rectangle(0, 0, 240, 200, 0xffdf9b, .04).setStrokeStyle(2, 0xffdf9b, .8).setVisible(false).setDepth(700)
    this.cafeSelection = this.add.graphics().setDepth(700).setVisible(false)
    this.cafeSelection.fillStyle(0xffdf9b, .04).lineStyle(2, 0xffdf9b, .8)
    const cafeOutline = [[378, 7], [1030, 7], [1030, 555], [858, 555], [858, 339], [378, 339]].map(([x, y]) => shift('cafe', x!, y!))
    this.cafeSelection.fillPoints(cafeOutline, true).strokePoints(cafeOutline, true)
    this.selection = this.add.ellipse(0, 0, 46, 17, 0xffdf9b, .18).setStrokeStyle(2, 0xffe7b2, .9).setVisible(false)
    this.shade = this.add.rectangle(0, 0, W, H, 0x192644, 1).setAlpha(0).setOrigin(0).setDepth(800)
    // One rectangle per home so sync() can light only the homes that actually have someone awake
    // in them, instead of every window in town switching on together at dusk.
    for (const [id, room] of Object.entries(HOME_ROOMS)) {
      const light = this.add.rectangle(room.x + 8 + (room.w - 16) / 2, room.y + 32 + (room.h - 32) / 2, room.w - 16, room.h - 32, 0xffcf7a, .38).setDepth(805).setVisible(false)
      this.homeLights.set(id, light)
    }
    this.cafeLights = [
      this.add.rectangle(CAFE_ROOM.x + 8 + (CAFE_ROOM.w - 16) / 2, CAFE_ROOM.y + 32 + (CAFE_ROOM.h - 32) / 2, CAFE_ROOM.w - 16, CAFE_ROOM.h - 32, 0xffcf7a, .38).setDepth(805).setVisible(false),
      this.add.rectangle(CAFE_WINDOW_ROOM.x + 8 + (CAFE_WINDOW_ROOM.w - 16) / 2, CAFE_WINDOW_ROOM.y + 32 + (CAFE_WINDOW_ROOM.h - 32) / 2, CAFE_WINDOW_ROOM.w - 16, CAFE_WINDOW_ROOM.h - 32, 0xffcf7a, .38).setDepth(805).setVisible(false),
      this.add.ellipse(shift('cafe', 480, 226).x, shift('cafe', 480, 226).y, 180, 185, 0xffd89a, .13).setDepth(805).setVisible(false),
    ]
    this.publicLights = [ACADEMY_ROOM, GYM_ROOM, SHOP_ROOM].map(room =>
      this.add.rectangle(room.x + 8 + (room.w - 16) / 2, room.y + 32 + (room.h - 32) / 2, room.w - 16, room.h - 32, 0xffcf7a, .26).setDepth(805).setVisible(false))
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
    // This art pass only has real anchors in the cafe and garden. A project at a resident's own
    // home remains in the story data until the world supplies a matching place/object; it must not
    // silently appear on a cafe table merely because that is the only old project shelf.
    objects.filter(object => ['cafe', 'garden'].includes(scenePlace(object.place))).forEach((object, index) => {
      const project = state.projects?.find(p => p.id === object.projectId)
      const progress = project?.progress ?? 100
      const kind = object.kind.toLowerCase()
      const cafe = scenePlace(object.place) === 'cafe'
      const shelf = cafe ? shift('cafe', /poster|海报/.test(kind) ? 825 : /flower|花/.test(kind) ? 574 : /book|书/.test(kind) ? 409 : 804, /poster|海报/.test(kind) ? 40 : /flower|花/.test(kind) ? 76 : /book|书/.test(kind) ? 98 : 158)
        : { x: 823 + GARDEN_OFFSET_X + index % 2 * 62, y: gardenY(366) }
      const { x, y } = shelf
      const g = this.add.graphics()
      this.projectLayer.add(g)
      const art = (atlas: string, frame: string, px: number, py: number, scale = 1) => {
        if (!this.textures.exists(atlas) || !this.textures.get(atlas).has(frame)) return
        this.projectLayer.add(this.add.image(px, py, atlas, frame).setOrigin(.5, 1).setScale(scale))
      }
      if (/poster|海报/.test(kind)) {
        art('interior', 'notice_1', x, y, .7)
      } else if (/flower|花/.test(kind)) {
        art('interior', 'plant_1', x, y, .5)
        if (progress > 40) art('town', 'flowers_3', x, y - 18, .5)
      } else if (/book|书/.test(kind)) {
        art('companion', 'office_books', x, y, .6)
      } else {
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
  /** Redraws companion-position-props.ts's derived list only when it actually changed - never
   * per-frame, matching updateProjects()'s own signature-diff just above. */
  private updatePositionProps(state: SceneSnapshot) {
    const props = positionUseProps(state)
    const signature = JSON.stringify(props)
    if (signature === this.positionPropSignature) return
    this.positionPropSignature = signature
    for (const tween of this.positionPropTweens) tween.stop()
    this.positionPropTweens = []
    for (const object of this.positionPropObjects) object.destroy()
    this.positionPropObjects = []
    for (const prop of props) this.drawPositionProp(prop)
  }
  private drawPositionProp(prop: PositionUseProp) {
    if (prop.prop === 'book') {
      // The same small open-book icon update()'s seated hand-prop already draws (brown cover, cream
      // pages, a spine line) - no atlas frame reads as a book lying open on a table at this scale
      // (interior-atlas's only book art, `book_1`, is a shelved upright spine; `office_books` is a
      // whole bookcase) - so this reuses the established procedural icon instead of stretching an
      // unrelated furniture sprite to a size it was not drawn for.
      const g = this.add.graphics().setDepth(prop.y)
      g.fillStyle(0x86745a).fillRect(prop.x - 8, prop.y - 6, 16, 11)
      g.fillStyle(0xece3cb).fillRect(prop.x - 7, prop.y - 5, 14, 9)
      g.lineStyle(1, 0xa3a68c).lineBetween(prop.x, prop.y - 4, prop.x, prop.y + 4)
      this.positionPropObjects.push(g)
    } else if (prop.prop === 'blanket') {
      // A warm cover from just below the sleeping resident's own head down to the foot of the bed.
      // `prop.y` is the resident's plain occupancy pixel (their root settles exactly there, in
      // sync()) - `sleepSpriteOffset()` is the same per-branch correction sync() now applies to the
      // sleep sprite itself (see that function's own doc comment for why solo and shared-household
      // beds need different corrections), so `footY` lands on the bed image's own foot exactly like
      // the sprite's own feet now do, in both branches, instead of this file guessing a second,
      // independent pixel offset that could drift from sync()'s.
      // 44px up from there is "just below the head": the sprite is a fixed 64px tall regardless of
      // branch, and roughly its top third is head/collar - short of the full 64 so the cover reads
      // as pulled up to the sleeper's shoulders, not as a rectangle floating above them.
      const footY = prop.y + sleepSpriteOffset(prop.positionId)
      const g = this.add.graphics().setDepth(prop.y - .5)
      g.fillStyle(0x5f7699, .85).fillRoundedRect(prop.x - 13, footY - 44, 26, 44, 4)
      this.positionPropObjects.push(g)
    } else {
      // A fixed, generously high depth rather than one derived from `prop.y`: companion-stage.ts
      // deliberately does not draw the cafe's back counter and its espresso machine at their own
      // literal y (the machine sits at a lower depth than the counter in front of it, by design, so
      // the front counter's edge reads as nearer the customer) - a wisp depth tied to `prop.y` the
      // way book/blanket use it landed behind the machine's own opaque sprite and never showed at
      // all. Steam rising off a machine should always read in front of the furniture around it.
      const g = this.add.graphics().setDepth(400)
      g.fillStyle(0xf5f0e4, .55).fillEllipse(0, 0, 6, 9)
      g.setPosition(prop.x, prop.y)
      this.positionPropObjects.push(g)
      // Static when reduced/no motion is requested (motionAllowed()) - the wisp still marks the
      // machine as in use, it just does not animate.
      if (motionAllowed()) this.positionPropTweens.push(this.tweens.add({ targets: g, y: prop.y - 14, alpha: { from: .6, to: 0 }, duration: 1800, repeat: -1, ease: 'Sine.easeOut' }))
    }
  }
  sync() {
    if (!this.ready) return
    const state = this.snapshot()
    if (this.stage) { const show = state.chrome !== false; for (const text of this.stage.signage) text.setVisible(show) }
    const night = state.minutes < 360 || state.minutes >= 1140
    // Overview uses the camera's background for the side bars around a tall world. Match the
    // ground instead of exposing a dark canvas edge that has no place in the street.
    this.cameras.main.setBackgroundColor(night ? '#737f74' : state.weather === 'rain' ? '#849176' : '#96a486')
    const ids = new Set(state.residents.map(r => r.id))
    for (const [id, actor] of this.actors) if (!ids.has(id)) { actor.root.destroy(); this.actors.delete(id) }
    // Which homes have someone in them who is not asleep right now - the per-window night light
    // below is lit only for those, dark for an empty home or one where everyone is asleep.
    const homeAwake = new Set<string>()
    state.residents.forEach((resident, index) => {
      const visibleMode = visibleActivity(resident.activity, resident.action, resident.objectKind)
      const atDesk = ['read', 'create', 'rest', 'drink'].includes(visibleMode) && (resident.location === 'cafe' || ['read', 'create'].includes(visibleMode))
      // The server describes a travelling actor as "walk"; destination belongs to its travel plan.
      const travelling = Boolean(resident.destination) && (resident.activity === 'walk' || resident.activity === 'travel')
      const location = travelling ? resident.destination! : resident.location
      if (scenePlace(location) === 'home' && visibleMode !== 'sleep') { const id = homeId(location); if (id) homeAwake.add(id) }
      let actor = this.actors.get(resident.id)
      // A recognised backend positionId wins outright - it says exactly which bed/seat/plot this
      // resident holds. Only guess a seat from place+index (the old heuristic) when there is none,
      // or POSITION_SLOTS has no pixel for it yet.
      const knownPositionId = resident.positionId && POSITION_SLOTS[resident.positionId] ? resident.positionId : undefined
      let seatIndex: number | undefined
      let slot: number | undefined
      let target: { x: number; y: number }
      if (knownPositionId) {
        const capacity = POSITION_SLOTS[knownPositionId]!.length
        // Same-position occupants (e.g. four people at the shared cafe-worktable) are spread across
        // its slots by first-come order, kept stable frame to frame like the old seatIndex below.
        const usedSlots = new Set([...this.actors.entries()].filter(([id, other]) => id !== resident.id && other.positionId === knownPositionId).map(([, other]) => other.slot).filter(value => value !== undefined))
        slot = actor?.positionId === knownPositionId && actor?.slot !== undefined ? actor.slot : ([...Array(capacity).keys()].find(seat => !usedSlots.has(seat)) ?? 0)
        target = residentPosition(location, index, resident.activity, resident.action, knownPositionId, slot, resident.id, [], resident.roomId)
      } else {
        const peersHere = [...this.actors.entries()].filter(([id, other]) => id !== resident.id && other.location === location)
        const usedSeats = new Set(peersHere.map(([, other]) => other.seatIndex).filter(value => value !== undefined))
        seatIndex = atDesk ? (actor?.seatIndex !== undefined && scenePlace(actor.location) === scenePlace(location) ? actor.seatIndex : CAFE_SEATS.map((_, index) => index).find(seat => !usedSeats.has(seat)) ?? index) : undefined
        // Free-standing (no seat matched): spread away from wherever every other resident
        // already visible in this place has settled, whatever put them there - another
        // free-standing pick, a bed, a desk seat, a garden plot.
        const occupied = peersHere.map(([, other]) => other.target)
        target = residentPosition(location, seatIndex ?? index, resident.activity, resident.action, undefined, 0, resident.id, occupied, resident.roomId)
      }
      if (travelling) target = travelAnchor(location)
      const conversation = !travelling ? state.conversations?.find(c => c.status === 'active' && c.place === location && (c.participantIds?.includes(resident.id) || c.turns.some(t => t.speakerId === resident.id))) : undefined
      const participants = conversation?.participantIds ?? [...new Set(conversation?.turns.map(t => t.speakerId) ?? [])]
      // A conversation does not uproot people from an occupied chair or the coffee machine.
      if (conversation && !knownPositionId && seatIndex === undefined) target = conversationPositionInRoom(location, participants.indexOf(resident.id), resident.roomId)
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
          // The purchased sheet has six right-facing sit poses followed by six left-facing poses.
          // Cycling all twelve reverses a person in their chair; keep each orientation separate.
          for (const [facing, start] of [['right', 0], ['left', 6]] as const) {
            const key = `${sheet}-sit-${facing}`
            if (!this.anims.exists(key)) this.anims.create({ key, frames: this.anims.generateFrameNumbers(sheet, { start: 4 * columns + start, end: 4 * columns + start + 5 }), frameRate: 5, repeat: -1 })
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
        const heldProp = this.add.graphics()
        root.add(heldProp)
        const label = this.add.text(0, index % 2 === 0 ? 11 : 28, resident.name, { fontFamily: 'system-ui', fontSize: '11px', color: '#f6e9ca', backgroundColor: '#465c50', resolution: 2, padding: { x: 6, y: 3 } }).setOrigin(.5)
        const activity = this.add.text(0, -86, '', { fontFamily: 'system-ui', fontSize: '12px', color: '#455047', backgroundColor: '#fff3d8', resolution: 2, wordWrap: { width: 190, useAdvancedWrap: true }, align: 'center', padding: { x: 6, y: 4 } }).setOrigin(.5)
        label.setVisible(false); activity.setVisible(false);
        root.add([label, activity]); root.setSize(64, 100).setInteractive(new Phaser.Geom.Rectangle(-32, -80, 64, 110), Phaser.Geom.Rectangle.Contains)
        root.on('pointerdown', () => this.select(resident.id))
        root.on('pointerover', () => { if (actor) actor.hovered = true }); root.on('pointerout', () => { if (actor) actor.hovered = false })
        actor = { mode: 'idle', sleeping: false, root, sprite, heldProp, sheet, label, activity, location, action: resident.action, target, path: [], facing: 'down', seatIndex }
        this.actors.set(resident.id, actor)
      }
      if (actor.target.x !== target.x || actor.target.y !== target.y) actor.path = companionPath(actor.root, target)
      actor.mode = travelling ? 'idle' : visibleMode
      actor.conversationId = conversation?.id
      actor.location = location
      actor.seatIndex = seatIndex
      actor.positionId = knownPositionId
      actor.slot = slot
      actor.target = target; actor.action = resident.action; actor.label.setText(resident.name)
      actor.activity.setText(resident.action)
    })
    this.shade.setAlpha(night ? .28 : state.weather === 'rain' ? .1 : 0)
    for (const [id, light] of this.homeLights) light.setVisible(night && homeAwake.has(id))
    // Gated on the same authoritative cafeStatus the soundscape uses (see CompanionScene.vue's
    // cafeOpen prop) - never on a guess about whether "now" falls inside opening hours.
    const cafeLit = night && (state.cafeOpen ?? true)
    for (const light of this.cafeLights) light.setVisible(cafeLit)
    for (const light of this.publicLights) light.setVisible(night)
    this.updateProjects(state)
    this.updatePositionProps(state)
    this.frameCamera()
  }
  update(time: number, delta: number) {
    const state = this.snapshot()
    const selectedFrame = state.selectedPlace ? placeFrame(state.selectedPlace) : undefined
    const selectedCafe = Boolean(state.selectedPlace && scenePlace(state.selectedPlace) === 'cafe')
    this.placeSelection?.setVisible(Boolean(selectedFrame) && !selectedCafe)
    this.cafeSelection?.setVisible(selectedCafe)
    if (selectedFrame) this.placeSelection.setPosition(selectedFrame.x + selectedFrame.w / 2, selectedFrame.y + selectedFrame.h / 2).setSize(selectedFrame.w, selectedFrame.h)
    const selected = state.selectedResidentId ? this.actors.get(state.selectedResidentId) : undefined
    this.selection?.setVisible(Boolean(selected))
    if (selected) this.selection.setPosition(selected.root.x, selected.root.y - 1).setDepth(selected.root.y - 1)
    // Dialogue is a short DOM caption; the complete exchange lives in the story panel.
    const active = [...(state.conversations ?? [])].reverse().find(c => c.status === 'active' && c.turns.length)
    const recentTurns = active?.turns.slice(-2) ?? []
    const turn = recentTurns[Math.floor(time / 6500) % Math.max(1, recentTurns.length)]
    for (const actor of this.actors.values()) {
      while (actor.path[0] && Phaser.Math.Distance.Between(actor.root.x, actor.root.y, actor.path[0].x, actor.path[0].y) < .5) {
        // Finish the corner before advancing to the next segment; dropping a near waypoint can
        // shave a fraction of a pixel off an L-shaped pavement edge and leave walkable ground.
        actor.root.setPosition(actor.path[0].x, actor.path[0].y)
        actor.path.shift()
      }
      const waypoint = actor.path[0]
      const from = { x: actor.root.x, y: actor.root.y }
      if (waypoint) {
        const next = resolveMove(from, stepTowardPoint(from, waypoint, WALK_PIXELS_PER_SECOND, Math.min(delta, 50)), COMPANION_COLLISION)
        actor.facing = dominantDirection(next.x - from.x, next.y - from.y, actor.facing)
        actor.root.setPosition(next.x, next.y)
      }
      actor.root.setDepth(actor.root.y)
      const peers = actor.conversationId ? [...this.actors.values()].filter(other => other !== actor && other.conversationId === actor.conversationId) : []
      const chatting = peers.length > 0 && !waypoint && peers.every(peer => !peer.path.length)
      if (chatting) actor.facing = dominantDirection(peers[0]!.root.x - actor.root.x, peers[0]!.root.y - actor.root.y, actor.facing)
      actor.sleeping = !waypoint && !actor.conversationId && actor.mode === 'sleep' && scenePlace(actor.location) === 'home'
      const seat = !waypoint && scenePlace(actor.location) === 'cafe' && Math.hypot(actor.root.x - actor.target.x, actor.root.y - actor.target.y) < .5 ? cafeSeatAt(actor.target) : undefined
      const homeDesk = !waypoint && actor.positionId?.endsWith('-desk') && Math.hypot(actor.root.x - actor.target.x, actor.root.y - actor.target.y) < .5
      const seatedFacing = seat?.facing ?? (homeDesk ? 'right' : undefined)
      if (seatedFacing) actor.facing = seatedFacing
      else if (!waypoint && actor.positionId === 'cafe-counter') actor.facing = 'up'
      const mode = waypoint || actor.conversationId ? 'idle' : actor.mode
      const nativeAction = !seatedFacing && isArtAction(mode) && this.anims.exists(`${actor.sheet}-${mode}`)
      const animation = waypoint ? `walk-${actor.facing}` : actor.sleeping ? 'sleep' : seatedFacing ? `sit-${seatedFacing}` : nativeAction ? mode : mode === 'read' ? 'read' : mode === 'rest' ? 'sit' : `idle-${actor.facing}`
      actor.sprite?.setPosition(0, actor.sleeping ? sleepSpriteOffset(actor.positionId) : 0)
        .setOrigin(nativeAction ? ACTION_FRAME.originX : .5, nativeAction ? ACTION_FRAME.originY : 1)
        .setFlipX(false).play(`${actor.sheet}-${animation}`, true)
      // Small pixel props follow the seated hand; the body keeps its actual seat orientation.
      actor.heldProp.clear()
      if (seatedFacing && !actor.conversationId) {
        const handX = seatedFacing === 'right' ? 12 : -12
        if (actor.mode === 'drink') {
          actor.heldProp.fillStyle(0x6d6655).fillRect(handX - 4, -22, 8, 7)
          actor.heldProp.fillStyle(0xf2ead6).fillRect(handX - 3, -22, 6, 5).fillRect(handX + 3, -21, 2, 3)
        } else if (['read', 'create'].includes(actor.mode)) {
          actor.heldProp.fillStyle(0x86745a).fillRect(handX - 7, -21, 14, 10)
          actor.heldProp.fillStyle(0xece3cb).fillRect(handX - 6, -20, 12, 8)
          actor.heldProp.lineStyle(1, 0xa3a68c).lineBetween(handX, -19, handX, -13)
          if (actor.mode === 'create') actor.heldProp.lineStyle(1, 0x766148).lineBetween(handX + 3, -23, handX, -17)
        }
      }

    }
    if (time >= this.nextLabelsAt) {
      this.nextLabelsAt = time + 80
      const zoom = this.cameras.main.zoom / this.viewport.density
      // Read the camera's own current centre rather than the cached target: while a docked pan is
      // still easing toward it (see frameCamera()), midPoint reflects where the camera actually is
      // this frame, so DOM labels stay pinned to their sprites instead of jumping ahead of the pan.
      const cameraCenter = this.cameras.main.midPoint ?? this.cameraCenter
      const labels: SceneLabel[] = []
      for (const [id, actor] of this.actors) {
        const x = (actor.root.x - cameraCenter.x) * zoom + this.viewport.width / 2 + 17
        // Same -28px clearance above the sprite's own head this always had (the old flat sprite
        // offset was -40, this label offset -68) - now relative to sleepSpriteOffset()'s per-branch
        // value instead of the flat constant that offset itself just stopped using.
        const bodyOffset = actor.sleeping ? sleepSpriteOffset(actor.positionId) - 28 : 0
        const bodyHeight = actor.sleeping ? 31 : 60
        const y = (actor.root.y + bodyOffset - cameraCenter.y) * zoom + this.viewport.height / 2 - bodyHeight * zoom - 20
        // Overview always fits the complete world into the viewport. Do not turn residents into
        // edge arrows there because their DOM label's head-offset may cross an edge while the body
        // itself is still plainly visible on the map (the old "画面内却说在画面外" bug).
        const offscreen = !state.overview && (x < 18 || x > this.viewport.width - 18 || y < 38 || y > this.viewport.height - 27)
        const direction = x < 18 ? '‹' : x > this.viewport.width - 18 ? '›' : y < 38 ? '⌃' : '⌄'
        const resident = state.residents.find(r => r.id === id)
        const speech = isSpeaking(id, turn?.speakerId, !actor.path.length) ? turn!.text.slice(0, 23) + (turn!.text.length > 23 ? '…' : '') : undefined
        const status = residentStatus(resident?.activity ?? '', actor.action, Boolean(actor.path.length), Boolean(speech))
        const topic = speech ? conversationEmoji(turn?.emoji) : ''
        const dialogue = speech ? recentTurns.map(line => ({ name: state.residents.find(r => r.id === line.speakerId)?.name ?? '邻居', text: line.text })) : undefined
        labels.push({ worldX: actor.root.x, worldY: actor.root.y, facing: actor.facing, id, name: actor.label.text, role: resident?.role === 'user' ? '你的小人' : resident?.role ?? '小街邻居', x: Math.max(20, Math.min(this.viewport.width - 20, x)), y: Math.max(68, Math.min(this.viewport.height - 29, y)), selected: actor === selected, speechOffset: 8, speech: offscreen ? undefined : speech, action: status.shortAction, emoji: topic, conversationId: speech ? active?.id : undefined, dialogue, bodyX: x - 17, bodyY: y + 20, bodyHeight: bodyHeight * zoom, hovered: actor.hovered, offscreen, direction: offscreen ? direction : undefined })
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
      this.onLabels(visibleSceneLabels(labels))
    }
    if (!this.rain) return
    this.rain.clear()
    if (this.snapshot().weather === 'rain') {
      this.rain.lineStyle(1, 0xeaf3e9, .48)
      for (let i = 0; i < 85; i++) {
        const x = (i * 137 + time * .025) % W, y = (i * 79 + time * .19) % H
        if (!rainFallsOutside(x, y)) continue
        this.rain.lineBetween(x, y, x - 5, y + 13)
      }
    }
  }
}
