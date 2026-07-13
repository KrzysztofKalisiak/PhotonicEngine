package at.redi2go.photonics.common.mixins.iris.compat;

import at.redi2go.photonics.common.iris.IrisBlockMaterialBridge;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.render.vanilla.VanillaSingleSubLevelRenderData", remap = false)
public abstract class SableSingleSubLevelRenderDataMixin {
    @Shadow(remap = false)
    private BlockState singleBlockState;

    @Shadow(remap = false)
    private BlockPos singleBlockPos;

    @Unique
    private boolean photonics$materialContextActive;

    @Inject(method = "renderSingleBlock", at = @At("HEAD"), require = 0, remap = false)
    private void photonics$beginIrisMaterialContext(
            RenderType layer,
            VertexConsumer consumer,
            Matrix4f modelView,
            double cameraX,
            double cameraY,
            double cameraZ,
            CallbackInfo ci
    ) {
        this.photonics$materialContextActive = IrisBlockMaterialBridge.begin(
                consumer,
                this.singleBlockState,
                this.singleBlockPos,
                "sable-single"
        );
    }

    @Inject(method = "renderSingleBlock", at = @At("RETURN"), require = 0, remap = false)
    private void photonics$endIrisMaterialContext(
            RenderType layer,
            VertexConsumer consumer,
            Matrix4f modelView,
            double cameraX,
            double cameraY,
            double cameraZ,
            CallbackInfo ci
    ) {
        IrisBlockMaterialBridge.end(consumer, this.photonics$materialContextActive);
        this.photonics$materialContextActive = false;
    }
}
