import type { Component } from 'vue'
import { Activity, Building2, Target, CalendarCheck2, ChartNoAxesColumnIncreasing, Sparkles, Settings, PawPrint, UserRound, Users } from 'lucide-vue-next'

/**
 * The single nav definition for UserLayout's sidebar/mobile nav *and* TownStage's docked-strip
 * camera target: each destination optionally names a `place` id (a key into
 * companion-art.ts's STAGE_PLACES). "小镇" itself has no place - it *is* the town, not a page that
 * looks in on it.
 */
export type NavItem = { to: string; label: string; icon: Component; mobile?: 'primary' | 'more'; group: string; place?: string }

export const NAV_ITEMS: NavItem[] = [
  // 'avatar' is the one virtual place (see STAGE_PLACES): /today follows wherever the avatar
  // actually is, rather than a fixed street target that is usually empty (everyone else is inside
  // a home or the cafe).
  { to: '/today', label: '今日', icon: CalendarCheck2, mobile: 'primary', group: '行动', place: 'avatar' },
  { to: '/goals', label: '目标', icon: Target, mobile: 'primary', group: '行动', place: 'board' },
  { to: '/ai', label: 'AI 助手', icon: Sparkles, mobile: 'more', group: '行动', place: 'cafe' },
  { to: '/attributes', label: '属性', icon: Activity, mobile: 'more', group: '成长', place: 'home' },
  { to: '/insights', label: '洞察', icon: ChartNoAxesColumnIncreasing, mobile: 'more', group: '成长', place: 'home' },
  { to: '/town', label: '小镇', icon: Building2, mobile: 'primary', group: '陪伴' },
  { to: '/partners', label: '伙伴', icon: PawPrint, mobile: 'more', group: '陪伴', place: 'garden' },
  { to: '/friends', label: '好友', icon: Users, mobile: 'more', group: '陪伴', place: 'cafe' },
  { to: '/profile', label: '个人', icon: UserRound, mobile: 'more', group: '账户', place: 'home' },
  { to: '/settings', label: '设置', icon: Settings, mobile: 'more', group: '账户', place: 'home' },
]

/** Which STAGE_PLACES id a route should point the docked-strip camera at. Falls back to 'street'
 * for /town itself and for any path with no matching nav entry (or no `place`, e.g. /town). */
export function placeForPath(path: string): string {
  const item = NAV_ITEMS.find(entry => entry.place && (path === entry.to || path.startsWith(`${entry.to}/`)))
  return item?.place ?? 'street'
}
