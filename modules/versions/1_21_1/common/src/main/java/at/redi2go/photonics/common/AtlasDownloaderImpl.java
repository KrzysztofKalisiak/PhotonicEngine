package at.redi2go.photonics.common;

import at.redi2go.photonics.api.mc.Id;
import at.redi2go.photonics.core.Photonics;
import at.redi2go.photonics.core.rendering.world.IgnoredInterruptedException;
import at.redi2go.photonics.core.rendering.world.bakery.texture.AtlasDownloader;
import at.redi2go.photonics.core.rendering.world.bakery.texture.AtlasTexture;
import at.redi2go.photonics.core.rendering.world.bakery.texture.CpuTexture;
import at.redi2go.photonics.core.rendering.world.bakery.texture.Rgba8Texture;
import at.redi2go.photonics.core.rendering.world.block.TextureData;
import it.unimi.dsi.fastutil.Pair;
import net.irisshaders.iris.gl.texture.TextureAccess;
import net.irisshaders.iris.pbr.texture.PBRTextureHolder;
import net.irisshaders.iris.pbr.texture.PBRTextureManager;
import net.irisshaders.iris.targets.backed.NativeImageBackedSingleColorTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

public class AtlasDownloaderImpl implements AtlasDownloader, Runnable {
    private final Map<Integer, CpuTexture.Factory> textureFormats = new HashMap<>();
    private final ConcurrentHashMap<Id, CompletableFuture<AtlasTexture>> cache = new ConcurrentHashMap<>();

    private final TextureManager textureManager = Minecraft.getInstance().getTextureManager();

    public AtlasDownloaderImpl() {
        textureFormats.put(GL11.GL_RGBA8, Rgba8Texture::new);
        ResourceReloaderListener.add(this);
    }

    @Override
    public void run() {
        cache.clear();
    }

    private CompletableFuture<AtlasTexture> downloadTexture(Id atlasId) {
        return cache.computeIfAbsent(atlasId, (id) ->
                getTextureAtlas(id)
                        .thenCompose(this::getTextureData)
                        .thenCompose(this::getPbrTextures)
                        .thenCompose((result) -> {
                            var albedo = result.first();
                            var pbrHolder = result.second();

                            var normalTexture = getTextureData(pbrHolder.normalTexture());
                            var specularTexture = getTextureData(pbrHolder.specularTexture());

                            return CompletableFuture.allOf(normalTexture, specularTexture)
                                    .thenApply((ignored) -> new AtlasTexture(
                                            createCpuTexture(albedo, 0),
                                            createCpuTexture(normalTexture.getNow(null), TextureData.DEFAULT_NORMAL),
                                            createCpuTexture(specularTexture.getNow(null), TextureData.DEFAULT_SPECULAR)
                                    ));
                        })
        );
    }

    @Override
    public void preloadTexture(Id textureId) {
        downloadTexture(textureId);
    }

    @Override
    public AtlasTexture get(Id textureId) {
        try {
            return downloadTexture(textureId).get();
        } catch (InterruptedException e) {
            throw new IgnoredInterruptedException();
        } catch (ExecutionException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public void close() {
        ResourceReloaderListener.remove(this);
    }

    private record TextureDownload(AbstractTexture texture, int width, int height, int[] data) {
        public boolean isSameSize(TextureDownload other) {
            return other.width == width &&
                    other.height == height;
        }
    }

    private CompletableFuture<@Nullable TextureDownload> getTextureData(AbstractTexture texture) {
        if (texture instanceof NativeImageBackedSingleColorTexture)
            return CompletableFuture.completedFuture(null);

        var result = new CompletableFuture<TextureDownload>();

        try {
            int textureId = texture.getId();

            int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            int[] data = new int[width * height];

            Minecraft.getInstance().execute(() -> {
                try {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
                    GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data);
                    result.complete(new TextureDownload(texture, width, height, data));
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                } finally {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
                }
            });
        } catch (Throwable t) {
            result.completeExceptionally(t);
        }

        return result;
    }

    private CompletableFuture<AbstractTexture> getTextureAtlas(Id atlasId) {
        var future = new CompletableFuture<AbstractTexture>();

        Minecraft.getInstance().execute(() -> {
            try {
                var texture = textureManager.getTexture((ResourceLocation) (Object) atlasId);

                // Preload PBR texture, might be unnecessary
                PBRTextureManager.INSTANCE.getOrLoadHolder(texture.getId());

                future.complete(texture);
            } catch (Throwable ex) {
                future.completeExceptionally(ex);
            }
        });

        return future;
    }

    private CompletableFuture<Pair<TextureDownload, PBRTextureHolder>> getPbrTextures(TextureDownload atlas) {
        var future = new CompletableFuture<Pair<TextureDownload, PBRTextureHolder>>();

        Minecraft.getInstance().execute(() -> {
            try {
                future.complete(Pair.of(atlas, PBRTextureManager.INSTANCE.getHolder(atlas.texture.getId())));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        return future;
    }

    private @Nullable CpuTexture createCpuTexture(TextureDownload download, int defaultValue) {
        if (download == null) return null;

        CpuTexture.Factory textureFormat = textureFormats.get(GL11.GL_RGBA8);
        if (textureFormat == null) {
            Photonics.LOGGER.warn("Unsupported texture format");
            return null;
        }

        return textureFormat.create(download.width, download.height, defaultValue, download.data);
    }
}
