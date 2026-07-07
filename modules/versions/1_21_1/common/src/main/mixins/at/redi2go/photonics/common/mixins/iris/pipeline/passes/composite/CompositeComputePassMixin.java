package at.redi2go.photonics.common.mixins.iris.pipeline.passes.composite;

import at.redi2go.photonics.common.iris.pipeline.CompositeRendererPassExt;
import at.redi2go.photonics.core.iris.pipeline.texture.IrisFramebuffer;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Optional;

@Mixin(targets = "net.irisshaders.iris.pipeline.CompositeRenderer$ComputeOnlyPass")
public abstract class CompositeComputePassMixin implements CompositeRendererPassExt {
    @Override
    public Optional<IrisFramebuffer> getFramebuffer() {
        return Optional.empty();
    }

    @Override
    public void setFramebuffer(@Nullable IrisFramebuffer framebuffer) {
        // Nothing
    }

    @Override
    public boolean usesIrisFramebuffer(GlFramebuffer framebuffer) {
        return false;
    }

    @Override
    public void updateSize() {
        // Nothing
    }
}
