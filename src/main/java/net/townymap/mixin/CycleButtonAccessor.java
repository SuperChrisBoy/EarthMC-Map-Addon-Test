package net.townymap.mixin;

import net.minecraft.client.gui.components.CycleButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@link CycleButton}'s private {@code cycleValue(int)} so the settings screen can step a
 * cycling button backward on right-click. {@code cycleValue(-1)} updates the value and fires the
 * value-change callback (which saves config), exactly like a forward left-click.
 */
@Mixin(value = CycleButton.class, remap = false)
public interface CycleButtonAccessor {
    @Invoker("cycleValue")
    void townymap$cycle(int amount);
}
