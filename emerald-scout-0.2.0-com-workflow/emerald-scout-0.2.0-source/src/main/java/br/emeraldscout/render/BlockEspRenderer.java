package br.emeraldscout.render;

import br.emeraldscout.config.EmeraldScoutConfig;
import br.emeraldscout.prediction.PredictionMarker;
import br.emeraldscout.prediction.PredictionStore;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** World-space boxes and floating, dark-backed ore labels. */
public final class BlockEspRenderer {
    private static final double EPSILON = 0.003;
    private static final int MAX_BOXES_PER_FRAME = 256;
    private static final int MAX_LABELS_PER_FRAME = 48;

    /* Custom lines layer: the depth state is changed when the buffered layer is
       actually drawn, not when its VertexConsumer is requested. */
    private static final RenderLayer THROUGH_WALL_LINES = new RenderLayer(
            "emeraldscout_through_wall_lines",
            VertexFormats.LINES,
            VertexFormat.DrawMode.LINES,
            32768,
            false,
            true,
            () -> {
                RenderSystem.setShader(GameRenderer::getRenderTypeLinesProgram);
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.disableDepthTest();
                RenderSystem.depthMask(false);
                RenderSystem.lineWidth(2.0f);
            },
            () -> {
                RenderSystem.lineWidth(1.0f);
                RenderSystem.depthMask(true);
                RenderSystem.enableDepthTest();
                RenderSystem.disableBlend();
            }
    ) {};

    private record Candidate(PredictionMarker marker, double distanceSquared) {}

    private BlockEspRenderer() {}

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(BlockEspRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        if (!EmeraldScoutConfig.isEspEnabled() || context.world() == null) return;

        PredictionStore.Snapshot snapshot = PredictionStore.snapshot();
        if (snapshot.dimension() == null
                || !snapshot.dimension().equals(context.world().getRegistryKey())
                || snapshot.markers().isEmpty()) return;

        MatrixStack matrices = context.matrixStack();
        VertexConsumerProvider consumers = context.consumers();
        if (matrices == null || consumers == null) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        Camera cameraObject = context.camera();
        Vec3d camera = cameraObject.getPos();
        Vec3d player = client.player.getPos();
        double range = EmeraldScoutConfig.getEspDistance();
        double rangeSquared = range * range;
        int centerChunkX = ((int) Math.floor(camera.x)) >> 4;
        int centerChunkZ = ((int) Math.floor(camera.z)) >> 4;
        int chunkRadius = (int) Math.ceil(range / 16.0) + 1;

        List<Candidate> nearby = new ArrayList<>();
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                List<PredictionMarker> bucket = snapshot.chunks().get(
                        PredictionStore.chunkKey(centerChunkX + dx, centerChunkZ + dz));
                if (bucket == null) continue;

                for (PredictionMarker marker : bucket) {
                    BlockPos pos = marker.pos();
                    double distanceSquared = camera.squaredDistanceTo(
                            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    if (distanceSquared > rangeSquared) continue;

                    Box box = new Box(
                            pos.getX() - EPSILON, pos.getY() - EPSILON, pos.getZ() - EPSILON,
                            pos.getX() + 1.0 + EPSILON,
                            pos.getY() + 1.0 + EPSILON,
                            pos.getZ() + 1.0 + EPSILON);
                    if (context.frustum() != null && !context.frustum().isVisible(box)) continue;
                    nearby.add(new Candidate(marker, distanceSquared));
                }
            }
        }

        nearby.sort(Comparator.comparingDouble(Candidate::distanceSquared));
        RenderLayer lineLayer = EmeraldScoutConfig.isThroughWalls()
                ? THROUGH_WALL_LINES : RenderLayer.getLines();
        VertexConsumer lines = consumers.getBuffer(lineLayer);
        TextRenderer textRenderer = client.textRenderer;
        Set<Long> labelledVeins = new HashSet<>();
        int boxesDrawn = 0;
        int labelsDrawn = 0;

        for (Candidate candidate : nearby) {
            if (boxesDrawn >= MAX_BOXES_PER_FRAME) break;
            PredictionMarker marker = candidate.marker();
            BlockPos pos = marker.pos();
            float[] color = colorFor(marker.kind());

            matrices.push();
            matrices.translate(
                    pos.getX() - camera.x,
                    pos.getY() - camera.y,
                    pos.getZ() - camera.z);
            WorldRenderer.drawBox(
                    matrices, lines,
                    -EPSILON, -EPSILON, -EPSILON,
                    1.0 + EPSILON, 1.0 + EPSILON, 1.0 + EPSILON,
                    color[0], color[1], color[2], 0.96f);
            matrices.pop();
            boxesDrawn++;

            if (EmeraldScoutConfig.isShowLabels()
                    && labelsDrawn < MAX_LABELS_PER_FRAME
                    && labelledVeins.add(marker.veinId())) {
                drawFloatingLabel(
                        matrices, consumers, textRenderer, cameraObject,
                        camera, player, marker, color);
                labelsDrawn++;
            }
        }
    }

    private static void drawFloatingLabel(
            MatrixStack matrices,
            VertexConsumerProvider consumers,
            TextRenderer textRenderer,
            Camera cameraObject,
            Vec3d camera,
            Vec3d player,
            PredictionMarker marker,
            float[] color
    ) {
        BlockPos pos = marker.pos();
        double dx = pos.getX() + 0.5 - player.x;
        double dz = pos.getZ() + 0.5 - player.z;
        int distance = (int) Math.round(Math.sqrt(
                dx * dx + Math.pow(pos.getY() + 0.5 - player.y, 2) + dz * dz));
        String direction = direction(dx, dz);

        String title = marker.displayName();
        String coordinates = EmeraldScoutConfig.isShowCoordinates()
                ? "X: " + pos.getX() + "  Y: " + pos.getY() + "  Z: " + pos.getZ()
                : "Y: " + pos.getY();
        String detail = distance + " blocks " + direction
                + "  " + marker.veinBlocks() + " block(s)"
                + "  " + marker.terrainStatus();
        int textColor = 0xFF000000
                | (Math.round(color[0] * 255.0f) << 16)
                | (Math.round(color[1] * 255.0f) << 8)
                | Math.round(color[2] * 255.0f);
        TextRenderer.TextLayerType layerType = EmeraldScoutConfig.isThroughWalls()
                ? TextRenderer.TextLayerType.SEE_THROUGH
                : TextRenderer.TextLayerType.NORMAL;

        matrices.push();
        matrices.translate(
                pos.getX() + 0.5 - camera.x,
                pos.getY() + 1.15 - camera.y,
                pos.getZ() + 0.5 - camera.z);
        matrices.multiply(cameraObject.getRotation());
        matrices.scale(-0.025f, -0.025f, 0.025f);

        float titleX = -textRenderer.getWidth(title) / 2.0f;
        float detailX = -textRenderer.getWidth(detail) / 2.0f;
        textRenderer.draw(
                title, titleX, -10.0f, textColor, true,
                matrices.peek().getPositionMatrix(), consumers,
                layerType, 0xA8000000, LightmapTextureManager.MAX_LIGHT_COORDINATE);
        textRenderer.draw(
                coordinates, -textRenderer.getWidth(coordinates) / 2.0f, 2.0f,
                0xFFF2F4F7, true, matrices.peek().getPositionMatrix(), consumers,
                layerType, 0xA8000000, LightmapTextureManager.MAX_LIGHT_COORDINATE);
        textRenderer.draw(
                detail, detailX, 14.0f, 0xFFD2D8DE, true,
                matrices.peek().getPositionMatrix(), consumers,
                layerType, 0xA8000000, LightmapTextureManager.MAX_LIGHT_COORDINATE);
        matrices.pop();
    }

    private static String direction(double dx, double dz) {
        double degrees = (Math.toDegrees(Math.atan2(dx, dz)) + 360.0) % 360.0;
        String[] directions = {"S", "SE", "E", "NE", "N", "NW", "W", "SW"};
        int index = (int) Math.round(degrees / 45.0) % 8;
        return directions[index];
    }

    private static float[] colorFor(PredictionMarker.Kind kind) {
        return switch (kind) {
            case CONFIRMED -> new float[]{0.12f, 1.00f, 0.30f};
            case REJECTED -> new float[]{1.00f, 0.20f, 0.20f};
            case PREDICTED -> new float[]{0.35f, 0.95f, 0.70f};
        };
    }
}
