package at.redi2go.photonics.core.rendering.lights;

import org.joml.Vector3i;

import java.util.List;
import java.util.Set;

/**
 * Render-time lights supplied by optional integrations such as moving sub-levels.
 */
public final class ExternalLightList {
    private static volatile Snapshot snapshot = new Snapshot(List.of(), Set.of(), 0L);

    private ExternalLightList() {
    }

    public static void submit(List<TracedLightPosition> lights) {
        submit(lights, Set.of());
    }

    public static void submit(List<TracedLightPosition> lights, Set<Vector3i> replacedBlockPositions) {
        var copy = List.copyOf(lights);
        var replacedCopy = Set.copyOf(replacedBlockPositions);
        var current = snapshot;

        if (!current.lights().equals(copy) || !current.replacedBlockPositions().equals(replacedCopy))
            snapshot = new Snapshot(copy, replacedCopy, current.revision() + 1L);
    }

    public static void clear() {
        submit(List.of());
    }

    public static Snapshot snapshot() {
        return snapshot;
    }

    public record Snapshot(
            List<TracedLightPosition> lights,
            Set<Vector3i> replacedBlockPositions,
            long revision
    ) {
    }
}
