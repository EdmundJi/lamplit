import type { WeatherKind } from './atmosphere'
import { dayPlanFallback, positionAt } from './day-plan'
import type { NpcActivity, NpcPlace, TownNpcView } from './town-npc.types'

/** Virtual weather, exactly matching TownSocietyService.isRainy(LocalDate).
 * Parse the server's calendar date in UTC so browser time zones cannot change the seed. */
export function weatherForDate(localDate: string): WeatherKind {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(localDate)) return 'clear'
  const timestamp = Date.parse(`${localDate}T00:00:00Z`)
  if (!Number.isFinite(timestamp)) return 'clear'
  const day = Math.floor(timestamp / 86_400_000)
  return ((day % 5) + 5) % 5 === 0 ? 'rain' : 'clear'
}

const places: Record<NpcPlace, string> = {
  home: '家里', academy: '学院', gym: '健身房', cafe: '咖啡馆', park: '公园', plaza: '广场', street: '街上',
}
const activities: Record<NpcActivity, string> = {
  idle: '歇一会儿', walking: '散步', reading: '读书', sit: '坐坐', phone: '看消息',
  watering: '浇花', chopping: '劈柴', fishing: '钓鱼', harvesting: '收获', digging: '照料菜地',
}

/** Only describe observable routines; never expose mood scores, regard or private facts. */
export function npcWhereabouts(npc: TownNpcView, minute: number): string {
  const position = positionAt(npc.dayPlan ?? dayPlanFallback(npc.schedule), minute)
  return position.kind === 'WALKING'
    ? `正从${places[position.fromPlace]}走向${places[position.toPlace]}`
    : `在${places[position.place]}${activities[position.activity]}`
}


const clockFormatters = new Map<string, Intl.DateTimeFormat>()
/** The user's town clock, independent of the browser/device timezone. */
export function minuteInZone(epochMs: number, zone: string): number {
  try {
    let formatter = clockFormatters.get(zone)
    if (!formatter) {
      formatter = new Intl.DateTimeFormat('en-GB', { timeZone: zone, hourCycle: 'h23', hour: '2-digit', minute: '2-digit', second: '2-digit', month: '2-digit' })
      clockFormatters.set(zone, formatter)
    }
    const fields = Object.fromEntries(formatter.formatToParts(epochMs).map(part => [part.type, part.value]))
    return Number(fields.hour) * 60 + Number(fields.minute) + Number(fields.second) / 60 + ((epochMs % 1000) + 1000) % 1000 / 60000
  } catch { const date = new Date(epochMs); return date.getHours() * 60 + date.getMinutes() + date.getSeconds() / 60 }
}

/** Visual previews never alter the authoritative NPC clock or the town calendar. */
export function townTime(epochMs: number, zone: string, nightOverride: boolean | null = null): { minutes: number; month: number } {
  const actualMinutes = minuteInZone(epochMs, zone)
  let month = new Date(epochMs).getMonth() + 1
  const formatter = clockFormatters.get(zone)
  if (formatter) month = Number(formatter.formatToParts(epochMs).find(part => part.type === 'month')?.value) || month
  return { minutes: nightOverride === null ? actualMinutes : nightOverride ? 22 * 60 : 12 * 60, month }
}
