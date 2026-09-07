/** TownSocialController / TownLetterService JSON payloads; api unwraps ApiEnvelope. */
export type TownLetterKind = 'LONG' | 'NOTE' | 'INVITE'
export interface TownLetter {
  publicId: string
  eventPublicId?: string | null
  senderKind: 'CONFIDANT' | 'NPC'
  senderRef: string | null
  senderName: string
  kind: TownLetterKind
  body: string
  deliverAt: string
  readAt: string | null
  createdAt: string
}
export interface TownLetterInbox {
  letters: TownLetter[]
  unreadCount: number
}
export const letterKindLabels: Record<TownLetterKind, string> = {
  LONG: '树洞长信', NOTE: '居民短笺', INVITE: '活动请柬',
}
export const CONFIDANT_MAX_LENGTH = 2000
