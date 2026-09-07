import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useTownSocialStore } from './social.store'
import { deferred, letterFixture } from './social.fixtures'
import type { TownLetterInbox } from './social.types'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../../../shared/api/client', () => ({ api }))

describe('town social store', () => {
  beforeEach(() => {
    api.get.mockReset().mockResolvedValue({ letters: [letterFixture()], unreadCount: 1 })
    api.post.mockReset().mockResolvedValue(null)
  })

  it('loads the backend envelope data and keeps confirmed state on failed refresh', async () => {
    const store = useTownSocialStore()
    await store.refresh()
    expect(api.get).toHaveBeenCalledWith('/town/letters')
    expect(store.unreadCount.value).toBe(1)
    api.get.mockRejectedValueOnce(new Error('private server text'))
    await store.refresh()
    expect(store.letters.value).toHaveLength(1)
    expect(store.loadError.value).toBe('暂时无法收取信件，请重试。')
    expect(store.loading.value).toBe(false)
  })

  it('marks read only after success, deduplicates clicks, and blocks stale snapshots', async () => {
    const store = useTownSocialStore()
    await store.refresh()
    const pending = deferred<void>()
    api.post.mockReturnValueOnce(pending.promise)
    const read = store.markRead('LONG')
    await store.markRead('LONG')
    await store.refresh()
    expect(api.get).toHaveBeenCalledTimes(1)
    expect(api.post).toHaveBeenCalledTimes(1)
    expect(store.unreadCount.value).toBe(1)
    pending.resolve()
    await read
    await store.markRead('LONG')
    expect(api.post).toHaveBeenCalledTimes(1)
    expect(store.unreadCount.value).toBe(0)
    expect(store.letters.value[0].readAt).toBeTruthy()
  })

  it('keeps failed reads unread and supports explicit retry with an encoded ID', async () => {
    api.get.mockResolvedValueOnce({ letters: [letterFixture('NOTE', { publicId: 'id/a' })], unreadCount: 1 })
    const store = useTownSocialStore()
    await store.refresh()
    api.post.mockRejectedValueOnce(new Error('private'))
    await store.markRead('id/a')
    expect(store.unreadCount.value).toBe(1)
    expect(store.letters.value[0].readAt).toBeNull()
    expect(store.readErrors.value['id/a']).toContain('请重试')
    await store.markRead('id/a')
    expect(api.post).toHaveBeenCalledWith('/town/letters/id%2Fa/read')
    expect(store.readErrors.value['id/a']).toBeUndefined()
    expect(store.unreadCount.value).toBe(0)
  })

  it('validates drafts, preserves failures, sends only message, and creates no instant reply', async () => {
    const store = useTownSocialStore()
    store.draft.value = '   '
    await store.send()
    store.draft.value = '字'.repeat(2001)
    await store.send()
    expect(api.post).not.toHaveBeenCalled()
    store.draft.value = '  私密心事  '
    api.post.mockRejectedValueOnce({ message: '私密心事' })
    await store.send()
    expect(store.draft.value).toBe('  私密心事  ')
    expect(store.sendError.value).not.toContain('私密心事')
    await store.send()
    expect(api.post).toHaveBeenLastCalledWith('/town/confidant', { message: '私密心事' })
    expect(store.draft.value).toBe('')
    expect(store.feedback.value).toContain('最早隔天')
    expect(store.letters.value).toEqual([])
    expect(api.get).not.toHaveBeenCalled()
  })

  it('accepts the 2000 character limit and prevents double sending', async () => {
    const store = useTownSocialStore()
    store.draft.value = '字'.repeat(2000)
    const pending = deferred<void>()
    api.post.mockReturnValueOnce(pending.promise)
    const send = store.send()
    await store.send()
    expect(api.post).toHaveBeenCalledTimes(1)
    expect(store.canSend.value).toBe(false)
    pending.resolve()
    await send
    expect(store.sending.value).toBe(false)
  })

  it('does not share drafts or revive private data after disposal', async () => {
    const store = useTownSocialStore()
    store.draft.value = '私密'
    expect(useTownSocialStore().draft.value).toBe('')
    const pending = deferred<TownLetterInbox>()
    api.get.mockReturnValueOnce(pending.promise)
    const load = store.refresh()
    await store.refresh()
    expect(api.get).toHaveBeenCalledTimes(1)
    store.dispose()
    pending.resolve({ letters: [letterFixture()], unreadCount: 1 })
    await load
    expect(store.draft.value).toBe('')
    expect(store.letters.value).toEqual([])
    expect(store.loaded.value).toBe(false)
  })
})
