package at.redi2go.photonics.common.meshing;

import at.redi2go.photonics.api.mc.Id;
import at.redi2go.photonics.api.mc.core.IBlockPos;
import at.redi2go.photonics.api.mc.world.level.IBlockAndTintGetter;
import at.redi2go.photonics.api.mc.world.level.IBlockState;
import at.redi2go.photonics.common.iris.IrisUtil;
import at.redi2go.photonics.core.Photonics;
import at.redi2go.photonics.core.rendering.world.bakery.BlockBuilder;
import at.redi2go.photonics.core.rendering.world.bakery.BlockMesher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class MinecraftBlockMesher implements BlockMesher<McMeshState> {
    private static final Id BLOCK_ATLAS = (Id) (Object) TextureAtlas.LOCATION_BLOCKS;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final TagKey<Block> NON_OCCLUDING_VOXELS = TagKey.create(
            Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("photonics", "non_occluding_voxels")
    );
    private static final TagKey<Block> THIN_CUTOUT_VOXELS = TagKey.create(
            Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("photonics", "thin_cutout_voxels")
    );
    private static final TagKey<Block> TALL_THIN_CUTOUT_VOXELS = TagKey.create(
            Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("photonics", "tall_thin_cutout_voxels")
    );
    private static final int UNKNOWN_BLOCK_ID_PAYLOAD = Integer.MAX_VALUE;
    private static final int THIN_CUTOUT_BLOCK_ID_FLAG = Integer.MIN_VALUE;
    private static final int THIN_CUTOUT_ALPHA = 96;
    private static final int TALL_THIN_CUTOUT_ALPHA = 54;
    private static final AtomicBoolean INVALID_BLOCK_ID_LOGGED = new AtomicBoolean();

    private final ThreadLocal<RandomSource> random = ThreadLocal.withInitial(RandomSource::create);

    @Override
    public McMeshState extractMeshState(
            Vector3i blockChunkOffset,
            IBlockPos pos,
            IBlockState blockState,
            IBlockAndTintGetter blockAndTintGetter
    ) {
        BlockState mcState = (BlockState) blockState;
        if (mcState.is(NON_OCCLUDING_VOXELS)) return McMeshState.EMPTY;
        if (mcState.getRenderShape() != RenderShape.MODEL) return McMeshState.EMPTY;

        BlockPos mcPos = (BlockPos) pos;
        int thinCutoutAlpha = mcState.is(TALL_THIN_CUTOUT_VOXELS)
                ? TALL_THIN_CUTOUT_ALPHA
                : mcState.is(THIN_CUTOUT_VOXELS) ? THIN_CUTOUT_ALPHA : 0;
        var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(mcState);
        var randomSource = random.get();
        var quads = new ArrayList<BakedQuad>();

        randomSource.setSeed(mcState.getSeed(mcPos));
        quads.addAll(model.getQuads(mcState, null, randomSource));

        for (Direction direction : DIRECTIONS) {
            randomSource.setSeed(mcState.getSeed(mcPos));
            quads.addAll(model.getQuads(mcState, direction, randomSource));
        }

        if (quads.isEmpty()) return McMeshState.EMPTY;

        boolean positionTinted = quads.stream().anyMatch(BakedQuad::isTinted);
        int blockId = packBlockId(IrisUtil.getBlockId(mcState), thinCutoutAlpha != 0);

        return new McMeshState(
                blockId,
                thinCutoutAlpha,
                positionTinted,
                List.copyOf(quads)
        );
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

        // BakedQuad positions are block-local; WorldCompiler applies the block position later.
        blockBuilder
                .useAtlas(BLOCK_ATLAS)
                .useBlockId(meshState.blockId())
                .useOffset(0.0f, 0.0f, 0.0f);

        for (BakedQuad quad : meshState.quads()) {
            int tint = getTint(
                    mcState,
                    level,
                    mcPos,
                    quad,
                    meshState.thinCutoutAlpha()
            );
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

    private static int getTint(
            BlockState state,
            BlockAndTintGetter level,
            BlockPos pos,
            BakedQuad quad,
            int thinCutoutAlpha
    ) {
        int tint = 0xFFFFFFFF;
        if (quad.isTinted()) {
            tint = Minecraft.getInstance().getBlockColors().getColor(
                    state,
                    level,
                    pos,
                    quad.getTintIndex()
            );
            tint = 0xFF000000 | tint;
        }

        return thinCutoutAlpha != 0
                ? (thinCutoutAlpha << 24) | (tint & 0x00FFFFFF)
                : tint;
    }

    private static int packBlockId(int blockId, boolean thinCutout) {
        int payload;
        if (blockId == -1) {
            payload = UNKNOWN_BLOCK_ID_PAYLOAD;
        } else if (blockId >= 0 && blockId < UNKNOWN_BLOCK_ID_PAYLOAD) {
            payload = blockId;
        } else {
            payload = UNKNOWN_BLOCK_ID_PAYLOAD;
            if (INVALID_BLOCK_ID_LOGGED.compareAndSet(false, true))
                Photonics.LOGGER.warn(
                        "Photonics cannot pack Iris block id {}; using the unknown-id sentinel",
                        blockId
                );
        }

        return thinCutout ? payload | THIN_CUTOUT_BLOCK_ID_FLAG : payload;
    }
}
