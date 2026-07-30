package net.townymap.input;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import net.townymap.TownyMapMod;
import org.lwjgl.glfw.GLFW;

public final class TownyMapKeybinds {

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("townymapaddon", "keybinds"));

    private static KeyBinding toggleSquaremap;
    private static KeyBinding cycleBorders;
    private static KeyBinding cycleMapMode;
    private static KeyBinding toggleChunkCounter;
    private static KeyBinding refreshTowns;
    private static KeyBinding mapScreenshot;

    private TownyMapKeybinds() {
    }

    public static void register() {
        toggleSquaremap = register("toggle_squaremap");
        cycleBorders = register("cycle_borders");
        cycleMapMode = register("cycle_map_mode");
        toggleChunkCounter = register("toggle_chunk_counter");
        refreshTowns = register("refresh_towns");
        mapScreenshot = register("map_screenshot", GLFW.GLFW_KEY_P);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleSquaremap.wasPressed()) TownyMapMod.toggleSquaremapBackground();
            while (cycleBorders.wasPressed()) TownyMapMod.cycleBorderOverlayMode();
            while (cycleMapMode.wasPressed()) TownyMapMod.cycleTownStatusOverlayMode();
            while (toggleChunkCounter.wasPressed()) TownyMapMod.toggleChunkCounter();
            while (refreshTowns.wasPressed()) TownyMapMod.refreshTownClaimsFromKeybind();
            // Only meaningful with the world map open — the countdown advances in its render. Arming with
            // it closed used to leave the "hide our UI" flag set indefinitely.
            while (mapScreenshot.wasPressed()) {
                if (TownyMapMod.isWorldMapOpen()) TownyMapMod.armMapScreenshot();
            }
            // The capture itself waits for a frame drawn without our chrome; see TownyMapMod.
            TownyMapMod.captureMapScreenshotIfReady();
        });
    }

    /** True if this key press matches the (rebindable) clean-screenshot bind, for use inside map screens. */
    public static boolean isMapScreenshotKey(net.minecraft.client.input.KeyInput input) {
        return mapScreenshot != null && !mapScreenshot.isUnbound() && mapScreenshot.matchesKey(input);
    }

    private static KeyBinding register(String id) {
        return register(id, GLFW.GLFW_KEY_UNKNOWN);
    }

    private static KeyBinding register(String id, int defaultKey) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.townymapaddon." + id,
                InputUtil.Type.KEYSYM,
                defaultKey,
                CATEGORY
        ));
    }

    /** The screenshot bind's current key, for the settings row. */
    public static KeyBinding mapScreenshotBinding() {
        return mapScreenshot;
    }

    /** Display name of the bound key, e.g. "P" or "Not bound". */
    public static String mapScreenshotKeyName() {
        if (mapScreenshot == null) return "—";
        return mapScreenshot.isUnbound()
                ? "Not bound"
                : mapScreenshot.getBoundKeyLocalizedText().getString();
    }

    /**
     * Rebinds the screenshot key from our own settings screen.
     *
     * <p>Writes through to the same KeyBinding vanilla's Controls screen edits and saves options, so the two
     * always agree — this is a second door onto one setting, not a copy of it.
     */
    public static void setMapScreenshotKey(int keyCode) {
        if (mapScreenshot == null) return;
        mapScreenshot.setBoundKey(keyCode == GLFW.GLFW_KEY_UNKNOWN
                ? InputUtil.UNKNOWN_KEY
                : InputUtil.Type.KEYSYM.createFromCode(keyCode));
        KeyBinding.updateKeysByCode();
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc != null && mc.options != null) mc.options.write();
    }
}
