import type Phaser from 'phaser'
import { COFFEE_CONTACT_RADIUS, COFFEE_DURATION, coffeeHandCup, createCoffeeMotion } from './coffee-motion'

export type TownFacilityId = 'coffee' | 'planter' | 'records' | 'books'
export type TownFacilityPoint = { x: number; y: number }
export type TownFacilityAnchors = Record<TownFacilityId, TownFacilityPoint & { actionPoint: TownFacilityPoint }>
export type TownFacilityState = { cup: boolean; watered: boolean; music: boolean; borrowed: boolean }
type Actor = Phaser.GameObjects.Sprite
type Action = { id: TownFacilityId; actorId: string; sprite: Actor; start: number; duration: number; x: number; y: number; angle: number; props: Phaser.GameObjects.Graphics; coffee?: ReturnType<typeof createCoffeeMotion> }
export type TownFacilityOptions = {
  onSelect?: (id: TownFacilityId, pointer: Phaser.Input.Pointer) => void
  onComplete?: (id: TownFacilityId, actorId: string) => void
  storageKey?: string
  /** Owner of the local borrowed-book state; NPCs read on site and return it. */
  playerId?: string
}
const labels: Record<TownFacilityId, string> = { coffee: '喝杯咖啡', planter: '给花浇水', records: '播放街角唱片', books: '借一本书看看' }
const durations: Record<TownFacilityId, number> = { coffee: COFFEE_DURATION, planter: 5200, records: 1800, books: 6000 }
const clamp = (n: number) => Math.max(0, Math.min(1, n))

/** User/NPC initiated facilities inside the production town; no scripted citizens or growth awards. */
export function createTownFacilities(scene: Phaser.Scene, anchors: TownFacilityAnchors, options: TownFacilityOptions = {}) {
  const playerId = options.playerId ?? 'self'
  const state: TownFacilityState = { cup: false, watered: false, music: false, borrowed: false }
  if (options.storageKey) {
    try {
      const stored = JSON.parse(localStorage.getItem(options.storageKey) ?? '{}')
      for (const key of Object.keys(state) as (keyof TownFacilityState)[]) if (typeof stored?.[key] === 'boolean') state[key] = stored[key]
    } catch { /* A disabled or malformed local store must never block play. */ }
  }
  const save = () => { if (options.storageKey) try { localStorage.setItem(options.storageKey, JSON.stringify(state)) } catch { /* Ephemeral mode. */ } }
  const active = new Map<string, Action>()
  const recentCompleted: { id: TownFacilityId; actorId: string; atMs: number }[] = []
  const reserved = new Map<TownFacilityId, { actorId: string; until: number }>()
  const owned: Phaser.GameObjects.GameObject[] = []
  const keep = <T extends Phaser.GameObjects.GameObject>(o: T) => { owned.push(o); return o }
  const layers = {} as Record<TownFacilityId, Phaser.GameObjects.Graphics>
  const coffeeRest = coffeeHandCup(anchors.coffee.actionPoint, 4, 1, anchors.coffee.actionPoint.x >= anchors.coffee.x)
  const tableCup = keep(scene.add.image(coffeeRest.x, coffeeRest.y, 'interior', 'coffee_cup').setOrigin(.5).setScale(.5).setDepth(anchors.coffee.y + 4))
  let now = 0
  let destroyed = false
  let hovered: TownFacilityId | null = null
  let audio: AudioContext | undefined
  let audioGain: GainNode | undefined
  let audioCapture: MediaStreamAudioDestinationNode | undefined
  let muted = false
  let nextNote = 0
  let melodyIndex = 0
  const voices = new Set<OscillatorNode>()
  const notes = [261.63, 329.63, 392, 329.63, 293.66, 0, 261.63, 196, 220, 261.63, 329.63, 293.66, 261.63, 0, 196, 0]
  function silenceAudio() {
    if (audio && audioGain) {
      audioGain.gain.cancelScheduledValues(audio.currentTime)
      audioGain.gain.setTargetAtTime(0, audio.currentTime, .025)
    }
    for (const voice of voices) { try { voice.stop() } catch { /* Completed voice. */ } }
    voices.clear(); nextNote = 0
  }
  function updateAudio() {
    if (!audio || !audioGain || audio.state !== 'running') return
    if (!state.music || muted) { if (nextNote || voices.size) silenceAudio(); return }
    audioGain.gain.setTargetAtTime(.11, audio.currentTime, .08)
    if (nextNote < audio.currentTime) nextNote = audio.currentTime + .03
    while (nextNote < audio.currentTime + .12) {
      const frequency = notes[melodyIndex % notes.length]!
      if (frequency) {
        const voice = audio.createOscillator(), envelope = audio.createGain()
        voice.type = 'sine'; voice.frequency.value = frequency
        envelope.gain.setValueAtTime(0, nextNote)
        envelope.gain.linearRampToValueAtTime(.16, nextNote + .025)
        envelope.gain.exponentialRampToValueAtTime(.0001, nextNote + .58)
        voice.connect(envelope); envelope.connect(audioGain)
        voices.add(voice)
        voice.onended = () => { voices.delete(voice); voice.disconnect(); envelope.disconnect() }
        voice.start(nextNote); voice.stop(nextNote + .6)
      }
      melodyIndex++; nextNote += .36
    }
  }
  /** Called synchronously from the initiating click/key gesture; saved music never autoplays. */
  function unlockAudio() {
    if (destroyed || typeof AudioContext === 'undefined') return
    try {
      if (!audio) { audio = new AudioContext(); audioGain = audio.createGain(); audioGain.gain.value = 0; audioGain.connect(audio.destination) }
      if (audio.state === 'suspended') void audio.resume().then(updateAudio).catch(() => { /* Browser denied audio; visual interaction still works. */ })
      else updateAudio()
    } catch { /* Audio is optional on unsupported devices. */ }
  }
  function setMuted(value: boolean) { muted = value; if (value) silenceAudio(); else updateAudio() }

  for (const id of Object.keys(anchors) as TownFacilityId[]) {
    const p = anchors[id]
    layers[id] = keep(scene.add.graphics().setDepth(p.y + 4))
    const zone = keep(scene.add.zone(p.x, p.y - 12, id === 'books' ? 66 : 64, 58).setDepth(p.y + 10).setInteractive({ useHandCursor: true }))
    const hint = keep(scene.add.text(p.x, p.y - 54, labels[id], { fontFamily: 'sans-serif', fontSize: '12px', color: '#fff1d1', backgroundColor: '#3c4b41', padding: { x: 8, y: 5 } }).setOrigin(.5, 1).setDepth(100000).setVisible(false))
    zone.on('pointerover', () => { hovered = id; hint.setText(id === 'records' && state.music ? '关闭唱片' : id === 'books' && state.borrowed ? '归还借阅的书' : labels[id]).setVisible(true) })
    zone.on('pointerout', () => { hovered = null; hint.setVisible(false) })
    zone.on('pointerup', (pointer: Phaser.Input.Pointer) => { hint.setVisible(false); options.onSelect?.(id, pointer) })
  }
  function book(g: Phaser.GameObjects.Graphics, x: number, y: number, open: boolean) {
    g.fillStyle(0x795f45).fillRect(x - 12, y - 7, 24, 15)
    g.fillStyle(0xf7e7ba).fillRect(x - 10, y - 5, 20, 11)
    if (open) { g.lineStyle(1, 0x9e875d).lineBetween(x, y - 5, x, y + 6); if (now % 2200 > 1750) g.fillStyle(0xffefc6).fillTriangle(x, y - 5, x + 8, y - 13, x, y + 6) }
    else g.fillStyle(0x788d76).fillRect(x - 12, y - 7, 23, 13)
  }
  function draw() {
    for (const layer of Object.values(layers)) layer.clear()
    if (hovered) { const h = anchors[hovered]; layers[hovered].lineStyle(1, 0xf0d79b, .65).strokeEllipse(h.x, h.y, 66, 23) }
    const held = [...active.values()].some(a => a.coffee?.holdsCup())
    tableCup.setVisible(!held).setTint(state.cup ? 0xe5d8bd : 0xffffff)
    const p = anchors.planter, pg = layers.planter
    if (state.watered) {
      pg.fillStyle(0x48372b, .8).fillEllipse(p.x, p.y - 7, 34, 9)
      for (let i = 0; i < 3; i++) { const x = p.x - 12 + i * 12, y = p.y - 15 - i % 2 * 5; pg.lineStyle(2, 0x648053).lineBetween(x, p.y - 5, x, y); pg.fillStyle(i % 2 ? 0xe9b88c : 0xe7d697).fillCircle(x - 3, y, 3).fillCircle(x + 3, y, 3).fillCircle(x, y - 3, 3); pg.fillStyle(0xbb8d51).fillCircle(x, y, 2) }
    }
    const r = anchors.records, rg = layers.records
    if (state.music) {
      for (let i = 0; i < 3; i++) { const rise = (now / 55 + i * 17) % 55, x = r.x - 13 + i * 12 + Math.sin(now / 900 + i) * 3, y = r.y - 29 - rise; rg.lineStyle(2, 0xf0d58e, 1 - rise / 65).lineBetween(x, y, x, y - 10); rg.fillStyle(0xf0d58e, 1 - rise / 65).fillEllipse(x - 3, y + 1, 7, 5) }
      rg.lineStyle(1, 0x796548).lineBetween(r.x - 54, r.y - 75, r.x + 54, r.y - 75)
      for (let i = 0; i < 7; i++) { const x = r.x - 48 + i * 16; rg.fillStyle(0xffd482,.07).fillCircle(x,r.y-70,11);rg.fillStyle(0xffdc94,.95).fillCircle(x,r.y-71,2.5) }
    }
    const b = anchors.books
    if (!state.borrowed) book(layers.books, b.x + 1, b.y - 12, false)
    else layers.books.fillStyle(0x514632, .8).fillRect(b.x - 10, b.y - 20, 21, 17)
  }
  function reserve(id: TownFacilityId, actorId: string) {
    if (destroyed || !anchors[id]) return false
    if (id === 'books' && state.borrowed && actorId !== playerId) return false
    const held = reserved.get(id)
    if (held && held.actorId !== actorId && held.until > now) return false
    if ([...active.values()].some(a => a.id === id && a.actorId !== actorId)) return false
    reserved.set(id, { actorId, until: now + 30000 })
    return true
  }
  function cancel(actorId?: string) {
    for (const [key, action] of active) if (actorId === undefined || key === actorId) {
      if (action.sprite.active) action.sprite.setAngle(action.angle)
      action.coffee?.destroy(); action.props.destroy(); active.delete(key)
    }
    for (const [id, reservation] of reserved) if (actorId === undefined || reservation.actorId === actorId) reserved.delete(id)
    if (!destroyed) draw()
  }
  function activate(id: TownFacilityId, sprite: Actor, actorId = 'self') {
    if (destroyed || !sprite.active || active.has(actorId) || !anchors[id]) return false
    const point = anchors[id].actionPoint
    if (Math.hypot(sprite.x - point.x, sprite.y - point.y) > (id === 'coffee' ? COFFEE_CONTACT_RADIUS : 52) || !reserve(id, actorId)) return false
    active.set(actorId, { id, actorId, sprite, start: now, duration: durations[id], x: sprite.x, y: sprite.y, angle: sprite.angle, props: scene.add.graphics().setDepth(sprite.y + 4), coffee: id === 'coffee' ? createCoffeeMotion(scene, sprite, anchors.coffee) : undefined })
    return true
  }
  function update(deltaMs: number) {
    if (destroyed) return
    now += Math.max(0, deltaMs)
    for (const [id, reservation] of reserved) if (reservation.until < now && !active.has(reservation.actorId)) reserved.delete(id)
    for (const [actorId, a] of active) {
      if (!a.sprite.active || !a.sprite.scene || Math.hypot(a.sprite.x - a.x, a.sprite.y - a.y) > 12) { cancel(actorId); continue }
      const elapsed = now - a.start, progress = clamp(elapsed / a.duration), p = anchors[a.id], g = a.props
      g.clear().setDepth(a.sprite.y + 4)
      const hand = { x: a.sprite.x + (p.x < a.sprite.x ? -9 : 9), y: a.sprite.y - 28 }
      if (a.id === 'coffee') {
        a.coffee?.update(elapsed)
      } else if (a.id === 'planter') {
        a.sprite.setAngle(a.angle + (progress < .85 ? 5 : 0))
        g.fillStyle(0x7e9f98).fillRoundedRect(hand.x - 6, hand.y - 7, 16, 13, 3)
        g.lineStyle(3, 0x7e9f98).lineBetween(hand.x + 8, hand.y - 2, hand.x + 18, hand.y + 3).strokeCircle(hand.x - 5, hand.y - 5, 5)
        if (progress > .12 && progress < .85) for (let i = 0; i < 6; i++) { const fall = (now / 32 + i * 5) % 28; g.fillStyle(0xb2dadd,.85).fillEllipse(hand.x + 18 + (p.x-hand.x-18)*fall/28,hand.y+5+(p.y-hand.y-8)*fall/28,2,3) }
      } else if (a.id === 'books') book(g, hand.x, hand.y, progress > .15 && progress < .9)
      else { g.lineStyle(4, 0xd4a682).lineBetween(hand.x, hand.y, p.x, p.y - 14); a.sprite.setAngle(a.angle + Math.sin(progress * Math.PI) * 4) }
      if (elapsed >= a.duration) {
        if (a.id === 'coffee') state.cup = true
        if (a.id === 'planter') state.watered = true
        if (a.id === 'records') state.music = !state.music
        if (a.id === 'books') state.borrowed = actorId === playerId ? !state.borrowed : false
        recentCompleted.push({ id: a.id, actorId, atMs: now })
        if (recentCompleted.length > 12) recentCompleted.shift()
        save(); cancel(actorId); options.onComplete?.(a.id, actorId)
      }
    }
    draw()
    updateAudio()
  }
  draw()
  return {
    captureAudio: () => {
      if (destroyed || !audio || !audioGain) return null
      if (!audioCapture || audioCapture.stream.getAudioTracks().every(track => track.readyState === 'ended')) {
        if (audioCapture) audioGain.disconnect(audioCapture)
        audioCapture = audio.createMediaStreamDestination()
        audioGain.connect(audioCapture)
      }
      return audioCapture.stream
    },
    activate, reserve, cancel, update, unlockAudio, setMuted,
    isBusy: (actorId: string) => active.has(actorId),
    interactables: () => (Object.keys(anchors) as TownFacilityId[]).map(id => ({ id, ...anchors[id], label: id === 'records' && state.music ? '关闭唱片' : id === 'books' && state.borrowed ? '归还借阅的书' : labels[id] })),
    snapshot: () => ({ state: { ...state }, recentCompleted: recentCompleted.map(item => ({ ...item })), active: [...active.values()].map(a => ({ id: a.id, actorId: a.actorId, elapsed: now-a.start, duration: a.duration, ...(a.coffee ? { motion: a.coffee.snapshot() } : {}) })), reserved: [...reserved].map(([id,r]) => ({ id, actorId:r.actorId })) }),
    destroy: () => { destroyed = true; cancel(); silenceAudio(); audioCapture?.stream.getTracks().forEach(track => track.stop()); audioCapture?.disconnect(); audioGain?.disconnect(); if (audio) void audio.close().catch(() => {}); audio = undefined; owned.forEach(o => o.destroy()) },
  }
}
