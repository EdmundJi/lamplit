const PREFS_KEY = 'better-self:town-immersive'

// 外壳的窗口容器用 role="dialog" 定位：面板组件自己的根元素也带 .world-panel 这个类，
// 按类名选会一个窗口数出两个元素。

describe('小镇 · 沉浸模式外壳', () => {
  beforeEach(() => {
    cy.mockTown()
    cy.visitTown('/town/immersive', { freshPrefs: true })
  })

  it('dock 列出全部九个功能面板', () => {
    cy.get('.immersive-dock .dock-button').should('have.length', 9)
    cy.get('.immersive-dock').should('contain', '今天').and('contain', 'AI 助手').and('contain', '设置')
  })

  it('点 dock 打开面板窗口，关闭按钮把它收掉', () => {
    cy.contains('.dock-button', '目标').click()
    cy.get('[role="dialog"]').should('have.length', 1).and('contain', '目标')
    cy.contains('.dock-button', '目标').should('have.attr', 'aria-pressed', 'true')
    cy.get('[role="dialog"] [aria-label="关闭"]').click()
    cy.get('[role="dialog"]').should('not.exist')
  })

  it('最小化的窗口进入下方托盘，点回来还能展开', () => {
    cy.contains('.dock-button', '属性').click()
    cy.get('[role="dialog"] [aria-label="最小化"]').click()
    cy.get('[role="dialog"]').should('not.exist')
    cy.get('.immersive-minimized button').should('contain', '属性').click()
    cy.get('[role="dialog"]').should('contain', '属性')
  })

  it('同时最多展开四扇窗，第五扇会把最久没点的那扇收起来而不是关掉', () => {
    for (const name of ['今天', '目标', '属性', '伙伴']) cy.contains('.dock-button', name).click()
    cy.get('[role="dialog"]').should('have.length', 4)
    // 等四扇窗都加载完再开第五扇：面板在"请求还在飞"的时候被最小化（卸载），
    // 回来的那次渲染会读到已经停掉的 computed，抛出未捕获错误（见交付说明里的第 4 条）。
    cy.get('[role="dialog"]').contains('正在整理').should('not.exist')
    cy.contains('.dock-button', '个人').click()
    cy.get('[role="dialog"]').should('have.length', 4)
    // 第一个打开的"今天"被收进托盘，状态没丢
    cy.get('.immersive-minimized button').should('contain', '今天')
  })

  it('Esc 依次关掉最上层窗口，全关完再按才退出沉浸模式', () => {
    cy.contains('.dock-button', '目标').click()
    cy.contains('.dock-button', '属性').click()
    cy.get('[role="dialog"]').should('have.length', 2)
    cy.get('body').type('{esc}')
    cy.get('[role="dialog"]').should('have.length', 1)
    cy.get('body').type('{esc}')
    cy.get('[role="dialog"]').should('not.exist')
    cy.get('body').type('{esc}')
    cy.location('pathname').should('eq', '/town')
  })

  it('开着的窗口和跑步开关写进 localStorage，重进沉浸模式原样恢复', () => {
    cy.contains('.dock-button', '洞察').click()
    cy.get('.run-toggle').click()
    cy.window().its('localStorage').invoke('getItem', PREFS_KEY).should('contain', 'insights')

    cy.visitTown('/town/immersive')
    cy.get('[role="dialog"]').should('contain', '洞察')
    cy.get('.run-toggle').should('have.attr', 'aria-pressed', 'true')
  })

  it('引擎挂了也不影响 dock 和面板（画面与功能解耦）', () => {
    // 让图集 404，Phaser 起不来，但 HUD 必须照常可用
    cy.intercept('GET', '/assets/town/town-atlas.json', { statusCode: 404, body: {} })
    cy.visitTown('/town/immersive')
    cy.contains('.dock-button', '今天').click()
    cy.get('[role="dialog"]').should('contain', '今天')
  })
})
