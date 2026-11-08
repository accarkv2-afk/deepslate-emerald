package br.emeraldscout.prediction;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compares predicted positions with blocks actually received by the client.
 * A REJECTED marker means the ore was not present when that chunk was checked;
 * player/server edits can also cause this result.
 */
public final class PredictionVerifier {
    private PredictionVerifier() {}

    public static void register() {
        ClientChunkEvents.CHUNK_LOAD.register(PredictionVerifier::verifyChunk);
    }

    /** Checks predictions for chunks that were already loaded before the scan completed. */
    public static int validateCurrentlyLoaded(ClientWorld world) {
        PredictionStore.Snapshot snapshot = PredictionStore.snapshot();
        if (snapshot.dimension() == null
                || !snapshot.dimension().equals(world.getRegistryKey())) {
            return 0;
        }

        int checked = 0;
        for (Map.Entry<Long, List<PredictionMarker>> entry : snapshot.chunks().entrySet()) {
            int chunkX = (int) (entry.getKey() >> 32);
            int chunkZ = (int) (long) entry.getKey();
            WorldChunk chunk = world.getChunkManager().getWorldChunk(chunkX, chunkZ);
            if (chunk != null) {
                checked += verifyChunkMarkers(world, chunk, entry.getValue());
            }
        }
        return checked;
    }

    private static void verifyChunk(ClientWorld world, WorldChunk chunk) {
        PredictionStore.Snapshot snapshot = PredictionStore.snapshot();
        if (snapshot.dimension() == null
                || !snapshot.dimension().equals(world.getRegistryKey())) {
            return;
        }

        ChunkPos pos = chunk.getPos();
        List<PredictionMarker> markers = snapshot.chunks().get(
                PredictionStore.chunkKey(pos.x, pos.z));
        if (markers != null && !markers.isEmpty()) {
            verifyChunkMarkers(world, chunk, markers);
        }
    }

    private static int verifyChunkMarkers(
            ClientWorld world,
            WorldChunk chunk,
            List<PredictionMarker> existing
    ) {
        List<PredictionMarker> updated = new ArrayList<>(existing.size());
        int checked = 0;

        for (PredictionMarker marker : existing) {
            if (marker.kind() != PredictionMarker.Kind.PREDICTED) {
                updated.add(marker);
                continue;
            }

            boolean found = chunk.getBlockState(marker.pos())
                    .isOf(Blocks.DEEPSLATE_EMERALD_ORE);

            updated.add(new PredictionMarker(
                    marker.pos(),
                    found ? PredictionMarker.Kind.CONFIRMED
                            : PredictionMarker.Kind.REJECTED,
                    marker.veinId(),
                    marker.displayName(),
                    marker.veinBlocks(),
                    found ? "confirmed in client chunk"
                            : "not found in loaded chunk"
            ));
            checked++;
        }

        if (checked > 0) {
            ChunkPos pos = chunk.getPos();
            PredictionStore.replaceChunk(
                    world.getRegistryKey(), pos.x, pos.z, updated);
        }
        return checked;
    }
}
