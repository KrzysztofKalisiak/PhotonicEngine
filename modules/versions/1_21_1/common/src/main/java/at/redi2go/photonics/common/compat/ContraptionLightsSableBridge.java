package at.redi2go.photonics.common.compat;

import at.redi2go.photonics.api.gpu.systems.IRenderSystem;
import at.redi2go.photonics.api.gpu.textures.IGpuTexture3D;
import at.redi2go.photonics.api.gpu.textures.ITextureFormat;
import at.redi2go.photonics.api.gpu.textures.TextureUsage;
import at.redi2go.photonics.api.mc.world.level.IBlock;
import at.redi2go.photonics.api.mc.world.level.IBlockState;
import at.redi2go.photonics.api.shaders.IShaderPack;
import at.redi2go.photonics.common.iris.IrisUtil;
import at.redi2go.photonics.core.Photonics;
import at.redi2go.photonics.core.config.PhConfig;
import at.redi2go.photonics.core.iris.extensions.RestirPipeline;
import at.redi2go.photonics.core.rendering.lights.ExternalLightList;
import at.redi2go.photonics.core.rendering.lights.TracedLightPosition;
import at.redi2go.photonics.core.rendering.sublevel.ExternalSubLevelMotion;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3i;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
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
    private static List<GeometryKey> geometryKeys = List.of();
    private static List<GeometryLayoutKey> geometryLayoutKeys = List.of();
    private static int[] geometryOffsets = new int[0];
    private static byte[] geometryPayload = new byte[0];
    private static int geometryWidth;
    private static int geometryHeight;
    private static int geometryDepth;
    private static int geometryRebuilds;
    private static int geometryUnchangedScans;

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
                    replacementAliases.add(new ExternalLightList.ReplacementAlias(
                            lightX[i],
                            lightY[i],
                            lightZ[i],
                            apiBlockState.ph$block()
                    ));
                    retainReplacementAliases(
                            identity,
                            replacementAliases,
                            apiBlockState.ph$block(),
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
            IBlock block,
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

            var alias = replacementAlias(position, block);
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
            IBlock block
    ) {
        return new ExternalLightList.ReplacementAlias(
                (int) Math.floor(position.x()),
                (int) Math.floor(position.y()),
                (int) Math.floor(position.z()),
                block
        );
    }

    private static void logReplacementAliasTrail(int lights, int aliases) {
        if (replacementAliasTrailLogged || lights <= 0)
            return;

        replacementAliasTrailLogged = true;
        Photonics.LOGGER.info(
                "Photonics v62 stale section-light suppression active: lights={}, aliases={}, aliasHoldMs={}, maxAliasesPerLight={}, matching=block-position+block-identity, mergeOwner=render-thread",
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

            int[] atlasOffsets = updateGeometryAtlas(candidates);
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
            ExternalSubLevelMotion.submit(occupancyTexture, subLevels);
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

    private static int[] updateGeometryAtlas(List<MotionCandidate> candidates) {
        var keys = new ArrayList<GeometryKey>(candidates.size());
        var layoutKeys = new ArrayList<GeometryLayoutKey>(candidates.size());
        for (MotionCandidate candidate : candidates) {
            keys.add(geometryKey(candidate));
            layoutKeys.add(new GeometryLayoutKey(
                    candidate.uniqueId(),
                    candidate.minX(),
                    candidate.minY(),
                    candidate.minZ(),
                    candidate.sizeX(),
                    candidate.sizeY(),
                    candidate.sizeZ()
            ));
        }

        if (keys.equals(geometryKeys))
            return geometryOffsets;

        int[] offsets = new int[candidates.size()];
        Arrays.fill(offsets, -1);
        int width = 1;
        int height = 1;
        int depth = 0;
        int accepted = 0;

        for (int i = 0; i < candidates.size(); i++) {
            MotionCandidate candidate = candidates.get(i);
            long volume = (long) candidate.sizeX() * candidate.sizeY() * candidate.sizeZ();
            if (candidate.sizeX() > MAX_GEOMETRY_AXIS
                    || candidate.sizeY() > MAX_GEOMETRY_AXIS
                    || candidate.sizeZ() > MAX_GEOMETRY_AXIS
                    || volume > MAX_GEOMETRY_VOLUME
                    || depth + candidate.sizeZ() > MAX_GEOMETRY_ATLAS_DEPTH)
                continue;

            offsets[i] = depth;
            depth += candidate.sizeZ();
            width = Math.max(width, candidate.sizeX());
            height = Math.max(height, candidate.sizeY());
            accepted++;
        }

        if (accepted == 0) {
            closeGeometryTexture();
            geometryKeys = List.copyOf(keys);
            geometryLayoutKeys = List.copyOf(layoutKeys);
            geometryOffsets = offsets;
            geometryPayload = new byte[0];
            return offsets;
        }

        width = (width + 3) & ~3;
        byte[] payload = new byte[width * height * depth];
        int receiverCells = 0;
        int occluderCells = 0;
        var mutablePos = new BlockPos.MutableBlockPos();

        for (int i = 0; i < candidates.size(); i++) {
            int atlasZ = offsets[i];
            if (atlasZ < 0)
                continue;

            MotionCandidate candidate = candidates.get(i);
            for (int z = 0; z < candidate.sizeZ(); z++) {
                for (int y = 0; y < candidate.sizeY(); y++) {
                    for (int x = 0; x < candidate.sizeX(); x++) {
                        mutablePos.set(
                                candidate.minX() + x,
                                candidate.minY() + y,
                                candidate.minZ() + z
                        );
                        BlockState state = candidate.blockGetter().getBlockState(mutablePos);
                        int cellFlags = 0;
                        if (!state.isAir() || !state.getFluidState().isEmpty())
                            cellFlags |= 0x40;
                        if (isCoarseOccluder(candidate.blockGetter(), mutablePos, state))
                            cellFlags |= 0x80;
                        if (cellFlags == 0)
                            continue;

                        int index = ((atlasZ + z) * height + y) * width + x;
                        payload[index] = (byte) cellFlags;
                        receiverCells++;
                        if ((cellFlags & 0x80) != 0)
                            occluderCells++;
                    }
                }
            }
        }

        boolean unchanged = geometryTexture != null
                && !geometryTexture.ph$isClosed()
                && geometryWidth == width
                && geometryHeight == height
                && geometryDepth == depth
                && layoutKeys.equals(geometryLayoutKeys)
                && Arrays.equals(offsets, geometryOffsets)
                && Arrays.equals(payload, geometryPayload);
        if (unchanged) {
            geometryKeys = List.copyOf(keys);
            geometryUnchangedScans++;
            if (Integer.bitCount(geometryUnchangedScans) == 1) {
                Photonics.LOGGER.info(
                        "Photonics v27 skipped unchanged Sable geometry upload: skipped={}, subLevels={}, size={}x{}x{}, payloadHash={}",
                        geometryUnchangedScans,
                        accepted,
                        width,
                        height,
                        depth,
                        Integer.toUnsignedString(Arrays.hashCode(payload), 16)
                );
            }
            return geometryOffsets;
        }

        ByteBuffer data = ByteBuffer.allocateDirect(payload.length);
        data.put(payload);
        data.flip();
        if (geometryTexture == null) {
            geometryTexture = IRenderSystem.getDevice().ph$createTexture3D(
                    () -> "Photonics Sable Geometry Atlas",
                    TextureUsage.TEXTURE_BINDING | TextureUsage.COPY_DST,
                    ITextureFormat.r8(),
                    width,
                    height,
                    depth,
                    1
            );
        } else if (geometryWidth != width || geometryHeight != height || geometryDepth != depth) {
            geometryTexture.ph$resize(new Vector3i(width, height, depth));
        }

        geometryWidth = width;
        geometryHeight = height;
        geometryDepth = depth;
        IRenderSystem.getDevice().ph$createCommandEncoder().ph$writeToTexture(
                geometryTexture,
                data,
                new Vector3i(),
                new Vector3i(width, height, depth)
        );
        geometryRebuilds++;
        Photonics.LOGGER.info(
                "Photonics v27 Sable geometry atlas rebuilt: rebuild={}, subLevels={}, receiverCells={}, occluderCells={}, size={}x{}x{}, precision=solid-block-cell, payloadHash={}",
                geometryRebuilds,
                accepted,
                receiverCells,
                occluderCells,
                width,
                height,
                depth,
                Integer.toUnsignedString(Arrays.hashCode(payload), 16)
        );
        geometryKeys = List.copyOf(keys);
        geometryLayoutKeys = List.copyOf(layoutKeys);
        geometryOffsets = offsets;
        geometryPayload = payload;
        return offsets;
    }

    private static boolean isCoarseOccluder(BlockGetter level, BlockPos pos, BlockState state) {
        if (state.isAir() || state.getLightEmission() > 0 || !state.getFluidState().isEmpty())
            return false;

        var collision = state.getCollisionShape(level, pos);
        if (collision.isEmpty())
            return false;

        return state.canOcclude() && Block.isShapeFullBlock(collision);
    }

    private static void resetGeometryAtlas() {
        geometryKeys = List.of();
        geometryLayoutKeys = List.of();
        geometryOffsets = new int[0];
        geometryPayload = new byte[0];
        geometryRebuilds = 0;
        geometryUnchangedScans = 0;
        closeGeometryTexture();
    }

    private static void closeGeometryTexture() {
        if (geometryTexture != null && !geometryTexture.ph$isClosed())
            geometryTexture.close();
        geometryTexture = null;
        geometryWidth = 0;
        geometryHeight = 0;
        geometryDepth = 0;
    }

    private static GeometryKey geometryKey(MotionCandidate candidate) {
        byte[] currentRevision = candidate.occupancyRevision() == null
                ? new byte[0]
                : candidate.occupancyRevision();
        GeometryRevisionSnapshot cached = geometryRevisionSnapshots.get(candidate.uniqueId());
        ByteBuffer revisionContent;
        if (cached != null && Arrays.equals(cached.sourceRevision(), currentRevision)) {
            revisionContent = cached.content();
        } else {
            byte[] revisionSnapshot = Arrays.copyOf(currentRevision, currentRevision.length);
            revisionContent = ByteBuffer.wrap(revisionSnapshot).asReadOnlyBuffer();
            geometryRevisionSnapshots.put(
                    candidate.uniqueId(),
                    new GeometryRevisionSnapshot(revisionSnapshot, revisionContent)
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
                revisionContent
        );
    }

    private static int motionToken(UUID uniqueId) {
        Integer current = motionTokens.get(uniqueId);
        if (current != null)
            return current;

        for (int attempt = 0; attempt < 0xffff; attempt++) {
            int token = nextMotionToken++;
            if (nextMotionToken > 0xffff)
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
                    "Photonics v50 Sable receiver motion active: subLevels={}, classifier=normal-guided-receiver-cell-atlas+emissive-cells, emitterIdentity=explicit-sublevel-token, localVisibility=tri-state-token-gated-conservative-supercover-dda, temporalTransform=camera-relative-stable-anchor-double-compose",
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

    private record GeometryKey(
            UUID uniqueId,
            int minX,
            int minY,
            int minZ,
            int sizeX,
            int sizeY,
            int sizeZ,
            ByteBuffer occupancyRevision
    ) {
    }

    private record GeometryRevisionSnapshot(byte[] sourceRevision, ByteBuffer content) {
    }

    private record GeometryLayoutKey(
            UUID uniqueId,
            int minX,
            int minY,
            int minZ,
            int sizeX,
            int sizeY,
            int sizeZ
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
