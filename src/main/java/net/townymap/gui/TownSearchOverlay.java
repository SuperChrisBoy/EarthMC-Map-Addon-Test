package net.townymap.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.townymap.TownyMapMod;
import net.townymap.model.EarthMcNationData;
import net.townymap.model.EarthMcPlayerData;
import net.townymap.model.MapJumpTarget;
import net.townymap.model.NationBonusProjection;
import net.townymap.model.PlayerHistoryEntry;
import net.townymap.model.PlayerMarker;
import net.townymap.model.TownData;
import net.townymap.model.TownPopupData;
import net.townymap.util.DiscordUrl;
import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class TownSearchOverlay {

    private static final int WIDTH = 180;
    private static final int FAVORITES_WIDTH = 74;
    private static final int ROW_HEIGHT = 20;
    private static final int MAX_RESULTS = 7;
    private static final int MAX_PER_TYPE = 3;
    private static final int MAX_INFO_LINES = 11;
    private static final int BG = 0xD8101010;
    private static final int BORDER = 0xFF333333;
    private static final int ACTIVE_BORDER = 0xFFDDDDDD;
    private static final int HOVER = 0x553BFF3B;
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    private static boolean focused;
    private static String query = "";
    private static int caret = 0;             // cursor position within query (0..length)
    private static int selAnchor = -1;        // other end of a selection; -1 = none. Selection = [lo, hi]
    private static boolean textDragging = false;   // a left-drag started in the bar is extending the selection
    private static int selected;
    private static String selectedType = "";
    private static String selectedName = "";
    private static boolean favoritesOpen;

    /** The nation currently selected in the panel (via a capital-star click or a nation search), else null. */
    public static String selectedNationName() {
        return "nation".equals(selectedType) && selectedName != null && !selectedName.isBlank()
                ? selectedName : null;
    }

    // The nation whose join-range zone is being shown, toggled by the ◯ button in the panel's top-right.
    // Explicit rather than "whatever is selected", so the zone stays up while you look around the map.
    private static String rangeNation = null;

    /** The nation whose join range should be drawn, or null when the overlay is off. */
    public static String rangeNationName() { return rangeNation; }

    /** Turns the join-range overlay on for {@code nation} (null/blank clears it). */
    public static void showNationRange(String nation) {
        rangeNation = nation == null || nation.isBlank() ? null : nation;
    }

    /**
     * Entering Planning puts the bar straight into typing mode as a nation picker — unless a nation is
     * already on screen (e.g. you came from the info panel's range button), in which case it adopts that one
     * and skips the search entirely. Leaving the mode throws the plan away.
     */
    public static void onStatusModeChanged(int before, int after) {
        if (after == PlanningOverlay.MODE) {
            String preset = selectedNationName();
            if (preset == null) preset = rangeNation;
            if (preset != null && !preset.isBlank()) {
                PlanningOverlay.setNation(preset);   // already know the nation: no search step needed
                return;
            }
            query = "";
            focused = true;                          // straight into typing
            selected = 0;
        } else if (before == PlanningOverlay.MODE) {
            PlanningOverlay.reset();
            rangeNation = null;
        }
    }

    private static void toggleNationRange(String nation) {
        rangeNation = nation.equalsIgnoreCase(rangeNation == null ? "" : rangeNation) ? null : nation;
    }
    private static String cachedNeedle = null;
    private static int cachedTownCount = -1;
    private static int cachedTownDetailCount = -1;
    private static int cachedMarkerCount = -1;
    private static int cachedApiPlayerCount = -1;
    private static int cachedPlayerDetailCount = -1;
    private static int cachedPlayerHistoryCount = -1;
    private static int cachedNationCount = -1;
    private static int cachedNationDetailCount = -1;
    private static List<Result> cachedResults = List.of();
    private static int infoDiscordX, infoDiscordY, infoDiscordW, infoDiscordH;
    private static int infoExpandX, infoExpandY, infoExpandW, infoExpandH;
    private static boolean infoExpandVisible;
    private static int infoRangeX, infoRangeY, infoRangeW, infoRangeH;
    private static boolean infoRangeVisible;
    private static int infoAnchorX, infoAnchorY;   // the right-side panel's top-left, for UI-Scale hit-testing
    private static boolean infoDiscordVisible;
    private static String infoDiscordUrl = "";
    // Clickable name spans inside the selected-info panel (Mayor/King → player,
    // Nation/Capital → nation/town).  Rebuilt every render; consumed on click.
    private static final List<InfoLink> infoLinks = new ArrayList<>();
    private static final int LINK_COLOR = 0xFF8FB7FF;
    private static final int LINK_HOVER_COLOR = 0xFFFFE066;

    private TownSearchOverlay() {}

    public static void render(DrawContext ctx, int sw, int sh,
                              List<TownData> towns, List<PlayerMarker> players,
                              Map<String, TownPopupData> townDetails,
                              List<EarthMcPlayerData> apiPlayers,
                              Map<String, EarthMcPlayerData> playerDetails,
                              Map<String, PlayerHistoryEntry> playerHistory,
                              List<EarthMcNationData> apiNations,
                              Map<String, EarthMcNationData> nationDetails,
                              List<String> favoriteTowns) {
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;
        tickTextDrag(mc, sw);   // follow a left-drag over the bar (updates the caret/selection)
        int x = left(sw);
        int y = top();
        boolean scaled = UiScale.active();   // shrink around the bar's CENTRE so a centred bar stays centred
        if (scaled) UiScale.push(ctx, x + WIDTH / 2f, y + ROW_HEIGHT / 2f);
        int border = focused ? ACTIVE_BORDER : BORDER;

        ctx.fill(x - 1, y - 1, x + WIDTH + 1, y + ROW_HEIGHT + 1, border);
        ctx.fill(x, y, x + WIDTH, y + ROW_HEIGHT, BG);

        String display = query.isEmpty() && !focused ? "Search towns/nations/players" : query;
        int color = query.isEmpty() && !focused ? 0xFFAAAAAA : 0xFFFFFFFF;
        int textLeft = x + 7;
        if (focused && hasSelection()) {   // drag / Ctrl+A selection highlight behind the text
            int a = textLeft + tr.getWidth(query.substring(0, selLo()));
            int b = textLeft + tr.getWidth(query.substring(0, selHi()));
            ctx.fill(a, y + 3, b, y + ROW_HEIGHT - 2, 0x993B6FE0);
        }
        ctx.drawText(tr, display, textLeft, y + 5, color, true);
        if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {   // blinking caret, only as tall as the text
            int cx = textLeft + tr.getWidth(query.substring(0, Math.min(caret, query.length())));
            ctx.fill(cx, y + 5, cx + 1, y + 4 + tr.fontHeight, 0xFFFFFFFF);
        }
        renderFavorites(ctx, tr, favoritesX(x), y, sw, towns, favoriteTowns);

        // The results dropdown shows only while the bar is focused (actively
        // searching); once a result is chosen the info panel takes over.  The two
        // are mutually exclusive — focusing clears the selection, selecting unfocuses
        // — so they are never on screen at the same time.
        if (focused) {
            List<Result> results = results(towns, players, townDetails, apiPlayers,
                    playerDetails, playerHistory, apiNations, nationDetails);
            for (int i = 0; i < results.size(); i++) {
                Result result = results.get(i);
                int rowY = resultRowY(y, i);
                ctx.fill(x - 1, rowY - 1, x + WIDTH + 1, rowY + ROW_HEIGHT + 1, BORDER);
                ctx.fill(x, rowY, x + WIDTH, rowY + ROW_HEIGHT, i == selected ? HOVER : BG);
                ctx.drawText(tr, trimToWidth(tr, result.label(), WIDTH - 14), x + 7, rowY + 5, 0xFFFFFFFF, true);
            }
            // Empty bar: tell players how to open the historical archive (there's no other discoverable entry).
            // Kept short so the row lines up with the search bar's own width.
            if (query.isEmpty()) {
                // Planning mode needs a nation before it can do anything, so it takes over this row until
                // one is chosen; otherwise the row advertises the archive.
                boolean planningPrompt = PlanningOverlay.isActive() && !PlanningOverlay.hasNation();
                String hint = planningPrompt ? "§fPlease enter a nation" : "§7Type §fdd/mm/yyyy §7= archive";
                int rowY = resultRowY(y, 0);
                ctx.fill(x - 1, rowY - 1, x + WIDTH + 1, rowY + ROW_HEIGHT + 1, BORDER);
                ctx.fill(x, rowY, x + WIDTH, rowY + ROW_HEIGHT, BG);
                ctx.drawText(tr, trimToWidth(tr, hint, WIDTH - 14), x + 7, rowY + 5, 0xFFFFFFFF, true);
            }
        }
        // The selected info panel is tied to the search bar: an empty bar means no lingering right-clicked
        // or searched selection. (A real selection always keeps the bar populated — openSearch/select set
        // the query — so this only fires once the bar has actually been cleared.)
        if (scaled) UiScale.pop(ctx);   // end the bar/dropdown scale; the right-side info panel scales itself
        if (query.isEmpty()) clearSelection();
        renderSelectedInfo(ctx, tr, sw, sh,
                towns, players, townDetails, playerDetails, playerHistory, nationDetails);
    }

    public static ClickResult click(double mouseX, double mouseY, int sw,
                                    List<TownData> towns, List<PlayerMarker> players,
                                    Map<String, TownPopupData> townDetails,
                                    List<EarthMcPlayerData> apiPlayers,
                                    Map<String, EarthMcPlayerData> playerDetails,
                                    Map<String, PlayerHistoryEntry> playerHistory,
                                    List<EarthMcNationData> apiNations,
                                    Map<String, EarthMcNationData> nationDetails,
                                    List<String> favoriteTowns) {
        int x = left(sw);
        int y = top();
        // The bar/dropdown and the right-side info panel scale around different anchors, so un-scale the
        // mouse for each separately. The caret uses the RAW mouse (charIndexAtX un-scales it itself).
        double barMx = mouseX, barMy = mouseY, infoMx = mouseX, infoMy = mouseY;
        if (UiScale.active()) {
            barMx = UiScale.unscale(mouseX, x + WIDTH / 2.0); barMy = UiScale.unscale(mouseY, y + ROW_HEIGHT / 2.0);
            infoMx = UiScale.unscale(mouseX, infoAnchorX);    infoMy = UiScale.unscale(mouseY, infoAnchorY);
        }
        ClickResult favoriteClick = favoriteClick(barMx, barMy, favoritesX(x), y, towns, favoriteTowns);
        if (favoriteClick.consumed()) return favoriteClick;
        if (infoDiscordVisible && inside(infoMx, infoMy, infoDiscordX, infoDiscordY, infoDiscordW, infoDiscordH)) {
            TownInfoOverlay.openDiscord(infoDiscordUrl);
            return ClickResult.consumedResult();
        }
        if (infoRangeVisible && inside(infoMx, infoMy, infoRangeX, infoRangeY, infoRangeW, infoRangeH)) {
            toggleNationRange(selectedName);
            return ClickResult.consumedResult();
        }
        if (infoExpandVisible && inside(infoMx, infoMy, infoExpandX, infoExpandY, infoExpandW, infoExpandH)) {
            DetailScreen.Kind kind = switch (selectedType) {
                case "nation" -> DetailScreen.Kind.NATION;
                case "player" -> DetailScreen.Kind.PLAYER;
                default -> DetailScreen.Kind.TOWN;
            };
            TownyMapMod.openDetail(kind, selectedName, null);
            return ClickResult.consumedResult();
        }
        // Clicking a name inside the info panel re-searches for that entity.
        for (InfoLink link : infoLinks) {
            if (link.contains(infoMx, infoMy)) {
                activateLink(link);
                return ClickResult.consumedResult();
            }
        }

        if (inside(barMx, barMy, x, y, WIDTH, ROW_HEIGHT)) {
            focused = true;
            selected = 0;
            caret = charIndexAtX(sw, mouseX);   // place the cursor where they clicked (raw mouse)
            selAnchor = caret;                  // anchor a potential drag-select
            textDragging = true;
            clearSelection();                   // hide the info panel while typing a new search
            return ClickResult.consumedResult();
        }

        if (focused) {
            List<Result> results = results(towns, players, townDetails, apiPlayers,
                    playerDetails, playerHistory, apiNations, nationDetails);
            for (int i = 0; i < results.size(); i++) {
                int rowY = resultRowY(y, i);
                if (inside(barMx, barMy, x, rowY, WIDTH, ROW_HEIGHT)) {
                    selected = i;
                    Result result = results.get(i);
                    focused = false;
                    select(result);
                    return ClickResult.jump(result.target());
                }
            }
        }

        // Click landed outside the search UI: just close the dropdown. Do NOT clear the selected info here —
        // that's deferred to the map click-away dismiss (armMapClickDismiss), so panning keeps the result up.
        focused = false;
        textDragging = false;
        selAnchor = -1;
        return ClickResult.none();
    }

    /**
     * While a text-drag is active, follow the live mouse each frame (Xaero's GuiMap doesn't forward
     * mouseDragged to us, and the map can't pan because our click cancelled its press handler). Ends the drag
     * when the button is released — a press with no movement leaves just a caret.
     */
    private static void tickTextDrag(MinecraftClient mc, int sw) {
        if (!textDragging) return;
        boolean held = focused && mc.getWindow() != null
                && GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (held) {
            caret = charIndexAtX(sw, mc.mouse.getX() * sw / (double) mc.getWindow().getWidth());
        } else {
            textDragging = false;
            if (selAnchor == caret) selAnchor = -1;
        }
    }

    private static boolean hasSelection() { return selAnchor >= 0 && selAnchor != caret; }
    private static int selLo() { return Math.min(caret, selAnchor); }
    private static int selHi() { return Math.max(caret, selAnchor); }

    /** The caret index nearest a screen-space x within the bar (0..length). */
    private static int charIndexAtX(int sw, double mouseX) {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        if (UiScale.active()) mouseX = UiScale.unscale(mouseX, left(sw) + WIDTH / 2.0);   // scaled around the bar centre
        double target = mouseX - (left(sw) + 7);
        if (target <= 0) return 0;
        for (int i = 1; i <= query.length(); i++) {
            double mid = (tr.getWidth(query.substring(0, i - 1)) + tr.getWidth(query.substring(0, i))) / 2.0;
            if (target < mid) return i - 1;
        }
        return query.length();
    }

    private static void deleteSelection() {
        int lo = selLo(), hi = selHi();
        query = query.substring(0, lo) + query.substring(hi);
        caret = lo;
        selAnchor = -1;
    }

    /** Inserts text at the caret (replacing any selection), respecting the length cap. */
    private static void insertText(String s) {
        if (hasSelection()) deleteSelection();
        int room = MAX_QUERY - query.length();
        if (room <= 0 || s.isEmpty()) return;
        if (s.length() > room) s = s.substring(0, room);
        query = query.substring(0, caret) + s + query.substring(caret);
        caret += s.length();
        selAnchor = -1;
    }

    public static ClickResult keyPressed(int keyCode, List<TownData> towns, List<PlayerMarker> players,
                                         Map<String, TownPopupData> townDetails,
                                         List<EarthMcPlayerData> apiPlayers,
                                         Map<String, EarthMcPlayerData> playerDetails,
                                         Map<String, PlayerHistoryEntry> playerHistory,
                                         List<EarthMcNationData> apiNations,
                                         Map<String, EarthMcNationData> nationDetails) {
        if (!focused) return ClickResult.none();
        List<Result> results = results(towns, players, townDetails, apiPlayers,
                playerDetails, playerHistory, apiNations, nationDetails);

        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            focused = false;
            selAnchor = -1;
            return ClickResult.consumedResult();
        }
        // Ctrl+A selects the whole query.
        if (keyCode == GLFW.GLFW_KEY_A && ctrlDown()) {
            selAnchor = 0;
            caret = query.length();
            return ClickResult.consumedResult();
        }
        // Clipboard: Ctrl+C copy, Ctrl+X cut, Ctrl+V paste — on the selection if there is one, else the whole query.
        if (keyCode == GLFW.GLFW_KEY_C && ctrlDown()) {
            setClipboard(hasSelection() ? query.substring(selLo(), selHi()) : query);
            return ClickResult.consumedResult();
        }
        if (keyCode == GLFW.GLFW_KEY_X && ctrlDown()) {
            setClipboard(hasSelection() ? query.substring(selLo(), selHi()) : query);
            if (hasSelection()) deleteSelection(); else { query = ""; caret = 0; selAnchor = -1; }
            afterEdit();
            return ClickResult.consumedResult();
        }
        if (keyCode == GLFW.GLFW_KEY_V && ctrlDown()) {
            insertText(sanitizePaste(getClipboard()));
            afterEdit();
            return ClickResult.consumedResult();
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            if (hasSelection()) deleteSelection();
            else if (ctrlDown()) deleteWordBackward();
            else if (caret > 0) { query = query.substring(0, caret - 1) + query.substring(caret); caret--; }
            selAnchor = -1;
            afterEdit();
            return ClickResult.consumedResult();
        }
        if (keyCode == GLFW.GLFW_KEY_DELETE) {
            if (hasSelection()) deleteSelection();
            else if (caret < query.length()) query = query.substring(0, caret) + query.substring(caret + 1);
            selAnchor = -1;
            afterEdit();
            return ClickResult.consumedResult();
        }
        if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
            int to = keyCode == GLFW.GLFW_KEY_LEFT ? caret - 1 : caret + 1;
            moveCaret(Math.max(0, Math.min(query.length(), to)), shiftDown());
            return ClickResult.consumedResult();
        }
        if (keyCode == GLFW.GLFW_KEY_HOME) { moveCaret(0, shiftDown()); return ClickResult.consumedResult(); }
        if (keyCode == GLFW.GLFW_KEY_END)  { moveCaret(query.length(), shiftDown()); return ClickResult.consumedResult(); }
        if (keyCode == GLFW.GLFW_KEY_DOWN) {
            if (!results.isEmpty()) selected = Math.min(results.size() - 1, selected + 1);
            return ClickResult.consumedResult();
        }
        if (keyCode == GLFW.GLFW_KEY_UP) {
            selected = Math.max(0, selected - 1);
            return ClickResult.consumedResult();
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (!results.isEmpty()) {
                Result result = results.get(Math.min(selected, results.size() - 1));
                focused = false;
                select(result);
                return ClickResult.jump(result.target());
            }
            return ClickResult.consumedResult();
        }
        return ClickResult.consumedResult();
    }

    public static boolean charTyped(char chr) {
        if (!focused) return false;
        if (Character.isISOControl(chr)) return true;
        insertText(String.valueOf(chr));   // replaces any selection, inserts at the caret
        afterEdit();
        return true;
    }

    /** Post-edit housekeeping: reset the result cursor and rebuild the dropdown. */
    private static void afterEdit() {
        selected = 0;
        invalidateResults();
        clearSelection();
    }

    private static void moveCaret(int to, boolean extend) {
        if (extend) { if (selAnchor < 0) selAnchor = caret; }
        else selAnchor = -1;
        caret = Math.max(0, Math.min(query.length(), to));
    }

    /** Ctrl+Backspace: deletes the word (and any whitespace right before it) just before the caret. */
    private static void deleteWordBackward() {
        int i = caret;
        while (i > 0 && Character.isWhitespace(query.charAt(i - 1))) i--;
        while (i > 0 && !Character.isWhitespace(query.charAt(i - 1))) i--;
        query = query.substring(0, i) + query.substring(caret);
        caret = i;
    }

    private static boolean shiftDown() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getWindow() == null) return false;
        long h = mc.getWindow().getHandle();
        return GLFW.glfwGetKey(h, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(h, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    }

    private static final int MAX_QUERY = 60;

    private static void setClipboard(String s) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.keyboard != null && s != null) mc.keyboard.setClipboard(s);
    }

    private static String getClipboard() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null && mc.keyboard != null ? mc.keyboard.getClipboard() : "";
    }

    /** Strips control characters (newlines, tabs…) so a multi-line paste stays a single search line. */
    private static String sanitizePaste(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isISOControl(c)) b.append(c);
        }
        return b.toString();
    }

    /** True while Ctrl (or Cmd on macOS) is held — via raw GLFW, matching how this mod reads Shift elsewhere. */
    private static boolean ctrlDown() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getWindow() == null) return false;
        long h = mc.getWindow().getHandle();
        return GLFW.glfwGetKey(h, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(h, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(h, GLFW.GLFW_KEY_LEFT_SUPER) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(h, GLFW.GLFW_KEY_RIGHT_SUPER) == GLFW.GLFW_PRESS;
    }


    public static String query() {
        return query;
    }

    public static String exactPlayerQuery() {
        String trimmed = query.trim();
        if (trimmed.length() < 3) return "";
        if (!trimmed.matches("[A-Za-z0-9_]{3,16}")) return "";
        return trimmed;
    }

    public static List<String> visibleApiPlayerMatches(List<EarthMcPlayerData> apiPlayers) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.length() < 2) return List.of();

        ArrayList<EarthMcPlayerData> matches = new ArrayList<>();
        for (EarthMcPlayerData player : apiPlayers) {
            if (player.name().toLowerCase(Locale.ROOT).contains(needle)) matches.add(player);
        }
        matches.sort(Comparator
                .comparingInt((EarthMcPlayerData p) -> score(p.name(), needle))
                .thenComparing(EarthMcPlayerData::name, String.CASE_INSENSITIVE_ORDER));

        ArrayList<String> names = new ArrayList<>();
        int limit = Math.min(MAX_RESULTS, matches.size());
        for (int i = 0; i < limit; i++) names.add(matches.get(i).name());
        return List.copyOf(names);
    }

    public static List<String> visibleTownMatches(List<TownData> towns) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.length() < 2) return List.of();

        ArrayList<TownData> matches = new ArrayList<>();
        for (TownData town : towns) {
            String lowerName = town.name().toLowerCase(Locale.ROOT);
            if (lowerName.contains(needle)) matches.add(town);
        }
        matches.sort(Comparator
                .comparingInt((TownData t) -> score(t.name(), needle))
                .thenComparing(TownData::name, String.CASE_INSENSITIVE_ORDER));

        ArrayList<String> names = new ArrayList<>();
        int limit = Math.min(MAX_RESULTS, matches.size());
        for (int i = 0; i < limit; i++) names.add(matches.get(i).name());
        return List.copyOf(names);
    }

    public static List<String> visibleNationMatches(List<EarthMcNationData> apiNations) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.length() < 2) return List.of();

        ArrayList<EarthMcNationData> matches = new ArrayList<>();
        for (EarthMcNationData nation : apiNations) {
            if (nation.name().toLowerCase(Locale.ROOT).contains(needle)) matches.add(nation);
        }
        matches.sort(Comparator
                .comparingInt((EarthMcNationData n) -> score(n.name(), needle))
                .thenComparing(EarthMcNationData::name, String.CASE_INSENSITIVE_ORDER));

        ArrayList<String> names = new ArrayList<>();
        int limit = Math.min(MAX_RESULTS, matches.size());
        for (int i = 0; i < limit; i++) names.add(matches.get(i).name());
        return List.copyOf(names);
    }

    private static List<Result> results(List<TownData> towns, List<PlayerMarker> players,
                                        Map<String, TownPopupData> townDetails,
                                        List<EarthMcPlayerData> apiPlayers,
                                        Map<String, EarthMcPlayerData> playerDetails,
                                        Map<String, PlayerHistoryEntry> playerHistory,
                                        List<EarthMcNationData> apiNations,
                                        Map<String, EarthMcNationData> nationDetails) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return List.of();

        // While Planning waits for a nation the bar is a nation picker: no archive shortcut, no other types.
        boolean planningPick = PlanningOverlay.isActive() && !PlanningOverlay.hasNation();
        if (planningPick) {
            List<Result> out = new ArrayList<>();
            for (EarthMcNationData n : apiNations) {
                if (n.name() == null || !n.name().toLowerCase(Locale.ROOT).contains(needle)) continue;
                out.add(new Result(n.name(), null, 0, "nation", n.name()));
                if (out.size() >= MAX_RESULTS) break;
            }
            return out;
        }

        // A dd/mm/yyyy query is an archive request: load the Wayback snapshot nearest that date.
        int archiveDate = parseArchiveDate(query.trim());
        if (archiveDate > 0) {
            return List.of(new Result("View archive: " + archiveDateLabel(archiveDate),
                    null, 0, "archive", String.valueOf(archiveDate)));
        }
        if (needle.equals(cachedNeedle)
                && cachedTownCount == towns.size()
                && cachedTownDetailCount == townDetails.size()
                && cachedMarkerCount == players.size()
                && cachedApiPlayerCount == apiPlayers.size()
                && cachedPlayerDetailCount == playerDetails.size()
                && cachedPlayerHistoryCount == playerHistory.size()
                && cachedNationCount == apiNations.size()
                && cachedNationDetailCount == nationDetails.size()) {
            return cachedResults;
        }

        Comparator<Result> byScore = Comparator
                .comparingInt(Result::score)
                .thenComparing(Result::label, String.CASE_INSENSITIVE_ORDER);
        Map<String, TownData> townIndex = new HashMap<>(Math.max(16, towns.size() * 2));
        for (TownData town : towns) {
            townIndex.put(town.name().toLowerCase(Locale.ROOT), town);
        }
        Map<String, PlayerMarker> markerIndex = new HashMap<>(Math.max(16, players.size() * 2));
        for (PlayerMarker player : players) {
            markerIndex.put(player.name().toLowerCase(Locale.ROOT), player);
        }
        Set<String> apiPlayerNames = new HashSet<>(Math.max(16, apiPlayers.size() * 2));
        for (EarthMcPlayerData player : apiPlayers) {
            apiPlayerNames.add(player.name().toLowerCase(Locale.ROOT));
        }
        Set<String> playerDetailNames = new HashSet<>(Math.max(16, playerDetails.size() * 2));
        for (EarthMcPlayerData player : playerDetails.values()) {
            playerDetailNames.add(player.name().toLowerCase(Locale.ROOT));
        }

        // ── Towns ────────────────────────────────────────────────────────────
        ArrayList<Result> townMatches = new ArrayList<>();
        for (TownData town : towns) {
            String lowerName = town.name().toLowerCase(Locale.ROOT);
            if (lowerName.contains(needle)) {
                townMatches.add(new Result("Town: " + town.name(),
                        new MapJumpTarget(town.name(), town.centerX(), town.centerZ()),
                        score(town.name(), needle), "town", town.name()));
            }
        }
        townMatches.sort(byScore);
        if (townMatches.size() > MAX_PER_TYPE) townMatches.subList(MAX_PER_TYPE, townMatches.size()).clear();

        // ── Nations ──────────────────────────────────────────────────────────
        ArrayList<Result> nationMatches = new ArrayList<>();
        for (EarthMcNationData nation : apiNations) {
            String lowerName = nation.name().toLowerCase(Locale.ROOT);
            if (!lowerName.contains(needle)) continue;

            EarthMcNationData details = nationDetails.get(lowerName);
            String suffix = details == null ? "Checking" : capitalLabel(details);
            String allianceTag = TownyMapMod.allianceTagForNation(nation.name());
            if (!allianceTag.isEmpty()) suffix += " · " + allianceTag;
            nationMatches.add(new Result("Nation: " + nation.name() + " (" + suffix + ")",
                    nationTarget(nation.name(), details, townIndex),
                    score(nation.name(), needle), "nation", nation.name()));
        }
        nationMatches.sort(byScore);
        if (nationMatches.size() > MAX_PER_TYPE) nationMatches.subList(MAX_PER_TYPE, nationMatches.size()).clear();

        // ── Players ──────────────────────────────────────────────────────────
        ArrayList<Result> playerMatches = new ArrayList<>();
        for (EarthMcPlayerData player : apiPlayers) {
            String lowerName = player.name().toLowerCase(Locale.ROOT);
            if (!lowerName.contains(needle)) continue;

            PlayerMarker marker = markerIndex.get(lowerName);
            EarthMcPlayerData details = playerDetails.get(lowerName);
            String status = playerStatus(details, marker);
            PlayerHistoryEntry history = playerHistory.get(lowerName);
            MapJumpTarget target = playerTarget(player.name(), marker, details, history, townIndex);
            playerMatches.add(new Result("Player: " + player.name() + " (" + status + ")",
                    target, score(player.name(), needle), "player", player.name()));
        }
        for (PlayerMarker player : players) {
            String lowerName = player.name().toLowerCase(Locale.ROOT);
            if (lowerName.contains(needle)
                    && !apiPlayerNames.contains(lowerName)) {
                playerMatches.add(new Result("Player: " + player.name() + " (Online)",
                        new MapJumpTarget(player.name(), player.x(), player.z()),
                        score(player.name(), needle), "player", player.name()));
            }
        }
        for (EarthMcPlayerData player : playerDetails.values()) {
            String lowerName = player.name().toLowerCase(Locale.ROOT);
            if (!lowerName.contains(needle)) continue;
            if (apiPlayerNames.contains(lowerName)) continue;
            if (markerIndex.containsKey(lowerName)) continue;
            PlayerMarker marker = markerIndex.get(lowerName);
            PlayerHistoryEntry history = playerHistory.get(lowerName);
            playerMatches.add(new Result("Player: " + player.name() + " (" + playerStatus(player, marker) + ")",
                    playerTarget(player.name(), marker, player, history, townIndex),
                    score(player.name(), needle), "player", player.name()));
        }
        for (PlayerHistoryEntry history : playerHistory.values()) {
            String lowerName = history.name().toLowerCase(Locale.ROOT);
            if (!lowerName.contains(needle)) continue;
            if (apiPlayerNames.contains(lowerName)) continue;
            if (markerIndex.containsKey(lowerName)) continue;
            if (playerDetailNames.contains(lowerName)) continue;
            playerMatches.add(new Result("Player: " + history.name() + " (Last seen)",
                    new MapJumpTarget(history.name(), history.x(), history.z()),
                    score(history.name(), needle), "player", history.name()));
        }
        String exact = exactPlayerQuery();
        String exactKey = exact.toLowerCase(Locale.ROOT);
        if (!exact.isBlank()
                && playerMatches.stream().noneMatch(r -> r.name().equalsIgnoreCase(exact))
                && !apiPlayerNames.contains(exactKey)
                && !markerIndex.containsKey(exactKey)) {
            playerMatches.add(new Result("Player: " + exact + " (Checking)",
                    null, score(exact, needle), "player", exact));
        }
        playerMatches.sort(byScore);
        if (playerMatches.size() > MAX_PER_TYPE) playerMatches.subList(MAX_PER_TYPE, playerMatches.size()).clear();

        // ── Combine: interleave by rank so each type gets fair representation ─
        ArrayList<Result> combined = new ArrayList<>(townMatches.size() + nationMatches.size() + playerMatches.size());
        int ti = 0, ni = 0, pi = 0;
        while (combined.size() < MAX_RESULTS) {
            boolean added = false;
            if (ti < townMatches.size())   { combined.add(townMatches.get(ti++));   added = true; }
            if (combined.size() < MAX_RESULTS && ni < nationMatches.size())  { combined.add(nationMatches.get(ni++));  added = true; }
            if (combined.size() < MAX_RESULTS && pi < playerMatches.size())  { combined.add(playerMatches.get(pi++));  added = true; }
            if (!added) break;
        }
        List<Result> result = List.copyOf(combined);
        cachedNeedle = needle;
        cachedTownCount = towns.size();
        cachedTownDetailCount = townDetails.size();
        cachedMarkerCount = players.size();
        cachedApiPlayerCount = apiPlayers.size();
        cachedPlayerDetailCount = playerDetails.size();
        cachedPlayerHistoryCount = playerHistory.size();
        cachedNationCount = apiNations.size();
        cachedNationDetailCount = nationDetails.size();
        cachedResults = result;
        return result;
    }

    /** Public so external data loads (e.g. the alliance roster) can force the result labels to rebuild. */
    public static void invalidateResults() {
        cachedNeedle = null;
        cachedResults = List.of();
    }

    private static int score(String name, String needle) {
        if (needle.isEmpty()) return 0;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals(needle)) return 0;
        if (lower.startsWith(needle)) return 1;
        return 2 + lower.indexOf(needle);
    }

    private static PlayerMarker visibleMarker(String name, List<PlayerMarker> players) {
        for (PlayerMarker marker : players) {
            if (marker.name().equalsIgnoreCase(name)) return marker;
        }
        return null;
    }

    private static String playerStatus(EarthMcPlayerData details, PlayerMarker marker) {
        if (marker != null) return "Online";
        if (details == null) return "Checking";
        if (details.online()) return "Hidden";
        return "Offline";
    }

    private static MapJumpTarget playerTarget(String name, PlayerMarker marker,
                                              EarthMcPlayerData details, PlayerHistoryEntry history,
                                              Map<String, TownData> townIndex) {
        if (marker != null) return new MapJumpTarget(name, marker.x(), marker.z());
        if (history != null) return new MapJumpTarget(name, history.x(), history.z());
        if (details != null && !details.townName().isBlank()) {
            TownData town = townIndex.get(details.townName().toLowerCase(Locale.ROOT));
            if (town != null) {
                return new MapJumpTarget(name, town.centerX(), town.centerZ());
            }
        }
        return null;
    }

    private static MapJumpTarget nationTarget(String name, EarthMcNationData details, Map<String, TownData> townIndex) {
        if (details == null) return null;
        if (!details.capitalName().isBlank()) {
            TownData town = townIndex.get(details.capitalName().toLowerCase(Locale.ROOT));
            if (town != null) {
                return new MapJumpTarget(name, town.centerX(), town.centerZ());
            }
        }
        if (details.hasSpawn()) return new MapJumpTarget(name, details.spawnX(), details.spawnZ());
        return null;
    }

    private static String capitalLabel(EarthMcNationData details) {
        if (details == null || details.capitalName().isBlank()) return "No capital";
        return "Capital: " + details.capitalName();
    }

    /** Parses a dd/mm/yyyy date (lenient) to yyyymmdd, or 0 if invalid / before MIN_DATE. Public so the
     *  settings screen can offer the same entry point. */
    public static int parseArchiveDate(String s) {
        // Lenient dd/mm/yyyy: no leading zero needed (17/4/2026); the separator may be / . , - OR a space
        // (17 4 2026); and the year may be 2 or 4 digits (17 4 26 → 2026).
        String sep = "(?:\\s*[/.,-]\\s*|\\s+)";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^\\s*(\\d{1,2})" + sep + "(\\d{1,2})" + sep + "(\\d{4}|\\d{2})\\s*$").matcher(s);
        if (!m.matches()) return 0;
        int d = Integer.parseInt(m.group(1)), mo = Integer.parseInt(m.group(2)), y = Integer.parseInt(m.group(3));
        if (y < 100) y += 2000;   // two-digit year → 20xx
        if (d < 1 || d > 31 || mo < 1 || mo > 12) return 0;
        int yyyymmdd = y * 10000 + mo * 100 + d;
        return yyyymmdd >= net.townymap.api.ArchiveClient.MIN_DATE ? yyyymmdd : 0;
    }

    private static String archiveDateLabel(int yyyymmdd) {
        int y = yyyymmdd / 10000, mo = (yyyymmdd / 100) % 100, d = yyyymmdd % 100;
        String[] mon = {"", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
        return d + " " + mon[mo] + " " + y;
    }

    private static void select(Result result) {
        if ("archive".equals(result.type())) {
            clearSelection();
            query = "";
            caret = 0;
            selAnchor = -1;
            invalidateResults();
            TownyMapMod.enterArchive(Integer.parseInt(result.name()));
            return;
        }
        if ("town".equals(result.type())) {
            // Towns use the same rich popup as right-clicking the map (Route button + full details),
            // so the search bar and the map share one town GUI instead of a separate inline panel.
            clearSelection();
            TownyMapMod.openTownPopupFromSearch(result.name());
            return;
        }
        selectedType = result.type();
        selectedName = result.name();
        if ("nation".equals(result.type()) && PlanningOverlay.isActive()) {
            PlanningOverlay.setNation(result.name());   // the prompt clears once a nation is picked
        }
        TownInfoOverlay.dismiss();
    }

    private static void clearSelection() {
        selectedType = "";
        selectedName = "";
        infoDiscordVisible = false;
        infoExpandVisible = false;
        infoDiscordUrl = "";
        infoLinks.clear();
    }

    /** Fully clears the search bar — query, focus, and any selected info panel. Called when the world
     *  map is reopened or panned, so a stale search/selection doesn't linger. */
    public static void reset() {
        query = "";
        focused = false;
        selected = 0;
        caret = 0;
        selAnchor = -1;
        textDragging = false;
        favoritesOpen = false;
        cachedNeedle = null;
        clearSelection();
    }

    /**
     * Hides the search result info panel.  Called when the right-click town popup
     * (TownInfoOverlay) opens, so the two info panels are never shown at once.
     */
    public static void dismissSelection() {
        clearSelection();
    }

    /**
     * Switches the info panel to a name clicked inside it (Mayor/King → that
     * player, Nation/Capital → that nation/town), mirroring a search-bar lookup:
     * the query is updated so detail fetching kicks in, and the panel re-renders
     * for the clicked entity.  The camera is left where it is.
     */
    private static void activateLink(InfoLink link) {
        openSearch(link.type(), link.name());
    }

    /**
     * Opens the search info panel for a given entity, as if it had been typed and
     * selected in the search bar.  Used by the in-panel name links and by the
     * right-click town popup's clickable names.
     */
    public static void openSearch(String type, String name) {
        if (name == null || name.isBlank()) return;
        query = name;
        selected = 0;
        focused = false;
        caret = query.length();
        selAnchor = -1;
        textDragging = false;
        invalidateResults();
        if ("town".equals(type)) {
            // Town links resolve to the shared rich popup, matching a search selection / right-click.
            clearSelection();
            TownyMapMod.openTownPopupFromSearch(name);
            return;
        }
        selectedType = type;
        selectedName = name;
        infoLinks.clear();
    }

    /** A smooth 1px ring, drawn as short rotated quads so it reads as a circle rather than a stair-stepped box. */
    private static void drawRing(DrawContext ctx, double cx, double cy, double r, int color) {
        if (r < 1.0) return;
        int segments = Math.max(16, Math.min(64, (int) Math.round(r * 6)));
        org.joml.Matrix3x2fStack m = ctx.getMatrices();
        double px = cx + r, py = cy;
        for (int i = 1; i <= segments; i++) {
            double a = Math.PI * 2 * i / segments;
            double nx = cx + Math.cos(a) * r, ny = cy + Math.sin(a) * r;
            double dx = nx - px, dy = ny - py;
            double len = Math.hypot(dx, dy);
            if (len >= 0.01) {
                m.pushMatrix();
                try {
                    m.translate((float) px, (float) py);
                    m.rotate((float) Math.atan2(dy, dx));
                    m.scale((float) len, 1f);
                    ctx.fill(0, 0, 1, 1, color);
                } finally {
                    m.popMatrix();
                }
            }
            px = nx; py = ny;
        }
    }

    private static void renderSelectedInfo(DrawContext ctx, TextRenderer tr, int sw, int sh,
                                           List<TownData> towns, List<PlayerMarker> players,
                                           Map<String, TownPopupData> townDetails,
                                           Map<String, EarthMcPlayerData> playerDetails,
                                           Map<String, PlayerHistoryEntry> playerHistory,
                                           Map<String, EarthMcNationData> nationDetails) {
        infoDiscordVisible = false;
        infoExpandVisible = false;
        infoDiscordUrl = "";
        infoLinks.clear();
        if (selectedName.isBlank()) return;
        List<InfoRow> lines = selectedInfo(towns, players, townDetails, playerDetails,
                playerHistory, nationDetails);
        if (lines.isEmpty()) return;
        String discordUrl = selectedDiscordUrl(townDetails, nationDetails);
        boolean showDiscordButton = !discordUrl.isBlank();
        // Every entity the search panel can show has a full panel behind it, so Expand is always offered.
        boolean showExpand = !selectedType.isBlank();
        if (lines.size() > MAX_INFO_LINES) {
            lines = new ArrayList<>(lines.subList(0, MAX_INFO_LINES));
        }

        int maxW = 0;
        for (InfoRow row : lines) maxW = Math.max(maxW, rowWidth(tr, row));
        int boxW = Math.min(Math.max(WIDTH, maxW + 16), Math.max(WIDTH, sw - 24));
        int buttonRowHeight = (showDiscordButton || showExpand) ? ROW_HEIGHT + 7 : 0;
        int boxH = lines.size() * 12 + 14 + buttonRowHeight;
        int x = Math.max(8, sw - boxW - 12);
        int y = Math.max(36, Math.min(sh - boxH - 36, sh / 2 - boxH / 2));
        // Scale around the panel's RIGHT-MIDDLE so it stays pinned to the right edge and vertically centred.
        infoAnchorX = x + boxW; infoAnchorY = y + boxH / 2;
        boolean scaled = UiScale.active();
        if (scaled) UiScale.push(ctx, infoAnchorX, infoAnchorY);

        ctx.fill(x - 1, y - 1, x + boxW + 1, y + boxH + 1, BORDER);
        ctx.fill(x, y, x + boxW, y + boxH, BG);
        if ("player".equals(selectedType)) {   // player head in the top-right corner
            int hs = 16;
            net.townymap.render.PlayerHeadRenderer.drawMenuHead(ctx, selectedName, x + boxW - 5 - hs / 2, y + 5 + hs / 2, hs);
        }

        int mx = scaled ? (int) Math.round(UiScale.unscale(scaledMouseX(), infoAnchorX)) : scaledMouseX();
        int my = scaled ? (int) Math.round(UiScale.unscale(scaledMouseY(), infoAnchorY)) : scaledMouseY();

        // Join-range toggle: a small ring in the top-right of a nation's panel. Labels itself on hover so the
        // icon doesn't need explaining, and stays lit while its zone is on the map.
        infoRangeVisible = false;
        if ("nation".equals(selectedType) && TownyMapMod.getConfig().nationRangeEnabled) {
            int d = 13;
            infoRangeX = x + boxW - 6 - d;
            infoRangeY = y + 5;
            infoRangeW = d;
            infoRangeH = d;
            infoRangeVisible = true;
            boolean on = selectedName.equalsIgnoreCase(rangeNation == null ? "" : rangeNation);
            boolean hov = inside(mx, my, infoRangeX, infoRangeY, d, d);
            int ring = on ? 0xFF6FD3A0 : hov ? 0xFFFFFFFF : 0xFF9AA0A8;
            drawRing(ctx, infoRangeX + d / 2.0, infoRangeY + d / 2.0, d / 2.0 - 1.0, ring);
            if (on) ctx.fill(infoRangeX + d / 2 - 1, infoRangeY + d / 2 - 1,
                             infoRangeX + d / 2 + 1, infoRangeY + d / 2 + 1, ring);
            if (hov) {   // left of the ring, so it never runs off the screen edge
                String label = "Nation Range";
                int lw = tr.getWidth(label);
                int lx = infoRangeX - 4 - lw, ly = infoRangeY + 3;
                ctx.fill(lx - 3, ly - 3, lx + lw + 3, ly + 10, 0xE0101114);
                ctx.drawText(tr, label, lx, ly, 0xFFFFFFFF, false);
            }
        }
        int ty = y + 7;
        for (InfoRow row : lines) {
            if (row.hasLink()) {
                int prefixW = tr.getWidth(row.prefix());
                ctx.drawText(tr, row.prefix(), x + 7, ty, 0xFFFFFFFF, true);
                int nameX = x + 7 + prefixW;
                int nameW = tr.getWidth(row.name());
                boolean hovered = mx >= nameX && mx <= nameX + nameW && my >= ty - 1 && my <= ty + 9;
                ctx.drawText(tr, row.name(), nameX, ty, hovered ? LINK_HOVER_COLOR : LINK_COLOR, true);
                infoLinks.add(new InfoLink(nameX, ty - 1, nameW, 10, row.linkType(), row.name()));
            } else {
                ctx.drawText(tr, trimToWidth(tr, row.prefix(), boxW - 14), x + 7, ty, 0xFFFFFFFF, true);
            }
            ty += 12;
        }
        if (showDiscordButton || showExpand) {
            int btnY = y + boxH - ROW_HEIGHT - 7;
            int avail = boxW - 14;
            int gap = 5;
            int btnW = (showDiscordButton && showExpand)
                    ? Math.min(82, (avail - gap) / 2) : Math.min(82, avail);
            int bx = x + 7;
            if (showDiscordButton) {
                infoDiscordX = bx;
                infoDiscordY = btnY;
                infoDiscordW = btnW;
                infoDiscordH = ROW_HEIGHT;
                infoDiscordVisible = true;
                infoDiscordUrl = discordUrl;
                drawPanelButton(ctx, infoDiscordX, infoDiscordY, infoDiscordW, infoDiscordH, "Discord");
                bx += btnW + gap;
            }
            if (showExpand) {
                infoExpandX = bx;
                infoExpandY = btnY;
                infoExpandW = btnW;
                infoExpandH = ROW_HEIGHT;
                infoExpandVisible = true;
                drawPanelButton(ctx, infoExpandX, infoExpandY, infoExpandW, infoExpandH, "Expand");
            }
        }
        if (scaled) UiScale.pop(ctx);
    }

    private static void drawPanelButton(DrawContext ctx, int bx, int by, int bw, int bh, String label) {
        if (DarkButtons.enabled()) {
            DarkButtons.draw(ctx, bx, by, bw, bh, label, true, 0xFFFFFFFF, scaledMouseX(), scaledMouseY());
        } else {
            ButtonWidget button = ButtonWidget.builder(coloredText(label, 0xFFFFFF), ignored -> {})
                    .dimensions(bx, by, bw, bh).build();
            button.render(ctx, scaledMouseX(), scaledMouseY(), 0.0F);
        }
    }

    private static String selectedDiscordUrl(Map<String, TownPopupData> townDetails,
                                             Map<String, EarthMcNationData> nationDetails) {
        String key = selectedName.toLowerCase(Locale.ROOT);
        if ("town".equals(selectedType)) {
            TownPopupData details = townDetails.get(key);
            return details == null ? "" : normalizeDiscordUrl(details.discord());
        }
        if ("nation".equals(selectedType)) {
            EarthMcNationData details = nationDetails.get(key);
            return details == null ? "" : normalizeDiscordUrl(details.discord());
        }
        return "";
    }

    private static List<InfoRow> selectedInfo(List<TownData> towns, List<PlayerMarker> players,
                                              Map<String, TownPopupData> townDetails,
                                              Map<String, EarthMcPlayerData> playerDetails,
                                              Map<String, PlayerHistoryEntry> playerHistory,
                                              Map<String, EarthMcNationData> nationDetails) {
        if ("town".equals(selectedType)) {
            for (TownData town : towns) {
                if (town.name().equalsIgnoreCase(selectedName)) {
                    TownPopupData details = townDetails.get(selectedName.toLowerCase(Locale.ROOT));
                    return townInfo(town, details, nationDetails);
                }
            }
            return List.of();
        }
        if ("nation".equals(selectedType)) {
            EarthMcNationData details = nationDetails.get(selectedName.toLowerCase(Locale.ROOT));
            ArrayList<InfoRow> lines = new ArrayList<>();
            lines.add(InfoRow.text("§f§lNation: " + selectedName));
            if (details == null) {
                lines.add(InfoRow.text("§7Details: §fChecking..."));
                return List.copyOf(lines);
            }
            boolean archive = TownyMapMod.isArchiveMode();
            if (!details.capitalName().isBlank()) lines.add(InfoRow.link("§7Capital: §f", details.capitalName(), "town"));
            if (!archive) {   // alliance/meganation membership is only known live, not for the archived date
                List<String> nationMegas = TownyMapMod.meganationsForNation(selectedName);
                if (!nationMegas.isEmpty()) lines.add(InfoRow.text("§7Meganation: §f" + String.join(", ", nationMegas)));
                List<String> nationAllis = TownyMapMod.alliancesForNation(selectedName);
                if (!nationAllis.isEmpty())
                    lines.add(InfoRow.text("§7Alliance" + (nationAllis.size() > 1 ? "s" : "") + ": §f" + String.join(", ", nationAllis)));
            }
            if (!details.kingName().isBlank()) lines.add(InfoRow.link("§7King: §f", details.kingName(), "player"));
            if (!details.founded().isBlank()) lines.add(InfoRow.text("§7Founded: §f" + details.founded()));
            if (details.townCount() > 0) lines.add(InfoRow.text("§7Towns: §f" + details.townCount()));
            if (details.residentCount() > 0) {
                String inactive = details.activeResidentCount() >= 0
                        && details.activeResidentCount() < details.residentCount()
                        ? " §8(" + (details.residentCount() - details.activeResidentCount()) + " Inactive)" : "";
                lines.add(InfoRow.text("§7Residents: §f" + details.residentCount() + inactive));
                // Nation bonus on its own row, with a projection of the next level drop when known.
                // EarthMC computes the bonus on ACTIVE residents (inactive members are still counted in
                // residentCount but don't earn bonus), so use its authoritative stats.nationBonus — the
                // local tier-of-total-residents is only a fallback when the API didn't send it. Omitted in
                // archive mode: it's a formula on the total, not a value the snapshot actually recorded.
                if (!archive) {
                    int bonusValue = details.nationBonus() >= 0
                            ? details.nationBonus() : nationBonus(details.residentCount());
                    String bonusLine = "§7Bonus: §f" + bonusValue;
                    NationBonusProjection proj = TownyMapMod.nationBonusProjection(selectedName);
                    if (proj != null && proj.daysUntilDrop() >= 0) {
                        // Cascade the countdown: days → hours (<24h) → minutes (<1h). Sub-day units come from
                        // the absolute instant, so they're already offset-correct vs the ~noon-Berlin purge.
                        String when;
                        if (proj.minutesUntilDrop() < 60) when = proj.minutesUntilDrop() + "m";
                        else if (proj.minutesUntilDrop() < 1440) when = proj.hoursUntilDrop() + "h";
                        else when = proj.daysUntilDrop() + "d";
                        bonusLine += " §8(→" + proj.nextBonus() + " in " + when + ", " + proj.dropDate() + ")";
                    }
                    lines.add(InfoRow.text(bonusLine));
                }
            }
            if (details.chunkCount() > 0) lines.add(InfoRow.text("§7Chunks: §f" + details.chunkCount()));
            if (!archive) lines.add(InfoRow.text("§7Gold: §f" + formatGold(details.balance())));
            if (details.outlawCount() > 0) lines.add(InfoRow.text("§7Outlaws: §f" + details.outlawCount()));
            if (details.enemyCount() > 0) lines.add(InfoRow.text("§7Enemies: §f" + details.enemyCount()));
            return List.copyOf(lines);
        }
        if (!"player".equals(selectedType)) return List.of();

        // Archive mode: a player is only what the snapshot reveals — their town, its nation, and their rank.
        // Nothing live (status, last seen, gold, last online…) is shown.
        if (TownyMapMod.isArchiveMode()) {
            ArrayList<InfoRow> lines = new ArrayList<>();
            lines.add(InfoRow.text("§f§lPlayer: " + selectedName));
            TownyMapMod.ArchivePlayerInfo info = TownyMapMod.archivePlayerInfo(selectedName);
            if (info == null) {
                lines.add(InfoRow.text("§7Not in this snapshot"));
            } else {
                lines.add(InfoRow.link("§7Town: §f", info.town(), "town"));
                if (!info.nation().isBlank()) lines.add(InfoRow.link("§7Nation: §f", info.nation(), "nation"));
                lines.add(InfoRow.text("§7Rank: §f" + info.role()));
            }
            return List.copyOf(lines);
        }

        PlayerMarker marker = visibleMarker(selectedName, players);
        EarthMcPlayerData details = playerDetails.get(selectedName.toLowerCase(Locale.ROOT));
        PlayerHistoryEntry history = playerHistory.get(selectedName.toLowerCase(Locale.ROOT));
        String status = playerStatus(details, marker);

        ArrayList<InfoRow> lines = new ArrayList<>();
        lines.add(InfoRow.text("§f§lPlayer: " + selectedName));
        lines.add(InfoRow.text("§7Status: §f" + status));
        if (marker != null) lines.add(InfoRow.text("§7Location: §f" + marker.x() + ", " + marker.z()));
        else if (history != null) {
            lines.add(InfoRow.text("§7Last seen: §f" + history.x() + ", " + history.z()));
            lines.add(InfoRow.text("§7On map: §f" + dateWithAgo(history.lastSeenMs())));
        }
        if (details == null) {
            lines.add(InfoRow.text("§7Details: §fChecking..."));
            return List.copyOf(lines);
        }
        if (!details.townName().isBlank()) lines.add(InfoRow.link("§7Town: §f", details.townName(), "town"));
        if (!details.nationName().isBlank()) lines.add(InfoRow.link("§7Nation: §f", details.nationName(), "nation"));
        if (details.king()) {
            lines.add(InfoRow.text("§7Role: §fKing"));
        } else if (details.mayor()) {
            lines.add(InfoRow.text("§7Role: §fMayor"));
        }
        lines.add(InfoRow.text("§7Gold: §f" + formatGold(details.balance())));
        if (!details.registered().isBlank()) lines.add(InfoRow.text("§7Registered: §f" + details.registered()));
        // Always say something about presence. This used to be skipped entirely for a player visible on the
        // map, so the one case where the answer is simply "right now" showed nothing at all.
        if (marker != null || details.online()) {
            lines.add(InfoRow.text("§7Online: §anow"));
        } else if (details.lastOnlineMs() > 0) {
            lines.add(InfoRow.text("§7Last online: §f" + ageLabel(details.lastOnlineMs())
                    + " §7(" + details.lastOnline() + ")"));
        } else if (!details.lastOnline().isBlank()) {
            lines.add(InfoRow.text("§7Last online: §f" + details.lastOnline()));
        }
        return List.copyOf(lines);
    }

    private static boolean isNationCapital(String townName, String nationName,
                                           Map<String, EarthMcNationData> nationDetails) {
        if (nationName == null || nationName.isBlank() || nationDetails == null) return false;
        EarthMcNationData nation = nationDetails.get(nationName.toLowerCase(Locale.ROOT));
        return nation != null && nation.capitalName() != null
                && nation.capitalName().equalsIgnoreCase(townName);
    }

    private static List<InfoRow> townInfo(TownData town, TownPopupData details,
                                          Map<String, EarthMcNationData> nationDetails) {
        TownyMapMod.requestTownActive(town.name());   // active count on-demand for the opened town only
        ArrayList<InfoRow> lines = new ArrayList<>();
        lines.add(InfoRow.text("§f§lTown: " + town.name()));
        if (details == null) {
            lines.add(InfoRow.text("§7Chunks: §f" + town.approximateChunks()));
            lines.add(InfoRow.text("§7Details: §fChecking..."));
            return List.copyOf(lines);
        }
        if (!details.nationName().isBlank()) {
            boolean capital = isNationCapital(town.name(), details.nationName(), nationDetails);
            String label = capital ? "§7Capital of: §f" : "§7Nation: §f";
            lines.add(InfoRow.link(label, details.nationName(), "nation"));
        }
        if (!details.mayor().isBlank()) lines.add(InfoRow.link("§7Mayor: §f", details.mayor(), "player"));
        boolean overLimit = details.isOverClaimed()
                || (details.maxChunks() > 0 && details.numChunks() > details.maxChunks());
        String sizeColor = overLimit ? "§c" : "§f";
        String maxStr = details.maxChunks() > 0 ? " / " + details.maxChunks() : "";
        lines.add(InfoRow.text("§7Chunks: " + sizeColor + details.numChunks() + maxStr));
        if (!details.founded().isBlank()) lines.add(InfoRow.text("§7Founded: §f" + details.founded()));
        String townInactive = details.activeResidentCount() >= 0
                && details.activeResidentCount() < details.residentCount()
                ? " §8(" + (details.residentCount() - details.activeResidentCount()) + " Inactive)" : "";
        lines.add(InfoRow.text("§7Residents: §f" + details.residentCount() + townInactive));
        lines.add(InfoRow.text("§7Gold: §f" + formatGold(details.balance())));
        lines.add(InfoRow.text("§7Open: §f" + yesNo(details.isOpen())));
        lines.add(InfoRow.text("§7Public: §f" + yesNo(details.isPublic())));
        return List.copyOf(lines);
    }

    /** "MMM d, yyyy (5h ago)" — absolute date plus relative age for a timestamp. */
    private static String dateWithAgo(long timestampMs) {
        if (timestampMs <= 0) return "Unknown";
        String date = Instant.ofEpochMilli(timestampMs).atZone(ZoneOffset.UTC).toLocalDate().format(DATE_FMT);
        return date + " §7(" + ageLabel(timestampMs) + ")";
    }

    private static int rowWidth(TextRenderer tr, InfoRow row) {
        int w = tr.getWidth(row.prefix());
        if (row.hasLink()) w += tr.getWidth(row.name());
        return w;
    }

    private static String yesNo(boolean value) {
        return value ? "Yes" : "No";
    }

    private static String formatGold(double gold) {
        return Long.toString(Math.round(gold));
    }

    private static String normalizeDiscordUrl(String discord) {
        return DiscordUrl.normalize(discord);
    }

    private static int nationBonus(int residents) {
        if (residents >= 200) return 100;
        if (residents >= 120) return 80;
        if (residents >= 80) return 60;
        if (residents >= 60) return 50;
        if (residents >= 40) return 30;
        if (residents >= 20) return 10;
        return 0;
    }

    private static int possibleTownChunks(TownPopupData town, Map<String, EarthMcNationData> nationDetails) {
        int residentChunks = Math.max(0, town.residentCount()) * 12;
        if (town.nationName().isBlank()) return residentChunks;
        EarthMcNationData nation = nationDetails.get(town.nationName().toLowerCase(Locale.ROOT));
        return nation == null ? residentChunks : residentChunks + nationBonus(nation.residentCount());
    }

    private static String ageLabel(long timestampMs) {
        long seconds = Math.max(0, (System.currentTimeMillis() - timestampMs) / 1000);
        if (seconds < 60) return seconds + "s ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 48) return hours + "h ago";
        return (hours / 24) + "d ago";
    }

    private static void renderFavorites(DrawContext ctx, TextRenderer tr, int x, int y, int sw,
                                        List<TownData> towns, List<String> favoriteTowns) {
        String label = "Favorites";
        if (DarkButtons.enabled()) {
            DarkButtons.draw(ctx, x, y, FAVORITES_WIDTH, ROW_HEIGHT, label, true, 0xFFFFFFFF,
                    scaledMouseX(), scaledMouseY());
        } else {
            ButtonWidget button = ButtonWidget.builder(coloredText(label, 0xFFFFFF), ignored -> {})
                    .dimensions(x, y, FAVORITES_WIDTH, ROW_HEIGHT)
                    .build();
            button.render(ctx, scaledMouseX(), scaledMouseY(), 0.0F);
        }
        if (!favoritesOpen) return;

        List<TownData> favorites = favoriteTowns(towns, favoriteTowns);
        int rows = Math.min(MAX_RESULTS, favorites.size());
        for (int i = 0; i < rows; i++) {
            TownData town = favorites.get(i);
            int ty = resultRowY(y, i);
            ctx.fill(x - 1, ty - 1, x + FAVORITES_WIDTH + 1, ty + ROW_HEIGHT + 1, BORDER);
            ctx.fill(x, ty, x + FAVORITES_WIDTH, ty + ROW_HEIGHT, BG);
            ctx.drawText(tr, trimToWidth(tr, town.name(), FAVORITES_WIDTH - 14), x + 7, ty + 5, 0xFFFFFFFF, true);
        }
        if (favorites.isEmpty()) {
            int rowY = resultRowY(y, 0);
            ctx.fill(x - 1, rowY - 1, x + FAVORITES_WIDTH + 1, rowY + ROW_HEIGHT + 1, BORDER);
            ctx.fill(x, rowY, x + FAVORITES_WIDTH, rowY + ROW_HEIGHT, BG);
            ctx.drawText(tr, trimToWidth(tr, "No favorites", FAVORITES_WIDTH - 14), x + 7, rowY + 5, 0xFFAAAAAA, true);
        }
    }

    private static ClickResult favoriteClick(double mouseX, double mouseY, int x, int y,
                                             List<TownData> towns, List<String> favoriteTowns) {
        if (inside(mouseX, mouseY, x, y, FAVORITES_WIDTH, ROW_HEIGHT)) {
            favoritesOpen = !favoritesOpen;
            focused = false;
            return ClickResult.consumedResult();
        }

        if (favoritesOpen) {
            List<TownData> favorites = favoriteTowns(towns, favoriteTowns);
            int rows = Math.min(MAX_RESULTS, favorites.size());
            for (int i = 0; i < rows; i++) {
                int ty = resultRowY(y, i);
                if (inside(mouseX, mouseY, x, ty, FAVORITES_WIDTH, ROW_HEIGHT)) {
                    TownData town = favorites.get(i);
                    favoritesOpen = false;
                    focused = false;
                    return ClickResult.jump(town);
                }
            }
        }
        return ClickResult.none();
    }

    private static List<TownData> favoriteTowns(List<TownData> towns, List<String> favoriteNames) {
        ArrayList<TownData> favorites = new ArrayList<>();
        for (String name : favoriteNames) {
            TownData town = townByName(towns, name);
            if (town != null) favorites.add(town);
        }
        favorites.sort(Comparator.comparing(TownData::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(favorites);
    }

    private static TownData townByName(List<TownData> towns, String name) {
        for (TownData town : towns) {
            if (town.name().equalsIgnoreCase(name)) return town;
        }
        return null;
    }

    private static String trimToWidth(TextRenderer tr, String text, int width) {
        if (tr.getWidth(text) <= width) return text;
        String ellipsis = "...";
        int max = Math.max(1, width - tr.getWidth(ellipsis));
        return tr.trimToWidth(text, max) + ellipsis;
    }

    private static Text coloredText(String label, int textColor) {
        return Text.literal(label).setStyle(Style.EMPTY.withColor(textColor & 0xFFFFFF));
    }

    private static int scaledMouseX() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return (int) (mc.mouse.getX() * mc.getWindow().getScaledWidth() / mc.getWindow().getWidth());
    }

    private static int scaledMouseY() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return (int) (mc.mouse.getY() * mc.getWindow().getScaledHeight() / mc.getWindow().getHeight());
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
    }

    private static int left(int sw) {
        return Math.max(8, sw / 2 - WIDTH / 2);
    }

    private static int top() {
        MinecraftClient mc = MinecraftClient.getInstance();
        int sh = mc == null ? 360 : mc.getWindow().getScaledHeight();
        return Math.max(36, sh - ROW_HEIGHT - 18);
    }

    private static int favoritesX(int searchX) {
        return Math.max(8, searchX - FAVORITES_WIDTH - 7);
    }

    private static int resultRowY(int searchY, int index) {
        return searchY - 3 - (index + 1) * ROW_HEIGHT;
    }

    private record Result(String label, MapJumpTarget target, int score, String type, String name) {}

    /** A clickable name span in the info panel: bounds + what to search for. */
    private record InfoLink(int x, int y, int w, int h, String type, String name) {
        boolean contains(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    /**
     * One line of the selected-info panel.  {@code prefix} is the formatted label
     * text; if {@code linkType} is non-empty, {@code name} is drawn after it as a
     * clickable span that re-searches for that town/nation/player.
     */
    private record InfoRow(String prefix, String name, String linkType) {
        static InfoRow text(String formatted) { return new InfoRow(formatted, "", ""); }
        static InfoRow link(String prefix, String name, String linkType) {
            return new InfoRow(prefix, name, linkType);
        }
        boolean hasLink() { return !linkType.isEmpty() && !name.isEmpty(); }
    }

    public record ClickResult(boolean consumed, MapJumpTarget target) {
        public static ClickResult none() {
            return new ClickResult(false, null);
        }

        public static ClickResult consumedResult() {
            return new ClickResult(true, null);
        }

        public static ClickResult jump(TownData town) {
            return new ClickResult(true, new MapJumpTarget(town.name(), town.centerX(), town.centerZ()));
        }

        public static ClickResult jump(MapJumpTarget target) {
            return new ClickResult(true, target);
        }
    }
}
