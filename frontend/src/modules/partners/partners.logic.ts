/**
 * 成长伙伴领域的业务逻辑：从 PartnersView.vue 里抽出来，供整页和沉浸式面板共用。
 * 这里只处理数据与状态，不关心具体渲染。
 */
import { computed, reactive, ref } from 'vue'
import { api, type ApiError } from '../../shared/api/client'
import { notifyDataChanged } from '../../shared/data-sync'
import { useDesktopPetStore } from './desktop-pet.store'
import { findPetSpecies, petSpeciesOptions } from './pet-options'
import { petVariantStorageKey, variantCountFor, variantIndexForKind } from './pet-variants'
import type { InteractionResult, PartnerProfile, Pet, ShopItem } from './partner.types'

export type PetFormMode = 'create' | 'edit'

/* ---------------------------- 伙伴资料 + 商店 ---------------------------- */

export function usePartnerProfile() {
  const profile = ref<PartnerProfile | null>(null)
  const loading = ref(true)
  const busy = ref(false)
  const error = ref('')
  const feedback = ref('')
  let loadRequest = 0
  let selecting = false

  const selectedPet = computed(() => profile.value?.selectedPet ?? profile.value?.pets[0])
  const wallet = computed(() => profile.value?.wallet ?? { coinBalance: 0, lifetimeCoins: 0 })

  async function load(showLoading = true) {
    if (selecting) return
    const request = ++loadRequest
    if (showLoading) loading.value = true
    error.value = ''
    try {
      const loaded = await api.get<PartnerProfile>('/partners/profile')
      if (request !== loadRequest) return
      profile.value = loaded
      window.dispatchEvent(new CustomEvent('better-self:partners-updated'))
    } catch {
      if (request === loadRequest) error.value = '伙伴资料暂时无法加载'
    } finally {
      if (request === loadRequest) loading.value = false
    }
  }

  async function selectPet(pet: Pet) {
    if (pet.selected || busy.value) return false
    busy.value = selecting = true
    // A GET started before the selection must not restore the old selected pet.
    ++loadRequest
    loading.value = false
    error.value = feedback.value = ''
    try {
      const selected = await api.post<Pet>(`/partners/pets/${encodeURIComponent(pet.publicId)}/select`)
      if (profile.value) {
        profile.value = {
          ...profile.value,
          selectedPet: selected,
          pets: profile.value.pets.map(item => item.publicId === selected.publicId
            ? selected : { ...item, selected: false }),
        }
      }
      feedback.value = `已切换到 ${selected.name}`
      // Keep this store locked during synchronous data-sync callbacks; other views refresh normally.
      notifyDataChanged(['partners', 'profile'])
      window.dispatchEvent(new CustomEvent('better-self:partners-updated'))
      return true
    } catch {
      error.value = '伙伴切换失败，请重试'
      return false
    } finally {
      busy.value = selecting = false
    }
  }

  /** 互动一次（好感度 +N，每天首次才有奖励）；失败时把错误信息写进 error 并返回 null。 */
  async function interact(petPublicId: string): Promise<InteractionResult | null> {
    if (busy.value) return null
    busy.value = true
    error.value = ''
    try {
      const result = await api.post<InteractionResult>(`/partners/pets/${petPublicId}/interact`)
      await load(false)
      notifyDataChanged(['partners', 'profile'])
      return result
    } catch {
      error.value = '互动失败，请稍后重试'
      return null
    } finally {
      busy.value = false
    }
  }

  function itemDisabled(item: ShopItem) {
    return item.price > wallet.value.coinBalance || Boolean(item.speciesCode && item.speciesCode !== selectedPet.value?.speciesCode)
  }

  function itemStatus(item: ShopItem) {
    if (item.speciesCode && item.speciesCode !== selectedPet.value?.speciesCode) return `仅适合${item.speciesName}`
    if (item.price > wallet.value.coinBalance) return '金币不足'
    return '可以使用'
  }

  /** 购买并使用一件商品；金币不足或物种不符会先在本地拦截，服务端仍会二次校验。 */
  async function purchase(item: ShopItem): Promise<{ item: ShopItem; pet: Pet } | null> {
    if (!selectedPet.value || busy.value) return null
    if (itemDisabled(item)) {
      error.value = item.price > wallet.value.coinBalance ? '金币不足，完成今日任务可以获得金币' : `${item.name} 仅适合${item.speciesName}，换一件试试`
      return null
    }
    error.value = ''
    busy.value = true
    try {
      const result = await api.post<{ item: ShopItem; pet: Pet }>('/partners/purchase', { petPublicId: selectedPet.value.publicId, itemPublicId: item.publicId })
      feedback.value = `${result.item.name} 已使用，${result.pet.name} 好感度 +${result.item.affectionGain}`
      await load(false)
      notifyDataChanged(['partners', 'profile'])
      return result
    } catch (err) {
      error.value = (err as Partial<ApiError> | null)?.code === 'INSUFFICIENT_COINS' ? '金币不足，完成今日任务可以获得金币' : '购买失败，请稍后重试'
      return null
    } finally {
      busy.value = false
    }
  }

  /** 创建新伙伴或修改现有伙伴的名字/种类/颜色。 */
  async function savePet(mode: PetFormMode, form: { speciesCode: string; name: string; breed: string; furColor: string }) {
    if (busy.value) return false
    busy.value = true
    error.value = ''
    try {
      if (mode === 'edit' && selectedPet.value) {
        await api.patch(`/partners/pets/${selectedPet.value.publicId}`, { name: form.name, breed: form.breed, furColor: form.furColor })
        try {
          window.localStorage?.removeItem(petVariantStorageKey(selectedPet.value.publicId))
        } catch {
          // 忽略不可用的浏览器存储，服务端数据已经保存。
        }
        feedback.value = '伙伴资料已更新'
      } else {
        const pet = await api.post<Pet>('/partners/pets', {
          speciesCode: form.speciesCode,
          name: form.name,
          breed: form.breed,
          furColor: form.furColor,
        })
        try {
          window.localStorage?.setItem(petVariantStorageKey(pet.publicId), String(variantIndexForKind(pet.speciesCode, pet.breed)))
        } catch {
          // 忽略不可用的浏览器存储。
        }
        await api.post(`/partners/pets/${pet.publicId}/select`)
        feedback.value = '新的伙伴已经加入'
      }
      await load(false)
      notifyDataChanged(['partners', 'profile'])
      return true
    } catch {
      error.value = '伙伴资料未保存，请检查名称、种类和颜色'
      return false
    } finally {
      busy.value = false
    }
  }

  return { profile, loading, busy, error, feedback, selectedPet, wallet, load, selectPet, interact, purchase, itemDisabled, itemStatus, savePet }
}

/* ---------------------------- 创建/编辑伙伴的表单 ---------------------------- */

export function usePetForm() {
  const form = reactive({ speciesCode: 'CAT', name: '小橘', breed: '中华田园猫', furColor: '橘白' })

  const formSpecies = computed(() => findPetSpecies(form.speciesCode))
  const kindOptions = computed(() => {
    const options = formSpecies.value?.kinds ?? []
    if (!form.breed || options.some(option => option.value === form.breed)) return options
    return [{ value: form.breed, defaultColor: form.furColor }, ...options]
  })

  function syncFrom(pet?: Pet) {
    if (!pet) return
    form.speciesCode = pet.speciesCode
    form.name = pet.name
    form.breed = pet.breed
    form.furColor = pet.furColor
  }

  function resetForCreate() {
    const species = petSpeciesOptions[0]
    form.speciesCode = species.code
    form.name = ''
    form.breed = species.kinds[0].value
    form.furColor = species.kinds[0].defaultColor
  }

  function chooseSpecies(code: string) {
    const species = findPetSpecies(code)
    if (!species) return
    form.speciesCode = species.code
    form.breed = species.kinds[0].value
    form.furColor = species.kinds[0].defaultColor
  }

  function chooseKind() {
    const kind = formSpecies.value?.kinds.find(option => option.value === form.breed)
    if (kind) form.furColor = kind.defaultColor
  }

  function speciesGlyph(code: string) {
    return findPetSpecies(code)?.glyph ?? '伴'
  }

  return { form, formSpecies, kindOptions, syncFrom, resetForCreate, chooseSpecies, chooseKind, speciesGlyph }
}

/* ---------------------------- 皮肤变体（同一物种的不同外观） ---------------------------- */

export function usePetVariant() {
  const variantIndex = ref(0)

  function resolveVariantIndex(pet: Pet) {
    try {
      const saved = Number(window.localStorage?.getItem(petVariantStorageKey(pet.publicId)) ?? '')
      if (Number.isInteger(saved) && saved >= 0) return saved
    } catch {
      // 部分内嵌浏览器禁用了 localStorage，退回确定性默认值。
    }
    return variantIndexForKind(pet.speciesCode, pet.breed)
  }

  function syncFor(pet?: Pet) {
    if (!pet) return
    variantIndex.value = resolveVariantIndex(pet)
  }

  function select(pet: Pet | undefined, index: number) {
    if (!pet) return
    const count = variantCountFor(pet.speciesCode)
    if (count < 2) return
    const next = ((index % count) + count) % count
    variantIndex.value = next
    try {
      window.localStorage?.setItem(petVariantStorageKey(pet.publicId), String(next))
    } catch {
      // 该选择仅在本次会话内生效。
    }
  }

  return { variantIndex, syncFor, select }
}

/* ---------------------------- 桌宠 ---------------------------- */

export function useDesktopCompanion() {
  const store = useDesktopPetStore()
  store.hydrate()
  const isDesktopApp = Boolean(window.betterSelfDesktop?.isDesktopApp)

  function isDesktopPet(pet?: Pet | null) {
    return Boolean(pet && store.petPublicId === pet.publicId)
  }

  /** 切换桌宠状态，返回一句可以直接展示的中文反馈。 */
  function toggle(pet: Pet | null | undefined): string {
    if (!pet) return ''
    if (isDesktopPet(pet)) {
      if (isDesktopApp) {
        window.betterSelfDesktop?.showPet()
        return `${pet.name} 的桌宠窗口已打开`
      }
      store.clear()
      return '桌宠已取消'
    }
    store.setPet(pet.publicId)
    if (isDesktopApp) window.betterSelfDesktop?.showPet()
    return `${pet.name} 已成为你的桌宠`
  }

  return { store, isDesktopApp, isDesktopPet, toggle }
}
