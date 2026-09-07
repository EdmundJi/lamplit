import { defineStore } from 'pinia'
import { api, type ApiError } from '../../shared/api/client'
import type { InitiativeBudget, NpcTalkingPoint, TownNpcsResponse, TownNpcTalkingPointsResponse, TownNpcView } from './town-npc.types'

/** Empty roster used before the first successful load, and when the endpoint isn't deployed yet. */
const EMPTY_BUDGET: InitiativeBudget = { limit: 0, used: 0 }

function isNotFound(error: unknown): boolean {
  const status = (error as ApiError | undefined)?.status
  return status === 404
}

export const useTownNpcStore = defineStore('townNpc', {
  state: () => ({
    npcs: [] as TownNpcView[],
    budget: EMPTY_BUDGET as InitiativeBudget,
    loading: false,
    error: '',
  }),
  getters: {
    /** Look up a roster entry by `npc_code`; `null` when unknown (or roster not loaded). */
    byCode: state => (code: string): TownNpcView | null => state.npcs.find(npc => npc.code === code) ?? null,
  },
  actions: {
    /** Fetches `GET /town/npcs`. A 404 means the feature isn't deployed yet — degrade to an
     * empty roster rather than surfacing an error, same as `town.store.ts` does for presence. */
    async load(): Promise<void> {
      this.loading = true
      this.error = ''
      try {
        const response = await api.get<TownNpcsResponse>('/town/npcs')
        if (!response || !Array.isArray(response.npcs) || !response.initiativeBudget) throw new Error('居民资料格式不完整，请检查小镇服务版本')
        this.npcs = response.npcs
        this.budget = response.initiativeBudget
      } catch (error) {
        if (isNotFound(error)) {
          this.npcs = []
          this.budget = EMPTY_BUDGET
          this.error = '居民服务还未连接，请检查开发服务是否运行当前分支'
        } else {
          this.error = (error as { message?: string }).message ?? '小镇居民暂时没能加载出来，请稍后再试'
        }
      } finally {
        this.loading = false
      }
    },

    /**
     * 护栏 A：报告一次 NPC 主动搭话，把全镇每日额度扣在服务端。plan.md 记过这个坑——额度只
     * 存在前端内存里的话，刷新一次就重置，护栏形同虚设。
     *
     * <p>返回的是服务端的权威值，调用方应当拿它去覆盖引擎里的乐观值。端点没上线（404）时
     * 保持本地值不动：宁可放宽，也不要因为后端还没部署就让 NPC 一句话都不说。
     */
    async consumeInitiative(): Promise<InitiativeBudget> {
      try {
        this.budget = await api.post<InitiativeBudget>('/town/initiative/consume', {})
      } catch (error) {
        if (!isNotFound(error)) throw error
      }
      return this.budget
    },

    /** Fetches `GET /town/npc/{code}/talking-points`. Tolerates a missing NPC or a
     * not-yet-deployed endpoint (both surface as 404) by returning an empty list. */
    async talkingPoints(code: string): Promise<NpcTalkingPoint[]> {
      try {
        const response = await api.get<TownNpcTalkingPointsResponse>(`/town/npc/${code}/talking-points`)
        return response.points
      } catch (error) {
        if (isNotFound(error)) return []
        throw error
      }
    },
  },
})
