package at.redi2go.photonics.common.mixins.iris.compat;

import at.redi2go.photonics.common.compat.ContraptionLightsSableBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "xyz.atmerek.contraptionlights.veil.sublevel.SubLevelVeilLighting", remap = false)
public abstract class ContraptionLightsSubLevelVeilLightingMixin {
    @ModifyArg(
            method = "updateForRender",
            at = @At(
                    value = "INVOKE",
                    target = "Lfoundry/veil/api/client/render/light/data/PointLightData;setBrightness(F)Lfoundry/veil/api/client/render/light/data/PointLightData;"
            ),
            index = 0,
            require = 0,
            remap = false
    )
    private static float photonics$suppressDuplicateVeilPointLight(float brightness) {
        return ContraptionLightsSableBridge.filterVeilPointLightBrightness(brightness);
    }

    @Inject(method = "clear", at = @At("RETURN"), require = 0, remap = false)
    private static void photonics$clearMovingLights(CallbackInfo ci) {
        ContraptionLightsSableBridge.clear();
    }
}
