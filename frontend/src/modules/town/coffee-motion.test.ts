import { describe, expect, it, vi } from 'vitest'
import type Phaser from 'phaser'
import { coffeeHandCup, coffeePoseAt, createCoffeeMotion } from './coffee-motion'

describe('compact coffee gesture', () => {
  it('transfers the cup at the same contact point and keeps it close to the original hand', () => {
    const actor = { x: 476, y: 1045 }
    const rest = coffeeHandCup(actor, 4)
    expect(coffeePoseAt(749).held).toBe(false)
    expect(coffeePoseAt(750).held).toBe(true)
    expect(coffeeHandCup(actor, coffeePoseAt(750).step!)).toEqual(rest)
    expect(coffeeHandCup(actor, coffeePoseAt(4399).step!)).toEqual(rest)
    expect(coffeePoseAt(4400).held).toBe(false)
    let previous = rest
    for (let t = 750; t < 4400; t += 50) {
      const pose = coffeePoseAt(t)
      const cup = coffeeHandCup(actor, pose.step!)
      expect(Math.hypot(cup.x-actor.x, cup.y-actor.y)).toBeLessThan(25)
      expect(Math.hypot(cup.x-previous.x, cup.y-previous.y)).toBeLessThan(8)
      expect(pose.step).toBeLessThanOrEqual(6)
      previous = cup
    }
  })

  it('includes a breathing pause between sips without rotating the whole person', () => {
    expect(coffeePoseAt(1400).phase).toBe('sip')
    expect(coffeePoseAt(2200).phase).toBe('breathe')
    expect(coffeePoseAt(2900).phase).toBe('sip')
    expect(coffeePoseAt(5300).phase).toBe('settle')
  })

  it.each([1792, 1854])('keeps identity and restores the actor after cancellation (%ipx sheet)', width => {
    const picture = {
      setScale: vi.fn().mockReturnThis(), setOrigin: vi.fn().mockReturnThis(), setVisible: vi.fn().mockReturnThis(),
      setPosition: vi.fn().mockReturnThis(), setDepth: vi.fn().mockReturnThis(), setAngle: vi.fn().mockReturnThis(), destroy: vi.fn(),
    }
    const scene = { add: { image: vi.fn(() => picture) } } as unknown as Phaser.Scene
    const texture = { key: 'the-same-resident', getSourceImage: () => ({ width, height: 1312 }) }
    const actor = {
      x: 476, y: 1045, active: true, scaleX: 1, angle: 0, texture, frame: { name: 68 as string | number },
      anims: { currentAnim: { key: 'resident-idle-left' }, isPlaying: true, stop: vi.fn() },
      setFrame: vi.fn(function(this: {frame:{name:string|number}}, frame: string | number) { this.frame.name = frame; return this }),
      play: vi.fn(),
    }
    const motion = createCoffeeMotion(scene, actor as unknown as Phaser.GameObjects.Sprite, { x: 435, y: 1048 })
    motion.update(1400)
    expect(actor.texture).toBe(texture)
    expect(actor.angle).toBe(0)
    expect(actor.frame.name).toBe(Math.floor(width/32)*11+34)
    motion.destroy()
    expect(actor.frame.name).toBe(68)
    expect(actor.play).toHaveBeenCalledWith('resident-idle-left', true)
    expect(picture.destroy).toHaveBeenCalledOnce()
  })
})
