package at.redi2go.photonics.api.gpu.buffers.heap;

import at.redi2go.photonics.api.Disposable;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * A basic heap, this does not do defragmentation.
 */
public abstract class AbstractGpuBufferHeap implements IGpuBufferHeap {
    private long index = 0;
    private final Map<Long, ConcurrentLinkedQueue<FreeRegion>> freeRegions = new ConcurrentHashMap<>();

    private final AtomicLong liveBytes = new AtomicLong();
    private final AtomicLong peakLiveBytes = new AtomicLong();
    private final AtomicLong reusableBytes = new AtomicLong();
    private final LongAdder allocations = new LongAdder();
    private final LongAdder reusedAllocations = new LongAdder();
    private final LongAdder frees = new LongAdder();
    private final LongAdder failedAllocations = new LongAdder();

    protected synchronized long allocatePtr(long byteSize) {
        if (byteSize <= 0)
            throw new IllegalArgumentException("byteSize must be positive");

        var queue = freeRegions.get(byteSize);
        if (queue != null) {
            final var region = queue.poll();
            if (region != null) {
                reusableBytes.addAndGet(-byteSize);
                recordAllocation(byteSize, true);
                return region.begin;
            }
        }

        long begin = index;
        long end = index + byteSize;

        if (end < begin || end > capacity()) {
            failedAllocations.increment();
            return -1;
        }

        index = end;
        recordAllocation(byteSize, false);

        return begin;
    }

    @Override
    public synchronized GpuBufferHeapStats stats() {
        return new GpuBufferHeapStats(
                capacity(),
                index,
                liveBytes.get(),
                peakLiveBytes.get(),
                reusableBytes.get(),
                allocations.sum(),
                reusedAllocations.sum(),
                frees.sum(),
                failedAllocations.sum()
        );
    }

    private void recordAllocation(long byteSize, boolean reused) {
        long currentLiveBytes = liveBytes.addAndGet(byteSize);
        peakLiveBytes.accumulateAndGet(currentLiveBytes, Math::max);
        allocations.increment();

        if (reused)
            reusedAllocations.increment();
    }

    private void freeRegion(long begin, long end) {
        long byteSize = end - begin;
        liveBytes.addAndGet(-byteSize);
        reusableBytes.addAndGet(byteSize);
        frees.increment();
        freeRegions.computeIfAbsent(byteSize, ignored -> new ConcurrentLinkedQueue<>())
                .add(new FreeRegion(begin, end));
    }

    private record FreeRegion(long begin, long end) implements MemorySlice {
        long size() {
            return end - begin;
        }
    }

    protected interface Region extends MemorySlice, Disposable {
        AbstractGpuBufferHeap owner();

        long _begin();

        void _setBegin(long value);

        @Override
        default long begin() {
            return _begin() & ~SIGN_BIT;
        }

        default boolean isClosed() {
            return (_begin() & SIGN_BIT) != 0;
        }

        @Override
        @SuppressWarnings("resource")
        default void close() {
            long begin;
            long end;
            synchronized (this) {
                if ((_begin() & SIGN_BIT) != 0) return;

                begin = begin();
                end = end();
                _setBegin(_begin() | SIGN_BIT);
            }

            owner().freeRegion(begin, end);
        }

        static List<? extends MemorySlice> takeFrom(Queue<Region> queue) {
            var regions = new ArrayList<Region>();

            while (!queue.isEmpty()) {
                @Nullable Region region = queue.poll();
                if (region == null) break;

                regions.add(region);
            }

            return MemorySlice.mergeNeighbors(regions);
        }
    }

    protected abstract class AbstractAllocation implements Region, MemoryView {
        private long begin;
        private final long end;

        protected AbstractAllocation(long begin, long length) {
            this.begin = begin;
            this.end = begin + length;
        }

        @Override
        public AbstractGpuBufferHeap owner() {
            return AbstractGpuBufferHeap.this;
        }

        @Override
        public long _begin() {
            return begin;
        }

        @Override
        public void _setBegin(long value) {
            begin = value;
        }

        @Override
        public long begin() {
            return Region.super.begin();
        }

        @Override
        public long end() {
            return end;
        }
    }

    protected abstract class AbstractSubheap extends AbstractGpuBufferHeap implements Region {
        private long begin;
        private final long end;

        private final AbstractGpuBufferHeap root;

        protected AbstractSubheap(AbstractGpuBufferHeap root, long begin, long length) {
            this.begin = begin;
            this.end = begin + length;

            this.root = root;
        }

        protected abstract ByteBuffer createSlice(long begin, long length);

        protected abstract void uploadSlice(Region region);

        protected abstract IGpuBufferHeap createHeap(long begin, long length);

        @Override
        public AbstractGpuBufferHeap owner() {
            return AbstractGpuBufferHeap.this;
        }

        @Override
        public long _begin() {
            return begin;
        }

        @Override
        public void _setBegin(long value) {
            begin = value;
        }

        @Override
        public long end() {
            return end;
        }

        @Override
        public long capacity() {
            return end - begin();
        }

        @Nullable
        @Override
        public MemoryView allocate(long byteSize) {
            long ptr = allocatePtr(byteSize);

            return ptr == -1 ? null : new HeapAllocation(ptr + begin, byteSize);
        }

        @Nullable
        @Override
        public IGpuBufferHeap allocateHeap(long byteSize) {
            long ptr = allocatePtr(byteSize);

            return ptr == -1 ? null : createHeap(ptr, byteSize);
        }

        @Override
        public void upload() {
            root.upload();
        }

        private class HeapAllocation extends AbstractAllocation {
            private final ByteBuffer slice;

            private HeapAllocation(long begin, long length) {
                super(begin, length);

                slice = createSlice(begin, length);
            }

            @Override
            public ByteBuffer buffer() {
                return slice;
            }

            @Override
            public void upload() {
                uploadSlice(this);
            }
        }
    }

    private static final long SIGN_BIT = Long.MIN_VALUE;
}
