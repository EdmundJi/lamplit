import { computed } from 'vue'

/** Keep unchanged drawing inputs referentially stable across saved-world refreshes. */
export function stableProjection<T>(read: () => T) {
  let signature = ''
  return computed<T>((previous) => {
    const next = read()
    const nextSignature = JSON.stringify(next)
    if (previous !== undefined && nextSignature === signature) return previous
    signature = nextSignature
    return next
  })
}
