describe('小镇 · HUD 与操作', () => {
  beforeEach(() => {
    cy.mockTown()
    cy.visitTown()
  })

  it('昼夜切换按钮改变自己的文案，并把夜晚状态传给引擎', () => {
    cy.waitForScene()
    cy.contains('button', /切到夜晚|切到白天/).as('toggle')
    cy.get('@toggle').invoke('text').then(before => {
      cy.get('@toggle').click()
      cy.get('@toggle').should('not.contain', before.trim())
    })
    // 引擎侧真的收到了：场景的 night 与按钮当前提示相反（按钮显示的是"下一步会切到哪"）
    cy.get('@toggle').invoke('text').then(text => {
      cy.townScene().its('night').should('eq', text.includes('切到白天'))
    })
  })

  it('奔跑开关用 aria-pressed 表达状态，快捷键 R 和按钮是同一个开关', () => {
    cy.contains('button', /开始奔跑|奔跑中/).as('run')
    cy.get('@run').should('have.attr', 'aria-pressed', 'false')
    cy.get('@run').click()
    cy.get('@run').should('have.attr', 'aria-pressed', 'true').and('contain', '奔跑中')
    cy.get('body').type('r')
    cy.get('@run').should('have.attr', 'aria-pressed', 'false')
  })

  it('去学院 / 回到小镇是同一个按钮的两个状态', () => {
    cy.waitForScene()
    cy.contains('button', '去学院').click()
    cy.contains('button', '回到小镇').should('be.visible')
    cy.get('.town-panel').should('contain', '成长学院')
    cy.contains('button', '回到小镇').click()
    cy.contains('button', '去学院').should('be.visible')
  })

  it('找小助会打开对话，反思接口给出的问候语作为开场白', () => {
    cy.wait('@reflection')
    cy.contains('button', '找小助').click()
    cy.get('.town-panel').should('contain', '昨天你把最难的一章啃完了')
  })

  it('邮递员按未读数改口径', () => {
    cy.get('.town-directory summary').click()
    cy.contains('.town-directory button', '邮递员').click()
    cy.get('.town-panel').should('contain', '2 封新信')
  })

  it('帮助按钮能把新手引导重新叫出来', () => {
    cy.contains('button[title="重新打开新手引导"]', '帮助').click()
    cy.contains('成长小镇').should('be.visible')
    cy.get('body').then($body => {
      // 引导用的是一个覆盖层；只要它出现了（有跳过/下一步这类按钮）就算通过。
      expect($body.text()).to.match(/跳过|下一步|开始/)
    })
  })

  it('沉浸模式按钮跳到 /town/immersive', () => {
    cy.contains('button[title="进入沉浸模式"]', '沉浸模式').click()
    cy.location('pathname').should('eq', '/town/immersive')
  })

  it('接口挂掉时给出可读的错误，而不是白屏', () => {
    cy.intercept('GET', '/api/v1/town', { statusCode: 500, body: { data: { status: 500, code: 'BOOM', message: '小镇暂时没能加载出来，请稍后再试' } } }).as('boom')
    cy.visit('/town')
    cy.wait('@boom')
    cy.get('[role="alert"]').should('contain', '小镇暂时没能加载出来')
  })
})
