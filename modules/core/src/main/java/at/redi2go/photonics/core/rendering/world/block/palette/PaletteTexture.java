package at.redi2go.photonics.core.rendering.world.block.palette;

import at.redi2go.photonics.api.gpu.buffers.heap.GpuBufferHeapStats;
import at.redi2go.photonics.core.rendering.RenderingComponent;

public interface PaletteTexture extends RenderingComponent {
    PaletteTextureView reserveEntry();

    default GpuBufferHeapStats heapStats() {
        return GpuBufferHeapStats.unavailable(0);
    }

    void upload();
}
