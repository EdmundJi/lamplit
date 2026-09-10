import { onBeforeUnmount, onMounted } from 'vue'

/**
 * The town's day is split the same way the backend clock splits it
 * (see CompanionRules.java): a coarse period drives both the resident
 * simulation and, here, the ambient tint on `--period-tint`.
 */
export type Period = 'morning' | 'afternoon' | 'evening' | 'night'

const CHECK_INTERVAL_MS = 60_000

export function currentPeriod(date: Date = new Date()): Period {
  const hour = date.getHours()
  if (hour < 6 || hour >= 23) return 'night'
  if (hour < 12) return 'morning'
  if (hour < 18) return 'afternoon'
  return 'evening'
}

/**
 * Keeps `document.documentElement.dataset.period` in sync with the wall
 * clock for as long as the calling component is mounted, so tokens.css can
 * key `--period-tint` off `:root[data-period]` without any component having
 * to thread the value through props.
 */
export function usePeriod() {
  let timer: ReturnType<typeof setInterval> | undefined

  function apply() {
    if (typeof document === 'undefined') return
    document.documentElement.dataset.period = currentPeriod()
  }

  onMounted(() => {
    apply()
    timer = setInterval(apply, CHECK_INTERVAL_MS)
  })

  onBeforeUnmount(() => {
    if (timer !== undefined) clearInterval(timer)
    if (typeof document !== 'undefined') delete document.documentElement.dataset.period
  })
}
