package net.townymap.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.townymap.TownyMapMod;

import java.util.ArrayList;
import java.util.List;

/**
 * "Planning" map mode: pick a nation, then drop hypothetical towns on the map and watch its join range grow.
 *
 * <p>EarthMC lets a town join a nation within 5k of the capital, and every town of that nation extends the
 * reachable area by 1.5k around itself — so a chain of towns walks the frontier outwards. This mode answers
 * "if I founded a town here, what would that open up?" without any of it being real: the planned towns are
 * client-side only and feed extra 1.5k circles into the same union the live range overlay draws.
 *
 * <p>The counter in the top-left mirrors the chunk counter's chip styling: one chip per planned town (T1, T2,
 * …) plus a {@code +} chip that arms the next map click. Chips wrap into further columns before they can run
 * past the middle of the screen, so a long plan never covers the map.
 */
public final class PlanningOverlay {

    private PlanningOverlay() {}

    /** The map-mode id this overlay owns (see TownyMapMod#STATUS_MODES). */
    public static final int MODE = 6;

    private static final int LEFT = 6;
    private static final int TOP = 30;
    private static final int CHIP_H = 14;
    private static final int CHIP_GAP = 3;
    private static final int COL_GAP = 4;
    private static final int PLANNED_RANGE = 1500;   // same 1.5k a real town contributes

    private static final int BG        = 0xC0101114;
    private static final int BORDER    = 0xFF2E3238;
    private static final int CHIP_BG   = 0xB0181B20;
    private static final int CHIP_ON   = 0xFF6FD3A0;
    private static final int CHIP_TEXT = 0xFFE5E7EB;
    private static final int CHIP_BAD    = 0xFFE2564E;   // out of range: must be moved
    private static final int CHIP_BAD_BG = 0x66401A18;

    /** The nation being planned for; blank until one is chosen. */
    private static String nation = "";
    /** Planned town centres in world blocks: {x, z}. */
    private static final List<int[]> planned = new ArrayList<>();
    /** True once "+" is clicked: the next left-click on the map drops a town instead of selecting. */
    private static boolean armed;
    /** Chip hitboxes recorded as drawn, so clicks match exactly under UI Scale. */
    private static final List<Hit> hits = new ArrayList<>();
    /** Bumped whenever the plan changes, so the range overlay's memoised circle set knows to rebuild. */
    private static volatile int version;
    /** Per planned town: false when it sits outside the nation's reachable area and must be moved. */
    private static volatile boolean[] valid = new boolean[0];
    /** Chip the cursor is over (-1 none), so the matching map marker can highlight itself. */
    private static volatile int hovered = -1;

    public static int version() { return version; }
    public static int hoveredIndex() { return hovered; }

    public static boolean isValid(int i) {
        boolean[] v = valid;
        return i < 0 || i >= v.length || v[i];
    }

    /** Planned town centres in world blocks, for drawing their map markers. */
    public static List<int[]> plannedPoints() { return List.copyOf(planned); }

    /**
     * Works out which planned towns are actually reachable, given the nation's real circles, and returns the
     * ones that legitimately extend the range.
     *
     * <p>A planned town only counts if it lies inside the area reachable so far — and once it does, it
     * extends that area for the others. Repeating until nothing new qualifies makes the result independent
     * of the order they were dropped in, so a chain placed "backwards" still resolves.
     */
    public static List<int[]> resolve(List<int[]> baseCircles) {
        int n = planned.size();
        boolean[] ok = new boolean[n];
        List<int[]> reach = new ArrayList<>(baseCircles);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < n; i++) {
                if (ok[i]) continue;
                int[] p = planned.get(i);
                if (!insideAny(reach, p[0], p[1])) continue;
                ok[i] = true;
                reach.add(new int[]{p[0], p[1], PLANNED_RANGE});
                changed = true;
            }
        }
        valid = ok;
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (ok[i]) out.add(new int[]{planned.get(i)[0], planned.get(i)[1], PLANNED_RANGE});
        }
        return out;
    }

    private static boolean insideAny(List<int[]> circles, int x, int z) {
        for (int[] c : circles) {
            double dx = x - c[0], dz = z - c[1];
            if (dx * dx + dz * dz <= (double) c[2] * c[2]) return true;
        }
        return false;
    }

    private record Hit(int x, int y, int w, int h, int index) {}   // index -1 = the "+" chip

    public static boolean isActive() {
        var cfg = TownyMapMod.getConfig();
        return cfg != null && cfg.townStatusOverlayMode == MODE;
    }

    /** The nation chosen for planning, or "" when the mode is still waiting for one. */
    public static String nation() { return nation; }
    public static boolean hasNation() { return !nation.isBlank(); }

    /** Sets the nation to plan for and shows its range. Called by the search bar and the info-panel button. */
    public static void setNation(String name) {
        if (name == null || name.isBlank()) return;
        if (!name.equalsIgnoreCase(nation)) { planned.clear(); version++; }   // new nation, new plan
        nation = name;
        TownSearchOverlay.showNationRange(name);
    }

    /** Leaving the mode (or switching nations) throws the plan away — it was never real. */
    public static void reset() {
        nation = "";
        planned.clear();
        armed = false;
        hits.clear();
        valid = new boolean[0];
        hovered = -1;
        version++;
    }

    public static boolean isArmed() { return armed; }

    /** Planned town centres as {x, z, radius} circles, for the join-range union. */
    public static List<int[]> plannedCircles() {
        if (planned.isEmpty()) return List.of();
        List<int[]> out = new ArrayList<>(planned.size());
        for (int[] p : planned) out.add(new int[]{p[0], p[1], PLANNED_RANGE});
        return out;
    }

    /** Drops a planned town at world (x, z) and disarms. Returns true if it consumed the click. */
    public static boolean placeAt(double worldX, double worldZ) {
        if (!armed || !hasNation()) return false;
        planned.add(new int[]{(int) Math.round(worldX), (int) Math.round(worldZ)});
        armed = false;
        version++;
        return true;
    }

    // ── Counter HUD ──────────────────────────────────────────────────────────

    public static void render(DrawContext ctx, TextRenderer tr, int sw, int sh) {
        hits.clear();
        if (!isActive()) return;

        boolean scaled = UiScale.active();
        if (scaled) UiScale.push(ctx, LEFT, TOP);

        if (!hasNation()) {
            String msg = "Planning · choose a nation";
            int w = tr.getWidth(msg) + 10;
            ctx.fill(LEFT - 1, TOP - 1, LEFT + w + 1, TOP + CHIP_H + 1, BORDER);
            ctx.fill(LEFT, TOP, LEFT + w, TOP + CHIP_H, BG);
            ctx.drawText(tr, msg, LEFT + 5, TOP + 3, CHIP_TEXT, false);
            if (scaled) UiScale.pop(ctx);
            return;
        }

        int count = planned.size() + 1;                     // + the "+" chip
        int chipW = Math.max(26, tr.getWidth("T" + Math.max(1, planned.size())) + 12);

        // Chips fill left→right and wrap onto a new row underneath. The grid is capped at the screen middle
        // horizontally, and must finish above the map-toggle column so it never overlaps those buttons.
        int gridTop = TOP + CHIP_H + CHIP_GAP + 2;
        int available = Math.max(chipW, sw / 2 - LEFT);
        int perRow = Math.max(1, (available + COL_GAP) / (chipW + COL_GAP));
        int bottomLimit = MapToggleOverlay.togglesTop(sh) - 4;
        int rowsThatFit = Math.max(1, (bottomLimit - gridTop) / (CHIP_H + CHIP_GAP));
        int rowsNeeded = (count + perRow - 1) / perRow;
        if (rowsNeeded > rowsThatFit) {   // very long plans: widen the rows rather than run into the toggles
            perRow = Math.max(perRow, (count + rowsThatFit - 1) / rowsThatFit);
        }

        int mx = scaled ? (int) Math.round(UiScale.unscale(mouseX(), LEFT)) : mouseX();
        int my = scaled ? (int) Math.round(UiScale.unscale(mouseY(), TOP)) : mouseY();

        // Header shows which nation the plan belongs to, and flags any towns that can't legally be reached.
        int bad = 0;
        for (int i = 0; i < planned.size(); i++) if (!isValid(i)) bad++;
        String head = nation + " · +" + planned.size() + (bad > 0 ? " · " + bad + " out of range" : "");
        int headW = Math.max(chipW, tr.getWidth(head) + 10);
        ctx.fill(LEFT - 1, TOP - 1, LEFT + headW + 1, TOP + CHIP_H + 1, BORDER);
        ctx.fill(LEFT, TOP, LEFT + headW, TOP + CHIP_H, BG);
        ctx.drawText(tr, head, LEFT + 5, TOP + 3, bad > 0 ? CHIP_BAD : CHIP_TEXT, false);

        int hoverIdx = -1;
        int usedRows = 0;
        for (int i = 0; i < count; i++) {
            int col = i % perRow, row = i / perRow;
            usedRows = row + 1;
            int cx = LEFT + col * (chipW + COL_GAP);
            int cy = gridTop + row * (CHIP_H + CHIP_GAP);
            boolean isPlus = i == planned.size();
            boolean hov = mx >= cx && mx < cx + chipW && my >= cy && my < cy + CHIP_H;
            boolean lit = isPlus && armed;
            boolean badChip = !isPlus && !isValid(i);
            if (hov && !isPlus) hoverIdx = i;

            int frame = lit ? CHIP_ON : badChip ? CHIP_BAD : BORDER;
            ctx.fill(cx - 1, cy - 1, cx + chipW + 1, cy + CHIP_H + 1, frame);
            ctx.fill(cx, cy, cx + chipW, cy + CHIP_H, badChip ? CHIP_BAD_BG : CHIP_BG);
            String label = isPlus ? "+" : "T" + (i + 1);
            int tw = tr.getWidth(label);
            ctx.drawText(tr, label, cx + (chipW - tw) / 2, cy + 3,
                    lit ? CHIP_ON : badChip ? CHIP_BAD : hov ? 0xFFFFFFFF : CHIP_TEXT, false);
            hits.add(new Hit(cx, cy, chipW, CHIP_H, isPlus ? -1 : i));
        }
        hovered = hoverIdx;

        if (armed) {   // tell the user what the armed "+" is waiting for
            int hy = gridTop + usedRows * (CHIP_H + CHIP_GAP) + 2;
            String hint = "Click the map to place";
            int hw = tr.getWidth(hint) + 10;
            if (hy + CHIP_H < bottomLimit) {
                ctx.fill(LEFT - 1, hy - 1, LEFT + hw + 1, hy + CHIP_H + 1, BORDER);
                ctx.fill(LEFT, hy, LEFT + hw, hy + CHIP_H, BG);
                ctx.drawText(tr, hint, LEFT + 5, hy + 3, CHIP_ON, false);
            }
        }

        if (scaled) UiScale.pop(ctx);
    }

    /** Chip click handling. Left-click "+" arms placement; left-click a town chip removes it. */
    public static boolean handleClick(double screenX, double screenY) {
        if (!isActive() || !hasNation()) return false;
        double mx = UiScale.active() ? UiScale.unscale(screenX, LEFT) : screenX;
        double my = UiScale.active() ? UiScale.unscale(screenY, TOP) : screenY;
        for (Hit h : hits) {
            if (mx >= h.x() && mx < h.x() + h.w() && my >= h.y() && my < h.y() + h.h()) {
                if (h.index() < 0) armed = !armed;
                else if (h.index() < planned.size()) { planned.remove(h.index()); version++; }
                return true;
            }
        }
        return false;
    }

    private static int mouseX() {
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        return (int) (mc.mouse.getX() * mc.getWindow().getScaledWidth() / mc.getWindow().getWidth());
    }

    private static int mouseY() {
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        return (int) (mc.mouse.getY() * mc.getWindow().getScaledHeight() / mc.getWindow().getHeight());
    }
}
