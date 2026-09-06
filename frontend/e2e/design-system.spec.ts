import { expect, test, type Page } from '@playwright/test'

// Isolated visual fixtures: no writes or authentication against the real backend.
async function fixture(page: Page, options: { empty?: boolean; dark?: boolean; admin?: boolean } = {}) {
  await page.addInitScript(({ dark }) => {
    localStorage.setItem('better-self:welcome:design-user', 'dismissed')
    localStorage.setItem('better-self:appearance', JSON.stringify({ theme: dark ? 'dark' : 'light', accent: 'forest', motion: 'off', density: 'comfortable', radius: 'modern' }))
  }, { dark: options.dark })
  const now = new Date().toISOString()
  const date = now.slice(0, 10)
  const user = { publicId: 'design-user', displayName: '林间', email: 'design@example.test', timezone: 'Asia/Shanghai', role: options.admin ? 'ADMIN' : 'USER' }
  const dimensions = ['KNOWLEDGE', 'HEALTH', 'CAREER', 'RELATIONSHIP', 'WELLBEING'].map((code, i) => ({ publicId: `dimension-${i}`, code, name: ['知识', '健康', '职场', '关系', '心境'][i] }))
  const attributes = dimensions.map((dim, i) => ({ ...dim, dimensionName: dim.name, description: ['学习与理解', '照顾身体', '积累工作能力', '连接重要的人', '照顾自己的感受'][i], experience: 120 - i * 20, level: 2, radarScore: 64 - i * 8, currentLevelExperience: 100, nextLevelExperience: 200, experienceToNextLevel: 80 + i * 20 }))
  const goals = options.empty ? [] : [{ publicId: 'goal-1', title: '让阅读重新成为生活的一部分', description: '每天读几页，在四周内完成一本书，留下自己的思考。', dimensionPublicId: 'dimension-0', startDate: date, endDate: '2026-12-31', status: 'ACTIVE' }]
  const tasks = options.empty ? [] : [
    { publicId: 'schedule-1', taskTitle: '读十页书，留下一句喜欢的话', plannedStartAt: `${date}T09:00:00`, status: 'PLANNED', roleName: '学生', estimatedMinutes: 15 },
    { publicId: 'schedule-2', taskTitle: '去户外走一走', plannedStartAt: `${date}T17:00:00`, status: 'PLANNED', roleName: '健身用户', estimatedMinutes: 20 },
  ]
  const pet = { publicId: 'pet-1', speciesCode: 'CAT', speciesName: '猫', name: '小橘', breed: '中华田园猫', furColor: '橘白', level: 2, affection: 8, nextLevelAffection: 20, selected: true }
  const profile = { ...user, birthDate: '1996-06-08', age: 30, createdAt: now, overallLevel: 3, totalExperience: 235, effectiveActions: 18, longestStreak: 7, wallet: { coinBalance: 42, lifetimeCoins: 80 }, petCount: options.empty ? 0 : 1, selectedPet: options.empty ? null : pet, soloGrowth: false, equippedTitle: null }
  const friend = { publicId: 'friend-1', displayName: '山月', overallLevel: 3, memberSince: date, status: 'ACCEPTED', direction: 'OUTGOING', createdAt: now }
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url())
    const path = url.pathname.replace('/api/v1', '')
    let data: unknown = []
    if (path === '/me') data = user
    else if (path === '/me/profile') data = profile
    else if (path === '/task-schedules') data = tasks
    else if (/\/task-schedules\/[^/]+\/events$/.test(path)) {
      const body = route.request().postDataJSON()
      const task = tasks.find(item => path.includes(item.publicId))!
      task.status = ({ COMPLETED: 'DONE', STARTED: 'IN_PROGRESS', PARTIAL: 'PARTIAL', DEFERRED: 'DEFERRED' } as Record<string, string>)[body.eventType] || 'SKIPPED'
      data = { scheduleStatus: task.status, eventPublicId: 'event-1', roleExperienceDelta: 0, coinDelta: 0 }
    } else if (path.endsWith('/reverse')) { tasks[0].status = 'PLANNED'; data = {} }
    else if (path === '/daily-status') data = { energy: 'STEADY', availableMinutes: 30, advice: 'LIGHT', ...(route.request().method() === 'POST' ? route.request().postDataJSON() : {}) }
    else if (path === '/goals') data = goals
    else if (path === '/dimensions') data = dimensions
    else if (path === '/tasks') data = tasks.map(task => ({ ...task, title: task.taskTitle, goalPublicId: 'goal-1', notes: '', difficulty: 1, rrule: 'FREQ=DAILY', roleCode: 'STUDENT', activeFrom: date, activeUntil: '2026-12-31', plannedLocalTime: '09:00', active: true }))
    else if (path === '/task-presets') data = { roleCode: 'STUDENT', roleName: '学生', presets: [], remainingRefreshes: 3 }
    else if (path === '/insights/attributes') data = { totalExperience: 400, overallLevel: 3, attributes }
    else if (path === '/insights/overview') data = { effectiveActions: 8, fulfillmentRate: .8, recoveryCount: 2, totalExperience: 235, statusCheckCount: 0, statusAdvices: {} }
    else if (path === '/achievements') data = [{ code: 'FIRST_STEP', name: '第一步', body: '把想法变成行动', triggerText: '累计完成一次行动', iconKey: 'Footprints', tone: 'green', earned: true, earnedAt: now }]
    else if (path === '/partners/profile') data = { wallet: profile.wallet, pets: options.empty ? [] : [pet], selectedPet: profile.selectedPet, shopItems: [] }
    else if (path === '/friends') data = { friends: options.empty ? [] : [friend], incoming: [], outgoing: [] }
    else if (path === '/friends/unread-summary') data = { totalUnread: 0 }
    else if (path === '/friends/conversations') data = options.empty ? [] : [{ peerPublicId: friend.publicId, peerDisplayName: friend.displayName, peerLevel: 3, lastMessage: '今天读到了一句很喜欢的话，想分享给你。', lastMessageAt: now, lastMessageFromMe: false, unreadCount: 0 }]
    else if (path === '/friends/friend-1/summary') data = friend
    else if (path === '/friends/friend-1') data = { ...friend, totalExperience: 235, overview: { effectiveActions: 8, fulfillmentRate: .8, recoveryCount: 1, totalExperience: 235 }, longestStreak: 7, roles: [], pet, attributes, todayTasks: [] }
    else if (path === '/friends/messages') data = [{ publicId: 'message-1', body: '今天读到了一句很喜欢的话，想分享给你。', fromMe: false, createdAt: now, read: true }]
    else if (path === '/friends/groups/group-1') data = { publicId: 'group-1', name: '一起慢慢成长', members: [friend] }
    else if (path === '/me/preferences') data = { aiRetentionDays: 30 }
    else if (path === '/me/notifications') data = [{ channel: 'EMAIL', enabled: true, maxPerDay: 2 }]
    else if (path === '/privacy/deletion') data = null
    else if (path === '/town') data = { localDate: date, serverTime: now, unread: 0, soloGrowth: true, residents: [{ ...user, level: 3, totalExperience: 235, dominantDimension: 'KNOWLEDGE', longestStreak: 7, self: true, schedules: [] }] }
    else if (path === '/admin/metrics') data = {}
    await route.fulfill({ json: { data, requestId: 'design-fixture', timestamp: now } })
  })
}

test('all main pages render without overflow or runtime errors', async ({ page }, info) => {
  test.setTimeout(120_000)
  await fixture(page)
  const errors: string[] = []
  page.on('pageerror', error => errors.push(`${page.url()}: ${error.stack}`))
  for (const path of ['/today', '/goals', '/attributes', '/insights', '/ai', '/partners', '/friends', '/friends/chat', '/friends/friend-1', '/friends/friend-1/chat', '/friends/groups/group-1', '/profile', '/settings', '/onboarding', '/town']) {
    await page.goto(path)
    await expect(page.locator('h1').first()).toBeVisible()
    await page.waitForLoadState('networkidle')
    if (path === '/partners') await expect(page.locator('.partner-stage')).toBeVisible()
    if (path === '/town') await expect(page.locator('.town-canvas canvas')).toBeVisible()
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await expect(page.locator('.error[role="alert"]')).toHaveCount(0)
    await page.screenshot({ path: info.outputPath(`${path.replaceAll('/', '-')}.png`), fullPage: true })
  }
  expect(errors).toEqual([])
})

test('today actions, more menu, rhythm and reversal remain usable', async ({ page }) => {
  await fixture(page)
  await page.goto('/today')
  await expect(page.getByRole('button', { name: '专注这一步' })).toBeInViewport()
  await page.getByRole('button', { name: '调整今日节奏' }).click()
  await expect(page.getByRole('heading', { name: '今天用哪种节奏开始？' })).toBeVisible()
  await page.locator('.task-row').first().getByRole('button', { name: '完成', exact: true }).click()
  await expect(page.getByRole('button', { name: '撤销上次记录' })).toBeVisible()
  await page.getByRole('button', { name: '撤销上次记录' }).click()
  await expect(page.locator('.task-row').first()).toContainText('待开始')
  await page.locator('.task-row').first().locator('summary').click()
  await page.getByRole('button', { name: '部分完成', exact: true }).first().click()
  await expect(page.getByRole('dialog', { name: '记录部分完成' })).toBeVisible()
  await page.getByRole('button', { name: '取消', exact: true }).click()
})

test('dark theme and empty states', async ({ page }, info) => {
  await fixture(page, { empty: true, dark: true })
  for (const path of ['/today', '/goals', '/partners', '/settings', '/attributes']) {
    await page.goto(path)
    await expect(page.locator('h1').first()).toBeVisible()
    await page.waitForLoadState('networkidle')
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: info.outputPath(`dark-${path.slice(1)}.png`), fullPage: true })
  }
})

test('goal drawer and focus dialog support keyboard dismissal and focus return', async ({ page }, info) => {
  await fixture(page)
  await page.goto('/goals')
  await page.getByRole('button', { name: '新建目标', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '新建目标', exact: true })
  await expect(drawer).toBeVisible()
  await drawer.getByLabel('目标名称').fill('每天给自己留一点阅读时间')
  await page.screenshot({ path: info.outputPath('goal-drawer.png'), fullPage: true })
  await page.keyboard.press('Escape')
  await expect(drawer).toHaveCount(0)
  await expect(page.getByRole('button', { name: '新建目标', exact: true })).toBeFocused()
  await page.goto('/today')
  await page.getByRole('button', { name: '专注这一步' }).click()
  await expect(page.locator('.focus-panel')).toBeVisible()
  await page.keyboard.press('Shift+Tab')
  await expect(page.locator('.focus-panel button').last()).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('button', { name: '专注这一步' })).toBeFocused()
})

test('small and tablet viewports keep navigation and content accessible', async ({ page }) => {
  await fixture(page)
  for (const width of [320, 760, 1024]) {
    await page.setViewportSize({ width, height: 844 })
    for (const path of ['/today', '/goals', '/attributes', '/ai', '/settings']) {
      await page.goto(path)
      await page.waitForLoadState('networkidle')
      await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    }
  }
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/today')
  await page.getByRole('button', { name: '我的', exact: true }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.locator('.mobile-more-links').getByRole('link', { name: '洞察', exact: true }).click()
  await expect(page).toHaveURL(/\/insights$/)
  await expect(page.locator('.mobile-more-sheet')).toHaveCount(0)
})

test('authentication and admin share the visual system', async ({ page }, info) => {
  await page.route('**/api/v1/**', route => route.fulfill({ status: 401, json: { data: { code: 'UNAUTHENTICATED' } } }))
  await page.goto('/auth')
  await expect(page.getByRole('heading', { name: '更好的自己', exact: true })).toBeVisible()
  await page.getByLabel('邮箱').focus()
  await expect(page.getByLabel('邮箱')).toBeFocused()
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: info.outputPath('auth.png'), fullPage: true })
  await page.unrouteAll()
  await fixture(page, { admin: true })
  await page.goto('/admin')
  await expect(page.getByRole('heading', { name: '治理工作台' })).toBeVisible()
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: info.outputPath('admin.png'), fullPage: true })
})
