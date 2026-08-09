package at.redi2go.photonics.core.rendering.world.compiler;

import at.redi2go.photonics.api.Disposable;
import at.redi2go.photonics.api.gpu.buffers.heap.GpuBufferHeapOutOfMemoryError;
import at.redi2go.photonics.api.gpu.buffers.heap.GpuBufferHeapStats;
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
    private static final long HEAP_RECOVERY_RELEASE_BYTES = 8L * 1024L * 1024L;
    private static final long MIN_HEAP_RECOVERY_PROBE_NANOS = 2_000_000_000L;
    private static final long MAX_HEAP_RECOVERY_PROBE_NANOS = 16_000_000_000L;
    private static final long COMPILER_IDLE_POLL_NANOS = 100_000_000L;
    private static final long FAILURE_LOG_INTERVAL_NANOS = 10_000_000_000L;

    private final Queue<Vector3i> unloadQueue;
    private final SectionManager.SectionQueue sectionQueue;
    private final SectionManager.TaskQueue<ChunkCompiler.BuildResult> builtSectionQueue;
    private final SectionManager sectionManager;

    private final WorldRegistry worldRegistry;

    private final ConcurrentMap<Vector3i, Long> latestSection = new ConcurrentHashMap<>();
    private final ConcurrentMap<Vector3i, Long> sectionHashes = new ConcurrentHashMap<>();
    /**
     * Block/model content only. The full section hash also contains sampled skylight,
     * which can change while neighboring sections stream in and must not invalidate
     * temporal GI history by itself.
     */
    private final ConcurrentMap<Vector3i, Long> sceneHashes = new ConcurrentHashMap<>();

    private final Thread[] threads = new Thread[THREAD_COUNT];
    private final AtomicInteger consecutiveHeapFailures = new AtomicInteger();
    private final AtomicReference<HeapPressure> heapPressure = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong nextHeapFailureLogNanos = new AtomicLong();
    private final AtomicLong nextHeapRecoveryLogNanos = new AtomicLong();
    private final AtomicLong nextHeapProbeLogNanos = new AtomicLong();
    private final AtomicLong nextCompilerFailureLogNanos = new AtomicLong();

    public ChunkCompiler(
            SectionManager sectionManager,
            SectionManager.TaskQueue<ChunkCompiler.BuildResult> builtSectionQueue,
            WorldRegistry worldRegistry
    ) {
        this.sectionManager = sectionManager;
        this.unloadQueue = sectionManager.newUnloadQueue();
        this.sectionQueue = sectionManager.newSectionQueue(false);
        this.builtSectionQueue = builtSectionQueue;

        this.worldRegistry = worldRegistry;

        for (int i = 0; i < THREAD_COUNT; i++) {
            var thread = new Thread(this, "Photonic Chunk Compiler #" + i);
            threads[i] = thread;

            thread.setDaemon(true);
            thread.start();
        }
    }

    @Override
    public void run() {
        while (!closed.get() && !Thread.currentThread().isInterrupted()) {
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
        awaitCompilerReady();
        sectionQueue.awaitTask();
        awaitCompilerReady();
        var result = sectionQueue.take();

        if (result.isEmpty()) return;

        var section = result.get();
        unloadChunks();

        ILevel level = Minecraft.getLevel();
        if (level == null) return;

        if (!isLatestSection(section.pos(), section.priority())) return;

        // Computing the hash immediately is cheaper than meshing an entire section just to discard it
        var hashes = section.computeSectionHashes(level);
        long hash = hashes.sectionHash();
        long sceneHash = hashes.sceneHash();
        if (isDuplicateSection(section.pos(), hash)) return;

        GpuBufferHeapStats worldHeapBefore = worldRegistry.worldHeapStats();
        GpuBufferHeapStats paletteHeapBefore = worldRegistry.paletteHeapStats();
        var buildResult = new BuildResult(section.pos(), section.blockPos(), hash, sceneHash, section.priority());
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
                        buildResult.heapFailure(),
                        worldHeapBefore,
                        paletteHeapBefore
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
            @Nullable GpuBufferHeapOutOfMemoryError heapFailure,
            GpuBufferHeapStats worldHeapBefore,
            GpuBufferHeapStats paletteHeapBefore
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

        HeapKind heapKind = classifyHeap(heapFailure.heapStats(), worldHeapBefore, paletteHeapBefore);
        if (heapKind == null) {
            if (shouldLog(nextCompilerFailureLogNanos)) {
                Photonics.LOGGER.error(
                        "Photonics rejected section {} after pressure from an unknown GPU heap; failedHeap={}, memory={}",
                        section.pos(),
                        heapFailure.heapStats(),
                        worldRegistry.memoryDiagnosticSummary(),
                        failure
                );
            }
            return;
        }

        GpuBufferHeapStats heapBefore = heapKind == HeapKind.WORLD ? worldHeapBefore : paletteHeapBefore;
        GpuBufferHeapStats heapNow = heapStats(heapKind);
        long attemptHeadroom = heapBefore.allocatableBytes();
        long currentHeadroom = heapNow.allocatableBytes();
        long capacity = heapNow.capacityBytes();

        if (attemptHeadroom < 0 || currentHeadroom < 0 || capacity <= 0) {
            if (shouldLog(nextCompilerFailureLogNanos)) {
                Photonics.LOGGER.error(
                        "Photonics rejected section {} because heap headroom is unavailable; heap={}, failedHeap={}, memory={}",
                        section.pos(),
                        heapKind.logName,
                        heapFailure.heapStats(),
                        worldRegistry.memoryDiagnosticSummary(),
                        failure
                );
            }
            return;
        }

        if (heapKind == HeapKind.PALETTE
                && attemptHeadroom >= capacity - heapFailure.requestedBytes()) {
            if (shouldLog(nextHeapFailureLogNanos)) {
                Photonics.LOGGER.error(
                        "Photonics rejected section {} until it changes: it exhausted the palette heap after starting with {} of {} bytes free; failedBlocks={}, requestedBytes={}, memory={}",
                        section.pos(),
                        attemptHeadroom,
                        capacity,
                        failedBlocks,
                        heapFailure.requestedBytes(),
                        worldRegistry.memoryDiagnosticSummary()
                );
            }
            return;
        }

        int consecutiveFailures = consecutiveHeapFailures.incrementAndGet();
        long resumeHeadroom = Math.max(
                saturatedAdd(attemptHeadroom, heapFailure.requestedBytes(), capacity),
                saturatedAdd(currentHeadroom, HEAP_RECOVERY_RELEASE_BYTES, capacity)
        );
        long probeDelayNanos = heapRecoveryProbeDelayNanos(consecutiveFailures);
        requireHeapHeadroom(
                heapKind,
                resumeHeadroom,
                System.nanoTime() + probeDelayNanos
        );

        if (shouldLog(nextHeapFailureLogNanos)) {
            Photonics.LOGGER.warn(
                    "Photonics GPU heap pressure v86: section={}, heap={}, failedBlocks={}, consecutiveFailures={}, attemptHeadroomBytes={}, currentHeadroomBytes={}, resumeHeadroomBytes={}, probeDelayMs={}, requestedBytes={}, memory={}",
                    section.pos(),
                    heapKind.logName,
                    failedBlocks,
                    consecutiveFailures,
                    attemptHeadroom,
                    currentHeadroom,
                    resumeHeadroom,
                    probeDelayNanos / 1_000_000L,
                    heapFailure.requestedBytes(),
                    worldRegistry.memoryDiagnosticSummary()
            );
        }

        if (Minecraft.isLevelActive() && !closed.get())
            sectionQueue.offer(section.pos(), section);
    }

    private void recordSuccessfulBuild(SectionCopy section) {
        int recoveredFailures = consecutiveHeapFailures.getAndSet(0);
        if (recoveredFailures == 0) return;

        if (shouldLog(nextHeapRecoveryLogNanos)) {
            Photonics.LOGGER.info(
                    "Photonics GPU heap recovery v72: section={} compiled after {} deferred failure(s); memory={}",
                    section.pos(),
                    recoveredFailures,
                    worldRegistry.memoryDiagnosticSummary()
            );
        }
    }

    private void awaitCompilerReady() throws InterruptedException {
        while (true) {
            if (closed.get() || Thread.currentThread().isInterrupted())
                throw new InterruptedException();

            if (!Minecraft.isLevelActive()) {
                LockSupport.parkNanos(COMPILER_IDLE_POLL_NANOS);
                continue;
            }

            HeapPressure pressure = heapPressure.get();
            if (pressure == null)
                return;

            long worldHeadroom = worldRegistry.worldHeapStats().allocatableBytes();
            long paletteHeadroom = worldRegistry.paletteHeapStats().allocatableBytes();
            if (pressure.isSatisfied(worldHeadroom, paletteHeadroom)) {
                heapPressure.compareAndSet(pressure, null);
                continue;
            }

            long now = System.nanoTime();
            if (pressure.isProbeDue(now)
                    && heapPressure.compareAndSet(pressure, null)) {
                if (shouldLog(nextHeapProbeLogNanos)) {
                    Photonics.LOGGER.info(
                            "Photonics GPU heap recovery probe v86: requiredWorldHeadroomBytes={}, currentWorldHeadroomBytes={}, requiredPaletteHeadroomBytes={}, currentPaletteHeadroomBytes={}, pendingSections={}",
                            pressure.worldHeadroomBytes(),
                            worldHeadroom,
                            pressure.paletteHeadroomBytes(),
                            paletteHeadroom,
                            sectionQueue.pendingCount()
                    );
                }
                continue;
            }

            LockSupport.parkNanos(COMPILER_IDLE_POLL_NANOS);
        }
    }

    private void requireHeapHeadroom(
            HeapKind kind,
            long headroomBytes,
            long probeAtNanos
    ) {
        heapPressure.updateAndGet(current -> {
            HeapPressure next = current == null
                    ? new HeapPressure(0, 0, probeAtNanos)
                    : current.withProbeDeadline(probeAtNanos);
            return next.withRequiredHeadroom(kind, headroomBytes);
        });
    }

    private GpuBufferHeapStats heapStats(HeapKind kind) {
        return kind == HeapKind.WORLD
                ? worldRegistry.worldHeapStats()
                : worldRegistry.paletteHeapStats();
    }

    private static @Nullable HeapKind classifyHeap(
            GpuBufferHeapStats failedHeap,
            GpuBufferHeapStats worldHeap,
            GpuBufferHeapStats paletteHeap
    ) {
        boolean matchesWorld = failedHeap.capacityBytes() == worldHeap.capacityBytes();
        boolean matchesPalette = failedHeap.capacityBytes() == paletteHeap.capacityBytes();

        if (matchesWorld == matchesPalette)
            return null;

        return matchesWorld ? HeapKind.WORLD : HeapKind.PALETTE;
    }

    private static long saturatedAdd(long value, long increment, long limit) {
        if (value >= limit || increment >= limit - value)
            return limit;

        return value + increment;
    }

    private static long heapRecoveryProbeDelayNanos(int consecutiveFailures) {
        int shift = Math.min(3, Math.max(0, consecutiveFailures - 1));
        return Math.min(
                MAX_HEAP_RECOVERY_PROBE_NANOS,
                MIN_HEAP_RECOVERY_PROBE_NANOS << shift
        );
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

    private void unloadChunks() {
        while (!unloadQueue.isEmpty()) {
            var section = unloadQueue.poll();
            if (section == null) continue;

            sectionHashes.remove(section);
            sceneHashes.remove(section);
            latestSection.remove(section);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true))
            return;

        heapPressure.set(null);
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

        Photonics.LOGGER.info(
                "Photonics chunk compiler v72 stopped: pendingSections={}, memory={}",
                sectionQueue.pendingCount(),
                worldRegistry.memoryDiagnosticSummary()
        );
    }

    private enum HeapKind {
        WORLD("world"),
        PALETTE("palette");

        private final String logName;

        HeapKind(String logName) {
            this.logName = logName;
        }
    }

    private record HeapPressure(
            long worldHeadroomBytes,
            long paletteHeadroomBytes,
            long probeAtNanos
    ) {
        private HeapPressure withRequiredHeadroom(HeapKind kind, long headroomBytes) {
            return switch (kind) {
                case WORLD -> new HeapPressure(
                        Math.max(worldHeadroomBytes, headroomBytes),
                        paletteHeadroomBytes,
                        probeAtNanos
                );
                case PALETTE -> new HeapPressure(
                        worldHeadroomBytes,
                        Math.max(paletteHeadroomBytes, headroomBytes),
                        probeAtNanos
                );
            };
        }

        private HeapPressure withProbeDeadline(long deadlineNanos) {
            return new HeapPressure(
                    worldHeadroomBytes,
                    paletteHeadroomBytes,
                    Math.max(probeAtNanos, deadlineNanos)
            );
        }

        private boolean isSatisfied(long worldHeadroom, long paletteHeadroom) {
            return worldHeadroom >= worldHeadroomBytes
                    && paletteHeadroom >= paletteHeadroomBytes;
        }

        private boolean isProbeDue(long nowNanos) {
            return nowNanos - probeAtNanos >= 0;
        }
    }

    public class BuildResult implements PrioritizedTask, Disposable {
        private final Vector3i chunkPos;
        private final Vector3i chunkBlockPos;
        private final long hash;
        private final long sceneHash;
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
                long sceneHash,
                long priority
        ) {
            this.chunkPos = chunkPos;
            this.chunkBlockPos = chunkBlockPos;
            this.hash = hash;
            this.sceneHash = sceneHash;
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
            if (!setLatestSection(chunkPos, priority))
                return false;

            Long previousHash = sectionHashes.put(chunkPos, hash);
            if (Objects.equals(previousHash, hash))
                return false;

            Long previousSceneHash = sceneHashes.put(chunkPos, sceneHash);
            // The first accepted content hash is initial streaming. A changed
            // block/model hash is a scene edit; skylight-only changes are not.
            if (previousSceneHash != null && !Objects.equals(previousSceneHash, sceneHash))
                sectionManager.markSceneChanged();

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
