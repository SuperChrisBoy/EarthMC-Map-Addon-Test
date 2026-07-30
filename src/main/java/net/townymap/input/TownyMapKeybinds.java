package net.townymap.input;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.resources.Identifier;
import net.townymap.TownyMapMod;
import org.lwjgl.glfw.GLFW;

public final class TownyMapKeybinds {

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("townymapaddon", "keybinds"));

    private static KeyMapping toggleSquaremap;
    private static KeyMapping cycleBorders;
    private static KeyMapping cycleMapMode;
    private static KeyMapping toggleChunkCounter;
    private static KeyMapping refreshTowns;
    private static KeyMapping mapScreenshot;

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
            while (toggleSquaremap.consumeClick()) TownyMapMod.toggleSquaremapBackground();
            while (cycleBorders.consumeClick()) TownyMapMod.cycleBorderOverlayMode();
            while (cycleMapMode.consumeClick()) TownyMapMod.cycleTownStatusOverlayMode();
            while (toggleChunkCounter.consumeClick()) TownyMapMod.toggleChunkCounter();
            while (refreshTowns.consumeClick()) TownyMapMod.refreshTownClaimsFromKeybind();
            // Only meaningful with the world map open — the countdown advances in its render. Arming with
            // it closed used to leave the "hide our UI" flag set indefinitely.
            while (mapScreenshot.consumeClick()) {
                if (TownyMapMod.isWorldMapOpen()) TownyMapMod.armMapScreenshot();
            }
            // The capture itself waits for a frame drawn without our chrome; see TownyMapMod.
            TownyMapMod.captureMapScreenshotIfReady();
        });
    }

    /** True if this key press matches the (rebindable) clean-screenshot bind, for use inside map screens. */
    public static boolean isMapScreenshotKey(net.minecraft.client.input.KeyEvent input) {
        return mapScreenshot != null && !mapScreenshot.isUnbound() && mapScreenshot.matches(input);
    }

    private static KeyMapping register(String id) {
        return register(id, GLFW.GLFW_KEY_UNKNOWN);
    }

    private static KeyMapping register(String id, int defaultKey) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.townymapaddon." + id,
                InputConstants.Type.KEYSYM,
                defaultKey,
                CATEGORY
        ));
    }

    /** The screenshot bind's current key, for the settings row. */
    public static KeyMapping mapScreenshotBinding() {
        return mapScreenshot;
    }

    /** Display name of the bound key, e.g. "P" or "Not bound". */
    public static String mapScreenshotKeyName() {
        if (mapScreenshot == null) return "—";
        return mapScreenshot.isUnbound()
                ? "Not bound"
                : mapScreenshot.getTranslatedKeyMessage().getString();
    }

    /**
     * Rebinds the screenshot key from our own settings screen.
     *
     * <p>Writes through to the same KeyMapping vanilla's Controls screen edits and saves options, so the two
     * always agree — this is a second door onto one setting, not a copy of it.
     */
    public static void setMapScreenshotKey(int keyCode) {
        if (mapScreenshot == null) return;
        mapScreenshot.setKey(keyCode == GLFW.GLFW_KEY_UNKNOWN
                ? InputConstants.UNKNOWN
                : InputConstants.Type.KEYSYM.getOrCreate(keyCode));
        KeyMapping.resetMapping();
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.options != null) mc.options.save();
    }
}
