import { expect, test } from '@playwright/test'

async function register(page: any, email: string, name: string) {
  await page.goto('/auth')
  await page.getByRole('button', { name: '注册' }).click()
  await page.getByLabel('邮箱').fill(email)
  await page.getByLabel('密码').fill('Correct-Horse-Battery-2026!')
  await page.getByLabel('称呼').fill(name)
  await page.getByLabel('出生日期').fill('1990-01-01')
  await page.getByLabel('我同意服务条款').check()
  await page.getByLabel('我同意隐私政策').check()
  await page.getByLabel('我了解 AI 使用说明').check()
  await page.getByRole('button', { name: '创建账户' }).click()
  await expect(page).toHaveURL(/\/onboarding$/)
  await page.goto('/today')
  await page.getByRole('button', { name: '关闭欢迎介绍' }).click().catch(() => {})
}

async function csrfOf(page: any) {
  return page.evaluate(() => {
    const match = document.cookie.split(';').map(v => v.trim()).find(v => v.startsWith('csrf_token='))
    return match ? decodeURIComponent(match.slice('csrf_token='.length)) : ''
  })
}

async function myPublicId(page: any) {
  const response = await page.request.get('/api/v1/me')
  return (await response.json()).data.publicId
}

async function acceptRequest(page: any, peerPublicId: string) {
  const csrf = await csrfOf(page)
  const response = await page.request.post(`/api/v1/friends/${peerPublicId}/accept`, {
    headers: { 'X-CSRF-Token': csrf },
  })
  expect(response.status()).toBe(200)
}

test('group chat with two columns and global unread bar navigation', { timeout: 120_000 }, async ({ browser }, testInfo) => {
  const stamp = `${testInfo.project.name}-${Date.now()}`
  const emails = {
    alice: `grp-a-${stamp}@example.test`,
    bob: `grp-b-${stamp}@example.test`,
    carol: `grp-c-${stamp}@example.test`,
  }

  const viewport = { width: 1440, height: 900 }
  const disableDesktopPet = async (context: any) => {
    await context.addInitScript(() => {
      ;(window as any).betterSelfDesktop = { isDesktopApp: true }
    })
  }
  const alice = await browser.newContext({ viewport })
  await disableDesktopPet(alice)
  const bob = await browser.newContext({ viewport })
  await disableDesktopPet(bob)
  const carol = await browser.newContext({ viewport })
  await disableDesktopPet(carol)
  const a = await alice.newPage()
  const b = await bob.newPage()
  const c = await carol.newPage()
  const errors: Record<string, string[]> = { a: [], b: [], c: [] }
  a.on('pageerror', error => errors.a.push(error.message))
  b.on('pageerror', error => errors.b.push(error.message))
  c.on('pageerror', error => errors.c.push(error.message))

  await register(a, emails.alice, '爱丽丝')
  await register(b, emails.bob, '鲍勃')
  await register(c, emails.carol, '卡罗')

  await a.goto('/friends')
  await a.locator('.add-friend-band input').fill(emails.bob)
  await a.getByRole('button', { name: '发送申请' }).click()
  await expect(a.getByText('申请已发送给 鲍勃')).toBeVisible()
  await a.locator('.add-friend-band input').fill(emails.carol)
  await a.getByRole('button', { name: '发送申请' }).click()
  await expect(a.getByText('申请已发送给 卡罗')).toBeVisible()

  const bobPublicId = await myPublicId(b)
  const carolPublicId = await myPublicId(c)
  await acceptRequest(b, (await myPublicId(a)))
  await acceptRequest(c, (await myPublicId(a)))
  await expect(bobPublicId).toBeTruthy()
  await expect(carolPublicId).toBeTruthy()

  await a.goto('/friends/chat')
  await a.getByRole('button', { name: '发起群聊' }).click()
  await expect(a.locator('.member-option')).toHaveCount(2)
  await a.locator('.member-option').nth(0).getByRole('checkbox').check()
  await a.locator('.member-option').nth(1).getByRole('checkbox').check()
  await a.getByLabel('群聊名称').fill('周末学习小组')
  await a.getByRole('button', { name: /创建群聊/ }).click()
  await expect(a).toHaveURL(/\/friends\/groups\//)
  await expect(a.locator('.chat-identity h1')).toHaveText('周末学习小组')
  await expect(a.locator('.chat-identity .eyebrow')).toContainText('3 人')

  await a.getByLabel('消息内容').fill('大家好 👋 今晚八点')
  await a.getByRole('button', { name: '发送消息' }).click()
  await expect(a.locator('.msg-row.mine .msg-bubble')).toHaveCount(1)
  await expect(a.locator('.msg-row.mine .msg-bubble img[alt="👋"]')).toBeVisible()
  await expect(a.locator('.msg-row:not(.mine)')).toHaveCount(0)

  await b.goto('/friends')
  await expect(b.locator('.global-unread-bar')).toBeVisible({ timeout: 25000 })
  await expect(b.locator('.global-unread-bar')).toContainText('你有 1 条新消息')
  await expect(b.locator('.global-unread-bar')).toContainText('周末学习小组')
  await b.locator('.global-unread-bar').click()
  await expect(b).toHaveURL(/\/friends\/groups\//)
  await expect(b.locator('.chat-identity h1')).toHaveText('周末学习小组')
  await expect(b.locator('.msg-row:not(.mine)')).toHaveCount(1)
  await expect(b.locator('.msg-row:not(.mine)')).toContainText('爱丽丝')
  await expect(b.locator('.msg-row:not(.mine) .msg-bubble img[alt="👋"]')).toBeVisible()
  await expect(b.locator('.msg-row:not(.mine) .msg-time').first()).toBeVisible()

  await b.getByLabel('消息内容').fill('来了来了 😊')
  await b.getByRole('button', { name: '发送消息' }).click()
  await expect(b.locator('.msg-row.mine .msg-bubble')).toHaveCount(1)

  await a.waitForTimeout(4500)
  await expect(a.locator('.msg-row:not(.mine) .msg-bubble img[alt="😊"]')).toBeVisible()

  await b.goto('/friends/chat')
  await expect(b.locator('.conversation-card').first()).toContainText('周末学习小组')
  await b.goto('/friends')
  await b.waitForTimeout(7000)
  await expect(b.locator('.global-unread-bar')).toHaveCount(0)

  await a.screenshot({ path: `../artifacts/${testInfo.project.name}-group-chat.png`, fullPage: false })
  for (const key of ['a', 'b', 'c']) {
    expect(errors[key], `${key} page errors`).toEqual([])
  }
  await alice.close()
  await bob.close()
  await carol.close()
})
