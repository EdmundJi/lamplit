import type PhaserNs from 'phaser'
import type { GreetCooldowns } from '../walkers'
import type { CollisionWorld, Point, FurnitureObstacle } from '../collision'
import { TownAtmosphere } from '../atmosphere'
import type { FurnitureItem, VenueFurniture } from '../town-furniture'
import type { ObservationLeg } from '../observation-mode'
import { type TownLifeStage } from '../town-life-stage'
import { createTownFacilities } from '../town-facilities'
import type { TownTravel, TownNearby, Walker, LabourAnim, Vehicle, SelfKeys } from './shared'
import type { TownRuntime } from './runtime'
import { lifecycleMethods, type LifecycleMethods } from './lifecycle'
import { worldMethods, type WorldMethods } from './world'
import { facilitiesMethods, type FacilitiesMethods } from './facilities'
import { npcsMethods, type NpcsMethods } from './npcs'
import { conversationsMethods, type ConversationsMethods } from './conversations'
import { navigationMethods, type NavigationMethods } from './navigation'

export function createTownScene(runtime: TownRuntime) {
  const Phaser = runtime.Phaser
  class TownScene extends Phaser.Scene {
    readonly runtime = runtime
    walkers: Walker[] = [];
    vehicles: Vehicle[] = [];
    glows: PhaserNs.GameObjects.GameObject[] = [];
    nightOverlay: PhaserNs.GameObjects.Graphics | null = null;
    night = false;
    dragStart: {
        x: number;
        y: number;
        scrollX: number;
        scrollY: number;
    } | null = null;
    dragged = false;
    buildingCenters = new Map<string, {
        x: number;
        y: number;
    }>();
    roofTop = new Map<string, {
        x: number;
        y: number;
    }>();
    residentIndex = new Map<string, number>();
    plotObjects = new Map<string, PhaserNs.GameObjects.GameObject[]>();
    plotSignature = new Map<string, string>();
    selfWalker: Walker | null = null;
    selfPath: Point[] = [];
    travel: TownTravel | null = null;
    nearby: TownNearby | null = null;
    conversation: {
        walker: Walker;
        code: string;
        since: number;
        initialPlace: string;
        phase: 'talking' | 'leaving';
        reason?: string;
        until?: number;
        state: Walker['state'];
        timer: number;
    } | null = null;
    clockFrameAt = -1;
    clockMinute = 0;
    nextPresenceAt = 0;
    nextNearbyAt = 0;
    eventDecor: PhaserNs.GameObjects.GameObject[] = [];
    eventSignature = '';
    entrances = new Map<string, Point>();
    publicRoofs = new Map<string, number>();
    destinationMarker: PhaserNs.GameObjects.Graphics | null = null;
    selfKeys: SelfKeys | null = null;
    /** HUD toggle: every self move runs until it is turned off. Shift always runs regardless. */
    runMode = false;
    greetCooldowns: GreetCooldowns = new Map();
    /** 观察模式的巡游路线；空数组表示没在观察模式。 */
    observationItinerary: ObservationLeg[] = [];
    observationStartedAt = 0;
    /** 观察模式里下一次让镜头里的人开口的时间。 */
    nextAmbientLineAt = 0;
    /** 劳作动画的几何；素材没生成时保持 null，NPC 就退回站着，不报错。 */
    labourGeometry: Record<string, LabourAnim> | null = null;
    /** 主动搭话的全镇节流：预算之外再加一层间隔，免得三次额度在同一秒里烧完。 */
    nextRosterCheckAt = 0;
    nextInitiativeAt = 0;
    /** Walkable ground + building obstacles the self avatar's free 8-directional movement is
     * checked against; rebuilt whenever a plot's height changes (level up adds a floor). */
    collisionWorld: CollisionWorld = { walkable: [], obstacles: [] };
    /** Camera smoothly follows the self avatar while it moves under keyboard/click control, but
     * a manual drag takes over and stays in charge for a few seconds afterwards. */
    cameraFollowPausedUntil = 0;
    followingCamera = false;
    atmosphere: TownAtmosphere | null = null;
    /** 场地配套家具（健身房/咖啡馆/公园） */
    venueFurniture: VenueFurniture[] = [];
    /** 街道家具列表 */
    streetFurnitureItems: FurnitureItem[] = [];
    /** 所有家具碰撞矩形（用于 buildCollisionWorld） */
    furnitureCollisions: FurnitureObstacle[] = [];
    /** 当前正在交互的家具 ID */
    activeFurnitureId: string | null = null;
    usingLegacyFurniture = false;
    seatedLegs: PhaserNs.GameObjects.Graphics | null = null;
    /** 交互气泡精灵 */
    interactionBubble: PhaserNs.GameObjects.Text | null = null;
    lifeStage: TownLifeStage | null = null;
    facilities: ReturnType<typeof createTownFacilities> | null = null;
    facilityNpcs = new Map<Walker, {
        id: 'coffee' | 'planter' | 'books';
        path: Point[];
        phase: 'walking' | 'using';
        until: number;
    }>();
    nextFacilityVisitAt = 0;
    facilityCooldowns = new Map<string, number>();
    terraceInvited = new Set<string>();
    terraceInvitationUntil = 0;
    constructor() { super('town') }
  }
  // Interface merging describes the methods installed on the prototype below. There are
  // no copied state snapshots: each method reads the same runtime object through this.runtime.
  interface TownScene extends LifecycleMethods, WorldMethods, FacilitiesMethods, NpcsMethods, ConversationsMethods, NavigationMethods {}
  Object.assign(TownScene.prototype, lifecycleMethods, worldMethods, facilitiesMethods, npcsMethods, conversationsMethods, navigationMethods)
  return TownScene
}
export type TownSceneInstance = InstanceType<ReturnType<typeof createTownScene>>
