<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api, type ApiError } from '../../../shared/api/client'
import { useAuthStore } from '../../auth/auth.store'
import type { Achievement } from '../../achievements/achievement.types'
import { earnedMementos, mementoDate } from '../mementos'
import { HOME_STYLES, readHomePreference, type HomeStyle } from '../home-style'
import { postcardAttempt, type VisitProfile, type VisitPostcard } from './town-visits'
const auth = useAuthStore()
const tab = ref<'directory' | 'mine' | 'received'>('directory')
const directory = ref<{ publicId: string; displayName: string }[]>([])
const mine = ref<VisitProfile | null>(null)
const visiting = ref<VisitProfile | null>(null)
const achievements = ref<Achievement[]>([])
const received = ref<VisitPostcard[]>([])
const enabled = ref(false), style = ref<HomeStyle>('original'), codes = ref<string[]>([])
const loading = ref(false), busy = ref(false), error = ref(''), feedback = ref(''), body = ref('')
const selectedProfile = computed(() => tab.value === 'mine' ? { displayName: auth.user?.displayName ?? '我的家', style: style.value, mementos: earnedMementos(achievements.value).filter(a => codes.value.includes(a.code)) } : visiting.value)
const palette = computed(() => HOME_STYLES[selectedProfile.value?.style ?? 'original'] ?? HOME_STYLES.original)
let attempt: ReturnType<typeof postcardAttempt> | null = null
let generation = 0
const message = (e: unknown) => (e as ApiError)?.message ?? '暂时没能完成，请稍后重试。'
async function load() {
  const token = ++generation; loading.value = true; error.value = ''; visiting.value = null
  try {
    const [d,m,a,r] = await Promise.all([api.get<typeof directory.value>('/town/visits'),api.get<VisitProfile>('/town/visits/mine'),api.get<Achievement[]>('/achievements'),api.get<VisitPostcard[]>('/town/visits/received')])
    if (token !== generation) return
    directory.value=d;mine.value=m;achievements.value=a;received.value=r;enabled.value=m.enabled;style.value=m.style;codes.value=m.mementos.map(x => x.code)
  } catch(e) { if(token===generation) error.value=message(e) }
  finally { if(token===generation) loading.value=false }
}
function switchTab(value: typeof tab.value) { generation++; loading.value=false; tab.value=value;visiting.value=null;body.value='';attempt=null;feedback.value='';error.value='' }
async function visit(id: string) {
  const token = ++generation; loading.value=true;error.value='';visiting.value=null;body.value='';attempt=null;feedback.value=''
  try { const p = await api.get<VisitProfile>(`/town/visits/${encodeURIComponent(id)}`); if(token===generation) visiting.value=p }
  catch(e) { if(token===generation) error.value=message(e) }
  finally { if(token===generation) loading.value=false }
}
async function save(disable = false) {
  busy.value=true;error.value='';feedback.value=''
  try {
    const p=await api.put<VisitProfile>('/town/visits/mine',{enabled:disable?false:enabled.value,style:style.value,mementoCodes:codes.value})
    mine.value=p;enabled.value=p.enabled;feedback.value=p.enabled?'已分享，只有已接受的好友可以拜访。':'分享已关闭，好友无法再读取这张居家小景。'
  } catch(e) { error.value=message(e) } finally { busy.value=false }
}
function toggle(code: string) { if(codes.value.includes(code)) codes.value=codes.value.filter(c=>c!==code);else if(codes.value.length<4) codes.value.push(code) }
async function send() {
  if(!visiting.value||!body.value.trim()||busy.value)return
  const owner=visiting.value.publicId;attempt=postcardAttempt(attempt,owner,body.value);busy.value=true;error.value='';feedback.value=''
  try { await api.post(`/town/visits/${encodeURIComponent(owner)}/postcards`,{body:attempt.body,requestKey:attempt.requestKey});body.value='';attempt=null;feedback.value='明信片已送到，主人下次打开时可以看到。' }
  catch(e) { error.value=message(e); if((e as ApiError)?.status===404)visiting.value=null }
  finally { busy.value=false }
}
async function remove(id:string) {
  busy.value=true;error.value=''
  try {await api.delete(`/town/visits/postcards/${encodeURIComponent(id)}`);received.value=received.value.filter(p=>p.publicId!==id);feedback.value='明信片已移除。'}
  catch(e){error.value=message(e)}finally{busy.value=false}
}
onMounted(load)
onBeforeUnmount(()=>{generation++})
</script>
<template>
<section class="visits" aria-label="好友异步拜访">
  <p class="intro">看看朋友愿意分享的居家小景，留一张明信片。这里是主人精选的静态展示，不代表实时房间或在线状态。</p>
  <nav aria-label="拜访内容"><button type="button" :aria-pressed="tab==='directory'" :disabled="busy||loading" @click="switchTab('directory')">去朋友家</button><button type="button" :aria-pressed="tab==='mine'" :disabled="busy||loading" @click="switchTab('mine')">我的分享</button><button type="button" :aria-pressed="tab==='received'" :disabled="busy||loading" @click="switchTab('received')">收到的明信片</button></nav>
  <p v-if="error" role="alert">{{error}} <button type="button" :disabled="busy" @click="load">重新加载</button></p>
  <p v-if="loading" role="status">正在整理拜访内容…</p>
  <template v-else>
    <template v-if="tab==='directory'">
      <div v-if="!visiting" class="directory"><p v-if="!directory.length">还没有好友开放分享。可以在「我的分享」先布置自己的居家小景。</p><button v-for="friend in directory" :key="friend.publicId" type="button" @click="visit(friend.publicId)">{{friend.displayName}}的家 · 拜访</button><button type="button" @click="load">刷新开放名单</button></div>
      <button v-else type="button" :disabled="busy" @click="visiting=null">返回好友名单</button>
    </template>
    <div v-if="tab==='mine' && mine" class="settings">
      <label><input v-model="enabled" type="checkbox" :disabled="busy" />允许已接受的好友查看</label>
      <p>默认关闭。保存后仅分享昵称、所选配色和最多四件纪念的名称、获得缘由与日期。不分享任务、心情、信件或位置。</p>
      <label>分享配色 <select v-model="style" :disabled="busy"><option v-for="(s,id) in HOME_STYLES" :key="id" :value="id">{{s.name}}</option></select></label>
      <button type="button" :disabled="busy" @click="style=readHomePreference(auth.user?.publicId??'').style">使用当前浏览器的家居配色</button>
      <fieldset :disabled="busy"><legend>选择真实纪念（{{codes.length}}/4）</legend><p v-if="!earnedMementos(achievements).length">还没有获得的纪念，也可以只分享配色。</p><label v-for="a in earnedMementos(achievements)" :key="a.code"><input type="checkbox" :checked="codes.includes(a.code)" :disabled="codes.length>=4&&!codes.includes(a.code)" @change="toggle(a.code)" />{{a.name}}</label></fieldset>
      <div class="actions"><button type="button" :disabled="busy" @click="save()">保存分享设置</button><button v-if="mine.enabled" type="button" :disabled="busy" @click="save(true)">立即关闭分享</button></div>
      <p class="scope">以下为发布预览。设置保存在账号中；修改本机家居配色不会自动更新此分享。</p>
    </div>
    <article v-if="selectedProfile" class="portrait" :style="{'--rug':palette.rug,'--chair':palette.chair,'--lamp':palette.lamp}">
      <h3>{{selectedProfile.displayName}}的居家小景</h3><span class="scope">主人精选 · 异步展示</span>
      <div class="room" aria-label="所选家居配色预览"><span class="window"/><span class="chair"/><span class="rug"/><span class="lamp"/><span class="table"/></div>
      <p>{{palette.name}}</p><div v-for="m in selectedProfile.mementos" :key="m.code" class="memory"><strong>{{m.name}}</strong><time>{{mementoDate(m.earnedAt,auth.user?.timezone??'Asia/Shanghai')}}</time><p v-if="m.triggerText">获得缘由：{{m.triggerText}}</p></div><p v-if="!selectedProfile.mementos.length" class="scope">主人留了一些空位。</p>
    </article>
    <form v-if="tab==='directory'&&visiting" @submit.prevent="send"><label for="visit-postcard">留一张明信片（仅主人可在收件箱查看）</label><textarea id="visit-postcard" v-model="body" maxlength="300" rows="3" :disabled="busy" placeholder="写一点想对朋友说的话…"/><button type="submit" :disabled="busy||!body.trim()">{{busy?'正在投递…':'投递明信片'}}</button></form>
    <div v-if="tab==='received'"><p class="scope">最近 100 张明信片，仅你可查看。删除后不再显示。</p><p v-if="!received.length">信箱里还没有拜访明信片。</p><article v-for="p in received" :key="p.publicId" class="postcard"><strong>{{p.senderName}}</strong><time>{{mementoDate(p.createdAt,auth.user?.timezone??'Asia/Shanghai')}}</time><p>{{p.body}}</p><button type="button" :disabled="busy" @click="remove(p.publicId)">删除这张明信片</button></article></div>
  </template>
  <p role="status">{{feedback}}</p>
</section>
</template>
<style scoped>
.visits{font-size:13px;line-height:1.8;color:#493f32;min-width:0}.visits nav,.actions{display:flex;flex-wrap:wrap;gap:7px;margin:12px 0}.visits button,.visits select{border:1px solid #c5b99f;background:#fffaf0;color:#493f32;border-radius:8px;padding:8px 11px;cursor:pointer}.visits button[aria-pressed=true]{background:#e4eddd;border-color:#657d59}.visits button:disabled{opacity:.5;cursor:default}.visits button:focus-visible{outline:3px solid #57735c;outline-offset:2px}.intro,.scope{color:#786e60}.scope{font-size:11px}.directory,.settings,fieldset{display:grid;gap:10px}.settings fieldset{max-height:200px;overflow:auto;border:1px solid #d4c7b2;border-radius:8px}.settings p{margin:0}.portrait{border:1px solid #d3c3a7;background:#fbf4e5;padding:16px;border-radius:12px;margin-top:14px}.portrait h3{margin:0;font-size:17px}.room{position:relative;height:132px;margin:14px 0;background:linear-gradient(#dfd3bd 0 46%,#cbb697 46%);border:4px solid #bba584;border-radius:7px;overflow:hidden}.room span{position:absolute;display:block}.window{left:22%;top:12px;width:43px;height:38px;background:#d3e4e3;border:5px solid #fff4df}.rug{left:20%;bottom:8px;width:64%;height:37px;border:3px solid #b69c76;border-radius:50%;background:var(--rug);filter:brightness(.8)}.chair{left:23%;bottom:33px;width:56px;height:45px;border:7px solid #8b6c4f;border-radius:12px;background:var(--chair);z-index:1}.lamp{right:20%;top:32px;width:26px;height:23px;background:var(--lamp);border-radius:15px 15px 2px 2px;border-bottom:42px solid #957c56}.table{left:51%;bottom:24px;width:44px;height:18px;background:#896749;border-radius:50%;z-index:2}.memory,.postcard{background:#fffaf0;border:1px solid #dbcdb8;padding:12px;margin-top:10px;border-radius:7px;overflow-wrap:anywhere}.visits time{display:block;color:#7e7363;font-size:11px}.memory p,.postcard p{margin:6px 0;white-space:pre-wrap}.visits form{display:grid;gap:9px;margin-top:18px}.visits textarea{width:100%;box-sizing:border-box;border:1px solid #c5b99f;border-radius:8px;padding:10px;background:#fffaf0;color:#493f32;font:inherit;resize:vertical}
</style>
