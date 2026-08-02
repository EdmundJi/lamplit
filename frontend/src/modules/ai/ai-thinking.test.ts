import { describe, expect, it } from 'vitest'
import { AI_THINKING_MESSAGES, pickThinkingMessage } from './ai-thinking'

describe('AI thinking messages', () => {
  it('provides 20 unique transition messages', () => {
    expect(AI_THINKING_MESSAGES).toHaveLength(20)
    expect(new Set(AI_THINKING_MESSAGES)).toHaveLength(20)
  })

  it('does not immediately repeat the current message', () => {
    const current = AI_THINKING_MESSAGES[0]
    expect(pickThinkingMessage(current, () => 0)).not.toBe(current)
  })
})
