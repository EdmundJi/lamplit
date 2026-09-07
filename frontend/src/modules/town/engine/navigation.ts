import { neighbourHouse } from '../neighbourhood-layout'
import type PhaserNs from 'phaser'
import { RUN_ANIM_SCALE, dominantDirection, movementDelta, moveSpeed } from '../walkers'
import { nearestStandable, resolveMove } from '../collision'
import { findPath } from '../pathfinding'
import { socialApproach } from '../social-motion'
import type { Point } from '../collision'
import { BASELINE, WORLD_HEIGHT, STREET_Y, SELF_OVERRIDE_MS, EMOTES } from './shared'
import type { TownSelection, TownNearby } from './shared'
import type { TownSceneInstance as TownScene } from './scene-core'

export const navigationMethods = {
  routeSelf(this: TownScene, target: Point): boolean {
      const runtime = this.runtime;
      const self = this.selfWalker!;
      const route = findPath({ x: self.sprite.x, y: self.sprite.y }, target, this.collisionWorld);
      if (!route?.length) {
          self.arriveSelect = null;
          self.state = 'act';
          self.manualWalk = false;
          self.travelEmote?.destroy();
          self.travelEmote = null;
          this.selfPath = [];
          if (this.travel) {
              this.travel = { ...this.travel, phase: 'blocked' };
              runtime.handlers.onTravelChange?.(this.travel);
          }
          this.destinationMarker?.destroy();
          this.destinationMarker = null;
          this.saySomething(self, '这里暂时走不到，试试旁边的路吧。', 2500);
          return false;
      }
      const next = route.shift()!;
      this.selfPath = route;
      self.targetX = next.x;
      self.targetY = next.y;
      return true;
  },

  travelLabel(this: TownScene, place: string): string {
      const runtime = this.runtime;
      if (place.startsWith('neighbour:')) {
          const code = place.slice(10), house = neighbourHouse(code);
          if (['GUIDE', 'POSTMAN', 'TRAVELER'].includes(code)) return house?.label ?? '邻里住宅区';
          return `${runtime.townNpcRoster.find(n => n.code === code)?.displayName ?? house?.name ?? '邻居'}的家`;
      }
      if (place.startsWith('facility:'))
          return this.facilities?.interactables().find(i => i.id === place.slice(9))?.label ?? '露台设施';
      return ({ neighbourhood: '邻里住宅区', home: '我的家', academy: '成长学院', gym: '活力健身房', cafe: '街角咖啡馆', terrace: '街角露台', park: '树荫公园', plaza: '日光广场', street: '街角' } as Record<string, string>)[place]
          ?? this.walkers.find(w => w.id === place || `npc:${w.id}` === place)?.npc?.displayName ?? '这里';
  },

  cancelTravel(this: TownScene): void {
      const runtime = this.runtime;
      this.endFurnitureInteraction();
      const self = this.selfWalker;
      if (self) {
          self.arriveSelect = null;
          self.manualWalk = false;
          self.state = 'act';
          self.overrideUntil = this.time.now + SELF_OVERRIDE_MS;
          self.travelEmote?.destroy();
          self.travelEmote = null;
      }
      this.selfPath = [];
      this.travel = null;
      this.destinationMarker?.destroy();
      this.destinationMarker = null;
      runtime.handlers.onTravelChange?.(null);
  },

  travelToPlace(this: TownScene, place: string): void {
      const runtime = this.runtime;
      this.endConversation();
      this.endFurnitureInteraction();
      if (runtime.activeRoomKey || runtime.academyEntered) {
          runtime.queuedDestination = place;
          if (runtime.activeRoomKey)
              runtime.exitRoom();
          else
              runtime.exitAcademy();
          return;
      }
      if (runtime.observationOn)
          this.setObservationMode(false);
      if (place.startsWith('facility:')) {
          const item = this.facilities?.interactables().find(i => i.id === place.slice(9));
          const self = this.selfWalker;
          if (!item || !self)
              return;
          if (item.id === 'records' && runtime.soundEnabled)
              this.facilities?.unlockAudio();
          if (!this.facilities?.reserve(item.id, self.id)) {
              this.saySomething(self, '有人正在用，等一小会儿吧。');
              return;
          }
          this.travel = { place, label: item.label, phase: 'walking' };
          runtime.handlers.onTravelChange?.(this.travel);
          this.walkSelfToAndSelect(item.actionPoint.x, item.actionPoint.y, place);
          return;
      }
      const actor = this.walkers.find(w => w.id === place || `npc:${w.id}` === place);
      const target = this.entrances.get(place) ?? (actor ? { x: actor.sprite.x, y: actor.sprite.y + 12 } : null);
      if (!target)
          return;
      this.travel = { place, label: this.travelLabel(place), phase: 'walking' };
      runtime.handlers.onTravelChange?.(this.travel);
      this.destinationMarker?.destroy();
      this.destinationMarker = this.add.graphics().lineStyle(2, 0xffdc87, 1).strokeEllipse(target.x, target.y, 30, 12).setDepth(target.y + 1);
      this.walkSelfToAndSelect(target.x, target.y, place);
  },

  updateNearby(this: TownScene): void {
      const runtime = this.runtime;
      const self = this.selfWalker;
      if (!self || this.time.now < this.nextNearbyAt)
          return;
      this.nextNearbyAt = this.time.now + 150;
      runtime.handlers.onPlayerMove?.(self.sprite.x, self.sprite.y);
      const guide = this.walkers.find(walker => walker.id === 'npc:assistant');
      if (guide)
          runtime.handlers.onDistanceToGuide?.(Math.hypot(guide.sprite.x - self.sprite.x, guide.sprite.y - self.sprite.y));
      const candidates: (TownNearby & {
          distance: number;
      })[] = [];
      for (const item of this.facilities?.interactables() ?? []) {
          const distance = Math.hypot(self.sprite.x - item.actionPoint.x, self.sprite.y - item.actionPoint.y);
          if (distance < 60)
              candidates.push({ id: `facility:${item.id}`, label: item.label, action: '', distance });
      }
      for (const [id, point] of this.entrances) {
          const distance = Math.hypot(self.sprite.x - point.x, self.sprite.y - point.y);
          if (distance < 88)
              candidates.push({ id, label: this.travelLabel(id), action: id.startsWith('neighbour:') ? '走到' : ['home', 'academy', 'gym', 'cafe'].includes(id) ? '进入' : '看看', distance });
      }
      for (const walker of this.walkers) {
          if (walker === self || walker.npc?.layer === 3)
              continue;
          const distance = Math.hypot(self.sprite.x - walker.sprite.x, self.sprite.y - walker.sprite.y);
          if (distance < 64)
              candidates.push({ id: walker.npc ? `npc:${walker.npc.code}` : walker.id, label: walker.npc?.displayName ?? (walker.id === 'npc:postman' ? '邮递员' : '小助'), action: '打招呼', distance });
      }
      candidates.sort((a, b) => a.distance - b.distance);
      const next = candidates[0] ?? null;
      if (next?.id !== this.nearby?.id || next?.label !== this.nearby?.label) {
          this.nearby = next;
          runtime.handlers.onNearbyChange?.(next);
      }
  },

  interactNearby(this: TownScene): void {
      const runtime = this.runtime;
      if (this.nearby)
          this.travelToPlace(this.nearby.id);
  },

  walkSelfToGround(this: TownScene, worldX: number, worldY: number, run?: boolean): void {
      run ??= this.runMode;
      const runtime = this.runtime;
      this.endConversation();
      this.endFurnitureInteraction();
      const self = this.selfWalker;
      if (!self)
          return;
      const target = nearestStandable({ x: worldX, y: worldY }, this.collisionWorld);
      self.arriveSelect = null;
      self.travelEmote?.destroy();
      self.travelEmote = null;
      this.cancelGreeting(self);
      self.manualWalk = true;
      self.running = run;
      if (Math.hypot(target.x - self.sprite.x, target.y - self.sprite.y) < 6) {
          self.overrideUntil = this.time.now + SELF_OVERRIDE_MS;
          return;
      }
      if (!this.routeSelf(target)) {
          this.facilities?.cancel(self.id);
          return;
      }
      self.state = 'walk';
      self.stuckMs = 0;
      this.startFollowingSelf();
  },

  walkSelfToAndSelect(this: TownScene, worldX: number, worldY: number, selection: TownSelection, run?: boolean): void {
      run ??= this.runMode;
      const runtime = this.runtime;
      const self = this.selfWalker;
      if (!self) {
          this.dispatchArrival(selection);
          return;
      }
      const person = selection?.startsWith('npc:') ? this.walkers.find(w => w.id === selection || `npc:${w.id}` === selection) : undefined;
      const target = person ? socialApproach(self.sprite, person.sprite, this.collisionWorld) : nearestStandable({ x: worldX, y: worldY }, this.collisionWorld);
      if (!target) { this.saySomething(self, '这边挤了些，换个地方再聊吧。', 1800); return; }
      this.cancelGreeting(self);
      self.manualWalk = true;
      self.running = run;
      if (Math.hypot(target.x - self.sprite.x, target.y - self.sprite.y) < 6) {
          this.dispatchArrival(selection);
          return;
      }
      self.arriveSelect = selection;
      if (!this.routeSelf(target)) {
          this.facilities?.cancel(self.id);
          return;
      }
      self.state = 'walk';
      self.stuckMs = 0;
      self.travelEmote?.destroy();
      self.travelEmote = this.add.sprite(self.sprite.x, self.sprite.y - 66, 'emotes', EMOTES.question[0]).setOrigin(0.5, 1).setDepth(4002).play('emote-question');
      this.startFollowingSelf();
  },

  dispatchArrival(this: TownScene, selection: TownSelection): void {
      const runtime = this.runtime;
      const person = selection?.startsWith('npc:') ? this.walkers.find(w => w.id === selection || `npc:${w.id}` === selection) : undefined;
      if (person && this.selfWalker) {
          const distance = Math.hypot(person.sprite.x - this.selfWalker.sprite.x, person.sprite.y - this.selfWalker.sprite.y);
          if (distance < 36 || distance > 60) { this.walkSelfToAndSelect(person.sprite.x, person.sprite.y, selection); return; }
          this.selfWalker.facing = dominantDirection(person.sprite.x - this.selfWalker.sprite.x, person.sprite.y - this.selfWalker.sprite.y, 'down');
          this.selfWalker.sprite.play(`${this.selfWalker.sheet}-idle-${this.selfWalker.facing}`, true);
      }
      if (this.travel) {
          this.travel = { ...this.travel, phase: 'arrived' };
          runtime.handlers.onTravelChange?.(this.travel);
      }
      this.destinationMarker?.destroy();
      this.destinationMarker = null;
      if (selection?.startsWith('facility:')) {
          const self = this.selfWalker;
          const item = this.facilities?.interactables().find(i => i.id === selection.slice(9));
          if (self && item) {
              this.restoreSheet(self);
              self.facing = item.x < self.sprite.x ? 'left' : 'right';
              self.sprite.play(`${self.sheet}-${item.id === 'books' ? 'read' : `idle-${self.facing}`}`, true);
              self.state = 'act';
              self.overrideUntil = this.time.now + SELF_OVERRIDE_MS;
              if (!this.facilities?.activate(item.id, self.sprite, self.id))
                  this.saySomething(self, '这边暂时有人在用。');
          }
      }
      else if (selection === 'terrace') {
          this.releaseCameraFollow(60000);
          this.cameras.main.pan(runtime.terraceOrigin.x, runtime.terraceOrigin.y + 55, 1000, 'Sine.easeInOut');
          this.cameras.main.zoomTo(Math.min(1.9, Math.max(1, this.scale.width / 740)), 900, 'Sine.easeInOut');
      }
      else if (selection?.startsWith('furniture:')) {
          const item = [...this.streetFurnitureItems, ...this.venueFurniture.flatMap(v => v.items)].find(item => item.id === selection.slice(10));
          if (item)
              this.executeFurnitureInteraction(item);
      }
      else if (selection?.startsWith('neighbour:')) {
          if (this.selfWalker) this.saySomething(this.selfWalker, `${this.travelLabel(selection)}就在这里。`, 2500);
          runtime.handlers.onSelect?.(null);
      }
      else if (selection === 'academy')
          runtime.enterAcademy();
      // M3-1: 走到自家门口 = 敲门进屋，而不是像其他建筑那样弹一张信息卡。
      else if (selection && ['home', 'gym', 'cafe'].includes(selection))
          void runtime.enterRoom(selection).catch(error => {
              console.error('Town room failed to open', error);
              if (runtime.sceneRef?.selfWalker)
                  runtime.sceneRef.saySomething(runtime.sceneRef.selfWalker, '门暂时打不开，稍后再试试。');
          });
      else
          runtime.handlers.onSelect?.(selection);
  },

  handleSelfKeys(this: TownScene, now: number, delta: number): void {
      const runtime = this.runtime;
      if (document.querySelector('[role="dialog"], .resident-moment, .onboarding-overlay:not(.allows-play)') || ['INPUT', 'TEXTAREA', 'SELECT'].includes(document.activeElement?.tagName ?? '') || (document.activeElement as HTMLElement | null)?.isContentEditable) {
          this.input.keyboard?.resetKeys();
          return;
      }
      const self = this.selfWalker;
      if (!self || !this.selfKeys)
          return;
      const { cursors, keyA, keyD, keyW, keyS, shift } = this.selfKeys;
      const left = Boolean(cursors?.left.isDown || keyA?.isDown);
      const right = Boolean(cursors?.right.isDown || keyD?.isDown);
      const up = Boolean(cursors?.up.isDown || keyW?.isDown);
      const down = Boolean(cursors?.down.isDown || keyS?.isDown);
      const inputX = left === right ? 0 : left ? -1 : 1;
      const inputY = up === down ? 0 : up ? -1 : 1;
      if (inputX === 0 && inputY === 0)
          return;
      this.cancelGreeting(self);
      if (self.frozenUntil > now) return;
      if (runtime.observationOn)
          this.setObservationMode(false);
      if (this.travel || this.selfPath.length)
          this.cancelTravel();
      this.selfPath = [];
      this.endFurnitureInteraction();
      const running = this.runMode || Boolean(shift?.isDown);
      self.running = running;
      const from: Point = { x: self.sprite.x, y: self.sprite.y };
      const step = movementDelta(inputX, inputY, moveSpeed(running), delta);
      const resolved = resolveMove(from, { x: from.x + step.x, y: from.y + step.y }, this.collisionWorld);
      self.sprite.x = resolved.x;
      self.sprite.y = resolved.y;
      self.sprite.setDepth(resolved.y);
      self.targetX = resolved.x;
      self.targetY = resolved.y;
      if (self.arriveSelect !== null) {
          self.arriveSelect = null;
          self.travelEmote?.destroy();
          self.travelEmote = null;
      }
      this.cancelGreeting(self);
      self.manualWalk = true;
      self.overrideUntil = now + SELF_OVERRIDE_MS;
      const dx = resolved.x - from.x;
      const dy = resolved.y - from.y;
      if (dx === 0 && dy === 0) {
          // Held against a wall: face the blocked direction instead of moon-walking in place.
          self.sprite.play(`${self.sheet}-idle-${self.facing}`, true);
      }
      else {
          self.facing = dominantDirection(dx, dy, self.facing);
          self.sprite.play(`${self.sheet}-walk-${self.facing}`, true);
      }
      self.sprite.anims.timeScale = running ? RUN_ANIM_SCALE : 1;
      self.keyDriven = true;
      this.startFollowingSelf();
  },

  handleViewportResize(this: TownScene, size: { width: number; height: number }, _base: unknown, _display: unknown, previousWidth: number, previousHeight: number): void {
      const runtime = this.runtime;
      // Scale.refresh also emits resize when only the canvas' DOM offset changed.
      // Recentring then fights startFollow every refresh (110 vs 40px offset).
      if (size.width === previousWidth && size.height === previousHeight)
          return;
      if (!this.scene.isActive() || runtime.observationOn || this.followingCamera || this.cameras.main.panEffect.isRunning)
          return;
      this.cameras.main.centerOn(this.selfWalker?.sprite.x ?? runtime.academyDoorX, (this.selfWalker?.sprite.y ?? STREET_Y) - 40);
  },

  setupCamera(this: TownScene): void {
      const runtime = this.runtime;
      const camera = this.cameras.main;
      camera.setBounds(0, 0, runtime.width, WORLD_HEIGHT);
      camera.setZoom(Math.min(1.2, Math.max(0.75, this.scale.height / 820)));
      const self = runtime.residents.find(item => item.isSelf) ?? runtime.residents[0];
      const center = self ? this.buildingCenters.get(self.publicId) : undefined;
      camera.centerOn(this.selfWalker?.sprite.x ?? center?.x ?? runtime.academyDoorX, BASELINE - 80);
  },

  setupInput(this: TownScene): void {
      const runtime = this.runtime;
      const interact = (event: KeyboardEvent) => {
          if (event.defaultPrevented || ['INPUT', 'TEXTAREA', 'SELECT'].includes(document.activeElement?.tagName ?? '') || document.querySelector('[role="dialog"], .resident-moment'))
              return;
          this.interactNearby();
      };
      this.input.keyboard?.on('keydown-E', interact);
      this.events.once('shutdown', () => this.input.keyboard?.off('keydown-E', interact));
      this.selfWalker = this.walkers.find(walker => walker.resident?.isSelf) ?? null;
      this.selfKeys = {
          cursors: this.input.keyboard?.createCursorKeys(),
          keyA: this.input.keyboard?.addKey('A'),
          keyD: this.input.keyboard?.addKey('D'),
          keyW: this.input.keyboard?.addKey('W'),
          keyS: this.input.keyboard?.addKey('S'),
          shift: this.input.keyboard?.addKey('SHIFT'),
      };
      const camera = this.cameras.main;
      this.input.on('pointerdown', (pointer: PhaserNs.Input.Pointer) => {
          if (!runtime.isWorldPointer(pointer))
              return;
          this.atmosphere?.beginManualControl();
          this.dragStart = { x: pointer.x, y: pointer.y, scrollX: camera.scrollX, scrollY: camera.scrollY };
          this.dragged = false;
      });
      this.input.on('pointermove', (pointer: PhaserNs.Input.Pointer) => {
          if (!this.dragStart || !pointer.isDown)
              return;
          const dx = pointer.x - this.dragStart.x;
          const dy = pointer.y - this.dragStart.y;
          if (!this.dragged && Math.abs(dx) + Math.abs(dy) > 6)
              this.pauseCameraFollowForDrag();
          camera.setScroll(this.dragStart.scrollX - dx / camera.zoom, this.dragStart.scrollY - dy / camera.zoom);
      });
      this.input.on('pointerup', (pointer: PhaserNs.Input.Pointer, objects: unknown[]) => {
          if (runtime.isWorldPointer(pointer) && this.dragStart && !this.dragged && objects.length === 0 && pointer.getDistance() < 6) {
              runtime.handlers.onSelect?.(null);
              this.endFurnitureInteraction(); // 点击地面时取消家具交互
              const shiftClick = Boolean((pointer.event as MouseEvent | undefined)?.shiftKey);
              this.walkSelfToGround(pointer.worldX, pointer.worldY, this.runMode || shiftClick);
          }
          this.dragStart = null;
          this.atmosphere?.endManualControl();
      });
      this.input.on('wheel', (_pointer: PhaserNs.Input.Pointer, _objects: unknown[], _dx: number, dy: number) => {
          camera.setZoom(Math.min(2.4, Math.max(0.5, camera.zoom - dy * 0.0012)));
      });
  },

  setScenicMode(this: TownScene, on: boolean): void {
      const runtime = this.runtime;
      runtime.scenicMode = on;
      for (const object of this.children.getChildren()) {
          if (object instanceof runtime.Phaser.GameObjects.Text && object.getData('town-hud')) object.setVisible(!on);
      }
      if (on) this.releaseCameraFollow(0);
      else { this.cameraFollowPausedUntil = 0; this.startFollowingSelf(); }
  },

  startFollowingSelf(this: TownScene): void {
      const runtime = this.runtime;
      if (runtime.scenicMode || runtime.observationOn || this.followingCamera || this.time.now < this.cameraFollowPausedUntil)
          return;
      const self = this.selfWalker;
      if (!self)
          return;
      this.cameras.main.startFollow(self.sprite, false, 0.08, 0.08, 0, 40);
      this.followingCamera = true;
  },

  releaseCameraFollow(this: TownScene, pauseMs = 3000): void {
      const runtime = this.runtime;
      this.cameras.main.stopFollow();
      this.followingCamera = false;
      this.cameraFollowPausedUntil = this.time.now + pauseMs;
  },

  pauseCameraFollowForDrag(this: TownScene): void {
      const runtime = this.runtime;
      this.dragged = true;
      this.releaseCameraFollow();
  },

  setRunMode(this: TownScene, enabled: boolean): void {
      const runtime = this.runtime;
      this.runMode = enabled;
      if (!enabled && this.selfWalker) {
          this.selfWalker.running = false;
          this.selfWalker.sprite.anims.timeScale = 1;
      }
  },

  setNight(this: TownScene, night: boolean, instant = false): void {
      const runtime = this.runtime;
      runtime.desiredNight = night;
      runtime.publishTime();
      this.night = night;
      if (instant) this.atmosphere?.update(900);
      runtime.soundscape.refresh();
  },

  focusOn(this: TownScene, publicId: string): void {
      const runtime = this.runtime;
      const center = publicId === 'academy' ? { x: runtime.academyDoorX, y: BASELINE - 300 } : this.buildingCenters.get(publicId);
      if (center) {
          this.releaseCameraFollow();
          this.cameras.main.pan(center.x, center.y + 40, 500, 'Sine.easeInOut');
      }
  }
}
export type NavigationMethods = typeof navigationMethods
