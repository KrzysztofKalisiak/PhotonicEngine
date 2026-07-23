package at.redi2go.photonics.core.rendering.lights;

import at.redi2go.photonics.api.mc.world.level.IBlockState;

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

    public static void submit(List<TracedLightPosition> lights, Set<ReplacementAlias> replacementAliases) {
        var copy = List.copyOf(lights);
        var replacementCopy = Set.copyOf(replacementAliases);
        var current = snapshot;

        if (!current.lights().equals(copy) || !current.replacementAliases().equals(replacementCopy))
            snapshot = new Snapshot(copy, replacementCopy, current.revision() + 1L);
    }

    public static void clear() {
        submit(List.of());
    }

    public static Snapshot snapshot() {
        return snapshot;
    }

    public record Snapshot(
            List<TracedLightPosition> lights,
            Set<ReplacementAlias> replacementAliases,
            long revision
    ) {
    }

    public record ReplacementAlias(int x, int y, int z, IBlockState blockState) {
        public static ReplacementAlias from(TracedLightPosition light) {
            var blockPos = light.blockPos();
            return new ReplacementAlias(
                    blockPos.x,
                    blockPos.y,
                    blockPos.z,
                    light.blockState()
            );
        }
    }
}
