package at.redi2go.photonics.core.iris;

public final class TemporalUpscalerDiagnostics {
    public static final String SPLIT_SCREEN_PROPERTY = "photonics.temporalUpscalerSplitScreen";
    public static final String SOURCE_VALIDATION_LANES_PROPERTY
            = "photonics.temporalUpscalerSourceValidationLanes";

    private static final boolean SPLIT_SCREEN_ENABLED = Boolean.getBoolean(SPLIT_SCREEN_PROPERTY);
    private static final boolean SOURCE_VALIDATION_LANES_ENABLED
            = Boolean.getBoolean(SOURCE_VALIDATION_LANES_PROPERTY);

    private TemporalUpscalerDiagnostics() {
    }

    public static boolean isSplitScreenEnabled() {
        return SPLIT_SCREEN_ENABLED && !SOURCE_VALIDATION_LANES_ENABLED;
    }

    public static boolean isSourceValidationLanesEnabled() {
        return SOURCE_VALIDATION_LANES_ENABLED;
    }
}
