import type { PetSpeciesCode } from './pet-options'

export type PetReaction = 'greet' | 'play' | 'comfort' | 'celebrate' | 'feed'

export type PetInputSpec = {
  name: string
  mode: 'fire' | 'pulse' | 'set' | 'fireOrPulse'
  value?: number
  delay?: number
}

export type PetVariant =
  | {
    id: string
    label: string
    color: string
    kind: 'rive'
    src: string
    artboard: string
    stateMachine: string
    credit: string
    reactions?: Partial<Record<PetReaction, PetInputSpec[]>>
  }
  | {
    id: string
    label: string
    color: string
    kind: 'image'
    src: string
    credit: string
    hue?: string
  }

export const petVariants: Record<PetSpeciesCode, readonly PetVariant[]> = {
  CAT: [
    {
      id: 'cat-play-time',
      label: '玩耍猫咪',
      color: '橘白',
      kind: 'rive',
      src: '/assets/pets/rive/cat-play-time.riv',
      artboard: '404_artboard',
      stateMachine: 'cat_SM',
      credit: 'Play Time · rkoomera',
      reactions: {
        greet: [{ name: 'arboard click', mode: 'fireOrPulse' }],
        play: [{ name: 'arboard click', mode: 'fireOrPulse' }],
        comfort: [{ name: 'arboard click', mode: 'fireOrPulse' }],
        celebrate: [{ name: 'arboard click', mode: 'fireOrPulse' }],
        feed: [{ name: 'arboard click', mode: 'fireOrPulse' }],
      },
    },
    {
      id: 'cat-feed-the-cat',
      label: '投喂猫咪',
      color: '奶油白',
      kind: 'rive',
      src: '/assets/pets/rive/cat-feed-the-cat.riv',
      artboard: 'Cat_1',
      stateMachine: 'State Machine 1',
      credit: 'Feed the cat · Jurgen_the_animator',
    },
    {
      id: 'cat-nera',
      label: '激光笔猫',
      color: '狸花棕',
      kind: 'rive',
      src: '/assets/pets/rive/cat-nera.riv',
      artboard: 'Artboard',
      stateMachine: 'Cat Machine',
      credit: 'Nera the Cat · Chloe_Cristina',
    },
    {
      id: 'cat-sleepy',
      label: '打瞌睡猫',
      color: '蓝灰',
      kind: 'rive',
      src: '/assets/pets/rive/cat-sleepy.riv',
      artboard: 'Artboard',
      stateMachine: 'cat_controller',
      credit: 'Sleepy Cat · metamom_mama',
      reactions: {
        greet: [{ name: 'tap_wake', mode: 'fire' }],
        play: [{ name: 'tap_wake', mode: 'fire' }],
        comfort: [{ name: 'tap_wake', mode: 'fire' }],
        celebrate: [{ name: 'tap_wake', mode: 'fire' }],
        feed: [{ name: 'tap_wake', mode: 'fire' }],
      },
    },
    {
      id: 'cat-bento',
      label: '便当盒猫',
      color: '黄白',
      kind: 'rive',
      src: '/assets/pets/rive/cat-bento.riv',
      artboard: 'Bento',
      stateMachine: 'State Machine 1',
      credit: 'Bento Cat · mikewirsch',
    },
  ],
  DOG: [
    {
      id: 'dog-interactive',
      label: '互动小狗',
      color: '焦糖白',
      kind: 'rive',
      src: '/assets/pets/rive/dog-interactive.riv',
      artboard: 'dog-walk-cycle',
      stateMachine: 'State Machine 1',
      credit: 'Interactive Dog · jouri',
      reactions: {
        greet: [{ name: 'Hit Oor', mode: 'pulse', value: 520 }],
        play: [{ name: 'Hit Staart', mode: 'pulse', value: 520 }],
        comfort: [{ name: 'Hit Oor', mode: 'pulse', value: 760 }],
        celebrate: [
          { name: 'Hit Staart', mode: 'pulse', value: 520 },
          { name: 'Hit Oor', mode: 'pulse', value: 520, delay: 210 },
        ],
        feed: [{ name: 'Hit Tong', mode: 'pulse', value: 520 }],
      },
    },
    {
      id: 'dog-happy',
      label: '开心小狗',
      color: '赤棕',
      kind: 'rive',
      src: '/assets/pets/rive/dog-happy.riv',
      artboard: 'Artboard',
      stateMachine: 'State Machine 1',
      credit: 'happy dog · silviaporcu.po',
    },
    {
      id: 'dog-walking-cycle',
      label: '散步小狗',
      color: '奶油黄',
      kind: 'rive',
      src: '/assets/pets/rive/dog-walking-cycle.riv',
      artboard: 'Dog walking sliders',
      stateMachine: 'State Machine 1',
      credit: 'Walking cycle Dog · jiska.heringa',
    },
    {
      id: 'dog-sausage',
      label: '腊肠狗',
      color: '黑褐',
      kind: 'rive',
      src: '/assets/pets/rive/dog-sausage.riv',
      artboard: 'Sausage dog 2',
      stateMachine: 'State Machine 1',
      credit: 'Sausage Dog · amcdowell100',
      reactions: {
        greet: [{ name: 'isHover', mode: 'pulse', value: 600 }],
        play: [{ name: 'isHover', mode: 'pulse', value: 600 }],
        comfort: [{ name: 'isHover', mode: 'pulse', value: 600 }],
        celebrate: [{ name: 'isHover', mode: 'pulse', value: 600 }],
        feed: [{ name: 'isHover', mode: 'pulse', value: 600 }],
      },
    },
  ],
  HAMSTER: [
    {
      id: 'hamster-idle-jump',
      label: '蹦跳仓鼠',
      color: '奶油金',
      kind: 'rive',
      src: '/assets/pets/rive/hamster-idle-jump.riv',
      artboard: 'HasmterNested',
      stateMachine: 'State Machine 1',
      credit: 'Hamster · ersanakpinarr',
      reactions: {
        greet: [{ name: 'Trigger 1', mode: 'fireOrPulse' }],
        play: [{ name: 'Trigger 1', mode: 'fireOrPulse' }],
        comfort: [{ name: 'Trigger 1', mode: 'fireOrPulse' }],
        celebrate: [
          { name: 'Trigger 1', mode: 'fireOrPulse' },
          { name: 'Trigger 1', mode: 'fireOrPulse', delay: 260 },
        ],
        feed: [{ name: 'Trigger 1', mode: 'fireOrPulse' }],
      },
    },
    {
      id: 'hamster-rat-bonk',
      label: '敲敲鼠',
      color: '银灰',
      kind: 'rive',
      src: '/assets/pets/rive/hamster-rat-bonk.riv',
      artboard: 'Artboard',
      stateMachine: 'State Machine 1',
      credit: 'Rat Bonk · duhkukx',
      reactions: {
        greet: [{ name: 'hit.trigger', mode: 'fire' }],
        play: [{ name: 'hit.trigger', mode: 'fire' }],
        comfort: [{ name: 'hit.trigger', mode: 'fire' }],
        celebrate: [
          { name: 'hit.trigger', mode: 'fire' },
          { name: 'hit.trigger', mode: 'fire', delay: 260 },
        ],
        feed: [{ name: 'hit.trigger', mode: 'fire' }],
      },
    },
    {
      id: 'hamster-walking-mouse',
      label: '星球小鼠',
      color: '沙金白',
      kind: 'rive',
      src: '/assets/pets/rive/hamster-walking-mouse.riv',
      artboard: 'Artboard',
      stateMachine: 'State Machine 1',
      credit: 'Walking Mouse · hidysaam',
      reactions: {
        greet: [{ name: 'isBig', mode: 'pulse', value: 600 }],
        play: [{ name: 'isBig', mode: 'pulse', value: 600 }],
        comfort: [{ name: 'isBig', mode: 'pulse', value: 600 }],
        celebrate: [{ name: 'isBig', mode: 'pulse', value: 600 }],
        feed: [{ name: 'isBig', mode: 'pulse', value: 600 }],
      },
    },
  ],
  SNAKE: [
    {
      id: 'snake-cartoon',
      label: '青绿蛇',
      color: '青绿',
      kind: 'image',
      src: '/assets/pets/snake-cartoon.svg',
      credit: 'Twemoji · CC BY 4.0',
    },
    {
      id: 'snake-hungry',
      label: '贪吃蛇',
      color: '橙红',
      kind: 'rive',
      src: '/assets/pets/rive/snake-hungry.riv',
      artboard: 'Artboard',
      stateMachine: 'State Machine 1',
      credit: 'Hungry Snake · RyanRumbolt',
    },
    {
      id: 'snake-cartoon-purple',
      label: '紫红蛇',
      color: '紫红',
      kind: 'image',
      src: '/assets/pets/snake-cartoon.svg',
      credit: 'Twemoji · CC BY 4.0',
      hue: '220deg',
    },
    {
      id: 'snake-cartoon-blue',
      label: '天蓝蛇',
      color: '天蓝',
      kind: 'image',
      src: '/assets/pets/snake-cartoon.svg',
      credit: 'Twemoji · CC BY 4.0',
      hue: '150deg',
    },
  ],
  RABBIT: [
    {
      id: 'rabbit-interactive',
      label: '登录兔',
      color: '月白',
      kind: 'rive',
      src: '/assets/pets/rive/rabbit-interactive.riv',
      artboard: 'Login',
      stateMachine: 'State Machine 1',
      credit: 'Animated Login Bunny · trong.phanduc34',
      reactions: {
        greet: [{ name: 'isFocus', mode: 'pulse', value: 620 }],
        play: [{ name: 'login_success', mode: 'fireOrPulse', value: 620 }],
        comfort: [{ name: 'isFocus', mode: 'pulse', value: 860 }],
        celebrate: [{ name: 'login_success', mode: 'fireOrPulse', value: 620 }],
        feed: [{ name: 'login_success', mode: 'fireOrPulse', value: 620 }],
      },
    },
    {
      id: 'rabbit-cute-bunny',
      label: '软萌兔',
      color: '烟灰白',
      kind: 'rive',
      src: '/assets/pets/rive/rabbit-cute-bunny.riv',
      artboard: 'Artboard',
      stateMachine: 'State Machine 1',
      credit: 'Cute Bunny Interactive Character · metamom_mama',
      reactions: {
        greet: [{ name: 'onClick', mode: 'fireOrPulse' }],
        play: [{ name: 'onClick', mode: 'fireOrPulse' }],
        comfort: [{ name: 'onClick', mode: 'fireOrPulse' }],
        celebrate: [{ name: 'onClick', mode: 'fireOrPulse' }],
        feed: [{ name: 'onClick', mode: 'fireOrPulse' }],
      },
    },
    {
      id: 'rabbit-button',
      label: '按钮兔',
      color: '雪白',
      kind: 'rive',
      src: '/assets/pets/rive/rabbit-button.riv',
      artboard: 'Rabbit Button',
      stateMachine: 'State Machine 1',
      credit: 'Button Rabbit Animation · zee31wizard',
      reactions: {
        greet: [{ name: 'Hover', mode: 'pulse', value: 620 }],
        play: [{ name: 'Pressed', mode: 'fire' }],
        comfort: [{ name: 'Hover', mode: 'pulse', value: 820 }],
        celebrate: [{ name: 'Pressed', mode: 'fire' }],
        feed: [{ name: 'Pressed', mode: 'fire' }],
      },
    },
    {
      id: 'rabbit-bunny-run',
      label: '兔兔跑酷',
      color: '浅棕白',
      kind: 'rive',
      src: '/assets/pets/rive/rabbit-bunny-run.riv',
      artboard: 'BunnyRun!!!',
      stateMachine: 'StateMachine',
      credit: 'Bunny Run Game · mixhead',
      reactions: {
        greet: [{ name: 'triggerStart', mode: 'fire' }],
        play: [{ name: 'triggerStart', mode: 'fire' }],
        comfort: [{ name: 'isHover', mode: 'pulse', value: 820 }],
        celebrate: [
          { name: 'triggerStart', mode: 'fire' },
          { name: 'triggerStart', mode: 'fire', delay: 240 },
        ],
        feed: [{ name: 'triggerStart', mode: 'fire' }],
      },
    },
  ],
  BIRD: [
    {
      id: 'bird-interactive',
      label: '飞飞鸟',
      color: '淡黄灰',
      kind: 'rive',
      src: '/assets/pets/rive/bird-interactive.riv',
      artboard: 'Bird',
      stateMachine: 'State Machine 1',
      credit: 'Bird · ElmerVergara',
      reactions: {
        greet: [{ name: 'direction', mode: 'set', value: 45 }],
        play: [{ name: 'direction', mode: 'set', value: 135 }],
        comfort: [{ name: 'direction', mode: 'set', value: 225 }],
        celebrate: [{ name: 'direction', mode: 'set', value: 315 }],
        feed: [{ name: 'direction', mode: 'set', value: 180 }],
      },
    },
    {
      id: 'bird-flying-set',
      label: '飞行小队',
      color: '黄绿',
      kind: 'rive',
      src: '/assets/pets/rive/bird-flying-set.riv',
      artboard: 'main',
      stateMachine: 'State Machine 1',
      credit: 'Flying Character Set · HaiDo',
      reactions: {
        greet: [{ name: 'direction', mode: 'set', value: 45 }],
        play: [{ name: 'direction', mode: 'set', value: 135 }],
        comfort: [{ name: 'direction', mode: 'set', value: 225 }],
        celebrate: [{ name: 'direction', mode: 'set', value: 315 }],
        feed: [{ name: 'direction', mode: 'set', value: 180 }],
      },
    },
    {
      id: 'bird-pigeon',
      label: '好奇鸽子',
      color: '灰白',
      kind: 'rive',
      src: '/assets/pets/rive/bird-pigeon.riv',
      artboard: 'Artboard',
      stateMachine: 'State Machine 1',
      credit: 'pigeon · yuva_m',
    },
    {
      id: 'bird-owl-mascot',
      label: '猫头鹰',
      color: '棕褐',
      kind: 'rive',
      src: '/assets/pets/rive/bird-owl-mascot.riv',
      artboard: 'Artboard',
      stateMachine: 'State Machine 1',
      credit: 'Owl Mascot Animation · AnggaMotion',
    },
    {
      id: 'bird-ducky',
      label: '小黄鸭',
      color: '明黄',
      kind: 'rive',
      src: '/assets/pets/rive/bird-ducky.riv',
      artboard: 'Ducky',
      stateMachine: 'Duck ',
      credit: 'Jumpy Walky Ducky · leule-4WCG8',
    },
  ],
  TURTLE: [
    {
      id: 'turtle-angry',
      label: '生气龟',
      color: '苔绿',
      kind: 'rive',
      src: '/assets/pets/rive/turtle-angry.riv',
      artboard: 'Artboard',
      stateMachine: 'State Machine 1',
      credit: 'Angry Turtle · extraframe28',
      reactions: {
        greet: [{ name: 'click trigger', mode: 'fire' }],
        play: [{ name: 'hit trigger', mode: 'fire' }],
        comfort: [{ name: 'body hover', mode: 'pulse', value: 820 }],
        celebrate: [{ name: 'stand up/down', mode: 'pulse', value: 900 }],
        feed: [{ name: 'hit trigger', mode: 'fire' }],
      },
    },
    {
      id: 'turtle-cartoon',
      label: '苔绿龟',
      color: '苔绿',
      kind: 'image',
      src: '/assets/pets/turtle-cartoon.svg',
      credit: 'Twemoji · CC BY 4.0',
    },
    {
      id: 'turtle-cartoon-blue',
      label: '青蓝龟',
      color: '青蓝',
      kind: 'image',
      src: '/assets/pets/turtle-cartoon.svg',
      credit: 'Twemoji · CC BY 4.0',
      hue: '150deg',
    },
  ],
  FOX: [
    {
      id: 'fox-idle',
      label: '乖乖狐',
      color: '赤橙',
      kind: 'rive',
      src: '/assets/pets/rive/fox-idle.riv',
      artboard: 'fox',
      stateMachine: 'State Machine 1',
      credit: 'Fox · sandeep.k',
    },
    {
      id: 'fox-in-the-hole',
      label: '洞中狐',
      color: '雪白',
      kind: 'rive',
      src: '/assets/pets/rive/fox-in-the-hole.riv',
      artboard: 'Artboard',
      stateMachine: 'State Machine 1',
      credit: 'Fox In The Hole! · lwhitcom',
    },
    {
      id: 'fox-walk-cycle',
      label: '散步狐',
      color: '沙金',
      kind: 'rive',
      src: '/assets/pets/rive/fox-walk-cycle.riv',
      artboard: 'Artboard 2',
      stateMachine: 'State Machine 1',
      credit: 'Fox walk cycle · Sandyuiuxstudio',
    },
  ],
}

const legacyKindToVariantIndex: Record<PetSpeciesCode, Record<string, number>> = {
  CAT: { 中华田园猫: 0, 英国短毛猫: 1, 布偶猫: 2, 暹罗猫: 3 },
  DOG: { 柯基: 0, 柴犬: 1, 拉布拉多: 2, 边境牧羊犬: 3 },
  HAMSTER: { 金丝熊: 0, 三线仓鼠: 1, 一线仓鼠: 2, 罗伯罗夫斯基仓鼠: 2 },
  SNAKE: { 玉米蛇: 0, 王蛇: 1, 奶蛇: 2, 球蟒: 0 },
  RABBIT: { 垂耳兔: 0, 荷兰侏儒兔: 1, 安哥拉兔: 2, 狮子兔: 3 },
  BIRD: { 玄凤鹦鹉: 0, 虎皮鹦鹉: 1, 文鸟: 2, 金丝雀: 3 },
  TURTLE: { 中华草龟: 0, 巴西红耳龟: 2, 黄缘闭壳龟: 1, 麝香龟: 0 },
  FOX: { 赤狐: 0, 北极狐: 1, 耳廓狐: 2, 银狐: 1 },
}

export function variantCountFor(code: string): number {
  return (petVariants as Record<string, readonly PetVariant[]>)[code]?.length ?? 0
}

export function variantAt(code: string, index: number): PetVariant | undefined {
  const list = (petVariants as Record<string, readonly PetVariant[]>)[code]
  if (!list?.length) return undefined
  return list[Math.max(0, Math.min(index || 0, list.length - 1))]
}

export function variantIndexForKind(code: string, breed: string): number {
  const list = (petVariants as Record<string, readonly PetVariant[]>)[code]
  const labelIndex = list?.findIndex(variant => variant.label === breed)
  if (labelIndex != null && labelIndex >= 0) return labelIndex
  const mapping = (legacyKindToVariantIndex as Record<string, Record<string, number>>)[code]
  return mapping?.[breed] ?? 0
}

export function petVariantStorageKey(petPublicId: string): string {
  return `better-self:pet-variant:${petPublicId}`
}
