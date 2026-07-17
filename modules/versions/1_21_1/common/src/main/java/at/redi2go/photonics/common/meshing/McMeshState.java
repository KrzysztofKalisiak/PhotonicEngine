package at.redi2go.photonics.common.meshing;

import at.redi2go.photonics.core.rendering.world.bakery.BlockMeshState;
import net.minecraft.client.renderer.block.model.BakedQuad;

import java.util.List;

public record McMeshState(
        int blockId,
        int thinCutoutAlpha,
        boolean positionTinted,
        List<BakedQuad> quads
) implements BlockMeshState {
    public static final McMeshState EMPTY = new McMeshState(-1, 0, false, List.of());

    @Override
    public boolean shouldCache() {
        return this != EMPTY && !positionTinted;
    }

    @Override
    public void prepareCacheUse() {
    }
}
