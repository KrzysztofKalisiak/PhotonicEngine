package at.redi2go.photonics.api.gpu.buffers.heap;

import at.redi2go.photonics.api.Disposable;
import org.jetbrains.annotations.Nullable;

public interface IGpuBufferHeap extends Disposable {
    /**
     * Returns the total capacity of this heap
     */
    long capacity();

    /**
     * Returns allocator diagnostics for this heap.
     */
    default GpuBufferHeapStats stats() {
        return GpuBufferHeapStats.unavailable(capacity());
    }

    /**
     * Allocates a chunk of memory of size {@code byteSize}
     *
     * @return The allocated memory view, or null if there was not enough memory to allocate {@code byteSize}
     */
    @Nullable MemoryView allocate(long byteSize);

    /**
     * Allocates a chunk of memory of size {@code byteSize}
     *
     * @return The allocated memory view
     * @throws OutOfMemoryError if {@code byteSize} could not be allocated
     */
    default MemoryView allocateOrThrow(long byteSize) {
        var result = allocate(byteSize);
        if (result == null)
            throw new GpuBufferHeapOutOfMemoryError(byteSize, stats());

        return result;
    }

    /**
     * Allocates a heap of size {@code byteSize} within this heap
     *
     * @return The allocated heap, or null if there was not enough memory to allocate {@code byteSize}
     */
    @Nullable IGpuBufferHeap allocateHeap(long byteSize);

    /**
     * Allocates a heap of size {@code byteSize} within this heap
     *
     * @return The allocated heap
     * @throws OutOfMemoryError if {@code byteSize} could not be allocated
     */
    default IGpuBufferHeap allocateHeapOrThrow(long byteSize) {
        var result = allocateHeap(byteSize);
        if (result == null)
            throw new GpuBufferHeapOutOfMemoryError(byteSize, stats());

        return result;
    }

    /**
     * Uploads changed regions to the underlying buffer
     */
    void upload();
}
