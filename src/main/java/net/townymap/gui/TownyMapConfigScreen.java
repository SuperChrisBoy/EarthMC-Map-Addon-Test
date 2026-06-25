package net.townymap.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.townymap.TownyMapConfig;
import net.townymap.TownyMapMod;
import net.townymap.mixin.CycleButtonAccessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * Cloth-Config-style settings: a searchable, scrolling list of rows grouped under section headers.
 * Each option row shows its label on the left, its control on the right, and a per-row Reset button.
 * Right-clicking a cycling control steps it backward.
 */
public class TownyMapConfigScreen extends Screen {

    private static final double PLAYER_NAME_SCALE_MIN = 0.01;
    private static final double PLAYER_NAME_SCALE_MAX = 0.30;

    // ── Layout metrics ────────────────────────────────────────────────────────
    private static final int ROW_H = 24;
    private static final int SECTION_HEADER_H = 18;
    private static final int SECTION_GAP = 10;
    private static final int CTRL_W = 120;       // right-side control width
    private static final int RESET_W = 46;
    private static final int COL_GAP = 6;
    private static final int PANEL_PAD = 14;
    private static final int SCROLLBAR_W = 6;
    private static final int FOOTER_HEIGHT = 40;
    private static final int PANEL_TOP = 32;
    private static final int SEARCH_Y = 38;
    private static final int SEARCH_H = 16;
    private static final int BODY_TOP = 60;

    private static final int PANEL_BG = 0xE80E0F12;
    private static final int PANEL_BORDER = 0xCC3A3D42;
    private static final int PANEL_ACCENT = 0xFF4FA37A;
    private static final int LABEL_COLOR = 0xFFE5E7EB;

    private static final Component YES = Component.literal("Yes").withStyle(ChatFormatting.GREEN);
    private static final Component NO = Component.literal("No").withStyle(ChatFormatting.RED);

    /** Field-initialised defaults, used to drive the per-row Reset buttons. */
    private static final TownyMapConfig DEFAULTS = new TownyMapConfig();

    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private TownyMapConfig cfg;
    private EditBox searchField;
    private String searchQuery = "";
    private int scrollOffset;
    private int contentHeight;

    // Geometry, recomputed each init() (handles resize).
    private int panelLeft, panelWidth, innerRight, contentLeft, contentRight;
    private int labelX, labelMaxW, ctrlX, resetX;

    public TownyMapConfigScreen(Screen parent) {
        super(Component.literal("EarthMC Map Addon Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        cfg = TownyMapMod.getConfig();
        rows.clear();

        panelWidth = Math.max(320, Math.min(this.width - 40, 460));
        panelLeft = (this.width - panelWidth) / 2;
        innerRight = panelLeft + panelWidth - PANEL_PAD;
        contentLeft = panelLeft + PANEL_PAD;
        contentRight = innerRight - SCROLLBAR_W;
        resetX = contentRight - RESET_W;
        ctrlX = resetX - COL_GAP - CTRL_W;
        labelX = contentLeft;
        labelMaxW = Math.max(40, ctrlX - COL_GAP - labelX);

        searchField = new EditBox(this.font, contentLeft, SEARCH_Y,
                innerRight - contentLeft, SEARCH_H, Component.literal("Search"));
        searchField.setMaxLength(64);
        searchField.setHint(Component.literal("Search…").withStyle(ChatFormatting.DARK_GRAY));
        searchField.setValue(searchQuery);
        searchField.setResponder(q -> { searchQuery = q; relayout(); });
        this.addRenderableWidget(searchField);

        section("General");
        option("EarthMC Only", onOff(cfg.earthmcOnly, v -> cfg.earthmcOnly = v),
                () -> cfg.earthmcOnly == DEFAULTS.earthmcOnly,
                () -> cfg.earthmcOnly = DEFAULTS.earthmcOnly);
        option("EarthMC Map In Nether", cycle(cfg.netherMode == 2 ? 2 : 1, new int[]{1, 2},
                        TownyMapConfigScreen::netherModeText, v -> cfg.netherMode = v),
                () -> cfg.netherMode == DEFAULTS.netherMode,
                () -> cfg.netherMode = DEFAULTS.netherMode);

        section("Minimap");
        option("Minimap Extensions", onOff(cfg.minimapExtensionsEnabled, v -> cfg.minimapExtensionsEnabled = v),
                () -> cfg.minimapExtensionsEnabled == DEFAULTS.minimapExtensionsEnabled,
                () -> cfg.minimapExtensionsEnabled = DEFAULTS.minimapExtensionsEnabled);
        option("Town Names", cycle(cfg.minimapTownNameMode, new int[]{0, 1, 2, 3},
                        TownyMapConfigScreen::minimapTownNameModeText,
                        v -> { cfg.minimapTownNameMode = v; cfg.minimapTownNamesEnabled = v != 0; }),
                () -> cfg.minimapTownNameMode == DEFAULTS.minimapTownNameMode,
                () -> { cfg.minimapTownNameMode = DEFAULTS.minimapTownNameMode;
                        cfg.minimapTownNamesEnabled = DEFAULTS.minimapTownNameMode != 0; });
        option("Players On Minimap", onOff(cfg.minimapPlayersEnabled, v -> cfg.minimapPlayersEnabled = v),
                () -> cfg.minimapPlayersEnabled == DEFAULTS.minimapPlayersEnabled,
                () -> cfg.minimapPlayersEnabled = DEFAULTS.minimapPlayersEnabled);
        option("Chunk Grid", cycle(cfg.minimapChunkGridMode, new int[]{0, 1, 2},
                        TownyMapConfigScreen::minimapChunkGridModeText, v -> cfg.minimapChunkGridMode = v),
                () -> cfg.minimapChunkGridMode == DEFAULTS.minimapChunkGridMode,
                () -> cfg.minimapChunkGridMode = DEFAULTS.minimapChunkGridMode);
        option("Wilderness Player Alert", onOff(cfg.minimapNationAlertEnabled, v -> cfg.minimapNationAlertEnabled = v),
                () -> cfg.minimapNationAlertEnabled == DEFAULTS.minimapNationAlertEnabled,
                () -> cfg.minimapNationAlertEnabled = DEFAULTS.minimapNationAlertEnabled);
        option("Hide Minimap In Nether", onOff(cfg.hideMinimapInNether, v -> cfg.hideMinimapInNether = v),
                () -> cfg.hideMinimapInNether == DEFAULTS.hideMinimapInNether,
                () -> cfg.hideMinimapInNether = DEFAULTS.hideMinimapInNether);

        section("World Map");
        option("Town Borders", onOff(cfg.townsEnabled, v -> cfg.townsEnabled = v),
                () -> cfg.townsEnabled == DEFAULTS.townsEnabled,
                () -> cfg.townsEnabled = DEFAULTS.townsEnabled);
        option("Squaremap Background", onOff(cfg.squaremapBackgroundEnabled, v -> cfg.squaremapBackgroundEnabled = v),
                () -> cfg.squaremapBackgroundEnabled == DEFAULTS.squaremapBackgroundEnabled,
                () -> cfg.squaremapBackgroundEnabled = DEFAULTS.squaremapBackgroundEnabled);
        option("World Map Overview (Test)", onOff(cfg.worldMapOverview, v -> cfg.worldMapOverview = v),
                () -> cfg.worldMapOverview == DEFAULTS.worldMapOverview,
                () -> cfg.worldMapOverview = DEFAULTS.worldMapOverview);
        option("Nation Capital Stars", onOff(cfg.nationStarsEnabled, v -> cfg.nationStarsEnabled = v),
                () -> cfg.nationStarsEnabled == DEFAULTS.nationStarsEnabled,
                () -> cfg.nationStarsEnabled = DEFAULTS.nationStarsEnabled);
        option("Real Borders", cycle(cfg.borderOverlayMode, new int[]{0, 1, 2},
                        TownyMapConfigScreen::borderModeText, v -> cfg.borderOverlayMode = v),
                () -> cfg.borderOverlayMode == DEFAULTS.borderOverlayMode,
                () -> cfg.borderOverlayMode = DEFAULTS.borderOverlayMode);
        option("Border Thickness", new BorderThicknessSlider(ctrlX, 0, CTRL_W, 20, cfg),
                () -> cfg.borderThicknessMultiplier == DEFAULTS.borderThicknessMultiplier,
                () -> cfg.borderThicknessMultiplier = DEFAULTS.borderThicknessMultiplier);
        option("Map Mode RGB", onOff(cfg.statusHighlightRainbow, v -> cfg.statusHighlightRainbow = v),
                () -> cfg.statusHighlightRainbow == DEFAULTS.statusHighlightRainbow,
                () -> cfg.statusHighlightRainbow = DEFAULTS.statusHighlightRainbow);
        option("Map Mode Color", new StatusHighlightHueSlider(ctrlX, 0, CTRL_W, 20, cfg),
                () -> cfg.statusHighlightColor == DEFAULTS.statusHighlightColor,
                () -> cfg.statusHighlightColor = DEFAULTS.statusHighlightColor);

        section("Players");
        option("Online Players", onOff(cfg.playersEnabled, v -> cfg.playersEnabled = v),
                () -> cfg.playersEnabled == DEFAULTS.playersEnabled,
                () -> cfg.playersEnabled = DEFAULTS.playersEnabled);
        option("Player Names", onOff(cfg.showPlayerNames, v -> cfg.showPlayerNames = v),
                () -> cfg.showPlayerNames == DEFAULTS.showPlayerNames,
                () -> cfg.showPlayerNames = DEFAULTS.showPlayerNames);
        option("Player Name Range", new PlayerNameRangeSlider(ctrlX, 0, CTRL_W, 20, cfg),
                () -> cfg.playerNameMinScale == DEFAULTS.playerNameMinScale,
                () -> cfg.playerNameMinScale = DEFAULTS.playerNameMinScale);
        option("Town/Nation Range", new PlayerAffiliationRangeSlider(ctrlX, 0, CTRL_W, 20, cfg),
                () -> cfg.playerAffiliationMinScale == DEFAULTS.playerAffiliationMinScale,
                () -> cfg.playerAffiliationMinScale = DEFAULTS.playerAffiliationMinScale);

        section("Info Display");
        option("Current Town & Nation", onOff(cfg.infoDisplayTownEnabled, v -> cfg.infoDisplayTownEnabled = v),
                () -> cfg.infoDisplayTownEnabled == DEFAULTS.infoDisplayTownEnabled,
                () -> cfg.infoDisplayTownEnabled = DEFAULTS.infoDisplayTownEnabled);
        option("Nearby Players", onOff(cfg.infoDisplayNearbyPlayersEnabled, v -> cfg.infoDisplayNearbyPlayersEnabled = v),
                () -> cfg.infoDisplayNearbyPlayersEnabled == DEFAULTS.infoDisplayNearbyPlayersEnabled,
                () -> cfg.infoDisplayNearbyPlayersEnabled = DEFAULTS.infoDisplayNearbyPlayersEnabled);
        option("Nearest Town (Wilderness)", onOff(cfg.infoDisplayNearestTownEnabled, v -> cfg.infoDisplayNearestTownEnabled = v),
                () -> cfg.infoDisplayNearestTownEnabled == DEFAULTS.infoDisplayNearestTownEnabled,
                () -> cfg.infoDisplayNearestTownEnabled = DEFAULTS.infoDisplayNearestTownEnabled);

        section("Advanced");
        option("Custom Overlays", onOff(cfg.customOverlaysEnabled, v -> {
            cfg.customOverlaysEnabled = v;
            if (v) net.townymap.integration.CustomOverlayManager.reload();
        }),
                () -> cfg.customOverlaysEnabled == DEFAULTS.customOverlaysEnabled,
                () -> cfg.customOverlaysEnabled = DEFAULTS.customOverlaysEnabled);
        action("Open Overlays Folder", () -> net.townymap.integration.CustomOverlayManager.openFolder());
        action("Reload Overlays", () -> net.townymap.integration.CustomOverlayManager.reload());

        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
                        .bounds(this.width / 2 - 75, this.height - 30, 150, 20).build());

        relayout();
    }

    // ── Row building ──────────────────────────────────────────────────────────

    private void section(String label) {
        rows.add(new Row(label, null, null, false, null));
    }

    private void option(String label, AbstractWidget control, BooleanSupplier isDefault, Runnable resetAction) {
        Button reset = Button.builder(Component.literal("Reset"), b -> {
            resetAction.run();
            cfg.save();
            this.rebuildWidgets();
        }).bounds(resetX, 0, RESET_W, 20).build();
        rows.add(new Row(label, control, reset, false, isDefault));
        this.addRenderableWidget(control);
        this.addRenderableWidget(reset);
    }

    private void action(String label, Runnable onClick) {
        Button b = Button.builder(Component.literal(label), x -> onClick.run())
                .bounds(contentLeft, 0, Math.max(40, contentRight - contentLeft), 20).build();
        rows.add(new Row(label, b, null, true, null));
        this.addRenderableWidget(b);
    }

    private CycleButton<Boolean> onOff(boolean value, Consumer<Boolean> setter) {
        return CycleButton.booleanBuilder(YES, NO, value).displayOnlyValue()
                .create(ctrlX, 0, CTRL_W, 20, Component.empty(), (btn, val) -> { setter.accept(val); cfg.save(); });
    }

    private CycleButton<Integer> cycle(int value, int[] values, Function<Integer, Component> toText, IntConsumer setter) {
        Integer[] boxed = new Integer[values.length];
        for (int i = 0; i < values.length; i++) boxed[i] = values[i];
        return CycleButton.builder(toText, value).withValues(boxed).displayOnlyValue()
                .create(ctrlX, 0, CTRL_W, 20, Component.empty(), (btn, val) -> { setter.accept(val); cfg.save(); });
    }

    // ── Layout / filtering ──────────────────────────────────────────────────────

    /** Assign content-space Y to each visible row (filtered by the search query), then position widgets. */
    private void relayout() {
        boolean searching = !searchQuery.isBlank();
        String needle = searchQuery.toLowerCase(Locale.ROOT);
        int y = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.isSection()) {
                boolean any = !searching || sectionHasMatch(i, needle);
                r.visible = any;
                if (any) {
                    if (y > 0) y += SECTION_GAP;
                    r.contentY = y;
                    y += SECTION_HEADER_H;
                }
            } else {
                boolean match = !searching || matches(r, needle);
                r.visible = match;
                if (match) {
                    r.contentY = y;
                    y += ROW_H;
                }
            }
        }
        contentHeight = y + 8;
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll()));
        applyWidgetLayout();
    }

    private boolean sectionHasMatch(int sectionIndex, String needle) {
        for (int j = sectionIndex + 1; j < rows.size(); j++) {
            Row r = rows.get(j);
            if (r.isSection()) break;
            if (matches(r, needle)) return true;
        }
        return false;
    }

    private boolean matches(Row r, String needle) {
        return r.label != null && r.label.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** Apply scroll offset to widget Y and hide rows outside the body / filtered out. */
    private void applyWidgetLayout() {
        int top = bodyTop();
        int bottom = bodyBottom();
        for (Row r : rows) {
            if (r.isSection()) continue;
            int rowY = top + r.contentY - scrollOffset;
            boolean show = r.visible && rowY + 20 >= top + 2 && rowY <= bottom - 2;
            if (r.control != null) {
                r.control.setY(rowY);
                r.control.visible = show;
            }
            if (r.reset != null) {
                r.reset.setY(rowY);
                r.reset.visible = show;
            }
        }
    }

    private void refreshResetStates() {
        for (Row r : rows) {
            if (r.reset != null && r.isDefault != null) {
                r.reset.active = !r.isDefault.getAsBoolean();
            }
        }
    }

    // ── Input ───────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        // Right-click a cycling control to step it backward (mirrors the in-game map buttons).
        if (click.buttonInfo().button() == 1) {
            double mx = click.x();
            double my = click.y();
            for (Row r : rows) {
                if (r.control instanceof CycleButton<?> cycling
                        && r.control.visible && r.control.isMouseOver(mx, my)) {
                    ((CycleButtonAccessor) cycling).townymap$cycle(-1);
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int maxScroll = maxScroll();
        if (maxScroll <= 0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) Math.round(verticalAmount * 24.0)));
        applyWidgetLayout();
        return true;
    }

    // ── Rendering ─────────────────────────────────────────────────────────────────

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        renderPanel(ctx);
        refreshResetStates();
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        ctx.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);
        drawSectionsAndLabels(ctx);
        drawScrollbar(ctx);
        drawScrollFades(ctx);
    }

    private void renderPanel(GuiGraphicsExtractor ctx) {
        int panelRight = panelLeft + panelWidth;
        int bottom = this.height - 8;
        ctx.fill(panelLeft - 4, PANEL_TOP + 4, panelRight + 4, bottom + 4, 0x66000000);
        ctx.fill(panelLeft - 1, PANEL_TOP - 1, panelRight + 1, bottom + 1, PANEL_BORDER);
        ctx.fill(panelLeft, PANEL_TOP, panelRight, bottom, PANEL_BG);
        ctx.fill(panelLeft, PANEL_TOP, panelRight, PANEL_TOP + 3, PANEL_ACCENT);
        ctx.fill(panelLeft, bodyTop() - 1, panelRight, bodyTop(), 0x663A3D42);
        ctx.fill(panelLeft, bodyBottom(), panelRight, bodyBottom() + 1, 0x663A3D42);
        ctx.fill(panelLeft, bodyBottom() + 1, panelRight, bottom, 0xAA14161A);
    }

    private void drawSectionsAndLabels(GuiGraphicsExtractor ctx) {
        int top = bodyTop();
        int bottom = bodyBottom();
        for (Row r : rows) {
            if (!r.visible) continue;
            int rowY = top + r.contentY - scrollOffset;
            if (r.isSection()) {
                if (rowY < top || rowY > bottom - 10) continue;
                ctx.fill(contentLeft, rowY + 1, contentLeft + 3, rowY + 10, PANEL_ACCENT);
                ctx.text(this.font, r.label, contentLeft + 8, rowY, LABEL_COLOR, true);
                int lineY = rowY + 12;
                ctx.fill(contentLeft, lineY, innerRight, lineY + 1, 0x553A3D42);
            } else if (!r.fullWidth) {
                if (rowY < top - 2 || rowY > bottom - 12) continue;
                int textY = rowY + (20 - this.font.lineHeight) / 2;
                String label = this.font.plainSubstrByWidth(r.label, labelMaxW);
                ctx.text(this.font, label, labelX, textY, LABEL_COLOR, false);
            }
        }
    }

    private void drawScrollbar(GuiGraphicsExtractor ctx) {
        int maxScroll = maxScroll();
        if (maxScroll <= 0) return;
        int trackTop = bodyTop();
        int trackBottom = bodyBottom();
        int trackHeight = trackBottom - trackTop;
        int thumbHeight = Math.max(24, trackHeight * trackHeight / Math.max(1, contentHeight));
        int thumbY = trackTop + (trackHeight - thumbHeight) * scrollOffset / maxScroll;
        int x = contentRight + 2;
        ctx.fill(x, trackTop + 4, x + 2, trackBottom - 4, 0x663A3D42);
        ctx.fill(x - 1, thumbY, x + 3, thumbY + thumbHeight, 0xFF9CA3AF);
    }

    private void drawScrollFades(GuiGraphicsExtractor ctx) {
        int panelRight = panelLeft + panelWidth;
        if (scrollOffset > 0) {
            ctx.fill(panelLeft + 1, bodyTop(), panelRight - 1, bodyTop() + 10, 0xAA0E0F12);
        }
        if (scrollOffset < maxScroll()) {
            ctx.fill(panelLeft + 1, bodyBottom() - 10, panelRight - 1, bodyBottom(), 0xAA0E0F12);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    private int bodyTop() {
        return BODY_TOP;
    }

    private int bodyBottom() {
        return Math.max(BODY_TOP + 60, this.height - FOOTER_HEIGHT);
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - (bodyBottom() - bodyTop()));
    }

    // ── Value → text for cycling controls ──────────────────────────────────────────

    private static Component borderModeText(Integer mode) {
        return Component.literal(switch (mode) {
            case 1 -> "Countries";
            case 2 -> "States + Countries";
            default -> "Off";
        });
    }

    private static Component minimapTownNameModeText(Integer mode) {
        return Component.literal(switch (mode) {
            case 1 -> "Nearby";
            case 2 -> "Major";
            case 3 -> "All";
            default -> "Off";
        });
    }

    private static Component netherModeText(Integer mode) {
        return Component.literal(mode == 2 ? "Overworld Coords" : "Hidden");
    }

    private static Component minimapChunkGridModeText(Integer mode) {
        return Component.literal(switch (mode) {
            case 1 -> "Always";
            case 2 -> "Enlarged Only";
            default -> "Off";
        });
    }

    private static String hexColor(int rgb) {
        return String.format("#%06X", rgb & 0x00FFFFFF);
    }

    /** One settings row: a section header (control == null) or an option (label + control + optional reset). */
    private static final class Row {
        final String label;
        final AbstractWidget control;
        final Button reset;
        final boolean fullWidth;
        final BooleanSupplier isDefault;
        int contentY;
        boolean visible = true;

        Row(String label, AbstractWidget control, Button reset, boolean fullWidth, BooleanSupplier isDefault) {
            this.label = label;
            this.control = control;
            this.reset = reset;
            this.fullWidth = fullWidth;
            this.isDefault = isDefault;
        }

        boolean isSection() {
            return control == null;
        }
    }

    private static final class PlayerNameRangeSlider extends AbstractSliderButton {
        private final TownyMapConfig config;

        private PlayerNameRangeSlider(int x, int y, int width, int height, TownyMapConfig config) {
            super(x, y, width, height, Component.empty(), sliderValue(config.playerNameMinScale));
            this.config = config;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal(rangeLabel(value)));
        }

        @Override
        protected void applyValue() {
            config.playerNameMinScale = scaleValue(value);
            config.save();
        }

        private static double sliderValue(double scale) {
            double clamped = Math.max(PLAYER_NAME_SCALE_MIN, Math.min(PLAYER_NAME_SCALE_MAX, scale));
            return 1.0 - ((clamped - PLAYER_NAME_SCALE_MIN) / (PLAYER_NAME_SCALE_MAX - PLAYER_NAME_SCALE_MIN));
        }

        private static double scaleValue(double sliderValue) {
            return PLAYER_NAME_SCALE_MIN + (1.0 - sliderValue) * (PLAYER_NAME_SCALE_MAX - PLAYER_NAME_SCALE_MIN);
        }

        private static String rangeLabel(double sliderValue) {
            double scale = scaleValue(sliderValue);
            if (scale <= 0.04) return "Very Far";
            if (scale <= 0.08) return "Far";
            if (scale <= 0.16) return "Normal";
            return "Near";
        }
    }

    private static final class StatusHighlightHueSlider extends AbstractSliderButton {
        private final TownyMapConfig config;

        private StatusHighlightHueSlider(int x, int y, int width, int height, TownyMapConfig config) {
            super(x, y, width, height, Component.empty(), hueFromRgb(config.statusHighlightColor));
            this.config = config;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(config.statusHighlightRainbow ? "RGB" : hexColor(config.statusHighlightColor)));
        }

        @Override
        protected void applyValue() {
            config.statusHighlightColor = hsvToRgb(value, 0.78, 1.0);
            config.save();
        }

        private static double hueFromRgb(int rgb) {
            double r = ((rgb >> 16) & 0xFF) / 255.0;
            double g = ((rgb >> 8) & 0xFF) / 255.0;
            double b = (rgb & 0xFF) / 255.0;
            double max = Math.max(r, Math.max(g, b));
            double min = Math.min(r, Math.min(g, b));
            double delta = max - min;
            if (delta <= 0.00001) return 0.78;
            double hue;
            if (max == r) {
                hue = ((g - b) / delta) % 6.0;
            } else if (max == g) {
                hue = (b - r) / delta + 2.0;
            } else {
                hue = (r - g) / delta + 4.0;
            }
            hue /= 6.0;
            return hue < 0 ? hue + 1.0 : hue;
        }

        private static int hsvToRgb(double hue, double saturation, double value) {
            double h = (hue - Math.floor(hue)) * 6.0;
            int sector = (int) Math.floor(h);
            double fraction = h - sector;
            double p = value * (1.0 - saturation);
            double q = value * (1.0 - fraction * saturation);
            double t = value * (1.0 - (1.0 - fraction) * saturation);
            double r, g, b;
            switch (sector) {
                case 0 -> { r = value; g = t; b = p; }
                case 1 -> { r = q; g = value; b = p; }
                case 2 -> { r = p; g = value; b = t; }
                case 3 -> { r = p; g = q; b = value; }
                case 4 -> { r = t; g = p; b = value; }
                default -> { r = value; g = p; b = q; }
            }
            return ((int) Math.round(r * 255.0) << 16)
                    | ((int) Math.round(g * 255.0) << 8)
                    | (int) Math.round(b * 255.0);
        }
    }

    private static final class PlayerAffiliationRangeSlider extends AbstractSliderButton {
        private final TownyMapConfig config;

        private PlayerAffiliationRangeSlider(int x, int y, int width, int height, TownyMapConfig config) {
            super(x, y, width, height, Component.empty(), sliderValue(config.playerAffiliationMinScale));
            this.config = config;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal(rangeLabel(value)));
        }

        @Override
        protected void applyValue() {
            config.playerAffiliationMinScale = scaleValue(value);
            config.save();
        }

        private static double sliderValue(double scale) {
            double clamped = Math.max(PLAYER_NAME_SCALE_MIN, Math.min(PLAYER_NAME_SCALE_MAX, scale));
            return 1.0 - ((clamped - PLAYER_NAME_SCALE_MIN) / (PLAYER_NAME_SCALE_MAX - PLAYER_NAME_SCALE_MIN));
        }

        private static double scaleValue(double sliderValue) {
            return PLAYER_NAME_SCALE_MIN + (1.0 - sliderValue) * (PLAYER_NAME_SCALE_MAX - PLAYER_NAME_SCALE_MIN);
        }

        private static String rangeLabel(double sliderValue) {
            double scale = scaleValue(sliderValue);
            if (scale <= 0.04) return "Very Far";
            if (scale <= 0.08) return "Far";
            if (scale <= 0.16) return "Normal";
            return "Near";
        }
    }

    /** Slider for border line thickness — range 0.1× to 3.0×, snaps to 0.05 steps. */
    private static final class BorderThicknessSlider extends AbstractSliderButton {

        private static final double MIN = 0.1;
        private static final double MAX = 3.0;

        private final TownyMapConfig config;

        private BorderThicknessSlider(int x, int y, int width, int height, TownyMapConfig config) {
            super(x, y, width, height, Component.empty(), toSlider(config.borderThicknessMultiplier));
            this.config = config;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(String.format("%.2f×", snapped(value))));
        }

        @Override
        protected void applyValue() {
            config.borderThicknessMultiplier = (float) snapped(value);
            config.save();
        }

        private static double snapped(double sliderValue) {
            double raw = MIN + sliderValue * (MAX - MIN);
            return Math.round(raw * 20) / 20.0;
        }

        private static double toSlider(double multiplier) {
            double clamped = Math.max(MIN, Math.min(MAX, multiplier));
            return (clamped - MIN) / (MAX - MIN);
        }
    }
}
