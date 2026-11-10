package com.spotifypvp;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

/** Small overlay: song, artist, play state, time left and a progress bar. */
public final class SpotifyHud implements HudElement {
    private static final int WIDTH = 150;
    private static final int PAD = 4;
    private static final int BACKGROUND = 0x99000000;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFB3B3B3;
    private static final int RED = 0xFFFF5555;
    private static final int GREEN = 0xFF1DB954;
    private static final int BAR_BACK = 0xFF404040;

    @Override
    public void render(DrawContext ctx, RenderTickCounter tickCounter) {
        Config cfg = Config.get();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!cfg.hudVisible || mc.options.hudHidden) {
            return;
        }
        TextRenderer tr = mc.textRenderer;
        int x = cfg.hudX;
        int y = cfg.hudY;
        int inner = WIDTH - PAD * 2;

        SpotifyApi.Track track = SpotifyApi.current();
        String flash = SpotifyApi.flash();

        if (track == null) {
            ctx.fill(x, y, x + WIDTH, y + 18, BACKGROUND);
            String message = flash != null ? flash : SpotifyApi.status();
            ctx.drawTextWithShadow(tr, tr.trimToWidth(message, inner), x + PAD, y + 5, GRAY);
            return;
        }

        ctx.fill(x, y, x + WIDTH, y + 41, BACKGROUND);

        // Line 1: song title, line 2: artist
        ctx.drawTextWithShadow(tr, tr.trimToWidth(track.title(), inner), x + PAD, y + PAD, WHITE);
        ctx.drawTextWithShadow(tr, tr.trimToWidth(track.artist(), inner),
                x + PAD, y + PAD + 10, GRAY);

        // Line 3: play state (left) and time left (right)
        int lineY = y + PAD + 20;
        String left = flash != null ? flash : (track.playing() ? "Playing" : "Paused");
        ctx.drawTextWithShadow(tr, tr.trimToWidth(left, inner - 40),
                x + PAD, lineY, flash != null ? RED : GREEN);
        String remaining = "-" + formatTime(track.remainingMs());
        ctx.drawTextWithShadow(tr, remaining,
                x + WIDTH - PAD - tr.getWidth(remaining), lineY, WHITE);

        // Progress bar
        int barY = y + PAD + 31;
        float fraction = track.durationMs() <= 0
                ? 0f : (float) track.progressNow() / (float) track.durationMs();
        int filled = Math.round(inner * Math.max(0f, Math.min(1f, fraction)));
        ctx.fill(x + PAD, barY, x + PAD + inner, barY + 2, BAR_BACK);
        ctx.fill(x + PAD, barY, x + PAD + filled, barY + 2, GREEN);
    }

    private static String formatTime(long ms) {
        long totalSeconds = ms / 1000;
        return (totalSeconds / 60) + ":" + String.format("%02d", totalSeconds % 60);
    }
}
