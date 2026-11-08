package br.emeraldscout.gui;

import br.emeraldscout.config.EmeraldScoutConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** Right Shift control panel for the Block ESP. */
public final class EmeraldScoutMenuScreen extends Screen {
    private final Screen parent;
    private ButtonWidget espButton;
    private ButtonWidget wallsButton;
    private ButtonWidget labelsButton;
    private ButtonWidget coordinatesButton;
    private ButtonWidget statsButton;
    private ButtonWidget distanceButton;

    public EmeraldScoutMenuScreen(Screen parent) {
        super(Text.literal("EmeraldScout"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int startY = height / 2 - 82;
        int buttonWidth = 230;
        int buttonX = centerX - buttonWidth / 2;
        int spacing = 25;

        espButton = addDrawableChild(ButtonWidget.builder(espText(), button -> {
            EmeraldScoutConfig.setEspEnabled(!EmeraldScoutConfig.isEspEnabled());
            button.setMessage(espText());
        }).dimensions(buttonX, startY, buttonWidth, 20).build());

        wallsButton = addDrawableChild(ButtonWidget.builder(wallsText(), button -> {
            EmeraldScoutConfig.setThroughWalls(!EmeraldScoutConfig.isThroughWalls());
            button.setMessage(wallsText());
        }).dimensions(buttonX, startY + spacing, buttonWidth, 20).build());

        labelsButton = addDrawableChild(ButtonWidget.builder(labelsText(), button -> {
            EmeraldScoutConfig.setShowLabels(!EmeraldScoutConfig.isShowLabels());
            button.setMessage(labelsText());
        }).dimensions(buttonX, startY + spacing * 2, buttonWidth, 20).build());

        coordinatesButton = addDrawableChild(ButtonWidget.builder(coordinatesText(), button -> {
            EmeraldScoutConfig.setShowCoordinates(!EmeraldScoutConfig.isShowCoordinates());
            button.setMessage(coordinatesText());
        }).dimensions(buttonX, startY + spacing * 3, buttonWidth, 20).build());

        statsButton = addDrawableChild(ButtonWidget.builder(statsText(), button -> {
            EmeraldScoutConfig.setShowStats(!EmeraldScoutConfig.isShowStats());
            button.setMessage(statsText());
        }).dimensions(buttonX, startY + spacing * 4, buttonWidth, 20).build());

        distanceButton = addDrawableChild(ButtonWidget.builder(distanceText(), button -> {
            int[] values = {64, 128, 192, 256, 384, 512};
            int current = EmeraldScoutConfig.getEspDistance();
            int next = values[0];
            for (int i = 0; i < values.length; i++) {
                if (values[i] == current) {
                    next = values[(i + 1) % values.length];
                    break;
                }
                if (values[i] > current) {
                    next = values[i];
                    break;
                }
            }
            EmeraldScoutConfig.setEspDistance(next);
            button.setMessage(distanceText());
        }).dimensions(buttonX, startY + spacing * 5, buttonWidth, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), button -> close())
                .dimensions(buttonX, startY + spacing * 6 + 5, buttonWidth, 20).build());
    }

    private static Text espText() {
        return Text.literal("Block ESP: " + onOff(EmeraldScoutConfig.isEspEnabled()));
    }

    private static Text wallsText() {
        return Text.literal("Through walls: " + onOff(EmeraldScoutConfig.isThroughWalls()));
    }

    private static Text labelsText() {
        return Text.literal("Floating labels: " + onOff(EmeraldScoutConfig.isShowLabels()));
    }

    private static Text coordinatesText() {
        return Text.literal("Coordinates X/Y/Z: " + onOff(EmeraldScoutConfig.isShowCoordinates()));
    }

    private static Text statsText() {
        return Text.literal("Statistics panel: " + onOff(EmeraldScoutConfig.isShowStats()));
    }

    private static Text distanceText() {
        return Text.literal("ESP distance: " + EmeraldScoutConfig.getEspDistance() + " blocks");
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        int centerX = width / 2;
        int panelWidth = 286;
        int panelTop = height / 2 - 111;
        context.fill(centerX - panelWidth / 2, panelTop,
                centerX + panelWidth / 2, panelTop + 226, 0xE610151D);
        context.fill(centerX - panelWidth / 2, panelTop,
                centerX + panelWidth / 2, panelTop + 2, 0xFF63D7A4);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.literal("EMERALDSCOUT"), centerX, panelTop + 12, 0xFF72E3B2);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.literal("BLOCK ESP CONTROL PANEL"), centerX, panelTop + 27, 0xFFAAB6C4);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
