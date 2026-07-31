package at.redi2go.photonics.core.iris;

public final class TemporalUpscalerDiagnostics {
    public static final String SPLIT_SCREEN_PROPERTY = "photonics.temporalUpscalerSplitScreen";

    private static final boolean SPLIT_SCREEN_ENABLED = Boolean.getBoolean(SPLIT_SCREEN_PROPERTY);

    private TemporalUpscalerDiagnostics() {
    }

    public static boolean isSplitScreenEnabled() {
        return SPLIT_SCREEN_ENABLED;
    }
}
