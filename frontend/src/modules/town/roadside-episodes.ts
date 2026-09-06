/**
 * M7-9 路上插曲的纯判定：擦肩时偶尔停下打招呼、雨天往屋檐下靠。这一层只回答"现在要不要演一
 * 段"，不碰 `NpcDayPlan`、不改相遇序列，也不做任何渲染——`town.engine.ts` 才是决定"演成什么样"
 * 的地方。
 *
 * 硬约束（plan.md §2.9 D20、M7-9 验收）：插曲不写库、不改相遇序列，所以它的随机源必须与
 * dayPlan/相遇序列完全隔离。dayPlan 的偏离用 `seed(npc_code, date)` 保证"同一天能重算出同一
 * 条链"；插曲恰恰相反，必须"每次都可能不一样，且关掉它夜间 job 的结果一模一样"——这意味着
 * 这里的随机源绝不能接受调用方传入按 `(npc, date)` 派生的确定性种子，默认也必须是运行时随机
 * （`Math.random`），而不是任何可复现的伪随机。
 */

export type Weather = 'clear' | 'rain' | 'snow'

/** 调用方可以注入自己的随机数源用于测试，但生产代码路径必须留给默认值——见文件头的硬约束。 */
export type RandomSource = () => number

const DEFAULT_GREETING_CHANCE = 0.15
const DEFAULT_SHELTER_CHANCE = 0.5

/** 擦肩打招呼时表现层应该暂停多久（纯常量，判定本身不关心具体动画时长）。 */
export const PASSING_GREETING_PAUSE_MS = 2_000

/**
 * 两人擦肩而过时，是否临时停下打个招呼。只应该在真正的擦肩（`PASSING`，一方 `AT` 一方
 * `WALKING`，或双方都在 `WALKING` 且路径交叉）发生时调用；这里只负责概率判定本身，不判断
 * "是不是擦肩"——那仍然由 CONTRACT-M7.md §3 的相遇类型判定负责，且完全在后端/相遇序列那条
 * 通路上，跟这个函数是两回事。
 */
export function shouldPauseForGreeting(random: RandomSource = Math.random, chance = DEFAULT_GREETING_CHANCE): boolean {
  return random() < chance
}

/**
 * 雨天走在户外的人是否要往屋檐下靠一靠。非雨天恒为 `false`，不消耗随机数——这样调用方不用先
 * 查天气再决定要不要调用它。
 */
export function shouldSeekRoadsideShelter(weather: Weather, random: RandomSource = Math.random, chance = DEFAULT_SHELTER_CHANCE): boolean {
  if (weather !== 'rain') return false
  return random() < chance
}
