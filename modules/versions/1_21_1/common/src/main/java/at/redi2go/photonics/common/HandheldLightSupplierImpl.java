package at.redi2go.photonics.common;

import at.redi2go.photonics.api.mc.world.level.IBlockState;
import at.redi2go.photonics.core.rendering.lights.HandheldItem;
import at.redi2go.photonics.core.rendering.lights.HandheldItemSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.Optional;

public class HandheldLightSupplierImpl implements HandheldItemSupplier {
    private static final Map<Item, BlockState> ITEM_TO_BLOCK = Map.of(
            Items.LAVA_BUCKET,
            Blocks.LAVA.defaultBlockState()
    );

    @Override
    public boolean isLeftHanded() {
        return Minecraft.getInstance().options.mainHand().get() == HumanoidArm.LEFT;
    }


    private Optional<HandheldItem> getHandheldItem(EquipmentSlot slot) {
        return Optional.ofNullable(Minecraft.getInstance().player)
                .map(player -> player.getItemBySlot(slot))
                .filter(stack -> !stack.isEmpty())
                .flatMap(stack -> {
                    var mapped = ITEM_TO_BLOCK.get(stack.getItem());
                    if (mapped != null) return Optional.of(new BlockItem(mapped, false));

                    return BuiltInRegistries.ITEM.getResourceKey(stack.getItem())
                            .flatMap(e -> BuiltInRegistries.BLOCK.getOptional(e.location()))
                            .map(block -> new BlockItem(block.defaultBlockState(), stack.isEnchanted()));
                });
    }

    @Override
    public Optional<HandheldItem> getMainHand() {
        return getHandheldItem(EquipmentSlot.MAINHAND);
    }

    @Override
    public Optional<HandheldItem> getOffHand() {
        return getHandheldItem(EquipmentSlot.OFFHAND);
    }

    private record BlockItem(BlockState blockState, boolean isEnchanted) implements HandheldItem {
        @Override
        public IBlockState getBlockState() {
            return (IBlockState) blockState;
        }
    }
}
