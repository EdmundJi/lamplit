import { hashString } from '../building-kit'
import { RESIDENT_WALK_SPEED, dominantDirection, stepTowardPoint } from '../walkers'
import { nearestStandable, resolveMove } from '../collision'
import { findPath } from '../pathfinding'
import type { FurnitureItem } from '../town-furniture'
import { positionAt, dayPlanFallback } from '../day-plan'
import { createTownLifeStage } from '../town-life-stage'
import { createTownFacilities, type TownFacilityId } from '../town-facilities'
import { STREET_Y, SELF_OVERRIDE_MS, resolveNpcFrame } from './shared'
import type { TownSceneInstance as TownScene } from './scene-core'

export const facilitiesMethods = {
  drawLifeTerrace(this: TownScene) {
      const runtime = this.runtime;
      const stage = this.lifeStage = createTownLifeStage(this, runtime.terraceOrigin);
      this.furnitureCollisions.push(...stage.obstacles);
      const a = stage.anchors;
      this.entrances.set('terrace', a.playerSpawn);
      this.buildingCenters.set('terrace', { x: runtime.terraceOrigin.x, y: runtime.terraceOrigin.y + 80 });
      this.facilities = createTownFacilities(this, {
          coffee: a.coffee,
          planter: { ...a.plant, actionPoint: a.gardener },
          records: { ...a.records, actionPoint: { x: a.records.x, y: a.records.y + 21 } },
          books: { ...a.bookshelf, actionPoint: { x: a.bookshelf.x, y: a.bookshelf.y + 20 } },
      }, {
          storageKey: `town:facilities:${runtime.residents.find(r => r.isSelf)?.publicId ?? 'local'}`,
          playerId: runtime.residents.find(r => r.isSelf)?.publicId ?? 'self',
          onSelect: (id, pointer) => {
              if (runtime.isWorldPointer(pointer) && this.dragStart && !this.dragged && pointer.getDistance() < 6)
                  this.travelToPlace(`facility:${id}`);
          },
          onComplete: (id, actorId) => {
              if (id === 'coffee')
                  stage.setChairPulledOut(true);
              if (id === 'records' && this.facilities?.snapshot().state.music && actorId === this.selfWalker?.id)
                  this.inviteTerraceNeighbours();
              if (actorId === this.selfWalker?.id) {
                  this.selfWalker.overrideUntil = this.time.now + SELF_OVERRIDE_MS;
                  this.selfWalker.state = 'act';
              }
          },
      });
      stage.setChairPulledOut(this.facilities.snapshot().state.cup);
  },

  inviteTerraceNeighbours(this: TownScene) {
      const runtime = this.runtime;
      const minute = this.npcMinuteOfDay();
      const nearby = runtime.townNpcRoster.filter(npc => {
          if (npc.layer === 1)
              return false;
          const plan = npc.dayPlan ?? dayPlanFallback(npc.schedule);
          const position = positionAt(plan, minute);
          const errand = plan.errands.find(e => e.startMinute <= minute && e.endMinute > minute);
          if (position.kind !== 'AT' || (errand?.priority ?? 1) >= 2 || ['academy', 'gym'].includes(position.place))
              return false;
          const frame = resolveNpcFrame(plan, minute, npc.code, this.townLayout(), STREET_Y);
          return Math.hypot(frame.x - runtime.terraceOrigin.x, frame.y - runtime.terraceOrigin.y) < 1000;
      }).sort((a, b) => a.layer - b.layer || a.code.localeCompare(b.code)).slice(0, 2);
      this.terraceInvited = new Set(nearby.map(n => n.code));
      this.terraceInvitationUntil = this.time.now + 90000;
      for (const code of this.terraceInvited)
          this.facilityCooldowns.delete(code);
      this.nextFacilityVisitAt = 0;
      this.applyTownNpcs(runtime.townNpcRoster);
  },

  updateFacilityVisitors(this: TownScene, now: number, delta: number) {
      const runtime = this.runtime;
      const facilities = this.facilities;
      if (!facilities)
          return;
      for (const [walker, visit] of this.facilityNpcs) {
          if (!walker.sprite.active || this.conversation?.walker === walker || now > visit.until) {
              facilities.cancel(walker.id);
              this.facilityNpcs.delete(walker);
              walker.frozenUntil = now - 1;
              continue;
          }
          if (visit.phase === 'using') {
              if (!facilities.isBusy(walker.id)) {
                  this.facilityNpcs.delete(walker);
                  walker.frozenUntil = now - 1;
                  walker.npcActivity = null;
              }
              continue;
          }
          const target = visit.path[0];
          facilities.reserve(visit.id, walker.id);
          if (target) {
              const from = { x: walker.sprite.x, y: walker.sprite.y };
              const point = resolveMove(from, stepTowardPoint(from, target, RESIDENT_WALK_SPEED, delta), this.collisionWorld);
              const direction = dominantDirection(point.x - from.x, point.y - from.y, walker.facing);
              walker.facing = direction;
              walker.sprite.setPosition(point.x, point.y).setDepth(point.y).play(`${walker.sheet}-walk-${direction}`, true);
              walker.state = 'walk';
              if (Math.hypot(point.x - target.x, point.y - target.y) < 1)
                  visit.path.shift();
          }
          else {
              const item = facilities.interactables().find(item => item.id === visit.id)!;
              walker.facing = item.x < walker.sprite.x ? 'left' : 'right';
              walker.sprite.play(`${walker.sheet}-${visit.id === 'books' ? 'read' : `idle-${walker.facing}`}`, true);
              walker.state = 'act';
              if (facilities.activate(visit.id, walker.sprite, walker.id))
                  visit.phase = 'using';
              else {
                  this.facilityNpcs.delete(walker);
                  facilities.cancel(walker.id);
                  walker.frozenUntil = now - 1;
              }
          }
      }
      if (now < this.nextFacilityVisitAt || this.facilityNpcs.size >= 2)
          return;
      this.nextFacilityVisitAt = now + 4500;
      const candidates = this.walkers.filter(w => {
          if (!w.npc || w.npc.layer === 1 || this.facilityNpcs.has(w) || this.conversation?.walker === w || (this.facilityCooldowns.get(w.id) ?? 0) > now)
              return false;
          const position = positionAt(w.dayPlan ?? dayPlanFallback(w.npc.schedule), this.npcMinuteOfDay());
          const errand = (w.dayPlan ?? dayPlanFallback(w.npc.schedule)).errands.find(e => e.startMinute <= this.npcMinuteOfDay() && e.endMinute > this.npcMinuteOfDay());
          if ((errand?.priority ?? 1) >= 2)
              return false;
          const invited = this.terraceInvited.has(w.id) && now < this.terraceInvitationUntil;
          if (position.kind !== 'AT' || (!invited && !['cafe', 'gym', 'street'].includes(position.place)))
              return false;
          return Math.hypot(w.sprite.x - runtime.terraceOrigin.x, w.sprite.y - runtime.terraceOrigin.y) < (invited ? 1000 : 650);
      }).sort((a, b) => Math.abs(a.sprite.x - runtime.terraceOrigin.x) - Math.abs(b.sprite.x - runtime.terraceOrigin.x));
      for (const walker of candidates) {
          const order: TownFacilityId[] = hashString(walker.id) % 2 ? ['books', 'coffee', 'planter'] : ['planter', 'coffee', 'books'];
          for (const id of order) {
              if (id === 'records')
                  continue;
              const item = facilities.interactables().find(item => item.id === id)!;
              if (!facilities.reserve(id, walker.id))
                  continue;
              const route = findPath(walker.sprite, item.actionPoint, this.collisionWorld);
              if (!route) {
                  facilities.cancel(walker.id);
                  continue;
              }
              this.restoreSheet(walker);
              this.facilityNpcs.set(walker, { id, path: route, phase: 'walking', until: now + 38000 });
              this.facilityCooldowns.set(walker.id, now + 90000);
              return;
          }
      }
  },

  onFurnitureInteract(this: TownScene, item: FurnitureItem) {
      const runtime = this.runtime;
      if (!this.selfWalker || this.activeFurnitureId === item.id) {
          // 再次点击同一家具 = 取消交互
          this.endFurnitureInteraction();
          return;
      }
      const target = nearestStandable({ x: item.x + 24, y: item.y + 12 }, this.collisionWorld);
      this.activeFurnitureId = item.id;
      this.walkSelfToAndSelect(target.x, target.y, `furniture:${item.id}`);
  },

  executeFurnitureInteraction(this: TownScene, item: FurnitureItem) {
      const runtime = this.runtime;
      if (!this.selfWalker)
          return;
      this.usingLegacyFurniture = true;
      switch (item.interactionType) {
          case 'sit':
              this.selfWalker.state = 'act';
              this.selfWalker.sprite.anims.stop();
              this.selfWalker.sprite.setCrop(0, 0, 32, 54);
              this.seatedLegs?.destroy();
              this.seatedLegs = this.add.graphics().setDepth(this.selfWalker.sprite.y + 1);
              this.seatedLegs.lineStyle(5, 0x555869).lineBetween(this.selfWalker.sprite.x, this.selfWalker.sprite.y - 9, this.selfWalker.sprite.x + 10, this.selfWalker.sprite.y - 9).lineBetween(this.selfWalker.sprite.x + 10, this.selfWalker.sprite.y - 9, this.selfWalker.sprite.x + 10, this.selfWalker.sprite.y - 2);
              break;
          case 'read':
              // 查看告示牌/菜单：显示气泡提示
              this.selfWalker.state = 'act';
              this.saySomething(this.selfWalker, item.id.startsWith('gym') ? '练完歇一会儿。咖啡馆外的新露台，有书、有花，也有唱片。' : '街角借书架可以借阅和归还；花槽旁的水壶也可以随手用。');
              break;
          case 'view':
              // 查看报刊亭等：显示气泡
              this.selfWalker.state = 'act';
              this.saySomething(this.selfWalker, '今天的街角：咖啡、翻书声，还有等你照料的小花。');
              break;
      }
  },

  showInteractionBubble(this: TownScene, x: number, y: number, emoji: string) {
      const runtime = this.runtime;
      if (this.interactionBubble)
          this.interactionBubble.destroy();
      const text = this.add.text(x, y, emoji, { fontSize: '24px' }).setOrigin(0.5).setDepth(5000);
      this.interactionBubble = text;
      this.time.delayedCall(2000, () => {
          if (this.interactionBubble === text)
              this.interactionBubble = null;
          text.destroy();
      });
  },

  endFurnitureInteraction(this: TownScene) {
      const runtime = this.runtime;
      if (this.selfWalker)
          this.facilities?.cancel(this.selfWalker.id);
      if (this.usingLegacyFurniture)
          this.selfWalker?.sprite.setCrop();
      this.usingLegacyFurniture = false;
      this.seatedLegs?.destroy();
      this.seatedLegs = null;
      this.activeFurnitureId = null;
      if (this.interactionBubble) {
          this.interactionBubble.destroy();
          this.interactionBubble = null;
      }
      if (this.selfWalker && this.selfWalker.state === 'act') {
          this.selfWalker.state = 'walk';
      }
  }
}
export type FacilitiesMethods = typeof facilitiesMethods
