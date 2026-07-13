package at.redi2go.photonics.common.mixins.iris.pipeline.passes.composite;

import at.redi2go.photonics.common.iris.IrisUtil;
import at.redi2go.photonics.common.iris.pipeline.CompositeRendererPassExt;
import at.redi2go.photonics.common.iris.pipeline.framebuffer.InternalIrisFramebuffer;
import at.redi2go.photonics.common.iris.pipeline.renderer.PhotonicsRenderer;
import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.program.ComputeProgram;
import net.irisshaders.iris.gl.program.Program;
import net.irisshaders.iris.pipeline.CompositePass;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(CompositeRenderer.class)
public abstract class CompositeRendererMixin {
    @Shadow
    @Final
    private WorldRenderingPipeline pipeline;

    @Shadow
    @Final
    private ImmutableList<CompositeRendererPassExt> passes;

    @WrapOperation(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/collect/ImmutableList$Builder;add(Ljava/lang/Object;)Lcom/google/common/collect/ImmutableList$Builder;",
                    ordinal = 1
            )
    )
    private ImmutableList.Builder<Object> addPass(
            ImmutableList.Builder<Object> instance,
            Object element,
            Operation<ImmutableList.Builder<Object>> original,
            @Local(name = "i") int i
    ) {
        ((CompositeRendererPassExt) element).setIndex(i);

        return original.call(instance, element);
    }

    @WrapOperation(
            method = "renderAll",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/irisshaders/iris/pipeline/CompositePass;name()Ljava/lang/String;"
            )
    )
    private String replaceName(CompositePass instance, Operation<String> original) {
        return (Object) this instanceof PhotonicsRenderer renderer ? renderer.getName() : original.call(instance);
    }

    @WrapOperation(
            method = "renderAll",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/irisshaders/iris/gl/program/ComputeProgram;use()V"
            )
    )
    private void useCompute(ComputeProgram instance, Operation<Void> original) {
        IrisUtil.bindBuffers(pipeline, instance.getProgramId());
        original.call(instance);
    }

    @WrapOperation(
            method = "renderAll",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/irisshaders/iris/gl/program/Program;use()V"
            )
    )
    private void use(Program instance, Operation<Void> original) {
        IrisUtil.bindBuffers(pipeline, instance.getProgramId());
        original.call(instance);
    }

    @WrapOperation(
            method = "renderAll",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/irisshaders/iris/gl/framebuffer/GlFramebuffer;bind()V"
            )
    )
    private void bindFramebuffer(
            GlFramebuffer instance,
            Operation<Void> original,
            @Local(name = "i") int passIndex
    ) {
        CompositeRendererPassExt pass = passes.get(passIndex);
        if (pass.usesIrisFramebuffer(instance) && pass.getFramebuffer().isPresent()) {
            ((InternalIrisFramebuffer) pass.getFramebuffer().orElseThrow()).bind();
            return;
        }

        original.call(instance);
    }
}
