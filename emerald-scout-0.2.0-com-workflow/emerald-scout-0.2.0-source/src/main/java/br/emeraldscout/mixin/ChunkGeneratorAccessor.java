package br.emeraldscout.mixin;

import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.feature.util.PlacedFeatureIndexer;
import net.minecraft.world.gen.feature.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.function.Supplier;

/** Read-only access to the exact global placed-feature order built by vanilla. */
@Mixin(ChunkGenerator.class)
public interface ChunkGeneratorAccessor {
    @Accessor("indexedFeaturesListSupplier")
    Supplier<List<PlacedFeatureIndexer.IndexedFeatures>> emeraldscout$getIndexedFeaturesListSupplier();
}
