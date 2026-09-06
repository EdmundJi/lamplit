/**
 * 小镇的确定性数据。数值是照着 docs/成长小镇.md 的换算表挑的，用例可以直接断言画面文案：
 *
 *   me   LV.7  → floorsForLevel(7)=3 → “4 层”，下一层在 LV.9
 *        streak 21 → 屋顶三件装置全解锁
 *        KNOWLEDGE → 书店门面；1 件 DONE + 1 件 PLANNED → “今天已经有收获（1/2）”
 *   邻居A LV.3 → “2 层”，IN_PROGRESS → “正在进行中”
 *   邻居B LV.1 → “1 层”，无安排 → “今天还没开张”，卷帘门放下
 */

const TODAY = new Date()
const iso = (hourOffset: number) => new Date(TODAY.getTime() + hourOffset * 3_600_000).toISOString()
const localDate = () => {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${TODAY.getFullYear()}-${pad(TODAY.getMonth() + 1)}-${pad(TODAY.getDate())}`
}

export const SELF_ID = 'self-0000-0000-0000'
export const NEIGHBOUR_ACTIVE_ID = 'nb-active-0000-0001'
export const NEIGHBOUR_IDLE_ID = 'nb-idle-0000-0002'

const PET = {
  publicId: 'pet-1', speciesCode: 'CAT', speciesName: '猫', name: '豆包', breed: '狸花', furColor: '#c8a97e',
  level: 2, affection: 40, nextLevelAffection: 100, selected: true,
}

type Presence = { x: number; y: number; facing: string; scene: string; updatedAt?: string } | null
type Schedule = {
  publicId: string; title: string; status: string
  roleCode: string | null; roleName: string | null
  plannedStartAt: string | null; plannedEndAt: string | null
  estimatedMinutes: number | null; difficulty: number | null
}
type Resident = {
  publicId: string; displayName: string; level: number; totalExperience: number
  dominantDimension: string | null; longestStreak: number; title: string | null
  self: boolean; timezone: string; schedules: Schedule[]; presence: Presence
}

export type TownFixture = ReturnType<typeof baseFixture>

function baseFixture() {
  return {
    me: { publicId: SELF_ID, email: 'cypress@example.test', displayName: '测试镇长', timezone: 'Asia/Shanghai', role: 'USER' },
    /** `/me/profile` 的完整形状（见 modules/profile/profile.logic.ts 的 Profile）。
     *  个人面板会直接读 wallet.coinBalance，缺字段会在渲染里抛未捕获错误。 */
    profile: {
      publicId: SELF_ID,
      email: 'cypress@example.test',
      displayName: '测试镇长',
      birthDate: '1990-01-01',
      age: 36,
      timezone: 'Asia/Shanghai',
      createdAt: new Date('2026-01-01').toISOString(),
      overallLevel: 7,
      totalExperience: 4200,
      effectiveActions: 18,
      longestStreak: 21,
      wallet: { coinBalance: 120, lifetimeCoins: 300 },
      selectedPet: PET,
      petCount: 1,
      soloGrowth: false,
      equippedTitle: { code: 'EARLY_BIRD', name: '晨型人', description: '连续早起 21 天', graphicType: 'LUCIDE', graphicKey: 'sunrise', frameStyle: 'gold', held: true, equipped: true, acquiredAt: null },
    },
    town: {
      localDate: localDate(),
      serverTime: new Date().toISOString(),
      unread: 2,
      soloGrowth: false,
      residents: <Resident[]>[
        {
          publicId: SELF_ID,
          displayName: '测试镇长',
          level: 7,
          totalExperience: 4200,
          dominantDimension: 'KNOWLEDGE',
          longestStreak: 21,
          title: '晨型人',
          self: true,
          timezone: 'Asia/Shanghai',
          schedules: [
            { publicId: 'sch-done', title: '读完一章', status: 'DONE', roleCode: 'STUDENT', roleName: '学生', plannedStartAt: iso(-2), plannedEndAt: iso(-1), estimatedMinutes: 60, difficulty: 2 },
            { publicId: 'sch-planned', title: '晚间复盘', status: 'PLANNED', roleCode: 'WORKER', roleName: '职场人', plannedStartAt: iso(3), plannedEndAt: iso(4), estimatedMinutes: 60, difficulty: 1 },
          ],
          presence: null,
        },
        {
          publicId: NEIGHBOUR_ACTIVE_ID,
          displayName: '邻居阿泽',
          level: 3,
          totalExperience: 900,
          dominantDimension: 'HEALTH',
          longestStreak: 5,
          title: null,
          self: false,
          timezone: 'Asia/Shanghai',
          schedules: [
            { publicId: 'sch-running', title: '跑步 5 公里', status: 'IN_PROGRESS', roleCode: 'FITNESS_USER', roleName: '健身', plannedStartAt: iso(-1), plannedEndAt: iso(1), estimatedMinutes: 120, difficulty: 3 },
          ],
          presence: null,
        },
        {
          publicId: NEIGHBOUR_IDLE_ID,
          displayName: '邻居小满',
          level: 1,
          totalExperience: 40,
          dominantDimension: 'WELLBEING',
          longestStreak: 0,
          title: null,
          self: false,
          timezone: 'Asia/Shanghai',
          schedules: [],
          presence: null,
        },
      ],
    },
    presence: { x: 1200, y: 920, facing: 'down', scene: 'town', updatedAt: new Date().toISOString() },
    reflection: <{ publicId: string; localDate: string; greeting: string; insights: string[] } | null>{
      publicId: 'reflect-1',
      localDate: localDate(),
      greeting: '昨天你把最难的一章啃完了，今天可以轻一点。',
      insights: ['连续 21 天没有断过', '知识维度涨得最快'],
    },
    /**
     * `GET /town/npcs`。**必须打桩**，而且必须是 `{ npcs, initiativeBudget }` 这个对象形状
     * （town-npc.types.ts 的注释里写着 "an object, not a bare array"）。
     *
     * 之前这个接口落到了 commands.ts 的兜底 `envelope([])` 上，于是 store 里
     * `this.npcs = response.npcs` 拿到 undefined，引擎 create() 走到
     * `if (townNpcRoster.length > 0)` 直接抛 TypeError，整个 create() 在 setupInput()
     * 之前中断——小人没有键盘、镜头没有跟随对象。而 e2e.ts 又把 Phaser 相关的报错静音了，
     * 所以这一切在日志里一个字都看不到，只表现为若干条"selfWalker 为 null"的断言失败。
     */
    npcs: {
      npcs: [
        {
          code: 'npc-baker', displayName: '面包师老周', layer: 1, sprite: 'c05',
          dimension: null, interests: {}, affinityToPlayer: 10, mood: { valence: 0.4, energy: 0.6 },
          schedule: [
            { startHour: 0, endHour: 8, place: 'home', activity: 'resting' },
            { startHour: 8, endHour: 18, place: 'cafe', activity: 'working' },
            { startHour: 18, endHour: 24, place: 'home', activity: 'resting' },
          ],
          talkingPoints: [],
        },
        {
          code: 'npc-coach', displayName: '教练阿岚', layer: 2, sprite: 'c09',
          dimension: 'FITNESS', interests: { FITNESS: 0.8 }, affinityToPlayer: 30, mood: { valence: 0.6, energy: 0.9 },
          schedule: [
            { startHour: 0, endHour: 7, place: 'home', activity: 'resting' },
            { startHour: 7, endHour: 21, place: 'gym', activity: 'working' },
            { startHour: 21, endHour: 24, place: 'home', activity: 'resting' },
          ],
          talkingPoints: [],
        },
      ],
      initiativeBudget: { limit: 3, used: 0 },
    },
    npcMessages: [] as unknown[],
    /** 伙伴面板会直接读 selectedPet，给一个最小但完整的资料，避免空态在渲染里炸掉。 */
    partnerProfile: {
      wallet: { coinBalance: 120, lifetimeCoins: 300 },
      pets: [PET],
      selectedPet: PET,
      shopItems: [],
    },
  }
}

export function townFixture(overrides: Partial<TownFixture> = {}): TownFixture {
  return { ...baseFixture(), ...overrides }
}
