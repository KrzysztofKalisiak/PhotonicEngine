package at.redi2go.photonics.core.iris;

public final class RestirDiagnostics {
    public static final String SOURCE_HISTORY_PROPERTY = "photonics.restirSourceHistoryDiagnostic";
    public static final String DIRECT_TEMPORAL_BYPASS_PROPERTY = "photonics.restirDirectTemporalBypassDiagnostic";
    public static final String DIRECT_ESTIMATOR_PROPERTY = "photonics.restirDirectEstimatorDiagnostic";
    public static final String DIRECT_ESTIMATOR_RANK_PROPERTY = "photonics.restirDirectEstimatorRankDiagnostic";
    public static final String DIRECT_VISIBILITY_LANES_PROPERTY = "photonics.restirDirectVisibilityLanesOverride";
    public static final String GI_TRANSPORT_LANES_PROPERTY = "photonics.restirGiTransportDiagnostic";
    public static final String GI_SUN_PROPOSAL_PROPERTY = "photonics.restirGiSunProposalDiagnostic";
    public static final String HISTORY_SPLIT_SCREEN_PROPERTY = "photonics.restirHistorySplitDiagnostic";

    private static final boolean SOURCE_HISTORY_ENABLED = Boolean.getBoolean(SOURCE_HISTORY_PROPERTY);
    private static final boolean DIRECT_TEMPORAL_BYPASS_ENABLED = Boolean.getBoolean(DIRECT_TEMPORAL_BYPASS_PROPERTY);
    private static final boolean DIRECT_ESTIMATOR_ENABLED = Boolean.getBoolean(DIRECT_ESTIMATOR_PROPERTY);
    private static final boolean DIRECT_ESTIMATOR_RANK_ENABLED = Boolean.getBoolean(DIRECT_ESTIMATOR_RANK_PROPERTY);
    private static final boolean GI_TRANSPORT_LANES_ENABLED = Boolean.getBoolean(GI_TRANSPORT_LANES_PROPERTY);
    private static final boolean GI_SUN_PROPOSAL_ENABLED = Boolean.getBoolean(GI_SUN_PROPOSAL_PROPERTY);
    private static final boolean HISTORY_SPLIT_SCREEN_ENABLED = Boolean.getBoolean(HISTORY_SPLIT_SCREEN_PROPERTY);
    private static final int REQUESTED_DIRECT_VISIBILITY_LANES = Integer.getInteger(
            DIRECT_VISIBILITY_LANES_PROPERTY,
            1
    );
    private static final int DIRECT_VISIBILITY_LANES = Math.max(
            1,
            Math.min(REQUESTED_DIRECT_VISIBILITY_LANES, 2)
    );

    private RestirDiagnostics() {
    }

    public static boolean isSourceHistoryEnabled() {
        return SOURCE_HISTORY_ENABLED;
    }

    public static boolean isDirectTemporalBypassEnabled() {
        return DIRECT_TEMPORAL_BYPASS_ENABLED;
    }

    public static boolean isDirectEstimatorEnabled() {
        return DIRECT_ESTIMATOR_ENABLED;
    }

    public static boolean isDirectEstimatorRankEnabled() {
        return DIRECT_ESTIMATOR_RANK_ENABLED;
    }

    public static int getRequestedDirectVisibilityLanes() {
        return REQUESTED_DIRECT_VISIBILITY_LANES;
    }

    public static int getDirectVisibilityLanes() {
        return DIRECT_VISIBILITY_LANES;
    }

    public static boolean isGiTransportLanesEnabled() {
        return GI_TRANSPORT_LANES_ENABLED;
    }

    public static boolean isGiSunProposalEnabled() {
        return GI_SUN_PROPOSAL_ENABLED;
    }

    public static boolean isHistorySplitScreenEnabled() {
        return HISTORY_SPLIT_SCREEN_ENABLED;
    }
}
