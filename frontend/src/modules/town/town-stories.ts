export type StoryAction = 'BEGIN' | 'CONTINUE' | 'PARTICIPATE' | 'OBSERVE' | 'PAUSE' | 'RESUME'
export type TownStoryView = {
  npcCode: string
  displayName: string
  title: string
  stage: number
  revision: number
  paused: boolean
  participation: 'UNDECIDED' | 'JOINED' | 'OBSERVED'
  heading: string
  body: string
  actions: { code: StoryAction; label: string }[]
  completedAt: string | null
}

export function supportsStory(npcCode: string): boolean {
  return npcCode === 'GUIDE' || npcCode === 'POSTMAN'
}
