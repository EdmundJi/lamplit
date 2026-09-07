import { safeTownPoint } from '../town-recovery'
import type { Point } from '../collision'
import { createInteriorScene, interiorSceneKey } from '../interior.scene'
import type { InteriorOptions, InteriorPet, InteriorPlayer } from '../interior.scene'
import { parseRoomMap } from '../map-loader'
import { positionAt, dayPlanFallback } from '../day-plan'
import { ASSETS, STREET_Y, SCENE_FADE_MS, fetchHomeExtras, characterSheet } from './shared'
import type { TownRuntime } from './runtime'

export function createRoomTransitions(runtime: TownRuntime) {
  function enterAcademy() {
      void runtime.enterRoom('academy').catch(error => {
          console.error('Town academy failed to open', error);
          if (runtime.sceneRef?.selfWalker)
              runtime.sceneRef.saySomething(runtime.sceneRef.selfWalker, '学院暂时打不开，稍后再试试。');
      });
  }
  
  async function enterRoom(roomId: string) {
      if (!runtime.sceneRef || runtime.academyEntered || runtime.academyBusy || runtime.roomBusy || runtime.activeRoomKey)
          return;
      const paths: Record<string, string> = { home: 'home-living-room', academy: 'academy-study', gym: 'public-gym', cafe: 'cafe-interior' };
      const file = paths[roomId];
      if (!file)
          return;
      runtime.roomBusy = true;
      const generation = ++runtime.transitionGeneration;
      runtime.roomRequest?.abort();
      const request = new AbortController();
      runtime.roomRequest = request;
      const requestTimeout = setTimeout(() => request.abort(), 12000);
      const town = runtime.sceneRef;
      town.endFurnitureInteraction();
      town.facilities?.cancel();
      town.facilities?.setMuted(true);
      town.facilityNpcs.clear();
      town.endConversation();
      if (town.selfWalker)
          runtime.exteriorReturn = { x: town.selfWalker.sprite.x, y: town.selfWalker.sprite.y };
      const cam = town.cameras.main;
      try {
          const fadeOutDone = new Promise<void>(resolve => {
              cam.fadeEffect.start(true, SCENE_FADE_MS, 8, 10, 8, true);
              cam.once('camerafadeoutcomplete', () => resolve());
              request.signal.addEventListener('abort', () => resolve(), { once: true });
          });
          const [room, , extras] = await Promise.all([
              fetch(`${ASSETS}/maps/${file}.json`, { signal: request.signal }).then(response => { if (!response.ok)
                  throw new Error(`房间地图加载失败 (${response.status})`); return response.json(); }).then(parseRoomMap),
              fadeOutDone,
              roomId === 'home' ? fetchHomeExtras(runtime.latestModel.residents.find(r => r.isSelf)?.timezone) : Promise.resolve({ homeAchievements: 0, pet: undefined as InteriorPet | undefined, memories: [] }),
          ]);
          if (generation !== runtime.transitionGeneration || runtime.sceneRef !== town || request.signal.aborted)
              return;
          const game = town.sys.game;
          const key = interiorSceneKey(room.id);
          if (game.scene.getScene(key))
              game.scene.remove(key);
          const self = runtime.latestModel.residents.find(item => item.isSelf) ?? null;
          const player: InteriorPlayer | undefined = self ? { characterSheet: characterSheet(self.publicId) } : undefined;
          const options: InteriorOptions = {
              room,
              metrics: { homeAchievements: extras.homeAchievements, healthDone: self?.schedules.filter(s => ['DONE', 'PARTIAL'].includes(s.status) && s.roleCode === 'FITNESS_USER').length ?? 0, knowledgeDone: self?.schedules.filter(s => ['DONE', 'PARTIAL'].includes(s.status) && ['STUDENT', 'WORKER'].includes(s.roleCode ?? '')).length ?? 0 },
              residents: roomId === 'academy' ? runtime.townNpcRoster.filter(npc => {
                  const position = positionAt(npc.dayPlan ?? dayPlanFallback(npc.schedule), town.npcMinuteOfDay());
                  return npc.layer !== 1 && position.kind === 'AT' && position.place === 'academy';
              }).slice(0, 4).map(npc => ({ publicId: npc.code, displayName: npc.displayName, isSelf: false, characterSheet: Number(npc.sprite.replace('c', '')) || 1, state: 'reading' as const })) : [],
              player,
              pet: roomId === 'home' && runtime.companionLoaded
                ? (runtime.companionState.pet ? { id: runtime.companionState.pet.publicId, name: runtime.companionState.pet.name, species: runtime.companionState.pet.speciesCode as InteriorPet['species'], breed: runtime.companionState.pet.breed, furColor: runtime.companionState.pet.furColor } : undefined)
                : extras.pet,
              homeObjectState: () => ({ hasPet: runtime.companionLoaded ? !!runtime.companionState.pet : !!extras.pet, outing: runtime.companionState.mode !== 'home', unread: runtime.letterUnread }),
              memories: extras.memories,
              homeAccountId: roomId === 'home' ? self?.publicId : undefined,
              isQuiet: () => runtime.scenicMode,
              onExit: target => { if (target === 'town')
                  runtime.exitRoom(); },
              // engine 自己不认识 home.open-desk 之类的动作 id，转给外壳去接 world-actions.ts。
              onInteract: (actionId, id) => runtime.handlers.onInteriorInteract?.(actionId, id),
              onPosition: (x, y, facing) => { if (generation === runtime.transitionGeneration && runtime.activeRoomKey === key)
                  runtime.presenceReporter.update({ x: Math.round(x), y: Math.round(y), facing, scene: key }); },
          };
          const interiorScene = game.scene.add(key, createInteriorScene(runtime.Phaser, options), true, options);
          town.scene.sleep();
          runtime.activeRoomKey = key;
          runtime.soundscape.setIndoor(true);
          runtime.academyEntered = roomId === 'academy';
          if (runtime.academyEntered)
              runtime.handlers.onAcademyChange?.(true);
          runtime.handlers.onRoomChange?.(roomId);
          runtime.handlers.onNearbyChange?.(null);
          runtime.handlers.onSelect?.(null);
          runtime.presenceReporter.update({ x: room.spawn.x, y: room.spawn.y, facing: 'down', scene: key });
          runtime.presenceReporter.flush();
          interiorScene?.events.once('create', () => { interiorScene.cameras.main.fadeIn(SCENE_FADE_MS, 8, 10, 8); });
      }
      catch (error) {
          if (generation !== runtime.transitionGeneration)
              return;
          town.facilities?.setMuted(!runtime.soundEnabled || document.hidden);
          cam.fadeIn(SCENE_FADE_MS, 8, 10, 8); // 加载失败也要把镜头亮回来，不能留一片黑屏
          throw error;
      }
      finally {
          clearTimeout(requestTimeout);
          if (generation === runtime.transitionGeneration) {
              runtime.roomBusy = false;
              runtime.roomRequest = null;
          }
      }
  }
  
  function restoreStreet(preferred: Point | null): boolean {
      const town = runtime.sceneRef, self = town?.selfWalker;
      if (!town || !self)
          return false;
      const home = town.entrances.get('home') ?? { x: runtime.academyDoorX, y: STREET_Y };
      const point = safeTownPoint(town.collisionWorld, preferred, home);
      if (!point)
          return false;
      town.endConversation();
      town.cancelTravel();
      town.endFurnitureInteraction();
      town.setObservationMode(false);
      town.input.keyboard?.resetKeys();
      self.frozenUntil = 0;
      self.keyDriven = false;
      self.running = false;
      self.sprite.setPosition(point.x, point.y).setDepth(point.y);
      self.targetX = point.x;
      self.targetY = point.y;
      self.facing = 'down';
      self.sprite.anims.timeScale = 1;
      self.sprite.play(`${self.sheet}-idle-down`, true);
      self.label.setPosition(point.x, point.y + 4);
      const camera = town.cameras.main;
      camera.stopFollow();
      camera.panEffect.reset();
      camera.zoomEffect.reset();
      camera.fadeEffect.reset();
      camera.setZoom(Math.min(1.2, Math.max(.75, town.scale.height / 820)));
      camera.centerOn(point.x, point.y - 110);
      town.followingCamera = false;
      town.cameraFollowPausedUntil = 0;
      town.startFollowingSelf();
      runtime.presenceReporter.clear();
      town.reportSelfPresence();
      runtime.presenceReporter.flush();
      runtime.handlers.onPlayerMove?.(point.x, point.y);
      return true;
  }
  
  function recover(): boolean {
      if (!runtime.sceneRef?.selfWalker)
          return false;
      runtime.transitionGeneration++;
      runtime.requestedConversation = null;
      runtime.sceneRef.endConversation();
      runtime.roomRequest?.abort();
      runtime.roomRequest = null;
      runtime.roomBusy = false;
      runtime.academyBusy = false;
      runtime.queuedDestination = null;
      runtime.activeRoomKey = null;
      runtime.academyEntered = false;
      for (const scene of runtime.game.scene.getScenes(false)) {
          if (scene.sys.settings.key.startsWith('interior:'))
              runtime.game.scene.stop(scene.sys.settings.key);
      }
      runtime.game.scene.wake('town');
      const restored = runtime.restoreStreet(null);
      runtime.handlers.onRoomChange?.(null);
      runtime.handlers.onAcademyChange?.(false);
      runtime.handlers.onNearbyChange?.(null);
      runtime.handlers.onSelect?.(null);
      return restored;
  }
  
  function exitRoom() {
      if (!runtime.sceneRef || !runtime.activeRoomKey || runtime.roomBusy)
          return;
      runtime.roomBusy = true;
      const generation = ++runtime.transitionGeneration;
      const key = runtime.activeRoomKey;
      const phaserGame = runtime.sceneRef.sys.game;
      const roomScene = phaserGame.scene.getScene(key);
      const finish = () => {
          if (generation !== runtime.transitionGeneration)
              return;
          phaserGame.scene.stop(key);
          runtime.activeRoomKey = null;
          if (runtime.academyEntered)
              runtime.handlers.onAcademyChange?.(false);
          runtime.academyEntered = false;
          runtime.roomBusy = false;
          if (runtime.sceneRef) {
              phaserGame.scene.wake('town');
              runtime.restoreStreet(runtime.exteriorReturn);
              runtime.sceneRef.cameras.main.fadeIn(SCENE_FADE_MS, 8, 10, 8);
          }
          runtime.handlers.onRoomChange?.(null);
          if (runtime.queuedDestination && runtime.sceneRef) {
              const place = runtime.queuedDestination;
              runtime.queuedDestination = null;
              runtime.sceneRef.travelToPlace(place);
          }
      };
      const cam = roomScene?.cameras.main;
      if (cam) {
          cam.fadeEffect.start(true, SCENE_FADE_MS, 8, 10, 8, true);
          cam.once('camerafadeoutcomplete', finish);
      }
      else {
          finish();
      }
  }
  
  function exitAcademy() {
      if (runtime.academyEntered)
          runtime.exitRoom();
  }
  return { enterAcademy, enterRoom, restoreStreet, recover, exitRoom, exitAcademy }
}
