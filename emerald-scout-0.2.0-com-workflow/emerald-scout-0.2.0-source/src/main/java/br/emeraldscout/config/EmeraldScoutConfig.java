package br.emeraldscout.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persistent client configuration stored in config/emeraldscout.json. */
public final class EmeraldScoutConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance()
            .getConfigDir().resolve("emeraldscout.json");

    private static Long seed;
    private static int radius = 16;

    // Block ESP defaults requested by the user.
    private static boolean espEnabled = true;
    private static boolean throughWalls = true;
    private static boolean showLabels = true;
    private static boolean showCoordinates = true;
    private static boolean showStats = true;
    private static int espDistance = 128;

    private EmeraldScoutConfig() {}

    public static synchronized void load() {
        if (!Files.exists(FILE)) {
            save();
            return;
        }

        try (Reader reader = Files.newBufferedReader(FILE)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            seed = json.has("seed") && !json.get("seed").isJsonNull()
                    ? json.get("seed").getAsLong() : null;
            radius = json.has("radius") ? json.get("radius").getAsInt() : 16;
            radius = Math.max(1, Math.min(128, radius));

            espEnabled = getBoolean(json, "espEnabled", true);
            throughWalls = getBoolean(json, "throughWalls", true);
            showLabels = getBoolean(json, "showLabels", true);
            showCoordinates = getBoolean(json, "showCoordinates", true);
            showStats = getBoolean(json, "showStats", true);
            espDistance = json.has("espDistance")
                    ? json.get("espDistance").getAsInt() : 128;
            espDistance = Math.max(16, Math.min(512, espDistance));
        } catch (Exception exception) {
            seed = null;
            radius = 16;
            espEnabled = true;
            throughWalls = true;
            showLabels = true;
            showCoordinates = true;
            showStats = true;
            espDistance = 128;
            System.err.println("[EmeraldScout] Could not read config: " + exception.getMessage());
        }
    }

    private static boolean getBoolean(JsonObject json, String key, boolean fallback) {
        return json.has(key) && !json.get(key).isJsonNull()
                ? json.get(key).getAsBoolean() : fallback;
    }

    public static synchronized void save() {
        JsonObject json = new JsonObject();
        if (seed == null) json.add("seed", null);
        else json.addProperty("seed", seed);
        json.addProperty("radius", radius);
        json.addProperty("espEnabled", espEnabled);
        json.addProperty("throughWalls", throughWalls);
        json.addProperty("showLabels", showLabels);
        json.addProperty("showCoordinates", showCoordinates);
        json.addProperty("showStats", showStats);
        json.addProperty("espDistance", espDistance);

        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GSON.toJson(json, writer);
            }
        } catch (IOException exception) {
            System.err.println("[EmeraldScout] Could not save config: " + exception.getMessage());
        }
    }

    public static synchronized Long getSeed() { return seed; }

    public static synchronized void setSeed(Long value) {
        seed = value;
        save();
    }

    public static synchronized int getRadius() { return radius; }

    public static synchronized void setRadius(int value) {
        radius = Math.max(1, Math.min(128, value));
        save();
    }

    public static synchronized boolean isEspEnabled() { return espEnabled; }

    public static synchronized void setEspEnabled(boolean value) {
        espEnabled = value;
        save();
    }

    public static synchronized boolean isThroughWalls() { return throughWalls; }

    public static synchronized void setThroughWalls(boolean value) {
        throughWalls = value;
        save();
    }

    public static synchronized boolean isShowLabels() { return showLabels; }

    public static synchronized void setShowLabels(boolean value) {
        showLabels = value;
        save();
    }

    public static synchronized boolean isShowCoordinates() { return showCoordinates; }

    public static synchronized void setShowCoordinates(boolean value) {
        showCoordinates = value;
        save();
    }

    public static synchronized boolean isShowStats() { return showStats; }

    public static synchronized void setShowStats(boolean value) {
        showStats = value;
        save();
    }

    public static synchronized int getEspDistance() { return espDistance; }

    public static synchronized void setEspDistance(int value) {
        espDistance = Math.max(16, Math.min(512, value));
        save();
    }
}
