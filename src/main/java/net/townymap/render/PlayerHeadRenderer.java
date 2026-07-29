package net.townymap.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.Identifier;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws a player marker as a skin head on a coloured dot, with an optional facing cone.
 *
 * <p>Skins come from the client's own player list — the profiles it already resolved for everyone online
 * on the server — and {@link PlayerSkinDrawer} draws the 8x8 face plus the hat overlay. So no external
 * head service and no HTTP of our own.
 */
public final class PlayerHeadRenderer {

    private PlayerHeadRenderer() {}

    /** Parsed UUIDs, so the dash-insertion + parse does not run per player per frame. */
    private static final ConcurrentHashMap<String, UUID> UUID_CACHE = new ConcurrentHashMap<>();
    private static final UUID NIL = new UUID(0, 0);   // cached "unparseable" marker

    /**
     * Resolves a player's skin from the client's own player list.
     *
     * <p>A bare GameProfile(uuid, name) carries no texture properties and supplySkinTextures does not go
     * fetch them, which is why every head came out as the default Steve/Alex. The client already holds a
     * fully resolved skin for everyone in its player list, and because the mod runs while connected to
     * EarthMC that list contains every online player — the exact set with map dots.
     */
    private static SkinTextures resolve(String uuidStr, String name) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getNetworkHandler() == null) return null;
        UUID uuid = parseUuid(uuidStr);
        var entry = uuid != null ? mc.getNetworkHandler().getPlayerListEntry(uuid) : null;
        if (entry == null && name != null && !name.isBlank()) {
            entry = mc.getNetworkHandler().getPlayerListEntry(name);
        }
        return entry != null ? entry.getSkinTextures() : null;
    }

    /** squaremap sends UUIDs without dashes; cache the parse since it runs for every visible player. */
    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        UUID cached = UUID_CACHE.get(s);
        if (cached != null) return cached == NIL ? null : cached;
        UUID parsed;
        try {
            String d = s.length() == 32
                    ? s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16)
                      + "-" + s.substring(16, 20) + "-" + s.substring(20)
                    : s;
            parsed = UUID.fromString(d);
        } catch (Exception e) {
            parsed = null;
        }
        UUID_CACHE.put(s, parsed == null ? NIL : parsed);
        return parsed;
    }

    /**
     * @param cx,cy      dot centre in screen pixels
     * @param headSize   head edge length in pixels
     * @param dotColor   ARGB of the coloured dot behind the head (its town/nation colour)
     */
    /**
     * Draws a player's head for a menu/panel corner (cx/cy = centre). Online players use their real skin from
     * the client's player list (instant, no network). For anyone else the real head is fetched once from
     * mc-heads.net and cached; while that loads (or if it fails) the default Steve/Alex skin fills the slot.
     */
    public static void drawMenuHead(DrawContext ctx, String name, int cx, int cy, int size) {
        int border = Math.max(1, size / 8);
        ctx.fill(cx - size / 2 - border, cy - size / 2 - border,
                 cx + size / 2 + border, cy + size / 2 + border, 0xFF101114);

        SkinTextures skin = resolve(null, name);   // online: real skin straight from the game
        if (skin != null) {
            PlayerSkinDrawer.draw(ctx, skin, cx - size / 2, cy - size / 2, size);
            return;
        }

        Identifier head = PlayerHeadImages.get(name);   // offline: real head fetched from mc-heads.net
        if (head != null) {
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, head, cx - size / 2, cy - size / 2, 0f, 0f,
                    size, size, PlayerHeadImages.HEAD_PX, PlayerHeadImages.HEAD_PX,
                    PlayerHeadImages.HEAD_PX, PlayerHeadImages.HEAD_PX);
            return;
        }

        if (name != null && !name.isBlank()) {   // placeholder while the fetch is in flight
            UUID u = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            SkinTextures def = net.minecraft.client.util.DefaultSkinHelper.getSkinTextures(u);
            if (def != null) PlayerSkinDrawer.draw(ctx, def, cx - size / 2, cy - size / 2, size);
        }
    }

    public static void draw(DrawContext ctx, String uuid, String name, int cx, int cy,
                            int headSize, int dotColor) {
        SkinTextures skin = resolve(uuid, name);
        // Thin, uniform coloured frame around the head — the same width on all four sides so the box is even.
        int border = Math.max(1, headSize / 8);
        int half = headSize / 2 + border;

        if ((dotColor >>> 24) != 0) {
            // A single opaque ctx.fill rect on integer pixels, so the frame is crisp with no fuzzy edge.
            ctx.fill(cx - half, cy - half, cx + half, cy + half, dotColor);
        }

        if (skin != null) {
            PlayerSkinDrawer.draw(ctx, skin, cx - headSize / 2, cy - headSize / 2, headSize);
        } else if ((dotColor >>> 24) == 0) {
            // Not in the player list AND no colour — draw a neutral pip so the player is still visible.
            ctx.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xFFFFFFFF);
        }
        // If the skin is unknown but we have a colour, the dot already drawn above is the fallback.
    }
}
