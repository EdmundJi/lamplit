import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useAuthStore } from '../auth/auth.store'
import type { SceneConversation, SceneObject, SceneProject, SceneResident } from './companion-scene'
import type { World } from './companion.types'

/** Keep unchanged drawing inputs referentially stable across saved-world refreshes. */
export function stableProjection<T>(read: () => T) {
  let signature = ''
  return computed<T>((previous) => {
    const next = read()
    const nextSignature = JSON.stringify(next)
    if (previous !== undefined && nextSignature === signature) return previous
    signature = nextSignature
    return next
  })
}

/** A resident's public role, falling back to their own self-description when the backend actor
 * record has not been given one yet. Shared by CompanionView's person panel and the scene
 * projection below so both agree on the same label. */
export function residentRoleFor(world: World | null | undefined, id: string) {
  const actor = world?.residents.find(item => item.id === id)
  const state = world?.residentStates?.find(item => item.id === id)
  return actor?.role || state?.occupation || '小街邻居'
}

/**
 * The CompanionScene input computeds, built once from a `world` getter and shared by every
 * consumer that mounts a scene - the always-on street strip and the full /town page - instead of
 * each recomputing (and re-diffing via stableProjection) its own copy of the same projection.
 */
export function sceneProjection(world: () => World | null) {
  const sceneActors = stableProjection<SceneResident[]>(() => {
    const w = world()
    if (!w) return []
    return [w.avatar, ...w.residents].map(actor => {
      const state = w.residentStates?.find(item => item.id === actor.id)
      const plan = state?.plan
      return { id: actor.id, name: actor.name, role: actor.id === w.avatar.id ? 'user' : residentRoleFor(w, actor.id), location: actor.place, action: actor.label, activity: actor.activity, objectKind: w.projects?.find(project => project.id === plan?.targetId)?.objectKind, destination: plan?.action === 'travel' ? plan.place : undefined, positionId: state?.positionId }
    })
  })
  const sceneProjects = stableProjection<SceneProject[]>(() => (world()?.projects || []).map(item => ({ id: item.id, title: item.title, place: item.place, status: item.status, progress: item.progress, objectKind: item.objectKind })))
  const sceneConversations = stableProjection<SceneConversation[]>(() => (world()?.conversations || []).filter(item => item.status === 'active').map(item => ({ id: item.id, place: item.place, status: item.status, topicId: item.topicId, participantIds: item.participantIds, turns: item.turns.slice(-2) })))
  const sceneObjects = stableProjection<SceneObject[]>(() => (world()?.objects || []).map(item => ({ id: item.id, kind: item.kind, place: item.place, label: item.label, state: item.state, projectId: item.projectId })))
  return { sceneActors, sceneProjects, sceneConversations, sceneObjects }
}

/**
 * A shared 1s clock: `minutes` (local time-of-day in the world's own timezone) drives the scene's
 * day/night lighting and rain/shade, in both the docked strip and /town, without either owning the
 * other's ticker. `worldTimezone` reads the current world's saved timezone; falls back to the
 * signed-in account's timezone, then the browser's own.
 */
export function useTownClock(worldTimezone: () => string | undefined | null) {
  const now = ref(Date.now())
  let ticker: ReturnType<typeof setInterval> | undefined
  onMounted(() => { ticker = setInterval(() => { now.value = Date.now() }, 1000) })
  onBeforeUnmount(() => clearInterval(ticker))
  const auth = useAuthStore()
  const timezone = computed(() => worldTimezone() || auth.user?.timezone || Intl.DateTimeFormat().resolvedOptions().timeZone)
  const minutes = computed(() => {
    const parts = new Intl.DateTimeFormat('en-GB', { timeZone: timezone.value, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(new Date(now.value))
    return Number(parts.find(p => p.type === 'hour')?.value || 0) * 60 + Number(parts.find(p => p.type === 'minute')?.value || 0)
  })
  return { now, timezone, minutes }
}
