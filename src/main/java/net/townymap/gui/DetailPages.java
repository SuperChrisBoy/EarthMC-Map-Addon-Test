package net.townymap.gui;

import net.townymap.gui.DetailScreen.Chips;
import net.townymap.gui.DetailScreen.Col;
import net.townymap.gui.DetailScreen.Cols;
import net.townymap.gui.DetailScreen.Kind;
import net.townymap.gui.DetailScreen.LineList;
import net.townymap.gui.DetailScreen.NameList;
import net.townymap.gui.DetailScreen.Page;
import net.townymap.gui.DetailScreen.Ref;
import net.townymap.gui.DetailScreen.Rule;
import net.townymap.api.ArchiveClient;
import net.townymap.model.EarthMcNationData;
import net.townymap.model.NationFullData;
import net.townymap.model.PlayerFullData;
import net.townymap.model.TownData;
import net.townymap.model.TownFullData;
import net.townymap.TownyMapMod;
import net.townymap.util.DiscordUrl;

import java.util.Locale;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds the block list for each kind of expanded panel. Layout lives in {@link DetailScreen}. */
public final class DetailPages {

    private DetailPages() {}

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault());

    private static String date(long ms) {
        return ms > 0 ? DATE.format(Instant.ofEpochMilli(ms)) : "—";
    }

    /** "3 months ago" / "4 minutes ago" — the relative form the Discord embeds use. */
    private static String ago(long ms) {
        if (ms <= 0) return "—";
        Duration d = Duration.between(Instant.ofEpochMilli(ms), Instant.now());
        long days = d.toDays();
        if (days >= 365) { long y = days / 365; return y + (y == 1 ? " year ago" : " years ago"); }
        if (days >= 30)  { long m = days / 30;  return m + (m == 1 ? " month ago" : " months ago"); }
        if (days >= 1)   return days + (days == 1 ? " day ago" : " days ago");
        long h = d.toHours();
        if (h >= 1) return h + (h == 1 ? " hour ago" : " hours ago");
        long m = Math.max(1, d.toMinutes());
        return m + (m == 1 ? " minute ago" : " minutes ago");
    }

    /** Rough human duration for a pact term. */
    private static String humanDuration(long ms) {
        long days = Duration.ofMillis(ms).toDays();
        if (days >= 365) { long y = days / 365; return y + (y == 1 ? " year" : " years"); }
        if (days >= 30)  { long m = days / 30;  return m + (m == 1 ? " month" : " months"); }
        if (days >= 1)   return days + (days == 1 ? " day" : " days");
        long h = Math.max(1, Duration.ofMillis(ms).toHours());
        return h + (h == 1 ? " hour" : " hours");
    }

    private static String money(double v) {
        return (v == Math.rint(v) ? String.valueOf((long) v) : String.format("%.2f", v)) + "g";
    }

    private static List<Ref> refs(List<String> names, Kind kind) {
        List<Ref> out = new ArrayList<>(names.size());
        for (String n : names) out.add(new Ref(kind, n));
        return out;
    }

    private static void addNames(List<DetailScreen.Block> blocks, String title, List<String> names, Kind kind) {
        if (names != null && !names.isEmpty()) blocks.add(new NameList(title, refs(names, kind)));
    }

    /**
     * Adds "Meganation" / "Alliances" rows for a nation, if it belongs to any (from the alliance roster).
     * Each bloc is a link through to its own panel, so a nation's memberships are explorable rather than
     * being a dead end of text.
     */
    private static void addAllianceBlocks(List<DetailScreen.Block> b, String nation) {
        if (nation == null || nation.isBlank()) return;
        addBlocRefs(b, TownyMapMod.meganationsForNation(nation), "Meganation", "Meganations");
        addBlocRefs(b, TownyMapMod.alliancesForNation(nation), "Alliance", "Alliances");
    }

    private static void addBlocRefs(List<DetailScreen.Block> b, List<String> names,
                                    String singular, String plural) {
        if (names.isEmpty()) return;
        List<DetailScreen.RefLine> lines = new ArrayList<>(names.size());
        for (String name : names) {
            net.townymap.api.AllianceClient.Alliance a = TownyMapMod.allianceByName(name);
            String suffix = a == null ? "" : "  " + a.nations().size() + " nations";
            lines.add(new DetailScreen.RefLine(new Ref(Kind.ALLIANCE, name), suffix));
        }
        b.add(new DetailScreen.RefLineList(names.size() > 1 ? plural : singular, lines));
    }

    // ── Alliance / meganation ─────────────────────────────────────────────────

    /**
     * The Expand panel for an alliance or meganation. Everything here comes from data already in memory —
     * the BreakTheBot roster plus the town list the map is drawing — so opening it costs no requests.
     */
    public static Page alliance(net.townymap.api.AllianceClient.Alliance a) {
        List<DetailScreen.Block> b = new ArrayList<>();
        List<String> nations = a.nations();

        // Roll the bloc's totals up from the towns already on the map, so they're complete even when the
        // per-nation detail fetches haven't caught up.
        int towns = 0;
        int chunks = 0;
        for (TownData t : TownyMapMod.currentTowns()) {
            String n = TownyMapMod.townNationOf(t.key());
            if (n == null || !a.nationsLower().contains(n.toLowerCase(Locale.ROOT))) continue;
            towns++;
            chunks += t.approximateChunks();
        }
        // Residents only exist in the nation records, which warm in the background; sum what has arrived.
        int residents = 0;
        boolean partial = false;
        for (String n : nations) {
            EarthMcNationData nd = TownyMapMod.nationDetails(n);
            if (nd == null) { partial = true; continue; }
            residents += nd.residentCount();
        }

        b.add(new Cols(List.of(
                new Col("Type", a.mega() ? "Meganation" : "Alliance", null),
                new Col("Tag", orDash(a.identifier()), null),
                new Col("Nations", String.valueOf(nations.size()), null))));
        b.add(new Cols(List.of(
                new Col("Towns", towns > 0 ? String.valueOf(towns) : "—", null),
                new Col("Chunks", chunks > 0 ? String.format("%,d", chunks) : "—", null),
                new Col("Residents", residents > 0 ? String.format("%,d", residents)
                        + (partial ? "+" : "") : "—", null))));
        b.add(new DetailScreen.Rule());

        List<DetailScreen.RefLine> lines = new ArrayList<>(nations.size());
        for (String n : nations) {
            EarthMcNationData nd = TownyMapMod.nationDetails(n);
            String suffix = nd == null ? "" : "  " + nd.townCount() + " towns";
            lines.add(new DetailScreen.RefLine(new Ref(Kind.NATION, n), suffix));
        }
        b.add(new DetailScreen.RefLineList("Members", lines));

        return new Page(Kind.ALLIANCE, a.label() == null || a.label().isBlank() ? a.identifier() : a.label(),
                (a.mega() ? "Meganation" : "Alliance") + " · " + nations.size() + " nations",
                b, "", "");
    }

    // ── Town ──────────────────────────────────────────────────────────────────

    public static Page town(TownFullData t) {
        List<DetailScreen.Block> b = new ArrayList<>();

        b.add(new Cols(List.of(
                new Col("Mayor", t.mayor(), new Ref(Kind.PLAYER, t.mayor())),
                new Col("Founded", date(t.registeredMs())),
                new Col("Founder", t.founder().isBlank() ? "—" : t.founder(),
                        t.founder().isBlank() ? null : new Ref(Kind.PLAYER, t.founder())))));

        if (t.hasNation() && !t.nation().isBlank()) {
            b.add(new Cols(List.of(
                    new Col("Nation", t.nation(), new Ref(Kind.NATION, t.nation())),
                    new Col("Joined nation", ago(t.joinedNationAtMs())),
                    new Col("Spawn", t.spawnX() + ", " + t.spawnY() + ", " + t.spawnZ()))));
        } else {
            b.add(new Cols(List.of(
                    new Col("Nation", "—"),
                    new Col("Spawn", t.spawnX() + ", " + t.spawnY() + ", " + t.spawnZ()))));
        }
        addAllianceBlocks(b, t.hasNation() ? t.nation() : "");

        if (!t.board().isBlank()) b.add(new DetailScreen.Wide("Board", t.board()));
        b.add(new Rule());

        String size = t.numTownBlocks() + " / " + (t.maxTownBlocks() >= 0 ? t.maxTownBlocks() : "?")
                + (t.bonusBlocks() > 0 ? "  (+" + t.bonusBlocks() + ")" : "");
        b.add(new Cols(List.of(
                new Col("Size", size),
                new Col("Bank", money(t.balance())),
                new Col("Nation bonus", String.valueOf(t.nationBonus())))));
        // No residents/trusted/outlawed counts here: the collapsible lists below already show them on
        // the right, and repeating them at the top just spent a row saying the same thing twice.
        if (t.isForSale() && t.forSalePrice() >= 0) {
            b.add(new Cols(List.of(new Col("For sale", money(t.forSalePrice())))));
        }
        b.add(overclaimCols(t));
        b.add(new Rule());

        b.add(chips(
                "public", t.isPublic(), "open", t.isOpen(), "neutral", t.isNeutral(),
                "capital", t.isCapital(), "overclaimed", t.isOverClaimed(), "ruined", t.isRuined(),
                "for sale", t.isForSale(), "outsider spawn", t.canOutsidersSpawn(),
                "pvp", t.pvp(), "explosions", t.explosion(), "fire", t.fire(), "mobs", t.mobs(),
                "passive mobs", t.canPassiveMobsSpawn(), "snow", t.hasSnowAccumulation(),
                "friendly fire", t.hasFriendlyFire()));

        addNames(b, "Residents", t.residents(), Kind.PLAYER);
        addNames(b, "Trusted", t.trusted(), Kind.PLAYER);
        addNames(b, "Outlawed", t.outlaws(), Kind.PLAYER);
        if (!t.quarters().isEmpty()) b.add(new LineList("Quarters", t.quarters()));
        if (!t.warps().isEmpty()) {
            List<String> w = new ArrayList<>();
            for (TownFullData.Warp warp : t.warps()) {
                w.add(warp.name() + " — " + warp.access() + " — " + warp.x() + ", " + warp.y() + ", " + warp.z()
                        + (warp.createdBy().isBlank() ? "" : " (by " + warp.createdBy() + ")"));
            }
            b.add(new LineList("Warps", w));
        }
        addRanks(b, t.occupiedRanks());

        String sub = t.hasNation() && !t.nation().isBlank()
                ? (t.isCapital() ? "capital of " + t.nation() : t.nation()) : "no nation";
        return new Page(Kind.TOWN, t.name(), sub, b, DiscordUrl.normalize(t.discord()), t.wiki());
    }

    /** The Expand panel for an ARCHIVED town — built only from what the Wayback snapshot recorded. Fields the
     *  archive doesn't have (chunks, bank, spawn, open/for-sale, discord, wiki…) are simply not shown. */
    public static Page archiveTown(ArchiveClient.ArchiveTown t) {
        List<DetailScreen.Block> b = new ArrayList<>();

        b.add(new Cols(List.of(
                new Col("Mayor", orDash(t.mayor()), t.mayor() == null || t.mayor().isBlank() ? null : new Ref(Kind.PLAYER, t.mayor())),
                new Col("Founded", orDash(t.founded())),
                new Col("Chunks", String.valueOf(t.chunks())))));

        if (t.nation() != null && !t.nation().isBlank()) {
            // No alliance/meganation rows here: that data is only available live, not for the archived date.
            b.add(new Cols(List.of(new Col("Nation", t.nation(), new Ref(Kind.NATION, t.nation())))));
        }
        if (t.board() != null && !t.board().isBlank()) b.add(new DetailScreen.Wide("Board", t.board()));
        b.add(new Rule());

        b.add(chips("public", t.isPublic(), "pvp", t.pvp()));
        addNames(b, "Residents", t.residents(), Kind.PLAYER);
        addNames(b, "Councillors", t.councillors(), Kind.PLAYER);

        String sub = (t.nation() == null || t.nation().isBlank()) ? "no nation"
                : (t.capital() ? "capital of " + t.nation() : t.nation());
        return new Page(Kind.TOWN, t.name(), sub, b, "", "");
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    /**
     * EarthMC's formattedName is MiniMessage (e.g. "&lt;dark_blue&gt; Maire Name"). Builds a styled MC
     * {@link net.minecraft.text.Text} from it — named colours/formats via {@link net.minecraft.util.Formatting},
     * and &lt;#rrggbb&gt; hex via {@link net.minecraft.text.TextColor} — so it renders in its true colour.
     */
    static net.minecraft.text.Text miniToText(String s) {
        net.minecraft.text.MutableText root = net.minecraft.text.Text.empty();
        if (s == null || s.isEmpty()) return root;
        net.minecraft.text.Style style = net.minecraft.text.Style.EMPTY;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<(/?)(#[0-9a-fA-F]{6}|[a-zA-Z_]+)(:[^>]*)?>").matcher(s);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) root.append(net.minecraft.text.Text.literal(s.substring(last, m.start())).setStyle(style));
            last = m.end();
            if (!m.group(1).isEmpty()) { style = net.minecraft.text.Style.EMPTY; continue; }   // closing → reset
            String tag = m.group(2).toLowerCase(java.util.Locale.ROOT);
            if (tag.startsWith("#") && tag.length() == 7) {
                try {
                    style = style.withColor(net.minecraft.text.TextColor.fromRgb(Integer.parseInt(tag.substring(1), 16)));
                } catch (NumberFormatException ignored) { /* keep style */ }
            } else if (tag.equals("reset")) {
                style = net.minecraft.text.Style.EMPTY;
            } else {
                net.minecraft.util.Formatting f = net.minecraft.util.Formatting.byName(tag);
                if (f != null) style = f.isColor() ? style.withColor(f) : style.withFormatting(f);   // unknown → dropped
            }
        }
        if (last < s.length()) root.append(net.minecraft.text.Text.literal(s.substring(last)).setStyle(style));
        return root;
    }

    /** The Expand panel for a player in ARCHIVE mode — only their residency that date (town, its nation, and
     *  their rank in it). No live data (last online, balance, registration…) exists in the snapshot. */
    public static Page archivePlayer(String name, String town, String nation, String role) {
        List<DetailScreen.Block> b = new ArrayList<>();
        List<Col> cols = new ArrayList<>();
        cols.add(new Col("Town", orDash(town), town == null || town.isBlank() ? null : new Ref(Kind.TOWN, town)));
        if (nation != null && !nation.isBlank()) cols.add(new Col("Nation", nation, new Ref(Kind.NATION, nation)));
        cols.add(new Col("Rank", orDash(role)));
        b.add(new Cols(cols));
        String sub = town == null || town.isBlank() ? "" : role.toLowerCase(java.util.Locale.ROOT) + " of " + town;
        return new Page(Kind.PLAYER, name, sub, b, "", "");
    }

    /** The Expand panel for an ARCHIVED nation — derived entirely from that date's member towns: the towns
     *  themselves, their residents and their chunk totals. Nation-level data the snapshot can't give (king,
     *  founded, bank, bonus, over-claim, allies…) is omitted. */
    public static Page archiveNation(String name, String capital, List<String> towns,
                                     List<String> residents, int chunks) {
        List<DetailScreen.Block> b = new ArrayList<>();
        b.add(new Cols(List.of(
                new Col("Capital", orDash(capital), capital == null || capital.isBlank() ? null : new Ref(Kind.TOWN, capital)),
                new Col("Towns", String.valueOf(towns.size())),
                new Col("Residents", String.valueOf(residents.size())))));
        b.add(new Cols(List.of(new Col("Chunks", String.valueOf(chunks)))));
        b.add(new Rule());
        addNames(b, "Towns", towns, Kind.TOWN);
        addNames(b, "Residents", residents, Kind.PLAYER);
        return new Page(Kind.NATION, name, towns.size() + " towns", b, "", "");
    }

    /**
     * Overclaim standing. A town is overclaimable once it holds more chunks than it can support, and that
     * limit already includes the nation bonus — so a bonus drop can tip a currently-safe town over without
     * it claiming anything. When that is what will happen, show the date it happens rather than a bare
     * "no", since the date is the part worth acting on.
     */
    private static Cols overclaimCols(TownFullData t) {
        return new Cols(List.of(Col.live("Overclaimable", () -> overclaimText(t))));
    }

    private static String overclaimText(TownFullData t) {
        if (t.maxTownBlocks() < 0) return "unknown";
        if (t.numTownBlocks() > t.maxTownBlocks()) return "yes, now";

        long now = System.currentTimeMillis();
        long soonest = Long.MAX_VALUE;
        String date = "";

        // Resident purges are the dominant term: each one costs a resident's worth of chunks, whereas the
        // nation bonus is a single smaller step. A town can sit far inside its limit today and still be
        // overclaimable in a few weeks purely because inactive residents drop off.
        var byResidents = TownyMapMod.townOverclaimProjection(t.name());
        if (byResidents != null && byResidents.known()) {
            soonest = byResidents.atMs();
            date = byResidents.date();
        }

        // The nation bonus falling can get there first for a town near its limit.
        if (t.hasNation()) {
            var bonus = TownyMapMod.nationBonusProjection(t.nation());
            if (bonus != null && bonus.nextBonus() < t.nationBonus() && bonus.dropAtMs() > 0) {
                int afterMax = t.maxTownBlocks() - (t.nationBonus() - bonus.nextBonus());
                if (t.numTownBlocks() > afterMax && bonus.dropAtMs() < soonest) {
                    soonest = bonus.dropAtMs();
                    date = bonus.dropDate();
                }
            }
        }

        if (soonest == Long.MAX_VALUE) return "no";
        int d = (int) Math.max(0, Math.ceil((soonest - now) / 86_400_000.0));
        String when = d > 1 ? d + " days" : d == 1 ? "1 day" : "today";
        return (date == null || date.isBlank() ? "" : date + ", ") + when;
    }

    // ── Player ────────────────────────────────────────────────────────────────

    public static Page player(PlayerFullData p) {
        List<DetailScreen.Block> b = new ArrayList<>();

        b.add(new Cols(List.of(
                new Col("Town", p.hasTown() && !p.town().isBlank() ? p.town() : "—",
                        p.hasTown() && !p.town().isBlank() ? new Ref(Kind.TOWN, p.town()) : null),
                new Col("Nation", p.hasNation() && !p.nation().isBlank() ? p.nation() : "—",
                        p.hasNation() && !p.nation().isBlank() ? new Ref(Kind.NATION, p.nation()) : null),
                new Col("Balance", money(p.balance())))));

        if (!p.formattedName().isBlank() && !p.formattedName().equals(p.name())) {
            b.add(new DetailScreen.LegacyLine("Formatted name", miniToText(p.formattedName())));
        }
        if (!p.about().isBlank()) b.add(new DetailScreen.Wide("About", p.about()));
        b.add(new Rule());

        b.add(new Cols(List.of(
                new Col("Registered", date(p.registeredMs())),
                new Col("Joined town", ago(p.joinedTownAtMs())),
                new Col(p.isOnline() ? "Online" : "Last online",
                        p.isOnline() ? "now" : ago(p.lastOnlineMs())))));
        b.add(new Rule());

        b.add(chips("online", p.isOnline(), "mayor", p.isMayor(), "king", p.isKing(),
                "has town", p.hasTown(), "has nation", p.hasNation(), "npc", p.isNPC()));

        if (!p.townRanks().isEmpty() || !p.nationRanks().isEmpty()) {
            List<String> r = new ArrayList<>();
            if (!p.townRanks().isEmpty()) r.add("Town: " + String.join(", ", p.townRanks()));
            if (!p.nationRanks().isEmpty()) r.add("Nation: " + String.join(", ", p.nationRanks()));
            b.add(new LineList("Ranks", r));
        }
        addNames(b, "Friends", p.friends(), Kind.PLAYER);

        String sub = p.hasTown() && !p.town().isBlank() ? p.town() : "townless";
        return new Page(Kind.PLAYER, p.name(), sub, b, "", "");
    }

    // ── Nation ────────────────────────────────────────────────────────────────

    public static Page nation(NationFullData n) {
        List<DetailScreen.Block> b = new ArrayList<>();

        b.add(new Cols(List.of(
                new Col("King", n.king().isBlank() ? "—" : n.king(),
                        n.king().isBlank() ? null : new Ref(Kind.PLAYER, n.king())),
                new Col("Capital", n.capital().isBlank() ? "—" : n.capital(),
                        n.capital().isBlank() ? null : new Ref(Kind.TOWN, n.capital())),
                new Col("Founded", date(n.registeredMs())))));

        if (!n.board().isBlank()) b.add(new DetailScreen.Wide("Board", n.board()));
        b.add(new Rule());

        b.add(new Cols(List.of(
                new Col("Chunks", String.valueOf(n.numTownBlocks())),
                new Col("Towns", String.valueOf(n.numTowns())),
                new Col("Residents", String.valueOf(n.numResidents())))));
        b.add(new Cols(List.of(
                new Col("Bank", money(n.balance())),
                // Live: the projection is fetched asynchronously and may not exist yet when this page is
                // built, and once it does the countdown has to keep moving while the panel stays open.
                Col.live("Nation bonus", () -> bonusText(n.name(), n.nationBonus())),
                new Col("Spawn", n.spawnX() + ", " + n.spawnY() + ", " + n.spawnZ()))));
        b.add(new Rule());

        b.add(chips("public", n.isPublic(), "open", n.isOpen(), "neutral", n.isNeutral()));

        addNames(b, "Towns", n.towns(), Kind.TOWN);
        addNames(b, "Allies", n.allies(), Kind.NATION);
        addNames(b, "Enemies", n.enemies(), Kind.NATION);
        addNames(b, "Sanctioned", n.sanctioned(), Kind.NATION);
        addNames(b, "Outlaws", n.outlaws(), Kind.PLAYER);
        addNames(b, "Residents", n.residents(), Kind.PLAYER);
        // Blocs sit with the other collapsible lists rather than up in the summary rows.
        addAllianceBlocks(b, n.name());
        addRanks(b, n.occupiedRanks());

        if (!n.pacts().isEmpty()) {
            List<DetailScreen.RefLine> p = new ArrayList<>();
            for (NationFullData.Pact pact : n.pacts()) {
                // A perpetual pact only has a start, so showing "expires —, duration —" was noise. A
                // fixed-term one gets its end and how long it runs.
                String terms = pact.status().toLowerCase(java.util.Locale.ROOT)
                        + " since " + date(pact.createdMs());
                if (pact.forever()) {
                    terms += " — never expires";
                } else {
                    terms += " — until " + date(pact.expiresAtMs());
                    if (pact.durationMs() > 0) terms += " (" + humanDuration(pact.durationMs()) + ")";
                }
                p.add(new DetailScreen.RefLine(
                        new Ref(Kind.NATION, pact.other(n.name())), terms));
            }
            b.add(new DetailScreen.RefLineList("Pacts", p));
        }
        List<String> emb = new ArrayList<>();
        for (String e : n.embargoesOwn()) emb.add("against " + e);
        for (String e : n.embargoesAgainst()) emb.add("from " + e);
        if (!emb.isEmpty()) b.add(new LineList("Embargoes", emb));

        return new Page(Kind.NATION, n.name(), n.numTowns() + " towns", b,
                DiscordUrl.normalize(n.discord()), n.wiki());
    }

    /**
     * "100 → 80 in 4d" once the projection lands, plain "100" until then. Asking for the projection also
     * kicks off the fetch when it is missing; that call is deduped by both an in-flight set and the cache,
     * so evaluating this every frame issues at most one request per nation.
     */
    private static String bonusText(String nation, int currentBonus) {
        var proj = TownyMapMod.nationBonusProjection(nation);
        if (proj == null || proj.nextBonus() >= currentBonus) return String.valueOf(currentBonus);
        long now = System.currentTimeMillis();
        if (proj.daysUntilDropAt(now) < 0) return String.valueOf(currentBonus);
        return currentBonus + " → " + proj.nextBonus() + " in " + countdown(proj, now);
    }

    /** Coarsest sensible unit: days out, then hours, then minutes. */
    private static String countdown(net.townymap.model.NationBonusProjection proj, long now) {
        int d = proj.daysUntilDropAt(now);
        if (d > 1) return d + "d";
        int h = proj.hoursUntilDropAt(now);
        if (h > 1) return h + "h";
        return Math.max(1, proj.minutesUntilDropAt(now)) + "m";
    }

    // ── shared ────────────────────────────────────────────────────────────────

    /** Rank holders are players, so they get the same clickable treatment as any other name list. */
    private static void addRanks(List<DetailScreen.Block> b, Map<String, List<String>> ranks) {
        if (ranks.isEmpty()) return;
        List<DetailScreen.RankGroup> groups = new ArrayList<>();
        ranks.forEach((rank, holders) -> groups.add(
                new DetailScreen.RankGroup(rank, refs(holders, Kind.PLAYER))));
        b.add(new DetailScreen.RankList("Ranks", groups));
    }

    /** chips("label", state, "label", state, …) */
    private static Chips chips(Object... pairs) {
        List<String> labels = new ArrayList<>();
        List<Boolean> states = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            labels.add((String) pairs[i]);
            states.add((Boolean) pairs[i + 1]);
        }
        return new Chips(labels, states);
    }
}
