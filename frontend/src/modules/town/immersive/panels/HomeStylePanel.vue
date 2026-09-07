<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useAuthStore } from '../../../auth/auth.store'
import { useTownStore } from '../../town.store'
import { HOME_STYLES, cancelHomePreview, previewHomeStyle, readHomePreference, saveHomePreference, type HomeStyle } from '../../home-style'
const emit = defineEmits<{ saved: []; cancel: [] }>()
const auth = useAuthStore()
const town = useTownStore()
const account = computed(() => auth.user?.publicId ?? town.model?.residents.find(r => r.isSelf)?.publicId ?? '')
const draft = ref<HomeStyle>('original')
const status = ref('')
watch(account, (id, old) => { if (old) cancelHomePreview(old); draft.value = readHomePreference(id).style; status.value = '' }, { immediate: true })
function choose(style: HomeStyle) { draft.value = style; previewHomeStyle(account.value, style); status.value = '正在预览，保存后才会保留。' }
function cancel() { cancelHomePreview(account.value); draft.value = readHomePreference(account.value).style; status.value = '已恢复保存的布置。'; emit('cancel') }
function save() {
  const ok = saveHomePreference(account.value, { ...readHomePreference(account.value), style: draft.value })
  status.value = ok ? '布置已保存，下次回家还是这个样子。' : '暂时无法保存，请确认已登录且浏览器允许本地存储。'
  if (ok) emit('saved')
}
onBeforeUnmount(() => cancelHomePreview(account.value))
</script>
<template>
  <section class="home-style" aria-label="布置我的家">
    <h3>选一个喜欢的家的颜色</h3>
    <p>地毯、扶手椅和灯留在熟悉的位置。进入家中时，选择会立即预览。</p>
    <div class="styles" role="group" aria-label="家的配色">
      <button v-for="(style, id) in HOME_STYLES" :key="id" type="button" :aria-pressed="draft === id" @click="choose(id)">
        <span class="swatches" aria-hidden="true"><i :style="{ background: style.rug }" /><i :style="{ background: style.chair }" /><i :style="{ background: style.lamp }" /></span>
        <strong>{{ style.name }}</strong><span>{{ style.description }}</span>
      </button>
    </div>
    <p class="scope">布置仅保存在当前浏览器，按账号分别记录；不会同步到其他设备。纪念物的摆放可在「纪念墙」选择。</p>
    <div class="actions"><button type="button" @click="choose('original')">恢复默认预览</button><button type="button" @click="cancel">取消预览</button><button class="save" type="button" :disabled="!account" @click="save">保存布置</button></div>
    <p role="status">{{ status }}</p>
  </section>
</template>
<style scoped>
.home-style{color:#493f32;padding:4px}.home-style h3{font-size:18px;margin:4px 0 12px}.home-style p{font-size:13px;line-height:1.8}.styles{display:grid;gap:10px}.styles button{display:grid;grid-template-columns:76px 1fr;gap:6px 12px;text-align:left;border:2px solid #d8cfbd;border-radius:12px;background:#fffaf0;color:#493f32;padding:14px;cursor:pointer}.styles button[aria-pressed=true]{border-color:#52735c;background:#f1f5eb}.styles button>span:last-child{grid-column:2;font-size:12px;line-height:1.6}.swatches{grid-row:span 2;display:flex;align-items:center}.swatches i{width:24px;height:38px;border:1px solid #9c947e;border-radius:5px}.scope{color:#786e60}.actions{display:flex;flex-wrap:wrap;gap:8px}.actions button{border:1px solid #c7bda9;border-radius:8px;padding:10px;background:#fffaf0;color:#493f32;cursor:pointer}.actions .save{background:#41694e;color:white}.actions button:disabled{opacity:.5}.home-style button:focus-visible{outline:3px solid #547965;outline-offset:2px}
</style>
