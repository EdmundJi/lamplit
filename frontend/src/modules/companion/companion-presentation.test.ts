import { describe, expect, it } from 'vitest'
import { conversationEmoji, residentStatus } from './companion-presentation'
describe('quiet resident status', () => {
  it.each([
    ['focus', '继续专注', '📖'], ['sleep', '睡着了', '💤'], ['garden', '照料花草', '🌱'],
    ['create', '继续画海报', '🎨'], ['help', '帮邻居摆桌子', '🤝'], ['coffee', '整理咖啡馆', '☕'],
    ['tend', '回到吧台，照应一下柜台前的人', '☕'], ['wait', '这里现在坐满了，先在旁边等一等', '⌛'],
  ])('uses a small status for %s', (activity, action, emoji) => expect(residentStatus(activity, action).emoji).toBe(emoji))
  it('shows the real speaker and travel animation before the underlying scheduled activity', () => {
    expect(residentStatus('read', '读书', false, true)).toEqual({ emoji: '💬', shortAction: '正在聊天' })
    expect(residentStatus('read', '读书', true).emoji).toBe('👣')
  })
  it('uses the actual action instead of mistaking a project title for the activity', () => {
    expect(residentStatus('help', '准备留一盏灯的读书小聚').shortAction).toBe('和邻居忙一会儿')
    expect(residentStatus('home', '回家休息').shortAction).toBe('在家歇一会儿')
  })
  it('never presents cafe tending as gardening because of the shared English verb', () => {
    expect(residentStatus('tend', '回到吧台，照应一下柜台前的人')).toEqual({ emoji: '☕', shortAction: '在吧台忙着' })
    expect(residentStatus('stand', '回到吧台，照应一下柜台前的人')).toEqual({ emoji: '☕', shortAction: '在吧台忙着' })
  })
  it('keeps "nothing in particular" distinct from resting now that the backend tells them apart', () => {
    const status = residentStatus('idle', '窗边发了会儿呆，没什么特别想做的')
    expect(status).not.toEqual({ emoji: '☕', shortAction: '歇一会儿' })
    expect(status).toEqual({ emoji: '🍃', shortAction: '没想做什么' })
  })
})

describe('the model chooses conversation emoji', () => {
  it('preserves imaginative combinations without a topic dictionary', () => {
    expect(conversationEmoji('🪴🪐')).toBe('🪴🪐')
    expect(conversationEmoji('🕯️📚')).toBe('🕯️📚')
    expect(conversationEmoji(null)).toBe('💬')
  })
})
