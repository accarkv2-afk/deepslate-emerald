package br.emeraldscout.prediction;

import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Immutable, thread-safe marker snapshots grouped by chunk. */
public final class PredictionStore {
    public record Snapshot(
            RegistryKey<World> dimension,
            Map<Long, List<PredictionMarker>> chunks,
            List<PredictionMarker> markers,
            int predictedBlocks,
            int confirmedBlocks,
            int rejectedBlocks,
            int uniqueVeins
    ) {
        public static Snapshot empty() {
            return new Snapshot(null, Map.of(), List.of(), 0, 0, 0, 0);
        }
    }

    private static final AtomicReference<Snapshot> CURRENT =
            new AtomicReference<>(Snapshot.empty());
    private static volatile String diagnostic =
            "Seed-based candidate predictor ready; set a seed and run /es scan.";

    private PredictionStore() {}

    public static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static void replace(
            RegistryKey<World> dimension,
            Collection<PredictionMarker> markers
    ) {
        Map<Long, List<PredictionMarker>> grouped = new HashMap<>();
        for (PredictionMarker marker : markers) {
            BlockPos pos = marker.pos();
            long key = chunkKey(pos.getX() >> 4, pos.getZ() >> 4);
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(marker);
        }

        Map<Long, List<PredictionMarker>> immutable = new HashMap<>();
        grouped.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
        CURRENT.set(buildSnapshot(dimension, immutable));
    }

    public static void replaceChunk(
            RegistryKey<World> dimension,
            int chunkX,
            int chunkZ,
            Collection<PredictionMarker> markers
    ) {
        long key = chunkKey(chunkX, chunkZ);
        CURRENT.updateAndGet(old -> {
            Map<Long, List<PredictionMarker>> updated =
                    old.dimension() != null && old.dimension().equals(dimension)
                            ? new HashMap<>(old.chunks()) : new HashMap<>();
            if (markers.isEmpty()) updated.remove(key);
            else updated.put(key, List.copyOf(markers));
            return buildSnapshot(dimension, updated);
        });
    }

    private static Snapshot buildSnapshot(
            RegistryKey<World> dimension,
            Map<Long, List<PredictionMarker>> chunks
    ) {
        List<PredictionMarker> all = new ArrayList<>();
        Set<Long> veins = new HashSet<>();
        int predicted = 0;
        int confirmed = 0;
        int rejected = 0;

        for (List<PredictionMarker> bucket : chunks.values()) {
            for (PredictionMarker marker : bucket) {
                all.add(marker);
                veins.add(marker.veinId());
                switch (marker.kind()) {
                    case PREDICTED -> predicted++;
                    case CONFIRMED -> confirmed++;
                    case REJECTED -> rejected++;
                }
            }
        }

        Map<Long, List<PredictionMarker>> immutable = new HashMap<>();
        chunks.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
        return new Snapshot(
                dimension,
                Map.copyOf(immutable),
                List.copyOf(all),
                predicted,
                confirmed,
                rejected,
                veins.size()
        );
    }

    public static Snapshot snapshot() {
        return CURRENT.get();
    }

    public static void setDiagnostic(String value) {
        diagnostic = value == null ? "" : value;
    }

    public static String diagnostic() {
        return diagnostic;
    }

    public static void clear() {
        CURRENT.set(Snapshot.empty());
    }
}
