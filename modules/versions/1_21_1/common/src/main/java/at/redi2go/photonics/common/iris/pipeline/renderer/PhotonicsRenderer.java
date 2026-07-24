package at.redi2go.photonics.common.iris.pipeline.renderer;

import at.redi2go.photonics.common.iris.pipeline.CompositeRendererPassExt;
import at.redi2go.photonics.common.iris.pipeline.framebuffer.InternalIrisFramebuffer;
import at.redi2go.photonics.common.mixins.iris.pipeline.passes.composite.CompositeRendererAccessor;
import at.redi2go.photonics.core.Photonics;
import com.google.common.collect.ImmutableMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.gl.image.GlImage;
import net.irisshaders.iris.gl.texture.TextureAccess;
import net.irisshaders.iris.pathways.CenterDepthSampler;
import net.irisshaders.iris.pipeline.CompositePass;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.targets.BufferFlipper;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

public class PhotonicsRenderer extends CompositeRenderer {
    private static final int GPU_QUERY_COUNT = 4;
    private static final int GPU_QUERY_FRAME_INTERVAL = 4;
    private static final int MIN_TIMING_FRAMES = 60;
    private static final long TIMING_LOG_INTERVAL_NANOS = 5_000_000_000L;

    private final String name;
    private final String passNames;

    private final int[] gpuQueries = new int[GPU_QUERY_COUNT];
    private final boolean[] gpuQueriesPending = new boolean[GPU_QUERY_COUNT];
    private boolean gpuQueriesInitialized = false;
    private int nextGpuQuery = 0;
    private int renderFrameSequence = 0;

    private long timingWindowStart = System.nanoTime();
    private long cpuNanos = 0L;
    private long maximumCpuNanos = 0L;
    private int cpuFrames = 0;
    private long gpuNanos = 0L;
    private long maximumGpuNanos = 0L;
    private int gpuFrames = 0;

    public PhotonicsRenderer(
            String name,
            WorldRenderingPipeline pipeline,
            PackDirectives packDirectives,
            ProgramSource[] sources,
            ComputeSource[][] computes,
            RenderTargets renderTargets,
            ShaderStorageBufferHolder holder,
            TextureAccess noiseTexture,
            FrameUpdateNotifier updateNotifier,
            CenterDepthSampler centerDepthSampler,
            BufferFlipper bufferFlipper,
            Supplier<ShadowRenderTargets> shadowTargetsSupplier,
            Object2ObjectMap<String, TextureAccess> customTextureIds,
            Object2ObjectMap<String, TextureAccess> irisCustomTextures,
            Set<GlImage> customImages,
            CustomUniforms customUniforms,
            List<DeferredIrisRenderer.Pass> passes
    ) {
        super(
                pipeline,
                CompositePass.DEFERRED,
                packDirectives,
                sources,
                computes,
                renderTargets,
                holder,
                noiseTexture,
                updateNotifier,
                centerDepthSampler,
                bufferFlipper,
                shadowTargetsSupplier,
                TextureStage.DEFERRED,
                customTextureIds,
                irisCustomTextures,
                customImages,
                ImmutableMap.of(),
                customUniforms
        );

        this.name = name;
        this.passNames = passes.stream().map(DeferredIrisRenderer.Pass::name).toList().toString();
        for (CompositeRendererPassExt pass : getPasses())
            pass.setFramebuffer(passes.get(pass.index()).framebuffer());
    }

    public String getName() {
        return name;
    }

    private List<CompositeRendererPassExt> getPasses() {
        return ((CompositeRendererAccessor) this).getPasses();
    }

    @Override
    public void renderAll() {
        ensureGpuQueries();
        pollGpuQueries();

        int queryIndex = Math.floorMod(renderFrameSequence++, GPU_QUERY_FRAME_INTERVAL) == 0
                && GL15.glGetQueryi(GL33.GL_TIME_ELAPSED, GL15.GL_CURRENT_QUERY) == 0
                ? findFreeGpuQuery()
                : -1;
        if (queryIndex >= 0)
            GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, gpuQueries[queryIndex]);

        long start = System.nanoTime();
        try {
            super.renderAll();
        } finally {
            long elapsed = System.nanoTime() - start;
            cpuNanos += elapsed;
            maximumCpuNanos = Math.max(maximumCpuNanos, elapsed);
            cpuFrames++;

            if (queryIndex >= 0) {
                GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                gpuQueriesPending[queryIndex] = true;
                nextGpuQuery = (queryIndex + 1) % gpuQueries.length;
            }

            logTimingsIfReady();
        }
    }

    private void ensureGpuQueries() {
        if (gpuQueriesInitialized) return;

        for (int i = 0; i < gpuQueries.length; i++)
            gpuQueries[i] = GL15.glGenQueries();

        gpuQueriesInitialized = true;
    }

    private void pollGpuQueries() {
        for (int i = 0; i < gpuQueries.length; i++) {
            if (!gpuQueriesPending[i]) continue;
            if (GL15.glGetQueryObjecti(gpuQueries[i], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) continue;

            long elapsed = GL33.glGetQueryObjecti64(gpuQueries[i], GL15.GL_QUERY_RESULT);
            gpuNanos += elapsed;
            maximumGpuNanos = Math.max(maximumGpuNanos, elapsed);
            gpuFrames++;
            gpuQueriesPending[i] = false;
        }
    }

    private int findFreeGpuQuery() {
        for (int i = 0; i < gpuQueries.length; i++) {
            int queryIndex = (nextGpuQuery + i) % gpuQueries.length;
            if (!gpuQueriesPending[queryIndex]) return queryIndex;
        }

        return -1;
    }

    private void logTimingsIfReady() {
        long now = System.nanoTime();
        if (cpuFrames < MIN_TIMING_FRAMES || now - timingWindowStart < TIMING_LOG_INTERVAL_NANOS) return;

        double averageCpuMillis = cpuNanos / (double) cpuFrames / 1_000_000.0;
        double maximumCpuMillis = maximumCpuNanos / 1_000_000.0;
        double averageGpuMillis = gpuFrames == 0 ? 0.0 : gpuNanos / (double) gpuFrames / 1_000_000.0;
        double maximumGpuMillis = maximumGpuNanos / 1_000_000.0;
        Viewport viewport = viewport();
        double megapixels = viewport.pixelCount() / 1_000_000.0;
        double averageGpuMillisPerMegapixel = megapixels > 0.0
                ? averageGpuMillis / megapixels
                : 0.0;

        Photonics.LOGGER.info(
                "Photonics pass timing v73: group={}, passes={}, viewport={}x{}, megapixels={}, cpuInvocations={}, cpuSubmitAvgMs={}, cpuSubmitMaxMs={}, gpuSamples={}, gpuAvgMs={}, gpuMaxMs={}, gpuAvgMsPerMP={}",
                name,
                passNames,
                viewport.width(),
                viewport.height(),
                formatMillis(megapixels),
                cpuFrames,
                formatMillis(averageCpuMillis),
                formatMillis(maximumCpuMillis),
                gpuFrames,
                formatMillis(averageGpuMillis),
                formatMillis(maximumGpuMillis),
                formatMillis(averageGpuMillisPerMegapixel)
        );

        timingWindowStart = now;
        cpuNanos = 0L;
        maximumCpuNanos = 0L;
        cpuFrames = 0;
        gpuNanos = 0L;
        maximumGpuNanos = 0L;
        gpuFrames = 0;
    }

    private Viewport viewport() {
        for (CompositeRendererPassExt pass : getPasses()) {
            var framebuffer = pass.getFramebuffer().orElse(null);
            if (!(framebuffer instanceof InternalIrisFramebuffer internalFramebuffer))
                continue;

            var size = internalFramebuffer.viewportSize();
            return new Viewport(size.x(), size.y());
        }

        return new Viewport(0, 0);
    }

    private static String formatMillis(double millis) {
        return String.format(Locale.ROOT, "%.3f", millis);
    }

    private record Viewport(int width, int height) {
        private long pixelCount() {
            return (long) width * height;
        }
    }

    @Override
    public void recalculateSizes() {
        for (CompositeRendererPassExt pass : getPasses())
            pass.updateSize();
    }

    @Override
    public void destroy() {
        try {
            super.destroy();
        } finally {
            if (gpuQueriesInitialized) {
                for (int query : gpuQueries)
                    GL15.glDeleteQueries(query);

                gpuQueriesInitialized = false;
            }
        }
    }
}
