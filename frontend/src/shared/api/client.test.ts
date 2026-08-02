import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './client'

describe('API client', () => {
  beforeEach(() => { document.cookie = 'csrf_token=test-csrf'; vi.restoreAllMocks() })

  it('sends cookies and the readable CSRF cookie on writes', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ data: { ok: true }, requestId: 'r', timestamp: 't' }), { status: 200 }))
    await api.post('/goals', { title: '四周复习' })
    const [, init] = fetchMock.mock.calls[0]
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/goals')
    expect(init?.credentials).toBe('include')
    expect(new Headers(init?.headers).get('X-CSRF-Token')).toBe('test-csrf')
  })

  it('refreshes once after an unauthorized response', async () => {
    const ok = () => new Response(JSON.stringify({ data: { ok: true }, requestId: 'r', timestamp: 't' }), { status: 200 })
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response('{}', { status: 401 }))
      .mockResolvedValueOnce(ok())
      .mockResolvedValueOnce(ok())
    await api.get('/me')
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(fetchMock.mock.calls[1][0]).toBe('/api/v1/auth/refresh')
  })
})
