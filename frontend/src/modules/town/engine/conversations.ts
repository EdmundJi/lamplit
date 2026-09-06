import type PhaserNs from 'phaser'
import { dominantDirection, registerGreet, shouldGreet } from '../walkers'
import type { WalkDirection } from '../walkers'
import { conversationDeparture } from '../npc-conversation'
import { bubbleTierFor, pointsToPlay, canInitiate, consume, layoutBubbles, wrapSpeech, peerBubbleTier } from '../talking-bubbles'
import type { BubbleBox, CameraRect } from '../talking-bubbles'
import { positionAt, dayPlanFallback } from '../day-plan'
import { FONT, GREET_PAUSE_MS, BUBBLE_HOLD_MS, EMOTES } from './shared'
import type { Walker } from './shared'
import type { TownSceneInstance as TownScene } from './scene-core'

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
      const runtime = this.runtime;
      if (a.resident?.isSelf || b.resident?.isSelf) {
          const npc = a.resident?.isSelf ? b : a;
          if (npc.npc)
              this.maybeInitiate(npc, this.time.now);
          return;
      }
      if (!a.npc || !b.npc)
          return;
      const tier = peerBubbleTier(a.npc, b.npc);
      if (tier === 'low')
          this.saySomething(a, '你好呀。', 1200);
      const hold = tier === 'high' ? BUBBLE_HOLD_MS * 3 : tier === 'mid' ? BUBBLE_HOLD_MS : 0;
      a.frozenUntil = b.frozenUntil = this.time.now + hold;
      for (const walker of [a, b]) {
          const points = pointsToPlay(tier, walker.npc?.talkingPoints ?? []);
          points.forEach((point, index) => {
              this.time.delayedCall(index * BUBBLE_HOLD_MS, () => {
                  if (walker.sprite.active && this.conversation?.walker !== walker)
                      this.saySomething(walker, point.text);
              });
          });
      }
  },

  maybeInitiate(this: TownScene, walker: Walker, now: number) {
      const runtime = this.runtime;
      const npc = walker.npc;
      if (!npc || !this.selfWalker)
          return;
      if (now < this.nextInitiativeAt)
          return;
      if (Math.hypot(walker.sprite.x - this.selfWalker.sprite.x, walker.sprite.y - this.selfWalker.sprite.y) > 90)
          return;
      if (!canInitiate(runtime.initiativeBudget, npc.code))
          return;
      const lines = pointsToPlay(bubbleTierFor(npc.affinityToPlayer), npc.talkingPoints);
      if (lines.length === 0)
          return;
      runtime.initiativeBudget = consume(runtime.initiativeBudget, npc.code);
      runtime.handlers.onInitiativeSpent?.(npc.code);
      this.nextInitiativeAt = now + 30000;
      this.saySomething(walker, lines[0].text);
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
          c.walker.frozenUntil = this.time.now; // catch up along the itinerary at normal walking speed
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
      const moving = this.walkers.filter(walker => !this.facilityNpcs.has(walker) && !this.facilities?.isBusy(walker.id) && this.conversation?.walker !== walker && walker.state === 'walk' && walker.frozenUntil <= now);
      for (let i = 0; i < moving.length; i += 1) {
          for (let j = i + 1; j < moving.length; j += 1) {
              const a = moving[i];
              const b = moving[j];
              const aDir: WalkDirection = a.targetX - a.sprite.x < 0 ? 'left' : 'right';
              const bDir: WalkDirection = b.targetX - b.sprite.x < 0 ? 'left' : 'right';
              if (Math.abs(a.sprite.y - b.sprite.y) > 48)
                  continue;
              if (shouldGreet({ id: a.id, x: a.sprite.x, dir: aDir }, { id: b.id, x: b.sprite.x, dir: bDir }, this.greetCooldowns, now)) {
                  this.triggerGreeting(a, b, now);
              }
          }
      }
  },

  triggerGreeting(this: TownScene, a: Walker, b: Walker, now: number) {
      const runtime = this.runtime;
      registerGreet(this.greetCooldowns, a.id, b.id, now);
      a.frozenUntil = now + GREET_PAUSE_MS;
      b.frozenUntil = now + GREET_PAUSE_MS;
      const aFacesRight = a.sprite.x <= b.sprite.x;
      a.sprite.play(`${a.sheet}-idle-${aFacesRight ? 'right' : 'left'}`, true);
      b.sprite.play(`${b.sheet}-idle-${aFacesRight ? 'left' : 'right'}`, true);
      for (const walker of [a, b]) {
          walker.travelEmote?.destroy();
          walker.travelEmote = this.add.sprite(walker.sprite.x, walker.sprite.y - 66, 'emotes', EMOTES.heart[0]).setOrigin(0.5, 1).setDepth(4002).play('emote-heart');
      }
      this.speakOnEncounter(a, b);
  }
}
export type ConversationsMethods = typeof conversationsMethods
