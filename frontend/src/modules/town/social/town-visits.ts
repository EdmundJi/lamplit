import type { HomeStyle } from '../home-style'
export type VisitMemento = { code: string; name: string; triggerText: string | null; earnedAt: string }
export type VisitProfile = { publicId: string; displayName: string; enabled: boolean; style: HomeStyle; mementos: VisitMemento[] }
export type VisitPostcard = { publicId: string; senderName: string; body: string; createdAt: string }
/** Preserve the key for a retry of identical text, replace it when the user edits the message. */
export function postcardAttempt(previous: { owner: string; body: string; requestKey: string } | null, owner: string, body: string) {
  const clean = body.trim()
  return previous?.owner === owner && previous.body === clean ? previous : { owner, body: clean, requestKey: typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, '0')).join('') }
}
