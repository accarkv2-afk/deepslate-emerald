package br.emeraldscout.worldgen;

import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.Objects;

/**
 * Read-only vanilla 1.21.1 Overworld generation context.
 * The connected client's dynamic registries are used, but the generator
 * profile itself is the vanilla default Overworld preset.
 */
public record VanillaWorldgenContext(
        long seed,
        DynamicRegistryManager registries,
        NoiseChunkGenerator generator,
        NoiseConfig noiseConfig
) {
    public static VanillaWorldgenContext createDefaultOverworld(
            DynamicRegistryManager registries,
            long seed
    ) {
        Objects.requireNonNull(registries, "registries");

        DimensionOptions options = WorldPresets.getDefaultOverworldOptions(registries);
        if (!(options.chunkGenerator() instanceof NoiseChunkGenerator noiseGenerator)) {
            throw new IllegalStateException("Default Overworld is not a NoiseChunkGenerator");
        }

        noiseGenerator.initializeIndexedFeaturesList();

        NoiseConfig config = NoiseConfig.create(
                noiseGenerator.getSettings().value(),
                registries.getOrThrow(RegistryKeys.NOISE_PARAMETERS),
                seed
        );

        return new VanillaWorldgenContext(seed, registries, noiseGenerator, config);
    }
}
