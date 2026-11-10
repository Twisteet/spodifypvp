package com.spotifypvp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Spotify login using the PKCE flow (no client secret needed). */
public final class SpotifyAuth {
    private static final String SCOPES =
            "user-read-playback-state user-modify-playback-state user-read-currently-playing";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile boolean loginRunning = false;

    private SpotifyAuth() {}

    public static String redirectUri() {
        return "http://127.0.0.1:" + Config.get().callbackPort + "/callback";
    }

    /** Opens a one-shot local web server and returns the Spotify login URL. */
    public static synchronized String beginLogin(Consumer<String> onResult) throws IOException {
        Config cfg = Config.get();
        if (cfg.clientId.isBlank()) {
            throw new IOException("No Client ID yet. Run /spotify setup <clientId> first.");
        }
        if (loginRunning) {
            throw new IOException("A login is already in progress.");
        }

        String verifier = randomUrlSafe(64);
        String challenge = challengeFor(verifier);
        String state = randomUrlSafe(16);

        ServerSocket server =
                new ServerSocket(cfg.callbackPort, 1, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(1000);
        loginRunning = true;

        Thread thread = new Thread(() -> {
            try (server) {
                long deadline = System.currentTimeMillis() + 180_000;
                while (System.currentTimeMillis() < deadline) {
                    Socket socket;
                    try {
                        socket = server.accept();
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    try (socket) {
                        String code = handleRequest(socket, state);
                        if (code != null) {
                            exchangeCode(code, verifier);
                            onResult.accept("Logged in to Spotify!");
                            return;
                        }
                    }
                }
                onResult.accept("Login timed out. Run /spotify login again.");
            } catch (Exception e) {
                onResult.accept("Login failed: " + e.getMessage());
            } finally {
                loginRunning = false;
            }
        }, "SpotifyPVP-Auth");
        thread.setDaemon(true);
        thread.start();

        return "https://accounts.spotify.com/authorize?response_type=code"
                + "&client_id=" + enc(cfg.clientId)
                + "&scope=" + enc(SCOPES)
                + "&redirect_uri=" + enc(redirectUri())
                + "&code_challenge_method=S256"
                + "&code_challenge=" + challenge
                + "&state=" + state;
    }

    /** Returns a valid access token (refreshing it if needed) or null if not logged in. */
    public static synchronized String getValidToken() {
        Config cfg = Config.get();
        if (cfg.refreshToken.isEmpty()) {
            return null;
        }
        if (cfg.accessToken.isEmpty() || System.currentTimeMillis() > cfg.expiresAtMs - 30_000) {
            try {
                Map<String, String> form = new LinkedHashMap<>();
                form.put("grant_type", "refresh_token");
                form.put("refresh_token", cfg.refreshToken);
                form.put("client_id", cfg.clientId);
                saveTokens(postForm(form));
            } catch (Exception e) {
                return null;
            }
        }
        return cfg.accessToken;
    }

    /** Forces the next getValidToken() call to refresh. */
    public static synchronized void expireToken() {
        Config.get().expiresAtMs = 0;
    }

    public static synchronized void logout() {
        Config cfg = Config.get();
        cfg.accessToken = "";
        cfg.refreshToken = "";
        cfg.expiresAtMs = 0;
        Config.save();
    }

    // ---------------------------------------------------------------- helpers

    private static void exchangeCode(String code, String verifier) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri());
        form.put("client_id", Config.get().clientId);
        form.put("code_verifier", verifier);
        saveTokens(postForm(form));
    }

    private static void saveTokens(JsonObject json) {
        Config cfg = Config.get();
        cfg.accessToken = json.get("access_token").getAsString();
        cfg.expiresAtMs = System.currentTimeMillis() + json.get("expires_in").getAsLong() * 1000L;
        if (json.has("refresh_token") && !json.get("refresh_token").isJsonNull()) {
            cfg.refreshToken = json.get("refresh_token").getAsString();
        }
        Config.save();
    }

    private static JsonObject postForm(Map<String, String> form) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Spotify said " + response.statusCode() + ": " + response.body());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    /** Reads one HTTP request. Returns the auth code if this was the /callback request. */
    private static String handleRequest(Socket socket, String expectedState) throws IOException {
        socket.setSoTimeout(5000);
        BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        String requestLine = in.readLine();
        if (requestLine == null) {
            return null;
        }
        String header;
        while ((header = in.readLine()) != null && !header.isEmpty()) {
            // skip headers
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 2 || !parts[1].startsWith("/callback")) {
            respond(socket, 404, "Not found");
            return null;
        }
        Map<String, String> query = parseQuery(parts[1]);
        if (query.containsKey("error")) {
            respond(socket, 400, "Spotify login was cancelled. You can close this tab.");
            throw new IOException(query.get("error"));
        }
        if (!expectedState.equals(query.get("state")) || query.get("code") == null) {
            respond(socket, 400, "Login check failed. Run /spotify login again.");
            throw new IOException("State mismatch");
        }
        respond(socket, 200, "Spotify connected! You can close this tab and go back to Minecraft.");
        return query.get("code");
    }

    private static Map<String, String> parseQuery(String target) {
        Map<String, String> map = new HashMap<>();
        int q = target.indexOf('?');
        if (q < 0) {
            return map;
        }
        for (String pair : target.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                map.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    private static void respond(Socket socket, int status, String message) throws IOException {
        byte[] body = ("<html><body style='font-family:sans-serif;text-align:center;"
                + "margin-top:20vh'><h2>" + message + "</h2></body></html>")
                .getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Error") + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static String randomUrlSafe(int byteCount) {
        byte[] bytes = new byte[byteCount];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String challengeFor(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
