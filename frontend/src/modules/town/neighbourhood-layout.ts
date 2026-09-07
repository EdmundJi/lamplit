import type { Point, Rect } from './collision'

/** Addresses are tied to NPC codes, never roster order or a daily schedule. */
const ADDRESSES = [
  ['KE_YUN', '柯云'], ['WEN_QING', '温晴'], ['SHEN_MU', '沈牧'],
  ['LU_XIA', '陆夏'], ['AN_HE', '安禾'], ['JI_MAI', '纪麦'], ['GUIDE', '小助'],
  ['POSTMAN', '邮递员'], ['TOWNIE_01', '王芳'], ['TOWNIE_02', '李娜'],
  ['TOWNIE_03', '张伟'], ['TOWNIE_04', '刘洋'], ['TOWNIE_05', '陈静'],
  ['TOWNIE_06', '杨帆'], ['TOWNIE_07', '赵敏'], ['TOWNIE_08', '黄岚'],
  ['TOWNIE_09', '周雨'], ['TOWNIE_10', '吴桐'], ['TRAVELER', '旅人客舍'],
] as const
export const NEIGHBOURHOOD_LANES = ['书香巷', '花邮巷', '晚风巷'] as const
export type NeighbourHouse = {
  code: string; name: string; label: string; district: string; address: string
  x: number; baseY: number; door: Point; palette: number; row: number
}
export const NEIGHBOUR_HOUSES: NeighbourHouse[] = ADDRESSES.map(([code, name], index) => {
  const row = index < 7 ? 0 : index < 13 ? 1 : 2
  const column = index - (row === 0 ? 0 : row === 1 ? 7 : 13)
  const x = 352 + column * 320, baseY = 1600 + row * 448
  const district = NEIGHBOURHOOD_LANES[row]!
  const label = code === 'TRAVELER' ? name : code === 'GUIDE' ? '小镇服务站' : code === 'POSTMAN' ? '花邮驿站' : `${name}的家`
  return { code, name, label, district,
    address: `${district} ${column + 1} 号`, x, baseY, door: { x: x + 160, y: baseY + 28 },
    palette: (column + row) % 3, row }
})
export function neighbourHouse(code: string): NeighbourHouse | undefined {
  return NEIGHBOUR_HOUSES.find(house => house.code === code)
}
export const NEIGHBOURHOOD_ENTRY: Point = { x: 224, y: 1264 }
/** Looped lanes provide two approaches to every home and a central pedestrian cut-through. */
export const NEIGHBOURHOOD_PATHS: Rect[] = [
  { x: 176, y: 1100, width: 96, height: 1512 },
  { x: 176, y: 1224, width: 2500, height: 80 },
  { x: 2580, y: 1224, width: 96, height: 1388 },
  { x: 1232, y: 1264, width: 64, height: 1348 },
  ...[1600, 2048, 2496].map(y => ({ x: 176, y, width: 2500, height: 112 })),
  // Communal gardens occupy the intentionally unbuilt seventh plot on the quiet lanes.
  { x: 2280, y: 1856, width: 300, height: 304 },
  { x: 2280, y: 2304, width: 300, height: 304 },
]
export const NEIGHBOURHOOD_OBSTACLES: Rect[] = NEIGHBOUR_HOUSES.map(house => ({
  x: house.x, y: house.baseY - 288, width: 224, height: 288,
}))
export function inNeighbourhoodPath(x: number, y: number): boolean {
  return NEIGHBOURHOOD_PATHS.some(rect => x >= rect.x && x <= rect.x + rect.width && y >= rect.y && y <= rect.y + rect.height)
}
