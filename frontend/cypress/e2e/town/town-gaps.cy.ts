/**
 * ⚠️ 这个 spec 默认是红的，而且应该保持红的。
 *
 * 它断言的是"代码已经写好、但没接上线"的几处：后端接口、纯函数、单元测试全都在，
 * 唯独缺最后一根线，所以在真实浏览器里完全没有效果。放在这里当作待办清单——
 * 接上一处就绿一条，全绿了就把这个文件并进上面的正式 spec。
 *
 * 跑：pnpm cy:gaps
 */
describe('小镇 · 已实现但未接线的能力', () => {
  beforeEach(() => {
    cy.mockTown()
  })

  it('走动应该把位置上报给 /town/presence（后端 V19 + TownPresenceService 已就绪）', () => {
    // town.engine.ts 有 onPresenceReport 回调、town.store 有 reportPresence()，
    // 但 TownView / ImmersiveTown 挂载引擎时没有传这个 handler，于是没人调用。
    cy.visitTown()
    cy.waitForScene()
    cy.get('body').trigger('keydown', { key: 'ArrowRight', code: 'ArrowRight' })
    cy.wait(1500)
    cy.get('body').trigger('keyup', { key: 'ArrowRight', code: 'ArrowRight' })
    cy.wait('@presence', { timeout: 10_000 })
  })

  it('邻居的 presence 应该让小人出现在别人当时站的位置', () => {
    cy.mockTown().then(fixture => {
      const withPresence = structuredClone(fixture.town)
      withPresence.residents[1].presence = { x: 2400, y: 900, facing: 'left', scene: 'town', updatedAt: new Date().toISOString() }
      cy.intercept('GET', '/api/v1/town', { statusCode: 200, body: { data: withPresence, requestId: 'x', timestamp: '' } }).as('town')
    })
    cy.visitTown()
    cy.waitForScene().then(scene => {
      const neighbour = scene.walkers.find(w => w.id.startsWith('nb-active'))
      expect(neighbour, '邻居走到了服务端记录的坐标').to.have.nested.property('sprite.x').closeTo(2400, 80)
    })
  })

  it('天气系统应该有入口：atmosphere.setWeather 存在但没有任何调用方', () => {
    cy.visitTown()
    cy.waitForScene()
    // 期望能从 UI 或引擎切到雨天；目前 TownGame 完全没有暴露天气开关。
    cy.window().its('__townScene').then(scene => {
      expect(scene, 'TownGame 暴露 setWeather').to.have.property('setWeather')
    })
  })

  it('环境小事件（ambient-events.ts）应该在场景里被调度', () => {
    // AmbientEventScheduler 写完并有单测，但 town.engine.ts 从未 import 它。
    cy.visitTown()
    cy.waitForScene().then(scene => {
      expect(scene, '场景持有事件调度器').to.have.property('ambientScheduler')
    })
  })

  it('NPC 日程（npc-schedule.ts）应该让小助按时间换位置', () => {
    // ASSISTANT_SCHEDULE / currentTimeSlot 已实现且有单测，同样没有被 import。
    cy.visitTown()
    cy.waitForScene().then(scene => {
      expect(scene, '场景按日程摆放 NPC').to.have.property('npcSchedules')
    })
  })

  it('室内场景（interior.scene.ts）应该有入口：TownGame.enterRoom 没有任何调用方', () => {
    cy.visitTown()
    cy.waitForScene()
    // 引擎导出了 enterRoom('home'|'academy'|'gym')，但 UI 里没有一个按钮/交互能触发它。
    cy.contains('button', /进屋|回家看看|进入室内/).should('exist')
  })
})
