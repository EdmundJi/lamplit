/**
 * 小镇 · 完整流程走一遍。
 *
 * 和旁边那几个 spec 的分工：town-model / town-hud / town-engine / immersive-shell 各自
 * 盯一个切面，粒度细、失败时定位准；这一条只做一件它们做不到的事——**按玩家真实的顺序
 * 从头走到尾**，把"每一步单独都对、连起来就坏"的问题挤出来（面板在请求还在飞的时候被
 * 最小化、进过学院回来镜头丢了跟随、走远之后动作菜单不再弹……都属于这一类）。
 *
 * 每一步都调 cy.record()。用例跑完会在 test-results/town-probe/ 下留一份 Markdown，
 * 里面是每一步的世界快照（玩家坐标、身边有谁、面板开着哪些、有没有报错）。
 * 文字快照用于定位，实际像素画面由 Playwright 视觉旅程的截图、录像与 trace 验证。
 *
 * 另外注意 cypress/support/e2e.ts：任何未捕获错误都会在 afterEach 结算并让用例变红，
 * 所以这条流程同时也是"整条路径上不许有静默异常"的守门人。
 */
describe('小镇 · 完整流程', () => {
  beforeEach(() => {
    cy.mockTown()
  })

  it('从街上走到学院再进沉浸模式，全程可控且无静默异常', () => {
    // ── 1. 进场：引擎起来，而且玩家是"可操作"的 ────────────────────────────────
    cy.visitTown()
    cy.waitForScene()
    cy.record('进入小镇')

    cy.townSnapshot().then(snap => {
      expect(snap.ready, '引擎就绪').to.be.true
      expect(snap.player, '玩家小人存在（selfWalker 不为 null）').to.not.be.null
      // 这一条是整个小镇最关键的健康指标：create() 只要在 setupInput() 之前抛错，
      // 画面看上去照常（居民和 NPC 都在走），但方向键彻底失效。
      expect(snap.player!.controllable, '方向键已接上（selfKeys 建好了）').to.be.true
      expect(snap.player!.onWalkable, '玩家脚下在可行走区域内').to.be.true
      expect(snap.world.walkableRects, '碰撞世界有可行走区').to.be.greaterThan(0)
      expect(snap.world.obstacleRects, '碰撞世界有障碍').to.be.greaterThan(0)
      expect(snap.actors.filter(a => a.kind === 'npc').length, 'NPC 已生成').to.be.greaterThan(0)
      expect(snap.failedRequests, '没有失败的请求').to.deep.equal([])
    })

    // ── 2. 走位：右走、左走，位移方向要对得上 ──────────────────────────────────
    cy.townWalk('right', 700).then(step => {
      expect(step.ok, step.reason).to.be.true
      expect(step.to!.x - step.from!.x, '按右键向右位移').to.be.greaterThan(20)
    })
    cy.record('按住右方向键 700ms')

    cy.townWalk('left', 700).then(step => {
      expect(step.to!.x - step.from!.x, '按左键向左位移').to.be.lessThan(-20)
    })
    cy.record('按住左方向键 700ms')

    // ── 3. 奔跑：同样时长要明显走得更远 ───────────────────────────────────────
    cy.townWalk('right', 600).then(walked => {
      cy.get('.run-toggle').click()
      cy.townSnapshot().its('world.runMode').should('be.true')
      cy.townWalk('right', 600).then(ran => {
        expect(ran.moved, `跑 ${ran.moved}px 应明显多于走 ${walked.moved}px`).to.be.greaterThan(walked.moved * 1.5)
      })
      cy.get('.run-toggle').click()
    })
    cy.record('开奔跑再走一段')

    // ── 4. 昼夜：HUD 的开关要真的传到引擎 ─────────────────────────────────────
    cy.contains('button', /切到夜晚/).click()
    cy.townSnapshot().its('world.night').should('be.true')
    cy.record('切到夜晚')
    cy.contains('button', /切到白天/).click()
    cy.townSnapshot().its('world.night').should('be.false')

    // ── 5. 找 NPC 说话 ───────────────────────────────────────────────────────
    cy.wait('@reflection')
    cy.contains('button', '找小助').click()
    cy.get('.town-panel').should('contain', '昨天你把最难的一章啃完了')
    cy.record('和小助对话')

    cy.get('.town-directory summary').click()
    cy.contains('.town-directory button', '邮递员').click()
    cy.get('.town-panel').should('contain', '2 封新信')
    cy.record('找邮递员看未读')

    // ── 6. 进学院再回来：换场景之后玩家必须仍然可控 ───────────────────────────
    cy.contains('button', '去学院').click()
    cy.get('.town-panel').should('contain', '成长学院')
    cy.record('进入成长学院')

    cy.contains('button', '回到小镇').click()
    cy.contains('button', '去学院').should('be.visible')
    cy.townSnapshot().then(snap => {
      expect(snap.player, '回到小镇后玩家还在').to.not.be.null
      expect(snap.player!.controllable, '回到小镇后方向键仍然有效').to.be.true
    })
    cy.townWalk('right', 500).then(step => {
      expect(step.moved, '从学院回来之后还能走动').to.be.greaterThan(10)
    })
    cy.record('回到小镇并再走一段')

    // ── 7. 沉浸模式：dock 默认收起，展开后开面板、最小化、恢复、Esc 退出 ───────
    cy.contains('button[title="进入沉浸模式"]', '沉浸模式').click()
    cy.location('pathname').should('eq', '/town/immersive')
    cy.get('canvas', { timeout: 30_000 }).should('exist')
    cy.record('进入沉浸模式')

    cy.townSnapshot().its('ui.dockCollapsed').should('be.true')
    cy.get('.immersive-dock-handle button').click()
    cy.get('.immersive-dock .dock-button').should('have.length', 9)
    cy.record('展开 dock')

    cy.contains('.dock-button', '目标').click()
    cy.get('[role="dialog"]').should('have.length', 1)
    cy.townSnapshot().its('ui.panelsOpen').should('deep.equal', ['goals'])
    cy.record('打开目标面板')

    cy.get('[role="dialog"] [aria-label="最小化"]').click()
    cy.townSnapshot().then(snap => {
      expect(snap.ui.panelsOpen, '最小化后不再是展开态').to.deep.equal([])
      expect(snap.ui.panelsMinimized, '进了托盘而不是被关掉').to.deep.equal(['goals'])
    })
    cy.record('最小化到托盘')

    cy.get('.immersive-minimized button').click()
    cy.townSnapshot().its('ui.panelsOpen').should('deep.equal', ['goals'])
    cy.record('从托盘恢复')

    cy.get('body').type('{esc}')
    cy.get('[role="dialog"]').should('not.exist')
    cy.get('body').type('{esc}')
    cy.location('pathname').should('eq', '/town')
    cy.record('Esc 退出沉浸模式')

    // ── 8. 收尾：整条路径上不许留下任何未捕获错误或失败请求 ────────────────────
    cy.townSnapshot().then(snap => {
      expect(snap.errors, `全程未捕获错误：\n${snap.errors.map(e => e.message).join('\n')}`).to.deep.equal([])
      expect(snap.failedRequests, '全程失败请求').to.deep.equal([])
    })
  })
})
