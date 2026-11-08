# EmeraldScout 0.2.0 — seed-based Block ESP source

Fabric client mod source for **Minecraft 1.21.1**, **Java 21**.

This source connects a manual-seed replay of the vanilla `minecraft:ore_emerald` placed-feature RNG to the Block ESP renderer. It is intended for single-player worlds and worlds/servers where the user is authorized to use seed-based world analysis. It does not request or force-load remote chunks.

## What is implemented

- Manual signed 64-bit seed stored in `config/emeraldscout.json`.
- Default vanilla Overworld worldgen context built from the client's dynamic registries.
- Resolves the actual `minecraft:ore_emerald` `PlacedFeature` and its vanilla feature index for `UNDERGROUND_ORES`.
- Replays the chunk population/decorator seeding sequence.
- Replays the 100 placement attempts, X/Z in-square placement, trapezoidal Y distribution (-16 through 480), and biome eligibility filter.
- Replays `OreFeature` size-3 sphere/vein geometry, including the order of random calls around the `OCEAN_FLOOR_WG` height gate.
- Generates provisional deepslate-ore host candidates using the default Overworld base terrain sample and the deepslate depth band (Y <= 0).
- Publishes candidates to a chunk-bucketed `PredictionStore` used by the ESP renderer.
- 3D boxes, optional through-wall rendering, floating ore labels, distance, compass direction, coordinates, candidate vein block count, and terrain-status text.
- HUD counters for predicted, confirmed, rejected blocks and unique candidate veins.
- Right Shift configuration menu and persistent settings.
- Client-chunk verification: when a chunk is actually received, candidates are compared to `minecraft:deepslate_emerald_ore` and marked confirmed/rejected.
- Scan runs on a dedicated worker with cancellation and stale-seed result protection.
- Duplicate candidate block positions shared by neighboring chunk feature runs are deduplicated in the final scan.

## Important accuracy boundary

The predictor currently replays the **placed-feature RNG and ore-vein geometry**, not the entire final chunk-generation pipeline. A marker is a **candidate**, not a guaranteed ore block.

The candidate filter samples the default vanilla Overworld noise column before surface rules and carving. It excludes the randomized deepslate transition band above Y=0 and only keeps base-stone candidates at Y <= 0. The following are not fully simulated:

- Cave/ravine carvers and the final post-carver block state.
- Surface rules and all per-column surface-rule random decisions.
- Earlier placed features that may have changed the host block before emerald generation.
- Structures, block edits, mining, custom datapacks, modded biomes/worldgen, and server-specific generator settings.

The target list in vanilla `ore_emerald` is configured for stone and deepslate ore-replaceable tags; the configured feature has size 3 and `discard_chance_on_air_exposure` 0.0. The code checks that size and discard chance match before scanning. Placement attempts use 100 per chunk, the in-square modifier, trapezoid height range, and biome filter. The implementation is version-locked to the vanilla 1.21.1 profile.

When a predicted chunk arrives from the client connection, the verifier checks the current block at each candidate coordinate. `CONFIRMED` means the block is currently `deepslate_emerald_ore`. `REJECTED` means it was not present at validation time; this can also happen because the block was mined, edited, replaced, or because the simulated terrain differed. Do not interpret the count of rejected markers as a pure algorithm false-positive rate unless you test in a fresh, unmodified vanilla world.

A manual seed is not proof that a server uses the default vanilla worldgen. Exact prediction requires the same Minecraft version, seed, generator settings, biome/feature registries, and datapacks as the target world.

## Commands

- `Right Shift` — open the settings menu.
- `/es seed <signed-long>` — set and save the manual seed.
- `/es seed clear` — clear the saved seed.
- `/es radius <1..128>` — set scan radius in chunks around the current player chunk.
- `/es scan` — replay candidate positions for the configured seed.
- `/es cancel` — request scan cancellation.
- `/es status` — show seed, radius, ESP state, marker count, and scan progress.
- `/es esp on` / `/es esp off` — enable or disable Block ESP.

The menu controls ESP enabled, through-walls, floating labels, coordinates, stats HUD, and ESP distance.

## Defaults

Settings are stored in `config/emeraldscout.json`:

```json
{
  "seed": null,
  "radius": 16,
  "espEnabled": true,
  "throughWalls": true,
  "showLabels": true,
  "showCoordinates": true,
  "showStats": true,
  "espDistance": 128
}
```

The ESP distance is measured in blocks from the camera and is independent of server view distance. It does not load chunks. The renderer currently caps drawing to 256 boxes and 48 labels per frame.

## Build

Requirements:

- JDK 21.
- Gradle 8.12 or a compatible Gradle release for Fabric Loom 1.10.5.
- Internet access for the initial Minecraft/Fabric dependency download.

From this folder:

```bash
gradle build
```

The remapped mod JAR should be generated in `build/libs/` with a name similar to `emerald-scout-0.2.0.jar`.

Install that JAR alongside Fabric API in the Minecraft 1.21.1 `mods` folder.

## Validation status

This package is **source code, not a compiled JAR**. It has not been compiled or launched in Minecraft in this environment. Java 21 is available here, but Gradle is not installed and the dependency set could not be downloaded, so source-level inspection and archive checks are not a substitute for a Loom build or an in-game parity test.

Recommended validation procedure:

1. Use a fresh, unmodified vanilla 1.21.1 world and its actual seed.
2. Set that seed with `/es seed <seed>` and start with `/es radius 2` or `4`.
3. Run `/es scan` and save the candidate coordinates.
4. Visit/load the same chunks and compare each candidate to the actual block.
5. Repeat across multiple seeds and regions before making any precision claim.

Large radii scale quadratically: radius 128 covers 66,049 chunks and replays roughly 6.6 million feature-placement attempts. Start with a small radius.
