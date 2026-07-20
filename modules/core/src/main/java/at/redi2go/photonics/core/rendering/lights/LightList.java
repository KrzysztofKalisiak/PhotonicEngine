package at.redi2go.photonics.core.rendering.lights;

import at.redi2go.photonics.core.rendering.WorldOrigin;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static java.lang.Math.max;

public class LightList extends AbstractList<TracedLightPosition> {
    private final TracedLightPosition[] lights;
    private final WorldOrigin origin;
    private final int movingLightCount;
    private final int priorityLightCount;

    public LightList(TracedLightPosition[] lights, WorldOrigin origin) {
        this(lights, origin, 0, 0);
    }

    public LightList(TracedLightPosition[] lights, WorldOrigin origin, int priorityLightCount) {
        this(lights, origin, priorityLightCount, priorityLightCount);
    }

    public LightList(
            TracedLightPosition[] lights,
            WorldOrigin origin,
            int movingLightCount,
            int priorityLightCount
    ) {
        this.lights = lights;
        this.origin = origin;
        this.movingLightCount = movingLightCount;
        this.priorityLightCount = priorityLightCount;
    }

    public WorldOrigin origin() {
        return origin;
    }

    public int priorityLightCount() {
        return priorityLightCount;
    }

    public int movingLightCount() {
        return movingLightCount;
    }

    @Override
    public int size() {
        return lights.length;
    }

    public boolean containsIndex(int index) {
        return index < size();
    }

    @Override
    public TracedLightPosition get(int index) {
        Objects.checkIndex(index, size());
        return lights[index];
    }

    public Mapping createMapping(LightList previous) {
        var mapping = new Mapping();
        for (int i = 0; i < max(size(), previous.size()); i++) {
            if (previous.containsIndex(i))
                mapping.addBefore(previous.get(i), i);

            if (containsIndex(i))
                mapping.addAfter(get(i), i);
        }

        return mapping;
    }

    @Override
    public int hashCode() {
        int result = 31 * Arrays.hashCode(lights) + priorityLightCount;
        return 31 * result + movingLightCount;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof LightList other)) return false;
        if (other.size() != size()) return false;
        if (other.movingLightCount != movingLightCount) return false;
        if (other.priorityLightCount != priorityLightCount) return false;

        for (int i = 0; i < size(); i++) {
            if (!other.lights[i].equals(lights[i]))
                return false;
        }

        return true;
    }

    public static class Mapping {
        private final Map<Object, LightInvalidation> mapping = new HashMap<>();

        private Mapping() {

        }

        private LightInvalidation createInvalidation(TracedLightPosition light) {
            return mapping.computeIfAbsent(
                    light.temporalMappingKey(),
                    ignored -> new LightInvalidation(light.blockPos())
            );
        }

        private void addBefore(TracedLightPosition light, int index) {
            var invalidation = createInvalidation(light);
            invalidation.before = light.lightInfo();
            invalidation.beforeIndex = index;
        }

        private void addAfter(TracedLightPosition light, int index) {
            var invalidation = createInvalidation(light);
            invalidation.after = light.lightInfo();
            invalidation.afterIndex = index;
        }

        public void forEachIndex(IndexConsumer consumer) {
            for (var e : mapping.entrySet()) {
                final var difference = e.getValue();

                final var before = difference.before;
                final var beforeIndex = difference.beforeIndex;

                final var after = difference.after;
                final var afterIndex = difference.afterIndex;

                if (Objects.equals(before, after)) {
                    consumer.accept(beforeIndex, afterIndex);
                    continue;
                } else if (beforeIndex != -1) {
                    consumer.accept(beforeIndex, -1);
                }
            }
        }

        @FunctionalInterface
        public interface IndexConsumer {
            void accept(int beforeIndex, int afterIndex);
        }
    }
}
