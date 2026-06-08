package net.townymap.mixin;

import net.townymap.TownyMapMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While our squaremap overlay is active we draw waypoints ourselves, on top of the overlay,
 * so suppress Xaero's native minimap waypoint pass — otherwise each waypoint renders twice
 * (Xaero's underneath the squaremap, ours on top). Scoped by a ThreadLocal that is only set
 * during {@code renderMinimap}, so the world map's waypoints are unaffected.
 */
@Mixin(value = xaero.hud.minimap.waypoint.render.WaypointMapRenderer.class, remap = false)
public class MixinWaypointMapRenderer {

    @Inject(
            method = "shouldRender(Lxaero/hud/minimap/element/render/MinimapElementRenderLocation;)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void townymapaddon$suppressMinimapWaypoints(@Coerce Object location,
                                                        CallbackInfoReturnable<Boolean> cir) {
        if (TownyMapMod.shouldSuppressNativeMinimapWaypoints()) {
            cir.setReturnValue(false);
        }
    }
}
