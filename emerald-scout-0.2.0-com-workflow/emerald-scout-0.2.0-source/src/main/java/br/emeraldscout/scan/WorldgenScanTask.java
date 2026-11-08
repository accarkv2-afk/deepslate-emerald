package br.emeraldscout.scan;

import br.emeraldscout.prediction.EmeraldOrePredictor;
import br.emeraldscout.prediction.PredictionMarker;
import br.emeraldscout.worldgen.VanillaWorldgenContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Progressive read-only replay. One worker owns the generator samplers and RNG. */
public final class WorldgenScanTask implements Runnable {
    private final VanillaWorldgenContext context;
    private final int centerChunkX;
    private final int centerChunkZ;
    private final int radius;
    private final AtomicBoolean cancelled;
    private final AtomicInteger completed = new AtomicInteger();
    private volatile ScanResult result;
    private volatile String error;

    public WorldgenScanTask(
            VanillaWorldgenContext context,
            int centerChunkX,
            int centerChunkZ,
            int radius,
            AtomicBoolean cancelled
    ) {
        this.context = context;
        this.centerChunkX = centerChunkX;
        this.centerChunkZ = centerChunkZ;
        this.radius = Math.max(1, Math.min(128, radius));
        this.cancelled = cancelled;
    }

    @Override
    public void run() {
        long started = System.nanoTime();
        int chunksScanned = 0;
        int chunksWithEmeraldBiome = 0;
        int biomeAllowedAttempts = 0;
        int featureAttemptsRun = 0;
        int heightmapRejectedAttempts = 0;
        // A small vein can straddle a chunk edge. Collapse identical block
        // coordinates across neighboring chunk feature runs for clean ESP data.
        Map<Long, PredictionMarker> uniqueMarkers = new LinkedHashMap<>();

        try {
            EmeraldOrePredictor predictor = new EmeraldOrePredictor(context);

            for (int dx = -radius; dx <= radius && !cancelled.get(); dx++) {
                for (int dz = -radius; dz <= radius && !cancelled.get(); dz++) {
                    int chunkX = centerChunkX + dx;
                    int chunkZ = centerChunkZ + dz;

                    EmeraldOrePredictor.ChunkResult prediction =
                            predictor.predictChunk(chunkX, chunkZ, cancelled);

                    for (PredictionMarker marker : prediction.markers()) {
                        uniqueMarkers.putIfAbsent(marker.pos().asLong(), marker);
                    }
                    biomeAllowedAttempts += prediction.biomeAllowedAttempts();
                    featureAttemptsRun += prediction.featureAttemptsRun();
                    heightmapRejectedAttempts += prediction.heightmapRejected();
                    if (prediction.biomeAllowedAttempts() > 0) {
                        chunksWithEmeraldBiome++;
                    }

                    chunksScanned++;
                    completed.set(chunksScanned);
                }
            }

            List<PredictionMarker> markers = List.copyOf(uniqueMarkers.values());
            result = new ScanResult(
                    chunksScanned,
                    chunksWithEmeraldBiome,
                    chunksScanned * 100,
                    biomeAllowedAttempts,
                    markers.size(),
                    featureAttemptsRun,
                    heightmapRejectedAttempts,
                    markers,
                    (System.nanoTime() - started) / 1_000_000L,
                    cancelled.get()
            );
        } catch (Throwable throwable) {
            error = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        }
    }

    public long getSeed() {
        return context.seed();
    }

    public int getCompletedChunks() {
        return completed.get();
    }

    public int getTotalChunks() {
        return (radius * 2 + 1) * (radius * 2 + 1);
    }

    public ScanResult getResult() {
        return result;
    }

    public String getError() {
        return error;
    }
}
