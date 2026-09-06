/** Public lazy-loading boundary for the town renderer. Scene responsibilities live in engine/. */
import type { TownModel } from './town.types'
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
      runtime.sceneRef?.facilities?.setMuted(document.hidden || Boolean(runtime.activeRoomKey));
  };
  document.addEventListener('visibilitychange', visibilityHandler);
  return {
      setLetterUnread: count => { runtime.letterUnread = Math.max(0, count); runtime.sceneRef?.applyLetterUnread(); },
      setNight: night => { runtime.desiredNight = night; runtime.sceneRef?.setNight(night); },
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
      destroy: () => {
          runtime.transitionGeneration++;
          runtime.roomRequest?.abort();
          runtime.presenceReporter.clear();
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
