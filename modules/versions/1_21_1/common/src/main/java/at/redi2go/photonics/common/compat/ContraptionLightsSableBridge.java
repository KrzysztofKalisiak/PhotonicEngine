package at.redi2go.photonics.common.compat;

import at.redi2go.photonics.api.mc.world.level.IBlockState;
import at.redi2go.photonics.api.shaders.IShaderPack;
import at.redi2go.photonics.core.Photonics;
import at.redi2go.photonics.core.config.PhConfig;
import at.redi2go.photonics.core.rendering.lights.ExternalLightList;
import at.redi2go.photonics.core.rendering.lights.TracedLightPosition;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3i;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public final class ContraptionLightsSableBridge {
    private static Access access;
    private static boolean unavailable;
    private static boolean activeLogged;
    private static boolean transientFailureLogged;
    private static int lastUploadedLights = -1;

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
            Map<?, ?> states = (Map<?, ?>) bridgeAccess.states.get(null);
            var shaderPack = IShaderPack.getCurrentPack().orElse(null);
            var lightRegistry = PhConfig.getLightRegistry();
            var lights = new ArrayList<TracedLightPosition>();
            var replacedBlockPositions = new HashSet<Vector3i>();
            int sourceLights = 0;

            for (var state : states.values()) {
                int[] lightX = (int[]) bridgeAccess.lightX.get(state);
                int[] lightY = (int[]) bridgeAccess.lightY.get(state);
                int[] lightZ = (int[]) bridgeAccess.lightZ.get(state);
                int[] lightLum = (int[]) bridgeAccess.lightLum.get(state);

                if (lightX == null || lightY == null || lightZ == null || lightLum == null)
                    continue;

                List<?> handles = selectHandles(bridgeAccess, state, lightX.length);
                int count = Math.min(
                        Math.min(lightX.length, lightY.length),
                        Math.min(Math.min(lightZ.length, lightLum.length), handles.size())
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

                    var worldPosition = bridgeAccess.position(handles.get(i));
                    int blockId = shaderPack == null ? -1 : shaderPack.getBlockId(apiBlockState);
                    lights.add(new TracedLightPosition(
                            blockId,
                            new Vector3d(worldPosition),
                            apiBlockState,
                            lightInfo
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
                        "Photonics v20 temporarily skipped a Contraption Lights/Sable moving-light capture",
                        exception
                );
            }
        } catch (ReflectiveOperationException | LinkageError exception) {
            unavailable = true;
            ExternalLightList.clear();
            Photonics.LOGGER.warn(
                    "Photonics v20 disabled the optional Contraption Lights/Sable moving-light bridge",
                    exception
            );
        }
    }

    public static void clear() {
        ExternalLightList.clear();
        if (lastUploadedLights > 0)
            Photonics.LOGGER.info("Photonics v20 Sable moving lights: {} -> 0", lastUploadedLights);
        lastUploadedLights = 0;
    }

    private static List<?> selectHandles(Access bridgeAccess, Object state, int lightCount)
            throws IllegalAccessException {
        List<?> pointHandles = (List<?>) bridgeAccess.pointHandles.get(state);
        if (pointHandles != null && pointHandles.size() >= lightCount)
            return pointHandles;

        List<?> customHandles = (List<?>) bridgeAccess.handles.get(state);
        return customHandles == null ? List.of() : customHandles;
    }

    private static void logCapture(int structures, int sourceLights, int uploadedLights) {
        if (!activeLogged && structures > 0) {
            activeLogged = true;
            Photonics.LOGGER.info(
                    "Photonics v20 Contraption Lights/Sable moving-light bridge active: structures={}, sourceLights={}, uploadedLights={}",
                    structures,
                    sourceLights,
                    uploadedLights
            );
        }

        if (uploadedLights != lastUploadedLights) {
            Photonics.LOGGER.info(
                    "Photonics v20 Sable moving lights: {} -> {} (structures={}, sourceLights={})",
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

    private static final class Access {
        private final Field states;
        private final Field handles;
        private final Field pointHandles;
        private final Field lightX;
        private final Field lightY;
        private final Field lightZ;
        private final Field lightLum;
        private final Method getLightData;

        private Access() throws ReflectiveOperationException {
            var lightingClass = Class.forName(
                    "xyz.atmerek.contraptionlights.veil.sublevel.SubLevelVeilLighting"
            );
            var stateClass = Class.forName(
                    "xyz.atmerek.contraptionlights.veil.sublevel.SubLevelVeilLighting$State"
            );
            var handleClass = Class.forName(
                    "foundry.veil.api.client.render.light.renderer.LightRenderHandle"
            );

            states = accessible(lightingClass.getDeclaredField("states"));
            handles = accessible(stateClass.getDeclaredField("handles"));
            pointHandles = accessible(stateClass.getDeclaredField("pointHandles"));
            lightX = accessible(stateClass.getDeclaredField("lightX"));
            lightY = accessible(stateClass.getDeclaredField("lightY"));
            lightZ = accessible(stateClass.getDeclaredField("lightZ"));
            lightLum = accessible(stateClass.getDeclaredField("lightLum"));
            getLightData = handleClass.getMethod("getLightData");
        }

        private Vector3dc position(Object handle) throws ReflectiveOperationException {
            Object lightData = getLightData.invoke(handle);
            Object position = lightData.getClass().getMethod("getPosition").invoke(lightData);

            if (position instanceof Vector3dc vector)
                return vector;

            if (position instanceof Vec3 vector)
                return new Vector3d(vector.x, vector.y, vector.z);

            throw new ReflectiveOperationException(
                    "Unsupported Contraption Lights position type: " + position.getClass().getName()
            );
        }

        private static Field accessible(Field field) {
            field.setAccessible(true);
            return field;
        }
    }
}
