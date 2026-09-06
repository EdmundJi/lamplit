import { freshSocialLine } from '../social-pacing'
import { resolveMove } from '../collision'
import type PhaserNs from 'phaser'
import { activityFor, hashString, whereShouldBe } from '../building-kit'
import type { TownVenue } from '../building-kit'
import { RESIDENT_WALK_SPEED, dominantDirection, stepTowardPoint } from '../walkers'
import { findPath } from '../pathfinding'
import { safeTownPoint } from '../town-recovery'
import type { ResidentActivity, TownResident } from '../town.types'
import { npcWhereabouts, minuteInZone } from '../world-life'
import { presenceTarget, shouldTeleport } from '../presence'
import type { TownNpcView, NpcActivity, NpcDayPlan } from '../town-npc.types'
import { cafeStandingPoint } from '../npc-standing'
import { densityCap } from '../npc-placement'
import type { TownLayout } from '../npc-placement'
import { buildItinerary, nextLeg } from '../observation-mode'
import { positionAt, dayPlanFallback } from '../day-plan'
import { shouldPauseForGreeting, shouldSeekRoadsideShelter, PASSING_GREETING_PAUSE_MS } from '../roadside-episodes'
import { ASSETS, BASELINE, YARD_X, ACADEMY_X, PLOT_START, PLOT_WIDTH, STREET_Y, FONT, DIRECTION_INDEX, OBSERVATION_LOOP_MS, OBSERVATION_ZOOM, EMOTES, LABOUR_ACTIVITIES, easeInOutSine, resolveNpcFrame, ROADSIDE_EPISODE_CHECK_MS, ROADSIDE_PASSING_DISTANCE_PX, ROADSIDE_SHELTER_MS, ROADSIDE_SHELTER_OFFSET_PX, characterSheet, worldWidth, plotX } from './shared'
import type { TownSelection, Direction, Walker, LabourAnim, NpcFrame } from './shared'
import type { TownSceneInstance as TownScene } from './scene-core'

export const npcsMethods = {
  residentFrame(this: TownScene, code: string, plan: NpcDayPlan): NpcFrame {
      const minute = this.npcMinuteOfDay()
      const position = positionAt(plan, minute)
      const usesCafe = position.kind === 'AT' ? position.place === 'cafe' : position.fromPlace === 'cafe' || position.toPlace === 'cafe'
      if (!usesCafe) return resolveNpcFrame(plan, minute, code, this.townLayout(), STREET_Y)
      const peers = this.runtime.townNpcRoster.filter(npc => {
          if (npc.layer === 1) return false
          const p = positionAt(npc.dayPlan ?? dayPlanFallback(npc.schedule), minute)
          return p.kind === 'AT' ? p.place === 'cafe' : p.fromPlace === 'cafe' || p.toPlace === 'cafe'
      }).map(npc => npc.code)
      const standing = cafeStandingPoint(code, peers, this.runtime.terraceOrigin.x, STREET_Y)
      const point = safeTownPoint(this.collisionWorld, standing, standing) ?? standing
      return resolveNpcFrame(plan, minute, code, this.townLayout(), STREET_Y, { cafe: point })
  },
  createSharedAnimations(this: TownScene) {
      const runtime = this.runtime;
      const frames = (prefix: string, count: number) => Array.from({ length: count }, (_, i) => ({ key: 'town', frame: `${prefix}_${i + 1}` }));
      this.anims.create({ key: 'crow-idle', frames: frames('crow', 6), frameRate: 4, repeat: -1 });
      this.anims.create({ key: 'pigeon-idle', frames: frames('pigeon', 6), frameRate: 5, repeat: -1 });
      for (const [name, pair] of Object.entries(EMOTES)) {
          this.anims.create({ key: `emote-${name}`, frames: pair.map(frame => ({ key: 'emotes', frame })), frameRate: 2, repeat: -1 });
      }
  },

  ensureCharacterAnimations(this: TownScene, sheet: string) {
      const runtime = this.runtime;
      if (this.anims.exists(`${sheet}-idle-down`))
          return;
      const columns = Math.floor((this.textures.get(sheet).getSourceImage() as HTMLImageElement).width / 32);
      const range = (row: number, start: number, length = 6) => Array.from({ length }, (_, i) => ({ key: sheet, frame: row * columns + start + i }));
      for (const direction of Object.keys(DIRECTION_INDEX) as Direction[]) {
          const offset = DIRECTION_INDEX[direction] * 6;
          this.anims.create({ key: `${sheet}-idle-${direction}`, frames: range(1, offset), frameRate: 6, repeat: -1 });
          this.anims.create({ key: `${sheet}-walk-${direction}`, frames: range(2, offset), frameRate: 9, repeat: -1 });
      }
      this.anims.create({ key: `${sheet}-phone`, frames: range(6, 3), frameRate: 6, repeat: -1 });
      this.anims.create({ key: `${sheet}-read`, frames: range(7, 0), frameRate: 5, repeat: -1 });
  },

  spawnWalker(this: TownScene, sheet: string, x: number, name: string, background: string, resident: TownResident | null, activity: ResidentActivity, emote: string | null, selection: TownSelection): Walker {
      const runtime = this.runtime;
      this.ensureCharacterAnimations(sheet);
      const columns = Math.floor((this.textures.get(sheet).getSourceImage() as HTMLImageElement).width / 32);
      const sprite = this.add.sprite(x, STREET_Y, sheet, columns + 18).setOrigin(0.5, 1).setDepth(STREET_Y);
      sprite.setInteractive({ useHandCursor: true });
      sprite.on('pointerup', (pointer: PhaserNs.Input.Pointer) => {
          if (!runtime.isWorldPointer(pointer) || !this.dragStart)
              return;
          if (this.dragged)
              return;
          // Clicking the guide/postman walks the self avatar to them first (task 6); a neighbour
          // or one's own sprite is selected immediately, same as clicking their building.
          if (selection?.startsWith('npc:'))
              this.walkSelfToAndSelect(sprite.x, sprite.y, selection);
          else if (walker.npc?.layer === 3)
              this.saySomething(walker, `我${npcWhereabouts(walker.npc, this.npcMinuteOfDay())}，待会儿见。`);
          else
              runtime.handlers.onSelect?.(selection);
      });
      const label = this.add.text(x, STREET_Y + 4, name, {
          fontFamily: FONT, fontSize: '11px', color: '#fff', backgroundColor: background, padding: { x: 5, y: 2 },
      }).setOrigin(0.5, 0).setDepth(4001).setResolution(2);
      const bubble = emote ? this.add.sprite(x, STREET_Y - 66, 'emotes', EMOTES[emote as keyof typeof EMOTES][0]).setOrigin(0.5, 1).setDepth(4002).play(`emote-${emote}`) : null;
      const walker: Walker = {
          sprite, label, emote: bubble, sheet, homeX: x, targetX: x, targetY: STREET_Y, state: 'act',
          timer: 600 + (hashString(name) % 2500), activity, resident, patrol: null, venue: 'home', action: 'idle',
          id: resident ? resident.publicId : String(selection),
          overrideUntil: 0, manualWalk: false, arriveSelect: null, travelEmote: null, frozenUntil: 0, keyDriven: false, running: false,
          facing: 'down', stuckMs: 0, lastPresence: null, teleporting: false, lastReportTime: 0,
          npc: null, speech: null, npcActivity: null, dayPlan: null, episodeCheckAt: 0, shelterUntil: 0,
      };
      sprite.play(`${sheet}-idle-down`);
      this.walkers.push(walker);
      return walker;
  },

  spawnResident(this: TownScene, resident: TownResident, index: number) {
      const runtime = this.runtime;
      const activity = activityFor(resident);
      const homeX = plotX(index) + PLOT_WIDTH / 2 + 40;
      // Use presence spawn point if available and matches current scene
      let spawnX = homeX;
      let spawnY = STREET_Y;
      if (resident.presence && (resident.presence.scene === 'town' || resident.presence.scene.startsWith('town:'))) {
          spawnX = resident.presence.x;
          spawnY = resident.presence.y;
      }
      const safe = safeTownPoint(this.collisionWorld, { x: spawnX, y: spawnY }, { x: homeX, y: STREET_Y });
      spawnX = safe?.x ?? homeX;
      spawnY = safe?.y ?? STREET_Y;
      const walker = this.spawnWalker(`char_${characterSheet(resident.publicId)}`, spawnX, resident.isSelf ? '我' : resident.displayName, resident.isSelf ? 'rgba(200,95,71,.92)' : 'rgba(40,30,26,.72)', resident, activity, activity, resident.publicId);
      // Initialize lastPresence for tracking
      if (resident.presence && (resident.presence.scene === 'town' || resident.presence.scene.startsWith('town:'))) {
          walker.lastPresence = { x: resident.presence.x, y: resident.presence.y };
      }
      // Adjust spawn y for self avatar (spawnWalker always uses STREET_Y for initial position)
      if (resident.isSelf) {
          walker.sprite.y = spawnY;
          walker.targetY = spawnY;
          walker.sprite.setDepth(spawnY);
      }
      // Start where the schedule already says they should be, instead of always waking up at home.
      const target = this.evaluateSchedule(walker);
      if (Math.abs(target - walker.sprite.x) > 4) {
          walker.targetX = target;
          walker.state = 'walk';
      }
  },

  spawnNpcs(this: TownScene) {
      const runtime = this.runtime;
      const guide = this.spawnWalker('npc_scout', runtime.academyDoorX - 70, '小助 · AI 助手', 'rgba(53,100,79,.92)', null, 'planned', 'question', 'npc:assistant');
      guide.patrol = [runtime.academyDoorX - 120, runtime.academyDoorX - 20];
      const postman = this.spawnWalker('npc_postman', PLOT_START - 120, runtime.model.unread > 0 ? `邮递员 · ${runtime.model.unread} 封新信` : '邮递员', 'rgba(60,90,140,.9)', null, 'working', runtime.model.unread > 0 ? 'mail' : null, 'npc:postman');
      postman.patrol = [ACADEMY_X - 100, runtime.width - 160];
      postman.targetX = runtime.width - 160;
      postman.state = 'walk';
      this.applyLetterUnread();
  },

  applyLetterUnread(this: TownScene) {
      const runtime = this.runtime;
      const postman = this.walkers.find(w => w.id === 'npc:postman');
      if (!postman)
          return;
      const label = runtime.letterUnread > 0 ? `邮递员 · ${runtime.letterUnread} 封未读信` : runtime.latestModel.unread > 0 ? `邮递员 · ${runtime.latestModel.unread} 条好友消息` : '邮递员';
      postman.label.setText(label);
      if (runtime.letterUnread > 0 && !postman.emote)
          postman.emote = this.add.sprite(postman.sprite.x, postman.sprite.y - 66, 'emotes', EMOTES.mail[0]).setOrigin(0.5, 1).setDepth(4002).play('emote-mail');
      if (runtime.letterUnread === 0 && runtime.latestModel.unread === 0) {
          postman.emote?.destroy();
          postman.emote = null;
      }
  },

  townLayout(this: TownScene): TownLayout {
      const runtime = this.runtime;
      return {
          academyDoorX: runtime.academyDoorX,
          plotStartX: PLOT_START,
          gymX: runtime.gymPlotX + PLOT_WIDTH / 2,
          cafeX: runtime.cafePlotX + PLOT_WIDTH / 2,
          parkX: runtime.garden.park.x,
          parkY: runtime.garden.park.y,
          branchX: runtime.garden.branchX,
          plazaMinX: YARD_X + 120,
          plazaMaxX: YARD_X + 320,
          worldWidth: runtime.width,
      };
  },

  applyTownNpcs(this: TownScene, list: TownNpcView[]) {
      const runtime = this.runtime;
      const minute = this.npcMinuteOfDay();
      if (this.time.now > this.terraceInvitationUntil)
          this.terraceInvited.clear();
      const candidates = list.filter(npc => {
          if (npc.layer === 1)
              return false; // guide and postman already have dedicated actors
          const position = positionAt(npc.dayPlan ?? dayPlanFallback(npc.schedule), minute);
          return this.terraceInvited.has(npc.code) || position.kind === 'WALKING' || position.place !== 'home';
      }).sort((a, b) => Number(this.terraceInvited.has(b.code)) - Number(this.terraceInvited.has(a.code)) || a.layer - b.layer || a.code.localeCompare(b.code));
      const visible = candidates.slice(0, Math.max(0, densityCap(minute / 60) - 2));
      const codes = new Set(visible.map(npc => npc.code));
      for (const walker of [...this.walkers]) {
          if (walker.npc && !codes.has(walker.npc.code) && this.conversation?.walker !== walker && !this.facilityNpcs.has(walker))
              this.despawnWalker(walker);
      }
      for (const npc of visible) {
          const existing = this.walkers.find(walker => walker.npc?.code === npc.code);
          if (existing) {
              existing.npc = npc;
              existing.dayPlan = npc.dayPlan ?? dayPlanFallback(npc.schedule);
          }
          else
              this.spawnTownNpc(npc);
      }
  },

  spawnTownNpc(this: TownScene, npc: TownNpcView) {
      const runtime = this.runtime;
      const sheet = npc.sprite.startsWith('c') && /^c\d+$/.test(npc.sprite)
          ? `char_${Number(npc.sprite.slice(1))}`
          : npc.sprite;
      if (!this.textures.exists(sheet))
          return;
      const dayPlan = npc.dayPlan ?? dayPlanFallback(npc.schedule);
      const frame = this.residentFrame(npc.code, dayPlan);
      const walker = this.spawnWalker(sheet, frame.x, npc.displayName, npc.layer === 2 ? 'rgba(84,64,120,.86)' : 'rgba(40,30,26,.62)', null, 'planned', null, 
      // 三层背景居民不可对话（plan §2.4）；点击他们不该弹出任何东西。
      npc.layer === 3 ? null : `npc:${npc.code}`);
      walker.npc = npc;
      walker.id = npc.code;
      walker.homeX = frame.x;
      walker.dayPlan = dayPlan;
      walker.sprite.y = frame.y;
      walker.targetY = frame.y;
      walker.sprite.setDepth(frame.depth);
      walker.npcActivity = null; // first update must start the scheduled animation
  },

  despawnWalker(this: TownScene, walker: Walker) {
      const runtime = this.runtime;
      this.facilities?.cancel(walker.id);
      this.facilityNpcs.delete(walker);
      walker.speech?.destroy();
      walker.travelEmote?.destroy();
      walker.emote?.destroy();
      walker.label.destroy();
      walker.sprite.destroy();
      this.walkers = this.walkers.filter(item => item !== walker);
  },

  npcMinuteOfDay(this: TownScene) {
      const runtime = this.runtime;
      if (this.clockFrameAt !== this.time.now) {
          this.clockFrameAt = this.time.now;
          this.clockMinute = minuteInZone(Date.now() + runtime.serverOffsetMs, runtime.latestModel.residents.find(r => r.isSelf)?.timezone ?? 'Asia/Shanghai');
      }
      return this.clockMinute;
  },

  updateNpcWalker(this: TownScene, walker: Walker, now: number, delta: number) {
      const runtime = this.runtime;
      const npc = walker.npc;
      if (!npc)
          return;
      // 冻结中（既有的擦肩打招呼、或 M7-9 的路上插曲）：原地不动，等冻结过去再继续渲染。这几
      // 秒会让画面"跳过" positionAt 本该给出的中间位置，是刻意的取舍（见 roadside-episodes.ts
      // 头部的硬约束）——冻结只改这一帧画在哪儿，绝不回头去改 positionAt 的输入或输出。
      if (walker.frozenUntil > now)
          return;
      const resuming = walker.frozenUntil !== 0;
      if (walker.frozenUntil !== 0) {
          walker.frozenUntil = 0;
          walker.npcActivity = null;
          walker.travelEmote?.destroy();
          walker.travelEmote = null;
      }
      if (!walker.dayPlan)
          walker.dayPlan = npc.dayPlan ?? dayPlanFallback(npc.schedule);
      const frame = this.residentFrame(npc.code, walker.dayPlan);
      const shelterActive = frame.walking && walker.shelterUntil > now;
      const y = shelterActive ? frame.y - ROADSIDE_SHELTER_OFFSET_PX : frame.y;
      const previous = { x: walker.sprite.x, y: walker.sprite.y };
      if (resuming) {
          const destination = safeTownPoint(this.collisionWorld, { x: frame.x, y }, previous) ?? previous;
          walker.catchupPath = findPath(previous, destination, this.collisionWorld) ?? [];
      }
      let target = walker.catchupPath?.[0] ?? { x: frame.x, y };
      if (Math.hypot(target.x - previous.x, target.y - previous.y) < 1 && walker.catchupPath?.length) {
          walker.sprite.setPosition(target.x, target.y);
          previous.x = target.x;
          previous.y = target.y;
          walker.catchupPath.shift();
          target = walker.catchupPath[0] ?? { x: frame.x, y };
      }
      let next = resolveMove(previous, stepTowardPoint(previous, target, RESIDENT_WALK_SPEED, delta), this.collisionWorld);
      // A regular itinerary can also meet newly placed furniture. Re-route instead of standing
      // against it forever; failed searches are throttled rather than repeated every render frame.
      if (Math.hypot(target.x - previous.x, target.y - previous.y) > 2 && Math.hypot(next.x - previous.x, next.y - previous.y) < .1) {
          walker.stuckMs += delta;
          if (walker.stuckMs >= 300) {
              const destination = safeTownPoint(this.collisionWorld, { x: frame.x, y }, previous) ?? previous;
              walker.catchupPath = findPath(previous, destination, this.collisionWorld) ?? [];
              walker.stuckMs = 0;
              if (walker.catchupPath[0]) next = resolveMove(previous, stepTowardPoint(previous, walker.catchupPath[0], RESIDENT_WALK_SPEED, delta), this.collisionWorld);
          }
      } else walker.stuckMs = 0;
      walker.sprite.setPosition(next.x, next.y);
      walker.sprite.setDepth(next.y);
      const walking = frame.walking || Math.hypot(frame.x - next.x, y - next.y) > 2;
      const facing = dominantDirection(next.x - previous.x, next.y - previous.y, walker.facing);
      walker.targetX = frame.targetX;
      walker.targetY = y;
      walker.state = walking ? 'walk' : 'act';
      if (walking) {
          // 在路上：朝向跟着走，动作永远是走路——劳作/读书/打电话那些动作只在"到达后"才播
          // （下面的 AT 分支），这正是 M7-7 要的"到达后再播活动动画"。
          if (walker.npcActivity !== 'walking' || walker.facing !== facing) {
              walker.npcActivity = 'walking';
              walker.facing = facing;
              this.restoreSheet(walker);
              walker.sprite.play(`${walker.sheet}-walk-${walker.facing}`, true);
          }
          this.maybeRoadsideEpisode(walker, now);
          return;
      }
      // AT：真的到了，才切一次到这个 errand 的动作动画——只在 activity 变化时才 play，不然
      // 每帧都重播会把已经在播的动画打断成卡顿。
      if (walker.npcActivity !== frame.activity) {
          walker.npcActivity = frame.activity;
          walker.action = frame.activity === 'reading' ? 'read' : frame.activity === 'phone' ? 'phone' : 'idle';
          this.performActivity(walker);
      }
  },

  maybeRoadsideEpisode(this: TownScene, walker: Walker, now: number) {
      const runtime = this.runtime;
      if (now < walker.episodeCheckAt)
          return;
      walker.episodeCheckAt = now + ROADSIDE_EPISODE_CHECK_MS; // 节流：不必每帧都掷一次骰子
      // 擦肩打招呼：身边真有人（没在冻结中）才判定，免得空无一人的路上也频繁掷骰子。
      const passerby = this.walkers.find(other => other !== walker && other.frozenUntil <= now && !this.facilityNpcs.has(other) && !this.facilities?.isBusy(other.id) && this.conversation?.walker !== other && Math.hypot(other.sprite.x - walker.sprite.x, other.sprite.y - walker.sprite.y) >= 36 && Math.hypot(other.sprite.x - walker.sprite.x, other.sprite.y - walker.sprite.y) < 64);
      if (passerby && shouldPauseForGreeting()) {
          this.triggerGreeting(walker, passerby, now);
          return;
      }
      // 雨天躲屋檐：只是视觉上往店面那侧靠一靠，x 和 activity 都不变。
      const weather = this.atmosphere?.getWeather() ?? 'clear';
      if (shouldSeekRoadsideShelter(weather))
          walker.shelterUntil = now + ROADSIDE_SHELTER_MS;
  },

  loadLabourAnimations(this: TownScene) {
      const runtime = this.runtime;
      const manifest = this.cache.json.get('labourAnims') as Record<string, LabourAnim> | undefined;
      if (!manifest)
          return;
      this.labourGeometry = manifest;
      for (const [name, geometry] of Object.entries(manifest)) {
          this.load.spritesheet(`labour_${name}`, `${ASSETS}/characters/${geometry.file}`, { frameWidth: geometry.frameWidth, frameHeight: geometry.frameHeight });
      }
      this.load.once('complete', () => {
          for (const [name, geometry] of Object.entries(manifest)) {
              const key = `labour-${name}`;
              if (this.anims.exists(key) || !this.textures.exists(`labour_${name}`))
                  continue;
              // 每张图里四个朝向首尾相接，只播朝向镜头的那一段（钓鱼那张的正面不是第一段）。
              const frames: number[] = [];
              for (let i = geometry.downStart; i < geometry.downEnd; i += 1)
                  frames.push(i);
              this.anims.create({
                  key,
                  frames: frames.map(frame => ({ key: `labour_${name}`, frame })),
                  frameRate: 10,
                  repeat: -1,
              });
          }
      });
      this.load.start();
  },

  applyLabour(this: TownScene, walker: Walker, activity: NpcActivity) {
      const runtime = this.runtime;
      const key = `labour-${activity}`;
      if (!this.anims.exists(key))
          return false;
      walker.sprite.setTexture(`labour_${activity}`);
      walker.sprite.play(key, true);
      return true;
  },

  restoreSheet(this: TownScene, walker: Walker) {
      const runtime = this.runtime;
      if (walker.sprite.texture.key === walker.sheet)
          return;
      walker.sprite.setTexture(walker.sheet);
      walker.sprite.play(`${walker.sheet}-idle-down`, true);
  },

  observationPoints(this: TownScene) {
      const runtime = this.runtime;
      const layout = this.townLayout();
      const y = BASELINE + 10;
      return [
          { x: runtime.garden.park.x, y: runtime.garden.park.y, zoom: OBSERVATION_ZOOM },
          { x: layout.academyDoorX, y, zoom: OBSERVATION_ZOOM },
          { x: layout.cafeX, y, zoom: OBSERVATION_ZOOM },
      ];
  },

  setObservationMode(this: TownScene, on: boolean) {
      const runtime = this.runtime;
      if (on === runtime.observationOn)
          return;
      runtime.observationOn = on;
      runtime.handlers.onObservationChange?.(on);
      const camera = this.cameras.main;
      if (on) {
          this.releaseCameraFollow();
          // 整圈按 30 秒排：plan 的验收标准就是"一段 30 秒长镜头"，而兴趣点有五个——用默认的
          // 每处 6s 停 + 4s 移，一圈要 50 秒，录 30 秒只能扫到五处里的三处。把总时长固定成 30 秒
          // 再按点数均分（六成停、四成移），无论以后兴趣点增减，一圈都还是一段 30 秒长镜头。
          const points = this.observationPoints();
          const perLeg = OBSERVATION_LOOP_MS / Math.max(1, points.length);
          this.observationItinerary = buildItinerary(points, {
              holdMs: Math.round(perLeg * 0.6),
              panMs: Math.round(perLeg * 0.4),
          });
          this.observationStartedAt = this.time.now;
      }
      else {
          this.observationItinerary = [];
          camera.pan(this.selfWalker?.sprite.x ?? runtime.academyDoorX, BASELINE - 120, 700, 'Sine.easeInOut');
          camera.zoomTo(Math.min(1.2, Math.max(0.75, this.scale.height / 820)), 500);
          this.startFollowingSelf();
      }
  },

  speakAmbientLine(this: TownScene, now: number) {
      const runtime = this.runtime;
      if (this.conversation || now < this.nextAmbientLineAt)
          return;
      this.nextAmbientLineAt = now + 30_000;
      const camera = this.cameras.main;
      const centerX = camera.getWorldPoint(camera.width / 2, camera.height / 2).x;
      // 只挑靠近画面中央的人：站在边上的人说话，气泡会被镜头切掉一半。
      const halfView = camera.width / camera.zoom / 2;
      const centerY = camera.getWorldPoint(camera.width / 2, camera.height / 2).y;
      const candidates = this.walkers.filter(walker => this.conversation?.walker !== walker && walker.npc?.talkingPoints?.length && !walker.speech
          && Math.abs(walker.sprite.y - centerY) < camera.height / camera.zoom / 2 - 35
          && Math.abs(walker.sprite.x - centerX) < halfView * 0.6);
      if (candidates.length === 0)
          return;
      const walker = candidates[Math.floor(this.time.now / 997) % candidates.length];
      const points = walker.npc?.talkingPoints ?? [];
      const text = freshSocialLine(runtime, walker.id, points.map(point => point.text));
      if (text) this.saySomething(walker, text);
  },

  updateObservation(this: TownScene) {
      const runtime = this.runtime;
      if (!runtime.observationOn || this.observationItinerary.length === 0)
          return;
      const position = nextLeg(this.observationItinerary, this.time.now - this.observationStartedAt);
      if (!position)
          return;
      // 停下来看的时候才让人说话；镜头还在移动时弹气泡看不清。
      if (position.phase === 'holding')
          this.speakAmbientLine(this.time.now);
      const camera = this.cameras.main;
      const legs = this.observationItinerary;
      const previous = legs[(position.legIndex - 1 + legs.length) % legs.length];
      const target = position.leg;
      if (position.phase === 'panning') {
          const t = easeInOutSine(position.progress);
          camera.centerOn(previous.x + (target.x - previous.x) * t, previous.y + (target.y - previous.y) * t);
          camera.setZoom(previous.zoom + (target.zoom - previous.zoom) * t);
      }
      else {
          camera.centerOn(target.x, target.y);
          camera.setZoom(target.zoom);
      }
  },

  chooseNextTarget(this: TownScene, walker: Walker) {
      const runtime = this.runtime;
      const [from, to] = walker.patrol as [
          number,
          number
      ];
      const roll = hashString(`${walker.sheet}:${walker.homeX}:${Math.floor(this.time.now)}`) % 100;
      return walker.resident === null && walker.sheet === 'npc_postman'
          ? (Math.abs(walker.sprite.x - to) < 8 ? from : to)
          : from + (roll / 100) * (to - from);
  },

  venueX(this: TownScene, venue: TownVenue, walker: Walker) {
      const runtime = this.runtime;
      const spread = (hashString(`${walker.sheet}:${walker.homeX}`) % 5) * 16 - 32;
      if (venue === 'academy')
          return runtime.academyDoorX + spread;
      if (venue === 'court')
          return runtime.courtX + spread;
      if (venue === 'park')
          return runtime.parkX + spread;
      return walker.homeX;
  },

  evaluateSchedule(this: TownScene, walker: Walker) {
      const runtime = this.runtime;
      if (walker.resident?.isSelf)
          return walker.sprite.x;
      if (!walker.resident)
          return walker.homeX;
      const result = whereShouldBe(walker.resident, Date.now(), runtime.serverOffsetMs);
      walker.venue = result.venue;
      walker.action = result.action;
      return this.venueX(result.venue, walker);
  },

  performActivity(this: TownScene, walker: Walker) {
      const runtime = this.runtime;
      if (this.facilities?.isBusy(walker.id) || this.facilityNpcs.has(walker))
          return;
      // 三层背景居民在公园干活时换成劳作动画；一二层不干活，免得"你熟悉的面孔"整天在浇水。
      if (walker.npc && walker.npc.layer === 3 && walker.npcActivity && LABOUR_ACTIVITIES.has(walker.npcActivity)
          && this.applyLabour(walker, walker.npcActivity)) {
          walker.timer = 3200 + (hashString(walker.id) % 2600);
          return;
      }
      if (walker.npc)
          this.restoreSheet(walker);
      const key = walker.action === 'read' ? `${walker.sheet}-read`
          : walker.action === 'phone' ? `${walker.sheet}-phone`
              : `${walker.sheet}-idle-down`;
      walker.sprite.play(key, true);
      const seed = hashString(`${walker.sheet}:${walker.homeX}:${Math.floor(this.time.now)}`);
      // Residents re-check their schedule roughly every 5s; NPC patrol timing is unchanged.
      walker.timer = walker.resident ? 4600 + (seed % 900) : 2600 + (seed % 4200);
  },

  applyResidents(this: TownScene, newResidents: TownResident[]) {
      const runtime = this.runtime;
      for (const resident of newResidents) {
          const walker = this.walkers.find(item => item.resident?.publicId === resident.publicId);
          if (!walker)
              continue;
          walker.resident = resident;
          // Handle presence updates for non-self residents (and stale ones)
          const remote = presenceTarget(resident.presence);
          if (remote && !resident.isSelf) {
              const current = { x: walker.sprite.x, y: walker.sprite.y };
              const hasMovedSignificantly = walker.lastPresence &&
                  Math.hypot(remote.x - walker.lastPresence.x, remote.y - walker.lastPresence.y) > 4;
              if (hasMovedSignificantly || walker.lastPresence === null) {
                  // Position changed: decide walk vs teleport
                  if (shouldTeleport(current, remote)) {
                      // Teleport with fade transition (distance > 800px)
                      walker.teleporting = true;
                      this.tweens.add({
                          targets: walker.sprite,
                          alpha: 0,
                          duration: 150,
                          onComplete: () => {
                              walker.sprite.setPosition(remote.x, remote.y);
                              walker.sprite.setDepth(remote.y);
                              walker.targetX = remote.x;
                              walker.targetY = remote.y;
                              walker.lastPresence = { x: remote.x, y: remote.y };
                              this.tweens.add({
                                  targets: walker.sprite,
                                  alpha: 1,
                                  duration: 150,
                                  onComplete: () => { walker.teleporting = false; }
                              });
                          }
                      });
                  }
                  else {
                      // Walk smoothly to new position
                      walker.targetX = remote.x;
                      walker.targetY = remote.y;
                      walker.state = 'walk';
                      walker.lastPresence = { x: remote.x, y: remote.y };
                  }
              }
          }
          // Own presence is a reconnect seed only. A polling response can be old, or
          // contain room-local coordinates; never apply it to the live street avatar.
          const activity = activityFor(resident);
          if (walker.activity !== activity) {
              walker.activity = activity;
              walker.emote?.play(`emote-${activity}`, true);
          }
          walker.timer = Math.min(walker.timer, 300); // re-check the schedule almost immediately
          const index = this.residentIndex.get(resident.publicId);
          if (index !== undefined)
              this.refreshPlot(resident, index);
      }
  }
}
export type NpcsMethods = typeof npcsMethods
