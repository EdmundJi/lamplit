# Rive pet assets

These files are licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
Keep the creator attribution visible in product credits or the open-source notices page.

Every species has multiple interchangeable variants (3-5). The partner page picks a
variant by breed (see `frontend/src/modules/partners/pet-variants.ts`) and lets the
user cycle through the rest. Variant choice is remembered per pet in `localStorage`
(`better-self:pet-variant:{petPublicId}`).

| File | Marketplace source | Creator | Runtime notes |
| --- | --- | --- | --- |
| `cat-play-time.riv` | [Play Time](https://rive.app/marketplace/26773-50283-play-time/) | rkoomera | Artboard `404_artboard`, state machine `cat_SM`; autonomous play scene, `arboard click` input |
| `cat-feed-the-cat.riv` | [Feed the cat](https://rive.app/community/26443-49531-feed-the-cat/) | Jurgen_the_animator | Artboard `Cat_1`, state machine `State Machine 1`; interactive feeding buttons |
| `cat-nera.riv` | [Nera the Cat](https://rive.app/community/18095-33943-nera-the-cat/) | Chloe_Cristina | Artboard `Artboard`, state machine `Cat Machine`; laser-pointer following, ear/body clicks |
| `cat-sleepy.riv` | [Sleepy Cat - Falling Asleep Loop](https://rive.app/community/25457-47508-sleepy-cat-falling-asleep-loop/) | metamom_mama | Artboard `Artboard`, state machine `cat_controller`; `tap_wake` trigger |
| `cat-bento.riv` | [Bento Cat](https://rive.app/community/21131-39716-bento-cat/) | mikewirsch | Artboard `Bento`, state machine `State Machine 1`; nap-finding play scene |
| `dog-interactive.riv` | [Interactive Dog](https://rive.app/marketplace/7994-15377-interactive-dog/) | jouri | Artboard `dog-walk-cycle`, state machine `State Machine 1`; boolean inputs `Hit Tong`, `Hit Staart`, `Hit Oor` |
| `dog-happy.riv` | [happy dog](https://rive.app/community/17040-32005-happy-dog/) | silviaporcu.po | Artboard `Artboard`, state machine `State Machine 1`; eye-following |
| `dog-walking-cycle.riv` | [Walking cycle Dog](https://rive.app/community/17756-33306-walking-cycle-dog/) | jiska.heringa | Artboard `Dog walking sliders`, state machine `State Machine 1`; walk cycle, hit detection |
| `dog-sausage.riv` | [Sausage Dog](https://rive.app/community/16950-31846-sausage-dog/) | amcdowell100 | Artboard `Sausage dog 2`, state machine `State Machine 1`; `isHover` boolean |
| `hamster-idle-jump.riv` | [Hamster](https://rive.app/marketplace/15474-29211-hamster/) | ersanakpinarr | Artboard `HasmterNested`, state machine `State Machine 1`; idle and jump animation |
| `hamster-rat-bonk.riv` | [Rat Bonk](https://rive.app/community/23296-50147-rat-bonk/) | duhkukx | Artboard `Artboard`, state machine `State Machine 1`; `hit.trigger` bonk |
| `hamster-walking-mouse.riv` | [Walking Mouse](https://rive.app/community/20440-38443-walking-mouse/) | hidysaam | Artboard `Artboard`, state machine `State Machine 1`; follow-path walk, `isBig` boolean |
| `snake-hungry.riv` | [Hungry Snake](https://rive.app/marketplace/24964-46590-hungry-snake/) | RyanRumbolt | Artboard `Artboard`, state machine `State Machine 1`; idle feeding animation |
| `rabbit-interactive.riv` | [Animated Login Bunny Character](https://rive.app/marketplace/8314-15930-animated-login-bunny-character/) | trong.phanduc34 | Artboard `Login`, state machine `State Machine 1`; focus, success, failure and eye tracking inputs |
| `rabbit-cute-bunny.riv` | [Cute Bunny Interactive Character](https://rive.app/community/25456-47507-cute-bunny-interactive-character/) | metamom_mama | Artboard `Artboard`, state machine `State Machine 1`; idle/hover/pressed/happy, `onClick` |
| `rabbit-button.riv` | [Button Rabbit Animation](https://rive.app/community/20909-39303-button-rabbit-animation/) | zee31wizard | Artboard `Rabbit Button`, state machine `State Machine 1`; triggers `Pressed`, boolean `Hover` |
| `rabbit-bunny-run.riv` | [Bunny Run Game](https://rive.app/community/15831-29829-bunny-run-game/) | mixhead | Artboard `BunnyRun!!!`, state machine `StateMachine`; game with `triggerStart`, `triggerSplit`, `isHover`, `Fail` |
| `bird-interactive.riv` | [Bird](https://rive.app/marketplace/9049-17337-bird/) | ElmerVergara | Artboard `Bird`, state machine `State Machine 1`; pointer-following flight and `direction` input |
| `bird-flying-set.riv` | [Flying Character Set – Looping Pet Animation](https://rive.app/community/20722-41488-flying-character-set-looping-pet-animation/) | HaiDo | Artboard `main`, state machine `State Machine 1`; multi-character flying pet pack |
| `bird-pigeon.riv` | [pigeon](https://rive.app/community/22575-42238-pigeon/) | yuva_m | Artboard `Artboard`, state machine `State Machine 1`; cursor-tracking pigeon |
| `bird-owl-mascot.riv` | [Owl Mascot Animation](https://rive.app/community/25550-47706-owl-mascot-animation/) | AnggaMotion | Artboard `Artboard`, state machine `State Machine 1`; owl mascot |
| `bird-ducky.riv` | [Jumpy Walky Ducky](https://rive.app/community/24710-46183-jumpy-walky-ducky/) | leule-4WCG8 | Artboard `Ducky`, state machine `Duck `; walk/jump duck |
| `turtle-angry.riv` | [Angry Turtle](https://rive.app/marketplace/13427-25580-angry-turtle/) | extraframe28 | Artboard `Artboard`, state machine `State Machine 1`; triggers `hit trigger`, `click trigger` and booleans `stand up/down`, `body hover` |
| `fox-idle.riv` | [fox](https://rive.app/marketplace/11162-21377-fox/) | sandeep.k | Artboard `fox`, state machine `State Machine 1`; autonomous idle animation |
| `fox-in-the-hole.riv` | [Fox In The Hole!](https://rive.app/community/27220-51408-fox-in-the-hole/) | lwhitcom | Artboard `Artboard`, state machine `State Machine 1`; click interaction |
| `fox-walk-cycle.riv` | [Fox walk cycle](https://rive.app/community/11163-21380-fox-walk-cycle/) | Sandyuiuxstudio | Artboard `Artboard 2`, state machine `State Machine 1`; walk cycle loop |

Static fallbacks (`frontend/public/assets/pets/snake-cartoon.svg`,
`frontend/public/assets/pets/turtle-cartoon.svg`) are Twemoji assets licensed under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
