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

test('probe bubble width and avatar initial', async ({ browser }) => {
  const stamp = Date.now()
  const alice = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const bob = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const a = await alice.newPage()
  const b = await bob.newPage()
  await register(a, `px-a-${stamp}@example.test`, '杨旭')
  await register(b, `px-b-${stamp}@example.test`, '李四')
  await a.goto('/friends')
  await a.locator('.add-friend-band input').fill(`px-b-${stamp}@example.test`)
  await a.getByRole('button', { name: '发送申请' }).click()
  await expect(a.getByText('申请已发送给 李四')).toBeVisible()
  await b.goto('/friends')
  await b.locator('.incoming-card button.primary').click()
  await expect(b.getByText('已和 杨旭 成为好友')).toBeVisible()

  await a.goto('/friends')
  await a.locator('.chat-entry').click()
  await expect(a.locator('.chat-identity h1')).toHaveText('李四')
  await a.getByLabel('消息内容').fill('今天天气很好我们一起去散步吧')
  await a.getByRole('button', { name: '发送消息' }).click()
  await expect(a.locator('.msg-row.mine')).toHaveCount(1)

  await b.goto('/friends')
  await b.locator('.chat-entry').click()
  await expect(b.locator('.chat-identity h1')).toHaveText('杨旭')
  await b.getByLabel('消息内容').fill('好的好的')
  await b.getByRole('button', { name: '发送消息' }).click()
  await expect(b.locator('.msg-row.mine')).toHaveCount(1)
  await a.waitForTimeout(4500)

  const report = await a.evaluate(() => {
    const mineRow = document.querySelector('.msg-row.mine')!
    const theirRow = document.querySelector('.msg-row:not(.mine)')!
    const bubble = mineRow.querySelector('.msg-bubble')!
    const bubbleRect = bubble.getBoundingClientRect()
    const list = document.querySelector('.message-list')!.getBoundingClientRect()
    return {
      mineName: mineRow.querySelector('.msg-name')!.textContent,
      mineAvatar: mineRow.querySelector('.msg-avatar')!.textContent,
      mineBubbleWidth: Math.round(bubbleRect.width),
      listWidth: Math.round(list.width),
      bubbleText: bubble.textContent,
    }
  })
  console.log('PROBE', JSON.stringify(report))
  await alice.close()
  await bob.close()
})
