import type { ProbeSnapshot } from '../../src/modules/town/town-probe'

/**
 * 把 `window.__town`（src/modules/town/town-probe.ts）包成 Cypress 命令，并额外提供
 * 一条 `cy.record()`：每一步都把"此刻世界长什么样"的中文描述记下来，用例结束后
 * 一次性写进 test-results/town-probe/<用例名>.md。
 *
 * 这份 Markdown 是给 agent 读的：它没法看截图，但读得懂
 * "我站在 (1768,936)，身边有 邮递员[npc] 42px，dock 收起，开着的面板 无"。
 */
type TownApi = {
  snapshot: () => ProbeSnapshot
  describe: () => string
  walk: (direction: 'left' | 'right' | 'up' | 'down', ms?: number) => Promise<{ ok: boolean; reason: string; moved: number; from: { x: number; y: number } | null; to: { x: number; y: number } | null }>
  walkTo: (x: number, timeoutMs?: number) => Promise<{ ok: boolean; reason: string; x: number | null }>
  reset: () => void
}

function api(win: Cypress.AUTWindow): TownApi {
  const town = (win as unknown as { __town?: TownApi }).__town
  if (!town) throw new Error('window.__town 不存在：探针没装上（只在 import.meta.env.DEV 下安装，确认跑的是 vite dev 而不是预览构建）')
  return town
}

let journal: string[] = []

beforeEach(() => { journal = [] })

Cypress.Commands.add('townSnapshot', () => cy.window({ log: false }).then(win => api(win).snapshot()))

Cypress.Commands.add('townDescribe', () => cy.window({ log: false }).then(win => api(win).describe()))

/** 记一步：标题 + 此刻的世界描述。这是 agent 事后复盘"哪一步开始不对"的依据。 */
Cypress.Commands.add('record', (step: string) =>
  cy.window({ log: false }).then(win => {
    const text = api(win).describe()
    journal.push(`## ${step}\n\n\`\`\`\n${text}\n\`\`\`\n`)
    Cypress.log({ name: 'record', message: step, consoleProps: () => ({ 世界: text }) })
    return cy.wrap(text, { log: false })
  }),
)

Cypress.Commands.add('townWalk', (direction: 'left' | 'right' | 'up' | 'down', ms = 700) =>
  cy.window({ log: false }).then(win => api(win).walk(direction, ms)),
)

Cypress.Commands.add('townWalkTo', (x: number, timeoutMs = 8000) =>
  cy.window({ log: false }).then({ timeout: timeoutMs + 2000 }, win => api(win).walkTo(x, timeoutMs)),
)

afterEach(function afterEachHook() {
  if (journal.length === 0) return
  const test = this.currentTest
  const status = test?.state === 'passed' ? '✅ 通过' : `❌ ${test?.state ?? '未知'}`
  const body = [
    `# ${test?.fullTitle() ?? '未命名用例'}`,
    '',
    `结果：${status}`,
    test?.err ? `\n失败原因：\n\n\`\`\`\n${test.err.message}\n\`\`\`\n` : '',
    '',
    ...journal,
  ].join('\n')
  const safe = (test?.fullTitle() ?? 'unnamed').replace(/[^\p{L}\p{N}]+/gu, '-').slice(0, 80)
  cy.writeFile(`../test-results/town-probe/${safe}.md`, body, { log: false })
})

export {}
