package at.redi2go.photonics.core.rendering.world.registry.block.model;

import at.redi2go.photonics.api.mc.core.IBlockPos;
import at.redi2go.photonics.api.mc.world.level.IBlockAndTintGetter;
import at.redi2go.photonics.api.mc.world.level.IBlockState;
import at.redi2go.photonics.core.collect.ConcurrentLong2ObjectMap;
import at.redi2go.photonics.core.rendering.world.bakery.BlockBakery;
import at.redi2go.photonics.core.rendering.world.bakery.BlockMeshState;
import at.redi2go.photonics.core.rendering.world.bakery.BlockMesher;
import at.redi2go.photonics.core.rendering.world.bakery.impl.BlockBakeryImpl;
import at.redi2go.photonics.core.rendering.world.block.BlockModel;
import at.redi2go.photonics.core.rendering.world.registry.block.BlockRegistry;
import at.redi2go.photonics.core.rendering.world.registry.block.builder.BlockModelBuilder;
import at.redi2go.photonics.core.rendering.world.registry.object.ObjectRegistry;
import at.redi2go.photonics.core.rendering.world.registry.object.WeakValue;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3i;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;

public class BlockModelRegistry extends ObjectRegistry<BlockModelImpl> {
    private final BlockBakery bakery;
    private final BlockRegistry blockRegistry;

    private final ConcurrentHashMap<BlockMeshState, CompletableFuture<@Nullable @WeakValue BlockModel>> blockMeshCache = new ConcurrentHashMap<>();
    private final ConcurrentLong2ObjectMap<CompletableFuture<BlockModelImpl>> modelCache = new ConcurrentLong2ObjectMap<>(16);

    public BlockModelRegistry(
            ReadWriteLock lock,
            BlockBakery bakery,
            BlockRegistry blockRegistry
    ) {
        super(lock);

        this.bakery = bakery;
        this.blockRegistry = blockRegistry;
    }

    @Override
    protected void removeObject(BlockModelImpl value) {
        modelCache.computeIfPresent(value.vertexHash(), (ignored, future) ->
                isCompletedWith(future, value) ? null : future
        );

        var meshes = value.meshes();
        while (!meshes.isEmpty()) {
            var mesh = meshes.remove();
            if (mesh == null) continue;

            blockMeshCache.computeIfPresent(mesh, (ignored, future) ->
                    isCompletedWith(future, value) ? null : future
            );
        }
    }

    @Override
    public Stats stats() {
        return stats(modelCache.size());
    }

    private CompletionStage<@WeakValue BlockModelImpl> cacheModel(BlockBakery.MeshResult blockMesh) {
        long vertexHash = blockMesh.vertexHash();
        CompletableFuture<BlockModelImpl> future = new CompletableFuture<>();
        var resultFuture = modelCache.putIfAbsent(vertexHash, future);

        if (resultFuture != null) {
            blockMesh.close();
            return resultFuture;
        }

        try {
            var builder = new BlockModelBuilder();

            blockMesh.bake(builder);

            var result = builder.build(vertexHash, blockRegistry, this);
            if (result != null)
                recordAllocatedObject();

            future.complete(result);
        } catch (Throwable t) {
            recordFailedAllocation();
            modelCache.remove(vertexHash, future);
            future.completeExceptionally(t);
        } finally {
            blockMesh.close();
        }

        return future;
    }


    public <T extends BlockMeshState> CompletionStage<@Nullable BlockModel> getBlockModel(
            BlockMesher<T> blockMesher,
            Vector3i blockChunkOffset,
            IBlockPos pos,
            IBlockState blockState,
            IBlockAndTintGetter blockAndTintGetter
    ) {
        T meshState = blockMesher.extractMeshState(
                blockChunkOffset,
                pos,
                blockState,
                blockAndTintGetter
        );

        CompletableFuture<@Nullable BlockModel> future = new CompletableFuture<>();

        try(var lock = acquireLock()) {
            if (meshState.shouldCache()) {
                var resultFuture = blockMeshCache.putIfAbsent(meshState, future);
                if (resultFuture != null)
                    return acquireModelReference(resultFuture);

                future.whenComplete((ignored, failure) -> {
                    if (failure != null)
                        blockMeshCache.remove(meshState, future);
                });
            }

            var meshResult = bakery.meshBlock(
                    blockMesher,
                    meshState,
                    blockChunkOffset,
                    pos,
                    blockState,
                    blockAndTintGetter
            );

            meshState.prepareCacheUse();
            if (meshResult == null) {
                future.complete(null);
                return future;
            }


            cacheModel(meshResult)
                    .handle((model, e) -> {
                        try {
                            if (e == null) {
                                if (model != null)
                                    model.meshes().add(meshState);

                                future.complete(model);
                            } else {
                                future.completeExceptionally(e);
                            }
                        } catch (Throwable t) {
                            future.completeExceptionally(t);
                        }

                        return null;
                    });

            return acquireModelReference(future);
        } catch (Throwable t) {
            future.completeExceptionally(t);
            return future;
        }
    }

    private CompletionStage<@Nullable BlockModel> acquireModelReference(CompletionStage<@Nullable @WeakValue BlockModel> originalFuture) {
        // Acquiring references is safe in thenApply as its either completed now, in which case we already hold the lock
        // or its being voxelized by a thread which also holds the lock, which will process all the thenApply dependants on that thread
        return originalFuture.thenApply((e) -> {
            if (e != null)
                ((BlockModelImpl) e).acquireReference();

            return e;
        });
    }

    private static boolean isCompletedWith(
            CompletableFuture<? extends @Nullable BlockModel> future,
            BlockModelImpl expected
    ) {
        return future.isDone()
                && !future.isCompletedExceptionally()
                && future.getNow(null) == expected;
    }
}
