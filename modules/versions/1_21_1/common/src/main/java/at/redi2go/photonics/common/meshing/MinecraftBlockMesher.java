package at.redi2go.photonics.common.meshing;

import at.redi2go.photonics.api.mc.Id;
import at.redi2go.photonics.api.mc.core.IBlockPos;
import at.redi2go.photonics.api.mc.world.level.IBlockAndTintGetter;
import at.redi2go.photonics.api.mc.world.level.IBlockState;
import at.redi2go.photonics.common.iris.IrisUtil;
import at.redi2go.photonics.core.rendering.world.bakery.BlockBuilder;
import at.redi2go.photonics.core.rendering.world.bakery.BlockMesher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;

public class MinecraftBlockMesher implements BlockMesher<McMeshState> {
    private static final Id BLOCK_ATLAS = (Id) (Object) TextureAtlas.LOCATION_BLOCKS;
    private static final Direction[] DIRECTIONS = Direction.values();

    private final ThreadLocal<RandomSource> random = ThreadLocal.withInitial(RandomSource::create);

    @Override
    public McMeshState extractMeshState(
            Vector3i blockChunkOffset,
            IBlockPos pos,
            IBlockState blockState,
            IBlockAndTintGetter blockAndTintGetter
    ) {
        BlockState mcState = (BlockState) blockState;
        if (mcState.getRenderShape() != RenderShape.MODEL) return McMeshState.EMPTY;

        var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(mcState);
        var randomSource = random.get();
        var quads = new ArrayList<BakedQuad>();

        randomSource.setSeed(mcState.getSeed((BlockPos) pos));
        quads.addAll(model.getQuads(mcState, null, randomSource));

        for (Direction direction : DIRECTIONS) {
            randomSource.setSeed(mcState.getSeed((BlockPos) pos));
            quads.addAll(model.getQuads(mcState, direction, randomSource));
        }

        if (quads.isEmpty()) return McMeshState.EMPTY;

        return new McMeshState(IrisUtil.getBlockId(mcState), List.copyOf(quads));
    }

    @Override
    public void meshBlock(
            McMeshState meshState,
            Vector3i blockChunkOffset,
            IBlockPos pos,
            IBlockState blockState,
            IBlockAndTintGetter blockAndTintGetter,
            BlockBuilder blockBuilder
    ) {
        if (meshState == McMeshState.EMPTY) return;

        BlockState mcState = (BlockState) blockState;
        BlockPos mcPos = (BlockPos) pos;
        BlockAndTintGetter level = (BlockAndTintGetter) blockAndTintGetter;

        blockBuilder
                .useAtlas(BLOCK_ATLAS)
                .useBlockId(meshState.blockId())
                .useOffset(blockChunkOffset.x, blockChunkOffset.y, blockChunkOffset.z);

        for (BakedQuad quad : meshState.quads()) {
            int tint = 0xFFFFFFFF;
            if (quad.isTinted()) {
                tint = Minecraft.getInstance().getBlockColors().getColor(mcState, level, mcPos, quad.getTintIndex());
                tint = 0xFF000000 | tint;
            }

            int[] vertices = quad.getVertices();
            for (int vertex = 0; vertex < 4; vertex++) {
                int offset = vertex * 8;
                blockBuilder
                        .addVertex(
                                Float.intBitsToFloat(vertices[offset]),
                                Float.intBitsToFloat(vertices[offset + 1]),
                                Float.intBitsToFloat(vertices[offset + 2])
                        )
                        .setTint(tint)
                        .setUv(
                                Float.intBitsToFloat(vertices[offset + 4]),
                                Float.intBitsToFloat(vertices[offset + 5])
                        );
            }
        }
    }
}
