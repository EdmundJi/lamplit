<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  ArrowLeft,
  Coins,
  Heart,
  MessageCircle,
  MonitorOff,
  MonitorUp,
  PawPrint,
  Pencil,
  Plus,
  ShoppingBag,
  Sparkles,
  X,
} from 'lucide-vue-next'
import { onDataChanged, notifyDataChanged } from '../../../../shared/data-sync'
import { randomPetDialogue } from '../../../partners/pet-dialogues'
import { findPetSpecies, petSpeciesOptions, type PetInteractionOption } from '../../../partners/pet-options'
import {
  useDesktopCompanion,
  usePartnerProfile,
  usePetForm,
  usePetVariant,
  type PetFormMode,
} from '../../../partners/partners.logic'
import type { Pet, ShopItem } from '../../../partners/partner.types'
import RivePet from '../../../partners/RivePet.vue'
import { worldBridgeKey } from '../panel.types'

type ShopFilter = 'ALL' | ShopItem['itemType']
type DialogueMessage = { text: string; actionLabel: string; rewarded: boolean; affectionDelta: number }

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

const bridge = inject(worldBridgeKey, undefined)

// 四个子视图：main | shop | edit | create
const view = ref<'main' | 'shop' | 'edit' | 'create'>('main')

const profile = usePartnerProfile()
const form = usePetForm()
const variant = usePetVariant()
const desktopPet = useDesktopCompanion()

const petRenderer = ref<InstanceType<typeof RivePet> | null>(null)
const dialogue = ref<DialogueMessage | null>(null)
let dialogueTimer: number | undefined

const shopFilter = ref<ShopFilter>('ALL')

const pet = computed(() => profile.selectedPet.value)
const affectionPercent = computed(() => {
  if (!pet.value) return 0
  return Math.min(100, Math.round((pet.value.affection / pet.value.nextLevelAffection) * 100))
})
const currentInteractions = computed(() => findPetSpecies(pet.value?.speciesCode ?? '')?.interactions ?? [])
const visibleItems = computed(() => {
  const items = profile.profile.value?.shopItems ?? []
  return shopFilter.value === 'ALL' ? items : items.filter(item => item.itemType === shopFilter.value)
})
const isDesktopPet = computed(() => desktopPet.isDesktopPet(pet.value))

watch(pet, p => variant.syncFor(p), { immediate: true })

function showDialogue(result: { rewarded: boolean; affectionDelta: number }, actionLabel: string) {
  dialogue.value = {
    text: randomPetDialogue(),
    actionLabel,
    rewarded: result.rewarded,
    affectionDelta: result.affectionDelta,
  }
  window.clearTimeout(dialogueTimer)
  dialogueTimer = window.setTimeout(closeDialogue, 7000)
}

function closeDialogue() {
  window.clearTimeout(dialogueTimer)
  dialogue.value = null
}

async function interact(action: PetInteractionOption) {
  if (!pet.value || !action || profile.busy.value) return
  petRenderer.value?.react(action.action)
  const result = await profile.interact(pet.value.publicId)
  if (result) {
    showDialogue(result, action.label)
    if (result.rewarded) bridge?.emit({ type: 'celebrate', publicId: result.pet.publicId })
  }
}

async function selectPet(p: Pet) {
  if (p.selected || profile.busy.value) return
  closeDialogue()
  if (await profile.selectPet(p)) bridge?.emit({ type: 'toast', text: profile.feedback.value })
}

function selectVariant(index: number) {
  if (profile.busy.value) return
  variant.select(pet.value, index)
}

function openShop() {
  view.value = 'shop'
  shopFilter.value = 'ALL'
}

function backToMain() {
  view.value = 'main'
  profile.error.value = ''
  profile.feedback.value = ''
}

async function purchase(item: ShopItem) {
  const result = await profile.purchase(item)
  if (result) {
    bridge?.emit({ type: 'toast', text: profile.feedback.value })
    bridge?.emit({ type: 'celebrate', publicId: result.pet.publicId })
  }
}

function openEditForm() {
  if (!pet.value) return
  form.syncFrom(pet.value)
  view.value = 'edit'
}

function openCreateForm() {
  form.resetForCreate()
  view.value = 'create'
}

async function savePet(mode: PetFormMode) {
  const ok = await profile.savePet(mode, form.form)
  if (ok) {
    bridge?.emit({ type: 'toast', text: profile.feedback.value })
    view.value = 'main'
  }
}

function toggleDesktopPet() {
  const text = desktopPet.toggle(pet.value)
  if (text) bridge?.emit({ type: 'toast', text })
}

const stopDataSync = onDataChanged(['partners'], () => profile.load(false))

onMounted(() => profile.load(true))
onBeforeUnmount(() => { stopDataSync(); closeDialogue() })
</script>

<template>
  <section class="world-panel partners-panel">
    <!-- 主视图 -->
    <template v-if="view === 'main'">
      <p v-if="profile.error.value" class="error" role="alert">{{ profile.error.value }}</p>
      <p v-if="profile.feedback.value" role="status">{{ profile.feedback.value }}</p>
      <p v-if="profile.loading.value" class="empty">正在整理伙伴资料…</p>
      <template v-else-if="pet && profile.profile.value">
        <div class="wallet">
          <Coins :size="15" />
          <span>{{ profile.wallet.value.coinBalance }} 金币</span>
        </div>

        <div class="pet-display">
          <div class="pet-preview">
            <RivePet
              v-if="pet.speciesCode"
              ref="petRenderer"
              :species-code="pet.speciesCode"
              :name="pet.name"
              :variant-index="variant.variantIndex.value"
              :size="140"
            />
            <div class="pet-nav">
              <button
                v-for="(p, idx) in profile.profile.value.pets"
                :key="p.publicId"
                class="pet-dot"
                :class="{ active: p.selected }"
                type="button"
                :aria-label="`切换到 ${p.name}`"
                :aria-pressed="p.selected"
                :disabled="profile.busy.value"
                @click="selectPet(p)"
              />
            </div>
          </div>
          <div class="pet-info">
            <div class="pet-header">
              <strong>{{ pet.name }} · LV.{{ pet.level }}</strong>
              <button class="icon-button mini" type="button" aria-label="编辑资料" @click="openEditForm">
                <Pencil :size="12" />
              </button>
            </div>
            <span class="muted">{{ pet.speciesName }} · {{ pet.breed }}</span>
            <div class="progress-bar">
              <div class="progress-fill" :style="{ width: `${affectionPercent}%` }" />
            </div>
            <span class="muted">好感度 {{ pet.affection }} / {{ pet.nextLevelAffection }}</span>
          </div>
        </div>

        <div v-if="dialogue" class="dialogue-banner" :class="{ rewarded: dialogue.rewarded }">
          <MessageCircle :size="14" />
          <p>
            <strong>{{ pet.name }}:</strong> {{ dialogue.text }}
            <span v-if="dialogue.rewarded" class="gain">+{{ dialogue.affectionDelta }}</span>
          </p>
        </div>

        <div class="action-grid">
          <button
            v-for="action in currentInteractions"
            :key="action.action"
            class="action-button"
            type="button"
            :disabled="profile.busy.value"
            @click="interact(action)"
          >
            <Heart :size="14" />
            {{ action.label }}
          </button>
        </div>

        <div class="pet-actions">
          <button class="secondary compact" type="button" @click="openShop">
            <ShoppingBag :size="14" />商店
          </button>
          <button class="secondary compact" type="button" @click="toggleDesktopPet">
            <component :is="isDesktopPet ? MonitorOff : MonitorUp" :size="14" />
            {{ isDesktopPet ? '取消桌宠' : '设为桌宠' }}
          </button>
        </div>
      </template>
      <div v-else class="empty">
        <PawPrint :size="20" />
        <p>还没有伙伴</p>
        <button class="primary compact" type="button" @click="openCreateForm">
          <Plus :size="14" />认养伙伴
        </button>
      </div>
    </template>

    <!-- 商店 -->
    <template v-else-if="view === 'shop'">
      <header class="sub-head">
        <button class="icon-button" type="button" aria-label="返回主页" @click="backToMain"><ArrowLeft :size="15" /></button>
        <strong>伙伴商店</strong>
        <div class="wallet mini">
          <Coins :size="13" />
          <span>{{ profile.wallet.value.coinBalance }}</span>
        </div>
      </header>

      <div class="filter-tabs">
        <button
          v-for="f in ['ALL', 'FOOD', 'DECOR'] as ShopFilter[]"
          :key="f"
          class="filter-tab"
          :class="{ active: shopFilter === f }"
          type="button"
          @click="shopFilter = f"
        >
          {{ f === 'ALL' ? '全部' : f === 'FOOD' ? '食物' : '装饰' }}
        </button>
      </div>

      <p v-if="profile.error.value" class="error" role="alert">{{ profile.error.value }}</p>

      <div class="shop-grid">
        <article v-for="item in visibleItems" :key="item.publicId" class="shop-card">
          <img
            v-if="shopItemImages[item.templateSource]"
            :src="shopItemImages[item.templateSource]"
            :alt="item.name"
            class="shop-image"
          />
          <div class="shop-copy">
            <strong>{{ item.name }}</strong>
            <p class="shop-desc">{{ item.description }}</p>
            <div class="shop-meta">
              <span class="shop-price"><Coins :size="12" />{{ item.price }}</span>
              <span class="shop-gain"><Heart :size="11" />+{{ item.affectionGain }}</span>
            </div>
          </div>
          <button
            class="primary compact"
            type="button"
            :disabled="profile.busy.value || profile.itemDisabled(item)"
            @click="purchase(item)"
          >
            使用
          </button>
          <small v-if="profile.itemDisabled(item)" class="shop-status">{{ profile.itemStatus(item) }}</small>
        </article>
      </div>
    </template>

    <!-- 编辑/创建表单 -->
    <template v-else-if="view === 'edit' || view === 'create'">
      <header class="sub-head">
        <button class="icon-button" type="button" aria-label="返回主页" @click="backToMain"><ArrowLeft :size="15" /></button>
        <strong>{{ view === 'edit' ? '编辑资料' : '认养伙伴' }}</strong>
      </header>

      <p v-if="profile.error.value" class="error" role="alert">{{ profile.error.value }}</p>

      <form class="pet-form" @submit.prevent="savePet(view)">
        <fieldset v-if="view === 'create'" class="species-picker">
          <legend class="field-label">选择物种</legend>
          <div class="species-grid">
            <button
              v-for="s in petSpeciesOptions"
              :key="s.code"
              type="button"
              class="species-option"
              :class="{ selected: form.form.speciesCode === s.code }"
              @click="form.chooseSpecies(s.code)"
            >
              {{ s.glyph }}
            </button>
          </div>
        </fieldset>

        <label class="field">
          <span class="field-label">名字</span>
          <input v-model="form.form.name" type="text" placeholder="给它起个名字" maxlength="20" required />
        </label>

        <label class="field">
          <span class="field-label">品种</span>
          <select v-model="form.form.breed" required @change="form.chooseKind()">
            <option v-for="kind in form.kindOptions.value" :key="kind.value" :value="kind.value">
              {{ kind.value }}
            </option>
          </select>
        </label>

        <label class="field">
          <span class="field-label">颜色</span>
          <input v-model="form.form.furColor" type="text" placeholder="描述外观颜色" maxlength="20" required />
        </label>

        <div class="form-actions">
          <button class="secondary" type="button" @click="backToMain">取消</button>
          <button class="primary" type="submit" :disabled="profile.busy.value">
            {{ view === 'edit' ? '保存' : '认养' }}
          </button>
        </div>
      </form>
    </template>

    <footer class="panel-footer">
      <button class="secondary compact" type="button" :disabled="profile.loading.value || profile.busy.value" @click="profile.load(true)">刷新伙伴</button>
    </footer>
  </section>
</template>

<style scoped>
.world-panel { display: grid; gap: 10px; width: 100%; color: var(--ink); }
.wallet { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--amber); font-weight: 700; }
.wallet.mini { font-size: 11px; }
.wallet.mini svg { width: 13px; height: 13px; }

.pet-display { display: grid; gap: 10px; padding: 10px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); }
.pet-preview { position: relative; display: grid; place-items: center; min-height: 140px; }
.pet-nav { position: absolute; bottom: 8px; display: flex; gap: 6px; }
.pet-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--border); border: none; cursor: pointer; transition: background 0.15s; }
.pet-dot.active { background: var(--primary); }
.pet-info { display: grid; gap: 4px; }
.pet-header { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
.pet-header strong { font-size: 13px; }
.icon-button.mini { width: 24px; height: 24px; padding: 0; }
.muted { color: var(--muted); font-size: 11px; }
.progress-bar { height: 6px; border-radius: 3px; background: var(--border); overflow: hidden; }
.progress-fill { height: 100%; background: linear-gradient(90deg, var(--primary), var(--accent)); transition: width 0.3s; }

.dialogue-banner { display: flex; align-items: start; gap: 8px; padding: 8px 10px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface-muted); font-size: 12px; }
.dialogue-banner.rewarded { background: color-mix(in srgb, var(--primary-soft) 40%, var(--surface)); border-color: var(--primary-soft); }
.dialogue-banner p { margin: 0; line-height: 1.4; }
.dialogue-banner strong { font-weight: 600; }
.dialogue-banner .gain { margin-left: 4px; color: var(--primary-strong); font-weight: 700; }

.action-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(100px, 1fr)); gap: 6px; }
.action-button { display: flex; align-items: center; justify-content: center; gap: 5px; padding: 8px 10px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); color: var(--ink); font-size: 12px; font-weight: 600; transition: all 0.15s; }
.action-button:hover:not(:disabled) { background: var(--primary-soft); border-color: var(--primary); }
.action-button:disabled { opacity: 0.5; cursor: not-allowed; }

.pet-actions { display: flex; gap: 6px; flex-wrap: wrap; }
button.compact { font-size: 12px; height: 30px; padding: 0 10px; }

.sub-head { display: flex; align-items: center; gap: 8px; }
.sub-head strong { font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.filter-tabs { display: flex; gap: 4px; }
.filter-tab { padding: 6px 12px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); color: var(--muted); font-size: 12px; font-weight: 600; transition: all 0.15s; }
.filter-tab.active { background: var(--primary-soft); border-color: var(--primary); color: var(--primary-strong); }

.shop-grid { display: grid; gap: 8px; max-height: 360px; overflow-y: auto; }
.shop-card { position: relative; display: grid; grid-template-columns: 56px minmax(0, 1fr) auto; align-items: start; gap: 10px; padding: 10px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); }
.shop-image { width: 56px; height: 56px; object-fit: contain; border-radius: 4px; background: var(--surface-muted); }
.shop-copy { min-width: 0; display: grid; gap: 3px; }
.shop-copy strong { font-size: 13px; }
.shop-desc { margin: 0; font-size: 11px; color: var(--muted); line-height: 1.3; }
.shop-meta { display: flex; align-items: center; gap: 8px; font-size: 11px; font-weight: 600; }
.shop-price { display: flex; align-items: center; gap: 3px; color: var(--amber); }
.shop-gain { display: flex; align-items: center; gap: 3px; color: var(--primary); }
.shop-status { position: absolute; bottom: 8px; right: 10px; font-size: 10px; color: var(--muted); }

.pet-form { display: grid; gap: 10px; }
.field { display: grid; gap: 4px; }
.field-label { font-size: 11px; font-weight: 600; color: var(--muted); }
.field input, .field select, .field textarea { border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); color: var(--ink); padding: 7px 9px; font-size: 13px; }
.species-picker { border: none; padding: 0; margin: 0; display: grid; gap: 6px; }
.species-picker legend { margin-bottom: 2px; }
.species-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(50px, 1fr)); gap: 6px; }
.species-option { width: 100%; aspect-ratio: 1; display: grid; place-items: center; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); font-size: 20px; transition: all 0.15s; }
.species-option:hover { background: var(--surface-muted); }
.species-option.selected { background: var(--primary-soft); border-color: var(--primary); }
.form-actions { display: flex; gap: 8px; justify-content: flex-end; }

.panel-footer { display: flex; justify-content: flex-end; }
.empty { display: grid; gap: 8px; place-items: center; text-align: center; padding: 20px; color: var(--muted); }
.empty p { margin: 0; }
</style>
