package net.townymap.gui;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.townymap.TownyMapMod;
import org.joml.Matrix3x2fStack;

/**
 * The user's "UI Scale" setting, applied to every GUI this mod draws. Each component scales its own drawing
 * around an anchor point (so it stays put and shrinks toward that anchor) and un-scales the mouse around the
 * same anchor for hit-testing. 1.0 = unchanged; lower shrinks text and gaps alike.
 */
public final class UiScale {
    private UiScale() {}

    /** The current scale (0.7–1.0). Below 70% the GUIs get unreadable, so that's the floor. */
    public static float get() {
        return Math.max(0.7f, Math.min(1.0f, TownyMapMod.infoPanelScale()));
    }

    /** Whether any scaling is in effect (avoid the matrix work at 100%). */
    public static boolean active() {
        return get() < 0.999f;
    }

    /** Push a scale transform around (cx, cy). Balance with {@link #pop}. */
    public static void push(DrawContext ctx, float cx, float cy) {
        push(ctx, get(), cx, cy);
    }

    public static void push(DrawContext ctx, float s, float cx, float cy) {
        Matrix3x2fStack m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(cx, cy);
        m.scale(s, s);
        m.translate(-cx, -cy);
    }

    public static void pop(DrawContext ctx) {
        ctx.getMatrices().popMatrix();
    }

    /** Maps a screen coordinate back into the pre-scale space around {@code center}, for hit-testing. */
    public static double unscale(double v, double center) {
        return center + (v - center) / get();
    }

    /** A copy of {@code click} with its coordinates un-scaled around (cx, cy). */
    public static Click unscaleClick(Click click, double cx, double cy) {
        return new Click(unscale(click.x(), cx), unscale(click.y(), cy), click.buttonInfo());
    }
}
