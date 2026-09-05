import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  chatTimeLabel,
  friendInitial,
  friendlyFriendError,
  memberSinceLabel,
  useChatThread,
  useConversations,
  useFriendDirectory,
  useFriendProfile,
  useGroupComposer,
} from './friends.logic'
import type { FriendItem } from './friends.types'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), delete: vi.fn(), patch: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))

const notify = vi.hoisted(() => vi.fn())
vi.mock('../../shared/data-sync', () => ({ notifyDataChanged: notify }))

const pendingIncoming: FriendItem = { publicId: 'user-a', displayName: '林晓', overallLevel: 2, memberSince: '2026-07-01', status: 'PENDING', direction: 'INCOMING', createdAt: '2026-08-01' }

describe('friendInitial / memberSinceLabel / chatTimeLabel', () => {
  it('takes the first Chinese character or uppercases a Latin one', () => {
    expect(friendInitial('林晓')).toBe('林')
    expect(friendInitial('anna')).toBe('A')
    expect(friendInitial('   ')).toBe('好')
  })

  it('formats a date as a Chinese long date', () => {
    expect(memberSinceLabel('2026-07-01')).toContain('2026')
  })

  it('formats chat time, empty when no value given', () => {
    expect(chatTimeLabel(undefined)).toBe('')
    expect(chatTimeLabel('2026-08-01T09:00:00Z')).toBeTruthy()
  })
})

describe('friendlyFriendError', () => {
  it('maps known error codes to Chinese copy, falls back otherwise', () => {
    expect(friendlyFriendError({ code: 'FRIENDS_ALREADY' }, 'x')).toBe('你们已经是好友了')
    expect(friendlyFriendError({ code: 'UNKNOWN' }, '兜底文案')).toBe('兜底文案')
  })
})

describe('useFriendDirectory: 好友申请状态流转', () => {
  beforeEach(() => {
    api.get.mockReset().mockResolvedValue({ friends: [], incoming: [], outgoing: [] })
    api.post.mockReset()
    api.delete.mockReset()
    notify.mockReset()
  })

  it('发送申请成功后刷新列表并通知全局同步', async () => {
    api.post.mockResolvedValueOnce({ publicId: 'user-b', displayName: '陈默', status: 'PENDING' })
    const directory = useFriendDirectory()
    await directory.load(true)
    const ok = await directory.sendRequest('chenmo@example.test')
    expect(ok).toBe(true)
    expect(directory.feedback.value).toContain('申请已发送给 陈默')
    expect(notify).toHaveBeenCalledWith(['social'])
  })

  it('对方已开启独自升级或邮箱不存在时给出可读提示', async () => {
    api.post.mockRejectedValueOnce({ code: 'FRIEND_USER_NOT_FOUND' })
    const directory = useFriendDirectory()
    const ok = await directory.sendRequest('missing@example.test')
    expect(ok).toBe(false)
    expect(directory.error.value).toContain('独自升级')
  })

  it('接受申请后该用户从 incoming 流转为好友', async () => {
    api.get.mockResolvedValueOnce({ friends: [], incoming: [pendingIncoming], outgoing: [] })
    const directory = useFriendDirectory()
    await directory.load(true)
    expect(directory.list.value.incoming).toHaveLength(1)

    api.post.mockResolvedValueOnce({ ...pendingIncoming, status: 'ACCEPTED' })
    api.get.mockResolvedValueOnce({ friends: [{ ...pendingIncoming, status: 'ACCEPTED' }], incoming: [], outgoing: [] })
    await directory.accept(pendingIncoming)
    expect(api.post).toHaveBeenCalledWith('/friends/user-a/accept')
    expect(directory.list.value.friends).toHaveLength(1)
    expect(directory.list.value.incoming).toHaveLength(0)
  })

  it('拒绝申请后该用户从 incoming 移除', async () => {
    api.get.mockResolvedValueOnce({ friends: [], incoming: [pendingIncoming], outgoing: [] })
    const directory = useFriendDirectory()
    await directory.load(true)

    api.post.mockResolvedValueOnce({})
    api.get.mockResolvedValueOnce({ friends: [], incoming: [], outgoing: [] })
    await directory.reject(pendingIncoming)
    expect(api.post).toHaveBeenCalledWith('/friends/user-a/reject')
    expect(directory.list.value.incoming).toHaveLength(0)
  })

  it('删除好友后从好友列表移除', async () => {
    const friend: FriendItem = { ...pendingIncoming, status: 'ACCEPTED', direction: 'OUTGOING' }
    api.get.mockResolvedValueOnce({ friends: [friend], incoming: [], outgoing: [] })
    const directory = useFriendDirectory()
    await directory.load(true)

    api.delete.mockResolvedValueOnce(undefined)
    api.get.mockResolvedValueOnce({ friends: [], incoming: [], outgoing: [] })
    await directory.remove(friend)
    expect(api.delete).toHaveBeenCalledWith('/friends/user-a')
    expect(directory.list.value.friends).toHaveLength(0)
    expect(directory.feedback.value).toContain('已删除好友')
  })
})

describe('useConversations: 会话未读计数', () => {
  beforeEach(() => {
    api.get.mockReset()
  })

  it('合并单聊与群聊，未读数按最新优先排序，并求和总未读', async () => {
    api.get.mockImplementation((path: string) => {
      if (path === '/friends/conversations') {
        return Promise.resolve([
          { peerPublicId: 'user-a', peerDisplayName: '林晓', peerLevel: 2, lastMessage: '在吗', lastMessageAt: '2026-08-01T09:00:00Z', lastMessageFromMe: false, unreadCount: 2 },
        ])
      }
      if (path === '/friends/groups') {
        return Promise.resolve([
          { publicId: 'group-1', name: '学习小组', lastMessage: '大家好', lastMessageAt: '2026-08-02T09:00:00Z', unreadCount: 3, memberCount: 3 },
        ])
      }
      return Promise.resolve([])
    })
    const conversations = useConversations()
    await conversations.load(true)
    expect(conversations.conversations.value[0].displayName).toBe('学习小组')
    expect(conversations.totalUnread.value).toBe(5)
  })

  it('加载失败时给出可读错误', async () => {
    api.get.mockRejectedValue(new Error('network'))
    const conversations = useConversations()
    await conversations.load(true)
    expect(conversations.error.value).toContain('暂时无法加载')
  })
})

describe('useGroupComposer: 群聊创建', () => {
  beforeEach(() => {
    api.get.mockReset()
    api.post.mockReset()
    notify.mockReset()
  })

  it('未命名或未选成员时给出提示，选好后创建成功', async () => {
    api.get.mockResolvedValueOnce({ friends: [{ publicId: 'user-a', displayName: '林晓' }] })
    const composer = useGroupComposer()
    await composer.open()
    expect(composer.friends.value).toHaveLength(1)

    let id = await composer.create()
    expect(id).toBeNull()
    expect(composer.error.value).toContain('群聊名称')

    composer.groupName.value = '学习小组'
    id = await composer.create()
    expect(id).toBeNull()
    expect(composer.error.value).toContain('至少选择一位好友')

    composer.toggleMember('user-a')
    api.post.mockResolvedValueOnce({ publicId: 'group-9' })
    id = await composer.create()
    expect(id).toBe('group-9')
    expect(notify).toHaveBeenCalledWith('social')
  })

  it('超过 10 人上限时给出可读提示', async () => {
    api.get.mockResolvedValueOnce({ friends: [] })
    const composer = useGroupComposer()
    await composer.open()
    composer.groupName.value = '大群'
    composer.toggleMember('user-a')
    api.post.mockRejectedValueOnce({ code: 'GROUP_MEMBER_LIMIT' })
    const id = await composer.create()
    expect(id).toBeNull()
    expect(composer.error.value).toContain('最多 10 人')
  })
})

describe('useChatThread: 单聊/群聊收发与草稿', () => {
  beforeEach(() => {
    api.get.mockReset()
    api.post.mockReset().mockResolvedValue({})
    notify.mockReset()
    window.localStorage?.clear()
  })

  it('打开单聊会话并标记已读', async () => {
    api.get.mockResolvedValueOnce([{ publicId: 'm1', body: '在吗', fromMe: false, createdAt: '2026-08-01T09:00:00Z', read: false }])
    const thread = useChatThread()
    await thread.open('single', 'user-a', '林晓')
    expect(thread.messages.value).toHaveLength(1)
    expect(api.post).toHaveBeenCalledWith('/friends/messages/read', { peerPublicId: 'user-a' })
  })

  it('发送失败时保留草稿，方便重发', async () => {
    api.get.mockResolvedValue([])
    const thread = useChatThread()
    await thread.open('single', 'user-a', '林晓')
    thread.draft.value = '你好'
    api.post.mockRejectedValueOnce({ code: 'INVALID_MESSAGE_BODY' })
    await thread.send()
    expect(thread.error.value).toContain('消息内容不能为空')
    expect(thread.draft.value).toBe('你好')

    api.post.mockResolvedValueOnce({})
    api.get.mockResolvedValueOnce([{ publicId: 'm2', body: '你好', fromMe: true, createdAt: '2026-08-01T09:01:00Z', read: true }])
    await thread.send()
    expect(thread.draft.value).toBe('')
    expect(notify).toHaveBeenCalledWith('social')
  })

  it('群聊消息通过群端点收发', async () => {
    api.get.mockResolvedValueOnce([])
    const thread = useChatThread()
    await thread.open('group', 'group-1', '学习小组')
    expect(api.post).toHaveBeenCalledWith('/friends/groups/group-1/read')

    thread.draft.value = '大家好'
    api.get.mockResolvedValueOnce([{ publicId: 'gm1', body: '大家好', fromMe: true, createdAt: '2026-08-01T09:00:00Z', senderName: '我' }])
    await thread.send()
    expect(api.post).toHaveBeenCalledWith('/friends/groups/group-1/messages', { body: '大家好' })
  })
})

describe('useFriendProfile', () => {
  it('计算今日完成数与宠物好感度进度', async () => {
    api.get.mockReset().mockResolvedValueOnce({
      publicId: 'user-a', displayName: '林晓', memberSince: '2026-06-01', overallLevel: 2, totalExperience: 10,
      overview: { effectiveActions: 1, fulfillmentRate: 0.5, recoveryCount: 0, totalExperience: 10 },
      longestStreak: 1, roles: [], pet: { speciesCode: 'CAT', speciesName: '猫', name: '团子', breed: '英短', furColor: '蓝', level: 1, affection: 15, nextLevelAffection: 30 },
      attributes: [], todayTasks: [{ publicId: 't1', title: 'x', status: 'DONE', roleName: '', plannedStartAt: '2026-08-01T09:00:00Z' }],
    })
    const profile = useFriendProfile()
    await profile.load('user-a')
    expect(profile.doneCount.value).toBe(1)
    expect(profile.petProgress.value).toBe(50)
    await flushPromises()
  })
})
