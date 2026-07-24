package at.redi2go.photonics.core.rendering.world.allocator;

import at.redi2go.photonics.api.gpu.buffers.heap.GpuBufferHeapStats;
import at.redi2go.photonics.core.rendering.RenderingComponent;

public interface WorldAllocator extends RenderingComponent {
    VoxelEntryMemory allocateEntry(boolean useChildMask, int extraFields);

    VoxelEntryListMemory allocateEntryList(boolean useChildMask, int extraFields);

    WorldLightMemory allocateWorldLight();

    default GpuBufferHeapStats heapStats() {
        return GpuBufferHeapStats.unavailable(0);
    }

    void upload();
}
