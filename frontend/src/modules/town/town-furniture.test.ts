import { describe, it, expect } from 'vitest'
import {
  createGymFurniture,
  createCafeFurniture,
  createParkFurniture,
  createStreetFurniture,
  createGroundDetails,
} from './town-furniture'

describe('町家具系统', () => {
  describe('场地配套家具', () => {
    it('健身房家具包含告示牌/自行车架/垃圾桶/花坛', () => {
      const gym = createGymFurniture(1000, 800)
      expect(gym.venueName).toBe('健身房')
      expect(gym.centerX).toBe(1000)
      expect(gym.items.length).toBeGreaterThanOrEqual(4)

      const hasSign = gym.items.some(item => item.interactionType === 'read')
      const hasCollision = gym.items.every(item => item.collision !== undefined)
      expect(hasSign).toBe(true)
      expect(hasCollision).toBe(true)
    })

    it('咖啡馆家具包含座椅/菜单牌/花箱', () => {
      const cafe = createCafeFurniture(1500, 800)
      expect(cafe.venueName).toBe('咖啡馆')
      expect(cafe.items.length).toBeGreaterThanOrEqual(4)

      const seats = cafe.items.filter(item => item.interactionType === 'sit')
      const menu = cafe.items.filter(item => item.interactionType === 'read')
      expect(seats.length).toBeGreaterThanOrEqual(2)
      expect(menu.length).toBeGreaterThanOrEqual(1)
    })

    it('公园家具包含长椅/指示牌/花坛/垃圾桶', () => {
      const park = createParkFurniture(500, 800)
      expect(park.venueName).toBe('公园')
      expect(park.items.length).toBeGreaterThanOrEqual(5)

      const benches = park.items.filter(item => item.interactionType === 'sit')
      expect(benches.length).toBeGreaterThanOrEqual(2)
    })
  })

  describe('街道家具', () => {
    it('沿街布置城市家具，密度合理', () => {
      const items = createStreetFurniture(0, 2000, 800, 400)
      // 2000px 范围，400px pitch，预期至少 4-5 组家具
      expect(items.length).toBeGreaterThanOrEqual(4)

      // 检查有多样性
      const types = new Set(items.map(item => item.frame))
      expect(types.size).toBeGreaterThanOrEqual(3)
    })

    it('街道家具包含可交互项', () => {
      const items = createStreetFurniture(0, 2000, 800, 400)
      const interactive = items.filter(item => item.interactive)
      expect(interactive.length).toBeGreaterThanOrEqual(1)
    })

    it('所有街道家具都有唯一 ID', () => {
      const items = createStreetFurniture(0, 2000, 800, 400)
      const ids = new Set(items.map(item => item.id))
      expect(ids.size).toBe(items.length)
    })
  })

  describe('地面细节层', () => {
    it('生成井盖等地面装饰', () => {
      const details = createGroundDetails(0, 2000, 800, 850)
      expect(details.length).toBeGreaterThanOrEqual(1)
      details.forEach(detail => {
        expect(detail.frame).toBeTruthy()
        expect(detail.depth).toBeLessThan(850)
      })
    })
  })

  describe('碰撞数据完整性', () => {
    it('可交互家具必须有碰撞矩形', () => {
      const gym = createGymFurniture(1000, 800)
      const cafe = createCafeFurniture(1500, 800)
      const park = createParkFurniture(500, 800)

      const allItems = [...gym.items, ...cafe.items, ...park.items]
      const interactive = allItems.filter(item => item.interactive)

      interactive.forEach(item => {
        expect(item.collision).toBeDefined()
        expect(item.collision!.width).toBeGreaterThan(0)
        expect(item.collision!.height).toBeGreaterThan(0)
      })
    })

    it('碰撞矩形尺寸合理（不超过 200px）', () => {
      const items = createStreetFurniture(0, 2000, 800, 400)
      items.forEach(item => {
        if (item.collision) {
          expect(item.collision.width).toBeLessThanOrEqual(200)
          expect(item.collision.height).toBeLessThanOrEqual(200)
        }
      })
    })
  })
})
