import type { TownLetter, TownLetterKind } from './social.types'

export function letterFixture(kind: TownLetterKind = 'LONG', extra: Partial<TownLetter> = {}): TownLetter {
  return {
    publicId: kind, senderKind: kind === 'LONG' ? 'CONFIDANT' : 'NPC',
    senderRef: kind === 'LONG' ? null : 'npc-code', senderName: kind === 'LONG' ? '树洞笔友' : '陆夏',
    kind, body: `${kind} 的正文\n第二段`, deliverAt: '2026-09-06T08:00:00Z',
    readAt: null, createdAt: '2026-09-06T07:00:00Z', ...extra,
  }
}

export function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
