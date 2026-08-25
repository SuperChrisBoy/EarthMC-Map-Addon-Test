package net.townymap.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.townymap.TownyMapMod;
import net.townymap.integration.XaeroWaypointBridge;
import net.townymap.teleport.TeleportDestination;
import net.townymap.teleport.TeleportRoute;

import java.util.List;
import java.util.Locale;

/** Scrollable route presentation; all route decisions remain in TeleportAccessService. */
public final class TeleportViewerScreen extends Screen {
    private final Screen parent;
    private final double targetX, targetZ;
    private boolean advanced, lastLoading = true;
    private int scroll;
    private String feedback = "";

    public TeleportViewerScreen(Screen parent, double x, double z) {
        super(Text.translatable("townymapaddon.teleport.title"));
        this.parent = parent;
        targetX = x;
        targetZ = z;
        advanced = TownyMapMod.getConfig().teleportDefaultAdvanced;
    }

    @Override protected void init() {
        int left = Math.max(12, (width - Math.min(620, width - 24)) / 2), right = width - left;
        addDrawableChild(ButtonWidget.builder(Text.translatable("townymapaddon.teleport.mode.standard"), b -> setAdvanced(false)).dimensions(left + 12, 72, 120, 22).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("townymapaddon.teleport.mode.advanced"), b -> setAdvanced(true)).dimensions(left + 138, 72, 120, 22).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("townymapaddon.teleport.target_waypoint"), b -> targetWaypoint()).dimensions(right - 176, 42, 164, 22).build());
        List<TeleportRoute> routes = routes();
        for (int i = 0; i < routes.size(); i++) {
            TeleportRoute route = routes.get(i);
            int y = 132 + i * 82 - scroll;
            if (y < 132 || y + 82 > height - 42) continue;
            ButtonWidget copy = ButtonWidget.builder(Text.translatable("townymapaddon.teleport.copy"), b -> copy(route)).dimensions(right - 220, y + 51, 62, 22).build();
            copy.active = route.destination().command() != null && !route.destination().command().isBlank();
            addDrawableChild(copy);
            ButtonWidget waypoint = ButtonWidget.builder(Text.translatable("townymapaddon.teleport.waypoint"), b -> waypoint(route)).dimensions(right - 154, y + 51, 72, 22).build();
            waypoint.active = route.destination().x() != 0 || route.destination().z() != 0;
            addDrawableChild(waypoint);
            addDrawableChild(ButtonWidget.builder(Text.translatable("townymapaddon.teleport.report_spawn"), b -> {
                TownyMapMod.cycleTeleportSpawnReport(route.destination());
                clearAndInit();
            }).dimensions(right - 78, y + 51, 66, 22).build());
        }
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> close()).dimensions(right - 92, height - 30, 80, 20).build());
    }

    private List<TeleportRoute> routes() {
        var plan = TownyMapMod.teleportPlan(targetX, targetZ);
        return advanced ? plan.advanced() : plan.standard();
    }

    private void setAdvanced(boolean value) { advanced = value; scroll = 0; clearAndInit(); }
    private void targetWaypoint() {
        XaeroWaypointBridge.createTeleportWaypoint(Text.translatable("townymapaddon.teleport.target").getString(), (int)Math.round(targetX), 0, (int)Math.round(targetZ));
        feedback = Text.translatable("townymapaddon.teleport.waypoint_created").getString();
    }
    private void copy(TeleportRoute route) {
        String value = route.steps().stream().map(TeleportRoute.Step::command).filter(s -> s != null && !s.isBlank()).reduce((a,b) -> a + "\n" + b).orElse(route.destination().command());
        if (client != null) client.keyboard.setClipboard(value);
        feedback = Text.translatable("townymapaddon.teleport.copied", value.replace('\n', ' ')).getString();
    }
    private void waypoint(TeleportRoute route) {
        var d = route.destination();
        XaeroWaypointBridge.createTeleportWaypoint((d.type() == TeleportDestination.Type.TOWN_SPAWN ? "TP " : "N Spawn ") + d.name(), d.x(), d.y(), d.z());
        feedback = Text.translatable("townymapaddon.teleport.waypoint_created").getString();
    }

    @Override public void tick() {
        super.tick();
        boolean loading = TownyMapMod.teleportPlan(targetX, targetZ).loading();
        if (lastLoading && !loading) clearAndInit();
        lastLoading = loading;
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontalAmount, double verticalAmount) {
        int count = routes().size();
        scroll = Math.clamp(scroll - (int)Math.round(verticalAmount * 40), 0, Math.max(0, count * 82 - (height - 174)));
        clearAndInit();
        return true;
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int left = Math.max(12, (width - Math.min(620, width - 24)) / 2), right = width - left;
        context.fill(left - 2, 16, right + 2, height - 8, 0xEE080B0E);
        context.fill(left, 18, right, height - 10, 0xF015191D);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 25, 0xFFFFFFFF);
        context.drawText(textRenderer, Text.translatable("townymapaddon.teleport.target_context", TownyMapMod.teleportTargetContext(targetX, targetZ)), left + 12, 47, 0xFFB9C3CC, false);
        if (!feedback.isBlank()) context.drawText(textRenderer, feedback, left + 12, 103, 0xFF7EE2B8, false);
        var plan = TownyMapMod.teleportPlan(targetX, targetZ);
        if (plan.loading()) { context.drawCenteredTextWithShadow(textRenderer, Text.translatable("townymapaddon.teleport.loading"), width / 2, 120, 0xFFFFFF88); return; }
        List<TeleportRoute> routes = routes();
        if (routes.isEmpty()) { context.drawCenteredTextWithShadow(textRenderer, Text.translatable("townymapaddon.teleport.none"), width / 2, 145, 0xFFFFAA55); return; }
        for (int i = 0; i < routes.size(); i++) {
            TeleportRoute route = routes.get(i); int y = 132 + i * 82 - scroll;
            if (y < 132 || y + 82 > height - 42) continue;
            context.fill(left + 10, y, right - 10, y + 76, 0xE0122B20);
            context.drawText(textRenderer, route.destination().name(), left + 18, y + 9, 0xFFFFFFFF, false);
            context.drawText(textRenderer, Text.translatable("townymapaddon.teleport.blocks", (int)Math.round(route.walkingDistance())), right - 105, y + 9, 0xFFFFFFFF, false);
            context.drawText(textRenderer, route.destination().command(), left + 18, y + 25, 0xFF9BFFD3, false);
            context.drawText(textRenderer, Text.translatable("townymapaddon.teleport.eligibility_reason." + route.destination().reason().name().toLowerCase(Locale.ROOT)), left + 18, y + 39, 0xFFB8C0C7, false);
            context.drawText(textRenderer, Text.translatable("townymapaddon.teleport.spawn_status", route.destination().physicalAccess()), left + 270, y + 39, route.destination().physicalAccess() == TeleportDestination.PhysicalAccess.OBSTRUCTED ? 0xFFFF7777 : 0xFFFFCC77, false);
            if (advanced && route.saving() != 0) context.drawText(textRenderer, Text.translatable("townymapaddon.teleport.saving", (int)Math.round(route.saving())), left + 18, y + 64, route.saving() > 0 ? 0xFF8FE3A8 : 0xFFFFAA55, false);
        }
    }

    @Override public void close() { if (client != null) client.setScreen(parent); }
}
