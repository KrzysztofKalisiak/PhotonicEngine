package at.redi2go.photonics.core.rendering.world.registry.object;

import at.redi2go.photonics.api.Disposable;
import at.redi2go.photonics.core.rendering.world.IgnoredInterruptedException;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.locks.LockSupport;

public abstract class AbstractWorldObject<M extends Disposable> implements WorldObject {
    private static final int CLOSED = -4;
    private static final int FAILED = -3;
    private static final int UNALLOCATED = -2;
    private static final int ALLOCATING = -1;

    private final ObjectRegistry<WorldObject> registry;
    private volatile int referenceCount = UNALLOCATED;
    private volatile Throwable allocationFailure = null;

    private M memory = null;

    @SuppressWarnings("unchecked")
    protected AbstractWorldObject(ObjectRegistry<?> registry) {
        this.registry = (ObjectRegistry<WorldObject>) registry;
    }

    @Override
    public boolean isAllocated() {
        return referenceCount >= 0;
    }

    public void awaitAllocated() {
        int count = referenceCount;
        int spins = 0;
        checkAvailable(count);

        while (count < 0) {
            if (Thread.currentThread().isInterrupted())
                throw new IgnoredInterruptedException();

            if (spins++ < 64)
                Thread.onSpinWait();
            else
                LockSupport.parkNanos(50_000L);

            count = referenceCount;
            checkAvailable(count);
        }
    }

    public void acquireReference() {
        awaitAllocated();

        while (true) {
            int count = referenceCount;
            checkAvailable(count);

            if (VAR_HANDLE.compareAndSet(this, count, count + 1))
                return;
        }
    }

    public boolean tryAcquireReference() {
        awaitAllocated();

        while (true) {
            int count = referenceCount;
            if (count == CLOSED || count == FAILED) return false;
            checkCanReference(count);

            if (VAR_HANDLE.compareAndSet(this, count, count + 1))
                return true;
        }
    }

    protected void loadDependants(List<WorldObject> output) {

    }

    protected WorldObject getKey() {
        return this;
    }

    protected M setMemory(Supplier<M> memorySupplier) {
        return setMemory(memorySupplier, ignored -> {
        });
    }

    protected M setMemory(Supplier<M> memorySupplier, Consumer<M> initializer) {
        while (true) {
            int count = this.referenceCount;
            checkCanAllocate(count);

            if (!VAR_HANDLE.compareAndSet(this, count, ALLOCATING)) continue;

            M allocatedMemory = null;
            List<WorldObject> acquiredDependants = new ArrayList<>();
            try {
                allocatedMemory = Objects.requireNonNull(memorySupplier.get(), "memorySupplier returned null");

                List<WorldObject> dependants = new ArrayList<>();
                loadDependants(dependants);

                for (var dependant : dependants) {
                    dependant.acquireReference();
                    acquiredDependants.add(dependant);
                }

                initializer.accept(allocatedMemory);
                this.memory = allocatedMemory;

                if (!VAR_HANDLE.compareAndSet(this, ALLOCATING, 0))
                    throw new IllegalStateException("Count was changed during allocation");

                registry.enqueueObject(new HandleImpl());
                return allocatedMemory;
            } catch (RuntimeException | Error failure) {
                rollbackAllocation(allocatedMemory, acquiredDependants, failure);
                throw failure;
            }
        }
    }

    private void rollbackAllocation(
            @Nullable M allocatedMemory,
            List<WorldObject> acquiredDependants,
            Throwable failure
    ) {
        for (int i = acquiredDependants.size() - 1; i >= 0; i--) {
            try {
                acquiredDependants.get(i).close();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }

        if (allocatedMemory != null) {
            try {
                allocatedMemory.close();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }

        memory = null;
        allocationFailure = failure;
        if (!VAR_HANDLE.compareAndSet(this, ALLOCATING, FAILED))
            failure.addSuppressed(new IllegalStateException("Count was changed during allocation rollback"));
    }

    protected @Nullable M memoryOrNull() {
        M memory = this.memory;
        int count = this.referenceCount;

        if (count < 0) return null;
        return memory;
    }

    protected @NonNls M memoryOrThrow() {
        M memory = this.memory;
        int count = referenceCount;

        return switch (count) {
            case CLOSED -> throw new IllegalStateException("closed");
            case FAILED -> throw new IllegalStateException("allocation failed", allocationFailure);
            case UNALLOCATED -> throw new IllegalStateException("not allocated");
            case ALLOCATING -> throw new IllegalStateException("allocating");

            default -> Objects.requireNonNull(memory, "memory was null");
        };
    }

    protected boolean dispose() {
        if (!VAR_HANDLE.compareAndSet(this, 0, CLOSED)) return false;

        memory.close();
        memory = null;

        List<WorldObject> dependants = new ArrayList<>();
        loadDependants(dependants);

        for (var dependant : dependants)
            dependant.close();

        return true;
    }

    @Override
    public final void close() {
        while (true) {
            int count = referenceCount;
            checkAvailable(count);
            checkCanReference(count);

            if (count == 0)
                throw new IllegalStateException("count was 0");

            if (VAR_HANDLE.compareAndSet(this, count, count - 1)) {
                if (count == 1)
                    registry.enqueueObject(new HandleImpl());

                return;
            }
        }
    }

    private void checkAvailable(int count) {
        if (count == CLOSED)
            throw new IllegalStateException("closed");
        if (count == FAILED)
            throw new IllegalStateException("allocation failed", allocationFailure);
    }

    private void checkCanReference(int count) {
        if (count == UNALLOCATED)
            throw new IllegalStateException("not allocated");
        if (count == ALLOCATING)
            throw new IllegalStateException("allocating");
    }

    private void checkCanAllocate(int count) {
        if (count == CLOSED)
            throw new IllegalStateException("closed");
        else if (count == FAILED)
            throw new IllegalStateException("allocation failed", allocationFailure);
        else if (count != UNALLOCATED)
            throw new IllegalStateException("already allocated");
    }

    private class HandleImpl implements Handle<WorldObject> {
        @Override
        public @Nullable WorldObject free() {
            return dispose() ? getKey() : null;
        }
    }

    private static final VarHandle VAR_HANDLE;

    static {
        try {
            VAR_HANDLE = MethodHandles.lookup()
                    .findVarHandle(AbstractWorldObject.class, "referenceCount", int.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
