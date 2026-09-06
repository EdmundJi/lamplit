import { freshSocialLine, allowSocialEncounter } from '../social-pacing'
import type PhaserNs from 'phaser'
import { dominantDirection, registerGreet, shouldGreet } from '../walkers'
import type { WalkDirection } from '../walkers'
import { conversationDeparture } from '../npc-conversation'
import { bubbleTierFor, pointsToPlay, canInitiate, consume, layoutBubbles, wrapSpeech, peerBubbleTier } from '../talking-bubbles'
import type { BubbleBox, CameraRect } from '../talking-bubbles'
import { positionAt, dayPlanFallback } from '../day-plan'
import { FONT, BUBBLE_HOLD_MS } from './shared'
import type { Walker } from './shared'
import type { TownSceneInstance as TownScene } from './scene-core'
import { createSocialTimeline, socialGestureFrame } from '../social-motion'

type Encounter = { a: Walker; b: Walker; ax: number; ay: number; bx: number; by: number; timeline: ReturnType<typeof createSocialTimeline>; turnMs: number; lines: [string, string]; speech: PhaserNs.GameObjects.Container | null; gesture?: { actor: Walker; start: number; posing: boolean } }
const encounters = new WeakMap<object, Encounter[]>()
function releaseEncounter(scene: TownScene, pair: Encounter) {
  pair.timeline.cancel()
  if (pair.gesture?.posing && pair.gesture.actor.sprite.active) pair.gesture.actor.sprite.play(`${pair.gesture.actor.sheet}-idle-${pair.gesture.actor.facing}`, true)
  for (const actor of [pair.a, pair.b]) {
    if (actor.speech === pair.speech) { actor.speech?.destroy(); actor.speech = null }
    if (actor.frozenUntil === pair.timeline.until) { actor.frozenUntil = scene.time.now || 1; actor.npcActivity = null }
  }
}


export const conversationsMethods = {
  saySomething(this: TownScene, walker: Walker, text: string, holdMs = BUBBLE_HOLD_MS) {
      const runtime = this.runtime;
      walker.speech?.destroy();
      const label = this.add.text(0, 0, text, {
          fontFamily: FONT, fontSize: '13px', color: '#2a211c', wordWrap: { callback: (text: string) => wrapSpeech(text, 16) },
          align: 'center', lineSpacing: 3,
      }).setOrigin(0.5, 1).setResolution(3);
      const padX = 9;
      const padY = 7;
      const bubble = this.add.graphics();
      // 文字的 origin 是 (0.5, 1)，也就是占 y ∈ [-h, 0]。气泡必须绕着这个范围上下各留一份内边距，
      // 否则上边 padY*2、下边 0，文字会紧贴着底边——看上去就是"文字溢出气泡"。
      bubble.fillStyle(0xfdf8f0, 0.97);
      bubble.fillRoundedRect(-label.width / 2 - padX, -label.height - padY, label.width + padX * 2, label.height + padY * 2, 8);
      bubble.lineStyle(1, 0x2a211c, 0.18);
      bubble.strokeRoundedRect(-label.width / 2 - padX, -label.height - padY, label.width + padX * 2, label.height + padY * 2, 8);
      const container = this.add.container(walker.sprite.x, walker.sprite.y - 74, [bubble, label]).setDepth(4100);
      // 说话的人可能正站在画面边缘，气泡比人宽得多，直接跟着他就会被镜头切掉半句；同屏还
      // 可能不止一个人在说话，两个气泡会叠在一起。半宽 + 整体高度记下来，交给
      // layoutSpeechBubbles（M7-8）统一夹回镜头、互相避让——这里只管把气泡"生"出来。
      container.setData('halfWidth', label.width / 2 + padX);
      container.setData('bubbleHeight', label.height + padY * 2);
      walker.speech = container;
      this.layoutSpeechBubbles();
      this.time.delayedCall(holdMs, () => {
          if (walker.speech === container)
              walker.speech = null;
          container.destroy();
      });
  },

  layoutSpeechBubbles(this: TownScene) {
      const runtime = this.runtime;
      const allSpeakers = this.walkers.filter(walker => walker.speech !== null);
      if (allSpeakers.length === 0)
          return;
      const camera = this.cameras.main;
      const origin = camera.getWorldPoint(0, 0);
      const cameraRect: CameraRect = { x: origin.x + 12 / camera.zoom, y: origin.y + 80 / camera.zoom, width: (camera.width - 24) / camera.zoom, height: (camera.height - 150) / camera.zoom };
      const speakers = allSpeakers.filter(walker => {
          const visible = walker.sprite.x >= origin.x && walker.sprite.x <= origin.x + camera.width / camera.zoom && walker.sprite.y >= origin.y && walker.sprite.y <= origin.y + camera.height / camera.zoom;
          walker.speech?.setVisible(visible);
          return visible;
      }).slice(0, 3);
      for (const walker of allSpeakers)
          if (!speakers.includes(walker))
              walker.speech?.setVisible(false);
      const boxes: BubbleBox[] = speakers.map(walker => {
          const container = walker.speech as PhaserNs.GameObjects.Container;
          const halfWidth = (container.getData('halfWidth') as number) ?? 0;
          const height = (container.getData('bubbleHeight') as number) ?? 0;
          const bottomY = walker.sprite.y - 74; // 气泡的锚点(容器 y=0 处)就是它自己的底边
          return { id: walker.id, x: walker.sprite.x - halfWidth, y: bottomY - height, width: halfWidth * 2, height };
      });
      const placed = layoutBubbles(boxes, cameraRect);
      speakers.forEach((walker, index) => {
          const box = placed[index];
          (walker.speech as PhaserNs.GameObjects.Container).setPosition(box.x + box.width / 2, box.y + box.height);
      });
  },

  speakOnEncounter(this: TownScene, a: Walker, b: Walker) {
      this.triggerGreeting(a, b, this.time.now);
  },

  cancelGreeting(this: TownScene, actor?: Walker) {
      const pairs = encounters.get(this) ?? [];
      for (const pair of pairs) if (!actor || pair.a === actor || pair.b === actor) releaseEncounter(this, pair);
      encounters.set(this, pairs.filter(pair => actor && pair.a !== actor && pair.b !== actor));
  },

  maybeInitiate(this: TownScene, walker: Walker, now: number) {
      const runtime = this.runtime;
      const npc = walker.npc;
      if (!npc || !this.selfWalker || this.conversation || walker.speech)
          return;
      if ((encounters.get(this) ?? []).some(pair => pair.a === walker || pair.b === walker)) return;
      if (now < this.nextInitiativeAt)
          return;
      if (Math.hypot(walker.sprite.x - this.selfWalker.sprite.x, walker.sprite.y - this.selfWalker.sprite.y) > 90)
          return;
      if (!canInitiate(runtime.initiativeBudget, npc.code))
          return;
      const lines = pointsToPlay(bubbleTierFor(npc.affinityToPlayer), npc.talkingPoints);
      const text = freshSocialLine(runtime, walker.id, lines.map(line => line.text));
      if (!text) return;
      runtime.initiativeBudget = consume(runtime.initiativeBudget, npc.code);
      runtime.handlers.onInitiativeSpent?.(npc.code);
      this.nextInitiativeAt = now + 180_000;
      this.saySomething(walker, text);
  },

  beginConversation(this: TownScene, code: string) {
      const runtime = this.runtime;
      if (this.conversation?.code === code)
          return true;
      this.endConversation();
      const id = code === 'GUIDE' ? 'npc:assistant' : code === 'POSTMAN' ? 'npc:postman' : code;
      const walker = this.walkers.find(w => w.id === id || w.npc?.code === code);
      if (!walker?.sprite.active || walker.npc?.layer === 3)
          return false;
      this.cancelGreeting(walker);
      if (this.selfWalker) this.cancelGreeting(this.selfWalker);
      this.facilities?.cancel(walker.id);
      this.facilityNpcs.delete(walker);
      const npc = walker.npc;
      const position = npc ? positionAt(npc.dayPlan ?? dayPlanFallback(npc.schedule), this.npcMinuteOfDay()) : null;
      const initialPlace = position?.kind === 'WALKING' ? position.toPlace : position?.place ?? 'street';
      this.conversation = { walker, code, since: Date.now(), initialPlace, phase: 'talking', state: walker.state, timer: walker.timer };
      walker.state = 'act';
      walker.frozenUntil = 0;
      walker.speech?.destroy();
      walker.speech = null;
      walker.travelEmote?.destroy();
      walker.travelEmote = null;
      this.restoreSheet(walker);
      walker.facing = dominantDirection((this.selfWalker?.sprite.x ?? walker.sprite.x) - walker.sprite.x, (this.selfWalker?.sprite.y ?? walker.sprite.y + 10) - walker.sprite.y, 'down');
      walker.sprite.play(`${walker.sheet}-idle-${walker.facing}`, true);
      runtime.handlers.onConversationChange?.({ npcCode: code, name: npc?.displayName ?? (code === 'GUIDE' ? '小助' : '邮递员'), phase: 'talking' });
      return true;
  },

  interruptConversation(this: TownScene, code: string, reason: string) {
      const runtime = this.runtime;
      const c = this.conversation;
      const text = reason.trim();
      if (!c || c.code !== code || c.phase === 'leaving' || !text || text.length > 200)
          return;
      c.phase = 'leaving';
      c.reason = text;
      c.until = Date.now() + 4000;
      this.saySomething(c.walker, text, 4500);
      runtime.handlers.onConversationChange?.({ npcCode: code, name: c.walker.npc?.displayName ?? (code === 'GUIDE' ? '小助' : '邮递员'), phase: 'leaving', reason: text, npcInitiated: true });
  },

  endConversation(this: TownScene, code?: string) {
      const runtime = this.runtime;
      const c = this.conversation;
      if (!c || (code && code !== c.code))
          return;
      this.conversation = null;
      if (c.walker.sprite.active) {
          c.walker.state = c.state;
          c.walker.timer = c.timer;
          c.walker.frozenUntil = this.time.now + 350; // A short closing beat before walking back along the collision route
          c.walker.npcActivity = null;
      }
      runtime.handlers.onConversationChange?.({ npcCode: c.code, name: c.walker.npc?.displayName ?? (c.code === 'GUIDE' ? '小助' : '邮递员'), phase: 'ended', reason: c.reason, npcInitiated: c.phase === 'leaving' });
  },

  updateConversation(this: TownScene) {
      const runtime = this.runtime;
      const c = this.conversation;
      if (!c)
          return;
      if (c.phase === 'leaving') {
          if (Date.now() >= (c.until ?? Infinity))
              this.endConversation(c.code);
          return;
      }
      const reason = conversationDeparture(c.walker.npc, this.npcMinuteOfDay(), Date.now() - c.since, c.initialPlace);
      if (reason)
          this.interruptConversation(c.code, reason);
  },

  detectGreetings(this: TownScene, now: number) {
      const runtime = this.runtime;
      const pairs = encounters.get(this) ?? [];
      const continuing: Encounter[] = [];
      for (const pair of pairs) {
          const interrupted = !pair.a.sprite.active || !pair.b.sprite.active || this.conversation?.walker === pair.a || this.conversation?.walker === pair.b
              || this.facilities?.isBusy(pair.a.id) || this.facilities?.isBusy(pair.b.id)
              || Math.hypot(pair.a.sprite.x - pair.ax, pair.a.sprite.y - pair.ay) > 8 || Math.hypot(pair.b.sprite.x - pair.bx, pair.b.sprite.y - pair.by) > 8;
          if (interrupted) { releaseEncounter(this, pair); continue; }
          const phase = pair.timeline.advance(now);
          if (phase === 'done') { releaseEncounter(this, pair); continue; }
          if (phase === 'greet' || phase === 'reply') {
              for (const actor of [pair.a, pair.b]) if (actor.speech === pair.speech) { actor.speech?.destroy(); actor.speech = null; }
              const speaker = phase === 'greet' ? pair.a : pair.b;
              const line = pair.lines[phase === 'greet' ? 0 : 1];
              if (line) this.saySomething(speaker, line, phase === 'greet' ? pair.turnMs : pair.turnMs + 200);
              pair.speech = speaker.speech;
              if (pair.gesture?.posing) pair.gesture.actor.sprite.play(`${pair.gesture.actor.sheet}-idle-${pair.gesture.actor.facing}`, true);
              pair.gesture = { actor: speaker, start: now, posing: false };
          }
          if (pair.gesture) {
              const gesture = pair.gesture, actor = gesture.actor;
              const source = actor.sprite.texture?.getSourceImage() as HTMLImageElement | undefined;
              const frame = source ? socialGestureFrame(source.width, source.height, actor.facing, now - gesture.start) : null;
              if (frame !== null && actor.sprite.texture.has(String(frame))) {
                  actor.sprite.anims.stop(); actor.sprite.setFrame(frame); gesture.posing = true;
              } else if (gesture.posing) {
                  actor.sprite.play(`${actor.sheet}-idle-${actor.facing}`, true); gesture.posing = false;
              }
          }
          if (phase === 'leave') for (const actor of [pair.a, pair.b]) {
              if (actor.speech === pair.speech) { actor.speech?.destroy(); actor.speech = null; }
              actor.facing = dominantDirection(actor.targetX - actor.sprite.x, actor.targetY - actor.sprite.y, actor.facing);
              actor.sprite.play(`${actor.sheet}-idle-${actor.facing}`, true);
          }
          continuing.push(pair);
      }
      encounters.set(this, continuing);
      const moving = this.walkers.filter(walker => !this.facilityNpcs.has(walker) && !this.facilities?.isBusy(walker.id) && this.conversation?.walker !== walker && walker.state === 'walk' && walker.frozenUntil <= now);
      for (let i = 0; i < moving.length; i += 1) {
          for (let j = i + 1; j < moving.length; j += 1) {
              const a = moving[i];
              const b = moving[j];
              const aDir: WalkDirection = a.targetX - a.sprite.x < 0 ? 'left' : 'right';
              const bDir: WalkDirection = b.targetX - b.sprite.x < 0 ? 'left' : 'right';
              if (Math.abs(a.sprite.y - b.sprite.y) > 48)
                  continue;
              if (shouldGreet({ id: a.id, x: a.sprite.x, dir: aDir }, { id: b.id, x: b.sprite.x, dir: bDir }, this.greetCooldowns, now, 48)) {
                  this.triggerGreeting(a, b, now);
              }
          }
      }
  },

  triggerGreeting(this: TownScene, a: Walker, b: Walker, now: number) {
      if (a.frozenUntil > now || b.frozenUntil > now || !a.sprite.active || !b.sprite.active) return;
      if (this.conversation?.walker === a || this.conversation?.walker === b || this.facilityNpcs.has(a) || this.facilityNpcs.has(b)) return;
      const distance = Math.hypot(a.sprite.x - b.sprite.x, a.sprite.y - b.sprite.y);
      if (distance < 32 || distance > 70) return;
      if (!allowSocialEncounter(this.runtime, a.id, b.id)) return;
      registerGreet(this.greetCooldowns, a.id, b.id, now, 300_000);
      const tier = a.npc && b.npc ? peerBubbleTier(a.npc, b.npc) : 'low';
      const first = a.npc ? pointsToPlay(tier, a.npc.talkingPoints)[0]?.text : undefined;
      const second = b.npc ? pointsToPlay(tier, b.npc.talkingPoints)[0]?.text : undefined;
      const lines: [string, string] = [
          freshSocialLine(this.runtime, a.id, first ? [first] : []) ?? '',
          freshSocialLine(this.runtime, b.id, second ? [second] : []) ?? '',
      ];
      const turnMs = Math.min(4200, Math.max(1300, Math.max(...lines.map(line => line.length)) * 95));
      const timeline = createSocialTimeline(now, turnMs);
      for (const [actor, other] of [[a, b], [b, a]] as const) {
          this.restoreSheet(actor);
          actor.frozenUntil = timeline.until;
          actor.facing = dominantDirection(other.sprite.x - actor.sprite.x, other.sprite.y - actor.sprite.y, actor.facing);
          actor.sprite.play(`${actor.sheet}-idle-${actor.facing}`, true);
          actor.travelEmote?.destroy(); actor.travelEmote = null;
      }
      const pair: Encounter = { a, b, ax: a.sprite.x, ay: a.sprite.y, bx: b.sprite.x, by: b.sprite.y, timeline, turnMs, lines, speech: null };
      encounters.set(this, [...(encounters.get(this) ?? []), pair]);

  }
}
export type ConversationsMethods = typeof conversationsMethods
