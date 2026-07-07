package at.redi2go.photonics.common.mixins.rendering.world.bakery;

import at.redi2go.photonics.core.rendering.world.bakery.BlockBuilder;
import at.redi2go.photonics.core.rendering.world.block.VoxelColor;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(BlockBuilder.class)
public interface VertexBuilderMixin extends VertexConsumer {
    @Shadow
    BlockBuilder shadow$addVertex(float x, float y, float z);

    @Override
    default @NotNull VertexConsumer addVertex(float x, float y, float z) {
        return (VertexConsumer) shadow$addVertex(x, y, z);
    }

    @Shadow
    BlockBuilder shadow$setTint(int color);

    @Override
    default @NotNull VertexConsumer setColor(int argb) {
        return (VertexConsumer) shadow$setTint(argb);
    }

    @Override
    default @NotNull VertexConsumer setColor(int r, int g, int b, int a) {
        return (VertexConsumer) shadow$setTint(VoxelColor.from(r, g, b, a));
    }

    @Shadow
    BlockBuilder shadow$setUv(float u, float v);

    @Override
    default @NotNull VertexConsumer setUv(float u, float v) {
        return (VertexConsumer) shadow$setUv(u, v);
    }

    @Override
    default @NotNull VertexConsumer setUv1(int i, int j) {
        return this;
    }

    @Override
    default VertexConsumer setUv2(int i, int j) {
        return this;
    }

    @Override
    default VertexConsumer setNormal(float f, float g, float h) {
        return this;
    }
}
