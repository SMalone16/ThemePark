# ThemePark — Eaglercraft Classroom Spawn Park

A **Paper 1.21.11** plugin designed for [SMalone16/Eaglercraft-1.21.11-Server](https://github.com/SMalone16/Eaglercraft-1.21.11-Server). This runs on the **Paper backend**, not Velocity. Java 21 and Maven are used to compile.

## First build

When selected in the classroom plugin menu for the first time, ThemePark builds a **70 × 70 block square centered on the Overworld spawn** (offsets -35..+34 on X and Z). It flattens the footprint to a common Y level, lays a solid stone floor, and adds dirt paths and a stone-brick spawn plaza. It then creates:

- **Lazy river:** a circular, two-block-deep, ~6-block-wide continuous water ring near the edge, with stone-brick borders and a gentle **clockwise swimmer current** (configurable).
- **One-lap coaster:** a validated 245-point closed rail loop with climbs, drops and interior 90-degree turns. A scripted visible minecart follows every rail and ejects its rider automatically at a separate emerald exit platform across the track from the entrance. No personal carts are needed.

- **Protected park:** block breaking/placing, bucket changes, burning, freezing, fluid spread, explosions, entity block changes, and piston changes inside the footprint are blocked. Ordinary minecart interaction remains usable.
- **Carousel:** four block-mounted seats each travel one full revolution and then automatically eject their riders.
- **Nightmare survival:** daytime villagers and protective iron golems inhabit the park. At Minecraft time **13000**, villagers disappear, a limited mix of zombies/skeletons/witches spawns, and park iron golems pursue park visitors. A red boss bar and action bar display **remaining enemies and time until dawn**. At **23000**, hostile mobs disappear and guardians become friendly again. Only entities tagged by this plugin are modified.
- **Four temporary hideouts:** five-block-wide wooden shelters have closeable fence gates and can shield visitors during the night. If enemies reach an occupied shelter, its wood can be broken away. Cover is restored every dawn.
- **Night wall:** a six-block-high ring of invisible barrier blocks **outside the river**, active from Minecraft time 13000–23000. It is removed during daylight and whenever the plugin disables; players in its footprint are moved safely inward when it appears.

**R-key controls:** In Eaglercraft **Options → Controls**, set **Swap Item With Offhand** to **R**. At the gold coaster entrance, carousel, iron strength machine, or blue archery platform, R starts that attraction. This works because the plugin handles the normal offhand-swap packet. Without rebinding, R is **not** supported by the unmodified client; right-click platforms or use commands instead. Sneak or `/themepark exit` can cancel a ride. The coaster and carousel normally auto-exit. No new client build is needed.

The coaster is deliberately built above the barrier's height, so the night wall does not intersect the rail loop.

**Important: first generation is destructive.** This plugin intentionally clears/levels existing spawn terrain across the entire 70 × 70 footprint; its world state is **not** included in GitHub. **Back up the existing Codespaces world folder before enabling the plugin.** Other spawn-build plugins (e.g. EaglerCity) can alter the same blocks; test separately first.

Existing saved parks are **not** flattened again to get the carousel and hideouts. On first startup with this version, the plugin adds only these attractions and records `extras-version: 1` in its saved park state. It can overwrite blocks immediately around the new attractions; make a backup first. When the park enters daylight, only the temporary hideout structures are restored.

The world is modified only on the first successful plugin generation or when an operator explicitly rebuilds. Successful generation stores location/UUID in `plugins/ThemePark/park-state.yml`, so subsequent restarts do **not** flatten the world again. If the world is replaced or that state file is deleted, it can generate again.

## Fairground prize attractions

**Pickaxe Strength Test:** Press R while standing at the iron booth. You get a temporary iron-axe mallet. Hit the anvil mannequin within 25 seconds; Bukkit attack damage determines your pickaxe: wooden (<4), stone (4–6.9), iron (7–9.9), diamond (10–12.9), or netherite (13+). Diamond and netherite prizes get Efficiency. The mallet is removed after scoring or timeout.

**Long-Shot Archery:** Stand on the blue lapis platform and press R to receive a temporary bow and three arrows. The concentric target is about 43 blocks away. All three shots are judged by the best impact. Three misses award an unenchanted bow and three arrows; outer ring awards Power I; inner ring Power II and Unbreaking I; exact bullseye Power III, Unbreaking II and Punch I. A 55-second timeout prevents endless games. Practice arrows that hit are removed from the world.

**Existing-world migration:** `extras-version: 2` upgrades an existing park **without flattening it**. Old perimeter rails are selectively replaced by the new interior coaster; stalls and target are constructed inside the park. Backup first, because the attraction footprints are modified. Previous minecarts in student inventories are not removed, but the plugin no longer gives new starter minecarts or accepts personal carts on its coaster rails.

## Operator and student commands

| Command | Who | Purpose |
| --- | --- | --- |
| `/themepark status` | Everyone | Build location and night-wall status |
| `/themepark ride` | Players near gold coaster entrance | Start one full coaster ride |
| `/themepark carousel` | Players near carousel | Start one full revolution |
| `/themepark exit` | Players | Dismount safely from either park ride |
| `/themepark strength` | Players near iron platform | Start the strength test |
| `/themepark archery` | Players near blue lapis platform | Start the 3-shot archery challenge |
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


night-enemies: 16
carousel-degrees-per-second: 24.0
coaster-blocks-per-tick: 0.46
```

**Survival timing and isolation:** enemies exist only between Minecraft times 13000 and 23000. Night waves are limited to `night-enemies` (clamped 3–32) with two replacements at most per seven seconds; the HUD counts living tagged monsters plus hostile park golems, not a global world-wide mob count. Peaceful difficulty prevents hostile creatures from lasting: use Easy/Normal/Hard for survival gameplay. Monsters break only the park's specially constructed wooden shelter blocks when a player is sheltering and an enemy has approached; ordinary park terrain stays protected. Other plugins' golems, villagers and mobs are not altered. No client mod is needed.

A backup and in-game test are still needed: CI checks Java compilation and packaged resources, but it does not simulate Minecraft physics, Eagler client rendering, actual minecart movement, or interactions with other plugin-generated structures.
