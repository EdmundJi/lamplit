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
  }
}

interface Window {
  __townScene?: TownScene
}
