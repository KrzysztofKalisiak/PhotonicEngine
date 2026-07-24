package at.redi2go.photonics.common;

import at.redi2go.photonics.api.shaders.AlphaMode;
import at.redi2go.photonics.api.shaders.LightingMode;
import at.redi2go.photonics.api.shaders.PhotonicsProperties;
import at.redi2go.photonics.core.Photonics;

public class PhotonicsPropertiesImpl implements PhotonicsProperties {
    public static final String RENDER_SCALE_OVERRIDE_PROPERTY = "photonics.renderScaleOverride";
    public static final String GI_RENDER_SCALE_OVERRIDE_PROPERTY = "photonics.giRenderScaleOverride";
    public static final String TEMPORAL_UPSCALER_OVERRIDE_PROPERTY = "photonics.temporalUpscalerOverride";
    public static final String TEMPORAL_UPSCALER_SOURCE_SCALE_OVERRIDE_PROPERTY =
            "photonics.temporalUpscalerSourceScaleOverride";
    public static final String TEMPORAL_UPSCALER_HISTORY_FRAMES_OVERRIDE_PROPERTY =
            "photonics.temporalUpscalerHistoryFramesOverride";
    public static final String RESTIR_INITIAL_SAMPLES_OVERRIDE_PROPERTY = "photonics.restirInitialSamplesOverride";
    public static final String RESTIR_DENOISER_PASSES_OVERRIDE_PROPERTY = "photonics.restirDenoiserPassesOverride";
    public static final String RESTIR_GI_DENOISER_PASSES_OVERRIDE_PROPERTY = "photonics.restirGiDenoiserPassesOverride";

    private static final boolean DISABLE_ACTIVE_PIPELINE_FOR_DIAGNOSTICS = false;
    private static final float MIN_RENDER_SCALE_OVERRIDE = 0.25f;
    private static final float MAX_RENDER_SCALE_OVERRIDE = 1.0f;
    private static final int MIN_RESTIR_INITIAL_SAMPLES = 8;
    private static final int MAX_RESTIR_INITIAL_SAMPLES = 32;
    private static final int MIN_TEMPORAL_UPSCALER_HISTORY_FRAMES = 2;
    private static final int MAX_TEMPORAL_UPSCALER_HISTORY_FRAMES = 32;
    private static final int MAX_RESTIR_SPATIAL_REUSE_SAMPLES = 1;
    private static final float MAX_RESTIR_SPATIAL_REUSE_RADIUS = 5.0f;
    private static final int MIN_RESTIR_DENOISER_PASSES = 5;
    private static final int MAX_RESTIR_DENOISER_PASSES_OVERRIDE = 12;

    private final Float renderScaleOverride = readFloatOverride(
            RENDER_SCALE_OVERRIDE_PROPERTY,
            MIN_RENDER_SCALE_OVERRIDE,
            MAX_RENDER_SCALE_OVERRIDE
    );
    private final Float giRenderScaleOverride = readFloatOverride(
            GI_RENDER_SCALE_OVERRIDE_PROPERTY,
            MIN_RENDER_SCALE_OVERRIDE,
            MAX_RENDER_SCALE_OVERRIDE
    );
    private final Boolean temporalUpscalerOverride = readBooleanOverride(
            TEMPORAL_UPSCALER_OVERRIDE_PROPERTY
    );
    private final Float temporalUpscalerSourceScaleOverride = readFloatOverride(
            TEMPORAL_UPSCALER_SOURCE_SCALE_OVERRIDE_PROPERTY,
            MIN_RENDER_SCALE_OVERRIDE,
            MAX_RENDER_SCALE_OVERRIDE
    );
    private final Integer temporalUpscalerHistoryFramesOverride = readIntOverride(
            TEMPORAL_UPSCALER_HISTORY_FRAMES_OVERRIDE_PROPERTY,
            MIN_TEMPORAL_UPSCALER_HISTORY_FRAMES,
            MAX_TEMPORAL_UPSCALER_HISTORY_FRAMES
    );
    private final Integer restirInitialSamplesOverride = readIntOverride(
            RESTIR_INITIAL_SAMPLES_OVERRIDE_PROPERTY,
            MIN_RESTIR_INITIAL_SAMPLES,
            MAX_RESTIR_INITIAL_SAMPLES
    );
    private final Integer restirDenoiserPassesOverride = readIntOverride(
            RESTIR_DENOISER_PASSES_OVERRIDE_PROPERTY,
            0,
            MAX_RESTIR_DENOISER_PASSES_OVERRIDE
    );
    private final Integer restirGiDenoiserPassesOverride = readIntOverride(
            RESTIR_GI_DENOISER_PASSES_OVERRIDE_PROPERTY,
            0,
            MAX_RESTIR_DENOISER_PASSES_OVERRIDE
    );

    public boolean enabled = PhotonicsProperties.DEFAULT_ENABLED;
    public float renderScale = PhotonicsProperties.DEFAULT_RENDER_SCALE;
    public float giRenderScale = PhotonicsProperties.DEFAULT_GI_RENDER_SCALE;
    public boolean temporalUpscaler = PhotonicsProperties.DEFAULT_USE_TEMPORAL_UPSCALER;
    public float temporalUpscalerSourceScale =
            PhotonicsProperties.DEFAULT_TEMPORAL_UPSCALER_SOURCE_SCALE;
    public int temporalUpscalerHistoryFrames =
            PhotonicsProperties.DEFAULT_TEMPORAL_UPSCALER_HISTORY_FRAMES;
    public int maxLights = PhotonicsProperties.DEFAULT_MAX_LIGHTS;
    public int maxGiBounces = PhotonicsProperties.DEFAULT_MAX_GI_BOUNCES;
    public AlphaMode alphaMode = PhotonicsProperties.DEFAULT_ALPHA_MODE;
    public float enchantmentGlintStrength = PhotonicsProperties.DEFAULT_ENCHANTMENT_GLINT_STRENGTH;
    public boolean useSeparateHandheldRays = PhotonicsProperties.DEFAULT_SEPARATE_HANDHELD_RAYS;
    public boolean blockLightEnabled = PhotonicsProperties.DEFAULT_IS_BLOCK_LIGHT_ENABLED;
    public boolean giEnabled = PhotonicsProperties.DEFAULT_IS_GI_ENABLED;
    public boolean blockLightGiEnabled = PhotonicsProperties.DEFAULT_IS_BLOCK_LIGHT_GI_ENABLED;
    public boolean handheldLightEnabled = PhotonicsProperties.DEFAULT_IS_HANDHELD_LIGHT_ENABLED;
    public boolean lightBinningEnabled = PhotonicsProperties.DEFAULT_IS_LIGHT_BINNING_ENABLED;
    public LightingMode lightingMode = PhotonicsProperties.DEFAULT_LIGHTING_MODE;
    public int restirInitialSamples = PhotonicsProperties.DEFAULT_RESTIR_INITIAL_SAMPLES;
    public int restirSpatialReuseSamples = PhotonicsProperties.DEFAULT_RESTIR_SPATIAL_REUSE_SAMPLES;
    public float restirRestirSpatialReuseRadius = PhotonicsProperties.DEFAULT_RESTIR_SPATIAL_REUSE_RADIUS;
    public int restirAccumulationFrames = PhotonicsProperties.DEFAULT_RESTIR_ACCUMULATION_FRAMES;
    public boolean restirSoftShadows = PhotonicsProperties.DEFAULT_USE_RESTIR_SOFT_SHADOWS;
    public boolean restirCombinedGi = PhotonicsProperties.DEFAULT_USE_RESTIR_COMBINED_GI;
    public int restirDenoiserPasses = PhotonicsProperties.DEFAULT_RESTIR_DENOISER_PASSES;
    public int restirGiDenoiserPasses = PhotonicsProperties.DEFAULT_RESTIR_GI_DENOISER_PASSES;
    public int maxSamples = PhotonicsProperties.DEFAULT_MAX_SAMPLES;

    public PhotonicsPropertiesImpl() {
        Photonics.LOGGER.info(
                "Photonics performance overrides: renderScale={}, giRenderScale={}, temporalUpscaler={}, temporalUpscalerSourceScale={}, temporalUpscalerHistoryFrames={}, restirInitialSamples={}, restirDenoiserPasses={}, restirGiDenoiserPasses={} (shader-pack means no JVM override)",
                overrideLabel(renderScaleOverride),
                overrideLabel(giRenderScaleOverride),
                overrideLabel(temporalUpscalerOverride),
                overrideLabel(temporalUpscalerSourceScaleOverride),
                overrideLabel(temporalUpscalerHistoryFramesOverride),
                overrideLabel(restirInitialSamplesOverride),
                overrideLabel(restirDenoiserPassesOverride),
                overrideLabel(restirGiDenoiserPassesOverride)
        );
    }

    @Override
    public boolean isPhotonicsEnabled() {
        return enabled;
    }

    @Override
    public float getRenderScale() {
        if (renderScaleOverride != null)
            return renderScaleOverride;
        if (!useTemporalUpscaler()
                || getLightingMode() != LightingMode.RESTIR
                || (!isBlockLightEnabled()
                    && !(isGiEnabled() && useRestirCombinedGi())))
            return renderScale;
        return Math.min(renderScale, getTemporalUpscalerSourceScale());
    }

    @Override
    public float getGiRenderScale() {
        float effectiveScale = giRenderScaleOverride != null
                ? giRenderScaleOverride
                : giRenderScale;
        return Math.min(
                getRenderScale(),
                Math.max(MIN_RENDER_SCALE_OVERRIDE, effectiveScale)
        );
    }

    @Override
    public float getShaderPackRenderScale() {
        return renderScale;
    }

    @Override
    public boolean useTemporalUpscaler() {
        return temporalUpscalerOverride != null
                ? temporalUpscalerOverride
                : temporalUpscaler;
    }

    @Override
    public float getTemporalUpscalerSourceScale() {
        float effectiveScale = temporalUpscalerSourceScaleOverride != null
                ? temporalUpscalerSourceScaleOverride
                : temporalUpscalerSourceScale;
        if (!Float.isFinite(effectiveScale))
            effectiveScale = PhotonicsProperties.DEFAULT_TEMPORAL_UPSCALER_SOURCE_SCALE;
        return Math.max(
                MIN_RENDER_SCALE_OVERRIDE,
                Math.min(effectiveScale, MAX_RENDER_SCALE_OVERRIDE)
        );
    }

    @Override
    public int getTemporalUpscalerHistoryFrames() {
        int effectiveFrames = temporalUpscalerHistoryFramesOverride != null
                ? temporalUpscalerHistoryFramesOverride
                : temporalUpscalerHistoryFrames;
        return Math.max(
                MIN_TEMPORAL_UPSCALER_HISTORY_FRAMES,
                Math.min(effectiveFrames, MAX_TEMPORAL_UPSCALER_HISTORY_FRAMES)
        );
    }

    @Override
    public int getMaxLights() {
        return maxLights;
    }

    @Override
    public int getMaxGiBounces() {
        return maxGiBounces;
    }

    @Override
    public AlphaMode getAlphaMode() {
        return alphaMode;
    }

    @Override
    public float getEnchantmentGlintStrength() {
        return enchantmentGlintStrength;
    }

    @Override
    public boolean useSeparateHandheldRays() {
        return useSeparateHandheldRays;
    }

    @Override
    public boolean isBlockLightEnabled() {
        return blockLightEnabled;
    }

    @Override
    public boolean isGiEnabled() {
        return giEnabled;
    }

    @Override
    public boolean isBlockLightGiEnabled() {
        return blockLightGiEnabled;
    }

    @Override
    public boolean isHandheldLightEnabled() {
        return handheldLightEnabled;
    }

    @Override
    public LightingMode getLightingMode() {
        if (DISABLE_ACTIVE_PIPELINE_FOR_DIAGNOSTICS) return LightingMode.OFF;
        if (lightingMode == LightingMode.OFF) return LightingMode.OFF;

        // BASIC has no active direct-light pipeline in this port. Force RESTIR so
        // stale Iris shader options cannot select the dead path.
        return LightingMode.RESTIR;
    }

    @Override
    public boolean isLightBinningEnabled() {
        return lightBinningEnabled;
    }

    @Override
    public int getMaxSamples() {
        return maxSamples;
    }

    @Override
    public int getRestirInitialSamples() {
        int effectiveSamples = restirInitialSamplesOverride != null
                ? restirInitialSamplesOverride
                : restirInitialSamples;
        return Math.max(
                MIN_RESTIR_INITIAL_SAMPLES,
                Math.min(effectiveSamples, MAX_RESTIR_INITIAL_SAMPLES)
        );
    }

    @Override
    public int getRestirSpatialReuseSamples() {
        return Math.max(
                0,
                Math.min(restirSpatialReuseSamples, MAX_RESTIR_SPATIAL_REUSE_SAMPLES)
        );
    }

    @Override
    public float getRestirSpatialReuseRadius() {
        return Math.max(
                0.0f,
                Math.min(restirRestirSpatialReuseRadius, MAX_RESTIR_SPATIAL_REUSE_RADIUS)
        );
    }

    @Override
    public int getRestirAccumulationFrames() {
        return restirAccumulationFrames;
    }

    @Override
    public boolean useRestirSoftShadows() {
        return false;
    }

    @Override
    public boolean useRestirCombinedGi() {
        return restirCombinedGi;
    }

    @Override
    public int getRestirDenoiserPasses() {
        if (restirDenoiserPassesOverride != null)
            return restirDenoiserPassesOverride;
        return Math.max(restirDenoiserPasses, MIN_RESTIR_DENOISER_PASSES);
    }

    @Override
    public int getRestirGiDenoiserPasses() {
        int effectivePasses = restirGiDenoiserPassesOverride != null
                ? restirGiDenoiserPassesOverride
                : restirGiDenoiserPasses;
        return Math.max(
                0,
                Math.min(effectivePasses, getRestirDenoiserPasses())
        );
    }

    private static Float readFloatOverride(String key, float minimum, float maximum) {
        String rawValue = System.getProperty(key);
        if (rawValue == null || rawValue.isBlank())
            return null;

        try {
            float value = Float.parseFloat(rawValue);
            if (Float.isFinite(value) && value >= minimum && value <= maximum)
                return value;
        } catch (NumberFormatException ignored) {
        }

        Photonics.LOGGER.warn(
                "Ignoring invalid JVM property {}={}; expected a finite value in [{}, {}]",
                key,
                rawValue,
                minimum,
                maximum
        );
        return null;
    }

    private static Integer readIntOverride(String key, int minimum, int maximum) {
        String rawValue = System.getProperty(key);
        if (rawValue == null || rawValue.isBlank())
            return null;

        try {
            int value = Integer.parseInt(rawValue);
            if (value >= minimum && value <= maximum)
                return value;
        } catch (NumberFormatException ignored) {
        }

        Photonics.LOGGER.warn(
                "Ignoring invalid JVM property {}={}; expected an integer in [{}, {}]",
                key,
                rawValue,
                minimum,
                maximum
        );
        return null;
    }

    private static Boolean readBooleanOverride(String key) {
        String rawValue = System.getProperty(key);
        if (rawValue == null || rawValue.isBlank())
            return null;

        if ("true".equalsIgnoreCase(rawValue) || "1".equals(rawValue))
            return true;
        if ("false".equalsIgnoreCase(rawValue) || "0".equals(rawValue))
            return false;

        Photonics.LOGGER.warn(
                "Ignoring invalid JVM property {}={}; expected true, false, 1, or 0",
                key,
                rawValue
        );
        return null;
    }

    private static String overrideLabel(Object value) {
        return value == null ? "shader-pack" : value.toString();
    }
}
