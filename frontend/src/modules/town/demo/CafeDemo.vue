<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { CafeDemo, CafeDemoState } from './cafe-demo'

const canvas = ref<HTMLElement | null>(null)
const state = ref<CafeDemoState>({ ready: false, paused: false, elapsed: 0, walking: false, labels: true, message: '' })
const error = ref('')
const clean = ref(false)
const soundEnabled = ref(false)
const soundPending = ref(false)
const soundError = ref('')
let demo: CafeDemo | undefined
let disposed = false
const progress = computed(() => Math.min(100, state.value.elapsed / 300))
const time = computed(() => `${Math.min(30, Math.floor(state.value.elapsed / 1000)).toString().padStart(2, '0')} / 30`)
function reload() { window.location.reload() }
async function toggleSound() {
  if (!demo || soundPending.value) return
  soundPending.value = true
  try { await demo.setSoundEnabled(!soundEnabled.value); soundEnabled.value = !soundEnabled.value; soundError.value = '' }
  catch { soundError.value = '声音暂时无法开启，可以继续安静观看。' }
  finally { soundPending.value = false }
}
function keydown(event: KeyboardEvent) {
  if (event.key === 'Escape') clean.value = false
  if (event.code === 'Space' && event.target === document.body) { event.preventDefault(); demo?.setPaused(!state.value.paused) }
}
onMounted(async () => {
  window.addEventListener('keydown', keydown)
  try {
    const { createCafeDemo } = await import('./cafe-demo')
    if (disposed || !canvas.value) return
    demo = createCafeDemo(canvas.value, value => { state.value = value }, message => { error.value = message })
    if (import.meta.env.DEV) (window as unknown as { __cafeDemo?: CafeDemo }).__cafeDemo = demo
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '场景未能加载，请刷新重试。' }
})
onBeforeUnmount(() => {
  disposed = true; window.removeEventListener('keydown', keydown); demo?.destroy()
  delete (window as unknown as { __cafeDemo?: CafeDemo }).__cafeDemo
})
</script>

<template>
  <main class="cafe-demo" :class="{ 'is-clean': clean }">
    <header v-if="!clean" class="demo-header">
      <div class="demo-identity"><span class="demo-kicker">成长小镇 · 可玩片段</span><h1>傍晚，街角</h1></div>
      <div class="demo-weather"><span class="sun-dot"></span><span>18:24 <span class="weather-note">日落前 · 微风</span></span></div>
    </header>
    <section class="demo-stage" aria-label="傍晚咖啡馆街角，可点击地面行走">
      <div ref="canvas" class="demo-canvas" />
      <div v-if="error" class="demo-loading" role="alert"><p>{{ error }}</p><button @click="reload">重新加载</button></div>
      <div v-else-if="!state.ready" class="demo-loading" role="status">街角的灯，正在亮起来…</div>
      <button v-if="clean" class="restore-ui" @click="clean = false" aria-label="显示界面">显示界面</button>
      <div v-if="state.message && !clean" class="demo-toast" role="status">{{ state.message }}</div>
    </section>
    <footer v-if="!clean" class="demo-footer">
      <div class="demo-caption"><p>{{ state.walking ? '沿着石板路走走，生活照常发生。' : '两杯咖啡，一个没有说出口的下午。' }}</p><span>{{ state.walking ? '点击地面 / WASD / 方向键移动' : '停留三十秒，看看他们的小事。' }}</span></div>
      <div class="demo-controls" aria-label="演示控制">
        <button :disabled="!state.ready" @click="demo?.setPaused(!state.paused)" :aria-pressed="state.paused">{{ state.paused ? '继续' : '暂停' }}</button>
        <button :disabled="!state.ready" @click="demo?.restart()">再看一次</button>
        <button :disabled="!state.ready" @click="demo?.setLabelsVisible(!state.labels)" :aria-pressed="!state.labels">{{ state.labels ? '隐藏气泡' : '显示气泡' }}</button>
        <button class="walk-button" :disabled="!state.ready" @click="demo?.setWalking(!state.walking)" :aria-pressed="state.walking">{{ state.walking ? '安静旁观' : '走进街角' }}</button>
        <button :disabled="!state.ready || soundPending" @click="toggleSound" :aria-pressed="soundEnabled">{{ soundEnabled ? '关闭声音' : '听听街角' }}</button>
        <button class="quiet-button" @click="clean = true">收起界面</button>
        <button class="quiet-button" :disabled="!state.ready" @click="demo?.saveFrame()">保存画面</button>
      </div>
      <div class="demo-timeline" aria-label="片段进度"><div><i :style="{ width: `${progress}%` }" /></div><span>{{ time }}</span></div>
      <p v-if="soundError" class="sound-error" role="status">{{ soundError }}</p>
    </footer>
  </main>
</template>

<style scoped>
.cafe-demo{--cream:#efe7d4;--muted:#a7b0a1;min-height:100svh;background:#1d2925;color:var(--cream);padding:0 36px 22px;display:flex;flex-direction:column;align-items:center;font-family:"Avenir Next","PingFang SC",sans-serif}
.demo-header{width:min(1120px,100%);display:flex;align-items:center;justify-content:space-between;padding:24px 0 20px;gap:16px}.demo-identity{display:flex;align-items:baseline;gap:20px}.demo-kicker{font-size:11px;letter-spacing:.16em;color:var(--muted)}h1{font-family:"Songti SC",STSong,serif;font-weight:400;font-size:27px;letter-spacing:.12em;margin:0}.demo-weather{display:flex;align-items:center;gap:10px;font-size:13px;font-variant-numeric:tabular-nums;color:#d5cdb8}.weather-note{margin-left:12px;color:var(--muted);font-size:11px}.sun-dot{width:8px;height:8px;border-radius:50%;background:#ecc185;box-shadow:0 0 15px #ecc18555}
.demo-stage{position:relative;width:min(1120px,100%);aspect-ratio:3/2;max-height:calc(100svh - 224px);background:#343e37;box-shadow:0 16px 60px #0004;overflow:hidden;border:1px solid #e0d5b322}.demo-canvas{width:100%;height:100%;position:absolute;inset:0}.demo-canvas :deep(canvas){display:block;image-rendering:pixelated}.demo-loading{position:absolute;inset:0;display:flex;flex-direction:column;justify-content:center;align-items:center;padding:30px;text-align:center;background:#29362f;letter-spacing:.1em;gap:12px}.demo-loading button{padding:10px 20px}.demo-footer{width:min(1120px,100%);padding-top:20px;display:grid;grid-template-columns:1fr auto;gap:15px 20px;align-items:center}.demo-caption p{font-family:"Songti SC",STSong,serif;font-size:16px;letter-spacing:.06em;margin:0 0 3px}.demo-caption>span{font-size:11px;color:var(--muted)}.demo-controls{display:flex;gap:7px;flex-wrap:wrap;justify-content:flex-end}.demo-controls button,.restore-ui{font-size:11px;min-height:36px;padding:8px 12px;border:1px solid #e9e0c92a;color:#e3dfce;background:transparent;border-radius:4px;font-weight:400}.demo-controls button:hover{background:#e9e0c913;border-color:#e9e0c975}.demo-controls .walk-button{background:#dbcea9;color:#25342c;border-color:#dbcea9}.demo-controls .quiet-button{color:var(--muted);border-color:transparent;padding-left:6px;padding-right:6px}.demo-timeline{grid-column:1/-1;display:flex;align-items:center;gap:14px}.demo-timeline>div{height:2px;background:#ffffff13;flex:1}.demo-timeline i{display:block;height:100%;background:#c3aa76;transition:width .2s linear}.demo-timeline span{font-size:10px;letter-spacing:.1em;color:#98a596;font-variant-numeric:tabular-nums}.demo-toast{position:absolute;bottom:20px;left:50%;transform:translateX(-50%);background:#1d2925ed;padding:10px 18px;border-radius:4px;font-size:12px;white-space:nowrap}.restore-ui{position:absolute;right:14px;bottom:14px;background:#1d2925b8;opacity:.25}.restore-ui:hover,.restore-ui:focus-visible{opacity:1}.is-clean{padding:0;justify-content:center}.is-clean .demo-stage{width:100%;max-height:100svh;border:0;box-shadow:none}
@media(min-width:1500px){.demo-stage{width:min(1280px,100%)}.demo-header,.demo-footer{width:min(1280px,100%)}}
@media(max-width:800px){.cafe-demo{padding:0 16px 18px}.demo-header{padding:22px 0 18px}.demo-identity{display:block}.demo-kicker{font-size:9px}h1{font-size:23px;margin-top:5px}.weather-note{display:none}.demo-stage{max-height:none}.demo-footer{grid-template-columns:1fr;padding-top:20px}.demo-controls{justify-content:flex-start}.demo-caption p{font-size:15px}.demo-timeline{grid-column:1}.is-clean{padding:0}}
@media(prefers-reduced-motion:reduce){.demo-timeline i{transition:none}}
.sound-error{font-size:11px;color:var(--muted);margin:0;grid-column:1/-1}
@media(max-height:500px) and (orientation:landscape){.cafe-demo{height:100svh;min-height:0;padding:0;overflow:hidden}.demo-header,.demo-caption,.demo-timeline{display:none}.demo-stage{width:100%;height:100svh;max-height:100svh;aspect-ratio:auto;border:0}.demo-footer{position:absolute;bottom:6px;left:10px;right:10px;width:auto;padding:0;display:flex;justify-content:center;pointer-events:none}.demo-controls{gap:4px;padding:4px;background:#1d2925db;border-radius:5px;pointer-events:auto}.demo-controls button{min-height:28px;padding:5px 8px;font-size:10px}.demo-controls .quiet-button{display:none}}
</style>
