package net.townymap.mixin;

import net.minecraft.client.gui.widget.CyclingButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@link CyclingButtonWidget}'s private {@code cycle(int)} so the settings screen can step a
 * cycling button backward on right-click. {@code cycle(-1)} updates the value and fires the
 * value-change callback (which saves config), exactly like a forward left-click.
 */
@Mixin(CyclingButtonWidget.class)
public interface CyclingButtonWidgetAccessor {
    @Invoker("cycle")
    void townymap$cycle(int amount);
}
