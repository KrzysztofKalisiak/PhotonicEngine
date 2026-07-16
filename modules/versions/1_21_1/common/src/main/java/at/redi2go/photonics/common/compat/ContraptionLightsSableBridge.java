package at.redi2go.photonics.common.compat;

import at.redi2go.photonics.api.mc.world.level.IBlockState;
import at.redi2go.photonics.api.shaders.IShaderPack;
import at.redi2go.photonics.core.Photonics;
import at.redi2go.photonics.core.config.PhConfig;
import at.redi2go.photonics.core.rendering.lights.ExternalLightList;
import at.redi2go.photonics.core.rendering.lights.TracedLightPosition;
import at.redi2go.photonics.core.rendering.sublevel.ExternalSubLevelMotion;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ContraptionLightsSableBridge {
    private static Access access;
    private static boolean unavailable;
    private static boolean activeLogged;
    private static boolean transientFailureLogged;
    private static int lastUploadedLights = -1;
    private static MotionAccess motionAccess;
    private static boolean motionUnavailable;
    private static boolean motionTransientFailureLogged;
    private static boolean motionActiveLogged;
    private static int lastMotionSubLevels = -1;
    private static int nextMotionToken = 1;
    private static final Map<UUID, Matrix4f> previousWorldToGrid = new HashMap<>();
    private static final Map<UUID, Integer> motionTokens = new HashMap<>();

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
            var replacedBlockPositions = new HashSet<Vector3i>();
            int sourceLights = 0;

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
                Matrix4f worldToGrid = new Matrix4f((Matrix4f) transformAccess.buildWorldToLocal.invoke(
                        null,
                        pose,
                        minX,
                        minY,
                        minZ
                ));
                if (!worldToGrid.isFinite() || Math.abs(worldToGrid.determinant()) < 0.000001f)
                    continue;
                Matrix4d gridToWorld = new Matrix4d(worldToGrid).invert();

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
                    if (lightLum[i] <= 0)
                        continue;

                    var localPos = new BlockPos(lightX[i], lightY[i], lightZ[i]);
                    var blockState = level.getBlockState(localPos);
                    var apiBlockState = (IBlockState) (Object) blockState;
                    var lightInfo = lightRegistry.get(apiBlockState);

                    if (lightInfo == null || !lightInfo.isTraced())
                        continue;

                    var worldPosition = gridToWorld.transformPosition(
                            lightX[i] - minX + 0.5d,
                            lightY[i] - minY + 0.5d,
                            lightZ[i] - minZ + 0.5d,
                            new Vector3d()
                    );
                    int blockId = shaderPack == null ? -1 : shaderPack.getBlockId(apiBlockState);
                    lights.add(new TracedLightPosition(
                            blockId,
                            worldPosition,
                            apiBlockState,
                            lightInfo,
                            new SableLightIdentity(uniqueId, lightX[i], lightY[i], lightZ[i])
                    ));
                    replacedBlockPositions.add(new Vector3i(lightX[i], lightY[i], lightZ[i]));
                }
            }

            ExternalLightList.submit(lights, replacedBlockPositions);
            logCapture(states.size(), sourceLights, lights.size());
            transientFailureLogged = false;
        } catch (InvocationTargetException | RuntimeException exception) {
            ExternalLightList.clear();
            if (!transientFailureLogged) {
                transientFailureLogged = true;
                Photonics.LOGGER.warn(
                        "Photonics v23 temporarily skipped a frame-aligned Contraption Lights/Sable moving-light capture",
                        exception
                );
            }
        } catch (ReflectiveOperationException | LinkageError exception) {
            unavailable = true;
            ExternalLightList.clear();
            Photonics.LOGGER.warn(
                    "Photonics v23 disabled the optional frame-aligned Contraption Lights/Sable moving-light bridge",
                    exception
            );
        }
    }

    public static void clear() {
        ExternalLightList.clear();
        ExternalSubLevelMotion.clear();
        previousWorldToGrid.clear();
        motionTokens.clear();
        nextMotionToken = 1;
        if (lastUploadedLights > 0)
            Photonics.LOGGER.info("Photonics v23 Sable moving lights: {} -> 0", lastUploadedLights);
        if (lastMotionSubLevels > 0)
            Photonics.LOGGER.info("Photonics v22 Sable receiver motion: {} -> 0", lastMotionSubLevels);
        lastUploadedLights = 0;
        lastMotionSubLevels = 0;
    }

    public static void captureReceiverMotion() {
        if (motionUnavailable || unavailable)
            return;

        try {
            var lightAccess = access();
            Map<?, ?> states = (Map<?, ?>) lightAccess.states.get(null);
            var bridgeAccess = motionAccess();
            var subLevels = new ArrayList<ExternalSubLevelMotion.SubLevel>();
            var currentWorldToGrid = new HashMap<UUID, Matrix4f>();

            for (var mapEntry : states.entrySet()) {
                if (subLevels.size() >= ExternalSubLevelMotion.MAX_SUBLEVELS)
                    break;
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
                Matrix4f current = new Matrix4f((Matrix4f) bridgeAccess.buildWorldToLocal.invoke(
                        null,
                        pose,
                        minX,
                        minY,
                        minZ
                ));
                if (!current.isFinite() || Math.abs(current.determinant()) < 0.000001f)
                    continue;

                Matrix4f previous = previousWorldToGrid.get(uniqueId);
                if (previous == null || !previous.isFinite() || Math.abs(previous.determinant()) < 0.000001f)
                    previous = current;

                Matrix4f currentToPrevious = new Matrix4f(previous)
                        .invert()
                        .mul(current);
                if (!currentToPrevious.isFinite())
                    continue;

                int atlasIndex = bridgeAccess.atlasIndex.getInt(state);
                int atlasZOffset = atlasIndex < 0
                        ? -1
                        : (int) bridgeAccess.atlasZOffset.invoke(null, atlasIndex);
                var emissiveCells = bridgeAccess.emissiveCells(
                        state,
                        minX,
                        minY,
                        minZ,
                        sizeX,
                        sizeY,
                        sizeZ
                );

                subLevels.add(new ExternalSubLevelMotion.SubLevel(
                        motionToken(uniqueId),
                        current,
                        currentToPrevious,
                        new Vector3i(sizeX, sizeY, sizeZ),
                        atlasZOffset,
                        emissiveCells
                ));
                currentWorldToGrid.put(uniqueId, current);
            }

            int occupancyTexture = (int) bridgeAccess.atlasTexture.invoke(null);
            ExternalSubLevelMotion.submit(occupancyTexture, subLevels);
            previousWorldToGrid.keySet().retainAll(currentWorldToGrid.keySet());
            previousWorldToGrid.putAll(currentWorldToGrid);
            motionTokens.keySet().retainAll(currentWorldToGrid.keySet());
            logMotionCapture(subLevels.size());
            motionTransientFailureLogged = false;
        } catch (InvocationTargetException | RuntimeException exception) {
            ExternalSubLevelMotion.clear();
            previousWorldToGrid.clear();
            if (!motionTransientFailureLogged) {
                motionTransientFailureLogged = true;
                Photonics.LOGGER.warn(
                        "Photonics v22 temporarily skipped Sable receiver-motion capture",
                        exception
                );
            }
        } catch (ReflectiveOperationException | LinkageError exception) {
            motionUnavailable = true;
            ExternalSubLevelMotion.clear();
            previousWorldToGrid.clear();
            Photonics.LOGGER.warn(
                    "Photonics v22 disabled the optional Sable receiver-motion bridge",
                    exception
            );
        }
    }

    private static int motionToken(UUID uniqueId) {
        return motionTokens.computeIfAbsent(uniqueId, ignored -> {
            int token = nextMotionToken++;
            if (nextMotionToken > 0xffff)
                nextMotionToken = 1;
            return token;
        });
    }

    private static void logMotionCapture(int subLevels) {
        if (!motionActiveLogged && subLevels > 0) {
            motionActiveLogged = true;
            Photonics.LOGGER.info(
                    "Photonics v22 Sable receiver motion active: subLevels={}, classifier=occluder-atlas+emissive-cells",
                    subLevels
            );
        }

        if (subLevels != lastMotionSubLevels) {
            Photonics.LOGGER.info(
                    "Photonics v22 Sable receiver motion: {} -> {}",
                    Math.max(lastMotionSubLevels, 0),
                    subLevels
            );
            lastMotionSubLevels = subLevels;
        }
    }

    private static void logCapture(int structures, int sourceLights, int uploadedLights) {
        if (!activeLogged && structures > 0) {
            activeLogged = true;
            Photonics.LOGGER.info(
                    "Photonics v23 frame-aligned Contraption Lights/Sable moving-light bridge active: structures={}, sourceLights={}, uploadedLights={}",
                    structures,
                    sourceLights,
                    uploadedLights
            );
        }

        if (uploadedLights != lastUploadedLights) {
            Photonics.LOGGER.info(
                    "Photonics v23 Sable moving lights: {} -> {} (structures={}, sourceLights={})",
                    Math.max(lastUploadedLights, 0),
                    uploadedLights,
                    structures,
                    sourceLights
            );
            lastUploadedLights = uploadedLights;
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
        private final Field atlasIndex;
        private final Field minX;
        private final Field minY;
        private final Field minZ;
        private final Field sizeX;
        private final Field sizeY;
        private final Field sizeZ;
        private final Field lightX;
        private final Field lightY;
        private final Field lightZ;
        private final Method renderPose;
        private final Method buildWorldToLocal;
        private final Method atlasTexture;
        private final Method atlasZOffset;

        private MotionAccess() throws ReflectiveOperationException {
            var lightingClass = Class.forName(
                    "xyz.atmerek.contraptionlights.veil.sublevel.SubLevelVeilLighting"
            );
            var stateClass = Class.forName(
                    "xyz.atmerek.contraptionlights.veil.sublevel.SubLevelVeilLighting$State"
            );
            var subLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.ClientSubLevel");
            var poseClass = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc");
            var atlasClass = Class.forName(
                    "xyz.atmerek.contraptionlights.veil.sublevel.SubLevelOccluderAtlas"
            );

            subLevel = accessible(stateClass.getDeclaredField("subLevel"));
            atlasIndex = accessible(stateClass.getDeclaredField("atlasIndex"));
            minX = accessible(stateClass.getDeclaredField("minX"));
            minY = accessible(stateClass.getDeclaredField("minY"));
            minZ = accessible(stateClass.getDeclaredField("minZ"));
            sizeX = accessible(stateClass.getDeclaredField("sizeX"));
            sizeY = accessible(stateClass.getDeclaredField("sizeY"));
            sizeZ = accessible(stateClass.getDeclaredField("sizeZ"));
            lightX = accessible(stateClass.getDeclaredField("lightX"));
            lightY = accessible(stateClass.getDeclaredField("lightY"));
            lightZ = accessible(stateClass.getDeclaredField("lightZ"));
            renderPose = subLevelClass.getMethod("renderPose");
            buildWorldToLocal = accessible(lightingClass.getDeclaredMethod(
                    "buildWorldToLocal",
                    poseClass,
                    int.class,
                    int.class,
                    int.class
            ));
            atlasTexture = atlasClass.getMethod("textureId");
            atlasZOffset = atlasClass.getMethod("zOffset", int.class);
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
