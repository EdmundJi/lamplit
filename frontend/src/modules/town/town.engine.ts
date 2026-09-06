/** Public lazy-loading boundary for the town renderer. Scene responsibilities live in engine/. */
import type { TownModel } from './town.types'
import type { RoomController } from './interior.scene'
import type { TownGame, TownHandlers } from './engine/shared'
import { computeServerOffset } from './engine/shared'
import { installTownProbe } from './town-probe'
import { weatherForDate } from './world-life'
import { TownRuntime } from './engine/runtime'
import { createTownScene, type TownSceneInstance } from './engine/scene-core'
import { createRoomTransitions } from './engine/rooms'

export type { TownSelection, TownTravel, TownNearby, TownHandlers, TownGame, NpcFrame } from './engine/shared'
export { resolveNpcFrame, minuteOfLocalDay, isInteriorPetSpecies, plotX } from './engine/shared'

export async function createTownGame(container: HTMLElement, model: TownModel, handlers: TownHandlers = {}): Promise<TownGame> {
  const Phaser = (await import('phaser')).default
  if (import.meta.env.DEV) installTownProbe()
  const runtime = new TownRuntime(Phaser, container, model, handlers)
  Object.assign(runtime, createRoomTransitions(runtime))
  const TownScene = createTownScene(runtime)
  runtime.game = new runtime.Phaser.Game({
      type: runtime.Phaser.AUTO,
      parent: runtime.container,
      width: runtime.container.clientWidth || 960,
      height: runtime.container.clientHeight || 600,
      pixelArt: true,
      roundPixels: false,
      backgroundColor: '#78a95f',
      scene: [TownScene],
      scale: { mode: runtime.Phaser.Scale.RESIZE, autoCenter: runtime.Phaser.Scale.NO_CENTER },
      audio: { noAudio: true },
  });
  // Flush presence when page becomes hidden
  const visibilityHandler = () => {
      if (document.hidden)
          runtime.presenceReporter.flush();
      runtime.sceneRef?.atmosphere?.setVisible(!document.hidden);
      runtime.sceneRef?.facilities?.setMuted(!runtime.soundEnabled || document.hidden || Boolean(runtime.activeRoomKey));
      runtime.soundscape.setVisible(!document.hidden);
  };
  document.addEventListener('visibilitychange', visibilityHandler);
  runtime.soundscape.setVisible(!document.hidden);
  return {
      setLetterUnread: count => { runtime.letterUnread = Math.max(0, count); runtime.sceneRef?.applyLetterUnread(); },
      setNight: night => { runtime.desiredNight = night; runtime.sceneRef?.setNight(night); runtime.soundscape.refresh(); },
      setAutomaticTime: () => { runtime.desiredNight = null; runtime.publishTime(); runtime.soundscape.refresh(); },
      setSoundEnabled: enabled => {
          runtime.soundEnabled = enabled;
          runtime.soundscape.setEnabled(enabled);
          runtime.sceneRef?.facilities?.setMuted(!enabled || document.hidden || Boolean(runtime.activeRoomKey));
          if (enabled && !document.hidden && !runtime.activeRoomKey) runtime.sceneRef?.facilities?.unlockAudio();
      },
      setRun: running => { runtime.desiredRun = running; runtime.sceneRef?.setRunMode(running); },
      focus: publicId => runtime.sceneRef?.focusOn(publicId),
      travelTo: place => runtime.sceneRef?.travelToPlace(place),
      cancelTravel: () => runtime.sceneRef?.cancelTravel(),
      interactNearby: () => runtime.sceneRef?.interactNearby(),
      recover: runtime.recover,
      beginConversation: code => { runtime.requestedConversation = code; return runtime.sceneRef?.beginConversation(code) ?? false; },
      endConversation: code => { if (!code || runtime.requestedConversation === code)
          runtime.requestedConversation = null; runtime.sceneRef?.endConversation(code); },
      interruptConversation: (code, reason) => runtime.sceneRef?.interruptConversation(code, reason),
      applyModel: nextModel => {
          runtime.latestModel = nextModel;
          runtime.serverOffsetMs = computeServerOffset(nextModel.serverTime);
          runtime.soundscape.refresh();
          runtime.sceneRef?.atmosphere?.setWeather(weatherForDate(nextModel.localDate));
          runtime.sceneRef?.applyResidents(nextModel.residents);
      },
      celebrate: publicId => { runtime.celebrationQueue.push(publicId); runtime.runCelebrationQueue(); },
      applyNpcs: (npcs, budget) => {
          runtime.townNpcRoster = npcs;
          runtime.initiativeBudget = budget;
          runtime.sceneRef?.applyTownNpcs(npcs);
      },
      setObservation: on => runtime.sceneRef?.setObservationMode(on),
      setScenic: on => { runtime.scenicMode = on; runtime.sceneRef?.setScenicMode(on); },
      applyEvents: events => { runtime.townEvents = events; runtime.sceneRef?.drawTownEvents(); },
      setInitiativeBudget: budget => { runtime.initiativeBudget = budget; },
      enterAcademy: runtime.enterAcademy,
      exitAcademy: runtime.exitAcademy,
      enterRoom: runtime.enterRoom,
      exitRoom: runtime.exitRoom,
      cancelRoomAction: () => {
          if (!runtime.activeRoomKey) return false;
          const room = runtime.game.scene.getScene(runtime.activeRoomKey) as { controller?: RoomController | null } | null;
          return room?.controller?.cancel?.() ?? false;
      },
      destroy: () => {
          runtime.transitionGeneration++;
          runtime.roomRequest?.abort();
          runtime.presenceReporter.clear();
          runtime.soundscape.destroy();
          document.removeEventListener('visibilitychange', visibilityHandler);
          runtime.sceneRef?.atmosphere?.destroy();
          const debugWindow = window as unknown as { __townScene?: TownSceneInstance };
          if (import.meta.env.DEV && debugWindow.__townScene === runtime.sceneRef)
              delete debugWindow.__townScene;
          runtime.sceneRef = null;
          runtime.celebrationQueue = [];
          runtime.game.destroy(true);
      },
  };
}
