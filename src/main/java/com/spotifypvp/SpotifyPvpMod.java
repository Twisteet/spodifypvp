package com.spotifypvp;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.net.URI;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class SpotifyPvpMod implements ClientModInitializer {
    public static final String MOD_ID = "spotifypvp";

    private static KeyBinding pauseKey;
    private static KeyBinding nextKey;
    private static KeyBinding previousKey;
    private static KeyBinding hudKey;

    @Override
    public void onInitializeClient() {
        Config.load();

        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
        pauseKey = register("pause", GLFW.GLFW_KEY_BACKSLASH, category);
        nextKey = register("next", GLFW.GLFW_KEY_RIGHT_BRACKET, category);
        previousKey = register("previous", GLFW.GLFW_KEY_LEFT_BRACKET, category);
        hudKey = register("hud", GLFW.GLFW_KEY_SEMICOLON, category);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (pauseKey.wasPressed()) {
                SpotifyApi.togglePlayPause();
            }
            while (nextKey.wasPressed()) {
                SpotifyApi.next();
            }
            while (previousKey.wasPressed()) {
                SpotifyApi.previous();
            }
            while (hudKey.wasPressed()) {
                Config.get().hudVisible = !Config.get().hudVisible;
                Config.save();
            }
        });

        HudElementRegistry.attachElementAfter(
                VanillaHudElements.MISC_OVERLAYS,
                Identifier.of(MOD_ID, "hud"),
                new SpotifyHud());

        registerCommands();
        SpotifyApi.start();
    }

    private static KeyBinding register(String name, int key, KeyBinding.Category category) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key." + MOD_ID + "." + name, InputUtil.Type.KEYSYM, key, category));
    }

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("spotify")
                        .then(literal("setup")
                                .then(argument("clientId", StringArgumentType.word())
                                        .executes(ctx -> {
                                            Config.get().clientId =
                                                    StringArgumentType.getString(ctx, "clientId");
                                            Config.save();
                                            chat("Client ID saved. Now run /spotify login");
                                            return 1;
                                        })))
                        .then(literal("login").executes(ctx -> {
                            startLogin();
                            return 1;
                        }))
                        .then(literal("logout").executes(ctx -> {
                            SpotifyAuth.logout();
                            chat("Logged out of Spotify.");
                            return 1;
                        }))
                        .then(literal("hud").executes(ctx -> {
                            Config.get().hudVisible = !Config.get().hudVisible;
                            Config.save();
                            return 1;
                        }))
                        .then(literal("pos")
                                .then(argument("x", IntegerArgumentType.integer(0))
                                        .then(argument("y", IntegerArgumentType.integer(0))
                                                .executes(ctx -> {
                                                    Config.get().hudX =
                                                            IntegerArgumentType.getInteger(ctx, "x");
                                                    Config.get().hudY =
                                                            IntegerArgumentType.getInteger(ctx, "y");
                                                    Config.save();
                                                    return 1;
                                                }))))));
    }

    private static void startLogin() {
        try {
            String url = SpotifyAuth.beginLogin(SpotifyPvpMod::chat);
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.keyboard.setClipboard(url);
            Text link = Text.literal("[Click here to log in to Spotify]")
                    .setStyle(Style.EMPTY
                            .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
                            .withColor(Formatting.GREEN)
                            .withUnderline(true));
            chatText(link);
            chat("(The link was also copied to your clipboard.)");
        } catch (IOException e) {
            chat(e.getMessage());
        }
    }

    /** Safe to call from any thread. */
    public static void chat(String message) {
        chatText(Text.literal("[Spotify PVP] " + message));
    }

    private static void chatText(Text text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendMessage(text, false);
            }
        });
    }
}
