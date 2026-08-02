import { expect, test } from '@playwright/test'

test('anonymous users cannot access owned resources', async ({ request }) => {
  for (const path of ['/api/v1/goals/00000000000000000000000000', '/api/v1/ai/sessions/00000000000000000000000000/messages', '/api/v1/privacy/exports/00000000000000000000000000']) {
    const response = await request.get(path)
    expect(response.status()).toBe(401)
  }
})
