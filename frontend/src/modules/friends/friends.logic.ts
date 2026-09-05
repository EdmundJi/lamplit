/**
 * 好友与消息领域的业务逻辑：从各个页面组件里抽出来，供整页和沉浸式面板共用。
 * 这里只处理数据与状态，不关心具体渲染——页面和面板各自决定怎么展示。
 */
import { computed, ref } from 'vue'
import { api, type ApiError } from '../../shared/api/client'
import { notifyDataChanged } from '../../shared/data-sync'
import type {
  ChatMessage,
  Conversation,
  FriendItem,
  FriendList,
  FriendProfile,
  GroupConversation,
  GroupMember,
  GroupMessage,
} from './friends.types'

/* ---------------------------- 通用格式化 ---------------------------- */

/** 头像里显示的首字母：中文取第一个字，其它取大写字母。 */
export function friendInitial(name: string) {
  const characters = Array.from(name.trim())
  if (!characters.length) return '好'
  if (/\p{Script=Han}/u.test(characters[0])) return characters[0]
  return characters[0].toUpperCase()
}

/** "2026年8月3日加入"这样的本地化日期。 */
export function memberSinceLabel(date: string) {
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' }).format(new Date(`${date}T00:00:00`))
}

/** 同一天的消息只显示时间，跨天再带上月日。 */
export function chatTimeLabel(value?: string | null) {
  if (!value) return ''
  const date = new Date(value)
  const now = new Date()
  const sameDay = date.getFullYear() === now.getFullYear() && date.getMonth() === now.getMonth() && date.getDate() === now.getDate()
  return new Intl.DateTimeFormat('zh-CN', sameDay
    ? { hour: '2-digit', minute: '2-digit' }
    : { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(date)
}

/* ---------------------------- 好友列表与申请 ---------------------------- */

const FRIEND_REQUEST_ERRORS: Record<string, string> = {
  FRIEND_USER_NOT_FOUND: '没有找到该邮箱对应的用户，也可能对方开启了「独自升级」暂不可被添加',
  FRIEND_SELF_REQUEST: '不能添加自己为好友',
  FRIEND_REQUEST_EXISTS: '好友申请已发送，等待对方接受',
  FRIENDS_ALREADY: '你们已经是好友了',
  FRIEND_REQUEST_NOT_FOUND: '没有找到该好友申请',
  FRIENDSHIP_NOT_FOUND: '该好友关系不存在',
}

export function friendlyFriendError(err: unknown, fallback: string) {
  const code = (err as Partial<ApiError> | null)?.code
  return FRIEND_REQUEST_ERRORS[code ?? ''] ?? fallback
}

/** 好友列表 + 收发申请：新增/接受/拒绝/取消/删除，供 FriendsView 和 FriendsPanel 共用。 */
export function useFriendDirectory() {
  const list = ref<FriendList>({ friends: [], incoming: [], outgoing: [] })
  const loading = ref(true)
  const busy = ref(false)
  const error = ref('')
  const feedback = ref('')

  async function load(showLoading = true) {
    if (showLoading) loading.value = true
    error.value = ''
    try {
      list.value = await api.get<FriendList>('/friends')
    } catch {
      error.value = '好友列表暂时无法加载'
    } finally {
      if (showLoading) loading.value = false
    }
  }

  /** 通过注册邮箱发送好友申请；返回是否成功，便于调用方决定是否清空表单。 */
  async function sendRequest(email: string) {
    const address = email.trim()
    if (!address) {
      error.value = '请输入好友的注册邮箱'
      return false
    }
    busy.value = true
    error.value = ''
    try {
      const item = await api.post<FriendItem>('/friends/requests', { email: address })
      feedback.value = item.status === 'ACCEPTED'
        ? `已和 ${item.displayName} 成为好友`
        : `申请已发送给 ${item.displayName}，等待对方接受`
      await load(false)
      notifyDataChanged(['social'])
      return true
    } catch (err) {
      error.value = friendlyFriendError(err, '申请发送失败，请稍后重试')
      return false
    } finally {
      busy.value = false
    }
  }

  async function accept(item: FriendItem) {
    busy.value = true
    error.value = ''
    try {
      await api.post<FriendItem>(`/friends/${item.publicId}/accept`)
      feedback.value = `已和 ${item.displayName} 成为好友`
      await load(false)
      notifyDataChanged(['social'])
    } catch (err) {
      error.value = friendlyFriendError(err, '接受失败，请稍后重试')
    } finally {
      busy.value = false
    }
  }

  async function reject(item: FriendItem) {
    busy.value = true
    error.value = ''
    try {
      await api.post(`/friends/${item.publicId}/reject`)
      feedback.value = `已拒绝 ${item.displayName} 的申请`
      await load(false)
      notifyDataChanged(['social'])
    } catch (err) {
      error.value = friendlyFriendError(err, '操作失败，请稍后重试')
    } finally {
      busy.value = false
    }
  }

  async function remove(item: FriendItem) {
    busy.value = true
    error.value = ''
    try {
      await api.delete(`/friends/${item.publicId}`)
      feedback.value = `已删除好友 ${item.displayName}`
      await load(false)
      notifyDataChanged(['social'])
    } catch (err) {
      error.value = friendlyFriendError(err, '删除失败，请稍后重试')
    } finally {
      busy.value = false
    }
  }

  return { list, loading, busy, error, feedback, load, sendRequest, accept, reject, remove }
}

/* ---------------------------- 会话列表（单聊 + 群聊） ---------------------------- */

export type ConversationRow = {
  kind: 'single' | 'group'
  peerPublicId: string
  displayName: string
  level: number | null
  lastMessage: string
  lastMessageAt: string
  lastMessageFromMe: boolean | null
  unreadCount: number
  memberCount: number | null
}

export function useConversations() {
  const conversations = ref<ConversationRow[]>([])
  const loading = ref(true)
  const error = ref('')

  async function load(showLoading = true) {
    if (showLoading) loading.value = true
    error.value = ''
    try {
      const [single, groups] = await Promise.all([
        api.get<Conversation[]>('/friends/conversations'),
        api.get<GroupConversation[]>('/friends/groups'),
      ])
      const rows: ConversationRow[] = [
        ...single.map(item => ({ kind: 'single' as const, peerPublicId: item.peerPublicId, displayName: item.peerDisplayName, level: item.peerLevel, lastMessage: item.lastMessage, lastMessageAt: item.lastMessageAt, lastMessageFromMe: item.lastMessageFromMe, unreadCount: item.unreadCount, memberCount: null })),
        ...groups.map(item => ({ kind: 'group' as const, peerPublicId: item.publicId, displayName: item.name, level: null, lastMessage: item.lastMessage ?? '', lastMessageAt: item.lastMessageAt ?? '', lastMessageFromMe: null, unreadCount: item.unreadCount, memberCount: item.memberCount })),
      ]
      rows.sort((a, b) => new Date(b.lastMessageAt).getTime() - new Date(a.lastMessageAt).getTime())
      conversations.value = rows
    } catch {
      error.value = '会话暂时无法加载'
    } finally {
      if (showLoading) loading.value = false
    }
  }

  const totalUnread = computed(() => conversations.value.reduce((sum, item) => sum + item.unreadCount, 0))

  return { conversations, loading, error, load, totalUnread }
}

/** 群聊创建：选好友、命名、提交，供消息列表页和面板共用。 */
export function useGroupComposer() {
  const creating = ref(false)
  const friends = ref<FriendItem[]>([])
  const groupName = ref('')
  const selected = ref<Set<string>>(new Set())
  const busy = ref(false)
  const error = ref('')
  const feedback = ref('')

  async function open() {
    creating.value = true
    feedback.value = ''
    error.value = ''
    groupName.value = ''
    selected.value = new Set()
    try {
      const list = await api.get<{ friends: FriendItem[] }>('/friends')
      friends.value = list.friends
    } catch {
      error.value = '好友列表暂时无法加载'
      creating.value = false
    }
  }

  function close() {
    creating.value = false
  }

  function toggleMember(publicId: string) {
    const next = new Set(selected.value)
    if (next.has(publicId)) {
      next.delete(publicId)
    } else {
      if (next.size >= 9) return
      next.add(publicId)
    }
    selected.value = next
  }

  /** 创建成功返回新群的 publicId，失败返回 null（错误信息写进 error）。 */
  async function create(): Promise<string | null> {
    const name = groupName.value.trim()
    if (!name) {
      error.value = '请填写群聊名称'
      return null
    }
    if (!selected.value.size) {
      error.value = '至少选择一位好友'
      return null
    }
    busy.value = true
    error.value = ''
    try {
      const group = await api.post<{ publicId: string }>('/friends/groups', {
        name,
        memberPublicIds: Array.from(selected.value),
      })
      feedback.value = '群聊已创建'
      creating.value = false
      notifyDataChanged('social')
      return group.publicId
    } catch (err) {
      error.value = (err as Partial<ApiError> | null)?.code === 'GROUP_MEMBER_LIMIT'
        ? '群聊最多 10 人（含自己）'
        : '创建失败，请稍后重试'
      return null
    } finally {
      busy.value = false
    }
  }

  return { creating, friends, groupName, selected, busy, error, feedback, open, close, toggleMember, create }
}

/* ---------------------------- 单聊 / 群聊消息 ---------------------------- */

export type ChatKind = 'single' | 'group'
export type ChatEntry = { publicId: string; body: string; fromMe: boolean; createdAt: string; senderName?: string }

function draftKey(kind: ChatKind, id: string) {
  return `better-self:chat-draft:${kind}:${id}`
}

function readDraft(kind: ChatKind, id: string) {
  try {
    return window.localStorage?.getItem(draftKey(kind, id)) ?? ''
  } catch {
    return ''
  }
}

function writeDraft(kind: ChatKind, id: string, value: string) {
  try {
    if (value) window.localStorage?.setItem(draftKey(kind, id), value)
    else window.localStorage?.removeItem(draftKey(kind, id))
  } catch {
    // 隐私模式等场景下没有 localStorage，草稿只在当前会话里保留。
  }
}

/**
 * 单聊与群聊共用的收发逻辑：一个实例通过 open() 切换到不同会话，
 * 面板里"点开一个会话就聊起来"和整页里"打开固定会话"都靠它驱动。
 */
export function useChatThread() {
  const kind = ref<ChatKind>('single')
  const peerId = ref('')
  const peerName = ref('')
  const messages = ref<ChatEntry[]>([])
  const loading = ref(false)
  const error = ref('')
  const busy = ref(false)
  const draft = ref('')
  let pollTimer: ReturnType<typeof setInterval> | undefined

  async function fetchMessages(): Promise<ChatEntry[]> {
    if (kind.value === 'single') {
      const rows = await api.get<ChatMessage[]>(`/friends/messages?peerPublicId=${encodeURIComponent(peerId.value)}`)
      return rows.map(row => ({ publicId: row.publicId, body: row.body, fromMe: row.fromMe, createdAt: row.createdAt }))
    }
    const rows = await api.get<GroupMessage[]>(`/friends/groups/${encodeURIComponent(peerId.value)}/messages`)
    return rows.map(row => ({ publicId: row.publicId, body: row.body, fromMe: row.fromMe, createdAt: row.createdAt, senderName: row.senderName }))
  }

  async function markRead() {
    if (kind.value === 'single') await api.post('/friends/messages/read', { peerPublicId: peerId.value }).catch(() => undefined)
    else await api.post(`/friends/groups/${encodeURIComponent(peerId.value)}/read`).catch(() => undefined)
  }

  async function load() {
    loading.value = true
    error.value = ''
    try {
      messages.value = await fetchMessages()
      await markRead()
    } catch (err) {
      const code = (err as Partial<ApiError> | null)?.code
      error.value = code === 'FRIENDSHIP_NOT_FOUND'
        ? '只能和好友聊天，或对方已删除好友关系'
        : code === 'GROUP_NOT_FOUND'
          ? '群聊不存在或你不在群内'
          : '消息暂时无法加载'
    } finally {
      loading.value = false
    }
  }

  function stopPoll() {
    clearInterval(pollTimer)
    pollTimer = undefined
  }

  function startPoll(intervalMs = 4000) {
    stopPoll()
    pollTimer = setInterval(async () => {
      if (document.hidden) return
      try {
        const incoming = await fetchMessages()
        if (incoming.length > messages.value.length) {
          messages.value = incoming
          await markRead()
        }
      } catch {
        // 轮询静默失败，等待下一次
      }
    }, intervalMs)
  }

  /** 切换到一个会话（单聊对方 id 或群聊 id），并恢复该会话未发出的草稿。 */
  async function open(nextKind: ChatKind, id: string, name: string) {
    stopPoll()
    kind.value = nextKind
    peerId.value = id
    peerName.value = name
    draft.value = readDraft(nextKind, id)
    await load()
  }

  /** 发送失败时草稿会保留在输入框里，方便直接重新点击发送。 */
  async function send() {
    const body = draft.value.trim()
    if (!body || busy.value) return
    busy.value = true
    error.value = ''
    try {
      if (kind.value === 'single') {
        await api.post('/friends/messages', { peerPublicId: peerId.value, body })
      } else {
        await api.post(`/friends/groups/${encodeURIComponent(peerId.value)}/messages`, { body })
      }
      draft.value = ''
      writeDraft(kind.value, peerId.value, '')
      messages.value = await fetchMessages()
      notifyDataChanged('social')
    } catch (err) {
      error.value = (err as Partial<ApiError> | null)?.code === 'INVALID_MESSAGE_BODY'
        ? '消息内容不能为空或超过 1000 个字符'
        : '发送失败，请稍后重试，消息内容已保留'
    } finally {
      busy.value = false
    }
  }

  function saveDraft() {
    if (peerId.value) writeDraft(kind.value, peerId.value, draft.value)
  }

  function insertEmoji(char: string) {
    draft.value += char
    saveDraft()
  }

  return { kind, peerId, peerName, messages, loading, error, busy, draft, open, load, startPoll, stopPoll, send, saveDraft, insertEmoji }
}

/* ---------------------------- 群聊成员 ---------------------------- */

export function useGroupInfo() {
  const groupName = ref('')
  const members = ref<GroupMember[]>([])
  const loading = ref(false)
  const error = ref('')

  async function load(groupPublicId: string) {
    loading.value = true
    error.value = ''
    try {
      const group = await api.get<{ publicId: string; name: string; members: GroupMember[] }>(`/friends/groups/${encodeURIComponent(groupPublicId)}`)
      groupName.value = group.name
      members.value = group.members
    } catch (err) {
      error.value = (err as Partial<ApiError> | null)?.code === 'GROUP_NOT_FOUND'
        ? '群聊不存在或你不在群内'
        : '群信息暂时无法加载'
    } finally {
      loading.value = false
    }
  }

  return { groupName, members, loading, error, load }
}

/* ---------------------------- 好友详情 ---------------------------- */

export function useFriendProfile() {
  const profile = ref<FriendProfile | null>(null)
  const loading = ref(true)
  const error = ref('')

  async function load(publicId: string) {
    loading.value = true
    error.value = ''
    try {
      profile.value = await api.get<FriendProfile>(`/friends/${publicId}`)
    } catch {
      error.value = '只能查看好友的资料，或者对方已删除好友关系'
    } finally {
      loading.value = false
    }
  }

  const doneCount = computed(() => profile.value?.todayTasks.filter(task => task.status === 'DONE').length ?? 0)
  const petProgress = computed(() => {
    const pet = profile.value?.pet
    if (!pet) return 0
    return Math.min(100, Math.round(pet.affection * 100 / Math.max(1, pet.nextLevelAffection)))
  })

  return { profile, loading, error, load, doneCount, petProgress }
}
