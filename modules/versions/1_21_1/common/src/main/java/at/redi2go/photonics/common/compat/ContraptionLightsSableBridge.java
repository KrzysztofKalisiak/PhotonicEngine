package at.redi2go.photonics.common.compat;

import at.redi2go.photonics.api.gpu.systems.IRenderSystem;
import at.redi2go.photonics.api.gpu.textures.IGpuTexture3D;
import at.redi2go.photonics.api.gpu.textures.ITextureFormat;
import at.redi2go.photonics.api.gpu.textures.TextureUsage;
import at.redi2go.photonics.api.mc.world.level.IBlockState;
import at.redi2go.photonics.api.shaders.IShaderPack;
import at.redi2go.photonics.common.iris.IrisUtil;
import at.redi2go.photonics.core.Photonics;
import at.redi2go.photonics.core.config.PhConfig;
import at.redi2go.photonics.core.config.lights.BlockLightInfo;
import at.redi2go.photonics.core.iris.extensions.RestirPipeline;
import at.redi2go.photonics.core.rendering.lights.ExternalLightList;
import at.redi2go.photonics.core.rendering.lights.TracedLightPosition;
import at.redi2go.photonics.core.rendering.sublevel.ExternalSubLevelMotion;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3i;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ContraptionLightsSableBridge {
    private static final int MAX_GEOMETRY_AXIS = 96;
    private static final int MAX_GEOMETRY_VOLUME = 300_000;
    private static final int MAX_GEOMETRY_ATLAS_DEPTH = 512;
    private static final int MAX_GEOMETRY_ATLAS_CELLS = 786_432;
    // RGBA8: receiver flag, shape box count, shape-id low, shape-id high.
    private static final int GEOMETRY_CELL_STRIDE = 4;
    private static final int MAX_GEOMETRY_PAYLOAD_BYTES =
            MAX_GEOMETRY_ATLAS_CELLS * GEOMETRY_CELL_STRIDE;
    private static final int MAX_SHAPE_BOXES = 8;
    private static final int MAX_SHAPE_DEFINITIONS = 511;
    private static final int MAX_SHAPE_TEXTURE_DIMENSION = 512;
    private static final int MAX_MOTION_TOKEN = 0xfffe;
    private static final int FULL_CELL_BOX_COUNT = 254;
    private static final int CONSERVATIVE_CELL_BOX_COUNT = 255;
    private static final double SHAPE_EPSILON = 1.0e-6;
    private static final long GEOMETRY_SCAN_WARNING_NANOS = 4_000_000L;
    private static final long GEOMETRY_UPDATE_WARNING_NANOS = 8_000_000L;
    private static final long GEOMETRY_UPLOAD_WARNING_BYTES = 1_048_576L;
    private static final double STATIC_LIGHT_POSITION_EPSILON_SQUARED = 1.0e-6;
    private static final long MOVING_LIGHT_HOLD_NANOS = 250_000_000L;
    private static final long REPLACEMENT_ALIAS_HOLD_NANOS = 250_000_000L;
    private static final int MAX_REPLACEMENT_ALIASES_PER_LIGHT = 24;
    private static final String FREEZE_SABLE_SKYLIGHT_PROPERTY =
            "photonics.debug.freezeSableSkyLight";

    private static Access access;
    private static boolean unavailable;
    private static boolean activeLogged;
    private static boolean transientFailureLogged;
    private static boolean veilPointLightSuppressionLogged;
    private static boolean replacementAliasTrailLogged;
    private static int lastUploadedLights = -1;
    private static int lastZeroLuminanceTracedLights = -1;
    private static int lastRejectedMaterials = -1;
    private static int lastRecoveredMaterials = -1;
    private static int lastActuallyMovingLights = -1;
    private static MotionAccess motionAccess;
    private static boolean motionUnavailable;
    private static boolean motionTransientFailureLogged;
    private static boolean motionActiveLogged;
    private static int lastMotionSubLevels = -1;
    private static int nextMotionToken = 1;
    private static final Map<UUID, Matrix4d> previousWorldToMotionAnchor = new HashMap<>();
    private static final Map<UUID, Vector3i> motionAnchors = new HashMap<>();
    private static final Map<UUID, Integer> motionTokens = new HashMap<>();
    private static Vector3d previousMotionCameraPosition;
    private static final Map<UUID, GeometryRevisionSnapshot> geometryRevisionSnapshots = new HashMap<>();
    private static final Map<SableLightIdentity, Vector3d> publishedLightPositions = new HashMap<>();
    private static final Map<SableLightIdentity, Vector3d> movementReferencePositions = new HashMap<>();
    private static final Map<SableLightIdentity, Long> movingLightHoldUntilNanos = new HashMap<>();
    private static final Map<SableLightIdentity, BlockState> lastValidLightStates = new HashMap<>();
    private static final Map<
            SableLightIdentity,
            LinkedHashMap<ExternalLightList.ReplacementAlias, Long>
            > replacementAliasHistory = new HashMap<>();
    private static final Map<UUID, Integer> lastSkyLightScales = new HashMap<>();
    private static boolean skyLightDiagnosticsLogged;

    private static IGpuTexture3D geometryTexture;
    private static IGpuTexture3D shapeTexture;
    private static final Map<UUID, CachedSubLevelGeometry> geometryCache = new HashMap<>();
    private static final LinkedHashMap<ShapeKey, Integer> geometryShapeIds =
            new LinkedHashMap<>();
    private static List<GeometryLayoutKey> geometryLayoutKeys = List.of();
    private static int[] geometryOffsets = new int[0];
    private static byte[] geometryPayload = new byte[0];
    private static int geometryWidth;
    private static int geometryHeight;
    private static int geometryDepth;
    private static int geometryShapeDefinitionCount;
    private static int geometryRebuilds;
    private static int geometryUnchangedScans;
    private static long geometryCacheHits;
    private static long geometryCacheRescans;
    private static long geometryScannedCells;
    private static long geometryFullUploads;
    private static long geometrySliceUploads;
    private static long geometryUploadedBytes;
    private static int geometryRuntimeBudgetWarnings;
    private static AtlasSkipSummary lastAtlasSkipSummary;
    private static int cachedMaximum3dTextureSize = -1;
    private static long nextGeometryRevision = 1L;

    private ContraptionLightsSableBridge() {
    }

    public static void capture() {
        if (unavailable)
            return;

        var level = Minecraft.getInstance().level;
        if (level == null) {
            clear();
            return;
        }

        try {
            var bridgeAccess = access();
            var transformAccess = motionAccess();
            Map<?, ?> states = (Map<?, ?>) bridgeAccess.states.get(null);
            var shaderPack = IShaderPack.getCurrentPack().orElse(null);
            var lightRegistry = PhConfig.getLightRegistry();
            var lights = new ArrayList<TracedLightPosition>();
            var replacementAliases = new HashSet<ExternalLightList.ReplacementAlias>();
            var seenLightIdentities = new HashSet<SableLightIdentity>();
            int sourceLights = 0;
            int zeroLuminanceTracedLights = 0;
            int rejectedMaterials = 0;
            int recoveredMaterials = 0;
            int actuallyMovingLights = 0;
            String firstValidationIssue = null;
            long captureTimeNanos = System.nanoTime();

            for (var mapEntry : states.entrySet()) {
                if (!(mapEntry.getKey() instanceof UUID uniqueId))
                    continue;

                Object state = mapEntry.getValue();
                Object subLevel = transformAccess.subLevel.get(state);
                if (subLevel == null)
                    continue;

                int minX = transformAccess.minX.getInt(state);
                int minY = transformAccess.minY.getInt(state);
                int minZ = transformAccess.minZ.getInt(state);
                Object pose = transformAccess.renderPose.invoke(subLevel);
                Matrix4d worldToGrid = transformAccess.buildWorldToLocal(
                        pose,
                        minX,
                        minY,
                        minZ
                );
                if (!worldToGrid.isFinite() || Math.abs(worldToGrid.determinant()) < 0.000001d)
                    continue;
                Matrix4d gridToWorld = new Matrix4d(worldToGrid).invert();
                Object logicalPose = transformAccess.logicalPose.invoke(subLevel);
                Matrix4d logicalWorldToGrid = transformAccess.buildWorldToLocal(
                        logicalPose,
                        minX,
                        minY,
                        minZ
                );
                Matrix4d logicalGridToWorld = logicalWorldToGrid.isFinite()
                        && Math.abs(logicalWorldToGrid.determinant()) >= 0.000001d
                        ? logicalWorldToGrid.invert()
                        : null;
                int temporalDomainToken = motionToken(uniqueId);

                int[] lightX = (int[]) bridgeAccess.lightX.get(state);
                int[] lightY = (int[]) bridgeAccess.lightY.get(state);
                int[] lightZ = (int[]) bridgeAccess.lightZ.get(state);
                int[] lightLum = (int[]) bridgeAccess.lightLum.get(state);

                if (lightX == null || lightY == null || lightZ == null || lightLum == null)
                    continue;

                int count = Math.min(
                        Math.min(lightX.length, lightY.length),
                        Math.min(lightZ.length, lightLum.length)
                );
                sourceLights += count;

                for (int i = 0; i < count; i++) {
                    var localPos = new BlockPos(lightX[i], lightY[i], lightZ[i]);
                    var identity = new SableLightIdentity(uniqueId, lightX[i], lightY[i], lightZ[i]);
                    seenLightIdentities.add(identity);

                    BlockState blockState = level.getBlockState(localPos);
                    IBlockState apiBlockState = (IBlockState) (Object) blockState;
                    var lightInfo = lightRegistry.get(apiBlockState);

                    if (lightInfo == null || !lightInfo.isTraced()) {
                        BlockState cachedState = lastValidLightStates.get(identity);
                        if (cachedState != null) {
                            blockState = cachedState;
                            apiBlockState = (IBlockState) (Object) cachedState;
                            lightInfo = lightRegistry.get(apiBlockState);
                            if (lightInfo != null && lightInfo.isTraced())
                                recoveredMaterials++;
                        }

                        if (lightInfo == null || !lightInfo.isTraced()) {
                            rejectedMaterials++;
                            if (firstValidationIssue == null)
                                firstValidationIssue = "rejectedMaterial id=" + uniqueId
                                        + " local=" + localPos + " sourceLuminance=" + lightLum[i]
                                        + " state=" + level.getBlockState(localPos);
                            continue;
                        }
                    } else {
                        lastValidLightStates.put(identity, blockState);
                    }

                    // Photonics' state-aware material registry is authoritative.
                    // Contraption Lights can transiently publish zero luminance
                    // for a source that is still a traced emissive block.
                    if (lightLum[i] <= 0) {
                        zeroLuminanceTracedLights++;
                        if (firstValidationIssue == null)
                            firstValidationIssue = "zeroLuminanceTraced id=" + uniqueId
                                    + " local=" + localPos + " state=" + blockState;
                    }

                    var worldPosition = gridToWorld.transformPosition(
                            lightX[i] - minX + 0.5d,
                            lightY[i] - minY + 0.5d,
                            lightZ[i] - minZ + 0.5d,
                            new Vector3d()
                    );
                    var logicalWorldPosition = logicalGridToWorld == null
                            ? worldPosition
                            : logicalGridToWorld.transformPosition(
                                    lightX[i] - minX + 0.5d,
                                    lightY[i] - minY + 0.5d,
                                    lightZ[i] - minZ + 0.5d,
                                    new Vector3d()
                            );
                    Vector3d publishedPosition = publishedLightPositions.get(identity);
                    boolean previousPositionValid = publishedPosition != null;
                    Vector3d previousWorldPosition = previousPositionValid
                            ? new Vector3d(publishedPosition)
                            : new Vector3d(worldPosition);
                    Vector3d movementReference = movementReferencePositions.get(identity);
                    boolean moved = movementReference == null
                            || movementReference.distanceSquared(worldPosition)
                            > STATIC_LIGHT_POSITION_EPSILON_SQUARED;
                    if (moved)
                        movementReferencePositions.put(identity, new Vector3d(worldPosition));

                    long movingUntilNanos = moved
                            ? captureTimeNanos + MOVING_LIGHT_HOLD_NANOS
                            : movingLightHoldUntilNanos.getOrDefault(identity, 0L);
                    boolean moving = moved || captureTimeNanos < movingUntilNanos;
                    if (moving) {
                        movingLightHoldUntilNanos.put(identity, movingUntilNanos);
                        actuallyMovingLights++;
                    } else {
                        movingLightHoldUntilNanos.remove(identity);
                    }
                    publishedLightPositions.put(identity, new Vector3d(worldPosition));

                    int blockId = shaderPack == null ? -1 : shaderPack.getBlockId(apiBlockState);
                    lights.add(new TracedLightPosition(
                            blockId,
                            worldPosition,
                            apiBlockState,
                            lightInfo,
                            identity,
                            moving,
                            previousWorldPosition,
                            previousPositionValid,
                            temporalDomainToken
                    ));
                    // Section notifications can expose the same Sable block in
                    // plot, interpolated render, logical, or recently stale
                    // render coordinates.
                    replacementAliases.add(ExternalLightList.ReplacementAlias.at(
                            lightX[i],
                            lightY[i],
                            lightZ[i],
                            apiBlockState,
                            lightInfo
                    ));
                    retainReplacementAliases(
                            identity,
                            replacementAliases,
                            apiBlockState,
                            lightInfo,
                            captureTimeNanos,
                            worldPosition,
                            logicalWorldPosition,
                            previousPositionValid ? previousWorldPosition : null
                    );
                }
            }

            publishedLightPositions.keySet().retainAll(seenLightIdentities);
            movementReferencePositions.keySet().retainAll(seenLightIdentities);
            movingLightHoldUntilNanos.keySet().retainAll(seenLightIdentities);
            lastValidLightStates.keySet().retainAll(seenLightIdentities);
            replacementAliasHistory.keySet().retainAll(seenLightIdentities);
            ExternalLightList.submit(lights, replacementAliases);
            logReplacementAliasTrail(lights.size(), replacementAliases.size());
            logCapture(
                    states.size(),
                    sourceLights,
                    lights.size(),
                    actuallyMovingLights,
                    zeroLuminanceTracedLights,
                    rejectedMaterials,
                    recoveredMaterials,
                    firstValidationIssue
            );
            transientFailureLogged = false;
        } catch (InvocationTargetException | RuntimeException exception) {
            ExternalLightList.clear();
            if (!transientFailureLogged) {
                transientFailureLogged = true;
                Photonics.LOGGER.warn(
                        "Photonics v24 temporarily skipped a frame-aligned Contraption Lights/Sable moving-light capture",
                        exception
                );
            }
        } catch (ReflectiveOperationException | LinkageError exception) {
            unavailable = true;
            ExternalLightList.clear();
            Photonics.LOGGER.warn(
                    "Photonics v24 disabled the optional frame-aligned Contraption Lights/Sable moving-light bridge",
                    exception
            );
        }
    }

    private static void retainReplacementAliases(
            SableLightIdentity identity,
            Set<ExternalLightList.ReplacementAlias> replacementAliases,
            IBlockState blockState,
            BlockLightInfo lightInfo,
            long captureTimeNanos,
            Vector3dc... positions
    ) {
        var history = replacementAliasHistory.computeIfAbsent(
                identity,
                ignored -> new LinkedHashMap<>()
        );
        history.entrySet().removeIf(entry -> entry.getValue() <= captureTimeNanos);

        long expiresAt = captureTimeNanos + REPLACEMENT_ALIAS_HOLD_NANOS;
        for (var position : positions) {
            if (position == null)
                continue;

            var alias = replacementAlias(position, blockState, lightInfo);
            history.remove(alias);
            history.put(alias, expiresAt);
        }

        while (history.size() > MAX_REPLACEMENT_ALIASES_PER_LIGHT) {
            var iterator = history.keySet().iterator();
            iterator.next();
            iterator.remove();
        }

        replacementAliases.addAll(history.keySet());
    }

    private static ExternalLightList.ReplacementAlias replacementAlias(
            Vector3dc position,
            IBlockState blockState,
            BlockLightInfo lightInfo
    ) {
        return ExternalLightList.ReplacementAlias.at(
                (int) Math.floor(position.x()),
                (int) Math.floor(position.y()),
                (int) Math.floor(position.z()),
                blockState,
                lightInfo
        );
    }

    private static void logReplacementAliasTrail(int lights, int aliases) {
        if (replacementAliasTrailLogged || lights <= 0)
            return;

        replacementAliasTrailLogged = true;
        Photonics.LOGGER.info(
                "Photonics v64 stale section-light suppression active: lights={}, aliases={}, aliasHoldMs={}, maxAliasesPerLight={}, matching=position+(registry-id/profile diagnostics)+after-loss-owned-nearby-Light-proxy-quarantine, mergeOwner=render-thread",
                lights,
                aliases,
                REPLACEMENT_ALIAS_HOLD_NANOS / 1_000_000L,
                MAX_REPLACEMENT_ALIASES_PER_LIGHT
        );
    }

    public static float filterVeilPointLightBrightness(float brightness) {
        var extension = IrisUtil.getPhotonics().orElse(null);
        if (!(extension instanceof RestirPipeline restirPipeline)
                || !restirPipeline.isBlockLightEnabled())
            return brightness;

        if (!veilPointLightSuppressionLogged) {
            veilPointLightSuppressionLogged = true;
            Photonics.LOGGER.info(
                    "Photonics v31 suppressing duplicate Contraption Lights/Veil point-light energy; Sable emissive material and bloom remain enabled"
            );
        }

        return 0.0f;
    }

    public static void clear() {
        ExternalLightList.clear();
        ExternalSubLevelMotion.clear();
        previousWorldToMotionAnchor.clear();
        motionAnchors.clear();
        motionTokens.clear();
        previousMotionCameraPosition = null;
        geometryRevisionSnapshots.clear();
        publishedLightPositions.clear();
        movementReferencePositions.clear();
        movingLightHoldUntilNanos.clear();
        lastValidLightStates.clear();
        replacementAliasHistory.clear();
        lastSkyLightScales.clear();
        nextMotionToken = 1;
        if (lastUploadedLights > 0)
            Photonics.LOGGER.info("Photonics v24 Sable moving lights: {} -> 0", lastUploadedLights);
        if (lastMotionSubLevels > 0)
            Photonics.LOGGER.info("Photonics v24 Sable receiver motion: {} -> 0", lastMotionSubLevels);
        lastUploadedLights = 0;
        lastActuallyMovingLights = 0;
        lastMotionSubLevels = 0;
        lastZeroLuminanceTracedLights = -1;
        lastRejectedMaterials = -1;
        lastRecoveredMaterials = -1;
        resetGeometryAtlas();
    }

    public static void captureReceiverMotion() {
        if (motionUnavailable || unavailable)
            return;

        try {
            var lightAccess = access();
            Map<?, ?> states = (Map<?, ?>) lightAccess.states.get(null);
            var bridgeAccess = motionAccess();
            var level = Minecraft.getInstance().level;
            if (level == null)
                return;
            var camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            var currentCameraPosition = new Vector3d(camera.x, camera.y, camera.z);
            var previousCameraPosition = previousMotionCameraPosition == null
                    ? new Vector3d(currentCameraPosition)
                    : new Vector3d(previousMotionCameraPosition);
            var candidates = new ArrayList<MotionCandidate>();
            var currentWorldToMotionAnchor = new HashMap<UUID, Matrix4d>();

            for (var mapEntry : states.entrySet()) {
                if (!(mapEntry.getKey() instanceof UUID uniqueId))
                    continue;

                Object state = mapEntry.getValue();
                Object subLevel = bridgeAccess.subLevel.get(state);
                if (subLevel == null)
                    continue;

                int sizeX = bridgeAccess.sizeX.getInt(state);
                int sizeY = bridgeAccess.sizeY.getInt(state);
                int sizeZ = bridgeAccess.sizeZ.getInt(state);
                if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0)
                    continue;

                int minX = bridgeAccess.minX.getInt(state);
                int minY = bridgeAccess.minY.getInt(state);
                int minZ = bridgeAccess.minZ.getInt(state);
                Object pose = bridgeAccess.renderPose.invoke(subLevel);
                bridgeAccess.getLatestSkyLightScale.invoke(subLevel);
                int sampledSkyLightScale = bridgeAccess.latestSkyLightScale.getInt(subLevel);
                Object logicalPose = bridgeAccess.logicalPose.invoke(subLevel);
                Vector3dc renderPosition = (Vector3dc) bridgeAccess.posePosition.invoke(pose);
                Vector3dc logicalPosition = (Vector3dc) bridgeAccess.posePosition.invoke(logicalPose);
                logSkyLightScale(
                        uniqueId,
                        sampledSkyLightScale,
                        logicalPosition.y(),
                        renderPosition.y(),
                        currentCameraPosition.y
                );
                Matrix4d currentGrid = bridgeAccess.buildWorldToLocal(
                        pose,
                        minX,
                        minY,
                        minZ
                );
                if (!currentGrid.isFinite() || Math.abs(currentGrid.determinant()) < 0.000001d)
                    continue;

                Vector3i anchor = motionAnchors.computeIfAbsent(
                        uniqueId,
                        ignored -> new Vector3i(minX, minY, minZ)
                );
                Matrix4d currentAnchor = new Matrix4d(currentGrid);
                currentAnchor.m30(currentAnchor.m30() + (double) minX - anchor.x);
                currentAnchor.m31(currentAnchor.m31() + (double) minY - anchor.y);
                currentAnchor.m32(currentAnchor.m32() + (double) minZ - anchor.z);

                Matrix4d previous = previousWorldToMotionAnchor.get(uniqueId);
                if (previous == null || !previous.isFinite() || Math.abs(previous.determinant()) < 0.000001d)
                    previous = currentAnchor;

                Matrix4d currentToPreviousDouble = new Matrix4d(previous)
                        .invert()
                        .mul(currentAnchor);
                if (!currentToPreviousDouble.isFinite())
                    continue;
                Matrix4d previousToCurrentGridDouble = new Matrix4d(currentGrid)
                        .mul(new Matrix4d(currentToPreviousDouble).invert());
                if (!previousToCurrentGridDouble.isFinite())
                    continue;

                Matrix4d currentPlayerToGridDouble = new Matrix4d(currentGrid)
                        .translate(currentCameraPosition);
                Matrix4d currentPlayerToPreviousPlayerDouble = new Matrix4d()
                        .translate(
                                -previousCameraPosition.x,
                                -previousCameraPosition.y,
                                -previousCameraPosition.z
                        )
                        .mul(currentToPreviousDouble)
                        .translate(currentCameraPosition);
                Matrix4d previousPlayerToCurrentGridDouble = new Matrix4d(previousToCurrentGridDouble)
                        .translate(previousCameraPosition);
                if (!currentPlayerToGridDouble.isFinite()
                        || !currentPlayerToPreviousPlayerDouble.isFinite()
                        || !previousPlayerToCurrentGridDouble.isFinite())
                    continue;

                var emissiveCells = bridgeAccess.emissiveCells(
                        state,
                        minX,
                        minY,
                        minZ,
                        sizeX,
                        sizeY,
                        sizeZ
                );

                candidates.add(new MotionCandidate(
                        uniqueId,
                        new Matrix4f(currentPlayerToGridDouble),
                        new Matrix4f(currentPlayerToPreviousPlayerDouble),
                        new Matrix4f(previousPlayerToCurrentGridDouble),
                        level,
                        minX,
                        minY,
                        minZ,
                        sizeX,
                        sizeY,
                        sizeZ,
                        (byte[]) bridgeAccess.occupancy.get(state),
                        emissiveCells
                ));
                currentWorldToMotionAnchor.put(uniqueId, currentAnchor);
            }

            candidates.sort(Comparator.comparing(MotionCandidate::uniqueId));
            if (candidates.size() > ExternalSubLevelMotion.MAX_SUBLEVELS)
                candidates.subList(ExternalSubLevelMotion.MAX_SUBLEVELS, candidates.size()).clear();

            GeometryAtlasState atlasState = updateGeometryAtlas(candidates);
            int[] atlasOffsets = atlasState.offsets();
            var subLevels = new ArrayList<ExternalSubLevelMotion.SubLevel>(candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                MotionCandidate candidate = candidates.get(i);
                subLevels.add(new ExternalSubLevelMotion.SubLevel(
                        motionToken(candidate.uniqueId()),
                        candidate.currentPlayerToGrid(),
                        candidate.currentPlayerToPreviousPlayer(),
                        candidate.previousPlayerToCurrentGrid(),
                        new Vector3i(candidate.sizeX(), candidate.sizeY(), candidate.sizeZ()),
                        atlasOffsets[i],
                        candidate.emissiveCells()
                ));
            }

            int occupancyTexture = geometryTexture == null
                    ? 0
                    : IrisUtil.getTextureHandle(geometryTexture);
            int localShapeTexture = shapeTexture == null
                    ? 0
                    : IrisUtil.getTextureHandle(shapeTexture);
            ExternalSubLevelMotion.submit(
                    occupancyTexture,
                    localShapeTexture,
                    atlasState.shapeDefinitionCount(),
                    subLevels
            );
            previousWorldToMotionAnchor.keySet().retainAll(currentWorldToMotionAnchor.keySet());
            previousWorldToMotionAnchor.putAll(currentWorldToMotionAnchor);
            previousMotionCameraPosition = currentCameraPosition;
            motionAnchors.keySet().retainAll(currentWorldToMotionAnchor.keySet());
            geometryRevisionSnapshots.keySet().retainAll(currentWorldToMotionAnchor.keySet());
            lastSkyLightScales.keySet().retainAll(currentWorldToMotionAnchor.keySet());
            logMotionCapture(subLevels.size());
            motionTransientFailureLogged = false;
        } catch (InvocationTargetException | RuntimeException exception) {
            ExternalSubLevelMotion.clear();
            previousWorldToMotionAnchor.clear();
            motionAnchors.clear();
            previousMotionCameraPosition = null;
            if (!motionTransientFailureLogged) {
                motionTransientFailureLogged = true;
                Photonics.LOGGER.warn(
                        "Photonics v24 temporarily skipped Sable receiver-motion/geometry capture",
                        exception
                );
            }
        } catch (ReflectiveOperationException | LinkageError exception) {
            motionUnavailable = true;
            ExternalSubLevelMotion.clear();
            previousWorldToMotionAnchor.clear();
            motionAnchors.clear();
            previousMotionCameraPosition = null;
            Photonics.LOGGER.warn(
                    "Photonics v24 disabled the optional Sable receiver-motion/geometry bridge",
                    exception
            );
        }
    }

    private static GeometryAtlasState updateGeometryAtlas(List<MotionCandidate> candidates) {
        long updateStarted = System.nanoTime();
        int textureSizeLimit = maximum3dTextureSize();
        int shapeDefinitionLimit = maximumShapeDefinitions(textureSizeLimit);
        int[] offsets = new int[candidates.size()];
        Arrays.fill(offsets, -1);
        var accepted = new ArrayList<AcceptedGeometry>(candidates.size());
        int unalignedWidth = 1;
        int height = 1;
        int depth = 0;
        int skippedOversized = 0;
        int skippedAtlasDepth = 0;
        int skippedCellBudget = 0;
        int skippedTextureLimit = 0;

        for (int i = 0; i < candidates.size(); i++) {
            MotionCandidate candidate = candidates.get(i);
            long volume = (long) candidate.sizeX() * candidate.sizeY() * candidate.sizeZ();
            if (candidate.sizeX() > MAX_GEOMETRY_AXIS
                    || candidate.sizeY() > MAX_GEOMETRY_AXIS
                    || candidate.sizeZ() > MAX_GEOMETRY_AXIS
                    || volume > MAX_GEOMETRY_VOLUME) {
                skippedOversized++;
                continue;
            }
            if (textureSizeLimit <= 0
                    || candidate.sizeX() > textureSizeLimit
                    || candidate.sizeY() > textureSizeLimit
                    || candidate.sizeZ() > textureSizeLimit) {
                skippedTextureLimit++;
                continue;
            }
            if (depth + candidate.sizeZ() > MAX_GEOMETRY_ATLAS_DEPTH) {
                skippedAtlasDepth++;
                continue;
            }

            int projectedWidth = alignGeometryWidth(Math.max(unalignedWidth, candidate.sizeX()));
            int projectedHeight = Math.max(height, candidate.sizeY());
            int projectedDepth = depth + candidate.sizeZ();
            if (projectedWidth > textureSizeLimit
                    || projectedHeight > textureSizeLimit
                    || projectedDepth > textureSizeLimit) {
                skippedTextureLimit++;
                continue;
            }
            long projectedCells = (long) projectedWidth * projectedHeight * projectedDepth;
            long projectedBytes = projectedCells * GEOMETRY_CELL_STRIDE;
            if (projectedCells > MAX_GEOMETRY_ATLAS_CELLS
                    || projectedBytes > MAX_GEOMETRY_PAYLOAD_BYTES) {
                skippedCellBudget++;
                continue;
            }

            offsets[i] = depth;
            accepted.add(new AcceptedGeometry(candidate, depth));
            depth += candidate.sizeZ();
            unalignedWidth = Math.max(unalignedWidth, candidate.sizeX());
            height = Math.max(height, candidate.sizeY());
        }

        if (accepted.isEmpty()) {
            closeGeometryTextures();
            geometryCache.clear();
            geometryShapeIds.clear();
            geometryLayoutKeys = List.of();
            geometryOffsets = offsets;
            geometryPayload = new byte[0];
            geometryShapeDefinitionCount = 0;
            logGeometrySkips(
                    candidates.size(),
                    0,
                    skippedOversized,
                    skippedAtlasDepth,
                    skippedCellBudget,
                    skippedTextureLimit,
                    textureSizeLimit
            );
            return new GeometryAtlasState(offsets, 0);
        }

        int width = alignGeometryWidth(unalignedWidth);
        int atlasCells = Math.toIntExact((long) width * height * depth);
        int atlasPayloadBytes = Math.multiplyExact(atlasCells, GEOMETRY_CELL_STRIDE);
        if (atlasCells > MAX_GEOMETRY_ATLAS_CELLS
                || atlasPayloadBytes > MAX_GEOMETRY_PAYLOAD_BYTES)
            throw new IllegalStateException("Sable atlas planner exceeded its hard payload budget");

        var layoutKeys = new ArrayList<GeometryLayoutKey>(accepted.size());
        var prepared = new ArrayList<PreparedGeometry>(accepted.size());
        var acceptedIds = new HashSet<UUID>();
        int frameCacheHits = 0;
        int frameRescans = 0;
        long frameScannedCells = 0L;
        long frameScanNanos = 0L;

        for (AcceptedGeometry entry : accepted) {
            MotionCandidate candidate = entry.candidate();
            acceptedIds.add(candidate.uniqueId());
            layoutKeys.add(new GeometryLayoutKey(
                    candidate.uniqueId(),
                    candidate.minX(),
                    candidate.minY(),
                    candidate.minZ(),
                    candidate.sizeX(),
                    candidate.sizeY(),
                    candidate.sizeZ(),
                    entry.atlasZ()
            ));

            GeometryKey key = geometryKey(candidate);
            CachedSubLevelGeometry previous = geometryCache.get(candidate.uniqueId());
            CachedSubLevelGeometry current;
            boolean contentChanged;
            if (previous != null && previous.key().equals(key)) {
                current = previous;
                contentChanged = false;
                frameCacheHits++;
            } else {
                long scanStarted = System.nanoTime();
                current = scanSubLevelGeometry(candidate, key);
                frameScanNanos += System.nanoTime() - scanStarted;
                frameScannedCells += current.cellCount();
                frameRescans++;
                contentChanged = previous == null
                        || !sameGeometryContent(previous, current);
                geometryCache.put(candidate.uniqueId(), current);
            }
            prepared.add(new PreparedGeometry(entry, current, contentChanged));
        }
        geometryCache.keySet().retainAll(acceptedIds);

        int newShapeDefinitions = registerPersistentShapes(
                prepared,
                shapeDefinitionLimit
        );
        List<ShapeKey> shapeKeys = List.copyOf(geometryShapeIds.keySet());
        boolean shapeUploadRequired = !shapeKeys.isEmpty()
                && (newShapeDefinitions > 0
                || !shapeTextureMatches(shapeKeys.size(), textureSizeLimit));

        boolean layoutChanged = geometryTexture == null
                || geometryTexture.ph$isClosed()
                || geometryWidth != width
                || geometryHeight != height
                || geometryDepth != depth
                || geometryPayload.length != atlasPayloadBytes
                || !layoutKeys.equals(geometryLayoutKeys)
                || !Arrays.equals(offsets, geometryOffsets);

        int frameSliceUploads = 0;
        long frameAtlasUploadBytes = 0L;
        int globalShapeFallbackCells = 0;
        long uploadStarted = System.nanoTime();
        if (layoutChanged) {
            byte[] payload = new byte[atlasPayloadBytes];
            for (PreparedGeometry entry : prepared) {
                EncodedGeometry encoded = encodeGeometry(entry.geometry());
                globalShapeFallbackCells += encoded.globalShapeFallbackCells();
                copySliceToAtlas(
                        encoded.payload(),
                        entry.accepted().candidate(),
                        entry.accepted().atlasZ(),
                        payload,
                        width,
                        height
                );
            }

            ensureGeometryTexture(width, height, depth);
            uploadGeometryRegion(
                    payload,
                    new Vector3i(),
                    new Vector3i(width, height, depth)
            );
            geometryPayload = payload;
            geometryFullUploads++;
            frameAtlasUploadBytes = payload.length;
        } else {
            for (PreparedGeometry entry : prepared) {
                if (!entry.contentChanged())
                    continue;

                EncodedGeometry encoded = encodeGeometry(entry.geometry());
                globalShapeFallbackCells += encoded.globalShapeFallbackCells();
                if (sliceMatchesAtlas(
                        encoded.payload(),
                        entry.accepted().candidate(),
                        entry.accepted().atlasZ(),
                        geometryPayload,
                        width,
                        height
                ))
                    continue;

                copySliceToAtlas(
                        encoded.payload(),
                        entry.accepted().candidate(),
                        entry.accepted().atlasZ(),
                        geometryPayload,
                        width,
                        height
                );
                MotionCandidate candidate = entry.accepted().candidate();
                uploadGeometryRegion(
                        encoded.payload(),
                        new Vector3i(0, 0, entry.accepted().atlasZ()),
                        new Vector3i(
                                candidate.sizeX(),
                                candidate.sizeY(),
                                candidate.sizeZ()
                        )
                );
                frameSliceUploads++;
                frameAtlasUploadBytes += encoded.payload().length;
            }
        }

        long frameShapeUploadBytes = 0L;
        if (shapeUploadRequired) {
            geometryShapeDefinitionCount = uploadShapeTable(
                    shapeKeys,
                    textureSizeLimit
            );
            if (geometryShapeDefinitionCount > 0) {
                frameShapeUploadBytes = shapeTablePayloadBytes(
                        geometryShapeDefinitionCount
                );
            }
        } else if (shapeKeys.isEmpty()) {
            closeShapeTexture();
            geometryShapeDefinitionCount = 0;
        } else if (shapeTextureMatches(shapeKeys.size(), textureSizeLimit)) {
            geometryShapeDefinitionCount = shapeKeys.size();
        } else {
            geometryShapeDefinitionCount = 0;
        }

        long frameUploadedBytes = frameAtlasUploadBytes + frameShapeUploadBytes;
        long frameUploadNanos = System.nanoTime() - uploadStarted;
        geometryWidth = width;
        geometryHeight = height;
        geometryDepth = depth;
        geometryLayoutKeys = List.copyOf(layoutKeys);
        geometryOffsets = offsets;
        geometryCacheHits += frameCacheHits;
        geometryCacheRescans += frameRescans;
        geometryScannedCells += frameScannedCells;
        geometrySliceUploads += frameSliceUploads;
        geometryUploadedBytes += frameUploadedBytes;

        GeometryCellStats cellStats = aggregateCellStats(prepared);
        long frameUpdateNanos = System.nanoTime() - updateStarted;
        logGeometryRuntimeBudget(
                frameScanNanos,
                frameUpdateNanos,
                frameUploadedBytes,
                frameRescans,
                frameScannedCells
        );
        boolean atlasUploaded = layoutChanged || frameSliceUploads > 0;
        boolean anythingUploaded = atlasUploaded || frameShapeUploadBytes > 0;
        if (anythingUploaded) {
            geometryRebuilds++;
            Photonics.LOGGER.info(
                    "Photonics Sable local-occlusion atlas update: update={}, subLevels={}, skippedOversized={}, skippedAtlasDepth={}, skippedCellBudget={}, skippedTextureLimit={}, cacheHits={}/{} total, rescans={}/{} total, scannedCells={}/{} total, scanMs={}, updateMs={}, uploadMode={}, fullUploads={} total, sliceUploads={}/{} total, uploadedBytes={}/{} total, uploadMs={}, receiverCells={}, exactFullCells={}, exactShapeCells={}, localConservativeCells={}, frameGlobalShapeFallbackCells={}, receiverOnlyCells={}, shapeDefinitions={}/{}, maxBoxesPerShape={}, size={}x{}x{}, glMax3dTextureSize={}, atlasCells={}/{}, payloadBytes={}/{}, authority=same-token-local-only",
                    geometryRebuilds,
                    accepted.size(),
                    skippedOversized,
                    skippedAtlasDepth,
                    skippedCellBudget,
                    skippedTextureLimit,
                    frameCacheHits,
                    geometryCacheHits,
                    frameRescans,
                    geometryCacheRescans,
                    frameScannedCells,
                    geometryScannedCells,
                    nanosToMilliseconds(frameScanNanos),
                    nanosToMilliseconds(frameUpdateNanos),
                    layoutChanged ? "full" : "slice",
                    geometryFullUploads,
                    frameSliceUploads,
                    geometrySliceUploads,
                    frameUploadedBytes,
                    geometryUploadedBytes,
                    nanosToMilliseconds(frameUploadNanos),
                    cellStats.receiverCells(),
                    cellStats.fullCells(),
                    cellStats.exactShapeCells(),
                    cellStats.conservativeCells(),
                    globalShapeFallbackCells,
                    cellStats.receiverOnlyCells(),
                    geometryShapeDefinitionCount,
                    shapeDefinitionLimit,
                    MAX_SHAPE_BOXES,
                    width,
                    height,
                    depth,
                    textureSizeLimit,
                    atlasCells,
                    MAX_GEOMETRY_ATLAS_CELLS,
                    atlasPayloadBytes,
                    MAX_GEOMETRY_PAYLOAD_BYTES
            );
        } else if (frameRescans > 0) {
            geometryUnchangedScans++;
            if (Integer.bitCount(geometryUnchangedScans) == 1) {
                Photonics.LOGGER.info(
                        "Photonics skipped byte-identical Sable topology upload: skipped={}, cacheHits={}, rescans={}, scannedCells={}, scanMs={}, atlasCells={}, payloadBytes={}",
                        geometryUnchangedScans,
                        frameCacheHits,
                        frameRescans,
                        frameScannedCells,
                        nanosToMilliseconds(frameScanNanos),
                        atlasCells,
                        atlasPayloadBytes
                );
            }
        }

        logGeometrySkips(
                candidates.size(),
                accepted.size(),
                skippedOversized,
                skippedAtlasDepth,
                skippedCellBudget,
                skippedTextureLimit,
                textureSizeLimit
        );

        return new GeometryAtlasState(offsets, geometryShapeDefinitionCount);
    }

    private static void logGeometrySkips(
            int candidateCount,
            int acceptedCount,
            int skippedOversized,
            int skippedAtlasDepth,
            int skippedCellBudget,
            int skippedTextureLimit,
            int textureSizeLimit
    ) {
        if (skippedOversized
                + skippedAtlasDepth
                + skippedCellBudget
                + skippedTextureLimit == 0) {
            lastAtlasSkipSummary = null;
            return;
        }

        var summary = new AtlasSkipSummary(
                candidateCount,
                acceptedCount,
                skippedOversized,
                skippedAtlasDepth,
                skippedCellBudget,
                skippedTextureLimit,
                textureSizeLimit
        );
        if (summary.equals(lastAtlasSkipSummary))
            return;

        lastAtlasSkipSummary = summary;
        Photonics.LOGGER.warn(
                "Photonics omitted Sable local geometry under bounded atlas policy: oversized={}, atlasDepth={}, cellBudget={}, textureLimit={}, uploaded={}, candidates={}, limits=axis:{} volume:{} atlasDepth:{} atlasCells:{} payloadBytes:{} glMax3dTextureSize:{}; omitted receivers retain bounds-classified motion identity and matching same-domain direct visibility fails closed",
                skippedOversized,
                skippedAtlasDepth,
                skippedCellBudget,
                skippedTextureLimit,
                acceptedCount,
                candidateCount,
                MAX_GEOMETRY_AXIS,
                MAX_GEOMETRY_VOLUME,
                MAX_GEOMETRY_ATLAS_DEPTH,
                MAX_GEOMETRY_ATLAS_CELLS,
                MAX_GEOMETRY_PAYLOAD_BYTES,
                textureSizeLimit
        );
    }

    private static int maximum3dTextureSize() {
        if (cachedMaximum3dTextureSize >= 0)
            return cachedMaximum3dTextureSize;

        cachedMaximum3dTextureSize = Math.max(
                0,
                GL11.glGetInteger(GL12.GL_MAX_3D_TEXTURE_SIZE)
        );
        if (cachedMaximum3dTextureSize == 0) {
            Photonics.LOGGER.error(
                    "Photonics could not query GL_MAX_3D_TEXTURE_SIZE; Sable fine geometry is disabled and matching local visibility will fail closed"
            );
        } else {
            Photonics.LOGGER.info(
                    "Photonics Sable geometry capability: GL_MAX_3D_TEXTURE_SIZE={}, policyDimensionLimit={}",
                    cachedMaximum3dTextureSize,
                    MAX_SHAPE_TEXTURE_DIMENSION
            );
        }
        return cachedMaximum3dTextureSize;
    }

    private static int maximumShapeDefinitions(int textureSizeLimit) {
        int dimensionLimit = Math.min(
                MAX_SHAPE_TEXTURE_DIMENSION,
                Math.max(0, textureSizeLimit)
        );
        if (dimensionLimit < MAX_SHAPE_BOXES * 2)
            return 0;
        return Math.min(MAX_SHAPE_DEFINITIONS, dimensionLimit - 1);
    }

    private static void logGeometryRuntimeBudget(
            long frameScanNanos,
            long frameUpdateNanos,
            long frameUploadedBytes,
            int frameRescans,
            long frameScannedCells
    ) {
        if (frameScanNanos <= GEOMETRY_SCAN_WARNING_NANOS
                && frameUpdateNanos <= GEOMETRY_UPDATE_WARNING_NANOS
                && frameUploadedBytes <= GEOMETRY_UPLOAD_WARNING_BYTES)
            return;

        geometryRuntimeBudgetWarnings++;
        if (Integer.bitCount(geometryRuntimeBudgetWarnings) != 1)
            return;

        Photonics.LOGGER.warn(
                "Photonics Sable topology update exceeded its runtime diagnostic target: warning={}, rescans={}, scannedCells={}, scanMs={}/{}, updateMs={}/{}, uploadedBytes={}/{}; updates are not deferred because retaining stale occluders could fail open",
                geometryRuntimeBudgetWarnings,
                frameRescans,
                frameScannedCells,
                nanosToMilliseconds(frameScanNanos),
                nanosToMilliseconds(GEOMETRY_SCAN_WARNING_NANOS),
                nanosToMilliseconds(frameUpdateNanos),
                nanosToMilliseconds(GEOMETRY_UPDATE_WARNING_NANOS),
                frameUploadedBytes,
                GEOMETRY_UPLOAD_WARNING_BYTES
        );
    }

    private static int alignGeometryWidth(int width) {
        return (width + 3) & ~3;
    }

    private static CachedSubLevelGeometry scanSubLevelGeometry(
            MotionCandidate candidate,
            GeometryKey key
    ) {
        int cellCount = Math.multiplyExact(
                Math.multiplyExact(candidate.sizeX(), candidate.sizeY()),
                candidate.sizeZ()
        );
        byte[] payload = new byte[Math.multiplyExact(cellCount, GEOMETRY_CELL_STRIDE)];
        var shapeIds = new LinkedHashMap<ShapeKey, Integer>();
        int receiverCells = 0;
        int fullCells = 0;
        int exactShapeCells = 0;
        int conservativeCells = 0;
        int receiverOnlyCells = 0;
        var mutablePos = new BlockPos.MutableBlockPos();

        for (int z = 0; z < candidate.sizeZ(); z++) {
            for (int y = 0; y < candidate.sizeY(); y++) {
                for (int x = 0; x < candidate.sizeX(); x++) {
                    mutablePos.set(
                            candidate.minX() + x,
                            candidate.minY() + y,
                            candidate.minZ() + z
                    );
                    BlockState state = candidate.blockGetter().getBlockState(mutablePos);
                    boolean receiver = !state.isAir()
                            || !state.getFluidState().isEmpty();
                    CellOcclusion occlusion = classifyLocalOcclusion(
                            candidate.blockGetter(),
                            mutablePos,
                            state,
                            shapeIds
                    );
                    if (!receiver && occlusion.boxCount() == 0)
                        continue;

                    int index = (
                            ((z * candidate.sizeY()) + y) * candidate.sizeX() + x
                    ) * GEOMETRY_CELL_STRIDE;
                    payload[index] = (byte) (receiver ? 0xff : 0);
                    payload[index + 1] = (byte) occlusion.boxCount();
                    payload[index + 2] = (byte) (occlusion.shapeId() & 0xff);
                    payload[index + 3] = (byte) ((occlusion.shapeId() >>> 8) & 0xff);

                    if (receiver)
                        receiverCells++;
                    if (occlusion.boxCount() == FULL_CELL_BOX_COUNT)
                        fullCells++;
                    else if (occlusion.boxCount() == CONSERVATIVE_CELL_BOX_COUNT)
                        conservativeCells++;
                    else if (occlusion.boxCount() > 0)
                        exactShapeCells++;
                    else if (receiver)
                        receiverOnlyCells++;
                }
            }
        }

        return new CachedSubLevelGeometry(
                key,
                payload,
                List.copyOf(shapeIds.keySet()),
                new GeometryCellStats(
                        receiverCells,
                        fullCells,
                        exactShapeCells,
                        conservativeCells,
                        receiverOnlyCells
                ),
                cellCount
        );
    }

    private static boolean sameGeometryContent(
            CachedSubLevelGeometry first,
            CachedSubLevelGeometry second
    ) {
        return first.shapeKeys().equals(second.shapeKeys())
                && Arrays.equals(first.localPayload(), second.localPayload());
    }

    private static int registerPersistentShapes(
            List<PreparedGeometry> prepared,
            int shapeDefinitionLimit
    ) {
        int added = 0;
        for (PreparedGeometry entry : prepared) {
            for (ShapeKey shape : entry.geometry().shapeKeys()) {
                if (geometryShapeIds.containsKey(shape))
                    continue;
                if (geometryShapeIds.size() >= shapeDefinitionLimit)
                    continue;

                geometryShapeIds.put(shape, geometryShapeIds.size() + 1);
                added++;
            }
        }
        return added;
    }

    private static EncodedGeometry encodeGeometry(CachedSubLevelGeometry geometry) {
        byte[] encoded = Arrays.copyOf(
                geometry.localPayload(),
                geometry.localPayload().length
        );
        int globalFallbackCells = 0;
        for (int index = 0; index < encoded.length; index += GEOMETRY_CELL_STRIDE) {
            int boxCount = Byte.toUnsignedInt(encoded[index + 1]);
            if (boxCount <= 0 || boxCount > MAX_SHAPE_BOXES)
                continue;

            int localShapeId = Byte.toUnsignedInt(encoded[index + 2])
                    | (Byte.toUnsignedInt(encoded[index + 3]) << 8);
            Integer globalShapeId = localShapeId > 0
                    && localShapeId <= geometry.shapeKeys().size()
                    ? geometryShapeIds.get(geometry.shapeKeys().get(localShapeId - 1))
                    : null;
            if (globalShapeId == null
                    || globalShapeId <= 0
                    || globalShapeId > MAX_SHAPE_DEFINITIONS) {
                encoded[index + 1] = (byte) CONSERVATIVE_CELL_BOX_COUNT;
                encoded[index + 2] = 0;
                encoded[index + 3] = 0;
                globalFallbackCells++;
                continue;
            }

            encoded[index + 2] = (byte) (globalShapeId & 0xff);
            encoded[index + 3] = (byte) ((globalShapeId >>> 8) & 0xff);
        }
        return new EncodedGeometry(encoded, globalFallbackCells);
    }

    private static void copySliceToAtlas(
            byte[] slice,
            MotionCandidate candidate,
            int atlasZ,
            byte[] atlas,
            int atlasWidth,
            int atlasHeight
    ) {
        int rowBytes = candidate.sizeX() * GEOMETRY_CELL_STRIDE;
        for (int z = 0; z < candidate.sizeZ(); z++) {
            for (int y = 0; y < candidate.sizeY(); y++) {
                int source = (
                        (z * candidate.sizeY() + y) * candidate.sizeX()
                ) * GEOMETRY_CELL_STRIDE;
                int target = (
                        ((atlasZ + z) * atlasHeight + y) * atlasWidth
                ) * GEOMETRY_CELL_STRIDE;
                System.arraycopy(slice, source, atlas, target, rowBytes);
            }
        }
    }

    private static boolean sliceMatchesAtlas(
            byte[] slice,
            MotionCandidate candidate,
            int atlasZ,
            byte[] atlas,
            int atlasWidth,
            int atlasHeight
    ) {
        int rowBytes = candidate.sizeX() * GEOMETRY_CELL_STRIDE;
        for (int z = 0; z < candidate.sizeZ(); z++) {
            for (int y = 0; y < candidate.sizeY(); y++) {
                int source = (
                        (z * candidate.sizeY() + y) * candidate.sizeX()
                ) * GEOMETRY_CELL_STRIDE;
                int target = (
                        ((atlasZ + z) * atlasHeight + y) * atlasWidth
                ) * GEOMETRY_CELL_STRIDE;
                if (Arrays.mismatch(
                        slice,
                        source,
                        source + rowBytes,
                        atlas,
                        target,
                        target + rowBytes
                ) >= 0)
                    return false;
            }
        }
        return true;
    }

    private static void ensureGeometryTexture(int width, int height, int depth) {
        if (geometryTexture == null || geometryTexture.ph$isClosed()) {
            geometryTexture = IRenderSystem.getDevice().ph$createTexture3D(
                    () -> "Photonics Sable Geometry Atlas",
                    TextureUsage.TEXTURE_BINDING | TextureUsage.COPY_DST,
                    ITextureFormat.rgba8(),
                    width,
                    height,
                    depth,
                    1
            );
        } else if (geometryWidth != width
                || geometryHeight != height
                || geometryDepth != depth) {
            geometryTexture.ph$resize(new Vector3i(width, height, depth));
        }
    }

    private static void uploadGeometryRegion(
            byte[] payload,
            Vector3i offset,
            Vector3i size
    ) {
        ByteBuffer data = ByteBuffer.allocateDirect(payload.length);
        data.put(payload);
        data.flip();
        IRenderSystem.getDevice().ph$createCommandEncoder().ph$writeToTexture(
                geometryTexture,
                data,
                offset,
                size
        );
    }

    private static GeometryCellStats aggregateCellStats(
            List<PreparedGeometry> prepared
    ) {
        int receiverCells = 0;
        int fullCells = 0;
        int exactShapeCells = 0;
        int conservativeCells = 0;
        int receiverOnlyCells = 0;
        for (PreparedGeometry entry : prepared) {
            GeometryCellStats stats = entry.geometry().stats();
            receiverCells += stats.receiverCells();
            fullCells += stats.fullCells();
            exactShapeCells += stats.exactShapeCells();
            conservativeCells += stats.conservativeCells();
            receiverOnlyCells += stats.receiverOnlyCells();
        }
        return new GeometryCellStats(
                receiverCells,
                fullCells,
                exactShapeCells,
                conservativeCells,
                receiverOnlyCells
        );
    }

    private static double nanosToMilliseconds(long nanos) {
        return nanos / 1_000_000.0d;
    }

    private static CellOcclusion classifyLocalOcclusion(
            BlockGetter level,
            BlockPos pos,
            BlockState state,
            Map<ShapeKey, Integer> shapeIds
    ) {
        // Emissive geometry stays present; the shader exempts only the
        // currently sampled emitter cell.
        if (state.isAir() || !state.getFluidState().isEmpty())
            return CellOcclusion.EMPTY;

        List<AABB> sourceBoxes = state.getShape(level, pos).toAabbs();
        if (sourceBoxes.isEmpty())
            return CellOcclusion.EMPTY;

        if (sourceBoxes.size() > MAX_SHAPE_BOXES)
            return CellOcclusion.CONSERVATIVE;

        var boxes = new ArrayList<ShapeBox>(sourceBoxes.size());
        for (AABB source : sourceBoxes) {
            if (!isRepresentableShapeBox(source))
                return CellOcclusion.CONSERVATIVE;
            ShapeBox box = clippedShapeBox(source);
            if (box != null)
                boxes.add(box);
        }
        if (boxes.isEmpty())
            return CellOcclusion.EMPTY;
        if (boxes.size() > MAX_SHAPE_BOXES)
            return CellOcclusion.CONSERVATIVE;
        if (boxes.size() == 1 && boxes.get(0).isFullCell())
            return CellOcclusion.FULL;

        ShapeKey key = new ShapeKey(List.copyOf(boxes));
        Integer shapeId = shapeIds.get(key);
        if (shapeId == null) {
            if (shapeIds.size() >= MAX_SHAPE_DEFINITIONS)
                return CellOcclusion.CONSERVATIVE;
            shapeId = shapeIds.size() + 1;
            shapeIds.put(key, shapeId);
        }

        return new CellOcclusion(boxes.size(), shapeId);
    }

    private static boolean isRepresentableShapeBox(AABB source) {
        return Double.isFinite(source.minX)
                && Double.isFinite(source.minY)
                && Double.isFinite(source.minZ)
                && Double.isFinite(source.maxX)
                && Double.isFinite(source.maxY)
                && Double.isFinite(source.maxZ)
                && source.minX >= -SHAPE_EPSILON
                && source.minY >= -SHAPE_EPSILON
                && source.minZ >= -SHAPE_EPSILON
                && source.maxX <= 1.0d + SHAPE_EPSILON
                && source.maxY <= 1.0d + SHAPE_EPSILON
                && source.maxZ <= 1.0d + SHAPE_EPSILON
                && source.minX <= source.maxX
                && source.minY <= source.maxY
                && source.minZ <= source.maxZ;
    }

    private static ShapeBox clippedShapeBox(AABB source) {
        double minX = clampShapeCoordinate(source.minX);
        double minY = clampShapeCoordinate(source.minY);
        double minZ = clampShapeCoordinate(source.minZ);
        double maxX = clampShapeCoordinate(source.maxX);
        double maxY = clampShapeCoordinate(source.maxY);
        double maxZ = clampShapeCoordinate(source.maxZ);
        if (maxX - minX <= SHAPE_EPSILON
                || maxY - minY <= SHAPE_EPSILON
                || maxZ - minZ <= SHAPE_EPSILON)
            return null;

        return new ShapeBox(
                (float) minX,
                (float) minY,
                (float) minZ,
                (float) maxX,
                (float) maxY,
                (float) maxZ
        );
    }

    private static double clampShapeCoordinate(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static boolean shapeTextureMatches(
            int shapeDefinitionCount,
            int textureSizeLimit
    ) {
        int shapeDefinitionLimit = maximumShapeDefinitions(textureSizeLimit);
        if (shapeDefinitionCount <= 0
                || shapeDefinitionCount > shapeDefinitionLimit
                || shapeTexture == null
                || shapeTexture.ph$isClosed())
            return false;

        int width = MAX_SHAPE_BOXES * 2;
        int height = shapeDefinitionCount + 1;
        if (width > MAX_SHAPE_TEXTURE_DIMENSION
                || height > MAX_SHAPE_TEXTURE_DIMENSION
                || width > textureSizeLimit
                || height > textureSizeLimit)
            return false;

        return shapeTexture.ph$size(0).equals(new Vector3i(width, height, 1));
    }

    private static long shapeTablePayloadBytes(int shapeDefinitionCount) {
        return (long) MAX_SHAPE_BOXES
                * 2L
                * (shapeDefinitionCount + 1L)
                * 4L
                * Float.BYTES;
    }

    private static int uploadShapeTable(
            List<ShapeKey> shapeKeys,
            int textureSizeLimit
    ) {
        if (shapeKeys.isEmpty()) {
            closeShapeTexture();
            return 0;
        }

        int width = MAX_SHAPE_BOXES * 2;
        int height = shapeKeys.size() + 1;
        int shapeDefinitionLimit = maximumShapeDefinitions(textureSizeLimit);
        if (shapeKeys.size() > shapeDefinitionLimit
                || width > MAX_SHAPE_TEXTURE_DIMENSION
                || height > MAX_SHAPE_TEXTURE_DIMENSION
                || width > textureSizeLimit
                || height > textureSizeLimit) {
            closeShapeTexture();
            Photonics.LOGGER.error(
                    "Photonics rejected invalid Sable shape-table dimensions: definitions={}, size={}x{}x1, limits=definitions:{} policyDimension:{} glMax3dTextureSize:{}; partial cells will fail closed",
                    shapeKeys.size(),
                    width,
                    height,
                    shapeDefinitionLimit,
                    MAX_SHAPE_TEXTURE_DIMENSION,
                    textureSizeLimit
            );
            return 0;
        }
        for (ShapeKey shape : shapeKeys) {
            if (shape.boxes().isEmpty() || shape.boxes().size() > MAX_SHAPE_BOXES) {
                closeShapeTexture();
                Photonics.LOGGER.error(
                        "Photonics rejected invalid Sable shape-table row: boxes={}, limit={}; partial cells will fail closed",
                        shape.boxes().size(),
                        MAX_SHAPE_BOXES
                );
                return 0;
            }
        }

        ByteBuffer data = ByteBuffer.allocateDirect(
                Math.toIntExact(shapeTablePayloadBytes(shapeKeys.size()))
        ).order(ByteOrder.nativeOrder());
        var values = data.asFloatBuffer();

        for (int shapeIndex = 0; shapeIndex < shapeKeys.size(); shapeIndex++) {
            List<ShapeBox> boxes = shapeKeys.get(shapeIndex).boxes();
            int row = shapeIndex + 1;
            for (int boxIndex = 0; boxIndex < boxes.size(); boxIndex++) {
                ShapeBox box = boxes.get(boxIndex);
                int minOffset = ((row * width + boxIndex * 2) * 4);
                values.put(minOffset, box.minX());
                values.put(minOffset + 1, box.minY());
                values.put(minOffset + 2, box.minZ());
                int maxOffset = minOffset + 4;
                values.put(maxOffset, box.maxX());
                values.put(maxOffset + 1, box.maxY());
                values.put(maxOffset + 2, box.maxZ());
            }
        }

        if (shapeTexture == null || shapeTexture.ph$isClosed()) {
            shapeTexture = IRenderSystem.getDevice().ph$createTexture3D(
                    () -> "Photonics Sable Shape Table",
                    TextureUsage.TEXTURE_BINDING | TextureUsage.COPY_DST,
                    ITextureFormat.rgba32f(),
                    width,
                    height,
                    1,
                    1
            );
        } else {
            Vector3i requiredSize = new Vector3i(width, height, 1);
            if (!shapeTexture.ph$size(0).equals(requiredSize))
                shapeTexture.ph$resize(requiredSize);
        }

        IRenderSystem.getDevice().ph$createCommandEncoder().ph$writeToTexture(
                shapeTexture,
                data,
                new Vector3i(),
                new Vector3i(width, height, 1)
        );
        return shapeKeys.size();
    }

    private static void resetGeometryAtlas() {
        geometryCache.clear();
        geometryShapeIds.clear();
        geometryLayoutKeys = List.of();
        geometryOffsets = new int[0];
        geometryPayload = new byte[0];
        geometryShapeDefinitionCount = 0;
        geometryRebuilds = 0;
        geometryUnchangedScans = 0;
        geometryCacheHits = 0L;
        geometryCacheRescans = 0L;
        geometryScannedCells = 0L;
        geometryFullUploads = 0L;
        geometrySliceUploads = 0L;
        geometryUploadedBytes = 0L;
        geometryRuntimeBudgetWarnings = 0;
        lastAtlasSkipSummary = null;
        cachedMaximum3dTextureSize = -1;
        nextGeometryRevision = 1L;
        closeGeometryTextures();
    }

    private static void closeGeometryTextures() {
        if (geometryTexture != null && !geometryTexture.ph$isClosed())
            geometryTexture.close();
        geometryTexture = null;
        closeShapeTexture();
        geometryWidth = 0;
        geometryHeight = 0;
        geometryDepth = 0;
        geometryShapeDefinitionCount = 0;
    }

    private static void closeShapeTexture() {
        if (shapeTexture != null && !shapeTexture.ph$isClosed())
            shapeTexture.close();
        shapeTexture = null;
    }

    private static GeometryKey geometryKey(MotionCandidate candidate) {
        byte[] currentRevision = candidate.occupancyRevision();
        GeometryRevisionSnapshot cached = geometryRevisionSnapshots.get(candidate.uniqueId());
        // Contraption Lights replaces this array on a topology rebuild. Its
        // identity catches partial-shape changes that leave coarse bytes equal.
        if (cached == null || cached.sourceRevision() != currentRevision) {
            cached = new GeometryRevisionSnapshot(
                    currentRevision,
                    nextGeometryRevision++
            );
            geometryRevisionSnapshots.put(
                    candidate.uniqueId(),
                    cached
            );
        }

        return new GeometryKey(
                candidate.uniqueId(),
                candidate.minX(),
                candidate.minY(),
                candidate.minZ(),
                candidate.sizeX(),
                candidate.sizeY(),
                candidate.sizeZ(),
                cached.revision()
        );
    }

    private static int motionToken(UUID uniqueId) {
        Integer current = motionTokens.get(uniqueId);
        if (current != null)
            return current;

        for (int attempt = 0; attempt < MAX_MOTION_TOKEN; attempt++) {
            int token = nextMotionToken++;
            if (nextMotionToken > MAX_MOTION_TOKEN)
                nextMotionToken = 1;
            if (motionTokens.containsValue(token))
                continue;

            motionTokens.put(uniqueId, token);
            return token;
        }

        throw new IllegalStateException("No free Sable temporal-domain token");
    }

    private static void logMotionCapture(int subLevels) {
        if (!motionActiveLogged && subLevels > 0) {
            motionActiveLogged = true;
            Photonics.LOGGER.info(
                    "Photonics Sable receiver motion active: subLevels={}, classifier=normal-guided-receiver-cell-atlas+emissive-cells+unique-atlasless-bounds-token+ambiguous-unknown-token, emitterIdentity=explicit-sublevel-token, localVisibility=same-token-rgba-cell-atlas+sparse-shape-aabb+64-box-fail-closed-supercover-dda, crossDomainVisibility=static-world-only, temporalTransform=camera-relative-stable-anchor-double-compose",
                    subLevels
            );
        }

        if (subLevels != lastMotionSubLevels) {
            Photonics.LOGGER.info(
                    "Photonics v24 Sable receiver motion: {} -> {}",
                    Math.max(lastMotionSubLevels, 0),
                    subLevels
            );
            lastMotionSubLevels = subLevels;
        }
    }

    private static void logSkyLightScale(
            UUID uniqueId,
            int scale,
            double logicalY,
            double renderY,
            double cameraY
    ) {
        if (!skyLightDiagnosticsLogged) {
            skyLightDiagnosticsLogged = true;
            Photonics.LOGGER.info(
                    "Photonics v55 Sable skylight diagnostics active: source=ClientSubLevel.latestSkyLightScale, freezeGetter={}",
                    Boolean.getBoolean(FREEZE_SABLE_SKYLIGHT_PROPERTY)
            );
        }

        Integer previous = lastSkyLightScales.put(uniqueId, scale);
        if (previous != null && previous == scale)
            return;

        Photonics.LOGGER.info(
                "Photonics v55 Sable skylight transition: subLevel={}, scale={} -> {}, logicalY={} (cell {}), renderY={} (cell {}), cameraY={} (cell {})",
                uniqueId,
                previous == null ? "initial" : previous,
                scale,
                formatCoordinate(logicalY),
                (long) Math.floor(logicalY),
                formatCoordinate(renderY),
                (long) Math.floor(renderY),
                formatCoordinate(cameraY),
                (long) Math.floor(cameraY)
        );
    }

    private static String formatCoordinate(double value) {
        return String.format(Locale.ROOT, "%.5f", value);
    }

    private static void logCapture(
            int structures,
            int sourceLights,
            int uploadedLights,
            int actuallyMovingLights,
            int zeroLuminanceTracedLights,
            int rejectedMaterials,
            int recoveredMaterials,
            String firstValidationIssue
    ) {
        if (!activeLogged && structures > 0) {
            activeLogged = true;
            Photonics.LOGGER.info(
                    "Photonics v42 frame-aligned Contraption Lights/Sable light bridge active: structures={}, sourceLights={}, uploadedLights={}, identity=sublevel-uuid-token, proposalBudget=adaptive-half-candidates",
                    structures,
                    sourceLights,
                    uploadedLights
            );
        }

        if (uploadedLights != lastUploadedLights) {
            Photonics.LOGGER.info(
                    "Photonics v24 Sable moving lights: {} -> {} (structures={}, sourceLights={})",
                    Math.max(lastUploadedLights, 0),
                    uploadedLights,
                    structures,
                    sourceLights
            );
            lastUploadedLights = uploadedLights;
        }

        if (actuallyMovingLights != lastActuallyMovingLights) {
            Photonics.LOGGER.info(
                    "Photonics v35 Sable reactive lights: moving={}, stationary={}, uploaded={}",
                    actuallyMovingLights,
                    Math.max(0, uploadedLights - actuallyMovingLights),
                    uploadedLights
            );
            lastActuallyMovingLights = actuallyMovingLights;
        }

        if (zeroLuminanceTracedLights != lastZeroLuminanceTracedLights
                || rejectedMaterials != lastRejectedMaterials
                || recoveredMaterials != lastRecoveredMaterials) {
            Photonics.LOGGER.info(
                    "Photonics v43 Sable source validation: zeroLuminanceButTraced={}, rejectedMaterial={}, recoveredStaleMaterial={}, firstIssue={}",
                    zeroLuminanceTracedLights,
                    rejectedMaterials,
                    recoveredMaterials,
                    firstValidationIssue == null ? "none" : firstValidationIssue
            );
            lastZeroLuminanceTracedLights = zeroLuminanceTracedLights;
            lastRejectedMaterials = rejectedMaterials;
            lastRecoveredMaterials = recoveredMaterials;
        }
    }

    private static Access access() throws ReflectiveOperationException {
        if (access == null)
            access = new Access();
        return access;
    }

    private static MotionAccess motionAccess() throws ReflectiveOperationException {
        if (motionAccess == null)
            motionAccess = new MotionAccess();
        return motionAccess;
    }

    private record SableLightIdentity(UUID subLevelId, int x, int y, int z) {
    }

    private record MotionCandidate(
            UUID uniqueId,
            Matrix4f currentPlayerToGrid,
            Matrix4f currentPlayerToPreviousPlayer,
            Matrix4f previousPlayerToCurrentGrid,
            BlockGetter blockGetter,
            int minX,
            int minY,
            int minZ,
            int sizeX,
            int sizeY,
            int sizeZ,
            byte[] occupancyRevision,
            List<Vector3i> emissiveCells
    ) {
    }

    private record CellOcclusion(int boxCount, int shapeId) {
        private static final CellOcclusion EMPTY = new CellOcclusion(0, 0);
        private static final CellOcclusion FULL =
                new CellOcclusion(FULL_CELL_BOX_COUNT, 0);
        private static final CellOcclusion CONSERVATIVE =
                new CellOcclusion(CONSERVATIVE_CELL_BOX_COUNT, 0);
    }

    private record ShapeBox(
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ
    ) {
        private boolean isFullCell() {
            return minX <= SHAPE_EPSILON
                    && minY <= SHAPE_EPSILON
                    && minZ <= SHAPE_EPSILON
                    && maxX >= 1.0d - SHAPE_EPSILON
                    && maxY >= 1.0d - SHAPE_EPSILON
                    && maxZ >= 1.0d - SHAPE_EPSILON;
        }
    }

    private record ShapeKey(List<ShapeBox> boxes) {
    }

    private record GeometryAtlasState(int[] offsets, int shapeDefinitionCount) {
    }

    private record AcceptedGeometry(
            MotionCandidate candidate,
            int atlasZ
    ) {
    }

    private record AtlasSkipSummary(
            int candidateCount,
            int acceptedCount,
            int skippedOversized,
            int skippedAtlasDepth,
            int skippedCellBudget,
            int skippedTextureLimit,
            int textureSizeLimit
    ) {
    }

    private record PreparedGeometry(
            AcceptedGeometry accepted,
            CachedSubLevelGeometry geometry,
            boolean contentChanged
    ) {
    }

    private record CachedSubLevelGeometry(
            GeometryKey key,
            byte[] localPayload,
            List<ShapeKey> shapeKeys,
            GeometryCellStats stats,
            int cellCount
    ) {
    }

    private record GeometryCellStats(
            int receiverCells,
            int fullCells,
            int exactShapeCells,
            int conservativeCells,
            int receiverOnlyCells
    ) {
    }

    private record EncodedGeometry(byte[] payload, int globalShapeFallbackCells) {
    }

    private record GeometryKey(
            UUID uniqueId,
            int minX,
            int minY,
            int minZ,
            int sizeX,
            int sizeY,
            int sizeZ,
            long topologyRevision
    ) {
    }

    private record GeometryRevisionSnapshot(byte[] sourceRevision, long revision) {
    }

    private record GeometryLayoutKey(
            UUID uniqueId,
            int minX,
            int minY,
            int minZ,
            int sizeX,
            int sizeY,
            int sizeZ,
            int atlasZ
    ) {
    }

    private static final class Access {
        private final Field states;
        private final Field lightX;
        private final Field lightY;
        private final Field lightZ;
        private final Field lightLum;

        private Access() throws ReflectiveOperationException {
            var lightingClass = Class.forName(
                    "xyz.atmerek.contraptionlights.veil.sublevel.SubLevelVeilLighting"
            );
            var stateClass = Class.forName(
                    "xyz.atmerek.contraptionlights.veil.sublevel.SubLevelVeilLighting$State"
            );

            states = accessible(lightingClass.getDeclaredField("states"));
            lightX = accessible(stateClass.getDeclaredField("lightX"));
            lightY = accessible(stateClass.getDeclaredField("lightY"));
            lightZ = accessible(stateClass.getDeclaredField("lightZ"));
            lightLum = accessible(stateClass.getDeclaredField("lightLum"));
        }

        private static Field accessible(Field field) {
            field.setAccessible(true);
            return field;
        }
    }

    private static final class MotionAccess {
        private final Field subLevel;
        private final Field occupancy;
        private final Field minX;
        private final Field minY;
        private final Field minZ;
        private final Field sizeX;
        private final Field sizeY;
        private final Field sizeZ;
        private final Field lightX;
        private final Field lightY;
        private final Field lightZ;
        private final Field latestSkyLightScale;
        private final Method renderPose;
        private final Method logicalPose;
        private final Method getLatestSkyLightScale;
        private final Method posePosition;
        private final Method poseOrientation;
        private final Method poseRotationPoint;
        private final Method poseScale;

        private MotionAccess() throws ReflectiveOperationException {
            var stateClass = Class.forName(
                    "xyz.atmerek.contraptionlights.veil.sublevel.SubLevelVeilLighting$State"
            );
            var subLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.ClientSubLevel");
            var poseClass = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc");

            subLevel = accessible(stateClass.getDeclaredField("subLevel"));
            occupancy = accessible(stateClass.getDeclaredField("occupancy"));
            minX = accessible(stateClass.getDeclaredField("minX"));
            minY = accessible(stateClass.getDeclaredField("minY"));
            minZ = accessible(stateClass.getDeclaredField("minZ"));
            sizeX = accessible(stateClass.getDeclaredField("sizeX"));
            sizeY = accessible(stateClass.getDeclaredField("sizeY"));
            sizeZ = accessible(stateClass.getDeclaredField("sizeZ"));
            lightX = accessible(stateClass.getDeclaredField("lightX"));
            lightY = accessible(stateClass.getDeclaredField("lightY"));
            lightZ = accessible(stateClass.getDeclaredField("lightZ"));
            latestSkyLightScale = accessible(subLevelClass.getDeclaredField("latestSkyLightScale"));
            renderPose = subLevelClass.getMethod("renderPose");
            logicalPose = subLevelClass.getMethod("logicalPose");
            getLatestSkyLightScale = subLevelClass.getMethod("getLatestSkyLightScale");
            posePosition = poseClass.getMethod("position");
            poseOrientation = poseClass.getMethod("orientation");
            poseRotationPoint = poseClass.getMethod("rotationPoint");
            poseScale = poseClass.getMethod("scale");
        }

        private Matrix4d buildWorldToLocal(Object pose, int minX, int minY, int minZ)
                throws ReflectiveOperationException {
            Vector3dc rotationPoint = (Vector3dc) poseRotationPoint.invoke(pose);
            Matrix4d localToWorld = new Matrix4d()
                    .translate((Vector3dc) posePosition.invoke(pose))
                    .rotate((Quaterniondc) poseOrientation.invoke(pose))
                    .scale((Vector3dc) poseScale.invoke(pose))
                    .translate(
                            -(rotationPoint.x() - minX),
                            -(rotationPoint.y() - minY),
                            -(rotationPoint.z() - minZ)
                    );
            return localToWorld.invert();
        }

        private List<Vector3i> emissiveCells(
                Object state,
                int minX,
                int minY,
                int minZ,
                int sizeX,
                int sizeY,
                int sizeZ
        ) throws IllegalAccessException {
            int[] xs = (int[]) lightX.get(state);
            int[] ys = (int[]) lightY.get(state);
            int[] zs = (int[]) lightZ.get(state);
            if (xs == null || ys == null || zs == null)
                return List.of();

            int count = Math.min(xs.length, Math.min(ys.length, zs.length));
            var result = new ArrayList<Vector3i>(count);
            for (int i = 0; i < count; i++) {
                int x = xs[i] - minX;
                int y = ys[i] - minY;
                int z = zs[i] - minZ;
                if (x >= 0 && y >= 0 && z >= 0 && x < sizeX && y < sizeY && z < sizeZ)
                    result.add(new Vector3i(x, y, z));
            }
            return result;
        }

        private static Field accessible(Field field) {
            field.setAccessible(true);
            return field;
        }

        private static Method accessible(Method method) {
            method.setAccessible(true);
            return method;
        }
    }
}
