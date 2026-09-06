import './commands'
import './probe'

/**
 * 错误处理策略。
 *
 * 这里原本是一条无条件静音：凡是 message/stack 里出现 phaser|WebGL|texture|getContext|
 * town.engine 的未捕获错误，一律 `return false` 放行。理由写的是"视图层对画面报错有兜底，
 * 不该让整条用例失败"。
 *
 * 代价是：`create()` 在半途抛错中断（小人没有键盘、镜头没有跟随对象）这种致命故障，
 * 在日志里一个字都不会出现，只会表现为几条摸不着头脑的"selfWalker 为 null"。
 * 真正需要的不是静音，而是**收集起来、跑完、最后一起报**——
 *   · 用例照常跑完，截图和快照都拿得到，方便定位；
 *   · 结束时把攒下的错误一次性亮出来，测试变红。
 *
 * 确实预期会抛错的用例（比如故意让图集 404 验证降级），显式声明放行的模式：
 *
 *   cy.allowErrors(/texture|atlas/)
 */
const uncaught: { message: string; stack: string }[] = []
let allowed: RegExp[] = []

Cypress.Commands.add('allowErrors', (...patterns: RegExp[]) => {
  allowed.push(...patterns)
})

beforeEach(() => {
  uncaught.length = 0
  allowed = []
})

Cypress.on('uncaught:exception', err => {
  uncaught.push({ message: err.message, stack: (err.stack ?? '').split('\n').slice(0, 6).join('\n') })
  return false // 不在抛出的那一刻打断；afterEach 里统一结算
})

afterEach(() => {
  const unexpected = uncaught.filter(item => !allowed.some(pattern => pattern.test(`${item.message}\n${item.stack}`)))
  if (unexpected.length === 0) return
  const detail = unexpected.map((item, index) => `\n[${index + 1}] ${item.message}\n${item.stack}`).join('\n')
  throw new Error(`页面抛了 ${unexpected.length} 个未捕获错误（预期内的请用 cy.allowErrors(/.../) 声明）：${detail}`)
})
