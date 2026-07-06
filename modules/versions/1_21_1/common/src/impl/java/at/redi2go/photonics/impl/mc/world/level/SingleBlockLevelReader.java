package at.redi2go.photonics.impl.mc.world.level;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

public class SingleBlockLevelReader implements LevelReader {
    private final BlockState blockState;

    public SingleBlockLevelReader(BlockState blockState) {
        this.blockState = Objects.requireNonNull(blockState);
    }

    @Override
    public boolean hasChunk(int i, int j) {
        return true;
    }

    @Override
    public @NotNull BlockState getBlockState(@NotNull BlockPos blockPos) {
        return blockState;
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(@NotNull BlockPos blockPos) {
        return null;
    }

    @Override
    public @NotNull RegistryAccess registryAccess() {
        return Objects.requireNonNull(Minecraft.getInstance().level).registryAccess();
    }

    @Override
    public @Nullable ChunkAccess getChunk(int i, int j, @NotNull ChunkStatus chunkStatus, boolean bl) {
        throw new UnsupportedOperationException("getChunk");
    }

    @Override
    public int getHeight(Heightmap.@NotNull Types types, int i, int j) {
        throw new UnsupportedOperationException("getHeight");
    }

    @Override
    public int getSkyDarken() {
        throw new UnsupportedOperationException("getSkyDarken");
    }

    @Override
    public @NotNull BiomeManager getBiomeManager() {
        throw new UnsupportedOperationException("getBiomeManager");
    }

    @Override
    public @NotNull Holder<Biome> getUncachedNoiseBiome(int i, int j, int k) {
        throw new UnsupportedOperationException("getUncachedNoiseBiome");
    }

    @Override
    public boolean isClientSide() {
        return true;
    }

    @Override
    public int getSeaLevel() {
        throw new UnsupportedOperationException("getSeaLevel");
    }

    @Override
    public @NotNull DimensionType dimensionType() {
        throw new UnsupportedOperationException("dimensionType");
    }

    @Override
    public @NotNull FeatureFlagSet enabledFeatures() {
        throw new UnsupportedOperationException("enabledFeatures");
    }

    @Override
    public float getShade(@NotNull Direction direction, boolean bl) {
        throw new UnsupportedOperationException("getShade");
    }

    @Override
    public @NotNull LevelLightEngine getLightEngine() {
        throw new UnsupportedOperationException("getLightEngine");
    }

    @Override
    public @NotNull WorldBorder getWorldBorder() {
        throw new UnsupportedOperationException("getWorldBorder");
    }

    @Override
    public @NotNull List<VoxelShape> getEntityCollisions(@Nullable Entity entity, @NotNull AABB aABB) {
        throw new UnsupportedOperationException("getEntityCollisions");
    }

    @Override
    public @NotNull FluidState getFluidState(@NotNull BlockPos blockPos) {
        throw new UnsupportedOperationException("getFluidState");
    }
}
