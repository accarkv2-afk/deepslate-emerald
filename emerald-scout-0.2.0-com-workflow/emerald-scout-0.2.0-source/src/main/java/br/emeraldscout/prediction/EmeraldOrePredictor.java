package br.emeraldscout.prediction;

import br.emeraldscout.mixin.ChunkGeneratorAccessor;
import br.emeraldscout.worldgen.VanillaWorldgenContext;

import net.minecraft.block.BlockState;
import net.minecraft.block.BlockTags;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkRandom;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.Xoroshiro128PlusPlusRandom;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import net.minecraft.world.gen.feature.ConfiguredFeature;
import net.minecraft.world.gen.feature.OreFeatureConfig;
import net.minecraft.world.gen.feature.PlacedFeature;
import net.minecraft.world.gen.feature.util.PlacedFeatureIndexer;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Replays the vanilla 1.21.1 minecraft:ore_emerald placement RNG for the
 * default Overworld profile. The returned positions are candidates, not a
 * claim that the completed server chunk contains ore: surface rules in the
 * deepslate transition band and post-noise carvers are not fully replayed.
 */
public final class EmeraldOrePredictor {
    private static final Identifier ORE_EMERALD_ID = Identifier.of("minecraft", "ore_emerald");
    private static final int COUNT = 100;
    private static final int MIN_Y = -16;
    private static final int MAX_Y = 480;
    private static final int HALF_HEIGHT_SPAN = (MAX_Y - MIN_Y) / 2;

    private final VanillaWorldgenContext context;
    private final NoiseChunkGenerator generator;
    private final PlacedFeature placedFeature;
    private final OreFeatureConfig oreConfig;
    private final int featureStep;
    private final int featureIndex;
    private final HeightLimitView heightLimit;

    public EmeraldOrePredictor(VanillaWorldgenContext context) {
        this.context = context;
        this.generator = context.generator();
        this.heightLimit = HeightLimitView.create(
                generator.getMinimumY(), generator.getWorldHeight()
        );

        Registry<PlacedFeature> placedFeatures =
                context.registries().getOrThrow(RegistryKeys.PLACED_FEATURE);
        this.placedFeature = placedFeatures.get(ORE_EMERALD_ID);
        if (placedFeature == null) {
            throw new IllegalStateException("minecraft:ore_emerald is missing from PLACED_FEATURE registry");
        }

        ConfiguredFeature<?, ?> configured = placedFeature.feature().value();
        if (!(configured.config() instanceof OreFeatureConfig config)) {
            throw new IllegalStateException("minecraft:ore_emerald does not use OreFeatureConfig");
        }
        this.oreConfig = config;
        if (oreConfig.size != 3) {
            throw new IllegalStateException("Unexpected vanilla emerald vein size: " + oreConfig.size);
        }
        if (oreConfig.discardOnAirChance != 0.0F) {
            throw new IllegalStateException("Unexpected vanilla emerald discardOnAirChance: "
                    + oreConfig.discardOnAirChance);
        }

        this.featureStep = GenerationStep.Feature.UNDERGROUND_ORES.ordinal();
        List<PlacedFeatureIndexer.IndexedFeatures> indexedByStep =
                ((ChunkGeneratorAccessor) generator)
                        .emeraldscout$getIndexedFeaturesListSupplier().get();
        if (featureStep >= indexedByStep.size()) {
            throw new IllegalStateException("UNDERGROUND_ORES feature index is unavailable");
        }

        PlacedFeatureIndexer.IndexedFeatures indexed = indexedByStep.get(featureStep);
        this.featureIndex = indexed.indexMapping().applyAsInt(placedFeature);
        if (featureIndex < 0 || featureIndex >= indexed.features().size()
                || indexed.features().get(featureIndex) != placedFeature) {
            throw new IllegalStateException("Could not resolve the vanilla ore_emerald feature index");
        }
    }

    public ChunkResult predictChunk(int chunkX, int chunkZ, AtomicBoolean cancelled) {
        int originX = chunkX << 4;
        int originZ = chunkZ << 4;

        // ChunkGenerator.generateFeatures() uses Xoroshiro as ChunkRandom's base
        // and then resets it through setPopulationSeed/setDecoratorSeed.
        ChunkRandom random = new ChunkRandom(new Xoroshiro128PlusPlusRandom(0L));
        long populationSeed = random.setPopulationSeed(context.seed(), originX, originZ);
        random.setDecoratorSeed(populationSeed, featureIndex, featureStep);

        Map<Long, VerticalBlockSample> columns = new HashMap<>();
        Map<Long, Integer> heights = new HashMap<>();
        Set<Long> alreadyPredicted = new HashSet<>();
        List<PredictionMarker> markers = new ArrayList<>();

        int biomeAllowedAttempts = 0;
        int featureAttemptsRun = 0;
        int heightmapRejected = 0;
        int hostCandidates = 0;

        for (int attempt = 0; attempt < COUNT && !cancelled.get(); attempt++) {
            // Vanilla ore_emerald placement chain:
            // count(100) -> in_square -> trapezoid(-16..480) -> biome.
            int x = originX + random.nextInt(16);
            int z = originZ + random.nextInt(16);
            int y = MIN_Y
                    + random.nextBetween(0, HALF_HEIGHT_SPAN)
                    + random.nextBetween(0, HALF_HEIGHT_SPAN);

            RegistryEntry<Biome> biome = generator.getBiomeSource().getBiome(
                    x >> 2,
                    y >> 2,
                    z >> 2,
                    context.noiseConfig().getMultiNoiseSampler()
            );
            if (!biome.value().getGenerationSettings().isFeatureAllowed(placedFeature)) {
                continue;
            }
            biomeAllowedAttempts++;

            // OreFeature.generate() consumes its angle and two vertical offsets
            // before the ocean-floor heightmap check. The three radius doubles
            // are consumed only when that gate succeeds.
            VeinFrame frame = createVeinFrame(random, new BlockPos(x, y, z), oreConfig.size);
            if (!passesOceanFloorGate(frame, heights)) {
                heightmapRejected++;
                continue;
            }
            featureAttemptsRun++;

            List<BlockPos> positions = generateVeinPositions(random, frame, oreConfig.size);
            long veinId = new BlockPos(x, y, z).asLong();
            List<BlockPos> deepslateCandidates = new ArrayList<>();

            for (BlockPos pos : positions) {
                int py = pos.getY();
                if (py < generator.getMinimumY()
                        || py >= generator.getMinimumY() + generator.getWorldHeight()) {
                    continue;
                }

                // Column samples are pre-carver and pre-surface-rule. The
                // Y<=0 + base-stone filter is therefore only a conservative
                // candidate heuristic for the vanilla deepslate band, not a
                // complete replay of the final deepslate_ore_replaceables tag.
                // Y=1..7 is deliberately excluded as a surface-rule transition.
                if (py > 0) continue;

                long posKey = pos.asLong();
                if (alreadyPredicted.contains(posKey)) continue;

                BlockState baseState = stateAt(pos.getX(), py, pos.getZ(), columns);
                if (baseState.isIn(BlockTags.BASE_STONE_OVERWORLD)) {
                    deepslateCandidates.add(pos);
                }
            }

            if (!deepslateCandidates.isEmpty()) {
                int veinBlocks = deepslateCandidates.size();
                for (BlockPos pos : deepslateCandidates) {
                    alreadyPredicted.add(pos.asLong());
                    hostCandidates++;
                    markers.add(new PredictionMarker(
                            pos,
                            PredictionMarker.Kind.PREDICTED,
                            veinId,
                            "DEEPSLATE EMERALD",
                            veinBlocks,
                            "terrain? (cave/server validation pending)"
                    ));
                }
            }
        }

        return new ChunkResult(
                List.copyOf(markers),
                biomeAllowedAttempts,
                featureAttemptsRun,
                heightmapRejected,
                hostCandidates
        );
    }

    private boolean passesOceanFloorGate(VeinFrame frame, Map<Long, Integer> heightCache) {
        // Mirrors OreFeature.generate(): inclusive x/z loops, return on the
        // first column where minY <= OCEAN_FLOOR_WG top Y.
        for (int x = frame.minX(); x <= frame.minX() + frame.horizontalSize(); x++) {
            for (int z = frame.minZ(); z <= frame.minZ() + frame.horizontalSize(); z++) {
                long key = columnKey(x, z);
                Integer cachedHeight = heightCache.get(key);
                int topY;
                if (cachedHeight != null) {
                    topY = cachedHeight;
                } else {
                    // Copy loop coordinates into effectively-final locals before
                    // any lambda-free cache insert; this also keeps the call easy
                    // to compare with ChunkGenerator.getHeight in vanilla.
                    topY = generator.getHeight(
                            x, z, Heightmap.Type.OCEAN_FLOOR_WG,
                            heightLimit, context.noiseConfig()
                    );
                    heightCache.put(key, topY);
                }
                if (frame.minY() <= topY) return true;
            }
        }
        return false;
    }

    private BlockState stateAt(
            int x,
            int y,
            int z,
            Map<Long, VerticalBlockSample> columns
    ) {
        long key = columnKey(x, z);
        VerticalBlockSample sample = columns.computeIfAbsent(key, ignored ->
                generator.getColumnSample(x, z, heightLimit, context.noiseConfig())
        );
        return sample.getState(y);
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** First half of OreFeature.generate(); consumes angle and two Y offsets. */
    private static VeinFrame createVeinFrame(
            net.minecraft.util.math.random.Random random,
            BlockPos origin,
            int size
    ) {
        float angle = random.nextFloat() * (float) Math.PI;
        float halfWidth = size / 8.0F;
        int padding = MathHelper.ceil((size / 16.0F * 2.0F + 1.0F) / 2.0F);

        double startX = origin.getX() + Math.sin(angle) * halfWidth;
        double endX = origin.getX() - Math.sin(angle) * halfWidth;
        double startZ = origin.getZ() + Math.cos(angle) * halfWidth;
        double endZ = origin.getZ() - Math.cos(angle) * halfWidth;
        double startY = origin.getY() + random.nextInt(3) - 2;
        double endY = origin.getY() + random.nextInt(3) - 2;

        int minX = origin.getX() - MathHelper.ceil(halfWidth) - padding;
        int minY = origin.getY() - 2 - padding;
        int minZ = origin.getZ() - MathHelper.ceil(halfWidth) - padding;
        int horizontalSize = 2 * (MathHelper.ceil(halfWidth) + padding);
        int verticalSize = 2 * (2 + padding);

        return new VeinFrame(
                startX, endX, startZ, endZ, startY, endY,
                minX, minY, minZ, horizontalSize, verticalSize
        );
    }

    /** Second half of OreFeature.generate(): consumes radius doubles after height gate. */
    private static List<BlockPos> generateVeinPositions(
            net.minecraft.util.math.random.Random random,
            VeinFrame frame,
            int size
    ) {
        double[] spheres = new double[size * 4];

        for (int i = 0; i < size; i++) {
            float progress = (float) i / size;
            double x = MathHelper.lerp((double) progress, frame.startX(), frame.endX());
            double y = MathHelper.lerp((double) progress, frame.startY(), frame.endY());
            double z = MathHelper.lerp((double) progress, frame.startZ(), frame.endZ());
            double radiusNoise = random.nextDouble() * size / 16.0;
            double radius = ((MathHelper.sin((float) Math.PI * progress) + 1.0F)
                    * radiusNoise + 1.0) / 2.0;

            spheres[i * 4] = x;
            spheres[i * 4 + 1] = y;
            spheres[i * 4 + 2] = z;
            spheres[i * 4 + 3] = radius;
        }

        // Vanilla removes the smaller overlapping sphere.
        for (int i = 0; i < size - 1; i++) {
            if (spheres[i * 4 + 3] <= 0.0) continue;
            for (int j = i + 1; j < size; j++) {
                if (spheres[j * 4 + 3] <= 0.0) continue;

                double dx = spheres[i * 4] - spheres[j * 4];
                double dy = spheres[i * 4 + 1] - spheres[j * 4 + 1];
                double dz = spheres[i * 4 + 2] - spheres[j * 4 + 2];
                double dr = spheres[i * 4 + 3] - spheres[j * 4 + 3];

                if (dr * dr > dx * dx + dy * dy + dz * dz) {
                    if (dr > 0.0) spheres[j * 4 + 3] = -1.0;
                    else spheres[i * 4 + 3] = -1.0;
                }
            }
        }

        BitSet visited = new BitSet(
                frame.horizontalSize() * frame.verticalSize() * frame.horizontalSize()
        );
        Set<BlockPos> positions = new LinkedHashSet<>();

        for (int i = 0; i < size; i++) {
            double radius = spheres[i * 4 + 3];
            if (radius < 0.0) continue;

            double centerX = spheres[i * 4];
            double centerY = spheres[i * 4 + 1];
            double centerZ = spheres[i * 4 + 2];

            int x1 = Math.max(MathHelper.floor(centerX - radius), frame.minX());
            int y1 = Math.max(MathHelper.floor(centerY - radius), frame.minY());
            int z1 = Math.max(MathHelper.floor(centerZ - radius), frame.minZ());
            int x2 = Math.max(MathHelper.floor(centerX + radius), x1);
            int y2 = Math.max(MathHelper.floor(centerY + radius), y1);
            int z2 = Math.max(MathHelper.floor(centerZ + radius), z1);

            for (int x = x1; x <= x2; x++) {
                double nx = (x + 0.5 - centerX) / radius;
                if (nx * nx >= 1.0) continue;

                for (int y = y1; y <= y2; y++) {
                    double ny = (y + 0.5 - centerY) / radius;
                    if (nx * nx + ny * ny >= 1.0) continue;

                    for (int z = z1; z <= z2; z++) {
                        double nz = (z + 0.5 - centerZ) / radius;
                        if (nx * nx + ny * ny + nz * nz >= 1.0) continue;

                        int bitIndex = x - frame.minX()
                                + (y - frame.minY()) * frame.horizontalSize()
                                + (z - frame.minZ()) * frame.horizontalSize() * frame.verticalSize();
                        if (bitIndex < 0) continue;
                        if (!visited.get(bitIndex)) {
                            visited.set(bitIndex);
                            positions.add(new BlockPos(x, y, z));
                        }
                    }
                }
            }
        }

        return List.copyOf(positions);
    }

    private record VeinFrame(
            double startX,
            double endX,
            double startZ,
            double endZ,
            double startY,
            double endY,
            int minX,
            int minY,
            int minZ,
            int horizontalSize,
            int verticalSize
    ) {}

    public record ChunkResult(
            List<PredictionMarker> markers,
            int biomeAllowedAttempts,
            int featureAttemptsRun,
            int heightmapRejected,
            int deepslateHostCandidates
    ) {}
}
