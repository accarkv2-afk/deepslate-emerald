package br.emeraldscout.render;

import br.emeraldscout.config.EmeraldScoutConfig;
import br.emeraldscout.prediction.PredictionStore;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/** Compact diagnostics panel styled after the supplied reference clip. */
public final class EspStatsHud {
    private EspStatsHud() {}

    public static void register() {
        HudRenderCallback.EVENT.register(EspStatsHud::render);
    }

    private static void render(DrawContext context, net.minecraft.client.render.RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || !EmeraldScoutConfig.isShowStats()) return;

        PredictionStore.Snapshot snapshot = PredictionStore.snapshot();
        int panelWidth = 242;
        int panelHeight = 92;
        int x = 8;
        int y = client.getWindow().getScaledHeight() - panelHeight - 10;

        context.fill(x, y, x + panelWidth, y + panelHeight, 0xB810141B);
        context.fill(x, y, x + panelWidth, y + 2, 0xFF63D7A4);

        context.drawTextWithShadow(client.textRenderer,
                Text.literal("EMERALDSCOUT  /  BLOCK ESP"), x + 8, y + 7, 0xFF72E3B2);
        context.drawTextWithShadow(client.textRenderer,
                Text.literal("Veins marked: " + snapshot.uniqueVeins()), x + 8, y + 22, 0xFFE8EDF2);
        context.drawTextWithShadow(client.textRenderer,
                Text.literal("Predicted blocks: " + snapshot.predictedBlocks()), x + 8, y + 34, 0xFF8FE7C5);
        context.drawTextWithShadow(client.textRenderer,
                Text.literal("Confirmed: " + snapshot.confirmedBlocks()
                        + "   Rejected: " + snapshot.rejectedBlocks()), x + 8, y + 46, 0xFFE8EDF2);
        context.drawTextWithShadow(client.textRenderer,
                Text.literal("ESP: " + (EmeraldScoutConfig.isEspEnabled() ? "ON" : "OFF")
                        + "  Range: " + EmeraldScoutConfig.getEspDistance() + " blocks"),
                x + 8, y + 58, 0xFFB7C3CF);

        String diagnostic = PredictionStore.diagnostic();
        if (diagnostic.length() > 34) diagnostic = diagnostic.substring(0, 31) + "...";
        context.drawTextWithShadow(client.textRenderer,
                Text.literal(diagnostic), x + 8, y + 73, 0xFFFFD27A);

        String status = "BlockESP " + snapshot.markers().size();
        int statusWidth = client.textRenderer.getWidth(status);
        int right = client.getWindow().getScaledWidth() - 8;
        context.fill(right - statusWidth - 16, 8, right, 24, 0xA810141B);
        context.drawTextWithShadow(client.textRenderer,
                Text.literal(status), right - statusWidth - 8, 12,
                EmeraldScoutConfig.isEspEnabled() ? 0xFF72E3B2 : 0xFFAAAAAA);
    }
}
