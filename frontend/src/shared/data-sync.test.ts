import { afterEach, describe, expect, it, vi } from 'vitest'
import { notifyDataChanged, onDataChanged } from './data-sync'

describe('data synchronization events', () => {
  afterEach(() => vi.restoreAllMocks())

  it('notifies handlers only for matching areas', () => {
    const handler = vi.fn()
    const stop = onDataChanged(['tasks', 'today'], handler)

    notifyDataChanged('profile')
    expect(handler).not.toHaveBeenCalled()

    notifyDataChanged(['tasks', 'profile'])
    expect(handler).toHaveBeenCalledOnce()
    expect(handler).toHaveBeenCalledWith(['tasks', 'profile'])

    stop()
    notifyDataChanged('tasks')
    expect(handler).toHaveBeenCalledOnce()
  })

  it('treats all as a wildcard and removes duplicate areas', () => {
    const handler = vi.fn()
    const stop = onDataChanged('all', handler)

    notifyDataChanged(['tasks', 'tasks', 'all'])

    expect(handler).toHaveBeenCalledWith(['tasks', 'all'])
    stop()
  })
})
