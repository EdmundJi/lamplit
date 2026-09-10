// town-stage 静态 mockup 的行为层。CompanionScene 是唯一一处真实组件挂载；其余全部是纯 DOM。
import { createApp, h, reactive } from 'vue'
import CompanionScene from '/src/modules/companion/CompanionScene.vue'

// ---------------------------------------------------------------------------
// 世界几何：抄自 frontend/src/modules/companion/companion-art.ts 的 PLACE_FRAMES /
// COMPANION_WORLD_SIZE，只用来给 CSS 镜头算一个"看向哪"的目标点，不改场景本身。
// ---------------------------------------------------------------------------
const WORLD = { width: 1248, height: 768 }
// 每个导航项对应的镜头落点（世界像素坐标），手调过，不是几何中心。
const CAMERA_TARGETS = {
  street: { x: 440, y: 410 }, // PLACE_FRAMES.street 中心附近，门前小街
  board: { x: 660, y: 195 }, // 咖啡馆门口 / 点单台一带，公告板落在这里最合理
  home: { x: 360, y: 410 }, // PLACE_FRAMES.home 中心，六间小屋所在的一片
  cafe: { x: 840, y: 270 }, // 咖啡馆窗边座位一带，最热闹的角落
  garden: { x: 1128, y: 329 }, // PLACE_FRAMES.garden 中心
}
// selectedPlace 传给场景用来点亮"地点高亮框"，场景只认 home/cafe/garden/street。
const SCENE_PLACE = { street: 'street', board: 'cafe', home: 'home', cafe: 'cafe', garden: 'garden' }
const PLACE_LABEL = { street: '门前小街', board: '公告板', home: '归家小屋', cafe: '慢慢咖啡', garden: '门前花园' }

const PERIOD_MINUTES = { morning: 480, afternoon: 840, evening: 1110, night: 1320 }
const PERIOD_GREETING = {
  morning: '早，新的一天开始了',
  afternoon: '午后，找点安静的事做',
  evening: '傍晚了，慢慢把今天收个尾',
  night: '夜深了，小镇也要睡了',
}

// ---------------------------------------------------------------------------
// 居民 fixture —— 只在这个 harness 里存在，不接后端。
// ---------------------------------------------------------------------------
const baseResidents = [
  { id: 'self', name: '你', role: 'user', location: 'home-self', activity: 'rest', action: '在家歇着' },
  { id: 'ahe', name: '阿禾', role: '店主', location: 'cafe', positionId: 'cafe-counter', activity: 'prepare', action: '准备热饮' },
  { id: 'achuan', name: '阿川', role: '插画师', location: 'cafe', positionId: 'cafe-worktable', activity: 'create', action: '写海报上的小故事' },
  { id: 'alin', name: '阿林', role: '园艺爱好者', location: 'garden', positionId: 'garden-plot', activity: 'garden', action: '给花圃浇水' },
  { id: 'xiaxia', name: '小夏', role: '邻居', location: 'home-artist', activity: 'rest', action: '歇一会儿' },
]

const sceneState = reactive({
  residents: baseResidents.map(r => ({ ...r })),
  minutes: PERIOD_MINUTES.morning,
  selectedPlace: 'street',
  overview: true, // 舞台横条和全屏小镇都用整条街的全景相机；镜头移动靠外层 CSS transform 做
  cafeOpen: true,
  textBubbles: true,
  conversations: [],
})

createApp({ setup() { return () => h(CompanionScene, { ...sceneState }) } }).mount('#stage-host')
document.documentElement.dataset.period = 'morning'
window.__townState = sceneState // 调试用：控制台里能看当前 residents/conversations 快照

// ---------------------------------------------------------------------------
// DOM 引用
// ---------------------------------------------------------------------------
const stageShell = document.getElementById('stage-shell')
const stageCamera = document.getElementById('stage-camera')
const stageHost = document.getElementById('stage-host')
const stageLabel = document.getElementById('stage-place-label')
const fullscreenBar = document.getElementById('stage-fullscreen-bar')
const btnBack = document.getElementById('btn-back')
const navTown = document.getElementById('nav-town')
const navItems = [...document.querySelectorAll('.nav-item[data-panel]')]
const periodButtons = [...document.querySelectorAll('.period-switch button')]
const greetingEl = document.getElementById('greeting')

let currentPanel = 'today'
let currentPlace = 'street'
let isFullscreen = false

// ---------------------------------------------------------------------------
// 镜头：世界像素 -> 屏幕像素的映射只在挂载完成、容器尺寸稳定之后量一次。
// 之后所有地点切换只挪动 translate，不再改 #stage-host 的实际尺寸——避免
// CompanionScene 内部用 getBoundingClientRect() 量出的宽高被外层 transform: scale
// 污染（那样会导致 Phaser 画布按"已经被缩放过"的尺寸再画一遍，越缩越小）。
// ---------------------------------------------------------------------------
let worldMap = null
function measureWorldMap() {
  const canvasBox = stageHost.querySelector('.companion-scene__canvas')
  if (!canvasBox) return false
  const rect = canvasBox.getBoundingClientRect()
  if (!rect.width || !rect.height) return false
  const zoom = Math.min(rect.width / WORLD.width, rect.height / WORLD.height)
  worldMap = {
    zoom,
    padX: (rect.width - WORLD.width * zoom) / 2,
    padY: (rect.height - WORLD.height * zoom) / 2,
  }
  return true
}
function waitForWorldMap() {
  if (measureWorldMap()) { applyCamera(currentPlace, false); return }
  requestAnimationFrame(waitForWorldMap)
}
waitForWorldMap()

function applyCamera(place, animate = true) {
  currentPlace = place
  if (isFullscreen || !worldMap) return
  const target = CAMERA_TARGETS[place] || CAMERA_TARGETS.street
  const shellRect = stageShell.getBoundingClientRect()
  const canvasX = worldMap.padX + target.x * worldMap.zoom
  const canvasY = worldMap.padY + target.y * worldMap.zoom
  const x = shellRect.width / 2 - canvasX
  const y = shellRect.height / 2 - canvasY
  stageCamera.style.transition = animate ? '' : 'none'
  stageCamera.style.transform = `translate(${x}px, ${y}px)`
  if (!animate) requestAnimationFrame(() => { stageCamera.style.transition = '' })
}
window.addEventListener('resize', () => { if (!isFullscreen) applyCamera(currentPlace, false) })

function flashLabel(place) {
  stageLabel.textContent = PLACE_LABEL[place] || PLACE_LABEL.street
  stageLabel.classList.remove('is-flash')
  // 强制 reflow 让下一次加回 class 真的重新触发一次高亮闪烁
  void stageLabel.offsetWidth
  stageLabel.classList.add('is-flash')
  setTimeout(() => stageLabel.classList.remove('is-flash'), 420)
}

function goToPlace(place, { flash = true } = {}) {
  sceneState.selectedPlace = SCENE_PLACE[place] || 'street'
  applyCamera(place)
  if (flash) flashLabel(place)
}

// ---------------------------------------------------------------------------
// 侧栏导航：面板零延迟切换 + 镜头平移
// ---------------------------------------------------------------------------
function selectPanel(panel, place) {
  currentPanel = panel
  navItems.forEach(btn => btn.classList.toggle('is-active', btn.dataset.panel === panel))
  document.querySelectorAll('.panel').forEach(p => { p.hidden = p.id !== `panel-${panel}` })
  goToPlace(place)
}
navItems.forEach(btn => btn.addEventListener('click', () => selectPanel(btn.dataset.panel, btn.dataset.place)))

// ---------------------------------------------------------------------------
// 横条 <-> 全屏小镇：View Transitions 形变
// ---------------------------------------------------------------------------
function setFullscreen(next) {
  isFullscreen = next
  stageShell.classList.toggle('fullscreen', next)
  stageShell.classList.toggle('docked', !next)
  fullscreenBar.hidden = !next
  navTown.classList.toggle('is-active', next)
  if (next) {
    stageCamera.style.transform = 'none'
    sizeFullscreenHost()
  } else {
    stageHost.style.width = '1000px'
    // 回到工作台：重新量一次（容器尺寸这次是真的变了，是合法的 resize），再复位镜头。
    requestAnimationFrame(() => { measureWorldMap(); applyCamera(currentPlace, false) })
  }
}
function sizeFullscreenHost() {
  const vw = window.innerWidth, vh = window.innerHeight
  const w = Math.max(vw, Math.round(vh * 1.86))
  stageHost.style.width = `${w}px`
}
window.addEventListener('resize', () => { if (isFullscreen) sizeFullscreenHost() })

function expandTown() {
  if (isFullscreen) return
  console.log('[town-stage] view transition: docked -> fullscreen')
  if (document.startViewTransition) document.startViewTransition(() => setFullscreen(true))
  else setFullscreen(true)
}
function collapseTown() {
  if (!isFullscreen) return
  console.log('[town-stage] view transition: fullscreen -> docked')
  if (document.startViewTransition) document.startViewTransition(() => setFullscreen(false))
  else setFullscreen(false)
}

stageShell.addEventListener('click', event => {
  if (isFullscreen) return
  if (event.target.closest('#stage-fullscreen-bar')) return
  expandTown()
})
btnBack.addEventListener('click', event => { event.stopPropagation(); collapseTown() })
navTown.addEventListener('click', () => { isFullscreen ? collapseTown() : expandTown() })

// ---------------------------------------------------------------------------
// 因果可见：完成"读十页书" -> 小人真的走到咖啡馆窗边，阿禾冒气泡
// ---------------------------------------------------------------------------
const btnCompleteRead = document.getElementById('btn-complete-read')
const taskReadRow = document.getElementById('task-read')
btnCompleteRead.addEventListener('click', () => {
  if (btnCompleteRead.disabled) return
  btnCompleteRead.disabled = true
  btnCompleteRead.textContent = '已完成'
  taskReadRow.classList.add('is-done')
  // 场景一旦出现任何一个带 positionId 的居民，companion-scene.ts 里的对话气泡（resident-topic/
  // resident-speech）就整体失效——跟这个居民是不是对话参与者、positionId 是不是合法值都无关，
  // 哪怕值是 null 也一样，像是 sync()/update() 里某处按对象形状做了判断。这是 frontend/src 里
  // 既有的问题，复现方法见报告，这里只能绕开：要显示"阿禾冒气泡"就不能有人带 positionId，
  // 所以完成任务这一刻把所有居民的 positionId 都摘掉，退回到按 activity 落座的通用位置。
  sceneState.residents = sceneState.residents.map(r => {
    const { positionId, ...rest } = r
    return r.id === 'self' ? { ...rest, location: 'cafe', activity: 'read', action: '读一会儿书' } : rest
  })
  sceneState.conversations = [{
    id: 'read-together',
    place: 'cafe',
    status: 'active',
    participantIds: ['self', 'ahe'],
    turns: [{ speakerId: 'ahe', emoji: '☕', text: '今天也来啦，老位置给你留着。', at: new Date().toISOString() }],
  }]
  // 面板还留在"今日"，但镜头跟着小人一起挪到慢慢咖啡——这是要看见的那一下。
  goToPlace('cafe')
})

// ---------------------------------------------------------------------------
// 时段：早/午/晚/夜
// ---------------------------------------------------------------------------
periodButtons.forEach(btn => btn.addEventListener('click', () => {
  const period = btn.dataset.period
  periodButtons.forEach(b => b.classList.toggle('is-active', b === btn))
  sceneState.minutes = PERIOD_MINUTES[period]
  document.documentElement.dataset.period = period
  if (period === 'night') document.documentElement.dataset.theme = 'dark'
  else delete document.documentElement.dataset.theme
  const greeting = PERIOD_GREETING[period]
  greetingEl.textContent = greeting
  const todayTitle = document.querySelector('#panel-today .panel-title')
  if (todayTitle) todayTitle.textContent = greeting
}))
