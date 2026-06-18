package net.townymap.gui;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.townymap.TownyMapConfig;
import net.townymap.TownyMapMod;
import net.townymap.mixin.CyclingButtonWidgetAccessor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

public class TownyMapConfigScreen extends Screen {

    private static final double PLAYER_NAME_SCALE_MIN = 0.01;
    private static final double PLAYER_NAME_SCALE_MAX = 0.30;
    private static final int PANEL_WIDTH = 284;
    private static final int CONTROL_WIDTH = 232;
    private static final int VIEW_TOP = 58;
    private static final int FOOTER_HEIGHT = 50;
    private static final int PANEL_BG = 0xE80E0F12;
    private static final int PANEL_BORDER = 0xCC3A3D42;
    private static final int PANEL_ACCENT = 0xFF4FA37A;

    // Auto-layout metrics (content-space, before scroll offset).
    private static final int ROW_H = 24;            // height allotted to each control row
    private static final int SECTION_HEADER_H = 18; // header label + underline + gap to first control
    private static final int SECTION_GAP = 12;      // extra space above each header after the first

    private final Screen parent;
    private final List<PositionedWidget> scrollingWidgets = new ArrayList<>();
    private final List<Section> sections = new ArrayList<>();
    private int scrollOffset;

    // Running cursor + derived totals, populated during init().
    private int cursorY;
    private int controlX;
    private int contentHeight;

    public TownyMapConfigScreen(Screen parent) {
        super(Text.literal("EarthMC Map Addon Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        TownyMapConfig cfg = TownyMapMod.getConfig();
        scrollingWidgets.clear();
        sections.clear();
        controlX = this.width / 2 - CONTROL_WIDTH / 2;
        cursorY = 0;

        section("General");
        control(onOff("EarthMC Only", cfg.earthmcOnly, v -> { cfg.earthmcOnly = v; cfg.save(); }));

        section("Minimap");
        control(onOff("Minimap Extensions", cfg.minimapExtensionsEnabled,
                v -> { cfg.minimapExtensionsEnabled = v; cfg.save(); }));
        control(cycle("Minimap Town Names", cfg.minimapTownNameMode, new int[]{0, 1, 2, 3},
                TownyMapConfigScreen::minimapTownNameModeText,
                v -> { cfg.minimapTownNameMode = v; cfg.minimapTownNamesEnabled = v != 0; cfg.save(); }));
        control(onOff("Players On Minimap", cfg.minimapPlayersEnabled,
                v -> { cfg.minimapPlayersEnabled = v; cfg.save(); }));
        control(cycle("Minimap Chunk Grid", cfg.minimapChunkGridMode, new int[]{0, 1, 2},
                TownyMapConfigScreen::minimapChunkGridModeText,
                v -> { cfg.minimapChunkGridMode = v; cfg.save(); }));
        control(onOff("Wilderness Player Alert", cfg.minimapNationAlertEnabled,
                v -> { cfg.minimapNationAlertEnabled = v; cfg.save(); }));
        control(onOff("Hide Minimap In Nether", cfg.hideMinimapInNether,
                v -> { cfg.hideMinimapInNether = v; cfg.save(); }));

        section("World Map");
        control(onOff("Town Borders", cfg.townsEnabled, v -> { cfg.townsEnabled = v; cfg.save(); }));
        control(onOff("Squaremap Background", cfg.squaremapBackgroundEnabled,
                v -> { cfg.squaremapBackgroundEnabled = v; cfg.save(); }));
        control(onOff("Nation Capital Stars", cfg.nationStarsEnabled,
                v -> { cfg.nationStarsEnabled = v; cfg.save(); }));
        control(cycle("Real Borders", cfg.borderOverlayMode, new int[]{0, 1, 2},
                TownyMapConfigScreen::borderModeText,
                v -> { cfg.borderOverlayMode = v; cfg.save(); }));
        control(new BorderThicknessSlider(controlX, 0, CONTROL_WIDTH, 20, cfg));
        control(onOff("Map Mode RGB", cfg.statusHighlightRainbow,
                v -> { cfg.statusHighlightRainbow = v; cfg.save(); }));
        control(new StatusHighlightHueSlider(controlX, 0, CONTROL_WIDTH, 20, cfg));

        section("Players");
        control(onOff("Online Players", cfg.playersEnabled, v -> { cfg.playersEnabled = v; cfg.save(); }));
        control(onOff("Player Names", cfg.showPlayerNames, v -> { cfg.showPlayerNames = v; cfg.save(); }));
        control(new PlayerNameRangeSlider(controlX, 0, CONTROL_WIDTH, 20, cfg));
        control(new PlayerAffiliationRangeSlider(controlX, 0, CONTROL_WIDTH, 20, cfg));

        section("Info Display");
        control(onOff("Current Town & Nation", cfg.infoDisplayTownEnabled,
                v -> { cfg.infoDisplayTownEnabled = v; cfg.save(); }));
        control(onOff("Nearby Players", cfg.infoDisplayNearbyPlayersEnabled,
                v -> { cfg.infoDisplayNearbyPlayersEnabled = v; cfg.save(); }));
        control(onOff("Nearest Town (Wilderness)", cfg.infoDisplayNearestTownEnabled,
                v -> { cfg.infoDisplayNearestTownEnabled = v; cfg.save(); }));

        section("Advanced");
        control(onOff("Custom Overlays", cfg.customOverlaysEnabled, v -> {
            cfg.customOverlaysEnabled = v;
            cfg.save();
            if (v) net.townymap.integration.CustomOverlayManager.reload();
        }));
        control(ButtonWidget.builder(Text.literal("Open Overlays Folder"),
                        btn -> net.townymap.integration.CustomOverlayManager.openFolder())
                .dimensions(controlX, 0, CONTROL_WIDTH, 20).build());
        control(ButtonWidget.builder(Text.literal("Reload Overlays"),
                        btn -> net.townymap.integration.CustomOverlayManager.reload())
                .dimensions(controlX, 0, CONTROL_WIDTH, 20).build());

        contentHeight = cursorY + 8;
        scrollOffset = Math.min(scrollOffset, maxScroll());

        this.addDrawableChild(
            ButtonWidget.builder(ScreenTexts.DONE, btn -> this.close())
                .dimensions(controlX, this.height - 30, CONTROL_WIDTH, 20)
                .build());
        updateScrollingWidgetPositions();
    }

    /** Record a section header at the cursor and advance past it. */
    private void section(String label) {
        if (!sections.isEmpty()) cursorY += SECTION_GAP;
        sections.add(new Section(label, cursorY));
        cursorY += SECTION_HEADER_H;
    }

    /** Place a control at the cursor and advance one row. */
    private void control(ClickableWidget widget) {
        addScrollingWidget(widget, cursorY);
        cursorY += ROW_H;
    }

    private CyclingButtonWidget<Boolean> onOff(String label, boolean value, Consumer<Boolean> onChange) {
        return CyclingButtonWidget.onOffBuilder(value)
                .build(controlX, 0, CONTROL_WIDTH, 20, Text.literal(label),
                       (btn, val) -> onChange.accept(val));
    }

    private CyclingButtonWidget<Integer> cycle(String label, int value, int[] values,
                                               Function<Integer, Text> valueToText, IntConsumer onChange) {
        Integer[] boxed = new Integer[values.length];
        for (int i = 0; i < values.length; i++) boxed[i] = values[i];
        return CyclingButtonWidget.builder(valueToText, value)
                .values(boxed)
                .build(controlX, 0, CONTROL_WIDTH, 20, Text.literal(label),
                       (btn, val) -> onChange.accept(val));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderPanel(ctx);
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title,
                this.width / 2, 17, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("EarthMC map overlays"),
                this.width / 2, 31, 0xFF9CA3AF);
        drawSections(ctx);
        drawScrollbar(ctx);
        drawScrollFades(ctx);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        // Right-click a cycling button to step it backward (mirrors right-click on the in-game map buttons).
        if (click.button() == 1) {
            double mx = click.x();
            double my = click.y();
            for (PositionedWidget entry : scrollingWidgets) {
                ClickableWidget w = entry.widget();
                if (w.visible && w.isMouseOver(mx, my) && w instanceof CyclingButtonWidget<?> cycling) {
                    ((CyclingButtonWidgetAccessor) cycling).townymap$cycle(-1);
                    if (this.client != null) {
                        this.client.getSoundManager().play(
                                PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                    }
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
        updateScrollingWidgetPositions();
        return true;
    }

    private <T extends ClickableWidget> T addScrollingWidget(T widget, int contentY) {
        scrollingWidgets.add(new PositionedWidget(widget, contentY));
        return this.addDrawableChild(widget);
    }

    private void updateScrollingWidgetPositions() {
        int top = bodyTop();
        int bottom = bodyBottom();
        for (PositionedWidget entry : scrollingWidgets) {
            ClickableWidget widget = entry.widget();
            widget.setY(top + entry.contentY() - scrollOffset);
            widget.visible = widget.getBottom() >= top + 2 && widget.getY() <= bottom - 2;
        }
    }

    private void renderPanel(DrawContext ctx) {
        int panelLeft = panelLeft();
        int panelRight = panelLeft + PANEL_WIDTH;
        int top = 40;
        int bottom = this.height - 8;
        ctx.fill(panelLeft - 4, top + 4, panelRight + 4, bottom + 4, 0x66000000);
        ctx.fill(panelLeft - 1, top - 1, panelRight + 1, bottom + 1, PANEL_BORDER);
        ctx.fill(panelLeft, top, panelRight, bottom, PANEL_BG);
        ctx.fill(panelLeft, top, panelRight, top + 3, PANEL_ACCENT);
        ctx.fill(panelLeft, bodyTop() - 1, panelRight, bodyTop(), 0x663A3D42);
        ctx.fill(panelLeft, bodyBottom(), panelRight, bodyBottom() + 1, 0x663A3D42);
        ctx.fill(panelLeft, bodyBottom() + 1, panelRight, bottom, 0xAA14161A);
    }

    private void drawSections(DrawContext ctx) {
        for (Section s : sections) {
            drawSection(ctx, s.label(), s.contentY());
        }
    }

    private void drawSection(DrawContext ctx, String label, int contentY) {
        int y = bodyTop() + contentY - scrollOffset;
        if (y < bodyTop() || y > bodyBottom() - 10) return;
        int x = this.width / 2 - CONTROL_WIDTH / 2;
        ctx.fill(x, y + 1, x + 3, y + 10, PANEL_ACCENT);
        ctx.drawText(this.textRenderer, label, x + 8, y, 0xFFE5E7EB, true);
        int lineY = y + 12;
        ctx.fill(x, lineY, x + CONTROL_WIDTH, lineY + 1, 0x553A3D42);
    }

    private void drawScrollbar(DrawContext ctx) {
        int maxScroll = maxScroll();
        if (maxScroll <= 0) return;
        int trackTop = bodyTop();
        int trackBottom = bodyBottom();
        int trackHeight = trackBottom - trackTop;
        int thumbHeight = Math.max(24, trackHeight * trackHeight / Math.max(1, contentHeight));
        int thumbY = trackTop + (trackHeight - thumbHeight) * scrollOffset / maxScroll;
        int x = panelLeft() + PANEL_WIDTH - 8;
        ctx.fill(x, trackTop + 4, x + 2, trackBottom - 4, 0x663A3D42);
        ctx.fill(x - 1, thumbY, x + 3, thumbY + thumbHeight, 0xFF9CA3AF);
    }

    private void drawScrollFades(DrawContext ctx) {
        int panelLeft = panelLeft();
        int panelRight = panelLeft + PANEL_WIDTH;
        if (scrollOffset > 0) {
            ctx.fill(panelLeft + 1, bodyTop(), panelRight - 1, bodyTop() + 10, 0xAA0E0F12);
        }
        if (scrollOffset < maxScroll()) {
            ctx.fill(panelLeft + 1, bodyBottom() - 10, panelRight - 1, bodyBottom(), 0xAA0E0F12);
        }
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }

    private int bodyTop() {
        return VIEW_TOP;
    }

    private int bodyBottom() {
        return Math.max(VIEW_TOP + 60, this.height - FOOTER_HEIGHT);
    }

    private int panelLeft() {
        return this.width / 2 - PANEL_WIDTH / 2;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - (bodyBottom() - bodyTop()));
    }

    private static Text borderModeText(Integer mode) {
        return Text.literal(switch (mode) {
            case 1 -> "Countries";
            case 2 -> "States + Countries";
            default -> "Off";
        });
    }

    private static Text minimapTownNameModeText(Integer mode) {
        return Text.literal(switch (mode) {
            case 1 -> "Nearby";
            case 2 -> "Major";
            case 3 -> "All";
            default -> "Off";
        });
    }

    private static Text minimapChunkGridModeText(Integer mode) {
        return Text.literal(switch (mode) {
            case 1 -> "Always";
            case 2 -> "Enlarged Only";
            default -> "Off";
        });
    }

    private static String hexColor(int rgb) {
        return String.format("#%06X", rgb & 0x00FFFFFF);
    }

    private record PositionedWidget(ClickableWidget widget, int contentY) {}

    private record Section(String label, int contentY) {}

    private static final class PlayerNameRangeSlider extends SliderWidget {
        private final TownyMapConfig config;

        private PlayerNameRangeSlider(int x, int y, int width, int height, TownyMapConfig config) {
            super(x, y, width, height, Text.empty(), sliderValue(config.playerNameMinScale));
            this.config = config;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Text.literal("Player Name Range: " + rangeLabel(value)));
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

    private static final class StatusHighlightHueSlider extends SliderWidget {
        private final TownyMapConfig config;

        private StatusHighlightHueSlider(int x, int y, int width, int height, TownyMapConfig config) {
            super(x, y, width, height, Text.empty(), hueFromRgb(config.statusHighlightColor));
            this.config = config;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            if (config.statusHighlightRainbow) {
                setMessage(Text.literal("Map Mode Color: RGB Cycle"));
            } else {
                setMessage(Text.literal("Map Mode Color: " + hexColor(config.statusHighlightColor)));
            }
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

    private static final class PlayerAffiliationRangeSlider extends SliderWidget {
        private final TownyMapConfig config;

        private PlayerAffiliationRangeSlider(int x, int y, int width, int height, TownyMapConfig config) {
            super(x, y, width, height, Text.empty(), sliderValue(config.playerAffiliationMinScale));
            this.config = config;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Text.literal("Town/Nation Range: " + rangeLabel(value)));
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

    /** Slider for border line thickness — range 0.5× to 3.0×, snaps to 0.25 steps. */
    private static final class BorderThicknessSlider extends SliderWidget {

        private static final double MIN = 0.1;
        private static final double MAX = 3.0;

        private final TownyMapConfig config;

        private BorderThicknessSlider(int x, int y, int width, int height, TownyMapConfig config) {
            super(x, y, width, height, Text.empty(), toSlider(config.borderThicknessMultiplier));
            this.config = config;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Border Thickness: %.2f×", snapped(value))));
        }

        @Override
        protected void applyValue() {
            config.borderThicknessMultiplier = (float) snapped(value);
            config.save();
        }

        /** Map slider 0–1 → multiplier MIN–MAX, then snap to nearest 0.05. */
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
