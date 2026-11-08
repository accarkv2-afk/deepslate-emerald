# Prediction method and current limits

## RNG replay

For each chunk, `EmeraldOrePredictor`:

1. Creates a `ChunkRandom` backed by `Xoroshiro128PlusPlusRandom`.
2. Calls `setPopulationSeed(worldSeed, chunkX * 16, chunkZ * 16)`.
3. Resolves `minecraft:ore_emerald` from the placed-feature registry and obtains its `UNDERGROUND_ORES` index from the generator's indexed feature list.
4. Calls `setDecoratorSeed(populationSeed, featureIndex, UNDERGROUND_ORES.ordinal())`.
5. Executes 100 placement attempts. Each attempt consumes X, Z, and the two inclusive random values used by the trapezoid height provider.
6. Evaluates the placed feature's biome eligibility at the sampled position.
7. For biome-eligible attempts, mirrors the initial random calls in `OreFeature.generate`: angle and two vertical offsets.
8. Checks the inclusive `OCEAN_FLOOR_WG` heightmap gate. Only a passing attempt consumes the radius doubles used by `generateVeinPart`.
9. Reproduces the size-3 overlapping-sphere culling and block-position geometry.

This is why the order of calls matters: a failed biome filter does not enter the ore feature, and a failed heightmap gate does not consume the sphere radii. Changing either behavior shifts the later pseudo-random sequence.

## Terrain candidate filter

A `NoiseChunkGenerator` column sample is pre-surface-rule/pre-carver. The current filter therefore uses the vanilla Overworld depth convention and base-stone tags to create provisional candidates at Y <= 0. It deliberately excludes Y=1..7, where the deepslate/stone transition is randomized.

It does not yet replay all blocks that `OreFeature.generateVeinPart` would inspect at generation time. In particular, it lacks the exact target-block state after carvers and all earlier placed features. This is why every result remains `PREDICTED` until a client chunk can be inspected, and why predictions in an altered world can be wrong.

## Verification

`PredictionVerifier` runs when the client receives a chunk and compares each predicted coordinate to the current `Blocks.DEEPSLATE_EMERALD_ORE` state. It updates the marker to `CONFIRMED` or `REJECTED`. If a block was mined or edited before the check, the verifier cannot distinguish that edit from a worldgen mismatch. For meaningful validation, use a fresh world without modifications.
