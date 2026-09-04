package net.townymap.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.townymap.TownyMapConfig;
import net.townymap.TownyMapMod;

public final class MapToggleOverlay {

    private static final int WIDTH = 92;
    private static final int HEIGHT = 20;
    private static final int GAP = 3;
    private static final int LEFT = 8;
    private static final int RESET_GAP = 3;
    private static final int RESET_WIDTH = 44;
    private static final int GROUP_GAP = 3;
    private static final int GROUP_WIDTH = 18;
    private static final int GROUP_LEFT = 40;
    private static final int GROUP_TOP = 8;
    private static final int ADD_WIDTH = 20;
    private static final int FILL_WIDTH = 34;
    private static final int SETTINGS_GAP = 7;   // extra gap above the settings button
    private static final int TOGGLE_ROWS = 8;    // Squaremap | Borders | Map mode | World | Chunks | Counter | Ice roads | Planner

    private MapToggleOverlay() {}

    public static void render(GuiGraphicsExtractor ctx, int sh, TownyMapConfig config,
                              boolean squaremapLoading, boolean bordersLoading, boolean earthMcActive) {
        boolean scaled = UiScale.active();
        if (scaled) UiScale.push(ctx, LEFT, togglesTop(sh));   // shrink the button column around its top-left
        try {
        Font tr = Minecraft.getInstance().font;
        int y = togglesTop(sh);

        if(earthMcActive){drawToggle(ctx, tr, 0, y, tr(squaremapLoading ? "squaremap_loading" : "squaremap"), config.squaremapOnWorldMap());
        drawMode(ctx, tr, 1, y, tr(bordersLoading ? "borders_loading" : "borders"), borderModeLabel(config.borderOverlayMode),
                config.borderOverlayMode != 0);
        drawMode(ctx, tr, 2, y, tr("map"), statusModeLabel(config.townStatusOverlayMode),
                config.townStatusOverlayMode != 0);
        drawMode(ctx, tr, 3, y, tr("world"), worldModeLabel(config),
                !TownyMapMod.viewingEarth() || config.mapWorldMode != TownyMapMod.WORLD_MODE_AUTO);
        drawToggle(ctx, tr, 4, y, tr("chunks"), config.chunkGridEnabled);
        drawMode(ctx, tr, 5, y, tr("counter"), ChunkCounterOverlay.toolbarLabel(config), config.chunkCounterEnabled);
        drawToggle(ctx, tr, 6, y, tr("ice_roads"), config.iceRoadOverlayEnabled);
        drawToggle(ctx, tr, 7, y, "Ice Planner", IceRoadPlannerOverlay.active());
        if (config.chunkCounterEnabled) {
            if (ChunkCounterOverlay.isMultiMode(config)) {
                drawCounterGroupButtons(ctx, tr, config);
            }
            drawCounterResetButton(ctx, tr, y);
        }}

        drawSettingsButton(ctx, tr, settingsTop(sh));
        if(TownyMapMod.isTeleportFeatureAvailable())drawTexturedButton(ctx,LEFT,teleportTop(sh),WIDTH,HEIGHT,Component.translatable("townymapaddon.teleport.title").getString(),true,0xFF7EE2B8);
        } finally {
            if (scaled) UiScale.pop(ctx);
        }
    }

    /** Returns true if a toggle was clicked (caller should NOT open settings). */
    public static boolean handleClick(double mouseX, double mouseY, int sh, TownyMapConfig config, boolean backward) {
        if (UiScale.active()) { mouseX = UiScale.unscale(mouseX, LEFT); mouseY = UiScale.unscale(mouseY, togglesTop(sh)); }
        if (ChunkCounterOverlay.isMultiMode(config)) {
            int group = counterGroupAt(mouseX, mouseY, config);
            if (group >= 0) {
                ChunkCounterOverlay.setActiveGroup(config, group);
                return true;
            }
            if (insideCounterAdd(mouseX, mouseY, config)) {
                ChunkCounterOverlay.addGroup(config);
                return true;
            }
            if (insideCounterFill(mouseX, mouseY, config)) {
                ChunkCounterOverlay.toggleFillEnclosed(config);
                return true;
            }
        }
        if (config.chunkCounterEnabled && insideCounterReset(mouseX, mouseY, sh)) {
            ChunkCounterOverlay.clearActive(config);
            return true;
        }
        if (mouseX < LEFT || mouseX > LEFT + WIDTH) return false;

        int row = toggleRowAt(mouseY, sh);
        if (row >= 0) {
            switch (row) {
                case 0 -> TownyMapMod.setSquaremapOnWorldMap(!config.squaremapOnWorldMap());
                case 1 -> config.borderOverlayMode = (config.borderOverlayMode + (backward ? 2 : 1)) % 3;
                case 2 -> {
                    int before = config.townStatusOverlayMode;
                    config.townStatusOverlayMode = TownyMapMod.nextStatusMode(before, backward);
                    TownSearchOverlay.onStatusModeChanged(before, config.townStatusOverlayMode);
                }
                // Auto -> Terra Nostra -> Moon -> Auto. Clicking at all is the override; coming back
                // round to Auto is how the following is resumed.
                case 3 -> config.mapWorldMode = (config.mapWorldMode + (backward ? 2 : 1)) % 3;
                case 4 -> config.chunkGridEnabled = !config.chunkGridEnabled;
                case 5 -> {
                    if (config.chunkCounterEnabled) ChunkCounterOverlay.flushSelection();
                    if (!config.chunkCounterEnabled) {
                        config.chunkCounterEnabled = true;
                        config.chunkCounterMode = 2;
                        ChunkCounterOverlay.prepareMultiMode(config);
                    } else {
                        config.chunkCounterEnabled = false;
                        config.chunkCounterMode = 2;
                    }
                }
                case 6 -> {if(backward){config.iceRoadOverlayEnabled=true;config.iceRoadStationFilter=(config.iceRoadStationFilter+1)%3;}else config.iceRoadOverlayEnabled=!config.iceRoadOverlayEnabled;}
                case 7 -> IceRoadPlannerOverlay.toggle();
                default -> { return false; }
            }
            config.save();
            return true;
        }
        return false;
    }

    /** Returns true if the ⚙ Settings button was clicked. */
    public static boolean handleSettingsClick(double mouseX, double mouseY, int sh) {
        if (UiScale.active()) { mouseX = UiScale.unscale(mouseX, LEFT); mouseY = UiScale.unscale(mouseY, togglesTop(sh)); }
        if (mouseX < LEFT || mouseX > LEFT + WIDTH) return false;
        int sy = settingsTop(sh);
        return mouseY >= sy && mouseY <= sy + HEIGHT;
    }

    public static boolean handleHunterClick(double mouseX, double mouseY, int sh) { return false; }
    public static boolean handleActivityClick(double mouseX,double mouseY,int sh){
        return false;
    }
    public static boolean handleTeleportClick(double mouseX,double mouseY,int sh,TownyMapConfig config){if(!config.teleportViewerEnabled)return false;if(UiScale.active()){mouseX=UiScale.unscale(mouseX,LEFT);mouseY=UiScale.unscale(mouseY,togglesTop(sh));}int y=teleportTop(sh);return mouseX>=LEFT&&mouseX<=LEFT+WIDTH&&mouseY>=y&&mouseY<=y+HEIGHT;}

    private static void drawToggle(GuiGraphicsExtractor ctx, Font tr, int row, int baseY,
                                   String name, boolean enabled) {
        int x = LEFT;
        int y = baseY + row * (HEIGHT + GAP);
        int text = enabled ? 0xFFFFFFFF : 0xFFBDBDBD;
        String label = Component.translatable("townymapaddon.map_controls.named_value", name,
                Component.translatable(enabled ? "options.on" : "options.off")).getString();

        drawTexturedButton(ctx, x, y, WIDTH, HEIGHT, label, true, text);
        ctx.fill(x + 2, y + 3, x + 5, y + HEIGHT - 3, enabled ? 0xFF67D76B : 0xFF606060);
    }

    private static void drawMode(GuiGraphicsExtractor ctx, Font tr, int row, int baseY,
                                 String name, String mode, boolean enabled) {
        int x = LEFT;
        int y = baseY + row * (HEIGHT + GAP);
        int text = enabled ? 0xFFFFFFFF : 0xFFBDBDBD;
        String label = Component.translatable("townymapaddon.map_controls.named_value", name, mode).getString();

        drawTexturedButton(ctx, x, y, WIDTH, HEIGHT, label, true, text);
        ctx.fill(x + 2, y + 3, x + 5, y + HEIGHT - 3, enabled ? 0xFF67D76B : 0xFF606060);
    }

    private static int toggleRowAt(double mouseY, int sh) {
        int baseY = togglesTop(sh);
        for (int row = 0; row < TOGGLE_ROWS; row++) {
            int top = baseY + row * (HEIGHT + GAP);
            if (mouseY >= top && mouseY <= top + HEIGHT) return row;
        }
        return -1;
    }

    /** Top edge of the toggle column — other left-side HUD must stay above this. */
    public static int togglesTop(int sh) {
        int totalHeight = TOGGLE_ROWS * HEIGHT + (TOGGLE_ROWS - 1) * GAP + SETTINGS_GAP + HEIGHT + GAP + HEIGHT;
        return Math.max(8, (sh - totalHeight) / 2);
    }

    private static int settingsTop(int sh) {
        return togglesTop(sh) + TOGGLE_ROWS * (HEIGHT + GAP) + SETTINGS_GAP;
    }

    private static int teleportTop(int sh){return settingsTop(sh)+HEIGHT+GAP;}
    private static int hunterTop(int sh) { return teleportTop(sh) + HEIGHT + GAP; }

    private static void drawSettingsButton(GuiGraphicsExtractor ctx, Font tr, int y) {
        String label = Component.translatable("townymapaddon.map_controls.settings").getString();
        drawTexturedButton(ctx, LEFT, y, WIDTH, HEIGHT, label, true, 0xFFCCCCCC);
    }

    private static void drawCounterResetButton(GuiGraphicsExtractor ctx, Font tr, int baseY) {
        int x = LEFT + WIDTH + RESET_GAP;
        int y = baseY + 4 * (HEIGHT + GAP);
        drawTexturedButton(ctx, x, y, RESET_WIDTH, HEIGHT, Component.translatable("townymapaddon.common.reset").getString(), true, 0xFFFF5555);
    }

    private static void drawCounterGroupButtons(GuiGraphicsExtractor ctx, Font tr, TownyMapConfig config) {
        int y = GROUP_TOP;
        int x = counterGroupsX();
        int visibleGroups = ChunkCounterOverlay.visibleGroupCount(config);
        for (int i = 0; i < visibleGroups; i++) {
            boolean active = ChunkCounterOverlay.isActiveGroup(config, i);
            int textColor = active ? 0xFFFFFFFF : 0xFFBDBDBD;
            drawTexturedButton(ctx, x, y, GROUP_WIDTH, HEIGHT, ChunkCounterOverlay.groupLabel(i), true, textColor);
            if (active) {
                ctx.fill(x + 2, y + HEIGHT - 4, x + GROUP_WIDTH - 2, y + HEIGHT - 2,
                        0xFF000000 | ChunkCounterOverlay.groupColor(i));
            }
            x += GROUP_WIDTH + GROUP_GAP;
        }
        if (ChunkCounterOverlay.canAddGroup(config)) {
            drawTexturedButton(ctx, x, y, ADD_WIDTH, HEIGHT, "+", true, 0xFFFFFFFF);
        }
        // Fill: count/shade any area fully enclosed by the selection (draw an outline, get the inside).
        boolean fillOn = ChunkCounterOverlay.isFillEnclosed(config);
        int fx = counterFillX(config);
        drawTexturedButton(ctx, fx, y, FILL_WIDTH, HEIGHT, tr("fill"), true,
                fillOn ? 0xFFFFFFFF : 0xFFBDBDBD);
        ctx.fill(fx + 2, y + HEIGHT - 4, fx + FILL_WIDTH - 2, y + HEIGHT - 2,
                fillOn ? 0xFF67D76B : 0xFF606060);
    }

    private static int counterFillX(TownyMapConfig config) {
        int x = counterGroupsX()
                + ChunkCounterOverlay.visibleGroupCount(config) * (GROUP_WIDTH + GROUP_GAP);
        if (ChunkCounterOverlay.canAddGroup(config)) x += ADD_WIDTH + GROUP_GAP;
        return x;
    }

    private static boolean insideCounterFill(double mouseX, double mouseY, TownyMapConfig config) {
        if (mouseY < GROUP_TOP || mouseY > GROUP_TOP + HEIGHT) return false;
        int x = counterFillX(config);
        return mouseX >= x && mouseX <= x + FILL_WIDTH;
    }

    private static boolean insideCounterReset(double mouseX, double mouseY, int sh) {
        int x = LEFT + WIDTH + RESET_GAP;
        int y = togglesTop(sh) + 4 * (HEIGHT + GAP);
        return mouseX >= x && mouseX <= x + RESET_WIDTH
                && mouseY >= y && mouseY <= y + HEIGHT;
    }

    private static int counterGroupAt(double mouseX, double mouseY, TownyMapConfig config) {
        int y = GROUP_TOP;
        if (mouseY < y || mouseY > y + HEIGHT) return -1;
        int x = counterGroupsX();
        int visibleGroups = ChunkCounterOverlay.visibleGroupCount(config);
        for (int i = 0; i < visibleGroups; i++) {
            if (mouseX >= x && mouseX <= x + GROUP_WIDTH) return i;
            x += GROUP_WIDTH + GROUP_GAP;
        }
        return -1;
    }

    private static boolean insideCounterAdd(double mouseX, double mouseY, TownyMapConfig config) {
        if (!ChunkCounterOverlay.canAddGroup(config)) return false;
        int y = GROUP_TOP;
        if (mouseY < y || mouseY > y + HEIGHT) return false;
        int visibleGroups = ChunkCounterOverlay.visibleGroupCount(config);
        int x = counterGroupsX() + visibleGroups * (GROUP_WIDTH + GROUP_GAP);
        return mouseX >= x && mouseX <= x + ADD_WIDTH;
    }

    private static int counterGroupsX() {
        return GROUP_LEFT;
    }

    private static void drawTexturedButton(GuiGraphicsExtractor ctx, int x, int y, int w, int h,
                                           String label, boolean active, int textColor) {
        if (DarkButtons.enabled()) {
            DarkButtons.draw(ctx, x, y, w, h, label, active, textColor, scaledMouseX(), scaledMouseY());
            return;
        }
        Button button = Button.builder(coloredText(label, textColor), ignored -> {})
                .bounds(x, y, w, h)
                .build();
        button.active = active;
        button.extractRenderState(ctx, scaledMouseX(), scaledMouseY(), 0.0F);
    }

    private static Component coloredText(String label, int textColor) {
        return Component.literal(label).setStyle(Style.EMPTY.withColor(textColor & 0xFFFFFF));
    }

    // Mouse in the column's own (unscaled) space, so button hover lines up under UI Scale.
    private static int scaledMouseX() {
        Minecraft mc = Minecraft.getInstance();
        double abs = mc.mouseHandler.getScaledXPos(mc.getWindow());
        return (int) Math.round(UiScale.unscale(abs, LEFT));
    }

    private static int scaledMouseY() {
        Minecraft mc = Minecraft.getInstance();
        double abs = mc.mouseHandler.getScaledYPos(mc.getWindow());
        return (int) Math.round(UiScale.unscale(abs, togglesTop(mc.getWindow().getGuiScaledHeight())));
    }

    /** "Auto" follows the dimension you are in; the other two pin the map to one world. */
    private static String worldModeLabel(TownyMapConfig config) {
        // In Auto the label has to show what it resolved to, or the button reads the same on both worlds.
        if (config.mapWorldMode == TownyMapMod.WORLD_MODE_AUTO) {
            return tr("world_auto") + ": " + (TownyMapMod.viewingEarth()?tr("terra_nostra"):tr("moon"));
        }
        return config.mapWorldMode == TownyMapMod.WORLD_MODE_MOON ? tr("moon") : tr("terra_nostra");
    }

    private static String borderModeLabel(int mode) {
        return tr(switch (mode) { case 1 -> "countries"; case 2 -> "states"; default -> "off"; });
    }

    private static String statusModeLabel(int mode) {
        return tr(switch (mode) { case 1 -> "public"; case 2 -> "overclaim"; case 3 -> "open";
            case 4 -> "meganations"; case 5 -> "alliances"; case 6 -> "planning"; default -> "none"; });
    }

    private static String tr(String id) { return Component.translatable("townymapaddon.map_controls." + id).getString(); }

}
