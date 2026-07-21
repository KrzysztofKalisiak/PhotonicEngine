package at.redi2go.photonics.core.rendering.sublevel;

import at.redi2go.photonics.core.iris.pipeline.texture.ISamplerHolder;
import at.redi2go.photonics.core.iris.pipeline.uniform.IUniformHolder;
import at.redi2go.photonics.core.iris.pipeline.uniform.IUniformUpdateFrequency;
import at.redi2go.photonics.core.rendering.RenderingComponent;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3i;
import org.joml.Vector3ic;
import org.joml.Vector4f;

import java.util.List;

/**
 * Render-time transforms supplied by optional moving-sublevel integrations.
 */
public final class ExternalSubLevelMotion implements RenderingComponent {
    public static final int MAX_SUBLEVELS = 16;
    public static final int MAX_EMISSIVE_CELLS = 64;

    private static final ExternalSubLevelMotion INSTANCE = new ExternalSubLevelMotion();
    private static volatile Snapshot snapshot = Snapshot.empty();

    private ExternalSubLevelMotion() {
    }

    public static ExternalSubLevelMotion instance() {
        return INSTANCE;
    }

    public static void submit(int occupancyTexture, List<SubLevel> subLevels) {
        snapshot = Snapshot.create(occupancyTexture, subLevels);
    }

    public static void clear() {
        snapshot = Snapshot.empty();
    }

    @Override
    public void registerUniforms(IUniformHolder uniforms) {
        uniforms.uniform1i(
                IUniformUpdateFrequency.perFrame(),
                "ph_sable_sublevel_count",
                () -> snapshot.subLevelCount
        );
        uniforms.uniform1i(
                IUniformUpdateFrequency.perFrame(),
                "ph_sable_emissive_cell_count",
                () -> snapshot.emissiveCellCount
        );
        for (int i = 0; i < MAX_SUBLEVELS; i++) {
            final int slot = i;
            uniforms.uniformMatrix(
                    IUniformUpdateFrequency.perFrame(),
                    "ph_sable_current_player_to_grid[" + slot + "]",
                    () -> snapshot.currentPlayerToGrid[slot]
            );
            uniforms.uniformMatrix(
                    IUniformUpdateFrequency.perFrame(),
                    "ph_sable_current_player_to_previous_player[" + slot + "]",
                    () -> snapshot.currentPlayerToPreviousPlayer[slot]
            );
            uniforms.uniformMatrix(
                    IUniformUpdateFrequency.perFrame(),
                    "ph_sable_previous_player_to_current_grid[" + slot + "]",
                    () -> snapshot.previousPlayerToCurrentGrid[slot]
            );
            uniforms.uniform4f(
                    IUniformUpdateFrequency.perFrame(),
                    "ph_sable_grid_info[" + slot + "]",
                    () -> snapshot.gridInfo[slot]
            );
        }

        for (int i = 0; i < Snapshot.IDENTITY_TOKEN_GROUPS; i++) {
            final int group = i;
            uniforms.uniform4f(
                    IUniformUpdateFrequency.perFrame(),
                    "ph_sable_identity_tokens[" + group + "]",
                    () -> snapshot.identityTokens[group]
            );
        }

        for (int i = 0; i < MAX_EMISSIVE_CELLS; i++) {
            final int cell = i;
            uniforms.uniform4f(
                    IUniformUpdateFrequency.perFrame(),
                    "ph_sable_emissive_cells[" + cell + "]",
                    () -> snapshot.emissiveCells[cell]
            );
        }
    }

    @Override
    public void registerCustomTextures(ISamplerHolder samplers) {
        samplers.addExternalSampler3D("ph_sable_occupancy", () -> snapshot.occupancyTexture);
    }

    public record SubLevel(
            int identityToken,
            Matrix4fc currentPlayerToGrid,
            Matrix4fc currentPlayerToPreviousPlayer,
            Matrix4fc previousPlayerToCurrentGrid,
            Vector3ic gridSize,
            int atlasZOffset,
            List<Vector3i> emissiveCells
    ) {
    }

    private static final class Snapshot {
        private static final int IDENTITY_TOKEN_GROUPS = (MAX_SUBLEVELS + 3) / 4;

        private final int occupancyTexture;
        private final int subLevelCount;
        private final int emissiveCellCount;
        private final Matrix4f[] currentPlayerToGrid;
        private final Matrix4f[] currentPlayerToPreviousPlayer;
        private final Matrix4f[] previousPlayerToCurrentGrid;
        private final Vector4f[] gridInfo;
        private final Vector4f[] identityTokens;
        private final Vector4f[] emissiveCells;

        private Snapshot(
                int occupancyTexture,
                int subLevelCount,
                int emissiveCellCount,
                Matrix4f[] currentPlayerToGrid,
                Matrix4f[] currentPlayerToPreviousPlayer,
                Matrix4f[] previousPlayerToCurrentGrid,
                Vector4f[] gridInfo,
                Vector4f[] identityTokens,
                Vector4f[] emissiveCells
        ) {
            this.occupancyTexture = occupancyTexture;
            this.subLevelCount = subLevelCount;
            this.emissiveCellCount = emissiveCellCount;
            this.currentPlayerToGrid = currentPlayerToGrid;
            this.currentPlayerToPreviousPlayer = currentPlayerToPreviousPlayer;
            this.previousPlayerToCurrentGrid = previousPlayerToCurrentGrid;
            this.gridInfo = gridInfo;
            this.identityTokens = identityTokens;
            this.emissiveCells = emissiveCells;
        }

        private static Snapshot empty() {
            return create(0, List.of());
        }

        private static Snapshot create(int occupancyTexture, List<SubLevel> source) {
            int count = Math.min(source.size(), MAX_SUBLEVELS);
            Matrix4f[] currentPlayerToGrid = matrices(MAX_SUBLEVELS);
            Matrix4f[] currentPlayerToPreviousPlayer = matrices(MAX_SUBLEVELS);
            Matrix4f[] previousPlayerToCurrentGrid = matrices(MAX_SUBLEVELS);
            Vector4f[] gridInfo = vectors(MAX_SUBLEVELS);
            Vector4f[] identityTokens = vectors(IDENTITY_TOKEN_GROUPS);
            Vector4f[] emissiveCells = vectors(MAX_EMISSIVE_CELLS);
            int emissiveCount = 0;

            for (int slot = 0; slot < count; slot++) {
                SubLevel subLevel = source.get(slot);
                currentPlayerToGrid[slot].set(subLevel.currentPlayerToGrid());
                currentPlayerToPreviousPlayer[slot].set(subLevel.currentPlayerToPreviousPlayer());
                previousPlayerToCurrentGrid[slot].set(subLevel.previousPlayerToCurrentGrid());
                gridInfo[slot].set(
                        subLevel.gridSize().x(),
                        subLevel.gridSize().y(),
                        subLevel.gridSize().z(),
                        subLevel.atlasZOffset()
                );
                setIdentityToken(identityTokens[slot / 4], slot % 4, subLevel.identityToken());

                for (Vector3ic cell : subLevel.emissiveCells()) {
                    if (emissiveCount >= MAX_EMISSIVE_CELLS)
                        break;

                    emissiveCells[emissiveCount].set(cell.x(), cell.y(), cell.z(), slot + 1);
                    emissiveCount++;
                }
            }

            return new Snapshot(
                    occupancyTexture,
                    count,
                    emissiveCount,
                    currentPlayerToGrid,
                    currentPlayerToPreviousPlayer,
                    previousPlayerToCurrentGrid,
                    gridInfo,
                    identityTokens,
                    emissiveCells
            );
        }

        private static void setIdentityToken(Vector4f group, int component, int token) {
            group.setComponent(component, token);
        }

        private static Matrix4f[] matrices(int count) {
            Matrix4f[] result = new Matrix4f[count];
            for (int i = 0; i < count; i++)
                result[i] = new Matrix4f();
            return result;
        }

        private static Vector4f[] vectors(int count) {
            Vector4f[] result = new Vector4f[count];
            for (int i = 0; i < count; i++)
                result[i] = new Vector4f();
            return result;
        }
    }
}
