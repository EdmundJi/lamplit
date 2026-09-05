import { SELF_ID } from '../../support/town-fixture'

/**
 * 只有画布截图的 E2E 说明不了"小人真的动了"。town.engine.ts 在 create() 里把场景挂到
 * window.__townScene，这里就借它断言引擎内部状态——走位、碰撞、跑步速度这些数值。
 *
 * 关于按键：Cypress 合成的 keydown 到不了 Phaser 的按键状态机（Phaser 自己在 window 上
 * 收事件并按帧结算，合成事件在 update 循环里读回来一直是 isDown=false），实测小人只会
 * 继续走它的日程。所以这里直接翻 Phaser 的 Key 对象——被测的是引擎的移动/碰撞/速度逻辑，
 * 浏览器事件投递那一层由 HUD 用例（town-hud.cy.ts 的 R 键）覆盖。
 */
function holdArrow(direction: 'left' | 'right', down: boolean) {
  return cy.window().then(win => {
    const key = win.__townScene!.selfKeys!.cursors[direction]
    key.isDown = down
    key.isUp = !down
  })
}

/** 按住方向键 700ms，返回这段时间里 x 的位移（带符号）。 */
function drift(direction: 'left' | 'right'): Cypress.Chainable<number> {
  return cy.window().then(win => {
    const start = win.__townScene!.selfWalker!.sprite.x
    holdArrow(direction, true)
    cy.wait(700)
    holdArrow(direction, false)
    return cy.window().then(w => w.__townScene!.selfWalker!.sprite.x - start)
  })
}

describe('小镇 · 引擎状态', () => {
  beforeEach(() => {
    cy.mockTown()
    cy.visitTown()
    cy.waitForScene()
  })

  it('三户居民 + 两个 NPC 都生成了，自己有独立的 selfWalker', () => {
    cy.townScene().then(scene => {
      const ids = scene.walkers.map(w => w.id)
      expect(ids, '居民').to.include.members([SELF_ID])
      expect(ids, 'NPC').to.include.members(['npc:assistant', 'npc:postman'])
      expect(scene.walkers.length).to.be.at.least(5)
      expect(scene.selfWalker, 'selfWalker').to.not.be.null
    })
  })

  it('碰撞世界建起来了：有可行走区域，也有建筑/家具障碍', () => {
    cy.townScene().then(scene => {
      expect(scene.collisionWorld.walkable.length, '可行走矩形').to.be.greaterThan(0)
      expect(scene.collisionWorld.obstacles.length, '障碍矩形').to.be.greaterThan(0)
    })
  })

  it('按住方向键，自己的小人朝按键方向走（而不是继续走自己的日程）', () => {
    drift('right').should('be.greaterThan', 20)
    drift('left').should('be.lessThan', -20)
  })

  it('奔跑模式下同样时长走得更远（WALK_SPEED 56 → RUN_SPEED 132）', () => {
    drift('right').then(walked => {
      cy.get('.run-toggle').click()
      cy.townScene().its('runMode').should('be.true')
      drift('right').then(ran => {
        expect(ran, `跑 ${Math.round(ran)}px 应明显多于走 ${Math.round(walked)}px`).to.be.greaterThan(walked * 1.8)
      })
    })
  })

  it('切到夜晚会把引擎的 night 打开', () => {
    cy.townScene().its('night').should('be.false')
    cy.contains('button', '切到夜晚').click()
    cy.townScene().its('night').should('be.true')
  })
})
