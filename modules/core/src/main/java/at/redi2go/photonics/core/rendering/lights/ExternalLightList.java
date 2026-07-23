package at.redi2go.photonics.core.rendering.lights;

import at.redi2go.photonics.api.mc.world.level.IBlockState;
import at.redi2go.photonics.core.config.lights.BlockLightInfo;

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

    public record ReplacementAlias(
            int x,
            int y,
            int z,
            String blockId,
            BlockLightInfo lightInfo
    ) {
        public static ReplacementAlias from(TracedLightPosition light) {
            var blockPos = light.blockPos();
            return at(
                    blockPos.x,
                    blockPos.y,
                    blockPos.z,
                    light.blockState(),
                    light.lightInfo()
            );
        }

        public static ReplacementAlias at(
                int x,
                int y,
                int z,
                IBlockState blockState,
                BlockLightInfo lightInfo
        ) {
            return new ReplacementAlias(
                    x,
                    y,
                    z,
                    blockId(blockState),
                    lightInfo
            );
        }

        public boolean matchesPosition(ReplacementAlias other) {
            return x == other.x && y == other.y && z == other.z;
        }

        public boolean matchesBlockId(ReplacementAlias other) {
            return blockId.equals(other.blockId);
        }

        public boolean matchesLightProfile(ReplacementAlias other) {
            return lightInfo.equals(other.lightInfo);
        }

        private static String blockId(IBlockState blockState) {
            var id = blockState.ph$block().ph$id();
            if (id == null)
                return "<unregistered>";

            return id.ph$namespace() + ":" + id.ph$path();
        }
    }
}
