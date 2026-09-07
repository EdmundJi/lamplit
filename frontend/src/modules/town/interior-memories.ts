import { observeHomePreference } from './home-style'
import type Phaser from 'phaser'
import type { RoomFurniture } from './map-loader'

/** Supplied only from earned records; the room never creates a title, milestone or reward. */
export type InteriorMemory = { code?: string; title: string; detail?: string }

export function createInteriorMemories(scene: Phaser.Scene, board: RoomFurniture, memories: InteriorMemory[], player: () => Phaser.GameObjects.Sprite | null, accountId = '') {
  let earned = memories.filter(memory => memory.title.trim())
  if (!earned.length) return { destroy() {} }
  const card = scene.add.text(board.x + 38, board.y + 14, '', {
    fontFamily: '"PingFang SC", "Microsoft YaHei", sans-serif', fontSize: '11px', color: '#594a35',
    backgroundColor: '#f4e7c9', padding: { x: 11, y: 9 }, wordWrap: { width: 175 }, lineSpacing: 4,
  }).setDepth(9050).setAlpha(0).setResolution(2)
  // This is physical earned content, not a root-level navigation hint.
  const content = scene.add.container(0, 0, [card]).setDepth(9050)
  let visible = false
  let since = 0
  let index = -1
  const stop = observeHomePreference(accountId, preference => {
    earned = memories.filter(memory => memory.title.trim() && (!memory.code || !preference.hiddenMementos.includes(memory.code))).slice(0, 4)
    index = -1; since = 0; visible = false
    card.setAlpha(0)
    if (!earned.length) card.setAlpha(0)
  })
  const update = (_time: number, delta: number) => {
    const avatar = player()
    const near = earned.length > 0 && !!avatar && Math.hypot(avatar.x - board.x, avatar.y - board.y) < 112
      && !document.querySelector('[role="dialog"], .resident-moment')
    if (near !== visible) { visible = near; since = 0; card.setAlpha(near ? 1 : 0) }
    if (!near) return
    since += Math.min(delta, 100)
    const next = Math.floor(since / 6500) % earned.length
    if (next !== index) {
      index = next
      const memory = earned[index]!
      card.setText(`已获得 · ${memory.title}${memory.detail ? `\n${memory.detail}` : ''}`)
    }
  }
  scene.events.on('update', update)
  const destroy = () => { stop(); scene.events.off('update', update); content.destroy(true) }
  scene.events.once('shutdown', destroy)
  return { destroy }
}
