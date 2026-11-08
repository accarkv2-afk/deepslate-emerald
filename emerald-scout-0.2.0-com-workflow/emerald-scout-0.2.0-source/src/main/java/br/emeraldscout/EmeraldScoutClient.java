package br.emeraldscout;

import br.emeraldscout.config.EmeraldScoutConfig;
import br.emeraldscout.input.EmeraldScoutKeybind;
import br.emeraldscout.prediction.PredictionStore;
import br.emeraldscout.prediction.PredictionVerifier;
import br.emeraldscout.render.BlockEspRenderer;
import br.emeraldscout.render.EspStatsHud;
import br.emeraldscout.scan.ScanResult;
import br.emeraldscout.scan.WorldgenScanTask;
import br.emeraldscout.worldgen.VanillaWorldgenContext;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.world.World;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EmeraldScoutClient implements ClientModInitializer {
    private static final ExecutorService SCAN_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "EmeraldScout-Worldgen");
                thread.setDaemon(true);
                return thread;
            });

    private static AtomicBoolean cancelFlag = new AtomicBoolean(false);
    private static volatile WorldgenScanTask activeTask;
    private static volatile Future<?> activeFuture;

    @Override
    public void onInitializeClient() {
        EmeraldScoutConfig.load();
        BlockEspRenderer.register();
        EspStatsHud.register();
        EmeraldScoutKeybind.register();
        PredictionVerifier.register();
        PredictionStore.setDiagnostic("Set a seed, then run /es scan.");
        registerCommands();
    }

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("es")
                        .then(ClientCommandManager.literal("seed")
                                .then(ClientCommandManager.argument("value", LongArgumentType.longArg())
                                        .executes(context -> {
                                            long seed = LongArgumentType.getLong(context, "value");
                                            cancelFlag.set(true);
                                            EmeraldScoutConfig.setSeed(seed);
                                            PredictionStore.clear();
                                            PredictionStore.setDiagnostic("Seed changed; run /es scan.");
                                            context.getSource().sendFeedback(Text.literal(
                                                    "[EmeraldScout] Manual seed saved: " + seed));
                                            return 1;
                                        }))
                                .then(ClientCommandManager.literal("clear")
                                        .executes(context -> {
                                            cancelFlag.set(true);
                                            EmeraldScoutConfig.setSeed(null);
                                            PredictionStore.clear();
                                            PredictionStore.setDiagnostic("Seed cleared.");
                                            context.getSource().sendFeedback(Text.literal(
                                                    "[EmeraldScout] Seed removed."));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("radius")
                                .then(ClientCommandManager.argument("chunks", IntegerArgumentType.integer(1, 128))
                                        .executes(context -> {
                                            int radius = IntegerArgumentType.getInteger(context, "chunks");
                                            EmeraldScoutConfig.setRadius(radius);
                                            context.getSource().sendFeedback(Text.literal(
                                                    "[EmeraldScout] Prediction radius: " + radius + " chunks."));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("scan")
                                .executes(context -> startScan(context.getSource().getClient())))
                        .then(ClientCommandManager.literal("cancel")
                                .executes(context -> {
                                    cancelFlag.set(true);
                                    context.getSource().sendFeedback(Text.literal(
                                            "[EmeraldScout] Scan cancellation requested."));
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("status")
                                .executes(context -> {
                                    Long seed = EmeraldScoutConfig.getSeed();
                                    PredictionStore.Snapshot snapshot = PredictionStore.snapshot();
                                    context.getSource().sendFeedback(Text.literal(
                                            "[EmeraldScout] seed=" + (seed == null ? "not set" : seed)
                                                    + ", radius=" + EmeraldScoutConfig.getRadius()
                                                    + ", ESP=" + (EmeraldScoutConfig.isEspEnabled() ? "ON" : "OFF")
                                                    + ", markers=" + snapshot.markers().size()
                                                    + ", scan=" + scanStatus()));
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("esp")
                                .then(ClientCommandManager.literal("on")
                                        .executes(context -> {
                                            EmeraldScoutConfig.setEspEnabled(true);
                                            context.getSource().sendFeedback(Text.literal(
                                                    "[EmeraldScout] Block ESP ON"));
                                            return 1;
                                        }))
                                .then(ClientCommandManager.literal("off")
                                        .executes(context -> {
                                            EmeraldScoutConfig.setEspEnabled(false);
                                            context.getSource().sendFeedback(Text.literal(
                                                    "[EmeraldScout] Block ESP OFF"));
                                            return 1;
                                        }))));
    }

    private static int startScan(MinecraftClient client) {
        if (client.world == null || client.player == null) return 0;
        if (!client.world.getRegistryKey().equals(World.OVERWORLD)) {
            client.player.sendMessage(Text.literal(
                    "[EmeraldScout] Enter the Overworld to run the vanilla predictor."), false);
            return 0;
        }

        Long seed = EmeraldScoutConfig.getSeed();
        if (seed == null) {
            client.player.sendMessage(Text.literal(
                    "[EmeraldScout] Set the world seed first: /es seed <number>"), false);
            return 0;
        }
        if (activeFuture != null && !activeFuture.isDone()) {
            client.player.sendMessage(Text.literal(
                    "[EmeraldScout] A scan is already running. Use /es cancel."), false);
            return 0;
        }

        try {
            PredictionStore.clear();
            PredictionStore.setDiagnostic("Replaying ore_emerald placement RNG...");
            VanillaWorldgenContext worldgen = VanillaWorldgenContext.createDefaultOverworld(
                    client.world.getRegistryManager(), seed
            );
            int centerChunkX = client.player.getBlockPos().getX() >> 4;
            int centerChunkZ = client.player.getBlockPos().getZ() >> 4;
            int radius = EmeraldScoutConfig.getRadius();
            cancelFlag = new AtomicBoolean(false);

            WorldgenScanTask task = new WorldgenScanTask(
                    worldgen, centerChunkX, centerChunkZ, radius, cancelFlag
            );
            activeTask = task;

            client.player.sendMessage(Text.literal(
                    "[EmeraldScout] Seed replay started: " + task.getTotalChunks()
                            + " chunks, radius " + radius + "."), false);
            if (radius >= 64) {
                client.player.sendMessage(Text.literal(
                        "[EmeraldScout] Large scans can take a long time; try radius 8-16 first."), false);
            }

            activeFuture = SCAN_EXECUTOR.submit(() -> {
                task.run();
                client.execute(() -> finishScan(client, task));
            });
            return 1;
        } catch (Exception exception) {
            client.player.sendMessage(Text.literal(
                    "[EmeraldScout] Could not create vanilla worldgen context: "
                            + exception.getClass().getSimpleName() + " - " + exception.getMessage()), false);
            PredictionStore.setDiagnostic("Worldgen context error: " + exception.getClass().getSimpleName());
            return 0;
        }
    }

    private static void finishScan(MinecraftClient client, WorldgenScanTask task) {
        if (task.getError() != null) {
            PredictionStore.setDiagnostic("Predictor error: " + task.getError());
            if (client.player != null) {
                client.player.sendMessage(Text.literal(
                        "[EmeraldScout] Prediction error: " + task.getError()), false);
            }
            return;
        }
        if (task.getResult() == null || client.player == null || client.world == null) return;
        Long currentSeed = EmeraldScoutConfig.getSeed();
        if (currentSeed == null || currentSeed.longValue() != task.getSeed()) {
            // A seed change during a scan cancels that scan. Never let its late
            // completion overwrite predictions for the newer seed.
            return;
        }
        if (!client.world.getRegistryKey().equals(World.OVERWORLD)) {
            PredictionStore.clear();
            PredictionStore.setDiagnostic("Scan result discarded: player left the Overworld.");
            return;
        }

        ScanResult result = task.getResult();
        PredictionStore.replace(client.world.getRegistryKey(), result.markers());
        int validated = PredictionVerifier.validateCurrentlyLoaded(client.world);

        PredictionStore.setDiagnostic(
                "RNG replay done; terrain/carver validation is partial."
        );

        PredictionStore.Snapshot snapshot = PredictionStore.snapshot();
        client.player.sendMessage(Text.literal(
                "[EmeraldScout] Seed prediction done: " + result.chunksScanned()
                        + " chunks; " + result.placementAttempts() + " placement attempts; "
                        + result.biomeAllowedAttempts() + " biome-eligible attempts; "
                        + result.featureRuns() + " ore feature runs; "
                        + result.heightmapRejectedAttempts() + " heightmap rejects; "
                        + result.candidateBlocks() + " deepslate candidates across "
                        + snapshot.uniqueVeins() + " veins; " + validated
                        + " candidates checked in already-loaded chunks; "
                        + result.elapsedMillis() + " ms."
                        + (result.cancelled() ? " (partial scan: cancelled)" : "")), false);
        client.player.sendMessage(Text.literal(
                "[EmeraldScout] Candidates replay the vanilla ore_emerald placement RNG and vein geometry, "
                        + "then apply a base-terrain/deepslate-band filter. Final cave carving, prior feature edits, "
                        + "and custom server worldgen are not reproduced; loaded chunks are checked against actual blocks."), false);
    }

    private static String scanStatus() {
        WorldgenScanTask task = activeTask;
        if (task == null) return "idle";
        Future<?> future = activeFuture;
        if (future == null || future.isDone()) return "finished";
        return task.getCompletedChunks() + "/" + task.getTotalChunks();
    }
}
