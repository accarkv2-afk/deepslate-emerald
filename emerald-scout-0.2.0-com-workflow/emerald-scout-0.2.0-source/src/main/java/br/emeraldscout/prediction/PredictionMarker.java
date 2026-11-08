package br.emeraldscout.prediction;

import net.minecraft.util.math.BlockPos;

import java.util.Objects;

/** A visual marker produced by a prediction engine or a validation pass. */
public record PredictionMarker(
        BlockPos pos,
        Kind kind,
        long veinId,
        String displayName,
        int veinBlocks,
        String terrainStatus
) {
    public PredictionMarker {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(kind, "kind");
        pos = pos.toImmutable();
        displayName = displayName == null || displayName.isBlank()
                ? "DEEPSLATE EMERALD" : displayName;
        veinBlocks = Math.max(1, Math.min(64, veinBlocks));
        terrainStatus = terrainStatus == null || terrainStatus.isBlank()
                ? "terrain pending" : terrainStatus;
    }

    /** Convenience constructor for a single-block emerald marker. */
    public PredictionMarker(BlockPos pos, Kind kind) {
        this(pos, kind, pos.asLong(), "DEEPSLATE EMERALD", 1, "terrain pending");
    }

    public enum Kind {
        PREDICTED,
        CONFIRMED,
        REJECTED
    }
}
