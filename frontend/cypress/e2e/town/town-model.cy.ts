import { NEIGHBOUR_ACTIVE_ID, NEIGHBOUR_IDLE_ID } from '../../support/town-fixture'

/**
 * docs/成长小镇.md 那张"画面里的每样东西都对应一条数据"的表，是小镇唯一的产品契约。
 * 这里逐行验证：数值变了，面板上的文案必须跟着变。
 */
describe('小镇 · 数据到画面的映射', () => {
  beforeEach(() => {
    cy.mockTown()
    cy.visitTown()
  })

  it('默认面板给出小镇总览：户数与今天开张的家数', () => {
    // 三户人家，其中自己（DONE）和阿泽（IN_PROGRESS）今天开了张，小满没有安排。
    cy.contains('h2', '3 户人家').should('be.visible')
    cy.contains('今天已经开张 2 户').should('be.visible')
    cy.get('.town-legend li').should('have.length', 5)
  })

  it('自己的房子：等级换楼层、连续记录换屋顶、称号只在自己名下显示', () => {
    cy.get('.town-directory summary').click()
    cy.contains('.town-directory button', '我的家').click()

    cy.get('.town-panel').within(() => {
      cy.contains('.eyebrow', '这是你的房子')
      cy.contains('h2', '测试镇长')
      cy.contains('small', '晨型人') // equippedTitle 只有自己看得到
      // LV.7 → floorsForLevel(7)=3 → 连地面共 4 层
      cy.contains('dd', 'LV.7 · 4 层')
      cy.contains('dd', '知识')          // KNOWLEDGE → 书店门面
      cy.contains('dd', '今天已经有收获（1/2）')
      cy.contains('dd', '21 天')
      cy.contains('dd', '升到 LV.9 加盖')  // 下一层门槛 =(3+1)*2+1
      cy.contains('a', '继续今天的行动').should('have.attr', 'href', '/today')
    })
  })

  it('邻居的房子：显示 TA 的等级与状态，不显示称号，入口指向好友页', () => {
    cy.get('.town-directory summary').click()
    cy.contains('.town-directory button', '邻居阿泽').click()

    cy.get('.town-panel').within(() => {
      cy.contains('.eyebrow', '邻居')
      cy.contains('dd', 'LV.3 · 2 层')
      cy.contains('dd', '健康')
      cy.contains('dd', '正在进行中')
      cy.contains('a', '看看 TA 的成长').should('have.attr', 'href', `/friends/${NEIGHBOUR_ACTIVE_ID}`)
    })
  })

  it('没有任何安排的邻居显示"今天还没开张"（对应卷帘门放下）', () => {
    cy.get('.town-directory summary').click()
    cy.contains('.town-directory button', '邻居小满').click()
    cy.get('.town-panel').within(() => {
      cy.contains('dd', 'LV.1 · 1 层')
      cy.contains('dd', '今天还没开张')
      cy.contains('dd', '0 天')
    })
    cy.wrap(NEIGHBOUR_IDLE_ID).should('be.a', 'string')
  })

  it('学院列出今天已完成任务的居民', () => {
    cy.get('.town-directory summary').click()
    cy.contains('.town-directory button', '成长学院').click()
    cy.get('.town-panel').within(() => {
      cy.contains('h2', '成长学院')
      cy.contains('今天有 1 位邻居完成了任务')
      cy.get('.town-roll li').should('have.length', 1).and('contain', '我 · 1/2')
    })
  })

  it('等级涨了以后，重新轮询会把楼层数刷新到面板上', () => {
    cy.get('.town-directory summary').click()
    cy.contains('.town-directory button', '我的家').click()
    cy.contains('dd', 'LV.7 · 4 层')

    cy.mockTown().then(fixture => {
      const grown = structuredClone(fixture.town)
      grown.residents[0].level = 9
      cy.intercept('GET', '/api/v1/town', { statusCode: 200, body: { data: grown, requestId: 'x', timestamp: '' } }).as('grown')
    })
    cy.get('.page-head [aria-label="刷新"]').click()
    cy.wait('@grown')
    // LV.9 → floorsForLevel(9)=4（封顶）→ 5 层，且不再提示"下一层"
    cy.contains('dd', 'LV.9 · 5 层')
    cy.get('.town-facts').should('not.contain', '加盖')
  })
})
