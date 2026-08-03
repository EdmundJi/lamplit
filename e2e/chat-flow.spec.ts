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
}

test('friends exchange text and emoji messages with unread tracking', async ({ browser }, testInfo) => {
  const stamp = `${testInfo.project.name}-${Date.now()}`
  const aliceEmail = `chat-a-${stamp}@example.test`
  const bobEmail = `chat-b-${stamp}@example.test`

  const alice = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const bob = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const a = await alice.newPage()
  const b = await bob.newPage()
  const aErrors: string[] = []
  const bErrors: string[] = []
  a.on('pageerror', error => aErrors.push(error.message))
  b.on('pageerror', error => bErrors.push(error.message))

  await register(a, aliceEmail, '爱丽丝')
  await register(b, bobEmail, '鲍勃')

  await a.goto('/friends')
  await a.getByRole('button', { name: '关闭欢迎介绍' }).click().catch(() => {})
  await a.locator('.add-friend-band input').fill(bobEmail)
  await a.getByRole('button', { name: '发送申请' }).click()
  await expect(a.getByText('申请已发送给 鲍勃')).toBeVisible()

  await b.goto('/friends')
  await b.getByRole('button', { name: '关闭欢迎介绍' }).click().catch(() => {})
  await b.locator('.incoming-card button.primary').click()
  await expect(b.getByText('已和 爱丽丝 成为好友')).toBeVisible()

  await a.goto('/friends')
  await expect(a.locator('.chat-entry')).toHaveCount(1)
  await a.locator('.chat-entry').click()
  await expect(a).toHaveURL(/\/friends\/.*\/chat$/)
  await expect(a.locator('.chat-identity h1')).toHaveText('鲍勃')

  await a.getByLabel('消息内容').fill('今晚一起复习一章')
  await a.getByRole('button', { name: '发送消息' }).click()
  await expect(a.locator('.msg-row.mine .msg-bubble')).toContainText('今晚一起复习一章')

  await a.getByRole('button', { name: '选择表情' }).click()
  await expect(a.locator('.emoji-picker')).toBeVisible()
  await expect(a.locator('.emoji-item img')).toHaveCount(63)
  await a.locator('.emoji-item img[alt="🎉"]').click()
  await a.getByRole('button', { name: '发送消息' }).click()
  await expect(a.locator('.msg-row.mine .msg-bubble img[alt="🎉"]')).toBeVisible()

  await b.goto('/friends/chat')
  await expect(b.locator('.conversation-card')).toHaveCount(1)
  await expect(b.locator('.conversation-card')).toContainText('爱丽丝')
  await expect(b.locator('.unread-badge')).toHaveText('2')
  await b.locator('.conversation-card').click()
  await expect(b).toHaveURL(/\/friends\/.*\/chat$/)
  await expect(b.locator('.msg-row:not(.mine) .msg-bubble img[alt="🎉"]')).toBeVisible()
  await expect(b.locator('.msg-row:not(.mine)')).toHaveCount(2)
  await expect(b.locator('.msg-row:not(.mine) .msg-name').first()).toHaveText('爱丽丝')
  await expect(b.locator('.msg-row:not(.mine) .msg-time').first()).toBeVisible()

  await b.getByLabel('消息内容').fill('好呀 😊 八点见')
  await b.getByRole('button', { name: '发送消息' }).click()
  await expect(b.locator('.msg-row.mine .msg-bubble img[alt="😊"]')).toBeVisible()

  await a.waitForTimeout(4500)
  await expect(a.locator('.msg-row:not(.mine) .msg-bubble img[alt="😊"]')).toBeVisible()
  await expect(a.locator('.msg-row.mine')).toHaveCount(2)

  await b.goto('/friends/chat')
  await expect(b.locator('.unread-badge')).toHaveCount(0)

  expect(aErrors).toEqual([])
  expect(bErrors).toEqual([])
  await a.screenshot({ path: `../artifacts/${testInfo.project.name}-chat.png`, fullPage: true })
  await alice.close()
  await bob.close()
})
