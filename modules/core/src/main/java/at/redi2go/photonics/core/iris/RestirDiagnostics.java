package at.redi2go.photonics.core.iris;

public final class RestirDiagnostics {
    public static final String SOURCE_HISTORY_PROPERTY = "photonics.restirSourceHistoryDiagnostic";

    private static final boolean SOURCE_HISTORY_ENABLED = Boolean.getBoolean(SOURCE_HISTORY_PROPERTY);

    private RestirDiagnostics() {
    }

    public static boolean isSourceHistoryEnabled() {
        return SOURCE_HISTORY_ENABLED;
    }
}
