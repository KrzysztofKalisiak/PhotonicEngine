package at.redi2go.photonics.common.iris.pipeline.framebuffer;

import at.redi2go.photonics.core.Photonics;
import at.redi2go.photonics.core.iris.RestirDiagnostics;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.textures.IGlTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL45;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;

final class ReservoirDiagnostics implements AutoCloseable {
    private static final String DIRECT_RESERVOIR = "restir_direct_reservoirs0";
    private static final String ESTIMATOR_SOURCE = "restir_local_lighting";
    private static final int TEXTURE_SAMPLE_BYTES = 3 * Float.BYTES;
    private static final int SAMPLE_BYTES = 2 * TEXTURE_SAMPLE_BYTES;
    private static final int SLOT_COUNT = 8;
    private static final int SAMPLE_FRAME_INTERVAL = 4;
    private static final int SAMPLE_GRID_WIDTH = 64;
    private static final int SAMPLE_GRID_HEIGHT = 36;
    private static final int SAMPLE_GRID_SIZE = SAMPLE_GRID_WIDTH * SAMPLE_GRID_HEIGHT;
    private static final int SAMPLE_PERMUTATION = 997;
    private static final int MIN_LOG_SAMPLES = 120;
    private static final long LOG_INTERVAL_NANOS = 5_000_000_000L;
    private static final float LEGACY_RESERVOIR_SAMPLE_CAP = 128.0f;
    private static final float TEMPORAL_RESERVOIR_SAMPLE_CAP = 640.0f;
    private static final int ESTIMATOR_STRATUM_COUNT = 7;
    private static final float ESTIMATOR_LIT_EPSILON = 0.000001f;

    private final Slot[] slots = new Slot[SLOT_COUNT];

    private boolean initialized = false;
    private int frameSequence = 0;
    private int sampleSequence = 0;
    private int sampledWidth = -1;
    private int sampledHeight = -1;
    private long lastLogNanos = System.nanoTime();

    private int samples = 0;
    private int populated = 0;
    private int empty = 0;
    private int visibilityRejected = 0;
    private int invalid = 0;
    private int overLegacyCap = 0;
    private int overTemporalCap = 0;
    private float minimumTotalSamples = Float.POSITIVE_INFINITY;
    private float maximumTotalSamples = 0.0f;

    private int estimatorSamples = 0;
    private int estimatorLit = 0;
    private int estimatorDark = 0;
    private int estimatorVisible = 0;
    private int estimatorRejected = 0;
    private int estimatorImpossibleGain = 0;
    private int estimatorMetadataInvalid = 0;
    private final int[] estimatorSelectedByStratum = new int[ESTIMATOR_STRATUM_COUNT];
    private final int[] estimatorRejectedByStratum = new int[ESTIMATOR_STRATUM_COUNT];
    private float minimumProposalExpansion = Float.POSITIVE_INFINITY;
    private float maximumProposalExpansion = 0.0f;

    ReservoirDiagnostics() {
        for (int i = 0; i < slots.length; i++)
            slots[i] = new Slot();
    }

    void sampleCompletedFrame(SingleFramebuffer framebuffer) {
        var attachment = framebuffer.attachment(DIRECT_RESERVOIR);
        var estimatorAttachment = RestirDiagnostics.isSourceHistoryEnabled()
                && RestirDiagnostics.isDirectEstimatorEnabled()
                ? framebuffer.attachment(ESTIMATOR_SOURCE)
                : null;
        var size = framebuffer.currentSize();
        if (attachment == null || size.x() <= 0 || size.y() <= 0) return;

        ensureInitialized();
        pollCompletedSamples();

        if (sampledWidth != size.x() || sampledHeight != size.y()) {
            sampledWidth = size.x();
            sampledHeight = size.y();
            resetStatistics();
        }

        Slot slot = findFreeSlot();
        if (Math.floorMod(frameSequence++, SAMPLE_FRAME_INTERVAL) == 0 && slot != null) {
            int gridCell = Math.floorMod(sampleSequence++ * SAMPLE_PERMUTATION, SAMPLE_GRID_SIZE);
            int gridX = gridCell % SAMPLE_GRID_WIDTH;
            int gridY = gridCell / SAMPLE_GRID_WIDTH;
            int x = Math.min(size.x() - 1, ((2 * gridX + 1) * size.x()) / (2 * SAMPLE_GRID_WIDTH));
            int y = Math.min(size.y() - 1, ((2 * gridY + 1) * size.y()) / (2 * SAMPLE_GRID_HEIGHT));

            submitSample(
                    slot,
                    ((IGlTexture) attachment.texture()).handle(),
                    estimatorAttachment == null
                            ? 0
                            : ((IGlTexture) estimatorAttachment.texture()).handle(),
                    x,
                    y
            );
        }

        logStatisticsIfReady();
    }

    private void ensureInitialized() {
        if (initialized) return;

        int previousPixelPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            for (var slot : slots) {
                slot.pbo = GL15.glGenBuffers();
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, slot.pbo);
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, SAMPLE_BYTES, GL15.GL_STREAM_READ);
            }
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPixelPackBuffer);
        }

        initialized = true;
    }

    private void pollCompletedSamples() {
        int previousPixelPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            for (var slot : slots) {
                if (slot.fence == 0L) continue;

                int result = GL32.glClientWaitSync(slot.fence, 0, 0L);
                if (result == GL32.GL_TIMEOUT_EXPIRED) continue;

                if (result == GL32.GL_ALREADY_SIGNALED || result == GL32.GL_CONDITION_SATISFIED) {
                    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, slot.pbo);
                    ByteBuffer data = GL30.glMapBufferRange(
                            GL21.GL_PIXEL_PACK_BUFFER,
                            0,
                            SAMPLE_BYTES,
                            GL30.GL_MAP_READ_BIT
                    );
                    if (data != null) {
                        data.order(ByteOrder.nativeOrder());
                        classify(data.getFloat(0), data.getFloat(Float.BYTES), data.getFloat(2 * Float.BYTES));
                        if (slot.hasEstimatorSample) {
                            classifyEstimator(
                                    data.getFloat(TEXTURE_SAMPLE_BYTES),
                                    data.getFloat(TEXTURE_SAMPLE_BYTES + Float.BYTES),
                                    data.getFloat(TEXTURE_SAMPLE_BYTES + 2 * Float.BYTES)
                            );
                        }
                        GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                    }
                }

                GL32.glDeleteSync(slot.fence);
                slot.fence = 0L;
                slot.hasEstimatorSample = false;
            }
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPixelPackBuffer);
        }
    }

    private void submitSample(Slot slot, int texture, int estimatorTexture, int x, int y) {
        int previousPixelPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, slot.pbo);
            GL45.glGetTextureSubImage(
                    texture,
                    0,
                    x,
                    y,
                    0,
                    1,
                    1,
                    1,
                    GL11.GL_RGB,
                    GL11.GL_FLOAT,
                    TEXTURE_SAMPLE_BYTES,
                    0L
            );
            slot.hasEstimatorSample = estimatorTexture != 0;
            if (slot.hasEstimatorSample) {
                GL45.glGetTextureSubImage(
                        estimatorTexture,
                        0,
                        x,
                        y,
                        0,
                        1,
                        1,
                        1,
                        GL11.GL_RGB,
                        GL11.GL_FLOAT,
                        TEXTURE_SAMPLE_BYTES,
                        TEXTURE_SAMPLE_BYTES
                );
            }
            slot.fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPixelPackBuffer);
        }
    }

    private Slot findFreeSlot() {
        for (var slot : slots) {
            if (slot.fence == 0L) return slot;
        }

        return null;
    }

    private void classify(float lightIndex, float weight, float totalSamples) {
        samples++;

        if (!Float.isFinite(lightIndex) || !Float.isFinite(weight) || !Float.isFinite(totalSamples)
                || weight < 0.0f || totalSamples < 0.0f) {
            invalid++;
            return;
        }

        if (totalSamples > LEGACY_RESERVOIR_SAMPLE_CAP + 0.5f)
            overLegacyCap++;
        if (totalSamples > TEMPORAL_RESERVOIR_SAMPLE_CAP + 0.5f)
            overTemporalCap++;

        if (lightIndex < -0.5f) {
            empty++;
            return;
        }

        minimumTotalSamples = Math.min(minimumTotalSamples, totalSamples);
        maximumTotalSamples = Math.max(maximumTotalSamples, totalSamples);

        if (totalSamples <= 0.0f) {
            invalid++;
        } else if (weight <= 0.0f) {
            visibilityRejected++;
        } else {
            populated++;
        }
    }

    private void classifyEstimator(float encodedUnshadowed, float encodedVisible, float metadata) {
        estimatorSamples++;
        if (!Float.isFinite(encodedUnshadowed)
                || !Float.isFinite(encodedVisible)
                || !Float.isFinite(metadata)
                || encodedUnshadowed < 0.0f
                || encodedVisible < 0.0f
                || metadata < 0.0f) {
            estimatorMetadataInvalid++;
            return;
        }

        float unshadowed = decodeEstimatorLuminance(encodedUnshadowed);
        float visible = decodeEstimatorLuminance(encodedVisible);
        if (!Float.isFinite(unshadowed) || !Float.isFinite(visible)) {
            estimatorMetadataInvalid++;
            return;
        }

        if (unshadowed <= ESTIMATOR_LIT_EPSILON) {
            estimatorDark++;
            if (visible > ESTIMATOR_LIT_EPSILON)
                estimatorImpossibleGain++;
            return;
        }

        estimatorLit++;
        boolean rejected = visible <= ESTIMATOR_LIT_EPSILON;
        if (rejected)
            estimatorRejected++;
        else
            estimatorVisible++;

        if (visible > unshadowed + Math.max(0.0001f, unshadowed * 0.001f))
            estimatorImpossibleGain++;

        int stratum = (int) Math.floor(metadata + 0.0001f);
        float encodedExpansion = metadata - stratum;
        if (stratum <= 0
                || stratum >= ESTIMATOR_STRATUM_COUNT
                || encodedExpansion < -0.001f
                || encodedExpansion >= 1.0f) {
            estimatorMetadataInvalid++;
            return;
        }

        float proposalExpansion = (float) Math.pow(
                2.0,
                Math.max(0.0f, encodedExpansion) * 16.0f
        ) - 1.0f;
        if (!Float.isFinite(proposalExpansion) || proposalExpansion <= 0.0f) {
            estimatorMetadataInvalid++;
            return;
        }

        estimatorSelectedByStratum[stratum]++;
        if (rejected)
            estimatorRejectedByStratum[stratum]++;
        minimumProposalExpansion = Math.min(minimumProposalExpansion, proposalExpansion);
        maximumProposalExpansion = Math.max(maximumProposalExpansion, proposalExpansion);
    }

    private void logStatisticsIfReady() {
        long now = System.nanoTime();
        if (samples < MIN_LOG_SAMPLES || now - lastLogNanos < LOG_INTERVAL_NANOS) return;

        float minimumSamples = minimumTotalSamples == Float.POSITIVE_INFINITY ? 0.0f : minimumTotalSamples;
        Photonics.LOGGER.info(
                "Photonics direct reservoir sample v64: samples={}, populated={}, emptyOrBackground={}, visibilityRejected={}, invalid={}, overLegacyCap={}, overTemporalCap={}, totalSamplesRange={}..{}, viewport={}x{}",
                samples,
                populated,
                empty,
                visibilityRejected,
                invalid,
                overLegacyCap,
                overTemporalCap,
                formatFloat(minimumSamples),
                formatFloat(maximumTotalSamples),
                sampledWidth,
                sampledHeight
        );

        if (estimatorSamples > 0) {
            float minimumExpansion = minimumProposalExpansion == Float.POSITIVE_INFINITY
                    ? 0.0f
                    : minimumProposalExpansion;
            Photonics.LOGGER.info(
                    "Photonics direct estimator sample v107: samples={}, lit={}, darkOrBackground={}, visible={}, finalRejected={}, impossibleGain={}, metadataInvalid={}, selectedByStratum={}, rejectedByStratum={}, proposalExpansionRange={}..{}",
                    estimatorSamples,
                    estimatorLit,
                    estimatorDark,
                    estimatorVisible,
                    estimatorRejected,
                    estimatorImpossibleGain,
                    estimatorMetadataInvalid,
                    Arrays.toString(estimatorSelectedByStratum),
                    Arrays.toString(estimatorRejectedByStratum),
                    formatFloat(minimumExpansion),
                    formatFloat(maximumProposalExpansion)
            );
        }

        resetStatistics();
        lastLogNanos = now;
    }

    private void resetStatistics() {
        samples = 0;
        populated = 0;
        empty = 0;
        visibilityRejected = 0;
        invalid = 0;
        overLegacyCap = 0;
        overTemporalCap = 0;
        minimumTotalSamples = Float.POSITIVE_INFINITY;
        maximumTotalSamples = 0.0f;
        estimatorSamples = 0;
        estimatorLit = 0;
        estimatorDark = 0;
        estimatorVisible = 0;
        estimatorRejected = 0;
        estimatorImpossibleGain = 0;
        estimatorMetadataInvalid = 0;
        Arrays.fill(estimatorSelectedByStratum, 0);
        Arrays.fill(estimatorRejectedByStratum, 0);
        minimumProposalExpansion = Float.POSITIVE_INFINITY;
        maximumProposalExpansion = 0.0f;
        lastLogNanos = System.nanoTime();
    }

    private static String formatFloat(float value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static float decodeEstimatorLuminance(float encoded) {
        return (float) Math.pow(2.0, Math.min(encoded, 16.0f)) - 1.0f;
    }

    @Override
    public void close() {
        if (!initialized) return;

        for (var slot : slots) {
            if (slot.fence != 0L) {
                GL32.glDeleteSync(slot.fence);
                slot.fence = 0L;
            }
            if (slot.pbo != 0) {
                GL15.glDeleteBuffers(slot.pbo);
                slot.pbo = 0;
            }
        }

        initialized = false;
    }

    private static final class Slot {
        private int pbo = 0;
        private long fence = 0L;
        private boolean hasEstimatorSample = false;
    }
}
