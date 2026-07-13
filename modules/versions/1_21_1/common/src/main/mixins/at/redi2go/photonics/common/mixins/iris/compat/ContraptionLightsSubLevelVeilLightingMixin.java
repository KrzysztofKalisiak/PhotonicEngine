package at.redi2go.photonics.common.mixins.iris.compat;

import at.redi2go.photonics.common.compat.ContraptionLightsSableBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "xyz.atmerek.contraptionlights.veil.sublevel.SubLevelVeilLighting", remap = false)
public abstract class ContraptionLightsSubLevelVeilLightingMixin {
    @Inject(method = "updateForRender", at = @At("RETURN"), require = 0, remap = false)
    private static void photonics$captureMovingLights(CallbackInfo ci) {
        ContraptionLightsSableBridge.capture();
    }

    @Inject(method = "clear", at = @At("RETURN"), require = 0, remap = false)
    private static void photonics$clearMovingLights(CallbackInfo ci) {
        ContraptionLightsSableBridge.clear();
    }
}
