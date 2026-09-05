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
        this.npcs = response.npcs
        this.budget = response.initiativeBudget
      } catch (error) {
        if (isNotFound(error)) {
          this.npcs = []
          this.budget = EMPTY_BUDGET
        } else {
          this.error = (error as { message?: string }).message ?? '小镇居民暂时没能加载出来，请稍后再试'
        }
      } finally {
        this.loading = false
      }
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
