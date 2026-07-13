package at.redi2go.photonics.common.iris;

import at.redi2go.photonics.core.Photonics;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.vertices.BlockSensitiveBufferBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class IrisBlockMaterialBridge {
    private static final Set<String> BRIDGE_LOGGED = ConcurrentHashMap.newKeySet();
    private static final Set<String> EMISSIVE_LOGGED = ConcurrentHashMap.newKeySet();

    private IrisBlockMaterialBridge() {
    }

    public static boolean begin(
            VertexConsumer consumer,
            BlockState blockState,
            BlockPos blockPos,
            String renderPath
    ) {
        if (!(consumer instanceof BlockSensitiveBufferBuilder builder)) {
            return false;
        }

        var blockStateIds = WorldRenderingSettings.INSTANCE.getBlockStateIds();
        if (blockStateIds == null) {
            return false;
        }

        int shaderPackId = blockStateIds.getOrDefault(blockState, -1);
        int lightEmission = blockState.getLightEmission();
        builder.beginBlock(
                shaderPackId,
                (byte) 0,
                (byte) lightEmission,
                blockPos.getX(),
                blockPos.getY(),
                blockPos.getZ()
        );

        if (BRIDGE_LOGGED.add(renderPath)) {
            Photonics.LOGGER.info(
                    "Photonics v20 Iris material bridge active: path={}, block={}, shaderPackId={}, emission={}",
                    renderPath,
                    BuiltInRegistries.BLOCK.getKey(blockState.getBlock()),
                    shaderPackId,
                    lightEmission
            );
        }

        if (lightEmission > 0 && EMISSIVE_LOGGED.add(renderPath)) {
            Photonics.LOGGER.info(
                    "Photonics v20 emissive material captured: path={}, block={}, shaderPackId={}, emission={}",
                    renderPath,
                    BuiltInRegistries.BLOCK.getKey(blockState.getBlock()),
                    shaderPackId,
                    lightEmission
            );
        }

        return true;
    }

    public static void end(VertexConsumer consumer, boolean active) {
        if (active && consumer instanceof BlockSensitiveBufferBuilder builder) {
            builder.endBlock();
        }
    }
}
