import { api } from '../../shared/api/client'
import type { IntentInput, Snapshot } from './companion.types'
const root = '/town/companion'
export const companionApi = {
  load: () => api.get<Snapshot>(root),
  join: (name: string, timezone: string) => api.post<Snapshot>(`${root}/join`, { name, timezone }),
  advance: () => api.post<Snapshot>(`${root}/advance`),
  intend: (input: IntentInput) => api.post<Snapshot>(`${root}/intents`, input),
  cancel: (id: string) => api.delete<Snapshot>(`${root}/intents/${encodeURIComponent(id)}`),
}
