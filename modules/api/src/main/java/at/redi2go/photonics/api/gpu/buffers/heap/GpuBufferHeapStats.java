package at.redi2go.photonics.api.gpu.buffers.heap;

public record GpuBufferHeapStats(
        long capacityBytes,
        long reservedBytes,
        long liveBytes,
        long peakLiveBytes,
        long reusableBytes,
        long allocations,
        long reusedAllocations,
        long frees,
        long failedAllocations
) {
    public static GpuBufferHeapStats unavailable(long capacityBytes) {
        return new GpuBufferHeapStats(capacityBytes, -1, -1, -1, -1, -1, -1, -1, -1);
    }

    public long allocatableBytes() {
        if (reservedBytes < 0 || reusableBytes < 0)
            return -1;

        return Math.max(0, capacityBytes - reservedBytes) + reusableBytes;
    }
}
