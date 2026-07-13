package at.redi2go.photonics.core.iris.patching;

import at.redi2go.photonics.api.ModLoader;
import at.redi2go.photonics.api.shaders.IPackPath;
import at.redi2go.photonics.api.shaders.IShaderPack;
import at.redi2go.photonics.core.Photonics;
import at.redi2go.photonics.core.iris.patching.sources.DevEnvSource;
import at.redi2go.photonics.core.iris.patching.sources.JarSource;
import at.redi2go.photonics.core.iris.patching.sources.ShaderPatchesSource;
import at.redi2go.photonics.core.util.Fs;
import com.google.common.collect.ImmutableList;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Stream;

public class ShaderPatcher {
    // TODO replace with comment at the top of the file
    private static final Set<String> AUTO_REPLACED_FILES = Set.of(
            "light.glsl",
            "light_list.glsl",
            "palette.glsl",
            "samplers.glsl",
            "tracing.glsl",
            "uniforms.glsl",

            // LEGACY FILE NAMES
            "photonics.glsl",
            "ph_samplers.glsl"
    );
    private static final Set<String> SHADER_PACK_INTERFACE_FILES = Set.of(
            "shader_interface.glsl"
    );
    private static final Path PATCHED_DEBUG_PATH = ModLoader.getGameDir().resolve(".ph-patched-shaders");
    private static final Path PHOTONICS_SHADERS_PATH = getPhotonicsShadersPath();
    private static final ConcurrentMap<String, String> LOGGED_SHADER_SOURCES = new ConcurrentHashMap<>();

    private final IShaderPack pack;
    private final @Nullable Patch patch;

    public ShaderPatcher(IShaderPack pack) {
        this.pack = pack;

        if (pack.supportsPhotonics()) {
            patch = null;
            return;
        }

        wipeDebug();
        patch = getPatchList().loadPatch(pack).orElse(null);
    }

    public boolean hasPatch() {
        return patch != null;
    }

    public List<IPackPath> getCreatedFiles() {
        List<IPackPath> createdFiles = new ArrayList<>();

        if (patch != null)
            createdFiles.addAll(patch.getFiles());

        Path includedShaders = PHOTONICS_SHADERS_PATH
                .resolve("photonics");

        try (Stream<Path> shaders = Files.walk(includedShaders)) {
            shaders.forEach(file -> {
                if (Files.isDirectory(file)) return;

                var relativePath = includedShaders.relativize(file);
                createdFiles.add(
                        IPackPath.fromAbsolutePath("/photonics")
                                .ph$resolve(relativePath.toString().replace('\\', '/'))
                );
            });
        } catch (IOException e) {
            Photonics.LOGGER.error("An error occurred loading Photonics's shaders", e);
        }

        return createdFiles;
    }

    private static Path getPhotonicsShadersPath() {
        if (Photonics.isDevEnvironment()) {
            return ModLoader.getGameDir().resolve("../../../../shaders")
                    .normalize();
        }

        return Photonics.getAssetsPath()
                .resolve("photonics")
                .resolve("shaders")
                .normalize();
    }

    public @Nullable String readPhotonicsFile(
            IPackPath packPath,
            Function<IPackPath, @Nullable String> shaderSourceSupplier
    ) {
        Path realPath = packPath.ph$resolved(PHOTONICS_SHADERS_PATH);
        Path relativePath = PHOTONICS_SHADERS_PATH.resolve("photonics")
                .relativize(realPath);
        String relativeName = relativePath.toString().replace('\\', '/');

        @Nullable String source = null;
        String sourceOrigin = "missing";

        readFile:
        {
            boolean bundledFileExists = Files.exists(realPath);
            boolean shaderPackOwnsFile = !bundledFileExists
                    || relativeName.startsWith("modifiers/")
                    || SHADER_PACK_INTERFACE_FILES.contains(relativeName);

            // Keep the render pipeline in lockstep with this build, while
            // preserving the pack-specific G-buffer interface and modifiers.
            if (patch == null && shaderPackOwnsFile && !AUTO_REPLACED_FILES.contains(relativeName)) {
                source = shaderSourceSupplier.apply(packPath);
                if (source != null) {
                    sourceOrigin = "shader-pack";
                    break readFile;
                }
            }

            // Try loading shader from assets in jar
            if (bundledFileExists) {
                source = Fs.tryReadString(realPath).orElse(null);
                if (source != null) {
                    sourceOrigin = "photonics-jar";
                    break readFile;
                }
            }
        }

        if (patch == null) {
            logShaderSource(packPath, sourceOrigin, source);
            return source;
        }
        @Nullable String loadedSource = source;

        source = patch.applyPatches(
                packPath,
                p -> {
                    if (p.equals(packPath))
                        return loadedSource;

                    return shaderSourceSupplier.apply(p);
                },
                pack.properties().isPhotonicsEnabled()
        );

        logShaderSource(packPath, "photonics-patch", source);

        return source;
    }

    private static void logShaderSource(IPackPath path, String origin, @Nullable String source) {
        if (source == null) return;

        String hash = sha256(source);
        String signature = origin + ':' + hash;
        if (signature.equals(LOGGED_SHADER_SOURCES.put(path.ph$pathString(), signature))) return;

        Photonics.LOGGER.info(
                "Photonics shader source: path={} origin={} sha256={}",
                path.ph$pathString(),
                origin,
                hash
        );
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

    /**
     * Reads a potentially patched shader file, also responsible for loading Photonics' built in shader files.
     */
    public @Nullable String readShaderFile(
            IPackPath path,
            Function<IPackPath, @Nullable String> shaderSourceSupplier
    ) {
        if (path.ph$startsWith("/photonics")) return readPhotonicsFile(path, shaderSourceSupplier);
        if (patch == null) return shaderSourceSupplier.apply(path);

        return patch.applyPatches(
                path,
                shaderSourceSupplier,
                pack.properties().isPhotonicsEnabled()
        );
    }

    private static void wipeDebug() {
        try(var debugFiles = Files.list(PATCHED_DEBUG_PATH)) {
            var listed = debugFiles.toList();

            for (var file : listed)
                Files.delete(file);
        } catch (IOException e) {
            Photonics.LOGGER.error("An exception was thrown while wiping Photonics's debug output");
        }
    }

    public static void writeDebug(
            IPackPath file,
            String source
    ) {
        try {
            Files.writeString(
                    file.ph$resolved(PATCHED_DEBUG_PATH),
                    source
            );
        } catch (IOException e) {
            Photonics.LOGGER.error("An exception was thrown while writing patched output of '{}'", file);
        }
    }

    // Patch tracking
    private static final List<PatchSource> SOURCES;

    private static PatchList patchList = null;
    private static boolean needsReload = true;

    static {
        var sourcesBuilder = ImmutableList.<PatchSource>builder();

        sourcesBuilder.add(JarSource.INSTANCE);
        sourcesBuilder.add(ShaderPatchesSource.INSTANCE);

        if (Photonics.isDevEnvironment()) sourcesBuilder.add(new DevEnvSource());

        SOURCES = sourcesBuilder.build();

        // Reload is deferred so in use patches are not discarded
        for (var source : SOURCES)
            source.onChanged(() -> needsReload = true);
    }

    private static synchronized void checkForReload() {
        if (!needsReload) return;

        // Done in a weird order in case PatchList init throws an exception
        // If you were to first close the old list, it could be left with stale patch paths
        var oldList = patchList;

        patchList = new PatchList(SOURCES);
        needsReload = false;

        if (oldList != null) oldList.close();
    }

    public static PatchList getPatchList() {
        if (needsReload) checkForReload();

        return Objects.requireNonNull(patchList);
    }
}
