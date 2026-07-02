package net.townymap.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.townymap.TownyMapConfig;
import net.townymap.TownyMapMod;

/**
 * Shared flat dark-button styling for the on-map UI, used when the "Dark Buttons" setting is on so the
 * toggle/settings/route/discord buttons all match instead of the vanilla textured widgets. A soft slate
 * body with a 1px top highlight + bottom shade reads as a proper button rather than a flat black box.
 */
public final class DarkButtons {

    private DarkButtons() {}

    public static boolean enabled() {
        TownyMapConfig c = TownyMapMod.getConfig();
        return c != null && c.darkButtons;
    }

    public static void draw(GuiGraphicsExtractor ctx, int x, int y, int w, int h, String label,
                            boolean active, int textColor, int mouseX, int mouseY) {
        boolean hover = active && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        int body = !active ? 0xFF1E2024 : hover ? 0xFF34373F : 0xFF26282E;
        ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF101114);    // outer border
        ctx.fill(x, y, x + w, y + h, body);                          // body
        ctx.fill(x, y, x + w, y + 1, 0x1AFFFFFF);                    // top highlight
        ctx.fill(x, y + h - 1, x + w, y + h, 0x22000000);            // bottom shade
        Minecraft mc = Minecraft.getInstance();
        int tw = mc.font.width(label);
        ctx.text(mc.font, label, x + (w - tw) / 2, y + (h - 8) / 2,
                active ? textColor : 0xFF7A7A7A, false);
    }
}
