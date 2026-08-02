export type Wallet = { coinBalance: number; lifetimeCoins: number }

export type Pet = {
  publicId: string
  speciesCode: string
  speciesName: string
  name: string
  breed: string
  furColor: string
  level: number
  affection: number
  nextLevelAffection: number
  selected: boolean
  lastInteractedAt?: string
}

export type ShopItem = {
  publicId: string
  itemType: 'FOOD' | 'DECOR'
  speciesCode?: string | null
  speciesName: string
  name: string
  description: string
  price: number
  affectionGain: number
  templateSource: string
}

export type PartnerProfile = {
  wallet: Wallet
  pets: Pet[]
  selectedPet: Pet
  shopItems: ShopItem[]
}

export type InteractionResult = {
  pet: Pet
  affectionDelta: number
  rewarded: boolean
  interactionDate: string
}
