package br.emeraldscout.scan;

import br.emeraldscout.prediction.PredictionMarker;

import java.util.List;

/** Summary of a deterministic placement-RNG replay scan. */
public record ScanResult(
        int chunksScanned,
        int chunksWithEmeraldBiome,
        int placementAttempts,
        int biomeAllowedAttempts,
        int candidateBlocks,
        int featureRuns,
        int heightmapRejectedAttempts,
        List<PredictionMarker> markers,
        long elapsedMillis,
        boolean cancelled
) {
    public ScanResult {
        markers = List.copyOf(markers);
    }
}
