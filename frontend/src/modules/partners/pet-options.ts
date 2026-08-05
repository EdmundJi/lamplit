import { petVariants } from './pet-variants'

export type PetSpeciesCode = 'CAT' | 'DOG' | 'HAMSTER' | 'SNAKE' | 'RABBIT' | 'BIRD' | 'TURTLE' | 'FOX'

export type PetReaction = 'greet' | 'play' | 'comfort' | 'celebrate'

export type PetKindOption = {
  value: string
  defaultColor: string
}

export type PetInteractionOption = {
  action: PetReaction
  label: string
}

export type PetSpeciesOption = {
  code: PetSpeciesCode
  label: string
  glyph: string
  kinds: readonly PetKindOption[]
  interactions: readonly PetInteractionOption[]
}

function kindsFrom(code: PetSpeciesCode): readonly PetKindOption[] {
  return petVariants[code].map(variant => ({ value: variant.label, defaultColor: variant.color }))
}

export const petSpeciesOptions: readonly PetSpeciesOption[] = [
  {
    code: 'CAT',
    label: '猫',
    glyph: '猫',
    kinds: kindsFrom('CAT'),
    interactions: [
      { action: 'greet', label: '摸摸脑袋' },
      { action: 'play', label: '逗猫棒' },
      { action: 'comfort', label: '靠一会儿' },
      { action: 'celebrate', label: '开心转圈' },
    ],
  },
  {
    code: 'DOG',
    label: '狗',
    glyph: '犬',
    kinds: kindsFrom('DOG'),
    interactions: [
      { action: 'greet', label: '摸摸耳朵' },
      { action: 'play', label: '追尾巴' },
      { action: 'comfort', label: '陪它坐坐' },
      { action: 'celebrate', label: '开心击掌' },
    ],
  },
  {
    code: 'HAMSTER',
    label: '仓鼠',
    glyph: '鼠',
    kinds: kindsFrom('HAMSTER'),
    interactions: [
      { action: 'greet', label: '轻轻招手' },
      { action: 'play', label: '跳跳挑战' },
      { action: 'comfort', label: '安静陪伴' },
      { action: 'celebrate', label: '开心蹦跳' },
    ],
  },
  {
    code: 'SNAKE',
    label: '蛇',
    glyph: '蛇',
    kinds: kindsFrom('SNAKE'),
    interactions: [
      { action: 'greet', label: '打个招呼' },
      { action: 'play', label: '左右摇摆' },
      { action: 'comfort', label: '安静盘绕' },
      { action: 'celebrate', label: '开心抬头' },
    ],
  },
  {
    code: 'RABBIT',
    label: '兔子',
    glyph: '兔',
    kinds: kindsFrom('RABBIT'),
    interactions: [
      { action: 'greet', label: '摸摸耳朵' },
      { action: 'play', label: '蹦跳游戏' },
      { action: 'comfort', label: '轻声陪伴' },
      { action: 'celebrate', label: '开心跳跃' },
    ],
  },
  {
    code: 'BIRD',
    label: '小鸟',
    glyph: '鸟',
    kinds: kindsFrom('BIRD'),
    interactions: [
      { action: 'greet', label: '轻声呼唤' },
      { action: 'play', label: '转向游戏' },
      { action: 'comfort', label: '安静听歌' },
      { action: 'celebrate', label: '展翅庆祝' },
    ],
  },
  {
    code: 'TURTLE',
    label: '乌龟',
    glyph: '龟',
    kinds: kindsFrom('TURTLE'),
    interactions: [
      { action: 'greet', label: '摸摸脑袋' },
      { action: 'play', label: '探头游戏' },
      { action: 'comfort', label: '安静陪伴' },
      { action: 'celebrate', label: '站起庆祝' },
    ],
  },
  {
    code: 'FOX',
    label: '狐狸',
    glyph: '狐',
    kinds: kindsFrom('FOX'),
    interactions: [
      { action: 'greet', label: '挥手问好' },
      { action: 'play', label: '跳跃游戏' },
      { action: 'comfort', label: '靠近陪伴' },
      { action: 'celebrate', label: '开心转圈' },
    ],
  },
]

export function findPetSpecies(code: string) {
  return petSpeciesOptions.find(species => species.code === code)
}
