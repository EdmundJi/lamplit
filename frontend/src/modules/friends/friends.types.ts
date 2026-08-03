export type FriendItem = {
  publicId: string
  displayName: string
  overallLevel: number
  memberSince: string
  status: 'PENDING' | 'ACCEPTED'
  direction: 'INCOMING' | 'OUTGOING'
  createdAt: string
}

export type FriendList = {
  friends: FriendItem[]
  incoming: FriendItem[]
  outgoing: FriendItem[]
}

export type FriendOverview = {
  effectiveActions: number
  fulfillmentRate: number
  recoveryCount: number
  totalExperience: number
}

export type FriendRole = {
  roleCode: string
  roleName: string
  level: number
}

export type FriendPet = {
  speciesCode: string
  speciesName: string
  name: string
  breed: string
  furColor: string
  level: number
  affection: number
  nextLevelAffection: number
}

export type FriendAttribute = {
  code: string
  name: string
  dimensionName: string
  experience: number
  level: number
  radarScore: number
}

export type FriendTask = {
  publicId: string
  title: string
  status: string
  roleName: string
  plannedStartAt: string
}

export type FriendProfile = {
  publicId: string
  displayName: string
  memberSince: string
  overallLevel: number
  totalExperience: number
  overview: FriendOverview
  longestStreak: number
  roles: FriendRole[]
  pet: FriendPet | null
  attributes: FriendAttribute[]
  todayTasks: FriendTask[]
}
