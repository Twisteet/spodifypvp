package com.spotifypvp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Settings and login tokens, saved to .minecraft/config/spotifypvp.json */
public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE =
            FabricLoader.getInstance().getConfigDir().resolve("spotifypvp.json");
    private static Config instance = new Config();

    public String clientId = "";
    public String accessToken = "";
    public String refreshToken = "";
    public long expiresAtMs = 0;
    public int callbackPort = 8888;
    public boolean hudVisible = true;
    public int hudX = 6;
    public int hudY = 6;

    public static Config get() {
        return instance;
    }

    public static void load() {
        try {
            if (Files.exists(FILE)) {
                Config loaded = GSON.fromJson(Files.readString(FILE), Config.class);
                if (loaded != null) {
                    instance = loaded;
                }
            }
        } catch (Exception e) {
            System.err.println("[SpotifyPVP] Could not read config: " + e);
        }
        save();
    }

    public static synchronized void save() {
        try {
            Files.writeString(FILE, GSON.toJson(instance));
        } catch (IOException e) {
            System.err.println("[SpotifyPVP] Could not save config: " + e);
        }
    }
}
