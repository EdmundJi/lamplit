import { SELF_ID, townFixture, type TownFixture } from './town-fixture'



// Cypress 16 把 Cypress.env() 挪成了 cy.env()（异步命令），这里要的是注册时就能读到的同步值。
const LIVE = Boolean(Cypress.config('env')?.LIVE)

function envelope(data: unknown) {
  return { data, requestId: 'cypress', timestamp: new Date().toISOString() }
}

Cypress.Commands.add('mockTown', (overrides: Partial<TownFixture> = {}) => {
  const fixture = townFixture(overrides)
  if (LIVE) return cy.wrap(fixture, { log: false })

  // 注册顺序 = 优先级倒序：Cypress 用最后注册的匹配路由，所以兜底必须先注册。
  // 兜底存在的意义是"没有任何请求会偷偷打到真后端"——面板里那些还没单独打桩的接口
  // 会拿到空数据并走各自的空态，而不是把测试变成对后端状态的赌博。
  cy.intercept('GET', '/api/v1/**', { statusCode: 200, body: envelope([]) })
  cy.intercept({ method: 'POST', url: '/api/v1/**' }, { statusCode: 200, body: envelope({}) })

  cy.intercept('GET', '/api/v1/me', { statusCode: 200, body: envelope(fixture.me) }).as('me')
  cy.intercept('GET', '/api/v1/me/profile', { statusCode: 200, body: envelope(fixture.profile) })
  cy.intercept('GET', '/api/v1/town', { statusCode: 200, body: envelope(fixture.town) }).as('town')
  cy.intercept('POST', '/api/v1/town/presence', { statusCode: 200, body: envelope(fixture.presence) }).as('presence')
  cy.intercept('GET', '/api/v1/town/reflection/latest', {
    statusCode: fixture.reflection ? 200 : 404,
    body: fixture.reflection
      ? envelope(fixture.reflection)
      : envelope({ status: 404, code: 'TOWN_REFLECTION_NOT_FOUND', message: '还没有反思记录' }),
  }).as('reflection')
  cy.intercept('GET', '/api/v1/town/npc/*/messages', { statusCode: 200, body: envelope(fixture.npcMessages) }).as('npcHistory')
  cy.intercept('GET', '/api/v1/friends/unread-summary', { statusCode: 200, body: envelope({ totalUnread: fixture.town.unread }) })

  // 沉浸模式的面板各自拉自己的数据。这些接口返回"合法的空态"而不是兜底的 []——面板对
  // 形状是有假设的（比如属性面板直接读 data.attributes.length），拿到错形状会在渲染里抛错。
  cy.intercept('GET', '/api/v1/insights/attributes', { statusCode: 200, body: envelope({ overallLevel: 7, totalExperience: 4200, attributes: [] }) })
  cy.intercept('GET', '/api/v1/insights/overview', {
    statusCode: 200,
    body: envelope({ effectiveActions: 12, fulfillmentRate: 0.6, recoveryCount: 1, totalExperience: 4200, statusCheckCount: 3, statusAdvices: {} }),
  })
  cy.intercept('GET', '/api/v1/insights/trends*', { statusCode: 200, body: envelope([]) })
  cy.intercept('GET', '/api/v1/progress/roles', { statusCode: 200, body: envelope([]) })
  cy.intercept('GET', '/api/v1/achievements', { statusCode: 200, body: envelope([]) })
  cy.intercept('GET', '/api/v1/daily-status*', { statusCode: 200, body: envelope({}) })
  cy.intercept('GET', '/api/v1/partners/profile', { statusCode: 200, body: envelope(fixture.partnerProfile) })
  cy.intercept('GET', '/api/v1/ai/sessions*', { statusCode: 200, body: envelope({ items: [] }) })
  cy.intercept('GET', '/api/v1/titles', { statusCode: 200, body: envelope([]) })

  return cy.wrap(fixture, { log: false })
})

Cypress.Commands.add('visitTown', (path = '/town', options: { freshPrefs?: boolean } = {}) => {
  cy.visit(path, {
    onBeforeLoad(win) {
      // 首次欢迎层（UserLayout 的 WelcomeGuide）和小镇新手引导都是全屏遮罩，会拦下所有点击。
      // 它们各自有专门的用例，其余用例统一按"老玩家"进场。
      win.localStorage.setItem(`better-self:welcome:${SELF_ID}`, 'dismissed')
      win.localStorage.setItem(`better-self:town-onboarding:${SELF_ID}:completed`, 'true')
      if (options.freshPrefs) win.localStorage.removeItem('better-self:town-immersive')
    },
  })
  if (!LIVE) cy.wait('@town')
  cy.get('canvas', { timeout: 30_000 }).should('exist')
})

Cypress.Commands.add('townScene', () => cy.window().its('__townScene'))

Cypress.Commands.add('waitForScene', () =>
  cy.window({ timeout: 30_000 }).should(win => {
    const scene = (win as unknown as { __townScene?: TownScene }).__townScene
    expect(scene, 'Phaser 场景已创建').to.exist
    expect(scene!.walkers.length, '居民和 NPC 已生成').to.be.greaterThan(0)
  }).its('__townScene'),
)

export {}
