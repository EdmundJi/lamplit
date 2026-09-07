import Phaser from 'phaser'
import { buildCafeStage, CAFE_OBSTACLES, CAFE_WALK_BOUNDS } from './cafe-stage'
import { createCafeLife, preloadCafeLife } from './cafe-life'
import { canStand, nearestStandable, resolveMove, type CollisionWorld, type Point } from '../collision'
import { findPath } from '../pathfinding'
import { dominantDirection, movementDelta, stepTowardPoint, type Direction4 } from '../walkers'
import { createCafeSound } from './cafe-sound'

export type CafeDemoState = { ready: boolean; paused: boolean; elapsed: number; walking: boolean; labels: boolean; message: string }
export type CafeDemo = ReturnType<typeof createCafeDemo>

/** A deliberately authored, reproducible scene. No production social records are fabricated. */
export function createCafeDemo(parent: HTMLElement, onState: (state: CafeDemoState) => void, onError: (message: string) => void) {
  let stage: ReturnType<typeof buildCafeStage> | undefined
  let life: ReturnType<typeof createCafeLife> | undefined
  let scene: Phaser.Scene | undefined
  let player: Phaser.GameObjects.Sprite | undefined
  let shadow: Phaser.GameObjects.Ellipse | undefined
  let marker: Phaser.GameObjects.Arc | undefined
  let keys: Record<string, Phaser.Input.Keyboard.Key> = {}
  let path: Point[] = []
  let facing: Direction4 = 'up'
  let disposed = false
  let elapsed = 0
  let paused = false
  let walking = false
  let labels = true
  let lastEmit = -1000
  let message = ''
  let messageUntil = 0
  const sound = createCafeSound()
  const world: CollisionWorld = { walkable: Array.isArray(CAFE_WALK_BOUNDS) ? CAFE_WALK_BOUNDS : [CAFE_WALK_BOUNDS], obstacles: CAFE_OBSTACLES }
  const emit = () => onState({ ready: !!life, paused, elapsed, walking, labels, message })
  const tell = (text: string) => { message = text; messageUntil = performance.now() + 4000; emit() }

  class CafeScene extends Phaser.Scene {
    constructor() { super('cafe-demo') }
    preload() {
      this.load.on('loaderror', (file: Phaser.Loader.File) => onError(`未能加载场景素材：${file.key}。请运行 pnpm town:assets 后重试。`))
      this.load.atlas('town', '/assets/town/town-atlas.png', '/assets/town/town-atlas.json')
      this.load.atlas('interior', '/assets/town/interior-atlas.png', '/assets/town/interior-atlas.json')
      this.load.spritesheet('cafe-player', '/assets/town/characters/c12.png', { frameWidth: 32, frameHeight: 64 })
      preloadCafeLife(this)
    }
    create() {
      if (disposed) return
      scene = this
      try {
        this.cameras.main.setZoom(1.2).centerOn(480, 325)
        stage = buildCafeStage(this)
        life = createCafeLife(this)
        const columns = (this.textures.get('cafe-player').getSourceImage() as HTMLImageElement).width / 32
        for (const [direction, index] of Object.entries({ right: 0, up: 1, left: 2, down: 3 })) {
          for (const [action, row] of [['idle', 1], ['walk', 2]] as const) {
            this.anims.create({ key: `cafe-player-${action}-${direction}`, frames: Array.from({ length: 6 }, (_, i) => ({ key: 'cafe-player', frame: row * columns + index * 6 + i })), frameRate: action === 'walk' ? 9 : 5, repeat: -1 })
          }
        }
        shadow = this.add.ellipse(180, 510, 24, 8, 0x352c30, .22).setVisible(false)
        player = this.add.sprite(180, 510, 'cafe-player').setOrigin(.5, 1).setScale(1.4).setVisible(false).play('cafe-player-idle-up')
        marker = this.add.circle(0, 0, 5).setStrokeStyle(1, 0xffe3ae, .8).setDepth(2).setVisible(false)
        keys = this.input.keyboard!.addKeys('W,A,S,D,UP,DOWN,LEFT,RIGHT') as typeof keys
        this.input.keyboard!.addCapture('W,A,S,D,UP,DOWN,LEFT,RIGHT')
        this.input.on('pointerdown', (pointer: Phaser.Input.Pointer) => {
          if (!walking || !player || paused) return
          const target = nearestStandable({ x: pointer.worldX, y: pointer.worldY }, world)
          const next = findPath(player, target, world)
          if (next) { path = next; marker?.setPosition(target.x, target.y).setVisible(true) }
          else tell('这边过不去，试试桌边的小路。')
        })
        life.update(0)
        emit()
      } catch (error) { onError(`场景加载失败：${error instanceof Error ? error.message : String(error)}`) }
    }
    update(_time: number, delta: number) {
      if (!life || !player || disposed) return
      const dt = Math.min(delta, 50)
      if (!paused) {
        elapsed += dt
        stage?.update(elapsed)
        life.update(elapsed)
        if (walking) {
          const from = { x: player.x, y: player.y }
          const dx = Number(keys.D?.isDown || keys.RIGHT?.isDown) - Number(keys.A?.isDown || keys.LEFT?.isDown)
          const dy = Number(keys.S?.isDown || keys.DOWN?.isDown) - Number(keys.W?.isDown || keys.UP?.isDown)
          let to = from
          if (dx || dy) {
            path = []; marker?.setVisible(false)
            const step = movementDelta(dx, dy, 88, dt)
            to = resolveMove(from, { x: from.x + step.x, y: from.y + step.y }, world)
          } else if (path[0]) {
            to = resolveMove(from, stepTowardPoint(from, path[0], 88, dt), world)
            if (Math.hypot(to.x - path[0].x, to.y - path[0].y) < 1) path.shift()
            if (!path.length) marker?.setVisible(false)
          }
          const moved = Math.hypot(to.x - from.x, to.y - from.y) > .01
          facing = dominantDirection(to.x - from.x, to.y - from.y, facing)
          player.setPosition(to.x, to.y).setDepth(to.y).play(`cafe-player-${moved ? 'walk' : 'idle'}-${facing}`, true)
          shadow?.setPosition(to.x, to.y - 2).setDepth(to.y - 1)
        }
      }
      if (message && performance.now() > messageUntil) { message = ''; emit() }
      sound.update(elapsed, paused)
      if (elapsed - lastEmit > 200) { lastEmit = elapsed; emit() }
    }
  }
  const game = new Phaser.Game({
    type: Phaser.AUTO, parent, width: 960, height: 640, backgroundColor: '#343e37',
    pixelArt: true, roundPixels: true, render: { preserveDrawingBuffer: true },
    scale: { mode: Phaser.Scale.FIT, autoCenter: Phaser.Scale.CENTER_BOTH },
    scene: CafeScene, audio: { noAudio: true },
  })
  const api = {
    setPaused(value: boolean) {
      paused = value
      sound.update(elapsed, paused)
      if (scene) { scene.anims.paused = value; scene.tweens.timeScale = value ? 0 : 1 }
      emit()
    },
    restart() { elapsed = 0; lastEmit = -1000; sound.resetCursor(); life?.reset(); life?.update(0); stage?.update(0); path = []; marker?.setVisible(false); api.setPaused(false) },
    seek(ms: number) { elapsed = Math.max(0, Math.min(120000, ms)); sound.resetCursor(); sound.update(elapsed, paused); life?.update(elapsed); stage?.update(elapsed); lastEmit = -1000; emit() },
    async setSoundEnabled(value: boolean) { await sound.setEnabled(value) },
    setLabelsVisible(value: boolean) { labels = value; life?.setLabelsVisible(value); emit() },
    setWalking(value: boolean) {
      walking = value; path = []; marker?.setVisible(false)
      player?.setVisible(value); shadow?.setVisible(value)
      if (value && player && !canStand(player, world)) { const p = nearestStandable({ x: 180, y: 510 }, world); player.setPosition(p.x, p.y) }
      emit()
    },
    snapshot() { return { ready: !!life, elapsed, paused, labels, walking, player: player ? { x: player.x, y: player.y, visible: player.visible, standable: canStand(player, world), pathLength: path.length } : null, life: life?.snapshot() } },
    saveFrame() { const link = document.createElement('a'); link.download = '傍晚街角.png'; link.href = game.canvas.toDataURL('image/png'); link.click() },
    destroy() { disposed = true; sound.destroy(); life?.destroy(); stage?.destroy(); game.destroy(true) },
  }
  return api
}
