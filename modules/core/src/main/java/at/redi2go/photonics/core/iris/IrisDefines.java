package at.redi2go.photonics.core.iris;

import at.redi2go.photonics.api.shaders.LightingMode;
import at.redi2go.photonics.api.shaders.PhotonicsProperties;
import at.redi2go.photonics.core.Photonics;
import at.redi2go.photonics.core.iris.pipeline.DefineHolder;

public class IrisDefines {
    public static void registerVersionDefines(DefineHolder defines) {
        defines.stringDefine("PHOTONICS", "");
        defines.stringDefine("PHOTONICS_VERSION", Photonics.getVersionString());
    }

    public static void registerDefines(DefineHolder defines, PhotonicsProperties phProperties) {
        defines.floatDefine("PH_RENDER_SCALE", phProperties.getRenderScale());
        defines.floatDefine("PH_GI_RENDER_SCALE", phProperties.getGiRenderScale());
        defines.floatDefine("PH_SHADERPACK_RENDER_SCALE", phProperties.getShaderPackRenderScale());
        defines.intDefine(
                "PH_TEMPORAL_UPSCALER_HISTORY_FRAMES",
                phProperties.getTemporalUpscalerHistoryFrames()
        );
        defines.intDefine("PH_MAX_LIGHTS", phProperties.getMaxLights());
        defines.intDefine("PH_MAX_GI_BOUNCES", phProperties.getMaxGiBounces());

        switch (phProperties.getAlphaMode()) {
            case BLOCK -> defines.stringDefine("PH_USE_TRANSPARENCY", "");
            case VOXEL -> {
                defines.stringDefine("PH_USE_TRANSPARENCY", "");
                defines.stringDefine("PH_FULL_TRANSPARENCY", "");
            }
        }

        if (phProperties.isBlockLightEnabled())
            defines.stringDefine("PH_ENABLE_BLOCKLIGHT", "");

        if (phProperties.isGiEnabled())
            defines.stringDefine("PH_ENABLE_GI", "");

        if (phProperties.isBlockLightGiEnabled())
            defines.stringDefine("PH_ENABLE_BLOCKLIGHT_GI", "");

        if (phProperties.isHandheldLightEnabled())
            defines.stringDefine("PH_ENABLE_HANDHELD_LIGHT", "");

        if (phProperties.isTemporalUpscalerActive())
            defines.stringDefine("PH_TEMPORAL_UPSCALER", "");

        if (phProperties.isTemporalUpscalerActive()
                && TemporalUpscalerDiagnostics.isSplitScreenEnabled())
            defines.stringDefine("PH_TEMPORAL_UPSCALER_SPLIT_SCREEN", "");

        if (phProperties.isTemporalUpscalerActive()
                && TemporalUpscalerDiagnostics.isSourceValidationLanesEnabled())
            defines.stringDefine("PH_TEMPORAL_UPSCALER_SOURCE_VALIDATION_LANES", "");

        if (phProperties.isLightBinningEnabled() || phProperties.getLightingMode() == LightingMode.BASIC)
            defines.stringDefine("PH_ENABLE_LIGHT_BINNING", "");

        if (phProperties.useSeparateHandheldRays())
            defines.stringDefine("PH_SEPARATE_HANDHELD_RAYS", "");

        defines.enumDefine("PH_LIGHTING_MODE", phProperties.getLightingMode());

        defines.intDefine("PH_RESTIR_INITIAL_SAMPLES", phProperties.getRestirInitialSamples());
        defines.intDefine("PH_RESTIR_SPATIAL_REUSE_SAMPLES", phProperties.getRestirSpatialReuseSamples());
        defines.floatDefine("PH_RESTIR_SPATIAL_REUSE_RADIUS", phProperties.getRestirSpatialReuseRadius());
        defines.intDefine("PH_RESTIR_ACCUMULATION_FRAMES", phProperties.getRestirAccumulationFrames());
        defines.intDefine("PH_RESTIR_DENOISER_PASSES", phProperties.getRestirDenoiserPasses());
        defines.intDefine("PH_RESTIR_GI_DENOISER_PASSES", phProperties.getRestirGiDenoiserPasses());

        if (phProperties.useRestirSoftShadows())
            defines.stringDefine("PH_RESTIR_SOFT_SHADOWS", "");

        if (phProperties.getLightingMode() == LightingMode.RESTIR && phProperties.useRestirCombinedGi())
            defines.stringDefine("PH_RESTIR_COMBINED_GI", "");

        if (phProperties.getLightingMode() == LightingMode.RESTIR
                && phProperties.isGiEnabled()
                && phProperties.useRestirCombinedGi()
                && RestirDiagnostics.isGiTransportLanesEnabled())
            defines.stringDefine("PH_RESTIR_GI_TRANSPORT_LANES", "");

        if (phProperties.getLightingMode() == LightingMode.RESTIR
                && phProperties.isGiEnabled()
                && phProperties.useRestirCombinedGi()
                && RestirDiagnostics.isGiSunProposalEnabled())
            defines.stringDefine("PH_RESTIR_GI_SUN_PROPOSAL", "");

        if (phProperties.getLightingMode() == LightingMode.RESTIR
                && phProperties.isGiEnabled()
                && phProperties.useRestirCombinedGi()
                && phProperties.getGiRenderScale() >= phProperties.getRenderScale() - 0.0001f
                && RestirDiagnostics.isGiValidityChannelsEnabled())
            defines.stringDefine("PH_RESTIR_GI_VALIDITY_CHANNELS_DIAGNOSTIC", "");

        if (phProperties.getLightingMode() == LightingMode.RESTIR
                && phProperties.isGiEnabled()
                && phProperties.useRestirCombinedGi()
                && phProperties.getGiRenderScale() >= phProperties.getRenderScale() - 0.0001f
                && RestirDiagnostics.isGiValidityEnabled())
            defines.stringDefine("PH_RESTIR_GI_VALIDITY_DIAGNOSTIC", "");

        if (phProperties.getLightingMode() == LightingMode.RESTIR
                && phProperties.isGiEnabled()
                && phProperties.useRestirCombinedGi()
                && RestirDiagnostics.isHistorySplitScreenEnabled())
            defines.stringDefine("PH_RESTIR_HISTORY_SPLIT_SCREEN", "");

        if (RestirDiagnostics.getHistorySplitMode() == 1
                || RestirDiagnostics.getHistorySplitMode() == 3)
            defines.stringDefine("PH_RESTIR_HISTORY_SPLIT_RADIANCE", "");

        if (RestirDiagnostics.getHistorySplitMode() == 2
                || RestirDiagnostics.getHistorySplitMode() == 3)
            defines.stringDefine("PH_RESTIR_HISTORY_SPLIT_RESERVOIR", "");

        if (phProperties.getLightingMode() == LightingMode.RESTIR
                && phProperties.useRestirCombinedGi()
                && phProperties.getGiRenderScale() < phProperties.getRenderScale() - 0.0001f)
            defines.stringDefine("PH_RESTIR_SPLIT_GI", "");

        boolean sourceHistoryDiagnostic = phProperties.getLightingMode() == LightingMode.RESTIR
                && phProperties.isBlockLightEnabled()
                && phProperties.isGiEnabled()
                && phProperties.useRestirCombinedGi()
                && phProperties.getGiRenderScale() < phProperties.getRenderScale() - 0.0001f
                && RestirDiagnostics.isSourceHistoryEnabled();
        if (sourceHistoryDiagnostic)
            defines.stringDefine("PH_RESTIR_SOURCE_HISTORY_DIAGNOSTIC", "");
        boolean directEstimatorDiagnostic = sourceHistoryDiagnostic
                && RestirDiagnostics.isDirectEstimatorEnabled();
        if (directEstimatorDiagnostic)
            defines.stringDefine("PH_RESTIR_DIRECT_ESTIMATOR_DIAGNOSTIC", "");
        if (directEstimatorDiagnostic && RestirDiagnostics.isDirectEstimatorRankEnabled())
            defines.stringDefine("PH_RESTIR_DIRECT_ESTIMATOR_RANK_DIAGNOSTIC", "");

        defines.intDefine(
                "PH_RESTIR_DIRECT_VISIBILITY_LANES",
                directEstimatorDiagnostic ? 1 : RestirDiagnostics.getDirectVisibilityLanes()
        );

        defines.intDefine("PH_MAX_SAMPLES",  phProperties.getMaxSamples());
    }
}
