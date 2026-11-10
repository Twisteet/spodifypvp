package com.spotifypvp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Talks to the Spotify Web API on a background thread so the game never lags. */
public final class SpotifyApi {

    /** What is playing right now. Time is estimated locally between polls. */
    public record Track(String title, String artist, long durationMs,
                        long progressMs, boolean playing, long fetchedAtMs) {

        public long progressNow() {
            if (!playing) {
                return Math.min(progressMs, durationMs);
            }
            long elapsed = System.currentTimeMillis() - fetchedAtMs;
            return Math.min(durationMs, progressMs + elapsed);
        }

        public long remainingMs() {
            return Math.max(0, durationMs - progressNow());
        }

        public Track withPlaying(boolean nowPlaying) {
            return new Track(title, artist, durationMs, progressNow(),
                    nowPlaying, System.currentTimeMillis());
        }
    }

    private static final String BASE = "https://api.spotify.com/v1/me/player";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ScheduledExecutorService EXEC =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "SpotifyPVP-Api");
                t.setDaemon(true);
                return t;
            });

    private static volatile Track current = null;
    private static volatile String status = "Run /spotify setup <clientId>";
    private static volatile String flash = null;
    private static volatile long flashUntil = 0;
    private static volatile long backoffUntil = 0;
    private static boolean started = false;

    private SpotifyApi() {}

    public static synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        EXEC.scheduleWithFixedDelay(SpotifyApi::pollSafe, 1, 3, TimeUnit.SECONDS);
    }

    public static Track current() {
        return current;
    }

    public static String status() {
        return status;
    }

    /** A short-lived error message (like "Premium required"), or null. */
    public static String flash() {
        return System.currentTimeMillis() < flashUntil ? flash : null;
    }

    // ---------------------------------------------------------------- controls

    public static void togglePlayPause() {
        Track t = current;
        if (t != null && t.playing()) {
            current = t.withPlaying(false);
            control("PUT", "/pause");
        } else {
            if (t != null) {
                current = t.withPlaying(true);
            }
            control("PUT", "/play");
        }
    }

    public static void next() {
        control("POST", "/next");
    }

    public static void previous() {
        control("POST", "/previous");
    }

    private static void control(String method, String path) {
        EXEC.execute(() -> {
            try {
                String token = SpotifyAuth.getValidToken();
                if (token == null) {
                    showFlash("Run /spotify login");
                    return;
                }
                HttpResponse<String> res =
                        HTTP.send(request(token, method, path), HttpResponse.BodyHandlers.ofString());
                int code = res.statusCode();
                if (code == 403) {
                    showFlash("Spotify Premium is required to control playback");
                } else if (code == 404) {
                    showFlash("No active device - press play in Spotify once");
                } else if (code >= 400) {
                    showFlash("Spotify error " + code);
                }
            } catch (Exception e) {
                showFlash("Network error");
            }
            EXEC.schedule(SpotifyApi::pollSafe, 500, TimeUnit.MILLISECONDS);
        });
    }

    private static void showFlash(String message) {
        flash = message;
        flashUntil = System.currentTimeMillis() + 4000;
    }

    // ---------------------------------------------------------------- polling

    private static void pollSafe() {
        try {
            poll();
        } catch (Throwable t) {
            status = "Network error";
        }
    }

    private static void poll() throws Exception {
        if (System.currentTimeMillis() < backoffUntil) {
            return;
        }
        String token = SpotifyAuth.getValidToken();
        if (token == null) {
            current = null;
            status = Config.get().clientId.isBlank()
                    ? "Run /spotify setup <clientId>" : "Run /spotify login";
            return;
        }
        HttpResponse<String> res = HTTP.send(
                request(token, "GET", "/currently-playing"), HttpResponse.BodyHandlers.ofString());
        switch (res.statusCode()) {
            case 200 -> parse(res.body());
            case 204 -> {
                current = null;
                status = "Nothing playing - start Spotify";
            }
            case 401 -> {
                SpotifyAuth.expireToken();
                status = "Refreshing login...";
            }
            case 429 -> {
                long wait = res.headers().firstValueAsLong("Retry-After").orElse(5);
                backoffUntil = System.currentTimeMillis() + wait * 1000L;
                status = "Rate limited - waiting";
            }
            default -> status = "Spotify error " + res.statusCode();
        }
    }

    private static void parse(String body) {
        if (body == null || body.isBlank()) {
            current = null;
            status = "Nothing playing - start Spotify";
            return;
        }
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonElement itemElement = root.get("item");
        if (itemElement == null || itemElement.isJsonNull()) {
            current = null;
            status = "Ad or unknown track";
            return;
        }
        JsonObject item = itemElement.getAsJsonObject();

        String title = item.get("name").getAsString();
        long duration = item.get("duration_ms").getAsLong();
        long progress = longOr(root, "progress_ms", 0);
        boolean playing = root.has("is_playing") && root.get("is_playing").getAsBoolean();

        StringBuilder artists = new StringBuilder();
        JsonArray array = item.has("artists") ? item.getAsJsonArray("artists") : null;
        if (array != null) {
            for (JsonElement a : array) {
                if (artists.length() > 0) {
                    artists.append(", ");
                }
                artists.append(a.getAsJsonObject().get("name").getAsString());
            }
        } else if (item.has("show") && item.get("show").isJsonObject()) {
            artists.append(item.getAsJsonObject("show").get("name").getAsString());
        }

        current = new Track(title, artists.toString(), duration, progress,
                playing, System.currentTimeMillis());
        status = "";
    }

    private static long longOr(JsonObject obj, String key, long fallback) {
        JsonElement e = obj.get(key);
        return (e == null || e.isJsonNull()) ? fallback : e.getAsLong();
    }

    private static HttpRequest request(String token, String method, String path) {
        return HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(8))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
    }
}
