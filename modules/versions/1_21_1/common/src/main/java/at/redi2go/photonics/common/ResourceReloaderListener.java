package at.redi2go.photonics.common;

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ResourceReloaderListener {
    private static final Set<Runnable> DEPENDANTS = ConcurrentHashMap.newKeySet();

    static {
        ((ReloadableResourceManager) Minecraft.getInstance().getResourceManager())
                .registerReloadListener((ResourceManagerReloadListener) resourceManager -> {
                    for (var runnable : DEPENDANTS)
                        runnable.run();
                });
    }

    public static void add(Runnable runnable) {
        DEPENDANTS.add(runnable);
    }

    public static void remove(Runnable runnable) {
        DEPENDANTS.remove(runnable);
    }
}
