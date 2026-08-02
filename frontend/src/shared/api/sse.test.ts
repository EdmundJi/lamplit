import { describe, expect, it, vi } from 'vitest'
import { parseSseBlock, postSse } from './sse'

describe('SSE client', () => {
  it('accepts named events and ignores unknown events', () => {
    expect(parseSseBlock('event:delta\r\ndata:{"text":"a"}')).toEqual({ name: 'delta', data: { text: 'a' } })
    expect(parseSseBlock('event:message\ndata:unsafe')).toBeNull()
  })

  it('preserves streamed event ordering and forwards abort signals', async () => {
    const stream = new ReadableStream({ start(controller) { controller.enqueue(new TextEncoder().encode('event:meta\ndata:{}\n\nevent:delta\ndata:{"text":"a"}\n\nevent:done\ndata:{}\n\n')); controller.close() } })
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(stream, { status: 200 }))
    const controller = new AbortController(); const names: string[] = []
    await postSse('/ai', {}, event => names.push(event.name), controller.signal)
    expect(names).toEqual(['meta', 'delta', 'done'])
    expect(fetchMock.mock.calls[0][1]?.signal).toBe(controller.signal)
  })

  it('refreshes once on an expired access cookie before the stream starts', async () => {
    const stream = new ReadableStream({ start(controller) { controller.enqueue(new TextEncoder().encode('event:done\ndata:{}\n\n')); controller.close() } })
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response('{}', { status: 401 }))
      .mockResolvedValueOnce(new Response('{}', { status: 200 }))
      .mockResolvedValueOnce(new Response(stream, { status: 200 }))

    await postSse('/ai', {}, () => undefined)

    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(fetchMock.mock.calls[1][0]).toBe('/api/v1/auth/refresh')
    expect(fetchMock.mock.calls[2][0]).toBe('/api/v1/ai')
  })
})
