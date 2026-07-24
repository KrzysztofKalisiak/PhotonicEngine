package at.redi2go.photonics.core.rendering.world.compiler;

import at.redi2go.photonics.api.Disposable;
import at.redi2go.photonics.api.gpu.buffers.heap.GpuBufferHeapOutOfMemoryError;
import at.redi2go.photonics.api.mc.Minecraft;
import at.redi2go.photonics.api.mc.world.level.IBlockState;
import at.redi2go.photonics.api.mc.world.level.ILevel;
import at.redi2go.photonics.core.Photonics;
import at.redi2go.photonics.core.rendering.PrioritizedTask;
import at.redi2go.photonics.core.rendering.RenderingComponent;
import at.redi2go.photonics.core.rendering.SectionCopy;
import at.redi2go.photonics.core.rendering.SectionManager;
import at.redi2go.photonics.core.rendering.world.bakery.BlockMesher;
import at.redi2go.photonics.core.rendering.world.IgnoredInterruptedException;
import at.redi2go.photonics.core.rendering.world.block.BlockModel;
import at.redi2go.photonics.core.rendering.world.registry.WorldRegistry;
import org.apache.logging.log4j.util.TriConsumer;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3i;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

public class ChunkCompiler implements Runnable, RenderingComponent {
    private static final int THREAD_COUNT = 2;
    private static final long MIN_HEAP_RETRY_MILLIS = 100;
    private static final long MAX_HEAP_RETRY_MILLIS = 2_000;
    private static final long FAILURE_LOG_INTERVAL_NANOS = 10_000_000_000L;

    private final Queue<Vector3i> unloadQueue;
    private final SectionManager.SectionQueue sectionQueue;
    private final SectionManager.TaskQueue<ChunkCompiler.BuildResult> builtSectionQueue;

    private final WorldRegistry worldRegistry;

    private final ConcurrentMap<Vector3i, Long> latestSection = new ConcurrentHashMap<>();
    private final ConcurrentMap<Vector3i, Long> sectionHashes = new ConcurrentHashMap<>();

    private final Thread[] threads = new Thread[THREAD_COUNT];
    private final AtomicInteger consecutiveHeapFailures = new AtomicInteger();
    private final AtomicLong heapBackoffUntilNanos = new AtomicLong();
    private final AtomicLong nextHeapFailureLogNanos = new AtomicLong();
    private final AtomicLong nextHeapRecoveryLogNanos = new AtomicLong();
    private final AtomicLong nextCompilerFailureLogNanos = new AtomicLong();

    public ChunkCompiler(
            SectionManager sectionManager,
            SectionManager.TaskQueue<ChunkCompiler.BuildResult> builtSectionQueue,
            WorldRegistry worldRegistry
    ) {
        this.unloadQueue = sectionManager.newUnloadQueue();
        this.sectionQueue = sectionManager.newSectionQueue(false);
        this.builtSectionQueue = builtSectionQueue;

        this.worldRegistry = worldRegistry;

        for (int i = 0; i < THREAD_COUNT; i++) {
            var thread = new Thread(this, "Photonic Chunk Compiler #" + i);
            threads[i] = thread;

            thread.setDaemon(false);
            thread.start();
        }
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                compileNextSection();
            } catch (InterruptedException ignored) {
                return;
            } catch (Throwable t) {
                if (IgnoredInterruptedException.shouldIgnore(t)
                        || Thread.currentThread().isInterrupted())
                    return;

                if (shouldLog(nextCompilerFailureLogNanos))
                    Photonics.LOGGER.warn("An exception was thrown during chunk compilation; worker will continue", t);
            }
        }
    }

    private void compileNextSection() throws InterruptedException, ExecutionException {
        awaitHeapBackoff();
        sectionQueue.awaitTask();
        var result = sectionQueue.take();

        if (result.isEmpty()) return;

        var section = result.get();
        unloadChunks();

        ILevel level = Minecraft.getLevel();
        if (level == null) return;

        if (!isLatestSection(section.pos(), section.priority())) return;

        // Computing the hash immediately is cheaper than meshing an entire section just to discard it
        long hash = section.computeSectionHash(level);
        if (isDuplicateSection(section.pos(), hash)) return;

        var buildResult = new BuildResult(section.pos(), section.blockPos(), hash, section.priority());
        boolean submitted = false;
        try {
            BlockMesher.REGISTRY.setup();
            try {
                section.forEachBlock((blockChunkOffset, blockPos, block) -> {
                    if (block.ph$isAir()) return;

                    BlockMesher.REGISTRY.get(block.ph$block())
                            .ifPresent(mesher -> buildResult.submitBlockFuture(
                                    blockChunkOffset.x(),
                                    blockChunkOffset.y(),
                                    blockChunkOffset.z(),
                                    block,
                                    worldRegistry.blockModelRegistry().getBlockModel(
                                            mesher,
                                            new Vector3i(blockChunkOffset),
                                            blockPos,
                                            block,
                                            level
                                    )
                            ));
                });
            } catch (RuntimeException | Error failure) {
                buildResult.recordFailure(failure);
            } finally {
                try {
                    BlockMesher.REGISTRY.teardown();
                } catch (RuntimeException | Error failure) {
                    buildResult.recordFailure(failure);
                }
            }

            buildResult.awaitBlocks();
            Throwable failure = buildResult.failure();
            if (failure != null) {
                buildResult.close();
                handleBuildFailure(
                        section,
                        buildResult.failureCount(),
                        failure,
                        buildResult.heapFailure()
                );
                return;
            }

            submitted = buildResult.submit();
            if (submitted)
                recordSuccessfulBuild(section);
        } finally {
            if (!submitted)
                buildResult.close();
        }
    }

    private void handleBuildFailure(
            SectionCopy section,
            int failedBlocks,
            Throwable failure,
            @Nullable GpuBufferHeapOutOfMemoryError heapFailure
    ) throws InterruptedException {
        worldRegistry.freeUnusedObjects();

        if (heapFailure == null) {
            if (shouldLog(nextCompilerFailureLogNanos)) {
                Photonics.LOGGER.error(
                        "Photonics rejected partial section {} after {} block-meshing failure(s)",
                        section.pos(),
                        failedBlocks,
                        failure
                );
            }
            return;
        }

        int consecutiveFailures = consecutiveHeapFailures.incrementAndGet();
        long retryMillis = Math.min(
                MAX_HEAP_RETRY_MILLIS,
                MIN_HEAP_RETRY_MILLIS << Math.min(5, Math.max(0, consecutiveFailures - 1))
        );
        long retryAt = System.nanoTime() + retryMillis * 1_000_000L;
        heapBackoffUntilNanos.accumulateAndGet(retryAt, Math::max);

        if (shouldLog(nextHeapFailureLogNanos)) {
            Photonics.LOGGER.warn(
                    "Photonics GPU heap pressure v71: section={}, failedBlocks={}, consecutiveFailures={}, retryMs={}, requestedBytes={}, failedHeap={}, memory={}",
                    section.pos(),
                    failedBlocks,
                    consecutiveFailures,
                    retryMillis,
                    heapFailure.requestedBytes(),
                    heapFailure.heapStats(),
                    worldRegistry.memoryDiagnosticSummary()
            );
        }

        sectionQueue.offer(section.pos(), section);
    }

    private void recordSuccessfulBuild(SectionCopy section) {
        int recoveredFailures = consecutiveHeapFailures.getAndSet(0);
        if (recoveredFailures == 0) return;

        if (shouldLog(nextHeapRecoveryLogNanos)) {
            Photonics.LOGGER.info(
                    "Photonics GPU heap recovery v71: section={} compiled after {} deferred failure(s); memory={}",
                    section.pos(),
                    recoveredFailures,
                    worldRegistry.memoryDiagnosticSummary()
            );
        }
    }

    private void awaitHeapBackoff() throws InterruptedException {
        while (true) {
            long remaining = heapBackoffUntilNanos.get() - System.nanoTime();
            if (remaining <= 0) return;

            LockSupport.parkNanos(Math.min(remaining, 50_000_000L));
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedException();
        }
    }

    private static @Nullable GpuBufferHeapOutOfMemoryError findHeapFailure(Throwable failure) {
        while (failure != null) {
            if (failure instanceof GpuBufferHeapOutOfMemoryError heapFailure)
                return heapFailure;

            failure = failure.getCause();
        }

        return null;
    }

    private static boolean shouldLog(AtomicLong nextLogNanos) {
        long now = System.nanoTime();
        long next = nextLogNanos.get();
        return now >= next && nextLogNanos.compareAndSet(next, now + FAILURE_LOG_INTERVAL_NANOS);
    }

    private boolean isLatestSection(Vector3i pos, long priority) {
        return priority > latestSection.getOrDefault(pos, Long.MIN_VALUE);
    }

    private boolean setLatestSection(Vector3i pos, long priority) {
        return latestSection.compute(pos, (ignored, previous) -> {
            if (previous == null) return priority;
            if (priority > previous) return priority;

            return previous;
        }).equals(priority);
    }

    private boolean isDuplicateSection(Vector3i pos, long hash) {
        return Objects.equals(sectionHashes.get(pos), hash);
    }

    private boolean setSectionHash(Vector3i pos, long hash) {
        var previousHash = sectionHashes.put(pos, hash);
        return !Objects.equals(previousHash, hash);
    }

    private void unloadChunks() {
        while (!unloadQueue.isEmpty()) {
            var section = unloadQueue.poll();
            if (section == null) continue;

            sectionHashes.remove(section);
            latestSection.remove(section);
        }
    }

    @Override
    public void close() {
        for (var thread : threads)
            thread.interrupt();

        boolean interrupted = false;
        for (var thread : threads) {
            while (thread.isAlive()) {
                try {
                    thread.join();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                    thread.interrupt();
                }
            }
        }

        if (interrupted)
            Thread.currentThread().interrupt();
    }

    public class BuildResult implements PrioritizedTask, Disposable {
        private final Vector3i chunkPos;
        private final Vector3i chunkBlockPos;
        private final long hash;
        private final long priority;

        private final Queue<BlockResult> blocks = new ConcurrentLinkedQueue<>();

        private final AtomicInteger pendingBlocks = new AtomicInteger();
        private final CompletableFuture<Void> future = new CompletableFuture<>();
        private final AtomicInteger failureCount = new AtomicInteger();
        private final AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        private final AtomicReference<GpuBufferHeapOutOfMemoryError> firstHeapFailure = new AtomicReference<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        public BuildResult(
                Vector3i chunkPos,
                Vector3i chunkBlockPos,
                long hash,
                long priority
        ) {
            this.chunkPos = chunkPos;
            this.chunkBlockPos = chunkBlockPos;
            this.hash = hash;
            this.priority = priority;
        }

        public Vector3i chunkPos() {
            return chunkPos;
        }

        public Vector3i chunkBlockPos() {
            return chunkBlockPos;
        }

        @Override
        public long priority() {
            return priority;
        }

        private void submitBlockFuture(
                int x, int y, int z,
                IBlockState blockState,
                CompletionStage<@Nullable BlockModel> block
        ) {
            while (true) {
                int pending = pendingBlocks.get();
                if (pending == -1) throw new IllegalStateException();

                int remaining = (pending & Integer.MAX_VALUE) + 1;
                if (pendingBlocks.compareAndSet(pending, remaining | (pending & Integer.MIN_VALUE))) {
                    block.handle((result, t) -> {
                        try {
                            if (t != null) {
                                recordFailure(t);
                                if (result != null)
                                    result.close();
                            } else if (result != null) {
                                blocks.add(new BlockResult(x, y, z, blockState, result));
                            }
                        } catch (RuntimeException | Error failure) {
                            recordFailure(failure);
                            if (result != null)
                                result.close();
                        } finally {
                            completeBlock();
                        }

                        return null;
                    });

                    return;
                }
            }
        }

        private void completeBlock() {
            while (true) {
                int pending = pendingBlocks.get();
                if (pending == -1) throw new IllegalStateException();

                int remaining = (pending & Integer.MAX_VALUE) - 1;
                if (pendingBlocks.compareAndSet(pending, remaining | (pending & Integer.MIN_VALUE))) {
                    trySubmit();

                    return;
                }
            }
        }

        private void trySubmit() {
            while (true) {
                int pending = pendingBlocks.get();
                if (pending == -1 || (pending & Integer.MIN_VALUE) == 0) return;

                int remaining = pending & Integer.MAX_VALUE;
                if (remaining > 0) return;

                // This is safe because the max possible pending is 4096
                if (pendingBlocks.compareAndSet(pending, -1)) {
                    future.complete(null);

                    return;
                }
            }
        }

        private void awaitBlocks() throws InterruptedException, ExecutionException {
            while (true) {
                int pending = pendingBlocks.get();
                if ((pending & Integer.MIN_VALUE) != 0) return;

                if (pendingBlocks.compareAndSet(pending, pending | Integer.MIN_VALUE)) {
                    trySubmit();
                    break;
                }
            }

            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        future.get();
                        break;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted)
                    Thread.currentThread().interrupt();
            }

            if (interrupted)
                throw new InterruptedException();
        }

        private boolean submit() throws InterruptedException {
            if (!setLatestSection(chunkPos, priority) || !setSectionHash(chunkPos, hash))
                return false;

            builtSectionQueue.offer(chunkPos, this);
            return true;
        }

        private void recordFailure(Throwable failure) {
            failureCount.incrementAndGet();
            firstFailure.compareAndSet(null, failure);

            var heapFailure = findHeapFailure(failure);
            if (heapFailure != null)
                firstHeapFailure.compareAndSet(null, heapFailure);
        }

        private int failureCount() {
            return failureCount.get();
        }

        private @Nullable Throwable failure() {
            return firstFailure.get();
        }

        private @Nullable GpuBufferHeapOutOfMemoryError heapFailure() {
            return firstHeapFailure.get();
        }

        public void forEachBlock(TriConsumer<Vector3i, IBlockState, BlockModel> blockConsumer) {
            for (var block : blocks)
                blockConsumer.accept(block, block.blockState, block.model);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;

            BlockResult block;
            while ((block = blocks.poll()) != null)
                block.model.close();
        }
    }

    private static class BlockResult extends Vector3i {
        public final BlockModel model;
        public final IBlockState blockState;

        public BlockResult(
                int x, int y, int z,
                IBlockState blockState,
                BlockModel model
        ) {
            super(x, y, z);

            this.blockState = blockState;
            this.model = model;
        }
    }
}
