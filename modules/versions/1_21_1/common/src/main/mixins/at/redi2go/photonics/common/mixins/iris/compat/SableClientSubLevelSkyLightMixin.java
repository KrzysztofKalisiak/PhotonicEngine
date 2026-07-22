package at.redi2go.photonics.common.mixins.iris.compat;

import at.redi2go.photonics.core.Photonics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.ClientSubLevel", remap = false)
public abstract class SableClientSubLevelSkyLightMixin {
    @Unique
    private static final boolean photonics$freezeSkyLight = Boolean.getBoolean(
            "photonics.debug.freezeSableSkyLight"
    );

    @Unique
    private int photonics$frozenSkyLightScale = Integer.MIN_VALUE;

    @Inject(
            method = "getLatestSkyLightScale",
            at = @At("RETURN"),
            cancellable = true,
            require = 0,
            remap = false
    )
    private void photonics$freezeSkyLightForDiagnostics(
            CallbackInfoReturnable<Integer> cir
    ) {
        if (!photonics$freezeSkyLight)
            return;

        if (this.photonics$frozenSkyLightScale == Integer.MIN_VALUE) {
            this.photonics$frozenSkyLightScale = cir.getReturnValue();
            Photonics.LOGGER.info(
                    "Photonics v55 diagnostic froze one Sable sublevel skylight getter at {}",
                    this.photonics$frozenSkyLightScale
            );
        }

        cir.setReturnValue(this.photonics$frozenSkyLightScale);
    }
}
