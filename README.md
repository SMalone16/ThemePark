# ThemePark — Eaglercraft Classroom Spawn Park

A **Paper 1.21.11** plugin designed for [SMalone16/Eaglercraft-1.21.11-Server](https://github.com/SMalone16/Eaglercraft-1.21.11-Server). This runs on the **Paper backend**, not Velocity. Java 21 and Maven are used to compile.

## First build

When selected in the classroom plugin menu for the first time, ThemePark builds a **70 × 70 block square centered on the Overworld spawn** (offsets -35..+34 on X and Z). It flattens the footprint to a common Y level, lays a solid stone floor, and adds dirt paths and a stone-brick spawn plaza. It then creates:

- **Lazy river:** a circular, two-block-deep, ~6-block-wide continuous water ring near the edge, with stone-brick borders and a gentle **clockwise swimmer current** (configurable).
- **Roller coaster:** elevated perimeter minecart rail loop with several hills, powered rails over redstone blocks, stone support columns, a three-wide staircase, and a raised boarding deck on the north side. Ride automatically with `/themepark ride` or place a test minecart manually.
- **Four test minecarts per player:** on join or respawn, the plugin tops up the player's inventory to four ordinary minecarts; repeated logins do not increase that count.
- **Protected park:** block breaking/placing, bucket changes, burning, freezing, fluid spread, explosions, entity block changes, and piston changes inside the footprint are blocked. Ordinary minecart interaction remains usable.
- **Carousel:** four rotating, player-mounted seats orbit a fixed point under a striped pavilion. Right-click an empty seat or the carousel floor to board; right-click again to exit. Sneak and `/themepark exit` are fallbacks for browser client interaction differences.
- **Nightmare survival:** daytime villagers and protective iron golems inhabit the park. At Minecraft time **13000**, villagers disappear, a limited mix of zombies/skeletons/witches spawns, and park iron golems pursue park visitors. A red boss bar and action bar display **remaining enemies and time until dawn**. At **23000**, hostile mobs disappear and guardians become friendly again. Only entities tagged by this plugin are modified.
- **Four temporary hideouts:** five-block-wide wooden shelters have closeable fence gates and can shield visitors during the night. If enemies reach an occupied shelter, its wood can be broken away. Cover is restored every dawn.
- **Night wall:** a six-block-high ring of invisible barrier blocks **outside the river**, active from Minecraft time 13000–23000. It is removed during daylight and whenever the plugin disables; players in its footprint are moved safely inward when it appears.

**Controls and client compatibility:** `/themepark ride` boards the coaster, `/themepark carousel` boards the carousel, and `/themepark exit` safely dismounts either ride. Right-clicking a park coaster cart or its station can board it; right-clicking while mounted requests an exit, and sneaking is a backup. The carousel uses armor stands with visible block helmets instead of modern display entities so the 1.12.2 Eagler browser client has a better chance of rendering it. **In-game client testing is still necessary**, especially for right-click while mounted and smooth passenger motion. Vanilla right-click-in-air without a held item may not generate a server interaction packet on some clients; use sneak or the exit command in that case.

The coaster is deliberately built above the barrier's height, so the night wall does not intersect the rail loop.

**Important: first generation is destructive.** This plugin intentionally clears/levels existing spawn terrain across the entire 70 × 70 footprint; its world state is **not** included in GitHub. **Back up the existing Codespaces world folder before enabling the plugin.** Other spawn-build plugins (e.g. EaglerCity) can alter the same blocks; test separately first.

Existing saved parks are **not** flattened again to get the carousel and hideouts. On first startup with this version, the plugin adds only these attractions and records `extras-version: 1` in its saved park state. It can overwrite blocks immediately around the new attractions; make a backup first. When the park enters daylight, only the temporary hideout structures are restored.

The world is modified only on the first successful plugin generation or when an operator explicitly rebuilds. Successful generation stores location/UUID in `plugins/ThemePark/park-state.yml`, so subsequent restarts do **not** flatten the world again. If the world is replaced or that state file is deleted, it can generate again.

## Operator and student commands

| Command | Who | Purpose |
| --- | --- | --- |
| `/themepark status` | Everyone | Build location and night-wall status |
| `/themepark ride` | Players | Spawn and board a coaster minecart |
| `/themepark carousel` | Players | Board an available rotating carousel seat |
| `/themepark exit` | Players | Dismount safely from either park ride |
| `/themepark rebuild confirm` | Operators | **Destructively recreate** the saved park |

The first enable requires no command. The station is on the north edge, with the staircase beginning ~19 blocks north of the center. There is no extra client-side mod: normal 1.12.2-client rails, water, minecarts, and barrier blocks are used.

## Build

```bash
mvn -B clean package
# output: target/ThemePark-1.0.0.jar
```

GitHub Actions builds on main and commits **`dist/ThemePark-1.0.0.jar`**; the classroom plugin picker downloads that JAR via `raw.githubusercontent.com`. Wait for the action to finish before expecting **READY** in the menu. The server's plugin catalog is configured separately in `classroom/plugins.conf` with id `themepark`, and the selection is opt-in by default.

In Codespaces, stop Paper normally, update your server checkout safely, then run `bash startup.sh` and select **Theme Park**. Do not use `git reset --hard` on a Codespace containing world data.

## Settings

Edit `server/plugins/ThemePark/config.yml` after the first run:

```yaml
world: world
generate-on-first-enable: true
night-barrier: true
river-current: true
river-current-speed: 0.14
give-test-minecarts: true
test-minecarts: 4
night-enemies: 16
carousel-degrees-per-second: 35.0
```

**Survival timing and isolation:** enemies exist only between Minecraft times 13000 and 23000. Night waves are limited to `night-enemies` (clamped 3–32) with two replacements at most per seven seconds; the HUD counts living tagged monsters plus hostile park golems, not a global world-wide mob count. Peaceful difficulty prevents hostile creatures from lasting: use Easy/Normal/Hard for survival gameplay. Monsters break only the park's specially constructed wooden shelter blocks when a player is sheltering and an enemy has approached; ordinary park terrain stays protected. Other plugins' golems, villagers and mobs are not altered. No client mod is needed.

A backup and in-game test are still needed: CI checks Java compilation and packaged resources, but it does not simulate Minecraft physics, Eagler client rendering, actual minecart movement, or interactions with other plugin-generated structures.
