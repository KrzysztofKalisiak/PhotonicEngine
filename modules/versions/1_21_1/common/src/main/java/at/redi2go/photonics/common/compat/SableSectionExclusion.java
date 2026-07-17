package at.redi2go.photonics.common.compat;

import at.redi2go.photonics.core.Photonics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/** Keeps Sable's reserved plot sections out of Photonics' main-world compiler. */
public final class SableSectionExclusion {
    private static final AtomicInteger FILTERED_NOTIFICATIONS = new AtomicInteger();

    private static Access access;
    private static boolean unavailable;
    private static boolean transientFailureLogged;

    private SableSectionExclusion() {
    }

    public static boolean isSubLevelSection(int sectionX, int sectionZ) {
        if (unavailable) return false;

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return false;

        try {
            boolean excluded = access().contains(level, sectionX, sectionZ);
            transientFailureLogged = false;
            if (excluded) {
                int filtered = FILTERED_NOTIFICATIONS.incrementAndGet();
                if (Integer.bitCount(filtered) == 1) {
                    Photonics.LOGGER.info(
                            "Photonics v32 ignored Sable plot section notification: filtered={}, sectionX={}, sectionZ={}",
                            filtered,
                            sectionX,
                            sectionZ
                    );
                }
            }
            return excluded;
        } catch (ClassNotFoundException exception) {
            unavailable = true;
            return false;
        } catch (InvocationTargetException | RuntimeException exception) {
            if (!transientFailureLogged) {
                transientFailureLogged = true;
                Photonics.LOGGER.warn(
                        "Photonics v32 temporarily failed to classify a Sable plot section",
                        exception
                );
            }
            return false;
        } catch (ReflectiveOperationException | LinkageError exception) {
            unavailable = true;
            Photonics.LOGGER.warn(
                    "Photonics v32 disabled optional Sable plot-section isolation",
                    exception
            );
            return false;
        }
    }

    private static Access access() throws ReflectiveOperationException {
        if (access == null) {
            Class<?> containerType = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
            Class<?> clientContainerType = Class.forName("dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer");
            access = new Access(
                    containerType.getMethod("getContainer", ClientLevel.class),
                    clientContainerType.getMethod("inBounds", int.class, int.class)
            );
        }
        return access;
    }

    private record Access(Method getContainer, Method inBounds) {
        boolean contains(ClientLevel level, int sectionX, int sectionZ)
                throws InvocationTargetException, IllegalAccessException {
            Object container = getContainer.invoke(null, level);
            return container != null && (boolean) inBounds.invoke(container, sectionX, sectionZ);
        }
    }
}
