<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { Coins, Gamepad2, HandHeart, Heart, HeartHandshake, MessageCircle, MonitorOff, MonitorUp, PartyPopper, Pencil, Plus, ShoppingBag, Smartphone, Sparkles, X } from 'lucide-vue-next'
import { api } from '../../shared/api/client'
import RivePet from './RivePet.vue'
import { useDesktopPetStore } from './desktop-pet.store'
import { randomPetDialogue } from './pet-dialogues'
import { findPetSpecies, petSpeciesOptions, type PetInteractionOption, type PetReaction } from './pet-options'
import { petVariantStorageKey, variantCountFor, variantIndexForKind } from './pet-variants'
import type { InteractionResult, PartnerProfile, Pet, ShopItem } from './partner.types'

type DialogueMessage = { text: string; actionLabel: string; rewarded: boolean; affectionDelta: number }
type ShopFilter = 'ALL' | ShopItem['itemType']

const profile = ref<PartnerProfile | null>(null)
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const feedback = ref('')
const panel = ref<'create' | 'edit' | null>(null)
const petRenderer = ref<InstanceType<typeof RivePet> | null>(null)
const dialogue = ref<DialogueMessage | null>(null)
const lastInteraction = ref<PetReaction | null>(null)
const variantIndex = ref(0)
const shopFilter = ref<ShopFilter>('ALL')
const desktopPet = useDesktopPetStore()
const isDesktopCompanion = Boolean(window.betterSelfDesktop?.isDesktopApp)
let dialogueTimer: number | undefined

desktopPet.hydrate()

const shopItemImages: Record<string, string> = {
  'pet-food-salmon-bento': '/assets/shop/salmon-bento.svg',
  'pet-food-bone-cookie': '/assets/shop/bone-cookie.svg',
  'pet-food-seed-cup': '/assets/shop/seed-cup.svg',
  'pet-food-mouse-jelly': '/assets/shop/moon-jelly.svg',
  'pet-food-veggie-bowl': '/assets/shop/veggie-bowl.svg',
  'pet-decor-ribbon': '/assets/shop/ribbon.svg',
  'pet-decor-cushion': '/assets/shop/cushion.svg',
  'pet-decor-plant': '/assets/shop/plant.svg',
}

const form = reactive({ speciesCode: 'CAT', name: '小橘', breed: '中华田园猫', furColor: '橘白' })
const selectedPet = computed(() => profile.value?.selectedPet ?? profile.value?.pets[0])
const wallet = computed(() => profile.value?.wallet ?? { coinBalance: 0, lifetimeCoins: 0 })
const isDesktopPet = computed(() => Boolean(selectedPet.value && desktopPet.petPublicId === selectedPet.value.publicId))
const formSpecies = computed(() => findPetSpecies(form.speciesCode))
const kindOptions = computed(() => {
  const options = formSpecies.value?.kinds ?? []
  if (!form.breed || options.some(option => option.value === form.breed)) return options
  return [{ value: form.breed, defaultColor: form.furColor }, ...options]
})
const currentInteractions = computed(() => findPetSpecies(selectedPet.value?.speciesCode ?? '')?.interactions ?? [])
const interactionIcons = {
  greet: HandHeart,
  play: Gamepad2,
  comfort: HeartHandshake,
  celebrate: PartyPopper,
}
const progressPercent = computed(() => {
  const pet = selectedPet.value
  if (!pet) return 0
  return Math.min(100, Math.round((pet.affection / pet.nextLevelAffection) * 100))
})
const visibleItems = computed(() => {
  const items = profile.value?.shopItems ?? []
  return shopFilter.value === 'ALL' ? items : items.filter(item => item.itemType === shopFilter.value)
})

async function load(showLoading = true) {
  if (showLoading) loading.value = true
  error.value = ''
  try {
    profile.value = await api.get<PartnerProfile>('/partners/profile')
    syncForm(profile.value.selectedPet)
    window.dispatchEvent(new CustomEvent('better-self:partners-updated'))
  } catch {
    error.value = '伙伴资料暂时无法加载'
  } finally {
    if (showLoading) loading.value = false
  }
}

function syncForm(pet?: Pet) {
  if (!pet) return
  form.speciesCode = pet.speciesCode
  form.name = pet.name
  form.breed = pet.breed
  form.furColor = pet.furColor
}

function resolveVariantIndex(pet: Pet) {
  const saved = Number(window.localStorage.getItem(petVariantStorageKey(pet.publicId)) ?? '')
  if (Number.isInteger(saved) && saved >= 0) return saved
  return variantIndexForKind(pet.speciesCode, pet.breed)
}

function selectVariant(index: number) {
  const pet = selectedPet.value
  if (!pet || busy.value) return
  const count = variantCountFor(pet.speciesCode)
  if (count < 2) return
  const next = ((index % count) + count) % count
  variantIndex.value = next
  window.localStorage.setItem(petVariantStorageKey(pet.publicId), String(next))
}

watch(selectedPet, pet => {
  if (!pet) return
  variantIndex.value = resolveVariantIndex(pet)
}, { immediate: true })

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

async function savePet() {
  busy.value = true
  error.value = ''
  try {
    if (panel.value === 'edit' && selectedPet.value) {
      await api.patch(`/partners/pets/${selectedPet.value.publicId}`, { name: form.name, breed: form.breed, furColor: form.furColor })
      window.localStorage.removeItem(petVariantStorageKey(selectedPet.value.publicId))
      feedback.value = '伙伴资料已更新'
    } else {
      const pet = await api.post<Pet>('/partners/pets', {
        speciesCode: form.speciesCode,
        name: form.name,
        breed: form.breed,
        furColor: form.furColor,
      })
      window.localStorage.setItem(petVariantStorageKey(pet.publicId), String(variantIndexForKind(pet.speciesCode, pet.breed)))
      await api.post(`/partners/pets/${pet.publicId}/select`)
      feedback.value = '新的伙伴已经加入'
    }
    panel.value = null
    await load(false)
  } catch {
    error.value = '伙伴资料未保存，请检查名称、种类和颜色'
  } finally {
    busy.value = false
  }
}

async function selectPet(pet: Pet) {
  if (pet.selected) return
  closeDialogue()
  lastInteraction.value = null
  await api.post(`/partners/pets/${pet.publicId}/select`)
  feedback.value = `已切换到 ${pet.name}`
  await load(false)
}

async function interact(action: PetInteractionOption) {
  if (!selectedPet.value || !action || busy.value) return
  busy.value = true
  error.value = ''
  feedback.value = ''
  lastInteraction.value = action.action
  petRenderer.value?.react(action.action)
  try {
    const result = await api.post<InteractionResult>(`/partners/pets/${selectedPet.value.publicId}/interact`)
    showDialogue(result, action.label)
    await load(false)
  } catch {
    error.value = '互动失败，请稍后重试'
  } finally {
    busy.value = false
  }
}

function showDialogue(result: InteractionResult, actionLabel: string) {
  dialogue.value = {
    text: randomPetDialogue(),
    actionLabel,
    rewarded: result.rewarded,
    affectionDelta: result.affectionDelta,
  }
  window.clearTimeout(dialogueTimer)
  dialogueTimer = window.setTimeout(closeDialogue, 9000)
}

function closeDialogue() {
  window.clearTimeout(dialogueTimer)
  dialogue.value = null
}

async function purchase(item: ShopItem) {
  if (!selectedPet.value || itemDisabled(item)) return
  try {
    const result = await api.post<any>('/partners/purchase', { petPublicId: selectedPet.value.publicId, itemPublicId: item.publicId })
    petRenderer.value?.react(item.itemType === 'FOOD' ? 'feed' : 'celebrate')
    feedback.value = `${result.item.name} 已使用，${result.pet.name} 好感度 +${result.item.affectionGain}`
    await load(false)
  } catch (err: any) {
    error.value = err?.code === 'INSUFFICIENT_COINS' ? '金币不足，完成今日任务可以获得金币' : '购买失败，请稍后重试'
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

function openCreate() {
  const species = petSpeciesOptions[0]
  form.speciesCode = species.code
  form.name = ''
  form.breed = species.kinds[0].value
  form.furColor = species.kinds[0].defaultColor
  panel.value = 'create'
}

function openEdit() {
  syncForm(selectedPet.value)
  panel.value = 'edit'
}

function toggleDesktopPet() {
  const pet = selectedPet.value
  if (!pet) return
  if (isDesktopPet.value) {
    if (isDesktopCompanion) {
      window.betterSelfDesktop?.showPet()
      return
    }
    desktopPet.clear()
    feedback.value = '桌宠已取消'
    return
  }
  desktopPet.setPet(pet.publicId)
  feedback.value = `${pet.name} 已成为你的桌宠`
  if (isDesktopCompanion) window.betterSelfDesktop?.showPet()
}

onMounted(load)
onBeforeUnmount(() => window.clearTimeout(dialogueTimer))
</script>

<template>
  <section class="page partners-page">
    <header class="page-head partner-head">
      <div>
        <p class="eyebrow">伙伴小屋</p>
        <h1>今天也来陪陪它</h1>
      </div>
      <div class="wallet-pill" aria-label="金币余额">
        <span class="coin-mark" aria-hidden="true"><Coins :size="18" /></span>
        <span class="wallet-copy"><small>我的金币</small><strong>{{ wallet.coinBalance }}</strong></span>
      </div>
    </header>

    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-if="feedback" class="feedback-banner partner-feedback" data-tone="support" role="status" aria-live="polite">
      <Sparkles :size="19" />
      <p>{{ feedback }}</p>
    </div>

    <p v-if="loading" class="empty">正在唤醒伙伴…</p>
    <template v-else-if="profile && selectedPet">
      <section class="partner-stage" aria-labelledby="partner-title">
        <div class="home-scene">
          <span class="scene-label"><Heart :size="15" fill="currentColor" />今日陪伴</span>
          <div class="pet-area">
            <RivePet
              ref="petRenderer"
              :key="selectedPet.publicId"
              :species-code="selectedPet.speciesCode"
              :name="selectedPet.name"
              :variant-index="variantIndex"
              :show-variant-switcher="true"
              @select-variant="selectVariant"
            />
            <Transition name="dialogue">
              <aside v-if="dialogue" class="dialogue-bar" role="status" aria-live="polite">
                <div class="dialogue-copy">
                  <p>
                    <span class="dialogue-icon" aria-hidden="true"><MessageCircle :size="15" /></span>
                    <strong>{{ selectedPet.name }}</strong>
                    <span class="dialogue-action">{{ dialogue.actionLabel }}</span>
                    <span class="dialogue-reward" :data-rewarded="dialogue.rewarded">
                      {{ dialogue.rewarded ? `首次互动 +${dialogue.affectionDelta}` : '今日奖励已领取' }}
                    </span>
                  </p>
                  <blockquote>{{ dialogue.text }}</blockquote>
                </div>
                <button class="dialogue-close" type="button" aria-label="关闭对话" @click="closeDialogue">
                  <X :size="16" />
                </button>
              </aside>
            </Transition>
          </div>
        </div>

        <div class="pet-panel">
          <div class="identity-line">
            <span class="species-token" :data-species="selectedPet.speciesCode">{{ selectedPet.speciesName }}</span>
            <span>{{ selectedPet.breed }} · {{ selectedPet.furColor }}</span>
          </div>
          <div class="name-row">
            <h2 id="partner-title">{{ selectedPet.name }}</h2>
            <strong class="level-badge">LV.{{ selectedPet.level }}</strong>
          </div>
          <p class="companion-copy">正在你的小屋里放松，看到你来会很开心。</p>
          <div class="affection-panel">
            <div class="level-row">
              <span><Heart :size="15" fill="currentColor" />好感度</span>
              <strong>{{ selectedPet.affection }} / {{ selectedPet.nextLevelAffection }}</strong>
            </div>
            <div class="progress" aria-label="好感度进度"><span :style="{ width: `${progressPercent}%` }" /></div>
            <small>每日首次互动可获得 2 点好感度</small>
          </div>
          <div class="interaction-panel">
            <span class="interaction-label">互动方式</span>
            <div class="interaction-grid" aria-label="选择互动方式">
              <button
                v-for="action in currentInteractions"
                :key="action.action"
                type="button"
                class="interaction-option"
                :class="{ active: lastInteraction === action.action }"
                :disabled="busy"
                @click="interact(action)"
              >
                <component :is="interactionIcons[action.action]" :size="17" />
                <span>{{ action.label }}</span>
              </button>
            </div>
          </div>
          <div class="stage-actions">
            <button class="secondary" type="button" @click="openEdit"><Pencil :size="17" />资料</button>
            <button class="secondary" type="button" @click="openCreate"><Plus :size="17" />新伙伴</button>
          </div>
        </div>
      </section>

      <section class="desktop-pet-setting band" aria-labelledby="desktop-pet-title">
        <div class="desktop-pet-control">
          <span class="desktop-pet-icon" aria-hidden="true"><MonitorUp :size="22" /></span>
          <div>
            <p class="eyebrow">PC 桌宠</p>
            <h2 id="desktop-pet-title">让{{ selectedPet.name }}陪在桌面一角</h2>
            <p>{{ isDesktopPet ? '当前正在使用这位伙伴。桌宠会跟随你浏览今日、目标和洞察页面。' : '一次只能设置一位桌宠，新的选择会自动替换当前伙伴。' }}</p>
          </div>
          <button type="button" :class="isDesktopPet ? 'secondary' : 'interact-button'" @click="toggleDesktopPet">
            <MonitorUp v-if="isDesktopPet && isDesktopCompanion" :size="17" />
            <MonitorOff v-else-if="isDesktopPet" :size="17" />
            <MonitorUp v-else :size="17" />
            {{ isDesktopPet ? (isDesktopCompanion ? '打开桌宠' : '取消桌宠') : '设为桌宠' }}
          </button>
        </div>
        <div class="desktop-pet-mobile">
          <Smartphone :size="21" />
          <div><strong>桌宠功能正在移动端开发中</strong><span>目前请在电脑端设置和使用桌宠。</span></div>
        </div>
      </section>

      <form v-if="panel" class="band stack pet-form" @submit.prevent="savePet">
        <div class="form-head">
          <div>
            <p class="eyebrow">{{ panel === 'edit' ? '自定义伙伴' : '选择伙伴' }}</p>
            <h2>{{ panel === 'edit' ? '修改名字、种类和颜色' : '创建新的 2D 小动物' }}</h2>
          </div>
          <button class="dialogue-close" type="button" aria-label="关闭伙伴资料" @click="panel = null"><X :size="18" /></button>
        </div>
        <div v-if="panel === 'create'" class="species-grid" aria-label="选择动物">
          <button v-for="species in petSpeciesOptions" :key="species.code" type="button" :class="{ active: form.speciesCode === species.code }" :data-species="species.code" :aria-pressed="form.speciesCode === species.code" @click="chooseSpecies(species.code)">
            <span class="species-glyph">{{ species.glyph }}</span>
            <span>{{ species.label }}</span>
          </button>
        </div>
        <div class="split">
          <div class="field"><label for="pet-name">名字</label><input id="pet-name" v-model="form.name" required maxlength="40"></div>
          <div class="field">
            <label for="pet-kind">种类</label>
            <select id="pet-kind" v-model="form.breed" required @change="chooseKind">
              <option v-for="kind in kindOptions" :key="kind.value" :value="kind.value">{{ kind.value }}</option>
            </select>
          </div>
        </div>
        <div class="field"><label for="pet-color">外观颜色</label><input id="pet-color" v-model="form.furColor" required maxlength="40"></div>
        <div class="actions">
          <button class="interact-button" :disabled="busy">{{ panel === 'edit' ? '保存资料' : '创建伙伴' }}</button>
          <button class="secondary" type="button" @click="panel = null">取消</button>
        </div>
      </form>

      <section class="companion-roster band" aria-labelledby="roster-title">
        <div class="section-head">
          <div><p class="eyebrow">伙伴列表</p><h2 id="roster-title">今天想陪谁</h2></div>
          <span class="section-count">{{ profile.pets.length }} 位伙伴</span>
        </div>
        <div class="pet-list">
          <button v-for="pet in profile.pets" :key="pet.publicId" type="button" class="pet-card" :class="{ selected: pet.selected }" :data-species="pet.speciesCode" :aria-pressed="pet.selected" @click="selectPet(pet)">
            <span class="pet-avatar" aria-hidden="true">{{ speciesGlyph(pet.speciesCode) }}</span>
            <span class="pet-card-copy">
              <strong>{{ pet.name }}</strong>
              <small>{{ pet.breed }} · {{ pet.furColor }}</small>
            </span>
            <span class="pet-level">LV.{{ pet.level }}</span>
          </button>
        </div>
      </section>

      <section class="shop band" aria-labelledby="shop-title">
        <div class="section-head shop-head">
          <div><p class="eyebrow">小屋商店</p><h2 id="shop-title">带一份小礼物回去</h2></div>
          <div class="shop-tools">
            <ShoppingBag :size="20" aria-hidden="true" />
            <div class="shop-tabs" role="group" aria-label="筛选商品">
              <button type="button" :aria-pressed="shopFilter === 'ALL'" @click="shopFilter = 'ALL'">全部</button>
              <button type="button" :aria-pressed="shopFilter === 'FOOD'" @click="shopFilter = 'FOOD'">食物</button>
              <button type="button" :aria-pressed="shopFilter === 'DECOR'" @click="shopFilter = 'DECOR'">装饰</button>
            </div>
          </div>
        </div>
        <div class="shop-list">
          <button v-for="item in visibleItems" :key="item.publicId" type="button" class="shop-item" :data-kind="item.itemType" :disabled="itemDisabled(item)" @click="purchase(item)">
            <span class="shop-art" aria-hidden="true">
              <img :src="shopItemImages[item.publicId]" alt="">
            </span>
            <span class="shop-copy">
              <span class="item-type">{{ item.itemType === 'FOOD' ? '食物' : '装饰' }} · {{ item.speciesName }}</span>
              <strong>{{ item.name }}</strong>
              <small>{{ item.description }}</small>
              <span class="item-meta"><b><Coins :size="14" />{{ item.price }}</b><em>+{{ item.affectionGain }} 好感</em></span>
              <span class="item-status">{{ itemStatus(item) }}</span>
            </span>
          </button>
        </div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.partners-page {
  --pet-paper: #fffdf8;
  --pet-cream: #fff7e8;
  --pet-apricot: #f3a65a;
  --pet-coral: #df6b57;
  --pet-coral-strong: #bd4f3f;
  --pet-gold: #e3af3d;
  --pet-mint: #6eaa8c;
  --pet-sky: #6f91bc;
  --pet-ink: #3d302a;
  --pet-muted: #78675f;
  --pet-line: #ead5bd;
  --pet-scene: #fff7e8;
  --pet-shadow: 0 16px 34px rgb(104 66 43 / 13%);
  color: var(--pet-ink);
}

.partners-page .eyebrow { color: var(--pet-coral-strong); letter-spacing: 0; }
.partner-head { align-items: center; }
.partner-head h1 { max-width: 16ch; font-family: ui-rounded, "SF Pro Rounded", "PingFang SC", sans-serif; color: var(--pet-ink); }

.wallet-pill { min-height: 54px; display: inline-flex; align-items: center; gap: 10px; padding: 6px 14px 6px 7px; border: 1px solid color-mix(in srgb, var(--pet-gold) 58%, var(--pet-line)); border-radius: 8px; background: #fff8df; color: #8c6113; box-shadow: 0 8px 20px rgb(139 96 25 / 10%); }
.coin-mark { width: 38px; height: 38px; display: grid; place-items: center; border-radius: 50%; background: #f4c85c; color: #754c08; box-shadow: inset 0 -3px 0 rgb(132 83 8 / 14%); }
.wallet-copy { display: grid; grid-template-columns: auto auto; align-items: baseline; gap: 0 8px; }
.wallet-copy small { grid-column: 1 / -1; color: #927446; font-size: 11px; }
.wallet-copy strong { font-size: 20px; line-height: 1; }

.partner-feedback { border-color: var(--pet-line); background: #fff3dc; color: var(--pet-ink); }
.partner-feedback svg { color: var(--pet-coral); }

.partner-stage { display: grid; grid-template-columns: minmax(420px, 1.18fr) minmax(290px, .82fr); gap: 26px; align-items: center; margin-bottom: 12px; padding: 28px 0; border-top: 1px solid var(--pet-line); border-bottom: 1px solid var(--pet-line); }
.home-scene { position: relative; min-width: 0; padding: 9px; border: 1px solid #e4c5a5; border-radius: 104px 104px 8px 8px; background: #f4c77d; box-shadow: var(--pet-shadow); }
.scene-label { position: absolute; z-index: 5; top: 19px; left: 22px; min-height: 30px; display: inline-flex; align-items: center; gap: 7px; padding: 0 10px; border: 1px solid rgb(134 75 46 / 18%); border-radius: 999px; background: rgb(255 250 240 / 92%); color: var(--pet-coral-strong); font-size: 12px; font-weight: 800; box-shadow: 0 6px 16px rgb(86 51 34 / 10%); }
.pet-area { position: relative; min-height: 400px; overflow: hidden; border-radius: 94px 94px 5px 5px; background: var(--pet-scene); }

.pet-panel { min-width: 0; display: grid; gap: 16px; padding-right: 8px; }
.identity-line { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 10px; color: var(--pet-muted); font-size: 13px; }
.species-token { min-height: 28px; display: inline-flex; align-items: center; padding: 0 10px; border-radius: 999px; background: #e9f4ec; color: #3d795c; font-weight: 800; }
.name-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.pet-panel h2, .section-head h2, .pet-form h2, .desktop-pet-control h2 { margin: 0; font-family: ui-rounded, "SF Pro Rounded", "PingFang SC", sans-serif; font-size: 22px; letter-spacing: 0; }
.pet-panel h2 { min-width: 0; overflow-wrap: anywhere; font-size: 34px; line-height: 1.1; }
.level-badge { flex: 0 0 auto; min-height: 34px; display: inline-flex; align-items: center; padding: 0 10px; border: 1px solid #9bb9dd; border-radius: 6px; background: #eef5ff; color: #436b9a; font-size: 13px; }
.companion-copy { margin: -3px 0 0; color: var(--pet-muted); line-height: 1.65; }
.affection-panel { display: grid; gap: 10px; padding: 14px; border: 1px solid var(--pet-line); border-radius: 8px; background: var(--pet-paper); box-shadow: 0 7px 18px rgb(104 66 43 / 7%); }
.level-row { display: flex; justify-content: space-between; gap: 12px; color: var(--pet-muted); font-size: 14px; }
.level-row span { display: inline-flex; align-items: center; gap: 6px; }
.level-row svg { color: var(--pet-coral); }
.level-row strong { color: var(--pet-coral-strong); }
.affection-panel small { color: var(--pet-muted); font-size: 11px; }
.partners-page .progress { height: 10px; background: #f2e3d3; }
.partners-page .progress > span { background: linear-gradient(90deg, var(--pet-coral), var(--pet-gold), var(--pet-mint)); }
.interaction-panel { display: grid; gap: 8px; }
.interaction-label { color: var(--pet-muted); font-size: 12px; font-weight: 800; }
.interaction-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.interaction-option { min-width: 0; min-height: 46px; display: inline-flex; align-items: center; justify-content: flex-start; gap: 8px; padding: 0 11px; border: 1px solid var(--pet-line); background: var(--pet-paper); color: var(--pet-ink); text-align: left; }
.interaction-option svg { flex: 0 0 auto; color: var(--pet-coral-strong); }
.interaction-option span { min-width: 0; overflow-wrap: anywhere; }
.interaction-option:hover, .interaction-option.active { border-color: var(--pet-coral); background: #fff0e6; color: var(--pet-coral-strong); }
.interaction-option.active { box-shadow: inset 0 -3px 0 var(--pet-coral); }
.stage-actions { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 9px; }
.interact-button { min-height: var(--control); display: inline-flex; align-items: center; justify-content: center; gap: 7px; padding: 0 16px; border-color: var(--pet-coral-strong); background: var(--pet-coral); color: white; box-shadow: 0 9px 18px rgb(178 74 58 / 18%); }
.interact-button:hover { background: var(--pet-coral-strong); }
.partners-page .secondary { border-color: var(--pet-line); background: var(--pet-paper); color: var(--pet-ink); }

.desktop-pet-setting { padding: 18px 0; }
.desktop-pet-control { display: grid; grid-template-columns: 46px minmax(0, 1fr) auto; align-items: center; gap: 13px; }
.desktop-pet-icon { width: 46px; height: 46px; display: grid; place-items: center; border: 1px solid #a9c6b6; border-radius: 8px; background: #e7f2eb; color: #42745a; }
.desktop-pet-control .eyebrow { margin-bottom: 3px; }
.desktop-pet-control h2 { font-size: 18px; }
.desktop-pet-control p:last-child { margin: 5px 0 0; color: var(--pet-muted); font-size: 12px; line-height: 1.55; }
.desktop-pet-control > button { min-width: 122px; }
.desktop-pet-mobile { display: none; align-items: center; gap: 11px; padding: 13px; border: 1px solid var(--pet-line); border-radius: 8px; background: var(--pet-cream); color: var(--pet-muted); }
.desktop-pet-mobile > svg { flex: 0 0 auto; color: var(--pet-coral); }
.desktop-pet-mobile div { min-width: 0; flex: 1; display: grid; gap: 3px; }
.desktop-pet-mobile strong { color: var(--pet-ink); font-size: 13px; }
.desktop-pet-mobile span { font-size: 11px; line-height: 1.5; }

.dialogue-bar { position: absolute; z-index: 6; top: 58px; left: 22px; width: min(410px, calc(100% - 44px)); display: grid; grid-template-columns: minmax(0, 1fr) 30px; gap: 10px; align-items: start; padding: 13px 12px 15px 15px; border: 1px solid #d8ad8d; border-radius: 8px; background: #fffdf8; box-shadow: 0 13px 28px rgb(91 51 30 / 17%); color: var(--pet-ink); text-align: left; }
.dialogue-bar::after { content: ''; position: absolute; left: 46px; bottom: -8px; width: 14px; height: 14px; transform: rotate(45deg); border-right: 1px solid #d8ad8d; border-bottom: 1px solid #d8ad8d; background: #fffdf8; }
.dialogue-icon { flex: 0 0 auto; width: 24px; height: 24px; display: grid; place-items: center; border-radius: 6px; background: var(--pet-coral); color: white; }
.dialogue-copy { min-width: 0; display: grid; gap: 7px; }
.dialogue-copy p { margin: 0; display: flex; flex-wrap: wrap; align-items: center; gap: 5px 8px; min-height: 24px; }
.dialogue-copy p strong { font-size: 14px; }
.dialogue-action { padding: 3px 6px; border-radius: 4px; background: #f8e5d5; color: var(--pet-coral-strong); font-size: 10px; font-weight: 800; }
.dialogue-reward { color: var(--pet-muted); font-size: 11px; font-weight: 700; }
.dialogue-reward[data-rewarded='true'] { color: #3e805f; }
.dialogue-copy blockquote { margin: 0; color: var(--pet-ink); font-size: 14px; line-height: 1.6; overflow-wrap: anywhere; }
.dialogue-close { width: 30px; height: 30px; display: grid; place-items: center; padding: 0; border: 0; background: transparent; color: var(--pet-muted); }
.dialogue-close:hover { background: #f5e8da; color: var(--pet-ink); }
.dialogue-enter-active, .dialogue-leave-active { transition: opacity var(--motion-fast) ease, transform var(--motion-fast) ease; }
.dialogue-enter-from, .dialogue-leave-to { opacity: 0; transform: translateY(7px) rotate(-1deg); }

.pet-form { margin: 20px 0 10px; padding: 20px; border: 1px solid var(--pet-line); border-radius: 8px; background: var(--pet-paper); box-shadow: var(--pet-shadow); }
.form-head { display: flex; align-items: start; justify-content: space-between; gap: 16px; }
.species-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; }
.species-grid button { min-width: 0; min-height: 66px; display: flex; align-items: center; justify-content: flex-start; gap: 9px; padding: 8px; border: 1px solid var(--pet-line); background: var(--pet-paper); color: var(--pet-muted); }
.species-grid button.active { border-color: var(--pet-coral); background: #fff0e6; color: var(--pet-coral-strong); box-shadow: inset 0 -3px 0 var(--pet-coral); }
.species-glyph { width: 34px; height: 34px; display: grid; place-items: center; flex: 0 0 auto; border-radius: 50%; background: #f6dfc6; color: var(--pet-ink); font-weight: 900; }
.partners-page .field input, .partners-page .field select { border-color: var(--pet-line); background: #fffefb; color: var(--pet-ink); }

.section-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.section-count { color: var(--pet-muted); font-size: 12px; font-weight: 700; }
.companion-roster { padding-top: 28px; }
.pet-list { display: grid; grid-auto-flow: column; grid-auto-columns: minmax(220px, 1fr); gap: 10px; overflow-x: auto; padding: 2px 2px 10px; scroll-snap-type: x proximity; scrollbar-width: thin; scrollbar-color: var(--pet-line) transparent; }
.pet-card { --species-color: #f3b36f; min-width: 0; min-height: 78px; display: grid; grid-template-columns: 48px minmax(0, 1fr) auto; align-items: center; gap: 10px; padding: 10px; border: 1px solid var(--pet-line); border-radius: 8px; background: var(--pet-paper); color: var(--pet-ink); text-align: left; scroll-snap-align: start; }
.pet-card.selected { border-color: var(--pet-coral); background: #fff1e8; box-shadow: inset 0 -3px 0 var(--pet-coral), 0 8px 18px rgb(145 76 51 / 9%); }
.pet-avatar { width: 48px; height: 48px; display: grid; place-items: center; border-radius: 50%; background: color-mix(in srgb, var(--species-color) 72%, white); color: var(--pet-ink); font-family: ui-rounded, "SF Pro Rounded", "PingFang SC", sans-serif; font-weight: 900; }
.pet-card-copy { min-width: 0; display: grid; gap: 4px; }
.pet-card-copy strong, .pet-card-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pet-card-copy small { color: var(--pet-muted); font-size: 11px; }
.pet-level { align-self: start; color: var(--pet-coral-strong); font-size: 11px; font-weight: 800; }
[data-species='DOG'] { --species-color: #e9ad64; }
[data-species='HAMSTER'] { --species-color: #f0ca65; }
[data-species='SNAKE'], [data-species='TURTLE'] { --species-color: #7fbc8c; }
[data-species='RABBIT'] { --species-color: #e7a5ac; }
[data-species='BIRD'] { --species-color: #82b6cf; }
[data-species='FOX'] { --species-color: #ea8357; }

.shop { padding-top: 30px; }
.shop-head { align-items: end; }
.shop-tools { display: flex; align-items: center; gap: 10px; color: var(--pet-coral); }
.shop-tabs { display: grid; grid-template-columns: repeat(3, minmax(58px, 1fr)); padding: 3px; border: 1px solid var(--pet-line); border-radius: 8px; background: #f7eadc; }
.shop-tabs button { min-height: 34px; padding: 0 11px; border: 0; border-radius: 6px; background: transparent; color: var(--pet-muted); font-size: 12px; }
.shop-tabs button[aria-pressed='true'] { background: var(--pet-paper); color: var(--pet-coral-strong); box-shadow: 0 3px 9px rgb(104 66 43 / 10%); }
.shop-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.shop-item { min-width: 0; min-height: 152px; display: grid; grid-template-columns: 104px minmax(0, 1fr); align-items: stretch; gap: 14px; padding: 12px; border: 1px solid var(--pet-line); border-radius: 8px; background: var(--pet-paper); color: var(--pet-ink); text-align: left; box-shadow: 0 8px 20px rgb(104 66 43 / 7%); }
.shop-art { width: 104px; min-height: 126px; display: grid; place-items: center; align-self: stretch; border-radius: 6px; background: #e7f2e9; }
.shop-item[data-kind='DECOR'] .shop-art { background: #e9eff8; }
.shop-art img { width: 72px; height: 72px; object-fit: contain; }
.shop-copy { min-width: 0; display: grid; align-content: center; gap: 6px; }
.shop-copy > strong { overflow-wrap: anywhere; font-size: 16px; }
.shop-copy > small { min-height: 36px; color: var(--pet-muted); line-height: 1.5; }
.shop-item:disabled { cursor: not-allowed; opacity: .72; }
.item-type { color: #3f7f61; font-size: 11px; font-weight: 800; }
.shop-item[data-kind='DECOR'] .item-type { color: #5476a1; }
.item-meta { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 7px 10px; color: var(--pet-muted); font-size: 13px; }
.item-meta b { display: inline-flex; align-items: center; gap: 4px; color: #996a13; }
.item-meta em { color: #3e805f; font-style: normal; font-weight: 700; }
.item-status { padding-top: 6px; border-top: 1px dashed var(--pet-line); color: var(--pet-muted); font-size: 11px; }

@media (prefers-reduced-motion: no-preference) {
  .pet-card, .shop-item, .interact-button, .interaction-option { transition: transform var(--motion-fast) ease, border-color var(--motion-fast) ease, box-shadow var(--motion-fast) ease, background-color var(--motion-fast) ease; }
  .pet-card:hover, .shop-item:not(:disabled):hover { transform: translateY(-3px); border-color: var(--pet-coral); box-shadow: var(--pet-shadow); }
  .coin-mark { animation: coin-arrive 420ms ease-out both; }
}

@media (max-width: 960px) {
  .partner-stage { grid-template-columns: minmax(0, 1fr); }
  .pet-panel { padding: 4px 2px 0; }
}

@media (max-width: 900px) {
  .desktop-pet-control { display: none; }
  .desktop-pet-mobile { display: flex; }
}

@media (max-width: 720px) {
  .partner-head { align-items: flex-start; flex-direction: column; }
  .partner-head h1 { font-size: 28px; }
  .partner-stage { gap: 20px; padding: 20px 0; }
  .home-scene { border-radius: 74px 74px 8px 8px; }
  .pet-area { min-height: 310px; border-radius: 66px 66px 5px 5px; }
  .pet-panel h2 { font-size: 30px; }
  .shop-head { align-items: flex-start; flex-direction: column; }
  .shop-tools { width: 100%; }
  .shop-tabs { width: 100%; }
  .shop-list { grid-template-columns: 1fr; }
  .species-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .dialogue-bar { top: 56px; left: 12px; width: calc(100% - 24px); grid-template-columns: minmax(0, 1fr) 28px; gap: 6px; padding: 11px 9px 12px 12px; }
  .dialogue-bar::after { left: 36px; }
  .dialogue-close { width: 28px; height: 28px; }
}

@media (max-width: 440px) {
  .wallet-pill { width: 100%; }
  .wallet-copy { width: 100%; }
  .home-scene { border-radius: 52px 52px 8px 8px; }
  .pet-area { min-height: 280px; border-radius: 45px 45px 5px 5px; }
  .scene-label { top: 14px; left: 15px; }
  .name-row { align-items: flex-start; }
  .pet-panel h2 { font-size: 28px; }
  .pet-list { grid-auto-columns: minmax(206px, 82vw); }
  .shop-item { grid-template-columns: 82px minmax(0, 1fr); gap: 10px; }
  .shop-art { width: 82px; min-height: 116px; }
  .shop-art img { width: 58px; height: 58px; }
  .dialogue-copy p strong, .dialogue-copy blockquote { font-size: 13px; }
}

:global(:root[data-theme='dark']) .partners-page {
  --pet-paper: #2b2522;
  --pet-cream: #332920;
  --pet-ink: #fff5e9;
  --pet-muted: #c9b6a8;
  --pet-line: #5b493d;
  --pet-scene: #f6ead9;
}

:global(:root[data-theme='dark']) .partners-page .wallet-pill { background: #342b1d; color: #f0c96d; }
:global(:root[data-theme='dark']) .partners-page .affection-panel,
:global(:root[data-theme='dark']) .partners-page .pet-card,
:global(:root[data-theme='dark']) .partners-page .shop-item,
:global(:root[data-theme='dark']) .partners-page .pet-form,
:global(:root[data-theme='dark']) .partners-page .interaction-option,
:global(:root[data-theme='dark']) .partners-page .desktop-pet-mobile { background: var(--pet-paper); }

@media (prefers-color-scheme: dark) {
  :global(:root[data-theme='system']) .partners-page {
    --pet-paper: #2b2522;
    --pet-cream: #332920;
    --pet-ink: #fff5e9;
    --pet-muted: #c9b6a8;
    --pet-line: #5b493d;
    --pet-scene: #f6ead9;
  }
}

@keyframes coin-arrive { from { opacity: 0; transform: rotate(-18deg) scale(.78); } to { opacity: 1; transform: rotate(0) scale(1); } }
</style>
