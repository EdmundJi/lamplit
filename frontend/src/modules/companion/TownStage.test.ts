import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import TownStage from './TownStage.vue'
import { useWorkspaceModeStore } from '../../shared/ui/workspace-mode.store'

// TownStage statically imports residentTarget/scenePlace from companion-scene.ts, which imports
// real Phaser at module scope for its `class CompanionStreetScene extends Phaser.Scene` - Phaser's
// own import-time feature detection (CanvasFeatures.js) crashes under jsdom without this, same as
// CompanionScene.test.ts already works around.
vi.mock('phaser', () => ({ default: { AUTO: 0, Scale: { NONE: 0, NO_CENTER: 0 }, Scene: class {}, Game: class {
  canvas = document.createElement('canvas'); scale = { resize: vi.fn() }; events = { once: vi.fn() }; loop = { sleep: vi.fn(), wake: vi.fn() }; destroy() {}
} } }))
// TownStage teleports the one shared CompanionScene into whichever DOM slot the current mode/route
// calls for (#town-strip-slot, #town-minimal-slot or #town-stage-slot). Real Phaser is irrelevant
// here - only *which slot got it* and *what framing it was given* - so CompanionScene itself is a
// thin stub that exposes the props under test as data attributes.
vi.mock('./CompanionScene.vue', () => ({
  // TownStage.vue loads this through defineAsyncComponent(() => import(...)); Vue's async-component
  // loader only unwraps a resolved module's `.default` when it recognises the result as an ES
  // module (checks `__esModule`) - without this marker it treats the whole { default } wrapper
  // itself as the component, which vue-test-utils then probes for internal flags (__isTeleport,
  // ...) that don't exist on it.
  __esModule: true,
  default: {
    name: 'CompanionScene',
    props: ['dockedFrameHeight', 'cameraMode', 'chrome', 'cameraTarget', 'residents'],
    template: '<div class="companion-scene-stub" :data-docked-frame-height="dockedFrameHeight" :data-camera-mode="cameraMode" :data-chrome="chrome ? \'true\' : \'false\'" />',
  },
}))
// A joined world, minimal but real enough for the template's `v-if="showScene && world"` to mount
// the (stubbed) scene at all - computeTarget()'s own docked branches (every case here, since none
// of these visit /town) never look at `world`'s contents, only its presence for that v-if.
const fixtures = vi.hoisted(() => {
  const actor = { id: 'self', name: '我', role: 'user', place: 'cafe', label: '安静读书', activity: 'study', x: 1, y: 1, until: '' }
  const world = { id: 'world', name: '梧桐小街', timezone: 'Asia/Shanghai', revision: 1, joinedAt: '2026-09-08T00:00:00Z', updatedAt: '2026-09-08T00:00:00Z', weather: 'sunny', period: 'morning', avatar: actor, residents: [], intents: [], diary: [], memories: [], focus: null, offlineSummary: null }
  return { world }
})
vi.mock('./companion.api', () => ({
  companionApi: {
    load: vi.fn().mockResolvedValue({ joined: true, world: fixtures.world }),
    join: vi.fn(),
    advance: vi.fn(),
    intend: vi.fn(),
    cancel: vi.fn(),
  },
}))

let router: Router
let wrapper: ReturnType<typeof mount> | undefined

beforeEach(async () => {
  // useWorkspaceModeStore restores `minimal` from localStorage on init - clear it so one test's
  // setMinimal(true) doesn't leak into the next test's fresh Pinia instance.
  localStorage.clear()
  setActivePinia(createPinia())
  document.body.innerHTML = '<div id="town-strip-slot"></div><div id="town-minimal-slot"></div>'
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/today')
  await router.isReady()
})

afterEach(() => { wrapper?.unmount(); document.body.innerHTML = '' })

async function mountStage() {
  wrapper = mount(TownStage, { global: { plugins: [router] } })
  await flushPromises()
  // The scene mounts only once the browser is idle (or a 300ms fallback timer) - advance past it.
  await new Promise(resolve => setTimeout(resolve, 350))
  await flushPromises()
  return wrapper
}

describe('TownStage slot targeting (全镇只有一个 Phaser 实例)', () => {
  it('docks into the street strip in growth mode, framed for a wide short band', async () => {
    await mountStage()
    const scene = document.querySelector('#town-strip-slot .companion-scene-stub')
    expect(scene).not.toBeNull()
    expect(document.querySelector('#town-minimal-slot .companion-scene-stub')).toBeNull()
    expect(scene!.getAttribute('data-docked-frame-height')).toBe('128')
    expect(scene!.getAttribute('data-camera-mode')).toBe('docked')
    expect(scene!.getAttribute('data-chrome')).toBe('false')
  })

  it('docks the same instance into the minimal rail instead (左清单右小镇), with a taller frame budget', async () => {
    useWorkspaceModeStore().setMinimal(true)
    await mountStage()
    expect(document.querySelector('#town-strip-slot .companion-scene-stub')).toBeNull()
    const scene = document.querySelector('#town-minimal-slot .companion-scene-stub')
    expect(scene).not.toBeNull()
    // The rail is narrower/taller than the strip - reusing the strip's 128 would crop to a sliver,
    // so it gets its own, larger budget (see TownStage.vue's dockedFrameHeight computed).
    expect(scene!.getAttribute('data-docked-frame-height')).toBe('360')
    expect(scene!.getAttribute('data-camera-mode')).toBe('docked')
  })

  it('moves the one instance from the strip to the rail when the mode switches at runtime', async () => {
    await mountStage()
    expect(document.querySelector('#town-strip-slot .companion-scene-stub')).not.toBeNull()

    useWorkspaceModeStore().setMinimal(true)
    await flushPromises()

    expect(document.querySelector('#town-strip-slot .companion-scene-stub')).toBeNull()
    expect(document.querySelector('#town-minimal-slot .companion-scene-stub')).not.toBeNull()
    // Only one instance ever exists, in either slot.
    expect(document.querySelectorAll('.companion-scene-stub')).toHaveLength(1)
  })
})
