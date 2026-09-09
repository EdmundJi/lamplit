/** A small visual vocabulary for an already chosen server action, never a new decision. */
export function residentStatus(activity: string, action: string, moving = false, speaking = false) {
  if (speaking) return { emoji: '💬', shortAction: '正在聊天' }
  if (moving) return { emoji: '👣', shortAction: '正在路上' }
  const chosen: Record<string, { emoji: string; shortAction: string }> = {
    sleep: { emoji: '💤', shortAction: '安静睡着' },
    home: { emoji: '☕', shortAction: '在家歇一会儿' },
    rest: { emoji: '☕', shortAction: '歇一会儿' },
    water: { emoji: '☕', shortAction: '喝口水' },
    focus: { emoji: '📖', shortAction: '安静读书' },
    study: { emoji: '📖', shortAction: '安静读书' },
    read: { emoji: '📖', shortAction: '安静读书' },
    create: { emoji: '🎨', shortAction: '正在创作' },
    draw: { emoji: '🎨', shortAction: '正在创作' },
    work: { emoji: '📝', shortAction: '做一件手上的工作' },
    make: { emoji: '📝', shortAction: '做一件手上的工作' },
    away: { emoji: '🚪', shortAction: '出门做事了' },
    help: { emoji: '🤝', shortAction: '和邻居忙一会儿' },
    assist: { emoji: '🤝', shortAction: '替人搭把手' },
    handover: { emoji: '🤝', shortAction: '商量着交接一件事' },
    invite: { emoji: '🤝', shortAction: '找邻居聊聊' },
    celebrate: { emoji: '🤝', shortAction: '一起看看成果' },
    wait: { emoji: '⌛', shortAction: '在旁边等着' },
    tend: { emoji: '☕', shortAction: '在吧台忙着' },
    prepare: { emoji: '☕', shortAction: '正在准备一杯热饮' },
    serve: { emoji: '☕', shortAction: '正在招待邻居' },
    garden: { emoji: '🌱', shortAction: '照料花草' },
    flowers: { emoji: '🌷', shortAction: '看看花草' },
    observe: { emoji: '☕', shortAction: '看看周围' },
  }
  if (chosen[activity]) return chosen[activity]!
  const source = `${activity} ${action}`.toLowerCase()
  // `tend` is a cafe service action in the simulation. It used to be caught by the garden
  // fallback below and made a person standing at the counter look as if they were watering.
  if (/\b(tend|serve|prepare)\b|吧台|柜台|热饮|咖啡/.test(source)) return { emoji: '☕', shortAction: '在吧台忙着' }
  if (/\b(wait)\b|等一等|等待/.test(source)) return { emoji: '⌛', shortAction: '在旁边等着' }
  if (/\b(handover|assist)\b|交接|搭把手/.test(source)) return { emoji: '🤝', shortAction: '商量着做一件事' }
  if (/\b(work|make)\b|工作|写|翻译|制作/.test(source)) return { emoji: '📝', shortAction: '做一件手上的工作' }
  if (/sleep|睡|入梦/.test(source)) return { emoji: '💤', shortAction: '安静睡着' }
  if (/garden|tend|plant|flowers|grow|花|园艺|种植|照料|浇水/.test(source)) return { emoji: '🌱', shortAction: '照料花草' }
  if (/create|paint|draw|画|海报/.test(source)) return { emoji: '🎨', shortAction: '正在创作' }
  if (/focus|study|read|专注|学习|读|备考|考试/.test(source)) return { emoji: '📖', shortAction: '安静读书' }
  if (/help|invite|celebrate|帮|邀请|布置|一起/.test(source)) return { emoji: '🤝', shortAction: '和邻居忙一会儿' }
  if (/talk|chat|聊|谈/.test(source)) return { emoji: '💬', shortAction: '正在聊天' }
  if (/walk|散步|走|回家|搬/.test(source)) return { emoji: '👣', shortAction: '慢慢走走' }
  return { emoji: '☕', shortAction: /water|喝水/.test(source) ? '喝口水' : /coffee|cafe|咖啡|收拾|整理/.test(source) ? '忙着日常小事' : '歇一会儿' }
}

/** Preserve the speaker's generated expression. Older/rule turns have only a neutral chat cue. */
export function conversationEmoji(emoji?: string | null) { return emoji?.trim() || '💬' }
