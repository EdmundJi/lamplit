/**
 * 小镇街道家具与陈设数据模型。数据驱动，便于调整布局密度与种类。
 */

export type FurnitureItem = {
  /** 唯一标识，用于交互追踪 */
  id: string
  /** 图集帧名 */
  frame: string
  /** 世界坐标 x */
  x: number
  /** 世界坐标 y */
  y: number
  /** 精灵深度（通常是 y 值） */
  depth?: number
  /** 是否可交互 */
  interactive?: boolean
  /** 交互类型 */
  interactionType?: 'sit' | 'read' | 'view'
  /** 碰撞矩形（相对于 x,y 的偏移 + 尺寸），不填则无碰撞 */
  collision?: { offsetX: number; offsetY: number; width: number; height: number }
}

export type VenueFurniture = {
  /** 场地名称（健身房/咖啡馆/公园） */
  venueName: string
  /** 场地中心 x 坐标 */
  centerX: number
  /** 基线 y */
  baselineY: number
  /** 该场地的配套陈设 */
  items: FurnitureItem[]
}

/** 健身房外配套陈设 */
export function createGymFurniture(centerX: number, baselineY: number): VenueFurniture {
  return {
    venueName: '健身房',
    centerX,
    baselineY,
    items: [
      // 告示牌（营业时间）
      {
        id: 'gym-sign-1',
        frame: 'infosign_1',
        x: centerX - 80,
        y: baselineY + 48,
        depth: baselineY + 48,
        interactive: true,
        interactionType: 'read',
        collision: { offsetX: 0, offsetY: 32, width: 32, height: 16 },
      },
      // 自行车架
      {
        id: 'gym-bike-rack',
        frame: 'bench_2', // 暂用长椅帧（素材包可能没自行车架）
        x: centerX + 60,
        y: baselineY + 52,
        depth: baselineY + 52,
        collision: { offsetX: 0, offsetY: 32, width: 64, height: 20 },
      },
      // 垃圾桶
      {
        id: 'gym-trash-1',
        frame: 'mailbox_1', // 暂用邮筒帧代替垃圾桶
        x: centerX - 100,
        y: baselineY + 60,
        depth: baselineY + 60,
        collision: { offsetX: 4, offsetY: 16, width: 24, height: 20 },
      },
      // 花坛装饰
      {
        id: 'gym-flowers-1',
        frame: 'flowerbush_3',
        x: centerX + 90,
        y: baselineY + 45,
        depth: baselineY + 45,
        collision: { offsetX: 0, offsetY: 20, width: 32, height: 16 },
      },
    ],
  }
}

/** 咖啡馆外配套陈设 */
export function createCafeFurniture(centerX: number, baselineY: number): VenueFurniture {
  return {
    venueName: '咖啡馆',
    centerX,
    baselineY,
    items: [
      // 露天座椅 + 小圆桌
      {
        id: 'cafe-table-1',
        frame: 'bench_1',
        x: centerX - 70,
        y: baselineY + 55,
        depth: baselineY + 55,
        interactive: true,
        interactionType: 'sit',
        collision: { offsetX: 0, offsetY: 24, width: 48, height: 24 },
      },
      {
        id: 'cafe-table-2',
        frame: 'bench_1',
        x: centerX + 40,
        y: baselineY + 55,
        depth: baselineY + 55,
        interactive: true,
        interactionType: 'sit',
        collision: { offsetX: 0, offsetY: 24, width: 48, height: 24 },
      },
      // 菜单牌
      {
        id: 'cafe-menu',
        frame: 'infosign_2',
        x: centerX - 90,
        y: baselineY + 48,
        depth: baselineY + 48,
        interactive: true,
        interactionType: 'read',
        collision: { offsetX: 0, offsetY: 32, width: 32, height: 16 },
      },
      // 花箱装饰
      {
        id: 'cafe-flowers-1',
        frame: 'flowerbush_1',
        x: centerX + 80,
        y: baselineY + 50,
        depth: baselineY + 50,
        collision: { offsetX: 0, offsetY: 20, width: 32, height: 16 },
      },
      {
        id: 'cafe-flowers-2',
        frame: 'flowerbush_2',
        x: centerX - 110,
        y: baselineY + 50,
        depth: baselineY + 50,
        collision: { offsetX: 0, offsetY: 20, width: 32, height: 16 },
      },
    ],
  }
}

/** 公园入口配套陈设 */
export function createParkFurniture(centerX: number, baselineY: number): VenueFurniture {
  return {
    venueName: '公园',
    centerX,
    baselineY,
    items: [
      // 入口长椅（可坐）
      {
        id: 'park-bench-1',
        frame: 'gardenbench_1',
        x: centerX - 90,
        y: baselineY + 58,
        depth: baselineY + 58,
        interactive: true,
        interactionType: 'sit',
        collision: { offsetX: 0, offsetY: 40, width: 96, height: 24 },
      },
      {
        id: 'park-bench-2',
        frame: 'gardenbench_1',
        x: centerX + 70,
        y: baselineY + 58,
        depth: baselineY + 58,
        interactive: true,
        interactionType: 'sit',
        collision: { offsetX: 0, offsetY: 40, width: 96, height: 24 },
      },
      // 指示牌
      {
        id: 'park-sign',
        frame: 'infosign_1',
        x: centerX,
        y: baselineY + 48,
        depth: baselineY + 48,
        interactive: true,
        interactionType: 'read',
        collision: { offsetX: 0, offsetY: 32, width: 32, height: 16 },
      },
      // 花坛
      {
        id: 'park-flowers-1',
        frame: 'flowerbush_4',
        x: centerX - 50,
        y: baselineY + 45,
        depth: baselineY + 45,
        collision: { offsetX: 0, offsetY: 20, width: 32, height: 16 },
      },
      {
        id: 'park-flowers-2',
        frame: 'flowerbush_5',
        x: centerX + 30,
        y: baselineY + 45,
        depth: baselineY + 45,
        collision: { offsetX: 0, offsetY: 20, width: 32, height: 16 },
      },
      // 垃圾桶
      {
        id: 'park-trash',
        frame: 'mailbox_1',
        x: centerX + 100,
        y: baselineY + 60,
        depth: baselineY + 60,
        collision: { offsetX: 4, offsetY: 16, width: 24, height: 20 },
      },
    ],
  }
}

/** 街道城市家具（沿人行道成组布置） */
export function createStreetFurniture(
  startX: number,
  endX: number,
  baselineY: number,
  pitch: number,
): FurnitureItem[] {
  const items: FurnitureItem[] = []
  let id = 0

  for (let x = startX; x < endX; x += pitch) {
    // 使用更好的伪随机种子，避免固定模式
    const seed = Math.abs(((x * 73) + 17) % 97)

    // 每 pitch 一组，随机选择不同的城市家具组合
    if (seed % 5 === 0) {
      // 报刊亭（用花车代替，素材限制）
      items.push({
        id: `street-kiosk-${id++}`,
        frame: 'flowercart_1',
        x: x + 20,
        y: baselineY + 60,
        depth: baselineY + 60,
        interactive: true,
        interactionType: 'view',
        collision: { offsetX: 0, offsetY: 40, width: 64, height: 32 },
      })
    }

    if (seed % 4 === 1) {
      // 邮筒
      items.push({
        id: `street-mailbox-${id++}`,
        frame: 'mailbox_1',
        x: x + 50,
        y: baselineY + 60,
        depth: baselineY + 60,
        collision: { offsetX: 4, offsetY: 16, width: 24, height: 20 },
      })
    }

    if (seed % 3 === 2) {
      // 花箱组
      items.push({
        id: `street-flowerbox-${id++}`,
        frame: 'flowerbush_6',
        x: x + 80,
        y: baselineY + 50,
        depth: baselineY + 50,
        collision: { offsetX: 0, offsetY: 20, width: 32, height: 16 },
      })
      items.push({
        id: `street-flowerbox-${id++}`,
        frame: 'flowers_3',
        x: x + 120,
        y: baselineY + 50,
        depth: baselineY + 50,
        collision: { offsetX: 0, offsetY: 8, width: 32, height: 8 },
      })
    }

    if (seed % 6 === 3) {
      // 长椅（可坐）
      items.push({
        id: `street-bench-${id++}`,
        frame: 'bench_1',
        x: x + 100,
        y: baselineY + 62,
        depth: baselineY + 62,
        interactive: true,
        interactionType: 'sit',
        collision: { offsetX: 0, offsetY: 24, width: 48, height: 24 },
      })
    }

    // 增加更多种类确保多样性
    if (seed % 7 === 4) {
      // 垃圾桶（用 mailbox_1 的变体位置表示不同物品）
      items.push({
        id: `street-trash-${id++}`,
        frame: 'flowerbush_2',
        x: x + 150,
        y: baselineY + 50,
        depth: baselineY + 50,
        collision: { offsetX: 0, offsetY: 20, width: 32, height: 16 },
      })
    }

    if (seed % 8 === 5) {
      // 小型花坛
      items.push({
        id: `street-flowers-${id++}`,
        frame: 'flowerbush_4',
        x: x + 180,
        y: baselineY + 50,
        depth: baselineY + 50,
        collision: { offsetX: 0, offsetY: 20, width: 32, height: 16 },
      })
    }
  }

  return items
}

/** 地面细节层项目（井盖/斑马线/草地边界） */
export type GroundDetail = {
  frame: string
  x: number
  y: number
  depth: number
}

/** 生成地面细节层（井盖等） */
export function createGroundDetails(
  startX: number,
  endX: number,
  baselineY: number,
  streetY: number,
): GroundDetail[] {
  const details: GroundDetail[] = []

  // 每隔一段距离放一个井盖样式的地砖（用现有的 asphalt 变体）
  for (let x = startX + 200; x < endX; x += 400) {
    details.push({
      frame: 'asphalt_15', // 使用深色 asphalt 变体模拟井盖
      x,
      y: streetY + 40,
      depth: streetY - 1, // 在地板之上，人物之下
    })
  }

  return details
}
