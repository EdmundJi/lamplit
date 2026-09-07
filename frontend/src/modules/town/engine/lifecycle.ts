import { createTownCompanion } from './companion'
import { inCompanionPark } from '../companion-motion'
import { RESIDENT_WALK_SPEED, RUN_ANIM_SCALE, dominantDirection, moveSpeed, stepToward, stepTowardPoint } from '../walkers'
import { resolveMove } from '../collision'
import type { Point } from '../collision'
import { TownAtmosphere, approach, paletteForTime, seasonForMonth } from '../atmosphere'
import type PhaserNs from 'phaser'
import { weatherForDate } from '../world-life'
import { type TownFacilityId } from '../town-facilities'
import { ASSETS, BASELINE, WORLD_HEIGHT, ACADEMY_X, PLOT_PITCH, STREET_Y, SELF_OVERRIDE_MS, SELF_STUCK_MS, resolveNpcFrame, characterSheet, worldWidth } from './shared'
import type { Direction } from './shared'
import type { TownSceneInstance as TownScene } from './scene-core'

export const lifecycleMethods = {
  preload(this: TownScene) {
      const runtime = this.runtime;
      this.load.atlas('town', `${ASSETS}/town-atlas.png`, `${ASSETS}/town-atlas.json`);
      this.load.atlas('interior', `${ASSETS}/interior-atlas.png`, `${ASSETS}/interior-atlas.json`);
      // 劳作动画的几何（每张的帧宽高、朝向分块）写在 sidecar 里，先把它读进来。
      this.load.json('labourAnims', `${ASSETS}/characters/labour-anims.json`);
      this.load.spritesheet('emotes', `${ASSETS}/emotes.png`, { frameWidth: 32, frameHeight: 32 });
      this.load.spritesheet('npc_postman', `${ASSETS}/characters/postman.png`, { frameWidth: 32, frameHeight: 64 });
      this.load.spritesheet('npc_scout', `${ASSETS}/characters/scout.png`, { frameWidth: 32, frameHeight: 64 });
      // 居民用到的表 + 全部 20 张预制表。多加载的十几张很小，换来的是名册后到时可以直接生成
      // NPC，不必再跑一轮运行时加载（那会让人物凭空闪现）。
      const sheets = new Set(runtime.residents.map(item => characterSheet(item.publicId)));
      for (let index = 1; index <= 20; index += 1)
          sheets.add(index);
      for (const index of sheets) {
          this.load.spritesheet(`char_${index}`, `${ASSETS}/characters/c${String(index).padStart(2, '0')}.png`, { frameWidth: 32, frameHeight: 64 });
      }
  },

  create(this: TownScene) {
      const runtime = this.runtime;
      runtime.sceneRef = this;
      if (import.meta.env.DEV)
          (window as unknown as {
              __townScene?: TownScene;
          }).__townScene = this;
      this.createSharedAnimations();
      this.createGlowTexture();
      this.createSparkTexture();
      this.drawGround();
      this.drawBackdrop();
      this.drawAcademy();
      runtime.residents.forEach((resident, index) => { this.residentIndex.set(resident.publicId, index); this.drawPlot(resident, index); });
      this.drawPublicPlaces();
      this.drawGardenDistrict();
      this.drawTownEvents();
      this.drawStreetFurniture(); // 在 buildCollisionWorld 之前绘制，收集碰撞数据
      this.drawLifeTerrace();
      this.facilities?.setMuted(!runtime.soundEnabled || document.hidden);
      this.buildCollisionWorld(); // 现在包含家具碰撞
      this.drawParkStrip();
      this.drawWildlife();
      this.spawnVehicles();
      runtime.residents.forEach((resident, index) => { if (resident.isSelf)
          this.spawnResident(resident, index); });
      this.companion = createTownCompanion(this, {
          player: () => this.selfWalker?.sprite ?? null,
          world: () => this.collisionWorld,
          park: () => runtime.garden.walkable[1]!,
          onModeChange: mode => runtime.handlers.onCompanionModeChange?.(mode),
          onInteract: () => runtime.handlers.onCompanionInteract?.(),
          onApproach: point => { this.setObservationMode(false); this.walkSelfToGround(point.x, point.y, true); },
      });
      this.companion.setState(runtime.companionState);
      this.spawnNpcs();
      // 名册可能比场景先到（store 已经拉过一次），那就在这里补生成，不必等下一次 applyNpcs。
      if (runtime.townNpcRoster.length > 0)
          this.applyTownNpcs(runtime.townNpcRoster);
      this.loadLabourAnimations();
      this.atmosphere = new TownAtmosphere({ worldWidth: runtime.width, worldHeight: WORLD_HEIGHT, groundY: BASELINE, townTime: () => runtime.environmentTime() });
      this.atmosphere.attach(this);
      runtime.publishTime();
      for (let x = ACADEMY_X - 160; x < runtime.width - 96; x += PLOT_PITCH)
          this.atmosphere.registerLight({ id: `lamp-${x}`, x: x + 16, y: BASELINE - 54, kind: 'lamp', radius: 85, color: 0xe6ac65 });
      this.atmosphere.registerLight({ id: 'terrace-cafe-window', x: runtime.terraceOrigin.x - 36, y: BASELINE - 65, kind: 'window', radius: 180, color: 0xd8a565 });
      this.atmosphere.registerLight({ id: 'terrace-lamp', x: runtime.terraceOrigin.x - 163, y: runtime.terraceOrigin.y - 76, kind: 'lamp', radius: 95, color: 0xe6b779 });
      this.atmosphere.registerLight({ id: 'terrace-table-lantern', x: runtime.terraceOrigin.x - 86, y: runtime.terraceOrigin.y + 55, kind: 'window', radius: 120, color: 0xe6b779 });
      this.atmosphere.registerLight({ id: 'terrace-reading-lantern', x: runtime.terraceOrigin.x + 96, y: runtime.terraceOrigin.y + 86, kind: 'window', radius: 95, color: 0xdfb478 });
      // Camera ownership stays with TownScene (follow, manual pan and observation).
      this.atmosphere.setWeather(weatherForDate(runtime.latestModel.localDate));
      this.atmosphere.setReducedMotion(window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false);
      this.atmosphere.setVisible(!document.hidden);
      this.nightOverlay = this.add.graphics().fillStyle(0x101c4a, 1).fillRect(0, 0, runtime.width, WORLD_HEIGHT).setAlpha(0).setDepth(5000);
      if (runtime.desiredNight !== null)
          this.setNight(runtime.desiredNight, true);
      if (runtime.desiredRun)
          this.setRunMode(true);
      this.setupCamera();
      this.setupInput();
      // DOM toolbars and page scrolling can move the canvas without resizing it.
      // Refresh before Phaser consumes the native event, otherwise clicks use stale offsets.
      const syncInputBounds = () => this.scale.updateBounds();
      const nativeEvents = ['pointerdown', 'mousedown', 'touchstart', 'wheel'] as const;
      for (const name of nativeEvents)
          this.game.canvas.addEventListener(name, syncInputBounds, { capture: true, passive: true });
      this.events.once('shutdown', () => {
          this.companion?.destroy(); this.companion = null;
          this.facilities?.destroy();
          this.facilities = null;
          this.lifeStage?.destroy();
          this.lifeStage = null;
          for (const name of nativeEvents)
              this.game.canvas.removeEventListener(name, syncInputBounds, true);
      });
      this.scale.on('resize', this.handleViewportResize, this);
      this.events.once('shutdown', () => this.scale.off('resize', this.handleViewportResize, this));
      this.events.on('wake', () => {
          this.facilities?.setMuted(!runtime.soundEnabled || document.hidden);
          runtime.soundscape.setIndoor(false);
          this.clockFrameAt = -1;
          for (const walker of this.walkers) {
              if (!walker.npc || !walker.dayPlan)
                  continue;
              const frame = this.residentFrame(walker.npc.code, walker.dayPlan);
              walker.sprite.setPosition(frame.x, frame.y);
              walker.frozenUntil = 0;
              walker.catchupPath = [];
              walker.speech?.destroy();
              walker.speech = null;
              walker.travelEmote?.destroy();
              walker.travelEmote = null;
          }
      });
      this.setupPresenceListeners();
      if (runtime.requestedConversation)
          this.beginConversation(runtime.requestedConversation);
      this.events.once('shutdown', () => {
          this.atmosphere?.destroy();
          this.atmosphere = null;
          runtime.presenceReporter.flush();
      });
  },

  setupPresenceListeners(this: TownScene) {
      const runtime = this.runtime;
      // Flush presence on page hide or beforeunload
      const handleVisibilityChange = () => {
          if (document.visibilityState === 'hidden')
              runtime.presenceReporter.flush();
      };
      const handleBeforeUnload = () => { runtime.presenceReporter.flush(); };
      if (typeof document !== 'undefined') {
          document.addEventListener('visibilitychange', handleVisibilityChange);
          window.addEventListener('beforeunload', handleBeforeUnload);
          this.events.once('shutdown', () => {
              document.removeEventListener('visibilitychange', handleVisibilityChange);
              window.removeEventListener('beforeunload', handleBeforeUnload);
          });
      }
  },

  reportSelfPresence(this: TownScene) {
      const runtime = this.runtime;
      if (!this.selfWalker)
          return;
      const { sprite, facing } = this.selfWalker;
      let scene = 'town';
      for (const place of ['academy', 'gym', 'cafe', 'park', 'plaza']) {
          const point = this.entrances.get(place);
          if (point && Math.hypot(point.x - sprite.x, point.y - sprite.y) <= 110) {
              scene = `town:${place}`;
              break;
          }
      }
      runtime.presenceReporter.update({
          x: Math.round(sprite.x),
          y: Math.round(sprite.y),
          facing,
          scene,
      });
  },

  update(this: TownScene, _time: number, delta: number) {
      const runtime = this.runtime;
      this.companion?.update(delta);
      const inPark = this.selfWalker ? inCompanionPark(this.selfWalker.sprite, runtime.garden.walkable[1]!) : false;
      if (runtime.companionPlace !== inPark) { runtime.companionPlace = inPark; runtime.handlers.onCompanionPlaceChange?.(inPark); }
      this.atmosphere?.update(delta);
      const environment = runtime.environmentTime();
      const intensity = paletteForTime(environment.minutes, seasonForMonth(environment.month)).lightIntensity;
      this.night = intensity >= .7;
      runtime.publishTime();
      for (const object of this.glows) {
          const light = object as PhaserNs.GameObjects.Image;
          light.setAlpha(approach(light.alpha, intensity * (light.getData('targetAlpha') ?? 1), delta, 900));
      }
      const now = this.time.now;
      if (this.travel?.phase === 'walking' && this.travel.place.startsWith('facility:') && this.selfWalker)
          this.facilities?.reserve(this.travel.place.slice(9) as TownFacilityId, this.selfWalker.id);
      this.lifeStage?.update(now);
      this.facilities?.update(delta);
      this.updateFacilityVisitors(now, delta);
      if (now >= this.nextRosterCheckAt) {
          this.nextRosterCheckAt = now + 10000;
          this.applyTownNpcs(runtime.townNpcRoster);
          this.drawTownEvents();
      }
      if (now >= this.nextPresenceAt) {
          this.nextPresenceAt = now + 3000;
          this.reportSelfPresence();
      }
      this.updateConversation();
      this.updateNearby();
      this.handleSelfKeys(now, delta);
      this.updateObservation();
      this.detectGreetings(now);
      for (const walker of this.walkers) {
          if (this.facilityNpcs.has(walker) || this.facilities?.isBusy(walker.id) || (walker.resident?.isSelf && this.usingLegacyFurniture)) {
              walker.label.setPosition(walker.sprite.x, walker.sprite.y + 4);
              walker.emote?.setPosition(walker.sprite.x + 14, walker.sprite.y - 62);
              continue;
          }
          if (this.conversation?.walker === walker) {
              walker.label.setPosition(walker.sprite.x, walker.sprite.y + 4);
              walker.emote?.setPosition(walker.sprite.x + 14, walker.sprite.y - 62);
              continue;
          }
          if (walker.npc) {
              // 18 人名册全部走 dayPlan 驱动的独立路径（M7-6/7/8/9）——不再进入下面这套给玩家/
              // 邻居/巡逻 NPC 用的"整点判定 + 直线走"状态机。标签/表情仍然统一跟随，气泡摆位则交
              // 给循环之后的 layoutSpeechBubbles 一次性处理（要看到所有正在说话的人才能互相避让）。
              this.updateNpcWalker(walker, now, delta);
              walker.label.setVisible(walker.npc.layer === 2 || Math.hypot(walker.sprite.x - (this.selfWalker?.sprite.x ?? 0), walker.sprite.y - (this.selfWalker?.sprite.y ?? 0)) < 160);
              walker.label.setPosition(walker.sprite.x, walker.sprite.y + 4);
              walker.emote?.setPosition(walker.sprite.x + 14, walker.sprite.y - 62);
              walker.travelEmote?.setPosition(walker.sprite.x + 14, walker.sprite.y - 66);
              this.maybeInitiate(walker, now);
              continue;
          }
          if (walker.frozenUntil > now) {
              walker.label.setPosition(walker.sprite.x, walker.sprite.y + 4);
              walker.emote?.setPosition(walker.sprite.x + 14, walker.sprite.y - 62);
              walker.travelEmote?.setPosition(walker.sprite.x + 14, walker.sprite.y - 66);
              continue;
          }
          if (walker.frozenUntil !== 0) {
              // Just came out of a greeting: drop the heart bubble and re-decide almost immediately.
              walker.frozenUntil = 0;
              walker.travelEmote?.destroy();
              walker.travelEmote = null;
              walker.timer = 200;
          }
          if (walker.keyDriven) {
              walker.keyDriven = false;
              walker.label.setPosition(walker.sprite.x, walker.sprite.y + 4);
              walker.emote?.setPosition(walker.sprite.x + 14, walker.sprite.y - 62);
              continue;
          }
          if (walker.state === 'walk' && walker.resident?.isSelf) {
              // Self avatar: free 2D pathing toward (targetX, targetY), collision-resolved every step
              // so a straight line into a wall slides along it instead of clipping through.
              const from: Point = { x: walker.sprite.x, y: walker.sprite.y };
              const to: Point = { x: walker.targetX, y: walker.targetY };
              const stepped = stepTowardPoint(from, to, moveSpeed(walker.running), delta);
              const resolved = resolveMove(from, stepped, this.collisionWorld);
              walker.sprite.x = resolved.x;
              walker.sprite.y = resolved.y;
              walker.sprite.setDepth(resolved.y);
              const dx = resolved.x - from.x;
              const dy = resolved.y - from.y;
              if (dx === 0 && dy === 0) {
                  walker.stuckMs += delta;
                  walker.sprite.play(`${walker.sheet}-idle-${walker.facing}`, true);
              }
              else {
                  walker.stuckMs = 0;
                  walker.facing = dominantDirection(dx, dy, walker.facing);
                  walker.sprite.play(`${walker.sheet}-walk-${walker.facing}`, true);
              }
              walker.sprite.anims.timeScale = walker.running ? RUN_ANIM_SCALE : 1;
              const remaining = Math.hypot(walker.targetX - walker.sprite.x, walker.targetY - walker.sprite.y);
              if (remaining < 1 && this.selfPath.length) {
                  walker.sprite.setPosition(walker.targetX, walker.targetY);
                  const next = this.selfPath.shift()!;
                  walker.targetX = next.x;
                  walker.targetY = next.y;
                  continue;
              }
              // Periodic reporting during movement (throttled by presenceReporter)
              if (now - walker.lastReportTime > 3000) {
                  walker.lastReportTime = now;
                  this.reportSelfPresence();
              }
              // A straight line can dead-end against a wall (no A* pathing); give up gracefully
              // after a short stall instead of animating in place forever.
              if (remaining < 1 || walker.stuckMs > SELF_STUCK_MS) {
                  if (remaining < 1)
                      walker.sprite.setPosition(walker.targetX, walker.targetY);
                  else {
                      this.selfPath = [];
                      if (this.travel) {
                          this.travel = { ...this.travel, phase: 'blocked' };
                          runtime.handlers.onTravelChange?.(this.travel);
                      }
                      this.destinationMarker?.destroy();
                      this.destinationMarker = null;
                  }
                  walker.state = 'act';
                  walker.running = false;
                  walker.stuckMs = 0;
                  walker.sprite.anims.timeScale = 1;
                  if (walker.arriveSelect !== null) {
                      const selection = walker.arriveSelect;
                      walker.arriveSelect = null;
                      walker.travelEmote?.destroy();
                      walker.travelEmote = null;
                      if (remaining < 12)
                          this.dispatchArrival(selection);
                      else
                          this.saySomething(walker, '前面有东西挡住了，换个方向走走吧。', 2500);
                  }
                  else if (walker.manualWalk) {
                      walker.overrideUntil = now + SELF_OVERRIDE_MS;
                  }
                  walker.manualWalk = false;
                  this.performActivity(walker);
                  // Report self presence when stopped
                  this.reportSelfPresence();
              }
          }
          else if (walker.state === 'walk') {
              // 玩家以外的人一律用居民步速（CONTRACT-M7.md §4）：慢一档，镜头扫过才看得出"正在
              // 走去哪儿"，而不是和玩家一样快得像一闪而过。
              const distance = walker.targetX - walker.sprite.x;
              walker.sprite.x = stepToward(walker.sprite.x, walker.targetX, RESIDENT_WALK_SPEED, delta);
              if (!walker.resident?.isSelf)
                  walker.sprite.y = stepToward(walker.sprite.y, walker.targetY, RESIDENT_WALK_SPEED, delta);
              const direction: Direction = distance < 0 ? 'left' : 'right';
              walker.sprite.play(`${walker.sheet}-walk-${direction}`, true);
              if (Math.abs(distance) < 1) {
                  walker.state = 'act';
                  walker.running = false;
                  walker.sprite.anims.timeScale = 1;
                  if (walker.arriveSelect !== null) {
                      const selection = walker.arriveSelect;
                      walker.arriveSelect = null;
                      walker.travelEmote?.destroy();
                      walker.travelEmote = null;
                      this.dispatchArrival(selection);
                  }
                  else if (walker.manualWalk) {
                      walker.overrideUntil = now + SELF_OVERRIDE_MS;
                  }
                  walker.manualWalk = false;
                  this.performActivity(walker);
                  // Report self presence when stopped (non-self avatars don't report)
                  if (walker.resident?.isSelf)
                      this.reportSelfPresence();
              }
          }
          else {
              walker.timer -= delta;
              if (walker.timer <= 0) {
                  // walker.npc 已经在循环最上面 continue 掉了，这里只剩玩家/邻居/巡逻 NPC 三种。
                  let next: number;
                  if (walker.patrol)
                      next = this.chooseNextTarget(walker);
                  else if (walker.resident?.isSelf && now < walker.overrideUntil)
                      next = walker.sprite.x;
                  else
                      next = this.evaluateSchedule(walker);
                  if (Math.abs(next - walker.sprite.x) > 4) {
                      walker.targetX = next;
                      walker.state = 'walk';
                  }
                  else
                      this.performActivity(walker);
              }
          }
          walker.label.setPosition(walker.sprite.x, walker.sprite.y + 4);
          walker.emote?.setPosition(walker.sprite.x + 14, walker.sprite.y - 62);
          walker.travelEmote?.setPosition(walker.sprite.x + 14, walker.sprite.y - 66);
      }
      // M7-8: 所有正在说话的人（含上面 continue 掉的 18 人名册）一次摆完，互相夹回镜头、避让重叠。
      this.layoutSpeechBubbles();
      for (const walker of this.walkers) {
          if (runtime.scenicMode) {
              walker.label.setVisible(false);
              walker.speech?.setVisible(false);
              walker.emote?.setVisible(false);
          }
          else {
              walker.speech?.setVisible(true);
              walker.emote?.setVisible(true);
              if (!walker.npc || walker.npc.layer === 2)
                  walker.label.setVisible(true);
          }
      }
      for (const vehicle of this.vehicles) {
          vehicle.sprite.x += (vehicle.speed * delta) / 1000;
          const roadEnd = runtime.terraceBounds.x - 100;
          if (vehicle.speed > 0 && vehicle.sprite.x > roadEnd - vehicle.sprite.width)
              vehicle.sprite.x = -vehicle.sprite.width - 40;
          if (vehicle.speed < 0 && vehicle.sprite.x < -vehicle.sprite.width - 40)
              vehicle.sprite.x = roadEnd - vehicle.sprite.width;
      }
  }
}
export type LifecycleMethods = typeof lifecycleMethods
