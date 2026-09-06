import type PhaserNs from 'phaser'
import { drawNeighbourhood, neighbourhoodFurniture } from './neighbourhood'
import { inNeighbourhoodPath, NEIGHBOURHOOD_PATHS, NEIGHBOURHOOD_OBSTACLES } from '../neighbourhood-layout'
import { buildBlueprint, hashString } from '../building-kit'
import { buildTownCollisionWorld } from '../collision'
import { eventTitle } from '../town-events'
import type { TownResident } from '../town.types'
import { createGymFurniture, createParkFurniture, createStreetFurniture, createGroundDetails } from '../town-furniture'
import type { FurnitureItem } from '../town-furniture'
import { TILE, BASELINE_ROW, BASELINE, ROWS, WORLD_HEIGHT, YARD_X, ACADEMY_X, SCHOOL_WIDTH, PLOT_START, PLOT_WIDTH, PLOT_PITCH, STREET_Y, FONT, WALK_APRON, WALK_BOTTOM, PLAZA_TOP, EMOTES, plotX, plotSignatureFor } from './shared'
import type { TownSelection } from './shared'
import type { TownSceneInstance as TownScene } from './scene-core'

export const worldMethods = {
  groundFrame(this: TownScene, column: number, row: number, crossingStart: number) {
      const runtime = this.runtime;
      const wx = column * TILE + TILE / 2, wy = row * TILE + TILE / 2;
      if (inNeighbourhoodPath(wx, wy)) return `sidewalk_${25 + ((column + row) % 4)}`;
      if (runtime.garden.walkable.some(r => wx >= r.x && wx <= r.x + r.width && wy >= r.y && wy <= r.y + r.height))
          return `sidewalk_${25 + ((column + row) % 4)}`;
      const noise = hashString(`${column}:${row}`);
      if (row >= BASELINE_ROW + 2 && row <= BASELINE_ROW + 6 && column >= crossingStart && column < crossingStart + 4)
          return 'sidewalk_34';
      if (row === BASELINE_ROW || row === BASELINE_ROW + 1)
          return `sidewalk_${25 + (noise % 4)}`;
      if (row === BASELINE_ROW + 2)
          return 'sidewalk_6';
      if (row >= BASELINE_ROW + 3 && row <= BASELINE_ROW + 5)
          return row === BASELINE_ROW + 4 && column % 2 === 0 ? 'asphalt_14' : `asphalt_${24 + (noise % 4)}`;
      if (row === BASELINE_ROW + 6)
          return 'sidewalk_2';
      if (row === BASELINE_ROW + 7 || row === BASELINE_ROW + 8)
          return `sidewalk_${25 + (noise % 4)}`;
      if (noise % 7 === 0)
          return `grass_${9 + (noise % 5)}`;
      return 'grass_22';
  },

  drawGround(this: TownScene) {
      const runtime = this.runtime;
      const chunkColumns = 32;
      const columns = Math.ceil(runtime.width / TILE);
      const crossingStart = Math.floor(runtime.academyDoorX / TILE) - 2;
      for (let start = 0; start < columns; start += chunkColumns) {
          const end = Math.min(columns, start + chunkColumns + 1);
          const chunk = this.add.renderTexture(start * TILE, 0, (end - start) * TILE, WORLD_HEIGHT).setOrigin(0).setDepth(0);
          chunk.beginDraw();
          for (let row = 0; row < ROWS; row += 1) {
              for (let column = start; column < end; column += 1) {
                  chunk.batchDrawFrame('town', this.groundFrame(column, row, crossingStart), (column - start) * TILE, row * TILE);
              }
          }
          chunk.endDraw();
      }
  },

  drawBackdrop(this: TownScene) {
      const runtime = this.runtime;
      for (let x = 24; x < runtime.width - 64; x += 72) {
          const seed = hashString(`backdrop:${x}`);
          const y = 96 + (seed % 5) * 22;
          const pick = seed % 7;
          if (pick < 3)
              this.add.image(x, y, 'town', `tree_${1 + (seed % 6)}`).setOrigin(0, 1).setDepth(1);
          else if (pick === 3)
              this.add.image(x, y, 'town', `bush_${1 + (seed % 4)}`).setOrigin(0, 1).setDepth(1);
          else if (pick === 4)
              this.add.image(x, y + 40, 'town', `flowers_${1 + (seed % 5)}`).setOrigin(0, 1).setDepth(1);
      }
      for (let x = 60; x < runtime.width - 64; x += 150) {
          const seed = hashString(`meadow:${x}`);
          const y = 230 + (seed % 4) * 30;
          if (seed % 3 === 0)
              this.add.image(x, y, 'town', `flowerbush_${1 + (seed % 6)}`).setOrigin(0, 1).setDepth(1);
          else if (seed % 3 === 1)
              this.add.image(x, y, 'town', `flowers_${1 + (seed % 5)}`).setOrigin(0, 1).setDepth(1);
      }
  },

  drawAcademy(this: TownScene) {
      const runtime = this.runtime;
      this.entrances.set('academy', { x: runtime.academyDoorX, y: BASELINE + 12 });
      const courtBottom = BASELINE - 24;
      this.add.image(YARD_X, courtBottom, 'town', 'court_3').setOrigin(0, 1).setDepth(1);
      this.add.image(YARD_X + 8, courtBottom - 352, 'town', 'basketnet_1').setOrigin(0, 1).setDepth(courtBottom - 352);
      this.add.image(YARD_X + 470, courtBottom - 40, 'town', 'stadiumlight_1').setOrigin(0, 1).setDepth(courtBottom - 40);
      this.add.image(YARD_X + 120, courtBottom - 360, 'town', 'yardtoy_12').setOrigin(0, 1).setDepth(courtBottom - 360);
      this.add.image(YARD_X + 300, courtBottom - 400, 'town', 'yardtoy_15').setOrigin(0, 1).setDepth(courtBottom - 400);
      this.add.image(YARD_X + 250, courtBottom - 80, 'town', 'soccerball_1').setOrigin(0, 1).setDepth(courtBottom - 80);
      this.add.image(ACADEMY_X, BASELINE, 'town', 'school_1').setOrigin(0, 1).setDepth(BASELINE - 3);
      this.add.image(ACADEMY_X - 120, BASELINE, 'town', 'schoolflag_1').setOrigin(0, 1).setDepth(BASELINE - 2);
      this.add.image(ACADEMY_X + SCHOOL_WIDTH + 24, BASELINE, 'town', 'tree_5').setOrigin(0, 1).setDepth(BASELINE - 2);
      this.add.image(ACADEMY_X + SCHOOL_WIDTH + 90, BASELINE, 'town', 'tree_3').setOrigin(0, 1).setDepth(BASELINE - 2);
      this.plate(runtime.academyDoorX, BASELINE - 750, '成长学院', '#fff4e8', '#35644f');
      this.hitZone(ACADEMY_X, BASELINE - 740, SCHOOL_WIDTH, 740, 'academy');
      this.glow(runtime.academyDoorX, BASELINE - 90, 520, 300);
  },

  stackPiece(this: TownScene, x: number, bottom: number, frame: string) {
      const runtime = this.runtime;
      const image = this.add.image(x, bottom, 'town', frame).setOrigin(0, 1).setDepth(BASELINE - 3);
      // Modular shop signs extend 64px ABOVE the wall seam; they overlap the next floor.
      const signOverhang = /^(gym|bakery|music|icecream)_/.test(frame) ? 64 : 0;
      if (signOverhang)
          image.setDepth(BASELINE - 2);
      return { image, top: bottom - image.height + signOverhang };
  },

  drawPlot(this: TownScene, resident: TownResident, index: number) {
      const runtime = this.runtime;
      const x = plotX(index);
      const blueprint = buildBlueprint(resident);
      const objects: PhaserNs.GameObjects.GameObject[] = [];
      let cursor = BASELINE;
      const ground = this.stackPiece(x, cursor, blueprint.ground);
      objects.push(ground.image);
      cursor = ground.top;
      for (const middle of blueprint.middles) {
          const piece = this.stackPiece(x, cursor, middle);
          objects.push(piece.image);
          cursor = piece.top;
      }
      const roof = this.stackPiece(x, cursor, blueprint.roof);
      objects.push(roof.image);
      const roofSurface = roof.top + roof.image.height - 44;
      blueprint.roofProps.forEach((prop, propIndex) => {
          const image = this.add.image(x + 20 + propIndex * 64, roofSurface - propIndex * 6, 'town', prop).setOrigin(0.5, 1).setDepth(BASELINE - 2);
          if (image.width > PLOT_WIDTH - 40)
              image.setX(x + 12);
          objects.push(image);
      });
      const top = roof.top;
      this.roofTop.set(resident.publicId, { x: x + PLOT_WIDTH / 2, y: top });
      const seed = hashString(`${resident.publicId}:props`);
      objects.push(this.add.image(x + PLOT_WIDTH + 12, BASELINE, 'town', `tree_${1 + (seed % 8)}`).setOrigin(0, 1).setDepth(BASELINE - 2));
      const title = resident.isSelf ? `${resident.displayName}（我）` : resident.displayName;
      const subtitle = resident.title ? ` · ${resident.title}` : '';
      objects.push(this.plate(x + PLOT_WIDTH / 2, top - 14, `${title} · LV.${resident.level}${subtitle}`, resident.isSelf ? '#fff4e8' : '#3b312c', resident.isSelf ? '#c85f47' : '#fffdfa'));
      this.buildingCenters.set(resident.publicId, { x: x + PLOT_WIDTH / 2, y: (top + BASELINE) / 2 });
      if (resident.isSelf) {
          this.entrances.set('home', { x: x + PLOT_WIDTH / 2, y: BASELINE + 12 });
          // M3-1: 自己的房子多一扇"门"——楼上照旧点开信息卡，楼下这一小条改成"走过去敲门进屋"。
          objects.push(...this.drawHomeDoor(x, top, resident.publicId));
      }
      else {
          objects.push(this.hitZone(x, top, PLOT_WIDTH, BASELINE - top, resident.publicId));
      }
      if (blueprint.open) {
          objects.push(this.glow(x + PLOT_WIDTH / 2, BASELINE - 60, PLOT_WIDTH + 120, 220));
      }
      this.plotObjects.set(resident.publicId, objects);
      this.plotSignature.set(resident.publicId, plotSignatureFor(resident));
  },

  refreshPlot(this: TownScene, resident: TownResident, index: number) {
      const runtime = this.runtime;
      const signature = plotSignatureFor(resident);
      if (this.plotSignature.get(resident.publicId) === signature)
          return;
      const previous = this.plotObjects.get(resident.publicId);
      if (previous) {
          this.glows = this.glows.filter(glow => !previous.includes(glow));
          for (const object of previous)
              object.destroy();
      }
      this.drawPlot(resident, index);
      this.buildCollisionWorld(); // the plot's roof (and so its footprint height) may have changed
  },

  buildCollisionWorld(this: TownScene) {
      const runtime = this.runtime;
      const buildings = [{ x: ACADEMY_X, width: SCHOOL_WIDTH, topY: BASELINE - 740 }];
      runtime.residents.forEach((resident, index) => {
          const roof = this.roofTop.get(resident.publicId);
          buildings.push({ x: plotX(index), width: PLOT_WIDTH, topY: roof ? roof.y : BASELINE - 200 });
      });
      buildings.push({ x: runtime.gymPlotX, width: PLOT_WIDTH, topY: this.publicRoofs.get('gym') ?? BASELINE - 288 }, { x: runtime.cafePlotX, width: PLOT_WIDTH, topY: this.publicRoofs.get('cafe') ?? BASELINE - 288 });
      this.collisionWorld = buildTownCollisionWorld({
          groundMinX: runtime.groundMinX,
          groundMaxX: runtime.groundMaxX,
          baselineY: BASELINE,
          apron: WALK_APRON,
          bottomY: WALK_BOTTOM,
          plaza: { minX: YARD_X, maxX: runtime.academyDoorX + 300, topY: PLAZA_TOP },
          buildings,
          furniture: this.furnitureCollisions,
      });
      this.collisionWorld.walkable.push(...runtime.garden.walkable, ...NEIGHBOURHOOD_PATHS);
      this.collisionWorld.obstacles.push(...NEIGHBOURHOOD_OBSTACLES);
  },

  drawPublicPlaces(this: TownScene) {
      const runtime = this.runtime;
      for (const [id, title, x, ground, roof] of [
          ['gym', '活力健身房', runtime.gymPlotX, 'gym_1', 'roof_1'],
          ['cafe', '街角咖啡馆', runtime.cafePlotX, 'bakery_5', 'roof_3'],
      ] as const) {
          const base = this.stackPiece(x, BASELINE, ground);
          // Keep the native roof edge pixels, but omit the oversized empty centre of the roof.
          // Public shops become shallower buildings while their frontage and door remain intact.
          const roofHeight = this.textures.getFrame('town', roof).height;
          const edge = Math.min(48, Math.floor(roofHeight / 2));
          this.add.image(x, base.top - edge * 2, 'town', roof).setOrigin(0).setCrop(0, 0, PLOT_WIDTH, edge).setDepth(BASELINE - 3);
          this.add.image(x, base.top - roofHeight, 'town', roof).setOrigin(0).setCrop(0, roofHeight - edge, PLOT_WIDTH, edge).setDepth(BASELINE - 3);
          const top = { top: base.top - edge * 2 };
          this.plate(x + PLOT_WIDTH / 2, top.top - 16, title, '#fff9ee', '#355b44');
          this.publicRoofs.set(id, top.top);
          this.entrances.set(id, { x: x + PLOT_WIDTH / 2, y: BASELINE + 12 });
          this.buildingCenters.set(id, { x: x + PLOT_WIDTH / 2, y: BASELINE - 80 });
          this.plate(x + PLOT_WIDTH / 2, BASELINE - 20, '点击进入 · E', '#fff9ee', '#355b44');
          this.hitZone(x, top.top, PLOT_WIDTH, BASELINE - top.top, id);
      }
      for (const [id, title, x] of [
          ['plaza', '日光广场', YARD_X + 220],
      ] as const) {
          this.plate(x, BASELINE - 110, title, '#fff9ee', '#355b44');
          this.buildingCenters.set(id, { x, y: STREET_Y });
          this.entrances.set(id, { x, y: STREET_Y });
          this.hitZone(x - 60, BASELINE - 110, 120, 36, id);
      }
  },

  drawGardenDistrict(this: TownScene) {
      const runtime = this.runtime;
      const { park, branchX } = runtime.garden;
      this.entrances.set('park', park);
      this.entrances.set('street', { x: branchX, y: STREET_Y });
      this.buildingCenters.set('park', park);
      this.plate(park.x, park.y - 112, '树荫公园 · 转角后的慢时光', '#fff9ee', '#355b44');
      this.hitZone(park.x - 110, park.y - 148, 220, 48, 'park');
      this.plate(branchX, BASELINE - 28, '↑ 树荫公园', '#fff9ee', '#355b44');
      this.hitZone(branchX - 70, BASELINE - 70, 140, 60, 'park');
      for (let x = branchX - 470; x <= branchX + 50; x += 104) {
          this.add.image(x, 350, 'town', `tree_${1 + (hashString(String(x)) % 5)}`).setOrigin(0.5, 1).setDepth(350);
          this.add.image(x, 590, 'town', 'flowerbush_2').setOrigin(0.5, 1).setDepth(590);
      }
      this.add.image(park.x - 175, park.y + 28, 'town', 'fountain_1').setOrigin(0.5, 1).setDepth(park.y + 28);
      this.add.sprite(park.x + 80, park.y + 14, 'town', 'pigeon_1').setOrigin(0.5, 1).setDepth(park.y + 14).play('pigeon-idle');
      drawNeighbourhood(this);
  },

  drawTownEvents(this: TownScene) {
      const runtime = this.runtime;
      const now = Date.now() + runtime.serverOffsetMs;
      const active = runtime.townEvents.filter(e => now >= Date.parse(e.startsAt) - 30 * 60000 && now < Date.parse(e.endsAt ?? e.startsAt) + 30 * 60000);
      const signature = active.map(e => e.publicId).join(',');
      if (signature === this.eventSignature)
          return;
      this.eventSignature = signature;
      this.eventDecor.forEach(obj => obj.destroy());
      this.eventDecor = [];
      for (const event of active) {
          const center = this.entrances.get(event.venue);
          if (!center)
              continue;
          const decorations: PhaserNs.GameObjects.GameObject[] = [];
          const string = this.add.graphics().lineStyle(2, 0x665d43, 1).lineBetween(center.x - 120, center.y - 115, center.x + 120, center.y - 115).setDepth(2000);
          for (let i = 0; i < 9; i++)
              string.fillStyle(i % 2 ? 0xf4b46a : 0xe9dda1, 1).fillCircle(center.x - 112 + i * 28, center.y - 110, 4);
          decorations.push(string, this.plate(center.x, center.y - 132, `${event.hostName} · ${eventTitle(event.kind)}`, '#fff9ee', '#7a5640'));
          decorations.push(this.add.image(center.x + 120, center.y + 40, 'town', 'foodcart_1').setOrigin(0, 1).setDepth(center.y + 40));
          this.eventDecor.push(...decorations);
      }
  },

  drawStreetFurniture(this: TownScene) {
      const runtime = this.runtime;
      // 公交站牌保留
      this.add.image(PLOT_START - 200, BASELINE + 60, 'town', 'busstop_1').setOrigin(0, 1).setDepth(BASELINE + 60);
      this.add.image(PLOT_START - 30, BASELINE + 60, 'town', 'busstopsign_1').setOrigin(0, 1).setDepth(BASELINE + 60);
      // 路灯（保留原有逻辑但密度优化）
      for (let x = ACADEMY_X - 160; x < runtime.width - 96; x += PLOT_PITCH) {
          this.add.image(x, BASELINE + 62, 'town', 'lamp_5').setOrigin(0, 1).setDepth(BASELINE + 62);
      }
      // 场地配套家具：健身房（假设在第一个商店位置）、咖啡馆（第二个）、公园入口（学院前）
      const gymX = runtime.gymPlotX;
      const cafeX = runtime.cafePlotX;
      const parkX = runtime.garden.park.x;
      this.venueFurniture = [
          createGymFurniture(gymX + PLOT_WIDTH / 2, BASELINE),
          createParkFurniture(parkX, runtime.garden.park.y),
      ];
      // 街道家具（密集布置）
      this.streetFurnitureItems = [...createStreetFurniture(ACADEMY_X, runtime.width - 200, BASELINE, 400), ...neighbourhoodFurniture()];
      // 绘制所有场地家具
      this.venueFurniture.forEach(venue => {
          venue.items.forEach(item => this.drawFurnitureItem(item));
      });
      // 绘制所有街道家具
      this.streetFurnitureItems.forEach(item => this.drawFurnitureItem(item));
      // 地面细节层
      const groundDetails = createGroundDetails(runtime.groundMinX, runtime.groundMaxX, BASELINE, STREET_Y);
      groundDetails.forEach(detail => {
          if (runtime.inTerrace(detail.x, detail.y, 40))
              return;
          this.add.image(detail.x, detail.y, 'town', detail.frame).setOrigin(0.5, 0.5).setDepth(detail.depth);
      });
  },

  drawFurnitureItem(this: TownScene, item: FurnitureItem) {
      const runtime = this.runtime;
      if (runtime.inTerrace(item.x, item.y, 60))
          return;
      const depth = item.depth ?? item.y;
      const sprite = this.add.image(item.x, item.y, 'town', item.frame).setOrigin(0, 1).setDepth(depth);
      // 添加碰撞
      if (item.collision) {
          this.furnitureCollisions.push({
              x: item.x + item.collision.offsetX,
              y: item.y - item.collision.height,
              width: item.collision.width,
              height: item.collision.height,
          });
      }
      // 可交互家具：添加交互区域
      if (item.interactive && item.interactionType) {
          const hitArea = this.add.zone(item.x, item.y, sprite.width, sprite.height).setOrigin(0, 1).setInteractive({ useHandCursor: true });
          hitArea.on('pointerup', (pointer: PhaserNs.Input.Pointer) => { if (runtime.isWorldPointer(pointer) && this.dragStart && !this.dragged)
              this.onFurnitureInteract(item); });
      }
  },

  drawParkStrip(this: TownScene) {
      const runtime = this.runtime;
      const y = (BASELINE_ROW + 9) * TILE;
      for (let x = 40; x < runtime.width - 80; x += 96) {
          if (runtime.inTerrace(x, y, 80) || inNeighbourhoodPath(x + 24, y))
              continue;
          const seed = hashString(`park:${x}`);
          const pick = seed % 6;
          if (pick === 0)
              this.add.image(x, y, 'town', `tree_${1 + (seed % 8)}`).setOrigin(0, 1).setDepth(y);
          else if (pick === 1)
              this.add.image(x, y - 16, 'town', `flowerbush_${1 + (seed % 6)}`).setOrigin(0, 1).setDepth(y - 16);
          else if (pick === 2)
              this.add.image(x, y - 20, 'town', `bush_${1 + (seed % 4)}`).setOrigin(0, 1).setDepth(y - 20);
          else if (pick === 3)
              this.add.image(x, y - 12, 'town', `flowers_${1 + (seed % 5)}`).setOrigin(0, 1).setDepth(y - 12);
          else if (pick === 4)
              this.add.image(x, y - 8, 'town', 'gardenbench_1').setOrigin(0, 1).setDepth(y - 8);
      }
      const plaza = (BASELINE_ROW + 8) * TILE + 20;
      this.add.image(runtime.academyDoorX - 32, plaza, 'town', 'fountain_1').setOrigin(0, 1).setDepth(plaza);
      this.add.image(runtime.academyDoorX - 260, plaza, 'town', 'foodcart_1').setOrigin(0, 1).setDepth(plaza);
      this.add.image(runtime.academyDoorX + 180, plaza, 'town', 'flowercart_1').setOrigin(0, 1).setDepth(plaza);
      this.add.image(runtime.academyDoorX + 330, plaza, 'town', 'hotdogcart_1').setOrigin(0, 1).setDepth(plaza);
  },

  drawWildlife(this: TownScene) {
      const runtime = this.runtime;
      this.add.sprite(ACADEMY_X + 120, BASELINE - 700, 'town', 'crow_1').setOrigin(0, 1).setDepth(BASELINE).play('crow-idle');
      for (let index = 0; index < runtime.residents.length; index += 2) {
          const x = plotX(index) + 160;
          this.add.sprite(x, (BASELINE_ROW + 8) * TILE, 'town', 'pigeon_1').setOrigin(0, 1).setDepth(1).play('pigeon-idle');
      }
      this.add.sprite(runtime.academyDoorX + 80, (BASELINE_ROW + 8) * TILE + 8, 'town', 'pigeon_1').setOrigin(0, 1).setDepth(2).play('pigeon-idle');
  },

  spawnVehicles(this: TownScene) {
      const runtime = this.runtime;
      const upperLane = (BASELINE_ROW + 4) * TILE + 4;
      const lowerLane = (BASELINE_ROW + 6) * TILE - 2;
      const cars = Math.max(3, Math.min(7, Math.floor(runtime.width / 700)));
      for (let index = 0; index < cars; index += 1) {
          const seed = hashString(`car:${index}`);
          const goesRight = index % 2 === 0;
          const frame = seed % 9 === 0 ? (goesRight ? 'busright_1' : 'busleft_1') : `${goesRight ? 'carright' : 'carleft'}_${1 + (seed % 8)}`;
          const y = goesRight ? lowerLane : upperLane;
          const sprite = this.add.image((seed % Math.max(300, runtime.terraceBounds.x - 200)), y, 'town', frame).setOrigin(0, 1).setDepth(y);
          this.vehicles.push({ sprite, speed: (goesRight ? 1 : -1) * (85 + (seed % 60)) });
      }
  },

  createGlowTexture(this: TownScene) {
      const runtime = this.runtime;
      if (this.textures.exists('glow'))
          return;
      const size = 256;
      const canvas = this.textures.createCanvas('glow', size, size);
      if (!canvas)
          return;
      const context = canvas.getContext();
      const gradient = context.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2);
      gradient.addColorStop(0, 'rgba(255, 216, 150, 0.85)');
      gradient.addColorStop(0.45, 'rgba(255, 200, 120, 0.32)');
      gradient.addColorStop(1, 'rgba(255, 190, 100, 0)');
      context.fillStyle = gradient;
      context.fillRect(0, 0, size, size);
      canvas.refresh();
  },

  createSparkTexture(this: TownScene) {
      const runtime = this.runtime;
      if (this.textures.exists('spark'))
          return;
      const size = 8;
      const canvas = this.textures.createCanvas('spark', size, size);
      if (!canvas)
          return;
      const context = canvas.getContext();
      context.fillStyle = '#ffd27a';
      context.beginPath();
      context.arc(size / 2, size / 2, size / 2, 0, Math.PI * 2);
      context.fill();
      canvas.refresh();
  },

  glow(this: TownScene, x: number, y: number, w: number, h: number, alpha = 1) {
      const runtime = this.runtime;
      const light = this.add.image(x, y, 'glow').setDisplaySize(w, h).setBlendMode(runtime.Phaser.BlendModes.ADD).setDepth(5001).setAlpha(0);
      light.setData('targetAlpha', alpha);
      this.glows.push(light);
      return light;
  },

  plate(this: TownScene, x: number, y: number, text: string, color: string, background: string) {
      const runtime = this.runtime;
      return this.add.text(x, y, text, {
          fontFamily: FONT, fontSize: '13px', color, backgroundColor: background, padding: { x: 7, y: 3 },
      }).setOrigin(0.5, 1).setDepth(4000).setResolution(2).setData('town-hud', true).setVisible(!runtime.scenicMode);
  },

  hitZone(this: TownScene, x: number, y: number, w: number, h: number, selection: TownSelection) {
      const runtime = this.runtime;
      const zone = this.add.zone(x, y, w, h).setOrigin(0).setInteractive({ useHandCursor: true });
      zone.on('pointerup', (pointer: PhaserNs.Input.Pointer) => {
          if (!runtime.isWorldPointer(pointer) || !this.dragStart)
              return;
          if (this.dragged)
              return;
          // The academy door walks the self avatar there first (task 6); building plots select immediately.
          if (selection === 'academy')
              this.travelToPlace('academy');
          else if (selection === this.selfWalker?.resident?.publicId)
              this.travelToPlace('home');
          else if (selection && ['gym', 'cafe', 'park', 'plaza'].includes(selection))
              this.travelToPlace(selection);
          else
              runtime.handlers.onSelect?.(selection);
      });
      return zone;
  },

  drawHomeDoor(this: TownScene, x: number, top: number, publicId: string): PhaserNs.GameObjects.GameObject[] {
      const runtime = this.runtime;
      const doorWidth = 64;
      const doorHeight = 44;
      const doorTop = BASELINE - doorHeight;
      const doorLeft = x + PLOT_WIDTH / 2 - doorWidth / 2;
      const objects: PhaserNs.GameObjects.GameObject[] = [];
      if (doorTop > top)
          objects.push(this.hitZone(x, top, PLOT_WIDTH, doorTop - top, publicId));
      const doorZone = this.add.zone(doorLeft, doorTop, doorWidth, doorHeight).setOrigin(0).setInteractive({ useHandCursor: true });
      doorZone.on('pointerup', (pointer: PhaserNs.Input.Pointer) => {
          if (!runtime.isWorldPointer(pointer) || !this.dragStart)
              return;
          if (this.dragged)
              return;
          this.travelToPlace('home');
      });
      objects.push(doorZone);
      return objects;
  },

  celebrateResident(this: TownScene, publicId: string) {
      const runtime = this.runtime;
      const center = this.buildingCenters.get(publicId);
      if (center) {
          this.releaseCameraFollow();
          this.cameras.main.pan(center.x, center.y + 40, 500, 'Sine.easeInOut');
      }
      const roof = this.roofTop.get(publicId);
      if (roof) {
          const prop = this.add.image(roof.x, roof.y - 260, 'town', 'roofprop_3').setOrigin(0.5, 1).setDepth(6000);
          this.tweens.add({
              targets: prop,
              y: roof.y - 4,
              duration: 650,
              ease: 'Bounce.easeOut',
              onComplete: () => { this.time.delayedCall(1800, () => prop.destroy()); },
          });
          this.spawnBurst(roof.x, roof.y - 30);
      }
      const walker = this.walkers.find(item => item.resident?.publicId === publicId);
      if (walker) {
          const bubble = this.add.sprite(walker.sprite.x, walker.sprite.y - 78, 'emotes', EMOTES.done[0])
              .setOrigin(0.5, 1).setDepth(4003).setAlpha(0).play('emote-done');
          this.tweens.add({
              targets: bubble,
              alpha: 1,
              y: walker.sprite.y - 92,
              duration: 220,
              onComplete: () => { this.time.delayedCall(1300, () => { this.tweens.add({ targets: bubble, alpha: 0, duration: 260, onComplete: () => bubble.destroy() }); }); },
          });
      }
  },

  spawnBurst(this: TownScene, x: number, y: number) {
      const runtime = this.runtime;
      const count = 12;
      for (let i = 0; i < count; i += 1) {
          const angle = (Math.PI * 2 * i) / count + Math.random() * 0.3;
          const speed = 50 + Math.random() * 60;
          const spark = i % 3 === 0
              ? this.add.image(x, y, 'town', 'flowers_1').setScale(0.5)
              : this.add.image(x, y, 'spark');
          spark.setDepth(6001);
          this.tweens.add({
              targets: spark,
              x: x + Math.cos(angle) * speed,
              y: y + Math.sin(angle) * speed - 30,
              alpha: 0,
              duration: 650 + Math.random() * 250,
              ease: 'Cubic.easeOut',
              onComplete: () => spark.destroy(),
          });
      }
  }
}
export type WorldMethods = typeof worldMethods
