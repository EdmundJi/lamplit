import { computed, onBeforeUnmount, ref, watch, type Ref } from 'vue'
import { onDataChanged } from '../../shared/data-sync'
import type { TownGame } from './town.engine'
import type { WorldPanelKey } from './immersive/panel.types'
import { useTownCompanionStore } from './town-companion.store'

/** One lifecycle shared by the ordinary page and immersive shell. */
export function useTownCompanionLife(options: {
  userId: Ref<string>
  room: Ref<string | null>
  game: () => TownGame | null
  openPanel: (panel: WorldPanelKey) => void
  feedback: (text: string) => void
}) {
  const companion = useTownCompanionStore()
  const inPark = ref(false)
  const inHome = computed(() => options.room.value === 'home')
  let disposed = false
  const sync = () => options.game()?.setCompanionState?.({ pet: companion.pet, mode: companion.mode })
  const refresh = async () => {
    if (disposed) return
    if (!options.userId.value) { companion.clearUser(); sync(); return }
    await companion.load(options.userId.value)
    if (!disposed) sync()
  }
  watch(options.userId, () => { void refresh() }, { immediate: true })
  watch(() => [companion.pet, companion.mode] as const, sync, { deep: true })
  watch(options.room, room => {
    inPark.value = false
    if (room === 'home') companion.endWalk()
  })
  const stopSync = onDataChanged('partners', () => { void refresh() })
  onBeforeUnmount(() => { disposed = true; stopSync() })

  async function act(action: 'walk' | 'stay' | 'stroke') {
    if (action === 'stay') { companion.endWalk(); options.feedback('牵引绳收好了，今天就在家歇一歇。'); return }
    if (!companion.pet || companion.loading) await refresh()
    if (disposed) return
    if (companion.error) { options.feedback(companion.error); return }
    if (!companion.pet) { options.openPanel('partners'); return }
    if (action === 'stroke') {
      if (await companion.stroke()) options.feedback(`${companion.pet?.name ?? '小家伙'}靠近了一点。`)
      else if (companion.error) options.feedback(companion.error)
      return
    }
    if (companion.startWalk()) {
      if (inHome.value) options.game()?.exitRoom()
      options.feedback(`带上${companion.pet.name}，出去走走。`)
    }
  }
  return {
    companion, inPark, inHome, sync, refresh, act,
    onModeChange: (mode: 'following' | 'roaming') => { companion.setRoaming(mode === 'roaming') },
    onPlaceChange: (value: boolean) => { inPark.value = value },
    onInteract: () => { void act('stroke') },
  }
}
