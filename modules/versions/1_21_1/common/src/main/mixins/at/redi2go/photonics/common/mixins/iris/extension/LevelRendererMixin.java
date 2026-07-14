package at.redi2go.photonics.common.mixins.iris.extension;

import at.redi2go.photonics.common.compat.ContraptionLightsSableBridge;
import at.redi2go.photonics.common.iris.IrisUtil;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(
            method = "renderLevel",
            at = @At("HEAD"),
            order = 900
    )
    public void renderLevel(
            DeltaTracker deltaTracker,
            boolean bl,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightTexture,
            Matrix4f matrix4f,
            Matrix4f matrix4f2,
            CallbackInfo ci
    ) {
        IrisUtil.getPhotonics().ifPresent(extension -> {
            ContraptionLightsSableBridge.captureReceiverMotion();
            extension.onFrameBegin();
        });
    }
}
