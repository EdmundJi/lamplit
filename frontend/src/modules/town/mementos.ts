import type { Achievement } from '../achievements/achievement.types'

/** The wall is a view of server-persisted achievements, never a fabricated diary. */
export function earnedMementos(achievements: readonly Achievement[]): Achievement[] {
  const timestamp = (item: Achievement) => item.earnedAt ? Date.parse(item.earnedAt) || 0 : 0
  return achievements.filter(item => item.earned).slice().sort((a, b) => timestamp(b) - timestamp(a) || a.code.localeCompare(b.code))
}

export function mementoDate(value: string | null, timezone: string): string {
  if (!value || !Number.isFinite(Date.parse(value))) return '日期未记录'
  try {
    return new Intl.DateTimeFormat('zh-CN', { timeZone: timezone, year: 'numeric', month: 'long', day: 'numeric' }).format(new Date(value))
  } catch {
    return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: 'long', day: 'numeric' }).format(new Date(value))
  }
}
