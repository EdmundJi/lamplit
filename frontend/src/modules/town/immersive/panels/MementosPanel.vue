<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Bookmark, Leaf } from 'lucide-vue-next'
import { api } from '../../../../shared/api/client'
import { onDataChanged } from '../../../../shared/data-sync'
import { growthIcon, type Achievement } from '../../../achievements/achievement.types'
import { useTownStore } from '../../town.store'
import { earnedMementos, mementoDate } from '../../mementos'

const town = useTownStore()
const timezone = computed(() => town.model?.residents.find(item => item.isSelf)?.timezone ?? 'Asia/Shanghai')
const records = ref<Achievement[]>([])
const loading = ref(true)
const error = ref('')
const selectedCode = ref<string | null>(null)
const mementos = computed(() => earnedMementos(records.value))
const selected = computed(() => mementos.value.find(item => item.code === selectedCode.value))
let request = 0
async function load() {
  const current = ++request
  loading.value = true
  error.value = ''
  try {
    const result = await api.get<Achievement[]>('/achievements')
    if (current !== request) return
    records.value = result
    if (!mementos.value.some(item => item.code === selectedCode.value)) selectedCode.value = mementos.value[0]?.code ?? null
  } catch {
    if (current === request) error.value = '暂时没能取出这些纪念，稍后再看看。'
  } finally {
    if (current === request) loading.value = false
  }
}
const stopSync = onDataChanged(['achievements', 'tasks'], () => { void load() })
onMounted(() => { void load() })
onBeforeUnmount(() => { request++; stopSync() })
</script>

<template>
  <section class="memento-wall" aria-label="家的纪念墙">
    <div class="wall-intro"><Leaf :size="18" aria-hidden="true" /><p>有些日子，值得留在家里。<span>挑一件纪念，坐下来看看。</span></p></div>
    <p v-if="loading && !mementos.length" class="wall-state" role="status">正在取出你的纪念…</p>
    <div v-else-if="error" class="wall-state" role="alert"><p>{{ error }}</p><button type="button" @click="load">再试一次</button></div>
    <div v-else-if="!mementos.length" class="wall-empty"><Bookmark :size="28" aria-hidden="true" /><p>墙上还留着空位。</p><span>以后获得的成就会放在这里。今天，也可以只是回家歇一歇。</span></div>
    <template v-else>
      <div class="keepsakes" role="group" aria-label="已获得的纪念">
        <button v-for="item in mementos" :key="item.code" type="button" class="keepsake" :class="`tone-${item.tone}`" :aria-pressed="selectedCode === item.code" @click="selectedCode = item.code">
          <span class="keepsake-pin" aria-hidden="true" />
          <component :is="growthIcon(item.iconKey)" :size="28" aria-hidden="true" />
          <strong>{{ item.name }}</strong>
          <span>{{ mementoDate(item.earnedAt, timezone) }}</span>
        </button>
      </div>
      <article v-if="selected" class="keepsake-note" aria-live="polite" aria-atomic="true">
        <span class="note-eyebrow">那时留下的纪念</span>
        <h3>{{ selected.name }}</h3>
        <time v-if="selected.earnedAt" :datetime="selected.earnedAt">{{ mementoDate(selected.earnedAt, timezone) }}</time>
        <p>{{ selected.body }}</p>
        <small v-if="selected.triggerText">{{ selected.triggerText }}</small>
      </article>
    </template>
  </section>
</template>

<style scoped>
.memento-wall { color: #493f32; padding: 4px; min-width: 0; }
.wall-intro { display: flex; align-items: center; gap: 12px; color: #54694f; margin-bottom: 18px; }
.wall-intro p { margin: 0; font-size: 14px; line-height: 1.8; }
.wall-intro span { display: block; color: #7b7265; font-size: 12px; }
.keepsakes { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(130px, 100%), 1fr)); gap: 14px; padding: 20px 14px 18px; border: 1px solid #c8b79a; border-radius: 8px; background: repeating-linear-gradient(0deg, #d6c5a7 0px, #d6c5a7 31px, #cdbb9c 32px); max-height: 280px; overflow: auto; }
.keepsake { position: relative; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 9px; padding: 23px 10px 14px; min-height: 140px; min-width: 0; border: 2px solid transparent; border-radius: 2px; background: #fff9eb; color: #5b664f; box-shadow: 0 3px 7px #69533726; cursor: pointer; }
.keepsake[aria-pressed='true'] { border-color: #6c7e59; background: #fffdf5; }
.keepsake:focus-visible { outline: 3px solid #355b44; outline-offset: 2px; }
.keepsake strong { font-size: 13px; overflow-wrap: anywhere; }
.keepsake > span:last-child { font-size: 10px; color: #776e61; }
.keepsake-pin { position: absolute; top: 7px; width: 6px; height: 6px; border-radius: 50%; background: #a67153; box-shadow: 0 1px 2px #53381e55; }
.tone-blue { color: #4c6b7d; }.tone-amber { color: #987535; }.tone-violet { color: #796586; }
.keepsake-note { margin-top: 18px; padding: 19px 20px; background: #fcf7eb; border-left: 3px solid #bda779; overflow-wrap: anywhere; }
.note-eyebrow { color: #81745f; font-size: 10px; letter-spacing: .12em; }
.keepsake-note h3 { margin: 8px 0 4px; font-size: 18px; }
.keepsake-note time, .keepsake-note small { font-size: 11px; color: #796e5e; line-height: 1.8; }
.keepsake-note p { font-size: 14px; line-height: 1.9; margin: 15px 0 8px; }
.wall-state, .wall-empty { padding: 28px 16px; text-align: center; font-size: 13px; line-height: 1.8; }
.wall-empty { border: 1px dashed #cabb9e; background: #faf5e9; color: #776e61; }
.wall-empty p { font-size: 17px; color: #596b50; }.wall-empty span { display: block; max-width: 280px; margin: auto; }
.wall-state button { padding: 8px 14px; border: 1px solid #cabb9e; border-radius: 6px; background: #faf5e9; color: #355b44; cursor: pointer; }
</style>
