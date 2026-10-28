# ThemePark — Eaglercraft Classroom Spawn Park

A **Paper 1.21.11** plugin designed for [SMalone16/Eaglercraft-1.21.11-Server](https://github.com/SMalone16/Eaglercraft-1.21.11-Server). This runs on the **Paper backend**, not Velocity. Java 21 and Maven are used to compile.

## First build

When selected in the classroom plugin menu for the first time, ThemePark builds a **70 × 70 block square centered on the Overworld spawn** (offsets -35..+34 on X and Z). It flattens the footprint to a common Y level, lays a solid stone floor, and adds dirt paths and a stone-brick spawn plaza. It then creates:

- **Lazy river:** a circular, two-block-deep, ~6-block-wide continuous water ring near the edge, with stone-brick borders and a gentle **clockwise swimmer current** (configurable).
- **Roller coaster:** elevated perimeter minecart rail loop with several hills, powered rails over redstone blocks, stone support columns, a three-wide staircase, and a raised boarding deck on the north side. Ride automatically with \`/themepark ride\` or place a test minecart manually.
- **Four test minecarts per player:** on join or respawn, the plugin tops up the player's inventory to four ordinary minecarts; repeated logins do not increase that count.
- **Protected park:** block breaking/placing, bucket changes, burning, freezing, fluid spread, explosions, entity block changes, and piston changes inside the footprint are blocked. Ordinary minecart interaction remains usable.
- **Night wall:** a six-block-high ring of invisible barrier blocks **outside the river**, active from Minecraft time 13000–23000. It is removed during daylight and whenever the plugin disables; players in its footprint are moved safely inward when it appears.

The coaster is deliberately built above the barrier's height, so the night wall does not intersect the rail loop.

**Important: first generation is destructive.** This plugin intentionally clears/levels existing spawn terrain across the entire 70 × 70 footprint; its world state is **not** included in GitHub. **Back up the existing Codespaces world folder before enabling the plugin.** Other spawn-build plugins (e.g. EaglerCity) can alter the same blocks; test separately first.

The world is modified only on the first successful plugin generation or when an operator explicitly rebuilds. Successful generation stores location/UUID in \`plugins/ThemePark/park-state.yml\`, so subsequent restarts do **not** flatten the world again. If the world is replaced or that state file is deleted, it can generate again.

## Operator and student commands

| Command | Who | Purpose |
| --- | --- | --- |
| \`/themepark status\` | Everyone | Build location and night-wall status |
| \`/themepark ride\` | Players | Spawn and board a coaster minecart |
| \`/themepark rebuild confirm\` | Operators | **Destructively recreate** the saved park |

The first enable requires no command. The station is on the north edge, with the staircase beginning ~19 blocks north of the center. There is no extra client-side mod: normal 1.12.2-client rails, water, minecarts, and barrier blocks are used.

## Build

\`\`\`bash
mvn -B clean package
# output: target/ThemePark-1.0.0.jar
\`\`\`

GitHub Actions builds on main and commits **\`dist/ThemePark-1.0.0.jar\`**; the classroom plugin picker downloads that JAR via \`raw.githubusercontent.com\`. Wait for the action to finish before expecting **READY** in the menu. The server's plugin catalog is configured separately in \`classroom/plugins.conf\` with id \`themepark\`, and the selection is opt-in by default.

In Codespaces, stop Paper normally, update your server checkout safely, then run \`bash startup.sh\` and select **Theme Park**. Do not use \`git reset --hard\` on a Codespace containing world data.

## Settings

Edit \`server/plugins/ThemePark/config.yml\` after the first run:

\`\`\`yaml
world: world
generate-on-first-enable: true
night-barrier: true
river-current: true
river-current-speed: 0.14
give-test-minecarts: true
test-minecarts: 4
\`\`\`

A backup and in-game test are still needed: CI checks Java compilation and packaged resources, but it does not simulate Minecraft physics, Eagler client rendering, actual minecart movement, or interactions with other plugin-generated structures.
