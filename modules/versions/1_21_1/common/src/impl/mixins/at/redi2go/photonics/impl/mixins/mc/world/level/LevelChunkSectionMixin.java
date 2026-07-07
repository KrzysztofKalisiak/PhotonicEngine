package at.redi2go.photonics.impl.mixins.mc.world.level;

import at.redi2go.photonics.api.mc.world.level.IBlockState;
import at.redi2go.photonics.api.mc.world.level.chunk.IChunkSection;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(LevelChunkSection.class)
public abstract class LevelChunkSectionMixin implements IChunkSection {
    @Shadow
    public abstract BlockState getBlockState(int x, int y, int z);

    @Shadow
    public abstract boolean hasOnlyAir();

    @Shadow
    public abstract PalettedContainer<BlockState> getStates();

    @Shadow
    public abstract PalettedContainerRO<Holder<Biome>> getBiomes();

    @Override
    public IBlockState ph$getBlockState(int x, int y, int z) {
        return (IBlockState) getBlockState(x, y, z);
    }

    @Override
    public boolean ph$hasOnlyAir() {
        return hasOnlyAir();
    }

    @Override
    public IChunkSection ph$createCopy() {
        return (IChunkSection) new LevelChunkSection(getStates().copy(), getBiomes().recreate());
    }
}
