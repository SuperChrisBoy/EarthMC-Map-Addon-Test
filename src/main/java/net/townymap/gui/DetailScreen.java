package net.townymap.gui;

import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.townymap.TownyMapMod;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The expanded information panel for a town, nation or player.
 *
 * <p>One screen renders all three: a page is just a list of typed blocks, so the town/nation/player
 * pages differ only in what {@link DetailPages} builds, not in layout code. Names inside list blocks are
 * clickable and push another panel with this screen as its parent, so Close walks back the way you came.
 *
 * <p>Styled to match {@link TownyMapConfigScreen} — translucent panel, accent rule, one scrolling body.
 * Long lists are collapsed by default; a 250-resident nation would otherwise bury everything else.
 */
public class DetailScreen extends Screen {

    public enum Kind { TOWN, NATION, PLAYER, ALLIANCE }

    /** A clickable entity reference. */
    public record Ref(Kind kind, String name) {}

    public sealed interface Block permits Cols, Wide, LegacyLine, Chips, Rule, NameList, LineList, RankList, RefLineList {}

    /**
     * One labelled value, optionally a link. {@code live} recomputes the text every frame, which is how
     * a value that is not known when the page is built — or that counts down — stays current: the blocks
     * are built once when the panel opens, so a plain string would freeze whatever was true then.
     */
    public record Col(String label, String value, Ref link, java.util.function.Supplier<String> live) {
        public Col(String label, String value) { this(label, value, null, null); }
        public Col(String label, String value, Ref link) { this(label, value, link, null); }
        public static Col live(String label, java.util.function.Supplier<String> supplier) {
            return new Col(label, "", null, supplier);
        }
        public String text() { return live != null ? live.get() : value; }
    }
    /** Up to three values side by side. */
    public record Cols(List<Col> cols) implements Block {}
    /** One value spanning the panel (board text, about, formatted name). */
    public record Wide(String label, String value) implements Block {}

    /** A one-line value rendered as a styled {@link Component} (for the colour-formatted player name). */
    public record LegacyLine(String label, Component value) implements Block {}
    /** Flag chips: filled when on, outlined when off. */
    public record Chips(List<String> labels, List<Boolean> states) implements Block {}
    public record Rule() implements Block {}
    /** Collapsible list of clickable names. */
    public record NameList(String title, List<Ref> refs) implements Block {}
    /** Collapsible list of plain text lines (warps, pacts). */
    public record LineList(String title, List<String> lines) implements Block {}
    /** One rank and the players holding it. */
    public record RankGroup(String rank, List<Ref> refs) {}
    /** Collapsible rank listing whose holders are clickable, unlike a plain LineList. */
    public record RankList(String title, List<RankGroup> groups) implements Block {}
    /** A clickable entity followed by descriptive text (pacts: the other nation, then its terms). */
    public record RefLine(Ref ref, String suffix) {}
    public record RefLineList(String title, List<RefLine> lines) implements Block {}

    /** A page: header plus blocks plus optional external links for the footer. */
    public record Page(Kind kind, String title, String subtitle, List<Block> blocks,
                       String discordUrl, String wikiUrl) {}

    private static final int PANEL_TOP = 26;
    private static final int PAD = 14;
    private static final int FOOTER_H = 34;
    private static final int LINE = 11;
    private static final int GAP = 8;

    private static final int PANEL_BG = 0xB80E0F12;
    private static final int PANEL_BORDER = 0xCC3A3D42;
    private static final int ACCENT = 0xFF4FA37A;
    private static final int LABEL = 0xFF7F868F;
    private static final int VALUE = 0xFFE5E7EB;
    private static final int MUTED = 0xFF9CA3AF;
    private static final int LINK = 0xFF7FB2E8;
    private static final int ON_FG = 0xFF7FE0B0;
    private static final int OFF_FG = 0xFF6B7280;
    /** Hover colour for anything clickable — the same gold the favourite star uses. */
    private static final int HOVER = 0xFFFFE066;

    private final Screen parent;
    private Page page;                 // null while the fetch is in flight
    private final String pendingTitle;
    private boolean failed;
    private final Set<String> open = new LinkedHashSet<>();
    /** Hit boxes recorded during draw, consumed by the next click. */
    private final List<Hit> hits = new ArrayList<>();
    private record Hit(int x1, int y1, int x2, int y2, Ref ref, String collapseKey) {}

    private int panelLeft, panelWidth, cLeft, cRight;
    private int scroll, contentHeight;

    private Button backButton, discordButton, wikiButton;
    /** Panel bottom, from the previous frame's measured content — the panel hugs its content instead of
     *  always filling the screen, which left the footer stranded far below a short page. */
    private int panelBottom;

    public DetailScreen(Screen parent, Page page) {
        super(Component.literal(page.title()));
        this.parent = parent;
        this.page = page;
        this.pendingTitle = page.title();
    }

    /** Opens immediately in a loading state; {@link #setPage} fills it in when the fetch returns. */
    public DetailScreen(Screen parent, String pendingTitle) {
        super(Component.literal(pendingTitle));
        this.parent = parent;
        this.page = null;
        this.pendingTitle = pendingTitle;
    }

    public void setPage(Page p) {
        this.page = p;
        this.failed = false;
        if (this.minecraft != null) this.rebuildWidgets();
    }

    /** No such entity, or the lookup failed — say so rather than leaving a blank panel. */
    public void markFailed() {
        this.failed = true;
    }

    @Override
    protected void init() {
        panelWidth = Math.max(380, Math.min(this.width - 40, 540));
        panelLeft = (this.width - panelWidth) / 2;
        cLeft = panelLeft + PAD;
        cRight = panelLeft + panelWidth - PAD;

        // Footer: external links from the left, Close pinned right, evenly gapped.
        if (panelBottom <= 0) panelBottom = this.height - 8;

        int bx = cLeft;
        discordButton = null;
        wikiButton = null;
        if (page != null && page.discordUrl() != null && !page.discordUrl().isBlank()) {
            discordButton = Button.builder(Component.literal("Discord"),
                            x -> TownInfoOverlay.openDiscord(page.discordUrl()))
                    .bounds(bx, footerButtonY(), 64, 20).build();
            discordButton.setTooltip(Tooltip.create(Component.literal(page.discordUrl())));
            this.addRenderableWidget(discordButton);
            bx += 64 + GAP;
        }
        if (page != null && page.wikiUrl() != null && !page.wikiUrl().isBlank()) {
            wikiButton = Button.builder(Component.literal("Wiki"),
                            x -> TownInfoOverlay.openDiscord(page.wikiUrl()))
                    .bounds(bx, footerButtonY(), 52, 20).build();
            this.addRenderableWidget(wikiButton);
        }
        backButton = Button.builder(
                        Component.literal(parent == null ? "Close" : "Back"), x -> this.onClose())
                .bounds(cRight - 64, footerButtonY(), 64, 20).build();
        this.addRenderableWidget(backButton);
    }

    private int bodyTop() { return PANEL_TOP + 22; }
    private int bodyBottom() { return panelBottom - FOOTER_H + 8; }
    /** Y that vertically centres a 20px button inside the footer strip below the divider. */
    private int footerButtonY() { return bodyBottom() + ((panelBottom - bodyBottom()) - 20) / 2; }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        // UI Scale: shrink the whole panel around the screen centre; the mouse is un-scaled to match.
        if (!UiScale.active()) { drawContent(ctx, mouseX, mouseY, delta); return; }
        float cx = this.width / 2f, cy = this.height / 2f;
        int mx = (int) Math.round(UiScale.unscale(mouseX, cx));
        int my = (int) Math.round(UiScale.unscale(mouseY, cy));
        UiScale.push(ctx, cx, cy);
        try { drawContent(ctx, mx, my, delta); }
        finally { UiScale.pop(ctx); }
    }

    private void drawContent(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        int right = panelLeft + panelWidth;
        // Hug the content: measured last frame, clamped to the screen. A short page no longer leaves the
        // footer stranded at the bottom of a mostly-empty full-height panel.
        int wanted = bodyTop() + Math.max(40, contentHeight) + FOOTER_H;
        int bottom = Math.min(this.height - 8, wanted);
        if (bottom != panelBottom) {
            panelBottom = bottom;
            int by = footerButtonY();
            if (backButton != null) backButton.setY(by);
            if (discordButton != null) discordButton.setY(by);
            if (wikiButton != null) wikiButton.setY(by);
        }
        ctx.fill(panelLeft - 4, PANEL_TOP + 4, right + 4, bottom + 4, 0x66000000);
        ctx.fill(panelLeft - 1, PANEL_TOP - 1, right + 1, bottom + 1, PANEL_BORDER);
        ctx.fill(panelLeft, PANEL_TOP, right, bottom, PANEL_BG);
        ctx.fill(panelLeft, PANEL_TOP, right, PANEL_TOP + 3, ACCENT);

        String title = page != null ? page.title() : pendingTitle;
        ctx.text(this.font, title, cLeft, PANEL_TOP + 9, 0xFFFFFFFF, false);
        if (page != null && page.kind() == Kind.PLAYER) {   // player head in the top-right corner
            int hs = 18;
            net.townymap.render.PlayerHeadRenderer.drawMenuHead(ctx, page.title(),
                    right - 10 - hs / 2, PANEL_TOP + 2 + hs / 2, hs);
        }
        String sub = page != null ? page.subtitle() : (failed ? "not found" : "loading…");
        if (sub != null && !sub.isBlank()) {
            ctx.text(this.font, sub,
                    cLeft + this.font.width(title) + 6, PANEL_TOP + 9, MUTED, false);
        }
        ctx.fill(panelLeft, bodyTop() - 1, right, bodyTop(), 0x663A3D42);

        hits.clear();
        if (page == null) {
            String msg = failed ? "Nothing found for \"" + pendingTitle + "\"." : "Loading…";
            ctx.text(this.font, msg, cLeft, bodyTop() + 10, MUTED, false);
            contentHeight = 30;
        } else {
            ctx.enableScissor(panelLeft, bodyTop(), right, bodyBottom());
            int y = drawBlocks(ctx, mouseX, mouseY);
            ctx.disableScissor();
            contentHeight = y + scroll - (bodyTop() + 5) + 6;
            scroll = Math.max(0, Math.min(scroll, maxScroll()));
        }

        ctx.fill(panelLeft, bodyBottom(), right, bodyBottom() + 1, 0x663A3D42);
        ctx.fill(panelLeft, bodyBottom() + 1, right, bottom, 0xAA14161A);
        drawScrollbar(ctx);
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        // The vanilla widgets self-render in the light textured style, so with "Dark Buttons" on we paint
        // the flat dark style over them. The real widgets stay underneath and keep handling clicks.
        if (DarkButtons.enabled()) {
            for (var child : this.children()) {
                if (child instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible) {
                    DarkButtons.draw(ctx, w.getX(), w.getY(), w.getWidth(), w.getHeight(),
                            w.getMessage().getString(), w.active, 0xFFFFFFFF, mouseX, mouseY);
                }
            }
        }
    }

    private int maxScroll() { return Math.max(0, contentHeight - (bodyBottom() - bodyTop())); }

    private void drawScrollbar(GuiGraphicsExtractor ctx) {
        int max = maxScroll();
        if (max <= 0) return;
        int top = bodyTop(), bot = bodyBottom(), h = bot - top;
        int thumb = Math.max(20, h * h / Math.max(1, contentHeight));
        int ty = top + (h - thumb) * scroll / max;
        int x = panelLeft + panelWidth - 5;
        ctx.fill(x, top + 2, x + 2, bot - 2, 0x553A3D42);
        ctx.fill(x, ty, x + 2, ty + thumb, 0xFF9CA3AF);
    }

    private int drawBlocks(GuiGraphicsExtractor ctx, int mouseX, int mouseY) {
        int y = bodyTop() + 5 - scroll;
        for (Block b : page.blocks()) {
            if (b instanceof Rule) {
                y += 2;
                ctx.fill(cLeft, y, cRight, y + 1, 0x443A3D42);
                y += 6;
            } else if (b instanceof Wide w) {
                ctx.text(this.font, w.label(), cLeft, y, LABEL, false);
                y += LINE;
                for (var l : this.font.split(Component.literal(w.value()), cRight - cLeft)) {
                    ctx.text(this.font, l, cLeft, y, VALUE, false);
                    y += LINE;
                }
                y += 3;
            } else if (b instanceof LegacyLine ll) {
                ctx.text(this.font, ll.label(), cLeft, y, LABEL, false);
                y += LINE;
                ctx.text(this.font, ll.value(), cLeft, y, VALUE, false);   // styled Component (real colours)
                y += LINE + 3;
            } else if (b instanceof Cols c) {
                int n = Math.max(1, c.cols().size());
                int colW = (cRight - cLeft - GAP * (n - 1)) / n;
                for (int i = 0; i < c.cols().size(); i++) {
                    Col col = c.cols().get(i);
                    int x = cLeft + i * (colW + GAP);
                    ctx.text(this.font, col.label(), x, y, LABEL, false);
                    boolean link = col.link() != null;
                    String shown = this.font.plainSubstrByWidth(col.text(), colW);
                    int w = this.font.width(shown);
                    boolean hot = link && mouseX >= x && mouseX < x + w
                            && mouseY >= y + LINE - 1 && mouseY < y + LINE + 9
                            && mouseY >= bodyTop() && mouseY < bodyBottom();
                    ctx.text(this.font, shown, x, y + LINE,
                            hot ? HOVER : link ? LINK : VALUE, false);
                    if (link) hits.add(new Hit(x, y + LINE - 1, x + w, y + LINE + 9, col.link(), null));
                }
                y += LINE * 2 + 4;
            } else if (b instanceof Chips ch) {
                int x = cLeft;
                for (int i = 0; i < ch.labels().size(); i++) {
                    String lab = ch.labels().get(i);
                    boolean on = ch.states().get(i);
                    int w = this.font.width(lab) + 10;
                    if (x + w > cRight) { x = cLeft; y += 13; }
                    ctx.fill(x, y - 1, x + w, y + 10, on ? 0x557FE0B0 : 0x1AFFFFFF);
                    ctx.text(this.font, lab, x + 5, y + 1, on ? ON_FG : OFF_FG, false);
                    x += w + 4;
                }
                y += 17;
            } else if (b instanceof NameList nl) {
                y = drawCollapsible(ctx, mouseX, mouseY, y, nl.title(), nl.refs().size(), nl);
            } else if (b instanceof LineList ll) {
                y = drawCollapsible(ctx, mouseX, mouseY, y, ll.title(), ll.lines().size(), ll);
            } else if (b instanceof RankList rl) {
                y = drawCollapsible(ctx, mouseX, mouseY, y, rl.title(), rl.groups().size(), rl);
            } else if (b instanceof RefLineList rll) {
                y = drawCollapsible(ctx, mouseX, mouseY, y, rll.title(), rll.lines().size(), rll);
            }
        }
        return y;
    }

    /** Header row for a collapsible block, then its contents when open. */
    private int drawCollapsible(GuiGraphicsExtractor ctx, int mouseX, int mouseY, int y,
                                String title, int count, Block body) {
        boolean isOpen = open.contains(title);
        boolean hover = mouseY >= y - 2 && mouseY < y + 11 && mouseX >= cLeft && mouseX <= cRight
                && mouseY >= bodyTop() && mouseY < bodyBottom();
        if (hover) ctx.fill(cLeft - 5, y - 2, cRight + 3, y + 11, 0x18FFFFFF);
        ctx.fill(cLeft, y, cLeft + 1, y + 9, ACCENT);
        ctx.text(this.font, (isOpen ? "▾ " : "▸ ") + title, cLeft + 6, y,
                isOpen ? 0xFFFFFFFF : VALUE, false);
        String c = String.valueOf(count);
        ctx.text(this.font, c, cRight - this.font.width(c), y, LABEL, false);
        hits.add(new Hit(cLeft - 5, y - 2, cRight + 3, y + 11, null, title));
        y += 14;
        if (!isOpen) return y;

        if (body instanceof NameList nl) {
            // Names flow inline and wrap, each with its own hit box so it can be clicked through.
            int x = cLeft + 10;
            for (int i = 0; i < nl.refs().size(); i++) {
                Ref ref = nl.refs().get(i);
                String txt = ref.name() + (i < nl.refs().size() - 1 ? "," : "");
                int w = this.font.width(txt);
                if (x + w > cRight) { x = cLeft + 10; y += LINE; }
                boolean hot = mouseX >= x && mouseX < x + w && mouseY >= y - 1 && mouseY < y + 9
                        && mouseY >= bodyTop() && mouseY < bodyBottom();
                ctx.text(this.font, txt, x, y, hot ? HOVER : LINK, false);
                hits.add(new Hit(x, y - 1, x + w, y + 9, ref, null));
                x += w + 4;
            }
            y += LINE + 3;
        } else if (body instanceof RankList rl) {
            for (RankGroup g : rl.groups()) {
                int x = cLeft + 10;
                String head = g.rank() + ": ";
                ctx.text(this.font, head, x, y, LABEL, false);
                x += this.font.width(head);
                for (int i = 0; i < g.refs().size(); i++) {
                    Ref ref = g.refs().get(i);
                    String txt = ref.name() + (i < g.refs().size() - 1 ? "," : "");
                    int w = this.font.width(txt);
                    if (x + w > cRight) { x = cLeft + 18; y += LINE; }
                    boolean hot = mouseX >= x && mouseX < x + w && mouseY >= y - 1 && mouseY < y + 9
                            && mouseY >= bodyTop() && mouseY < bodyBottom();
                    ctx.text(this.font, txt, x, y, hot ? HOVER : LINK, false);
                    hits.add(new Hit(x, y - 1, x + w, y + 9, ref, null));
                    x += w + 4;
                }
                y += LINE;
            }
            y += 3;
        } else if (body instanceof RefLineList rll) {
            for (RefLine rl : rll.lines()) {
                int x = cLeft + 10;
                String nm = rl.ref().name();
                int w = this.font.width(nm);
                boolean hot = mouseX >= x && mouseX < x + w && mouseY >= y - 1 && mouseY < y + 9
                        && mouseY >= bodyTop() && mouseY < bodyBottom();
                ctx.text(this.font, nm, x, y, hot ? HOVER : LINK, false);
                hits.add(new Hit(x, y - 1, x + w, y + 9, rl.ref(), null));
                ctx.text(this.font,
                        this.font.plainSubstrByWidth(rl.suffix(), cRight - (x + w + 5)),
                        x + w + 5, y, MUTED, false);
                y += LINE;
            }
            y += 3;
        } else if (body instanceof LineList ll) {
            for (String line : ll.lines()) {
                for (var l : this.font.split(Component.literal(line), cRight - cLeft - 10)) {
                    ctx.text(this.font, l, cLeft + 10, y, MUTED, false);
                    y += LINE;
                }
            }
            y += 3;
        }
        return y;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (UiScale.active()) click = UiScale.unscaleClick(click, this.width / 2.0, this.height / 2.0);
        double mx = click.x(), my = click.y();

        // Click-away dismiss. Anything outside the panel closes it — and closes the whole stack, not one
        // level: after following names a few panels deep, clicking the map means "get out of this", not
        // "go back one". Buttons and content sit inside the rect, so they are unaffected.
        if (mx < panelLeft || mx > panelLeft + panelWidth || my < PANEL_TOP || my > panelBottom) {
            this.minecraft.gui.setScreen(rootParent());
            return true;
        }

        if (my >= bodyTop() && my < bodyBottom()) {
            for (Hit h : hits) {
                if (mx < h.x1() || mx > h.x2() || my < h.y1() || my > h.y2()) continue;
                if (h.collapseKey() != null) {
                    if (!open.remove(h.collapseKey())) open.add(h.collapseKey());
                    return true;
                }
                if (h.ref() != null) {
                    TownyMapMod.openDetail(h.ref().kind(), h.ref().name(), this);
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (UiScale.active()) {
            mouseX = UiScale.unscale(mouseX, this.width / 2.0);
            mouseY = UiScale.unscale(mouseY, this.height / 2.0);
        }
        int max = maxScroll();
        if (max <= 0) return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.round(vertical * 18.0)));
        return true;
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    /** The first ancestor that is not one of these panels — where a click-away should land. */
    private Screen rootParent() {
        Screen s = parent;
        while (s instanceof DetailScreen d) s = d.parent;
        return s;
    }
}
