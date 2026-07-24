package at.redi2go.photonics.api.gpu.buffers.heap;

public class GpuBufferHeapOutOfMemoryError extends OutOfMemoryError {
    private final long requestedBytes;
    private final GpuBufferHeapStats heapStats;

    public GpuBufferHeapOutOfMemoryError(long requestedBytes, GpuBufferHeapStats heapStats) {
        super("Could not allocate " + requestedBytes + " bytes from GPU buffer heap: " + heapStats);

        this.requestedBytes = requestedBytes;
        this.heapStats = heapStats;
    }

    public long requestedBytes() {
        return requestedBytes;
    }

    public GpuBufferHeapStats heapStats() {
        return heapStats;
    }
}
