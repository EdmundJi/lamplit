export const AI_THINKING_MESSAGES = [
  '正在理解你的问题...',
  '正在梳理关键信息...',
  '正在把想法排好顺序...',
  '正在寻找更适合你的表达...',
  '正在检查有没有遗漏...',
  '正在把大问题拆小...',
  '正在权衡几种可能...',
  '正在整理一条清晰思路...',
  '正在连接前面的线索...',
  '正在为你组织答案...',
  '正在确认细节...',
  '正在找一个可执行的起点...',
  '正在把建议变得更具体...',
  '正在收拢零散想法...',
  '正在换个角度看看...',
  '正在思考 ing...',
  '正在校准回答的节奏...',
  '正在挑选更稳妥的方案...',
  '正在做最后一遍检查...',
  '快想好了，再给我一点点时间...',
] as const

export function pickThinkingMessage(current = '', random = Math.random) {
  const choices = AI_THINKING_MESSAGES.filter(message => message !== current)
  return choices[Math.floor(random() * choices.length)]
}
