package at.redi2go.photonics.core.iris.patching;

import at.redi2go.photonics.api.shaders.IPackPath;
import at.redi2go.photonics.core.Photonics;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class PhotonShaderCompatibility {
    private static final String DIFFUSE_LIGHTING_PATH = "/include/lighting/diffuse_lighting.glsl";
    private static final String SUPPORTED_DIFFUSE_LIGHTING_SHA256 =
            "dec317d851dfa22d3d44275efd0817fbae25291f78ef412295df8f380fd75eed";

    private static final String CAVE_LIGHTING = """
                lighting += 0.15 * CAVE_LIGHTING_I * directional_lighting * ao
                    * (1.0 - light_levels.y * light_levels.y)
                    * (1.0 - 0.7 * darknessFactor);
            """;
    private static final String PHOTONICS_AWARE_CAVE_LIGHTING = """
            #if defined PHOTONICS_DIFFUSE
                if (is_lod) {
            #endif
                lighting += 0.15 * CAVE_LIGHTING_I * directional_lighting * ao
                    * (1.0 - light_levels.y * light_levels.y)
                    * (1.0 - 0.7 * darknessFactor);
            #if defined PHOTONICS_DIFFUSE
                }
            #endif
            """;

    private static final Set<String> LOGGED_RESULTS = ConcurrentHashMap.newKeySet();

    private PhotonShaderCompatibility() {
    }

    static @Nullable String apply(
            IPackPath path,
            @Nullable String source,
            boolean photonicsEnabled
    ) {
        if (!photonicsEnabled
                || source == null
                || !DIFFUSE_LIGHTING_PATH.equals(path.ph$pathString()))
            return source;

        String normalizedSource = normalizeNewlines(source);
        String sourceHash = sha256(normalizedSource);
        if (!SUPPORTED_DIFFUSE_LIGHTING_SHA256.equals(sourceHash)) {
            logOnce(
                    "unsupported:" + sourceHash,
                    "Photon compatibility v117 skipped for {}: unsupported sha256={}",
                    path.ph$pathString(),
                    sourceHash
            );
            return source;
        }

        int firstMatch = normalizedSource.indexOf(CAVE_LIGHTING);
        if (firstMatch < 0
                || normalizedSource.indexOf(CAVE_LIGHTING, firstMatch + CAVE_LIGHTING.length()) >= 0) {
            logOnce(
                    "ambiguous:" + sourceHash,
                    "Photon compatibility v117 skipped for {}: cave-lighting block was not unique",
                    path.ph$pathString()
            );
            return source;
        }

        String patchedSource = normalizedSource.substring(0, firstMatch)
                + PHOTONICS_AWARE_CAVE_LIGHTING
                + normalizedSource.substring(firstMatch + CAVE_LIGHTING.length());
        logOnce(
                "applied:" + sourceHash,
                "Photon compatibility v117 applied to {}: native Photonics receivers use traced GI; Photon cave lighting remains enabled for DH receivers",
                path.ph$pathString()
        );
        return patchedSource;
    }

    private static String normalizeNewlines(String source) {
        return source.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String sha256(String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is unavailable", e);
        }
    }

    private static void logOnce(String key, String message, Object... parameters) {
        if (LOGGED_RESULTS.add(key))
            Photonics.LOGGER.info(message, parameters);
    }
}
