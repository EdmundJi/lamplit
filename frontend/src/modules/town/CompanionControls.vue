<script setup lang="ts">
import { computed } from 'vue'
import { Footprints, HandHeart, Home, RotateCcw, UserRoundPlus } from 'lucide-vue-next'
import { useTownCompanionStore } from './town-companion.store'

const props = defineProps<{ inPark: boolean; inHome: boolean }>()
const emit = defineEmits<{ walk: []; stroke: []; home: []; choose: [] }>()
const companion = useTownCompanionStore()
const isOut = computed(() => companion.mode !== 'home')

</script>

<template>
  <section class="companion-controls" aria-label="伙伴出游" @pointerdown.stop @keydown.stop>
    <template v-if="companion.pet">
      <p class="companion-summary">
        <span>{{ companion.pet.name }}</span>
        <small v-if="isOut">{{ companion.mode === 'roaming' ? '在公园自由活动' : '正在跟着你' }}</small>
        <small v-else>在家等你</small>
      </p>
      <div class="companion-actions">
        <button v-if="!isOut" type="button" :disabled="companion.loading" @click="emit('walk')"><Footprints :size="15" />带上出门</button>
        <button v-else type="button" :disabled="companion.busy" @click="emit('stroke')"><HandHeart :size="15" />{{ companion.busy ? '摸摸中…' : '摸摸' }}</button>
        <button v-if="isOut && props.inPark" type="button" @click="companion.setRoaming(companion.mode !== 'roaming')"><RotateCcw :size="15" />{{ companion.mode === 'roaming' ? '叫回身边' : '放开活动' }}</button>
        <button v-if="isOut && !props.inHome" type="button" @click="emit('home')"><Home :size="15" />带它回家</button>
      </div>
    </template>
    <template v-else-if="!companion.loading">
      <p class="companion-summary"><span>还没有同行的伙伴</span><small>选择一位伙伴，一起去公园走走。</small></p>
      <button type="button" @click="emit('choose')"><UserRoundPlus :size="15" />选择伙伴</button>
    </template>
    <p v-if="companion.error" class="companion-error" role="alert">{{ companion.error }} <button v-if="companion.userId" type="button" @click="companion.load(companion.userId)">重试</button></p>
  </section>
</template>

<style scoped>
.companion-controls { width: min(350px, calc(100vw - 32px)); color: var(--ink); display: grid; gap: 7px; padding: 9px 11px; border: 1px solid color-mix(in srgb, var(--border) 72%, transparent); border-radius: 12px; background: color-mix(in srgb, var(--surface) 94%, transparent); box-shadow: var(--shadow); }
.companion-summary { display: grid; gap: 1px; margin: 0; font-size: 13px; font-weight: 650; }.companion-summary small { color: var(--muted); font-weight: 400; font-size: 11px; }.companion-actions { display: flex; flex-wrap: wrap; gap: 6px; }.companion-controls button { min-height: 32px; display: inline-flex; align-items: center; gap: 5px; padding: 5px 9px; font-size: 12px; }.companion-error { margin: 0; color: var(--danger); font-size: 12px; }.companion-error button { min-height: 24px; padding: 1px 5px; color: inherit; background: transparent; border: none; text-decoration: underline; }
</style>
