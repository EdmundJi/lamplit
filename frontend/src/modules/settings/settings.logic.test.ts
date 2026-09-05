import { describe, expect, it } from 'vitest'
import { channelHint, channelLabel } from './settings.logic'

describe('notification channel Chinese labels', () => {
  it('maps known channel codes to their Chinese label and hint', () => {
    expect(channelLabel('EMAIL')).toBe('邮件提醒')
    expect(channelHint('EMAIL')).toBe('发到你的注册邮箱')
    expect(channelLabel('IN_APP')).toBe('站内消息')
    expect(channelLabel('WEB_PUSH')).toBe('浏览器推送')
  })

  it('falls back to the raw channel code for anything unknown', () => {
    expect(channelLabel('SMS')).toBe('SMS')
    expect(channelHint('SMS')).toBe('提醒渠道')
  })
})
