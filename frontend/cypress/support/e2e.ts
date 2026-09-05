import './commands'

// Phaser 在 WebGL 上下文丢失、或某个图集帧缺失时会往 window 抛错。小镇的视图层对这类
// 错误已经有兜底（TownView 的 engineError / onErrorCaptured），所以画面报错不应该让
// 整条用例失败——真正要断言的是"兜底提示出现了没有"。
Cypress.on('uncaught:exception', err => {
  const where = `${err.message}\n${err.stack ?? ''}`
  if (/phaser|WebGL|texture|getContext|town\.engine/i.test(where)) return false
  return undefined
})
