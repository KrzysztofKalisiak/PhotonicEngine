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
    static final String SHADOW_DIAGNOSTIC_PROPERTY = "photonics.photonDhShadowDiagnostic";
    static final String DISABLE_DH_CAVE_FALLBACK_PROPERTY = "photonics.photonDisableDhCaveFallback";

    private static final String DIFFUSE_LIGHTING_PATH = "/include/lighting/diffuse_lighting.glsl";
    private static final String DEFERRED_SHADING_PATH = "/program/d4_deferred_shading.fsh";
    private static final String TAA_EXPOSURE_PATH = "/program/c4_taa_exposure.fsh";
    private static final String SUPPORTED_DIFFUSE_LIGHTING_SHA256 =
            "dec317d851dfa22d3d44275efd0817fbae25291f78ef412295df8f380fd75eed";
    private static final String SUPPORTED_DEFERRED_SHADING_SHA256 =
            "c26f1908fc08a556127af5e6fb888415957dee562ea03c579e14db4535c1fd74";
    private static final String SUPPORTED_TAA_EXPOSURE_SHA256 =
            "549a1057782288908644c400ac3e3c4d22737fd2685f2d5ac9f62d6214b1f188";
    private static final boolean SHADOW_DIAGNOSTIC_ENABLED = Boolean.getBoolean(
            SHADOW_DIAGNOSTIC_PROPERTY
    );
    private static final boolean DISABLE_DH_CAVE_FALLBACK = Boolean.getBoolean(
            DISABLE_DH_CAVE_FALLBACK_PROPERTY
    );

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
    private static final String PHOTONICS_WITHOUT_DH_CAVE_LIGHTING = """
            #if !defined PHOTONICS_DIFFUSE
                lighting += 0.15 * CAVE_LIGHTING_I * directional_lighting * ao
                    * (1.0 - light_levels.y * light_levels.y)
                    * (1.0 - 0.7 * darknessFactor);
            #endif
            """;
    private static final String DEFERRED_OUTPUT_TAIL = """
                    fragment_color = purkinje_shift(fragment_color, light_levels);
                }
            }
            """;
    private static final String SHADOW_DIAGNOSTIC_OUTPUT_TAIL = """
                    fragment_color = purkinje_shift(fragment_color, light_levels);

            #if defined PHOTONICS_DIFFUSE
                // Top-left remains normal. The other quadrants expose the inputs
                // that decide Photon/DH directional-shadow ownership.
                    bool shadow_was_evaluated
                        = NoL > 1e-3 || material.sss_amount > 1e-3;
                    if (uv.x >= 0.5 && uv.y >= 0.5) {
                        fragment_color = is_lod
                            ? vec3(1.0, 0.0, 1.0)
                            : vec3(0.0, 1.0, 0.0);
                    } else if (uv.x < 0.5 && uv.y < 0.5) {
                        fragment_color = shadow_was_evaluated
                            ? clamp(shadows, vec3(0.0), vec3(1.0))
                            : vec3(0.0, 0.0, 1.0);
                    } else if (uv.x >= 0.5 && uv.y < 0.5) {
                        fragment_color = shadow_was_evaluated
                            ? vec3(clamp(shadow_distance_fade, 0.0, 1.0))
                            : vec3(0.0, 0.0, 1.0);
                    }
            #endif

                }
            }
            """;
    private static final String TAA_HISTORY_BLEND = """
                current_color = mix(history_color, current_color, alpha);
            """;
    private static final String DIAGNOSTIC_CURRENT_FRAME_BLEND = """
                // Screen-space diagnostic lanes must not enter temporal history.
                current_color = mix(history_color, current_color, 1.0);
            """;

    private static final Set<String> LOGGED_RESULTS = ConcurrentHashMap.newKeySet();

    private PhotonShaderCompatibility() {
    }

    static @Nullable String apply(
            IPackPath path,
            @Nullable String source,
            boolean nativePhotonicsPack
    ) {
        String normalizedPath = path.ph$pathString().replace('\\', '/');
        if (!nativePhotonicsPack || source == null) return source;

        boolean patchDiffuse = normalizedPath.endsWith(DIFFUSE_LIGHTING_PATH);
        boolean patchDeferred = SHADOW_DIAGNOSTIC_ENABLED
                && normalizedPath.endsWith(DEFERRED_SHADING_PATH);
        boolean patchTaa = SHADOW_DIAGNOSTIC_ENABLED
                && normalizedPath.endsWith(TAA_EXPOSURE_PATH);
        if (!patchDiffuse && !patchDeferred && !patchTaa) return source;

        String normalizedSource = normalizeNewlines(source);
        String sourceHash = sha256(normalizedSource);

        if (patchDiffuse)
            return patchDiffuseLighting(normalizedPath, source, normalizedSource, sourceHash);

        if (patchDeferred)
            return patchDeferredDiagnostic(normalizedPath, source, normalizedSource, sourceHash);

        if (patchTaa)
            return patchDiagnosticTaa(normalizedPath, source, normalizedSource, sourceHash);

        return source;
    }

    private static String patchDiffuseLighting(
            String normalizedPath,
            String originalSource,
            String normalizedSource,
            String sourceHash
    ) {
        if (!SUPPORTED_DIFFUSE_LIGHTING_SHA256.equals(sourceHash)) {
            logOnce(
                    "diffuse-unsupported:" + sourceHash,
                    "Photon compatibility v119 skipped for {}: unsupported sha256={}",
                    normalizedPath,
                    sourceHash
            );
            return originalSource;
        }

        int firstMatch = normalizedSource.indexOf(CAVE_LIGHTING);
        if (firstMatch < 0
                || normalizedSource.indexOf(CAVE_LIGHTING, firstMatch + CAVE_LIGHTING.length()) >= 0) {
            logOnce(
                    "diffuse-ambiguous:" + sourceHash,
                    "Photon compatibility v119 skipped for {}: cave-lighting block was not unique",
                    normalizedPath
            );
            return originalSource;
        }

        String caveLighting = DISABLE_DH_CAVE_FALLBACK
                ? PHOTONICS_WITHOUT_DH_CAVE_LIGHTING
                : PHOTONICS_AWARE_CAVE_LIGHTING;
        String patchedSource = normalizedSource.substring(0, firstMatch)
                + caveLighting
                + normalizedSource.substring(firstMatch + CAVE_LIGHTING.length());
        logOnce(
                "diffuse-applied:" + sourceHash + ":disableDhCave=" + DISABLE_DH_CAVE_FALLBACK,
                "Photon compatibility v119 applied to {}: sourceSha256={}, patchedSha256={}, "
                        + "disableDhCaveFallback={}; native Photonics receivers use traced GI "
                        + "and DH cave fallback defaults to enabled",
                normalizedPath,
                sourceHash,
                sha256(patchedSource),
                DISABLE_DH_CAVE_FALLBACK
        );
        return patchedSource;
    }

    private static String patchDeferredDiagnostic(
            String normalizedPath,
            String originalSource,
            String normalizedSource,
            String sourceHash
    ) {
        if (!SUPPORTED_DEFERRED_SHADING_SHA256.equals(sourceHash)) {
            logOnce(
                    "deferred-unsupported:" + sourceHash,
                    "Photon shadow diagnostic v119 skipped for {}: unsupported sha256={}",
                    normalizedPath,
                    sourceHash
            );
            return originalSource;
        }

        int firstMatch = normalizedSource.indexOf(DEFERRED_OUTPUT_TAIL);
        if (firstMatch < 0
                || normalizedSource.indexOf(
                        DEFERRED_OUTPUT_TAIL,
                        firstMatch + DEFERRED_OUTPUT_TAIL.length()
                ) >= 0) {
            logOnce(
                    "deferred-ambiguous:" + sourceHash,
                    "Photon shadow diagnostic v119 skipped for {}: output tail was not unique",
                    normalizedPath
            );
            return originalSource;
        }

        String patchedSource = normalizedSource.substring(0, firstMatch)
                + SHADOW_DIAGNOSTIC_OUTPUT_TAIL
                + normalizedSource.substring(firstMatch + DEFERRED_OUTPUT_TAIL.length());
        logOnce(
                "deferred-applied:" + sourceHash,
                "Photon shadow diagnostic v119 applied to {}: sourceSha256={}, patchedSha256={}; "
                        + "quadrants=normal/native-green-lod-magenta/"
                        + "evaluated-shadow-blue-backface/shadow-distance-fade",
                normalizedPath,
                sourceHash,
                sha256(patchedSource)
        );
        return patchedSource;
    }

    private static String patchDiagnosticTaa(
            String normalizedPath,
            String originalSource,
            String normalizedSource,
            String sourceHash
    ) {
        if (!SUPPORTED_TAA_EXPOSURE_SHA256.equals(sourceHash)) {
            logOnce(
                    "taa-unsupported:" + sourceHash,
                    "Photon shadow diagnostic v119 skipped TAA bypass for {}: unsupported sha256={}",
                    normalizedPath,
                    sourceHash
            );
            return originalSource;
        }

        int firstMatch = normalizedSource.indexOf(TAA_HISTORY_BLEND);
        if (firstMatch < 0
                || normalizedSource.indexOf(
                        TAA_HISTORY_BLEND,
                        firstMatch + TAA_HISTORY_BLEND.length()
                ) >= 0) {
            logOnce(
                    "taa-ambiguous:" + sourceHash,
                    "Photon shadow diagnostic v119 skipped TAA bypass for {}: history blend was not unique",
                    normalizedPath
            );
            return originalSource;
        }

        String patchedSource = normalizedSource.substring(0, firstMatch)
                + DIAGNOSTIC_CURRENT_FRAME_BLEND
                + normalizedSource.substring(firstMatch + TAA_HISTORY_BLEND.length());
        logOnce(
                "taa-applied:" + sourceHash,
                "Photon shadow diagnostic v119 disabled Photon TAA history for {}: "
                        + "sourceSha256={}, patchedSha256={}",
                normalizedPath,
                sourceHash,
                sha256(patchedSource)
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
