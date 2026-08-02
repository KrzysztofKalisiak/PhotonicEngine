package at.redi2go.photonics.core.iris;

public final class RestirDiagnostics {
    public static final String SOURCE_HISTORY_PROPERTY = "photonics.restirSourceHistoryDiagnostic";
    public static final String DIRECT_TEMPORAL_BYPASS_PROPERTY = "photonics.restirDirectTemporalBypassDiagnostic";

    private static final boolean SOURCE_HISTORY_ENABLED = Boolean.getBoolean(SOURCE_HISTORY_PROPERTY);
    private static final boolean DIRECT_TEMPORAL_BYPASS_ENABLED = Boolean.getBoolean(DIRECT_TEMPORAL_BYPASS_PROPERTY);

    private RestirDiagnostics() {
    }

    public static boolean isSourceHistoryEnabled() {
        return SOURCE_HISTORY_ENABLED;
    }

    public static boolean isDirectTemporalBypassEnabled() {
        return DIRECT_TEMPORAL_BYPASS_ENABLED;
    }
}
