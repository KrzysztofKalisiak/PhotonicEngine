package at.redi2go.photonics.common.mixins;

import at.redi2go.photonics.common.iris.IrisUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftLevelLifecycleMixin {
    @Inject(method = "clearClientLevel", at = @At("HEAD"))
    private void closePhotonicsForLevel(Screen screen, CallbackInfo ci) {
        IrisUtil.getPipelineManager().closePhotonics("client-level-cleared");
    }
}
