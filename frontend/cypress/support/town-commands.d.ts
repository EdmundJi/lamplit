/** 用例真正会读到的引擎字段；town.engine.ts create() 里挂到 window.__townScene 上。 */
type TownScene = {
  walkers: { id: string; state: string; running: boolean; sprite: { x: number; y: number } }[]
  night: boolean
  collisionWorld: { walkable: unknown[]; obstacles: unknown[] }
  atmosphere: unknown
  selfWalker: { sprite: { x: number; y: number }; running: boolean; state: string } | null
  runMode: boolean
  /** Phaser 的按键对象；用例直接翻它来模拟按住方向键（见 town-engine.cy.ts 的说明）。 */
  selfKeys: { cursors: Record<'left' | 'right' | 'up' | 'down', { isDown: boolean; isUp: boolean }> } | null
}

type TownFixtureShape = import('./town-fixture').TownFixture

declare namespace Cypress {
  interface Chainable {
    /** 打桩全部 /api/v1 请求，让小镇画面的数值完全确定。CYPRESS_LIVE=1 时整体跳过。 */
    mockTown(overrides?: Partial<TownFixtureShape>): Chainable<TownFixtureShape>
    /** 访问一个路由并等到小镇模型加载完成（`@town` 请求返回）。 */
    visitTown(path?: string, options?: { freshPrefs?: boolean }): Chainable<void>
    /** 拿到引擎在 window 上挂的 Phaser 场景。 */
    townScene(): Chainable<TownScene>
    /** 等到 Phaser 场景真的创建出来（画布出现 ≠ 场景就绪）。 */
    waitForScene(): Chainable<TownScene>

    /** 声明这条用例预期内会抛的错误；其余未捕获错误会在 afterEach 里让用例变红。 */
    allowErrors(...patterns: RegExp[]): void
    /** canvas 世界的结构化快照：玩家坐标、场上角色、身边可互动物、面板状态、错误与失败请求。 */
    townSnapshot(): Chainable<import('../../src/modules/town/town-probe').ProbeSnapshot>
    /** 同一份快照的中文描述，给人和 agent 直接读。 */
    townDescribe(): Chainable<string>
    /** 记一步到 test-results/town-probe/<用例名>.md，用例跑完自动落盘。 */
    record(step: string): Chainable<string>
    /** 按住方向键走一段，返回位移。走的是引擎的移动/碰撞逻辑，不是合成键盘事件。 */
    townWalk(direction: 'left' | 'right' | 'up' | 'down', ms?: number): Chainable<{ ok: boolean; reason: string; moved: number; from: { x: number; y: number } | null; to: { x: number; y: number } | null }>
    /** 一路走到某个 x 坐标（撞墙或超时会 ok:false 并说明原因）。 */
    townWalkTo(x: number, timeoutMs?: number): Chainable<{ ok: boolean; reason: string; x: number | null }>
  }
}

interface Window {
  __townScene?: TownScene
}
