package at.redi2go.photonics.core.rendering.lights;

import at.redi2go.photonics.api.mc.Id;
import at.redi2go.photonics.api.mc.Minecraft;
import at.redi2go.photonics.api.mc.core.IBlockPos;
import at.redi2go.photonics.api.mc.world.level.IBlock;
import at.redi2go.photonics.api.mc.world.level.ILevel;
import at.redi2go.photonics.api.mc.world.level.chunk.IChunkSection;
import at.redi2go.photonics.api.shaders.IShaderPack;
import at.redi2go.photonics.core.Photonics;
import at.redi2go.photonics.core.config.PhConfig;
import at.redi2go.photonics.core.config.PhConfigWatcher;
import at.redi2go.photonics.core.config.lights.LightRegistry;
import at.redi2go.photonics.core.iris.pipeline.uniform.IDynamicUniformHolder;
import at.redi2go.photonics.core.iris.pipeline.uniform.IUniformHolder;
import at.redi2go.photonics.core.iris.pipeline.uniform.IUniformUpdateFrequency;
import at.redi2go.photonics.core.rendering.RenderingComponent;
import at.redi2go.photonics.core.rendering.SectionCopy;
import at.redi2go.photonics.core.rendering.SectionManager;
import at.redi2go.photonics.core.rendering.UniformUpdater;
import at.redi2go.photonics.core.rendering.WorldOrigin;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.MultimapBuilder;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public abstract class AbstractLightList implements Runnable, RenderingComponent {
    private static final int MAX_SECTIONS_PER_RUN = 48;
    private static final long EXTERNAL_PROXY_SETTLE_NANOS = 125_000_000L;
    private static final long EXTERNAL_PROXY_SETTLE_FRAMES = 2L;
    private static final long EXTERNAL_PROXY_OWNERSHIP_GRACE_NANOS = 250_000_000L;
    private static final int EXTERNAL_PROXY_ALIAS_RADIUS = 3;
    private static final String EXTERNAL_PROXY_BLOCK_ID = "minecraft:light";
    private static final IBlock BLOCK_LAVA = IBlock.fromIdOrThrow(Id.fromNamespaceAndPath("minecraft", "lava"));

    private final Thread compilerThread;
    private final ReentrantLock lock = new ReentrantLock();

    private boolean needsReload = false;
    private LightRegistry lightRegistry;
    private final PhConfigWatcher<LightRegistry> lightsRegistryObserver;

    private final int maxLights;
    private final Supplier<WorldOrigin> worldOriginSupplier;

    private final SectionManager.SectionQueue sectionQueue;

    private final Object2LongMap<Vector3i> sectionHashes = new Object2LongOpenHashMap<>();

    private final ListMultimap<Vector3i, TracedLightPosition> tracedLightPositions;
    private final UniformUpdater uniformUpdater = new UniformUpdater();

    protected LightList lights;
    protected LightList mostRecentLights;
    private LightList sectionLights;
    private long sectionLightsRevision;
    private long combinedSectionLightsRevision = -1L;
    private long externalLightsRevision = -1L;

    private int lastDiagnosticEligibleLights = -1;
    private int lastDiagnosticSelectedLights = -1;
    private int lastDiagnosticSections = -1;
    private int lastDiagnosticPriorityLights = -1;
    private int lastDiagnosticMovingLights = -1;
    private int lastDiagnosticSuppressedSectionLights = -1;
    private final Map<SectionLightCell, ProxyCandidateState> proxyCandidates = new HashMap<>();
    private final Map<SectionLightCell, ProxyOwnershipClaim> confirmedExternalProxyClaims = new HashMap<>();
    private final Set<SectionLightCell> distantProxyCells = new HashSet<>();
    private long nextProxyReleaseNanos = Long.MAX_VALUE;
    private long quarantinedProxyEvents;
    private long distantProxyEvents;
    private long renderFrameSequence;

    @SuppressWarnings("UnstableApiUsage")
    public AbstractLightList(
            SectionManager sectionManager,
            int maxLights,
            Supplier<WorldOrigin> worldOriginSupplier
    ) {
        this.maxLights = maxLights;
        this.worldOriginSupplier = worldOriginSupplier;

        this.sectionQueue = sectionManager.newSectionQueue(true);

        this.tracedLightPositions = MultimapBuilder.hashKeys()
                .arrayListValues()
                .build();

        this.compilerThread = new Thread(this, "Photonics Light List Compiler");
        this.compilerThread.start();

        this.lightRegistry = PhConfig.getLightRegistry();
        this.lightsRegistryObserver = PhConfig.watch(
                ignored -> PhConfig.getLightRegistry(),
                this::setLightRegistry
        );
    }

    private void setLightRegistry(LightRegistry lightRegistry) {
        this.lightRegistry = lightRegistry;

        needsReload = true;
        compilerThread.interrupt();
    }

    @Override
    public void run() {
        while (true) {
            if (Thread.interrupted() && !needsReload) return;

            try {
                boolean needsUpload = reloadLights();
                if (!needsUpload)
                    sectionQueue.awaitTask();

                var unloadedSections = sectionQueue.drainUnloadQueue();
                if (!unloadedSections.isEmpty()) {
                    needsUpload = true;
                    unloadSections(unloadedSections);
                }

                var loadedSections = sectionQueue.drain(MAX_SECTIONS_PER_RUN);
                if (!loadedSections.isEmpty() && addNewSections(loadedSections))
                    needsUpload = true;

                if (needsUpload) {
                    var newLights = trimLights();
                    storeSectionLights(newLights);
                }
            } catch (InterruptedException e) {
                if (!needsReload) return;
            }
        }
    }

    // Compiler stages

    private void unloadSections(List<Vector3i> unloadedSections) {
        for (var section : unloadedSections) {
            tracedLightPositions.removeAll(section);
            sectionHashes.removeLong(section);
        }
    }

    private boolean reloadLights() {
        if (!needsReload) return false;
        needsReload = false;

        for (var section : List.copyOf(tracedLightPositions.keySet())) {
            var lights = tracedLightPositions.get(section);

            for (var itr = lights.listIterator(); itr.hasNext(); ) {
                var lightPosition = itr.next();
                var newLightInfo = lightRegistry.get(lightPosition.blockState());

                if (newLightInfo != null) {
                    itr.set(new TracedLightPosition(
                            lightPosition.blockId(),
                            lightPosition.pos(),
                            lightPosition.blockState(),
                            newLightInfo
                    ));
                } else itr.remove();
            }
        }

        return true;
    }

    private boolean addNewSections(List<SectionCopy> newSections) {
        var level = Minecraft.getLevel();
        if (level == null) return false;

        var shaderPack = IShaderPack.getCurrentPack();
        boolean changed = false;

        for (var section : newSections) {
            var sectionHash = section.computeSectionHash(null);
            if (sectionHashes.put(section.pos(), sectionHash) == sectionHash) continue;

            changed = true;
            var lights = tracedLightPositions.get(section.pos());
            lights.clear();

            section.forEachBlock((blockChunkOffset, blockPos, block) -> {
                var light = lightRegistry.get(block);
                if (light == null || !light.isTraced()) return;

                if (!shouldCullLight(section, level, blockPos))
                    lights.add(
                            new TracedLightPosition(
                                    shaderPack.map(e -> e.getBlockId(block)).orElse(-1),
                                    new Vector3d(blockPos.ph$x(), blockPos.ph$y(), blockPos.ph$z()).add(0.5, 0.5, 0.5),
                                    block,
                                    light
                            )
                    );
            });

            if (lights.isEmpty())
                tracedLightPositions.removeAll(section.pos());
        }

        return changed;
    }

    private boolean shouldCullLight(
            SectionCopy blockOwner,
            ILevel level,
            IBlockPos blockPos
    ) {
        IChunkSection section = blockOwner;
        Vector3i sectionPos = blockOwner.pos();

        for (var offset : NEIGHBORS) {
            var neighborBlockPos = blockPos.ph$offset(offset);
            var blockSectionPos = SectionCopy.getSectionCoord(neighborBlockPos);

            if (blockSectionPos.equals(sectionPos)) {
                section = blockOwner;
            } else {
                var chunkAccess = level.ph$getChunkOrNull(blockSectionPos.x, blockSectionPos.z);
                if (chunkAccess == null) continue;

                var newSection = chunkAccess.ph$sections()[level.ph$getSectionIndexFromSectionY(blockSectionPos.y)];
                if (newSection == null) continue;

                section = newSection;
            }

            if (section.ph$hasOnlyAir()) continue;

            var blockState = section.ph$getBlockState(
                    neighborBlockPos.ph$x() & 15,
                    neighborBlockPos.ph$y() & 15,
                    neighborBlockPos.ph$z() & 15
            );

            if (blockState.ph$is(BLOCK_LAVA)) continue;

            if (blockState.ph$isAir() || !blockState.ph$isSuffocating(level, neighborBlockPos) || !blockState.ph$isCollisionShapeFullBlock(level, neighborBlockPos))
                return false;
        }

        return true;
    }

    private LightList trimLights() {
        var loadedLights = tracedLightPositions.values().toArray(TracedLightPosition[]::new);
        Vector3d cameraPosition = Minecraft.getCameraPos();
        int mod = (int) System.nanoTime();

        Arrays.sort(
                loadedLights,
                Comparator.comparingDouble(light -> -light.getLuminance(cameraPosition, mod))
        );

        int selectedLights = Math.min(loadedLights.length, maxLights);
        int sectionCount = tracedLightPositions.keySet().size();
        logCandidateDiagnostics(loadedLights.length, selectedLights, sectionCount);

        if (loadedLights.length < maxLights)
            return new LightList(loadedLights, WorldOrigin.get());

        return new LightList(
                Arrays.copyOf(loadedLights, maxLights),
                WorldOrigin.get()
        );
    }

    private void logCandidateDiagnostics(int eligibleLights, int selectedLights, int sectionCount) {
        if (eligibleLights == lastDiagnosticEligibleLights
                && selectedLights == lastDiagnosticSelectedLights
                && sectionCount == lastDiagnosticSections)
            return;

        lastDiagnosticEligibleLights = eligibleLights;
        lastDiagnosticSelectedLights = selectedLights;
        lastDiagnosticSections = sectionCount;

        Photonics.LOGGER.info(
                "Photonics light candidates v18: eligible={}, selected={}, maxLights={}, lightSections={}, capped={}",
                eligibleLights,
                selectedLights,
                maxLights,
                sectionCount,
                eligibleLights > selectedLights
        );
    }

    protected abstract void storeLight(
            int index,
            Vector4f[] light,
            Vector4f previousPosition
    );

    protected abstract void storeMapping(int beforeIndex, int afterIndex);

    protected abstract void clearMapping();

    protected abstract void prepareUpload();

    private void storeSectionLights(LightList sectionLights) throws InterruptedException {
        lock.lockInterruptibly();

        try {
            this.sectionLights = sectionLights;
            sectionLightsRevision++;
        } finally {
            lock.unlock();
        }
    }

    private LightList combineLights(
            LightList sectionLights,
            long currentSectionLightsRevision,
            ExternalLightList.Snapshot externalSnapshot,
            long nowNanos
    ) {
        var externalLights = externalSnapshot.lights();
        var replacementAliases = externalSnapshot.replacementAliases();

        if (externalLights.isEmpty() && replacementAliases.isEmpty()) {
            clearProxyOwnership();
            return sectionLights == null
                    ? new LightList(new TracedLightPosition[0], WorldOrigin.get())
                    : sectionLights;
        }

        var combined = new TracedLightPosition[
                (sectionLights == null ? 0 : sectionLights.size()) + externalLights.size()
        ];
        int size = 0;
        int suppressedSectionLights = 0;
        int blockIdMatches = 0;
        int lightProfileMatches = 0;
        int positionFallbacks = 0;
        int confirmedProxySuppressions = 0;
        int quarantinedProxyLights = 0;
        Vector3i firstSuppressedPosition = null;
        Set<TracedLightPosition> knownLights = new HashSet<>();
        Set<SectionLightCell> presentProxyCells = new HashSet<>();
        boolean proxyOwnershipActive = !externalLights.isEmpty() && !replacementAliases.isEmpty();
        nextProxyReleaseNanos = Long.MAX_VALUE;
        if (!proxyOwnershipActive) {
            proxyCandidates.clear();
            confirmedExternalProxyClaims.clear();
            distantProxyCells.clear();
        }

        if (sectionLights != null) {
            for (var light : sectionLights) {
                var sectionAlias = ExternalLightList.ReplacementAlias.from(light);
                boolean externalProxy = proxyOwnershipActive
                        && EXTERNAL_PROXY_BLOCK_ID.equals(sectionAlias.blockId());
                SectionLightCell proxyCell = externalProxy
                        ? new SectionLightCell(sectionAlias.x(), sectionAlias.y(), sectionAlias.z())
                        : null;
                if (proxyCell != null)
                    presentProxyCells.add(proxyCell);

                boolean positionMatched = false;
                boolean blockIdMatched = false;
                boolean lightProfileMatched = false;
                boolean proxyAliasNearby = false;
                ExternalLightList.ReplacementAlias nearestAlias = null;
                long nearestAliasDistanceSquared = Long.MAX_VALUE;
                for (var alias : replacementAliases) {
                    if (externalProxy) {
                        long dx = (long) sectionAlias.x() - alias.x();
                        long dy = (long) sectionAlias.y() - alias.y();
                        long dz = (long) sectionAlias.z() - alias.z();
                        long distanceSquared = dx * dx + dy * dy + dz * dz;
                        if (distanceSquared < nearestAliasDistanceSquared) {
                            nearestAliasDistanceSquared = distanceSquared;
                            nearestAlias = alias;
                        }
                        if (Math.abs(dx) <= EXTERNAL_PROXY_ALIAS_RADIUS
                                && Math.abs(dy) <= EXTERNAL_PROXY_ALIAS_RADIUS
                                && Math.abs(dz) <= EXTERNAL_PROXY_ALIAS_RADIUS)
                            proxyAliasNearby = true;
                    }

                    if (!alias.matchesPosition(sectionAlias))
                        continue;

                    positionMatched = true;
                    if (alias.matchesBlockId(sectionAlias)) {
                        blockIdMatched = true;
                        break;
                    }
                    if (alias.matchesLightProfile(sectionAlias))
                        lightProfileMatched = true;
                }

                // Sable's mirrored section may expose a different state wrapper.
                // An exact cell match still denotes the same block emitter.
                if (positionMatched) {
                    if (proxyCell != null) {
                        distantProxyCells.remove(proxyCell);
                        proxyCandidates.remove(proxyCell);
                        confirmedExternalProxyClaims.put(
                                proxyCell,
                                new ProxyOwnershipClaim(Long.MAX_VALUE)
                        );
                    }
                    suppressedSectionLights++;
                    if (blockIdMatched)
                        blockIdMatches++;
                    else if (lightProfileMatched)
                        lightProfileMatches++;
                    else
                        positionFallbacks++;
                    if (firstSuppressedPosition == null)
                        firstSuppressedPosition = light.blockPos();
                    continue;
                }

                // Contraption Lights publishes a vanilla Light block at the
                // server-logical cell before the client render-pose alias can
                // reach that cell. Quarantine only nearby proxy-shaped cells;
                // an exact match owns its cell until a bounded grace period
                // after the first unmatched observation.
                if (proxyCell != null) {
                    ProxyOwnershipClaim confirmedClaim =
                            confirmedExternalProxyClaims.get(proxyCell);
                    if (confirmedClaim != null) {
                        long claimUntilNanos = confirmedClaim.untilNanos();
                        if (claimUntilNanos == Long.MAX_VALUE) {
                            claimUntilNanos =
                                    nowNanos + EXTERNAL_PROXY_OWNERSHIP_GRACE_NANOS;
                            confirmedExternalProxyClaims.put(
                                    proxyCell,
                                    new ProxyOwnershipClaim(claimUntilNanos)
                            );
                        }
                        if (nowNanos < claimUntilNanos) {
                            ProxyCandidateState candidate = proxyCandidates.get(proxyCell);
                            if (candidate == null || candidate.light() != light)
                                proxyCandidates.put(
                                        proxyCell,
                                        new ProxyCandidateState(
                                                nowNanos,
                                                renderFrameSequence,
                                                light
                                        )
                                );
                            suppressedSectionLights++;
                            confirmedProxySuppressions++;
                            nextProxyReleaseNanos = Math.min(
                                    nextProxyReleaseNanos,
                                    claimUntilNanos
                            );
                            if (firstSuppressedPosition == null)
                                firstSuppressedPosition = light.blockPos();
                            continue;
                        }
                        confirmedExternalProxyClaims.remove(proxyCell);
                    }

                    if (!proxyAliasNearby) {
                        proxyCandidates.remove(proxyCell);
                        if (distantProxyCells.add(proxyCell))
                            logDistantProxy(
                                    sectionAlias,
                                    nearestAlias,
                                    nearestAliasDistanceSquared,
                                    currentSectionLightsRevision,
                                    externalSnapshot.revision()
                            );
                        combined[size++] = light;
                        knownLights.add(light);
                        continue;
                    }
                    distantProxyCells.remove(proxyCell);

                    ProxyCandidateState candidate = proxyCandidates.get(proxyCell);
                    boolean newlySeen = candidate == null || candidate.light() != light;
                    if (newlySeen) {
                        candidate = new ProxyCandidateState(
                                nowNanos,
                                renderFrameSequence,
                                light
                        );
                        proxyCandidates.put(proxyCell, candidate);
                    }

                    long releaseNanos = candidate.firstSeenNanos() + EXTERNAL_PROXY_SETTLE_NANOS;
                    boolean observedEnoughFrames =
                            renderFrameSequence - candidate.firstSeenFrame()
                                    >= EXTERNAL_PROXY_SETTLE_FRAMES;
                    if (nowNanos < releaseNanos || !observedEnoughFrames) {
                        suppressedSectionLights++;
                        quarantinedProxyLights++;
                        nextProxyReleaseNanos = Math.min(
                                nextProxyReleaseNanos,
                                Math.max(nowNanos, releaseNanos)
                        );
                        if (firstSuppressedPosition == null)
                            firstSuppressedPosition = light.blockPos();
                        if (newlySeen)
                            logProxyQuarantine(
                                    sectionAlias,
                                    nearestAlias,
                                    nearestAliasDistanceSquared,
                                    currentSectionLightsRevision,
                                    externalSnapshot.revision()
                            );
                        continue;
                    }
                }

                combined[size++] = light;
                knownLights.add(light);
            }
        }

        if (proxyOwnershipActive) {
            proxyCandidates.keySet().retainAll(presentProxyCells);
            confirmedExternalProxyClaims.keySet().retainAll(presentProxyCells);
            distantProxyCells.retainAll(presentProxyCells);
        }

        if (suppressedSectionLights != lastDiagnosticSuppressedSectionLights) {
            Photonics.LOGGER.info(
                    "Photonics v64 external-light de-duplication: suppressedSectionLights={}, aliasBlockIdMatches={}, aliasLightProfileMatches={}, aliasPositionFallbacks={}, confirmedProxySuppressions={}, quarantinedProxyLights={}, trackedProxyCells={}, replacementAliases={}, externalLights={}, firstSuppressed={}",
                    suppressedSectionLights,
                    blockIdMatches,
                    lightProfileMatches,
                    positionFallbacks,
                    confirmedProxySuppressions,
                    quarantinedProxyLights,
                    presentProxyCells.size(),
                    replacementAliases.size(),
                    externalLights.size(),
                    firstSuppressedPosition == null ? "none" : firstSuppressedPosition
            );
            lastDiagnosticSuppressedSectionLights = suppressedSectionLights;
        }

        for (var light : externalLights) {
            if (knownLights.add(light))
                combined[size++] = light;
        }

        if (size != combined.length)
            combined = Arrays.copyOf(combined, size);

        Vector3d cameraPosition = Minecraft.getCameraPos();
        int mod = (int) System.nanoTime();
        Arrays.sort(
                combined,
                Comparator
                        .comparingInt((TracedLightPosition light) -> light.isTemporallyMoving() ? 0 : 1)
                        .thenComparingInt(light -> light.hasTemporalIdentity() ? 0 : 1)
                        .thenComparingDouble(light -> -light.getLuminance(cameraPosition, mod))
        );

        if (combined.length > maxLights)
            combined = Arrays.copyOf(combined, maxLights);

        int movingLightCount = 0;
        while (movingLightCount < combined.length && combined[movingLightCount].isTemporallyMoving())
            movingLightCount++;

        int priorityLightCount = 0;
        while (priorityLightCount < combined.length && combined[priorityLightCount].hasTemporalIdentity())
            priorityLightCount++;

        return new LightList(
                combined,
                sectionLights == null ? WorldOrigin.get() : sectionLights.origin(),
                movingLightCount,
                priorityLightCount
        );
    }

    private void logProxyQuarantine(
            ExternalLightList.ReplacementAlias proxy,
            ExternalLightList.ReplacementAlias nearestAlias,
            long nearestAliasDistanceSquared,
            long currentSectionLightsRevision,
            long currentExternalLightsRevision
    ) {
        quarantinedProxyEvents++;
        if (Long.bitCount(quarantinedProxyEvents) != 1)
            return;

        String nearestOffset = nearestAlias == null
                ? "none"
                : "("
                + (proxy.x() - nearestAlias.x()) + ","
                + (proxy.y() - nearestAlias.y()) + ","
                + (proxy.z() - nearestAlias.z()) + ")";
        Photonics.LOGGER.info(
                "Photonics v64 quarantined unmatched Contraption Lights proxy: events={}, proxy=({}, {}, {}), nearestAliasOffset={}, nearestAliasDistanceSquared={}, settleMs={}, settleFrames={}, aliasRadius={}, sectionRevision={}, externalRevision={}",
                quarantinedProxyEvents,
                proxy.x(),
                proxy.y(),
                proxy.z(),
                nearestOffset,
                nearestAlias == null ? "n/a" : nearestAliasDistanceSquared,
                EXTERNAL_PROXY_SETTLE_NANOS / 1_000_000L,
                EXTERNAL_PROXY_SETTLE_FRAMES,
                EXTERNAL_PROXY_ALIAS_RADIUS,
                currentSectionLightsRevision,
                currentExternalLightsRevision
        );
    }

    private void logDistantProxy(
            ExternalLightList.ReplacementAlias proxy,
            ExternalLightList.ReplacementAlias nearestAlias,
            long nearestAliasDistanceSquared,
            long currentSectionLightsRevision,
            long currentExternalLightsRevision
    ) {
        distantProxyEvents++;
        if (Long.bitCount(distantProxyEvents) != 1)
            return;

        String nearestOffset = nearestAlias == null
                ? "none"
                : "("
                + (proxy.x() - nearestAlias.x()) + ","
                + (proxy.y() - nearestAlias.y()) + ","
                + (proxy.z() - nearestAlias.z()) + ")";
        Photonics.LOGGER.info(
                "Photonics v64 left distant vanilla Light candidate unsuppressed: events={}, proxy=({}, {}, {}), nearestAliasOffset={}, nearestAliasDistanceSquared={}, aliasRadius={}, sectionRevision={}, externalRevision={}",
                distantProxyEvents,
                proxy.x(),
                proxy.y(),
                proxy.z(),
                nearestOffset,
                nearestAlias == null ? "n/a" : nearestAliasDistanceSquared,
                EXTERNAL_PROXY_ALIAS_RADIUS,
                currentSectionLightsRevision,
                currentExternalLightsRevision
        );
    }

    private void clearProxyOwnership() {
        proxyCandidates.clear();
        confirmedExternalProxyClaims.clear();
        distantProxyCells.clear();
        nextProxyReleaseNanos = Long.MAX_VALUE;
        lastDiagnosticSuppressedSectionLights = -1;
    }

    private void storeLightsLocked(LightList lights) {
        if (Objects.equals(this.lights, lights)) return;

        int previousSize = this.lights == null ? 0 : this.lights.size();
        this.lights = lights;
        var worldOrigin = lights.origin();

        if (previousSize != lights.size())
            Photonics.LOGGER.info("Photonics light list pending: {} -> {}", previousSize, lights.size());

        int priorityLightCount = lights.priorityLightCount();
        int movingLightCount = lights.movingLightCount();
        if (lastDiagnosticPriorityLights != priorityLightCount
                || lastDiagnosticMovingLights != movingLightCount) {
            Photonics.LOGGER.info(
                    "Photonics v35 direct-light prefixes: movingLights={}, externalLights={}, totalLights={}, proposalBudget=adaptive-half-candidates",
                    movingLightCount,
                    priorityLightCount,
                    lights.size()
            );
            lastDiagnosticPriorityLights = priorityLightCount;
            lastDiagnosticMovingLights = movingLightCount;
        }

        for (int i = 0; i < lights.size(); i++) {
            var light = lights.get(i);

            storeLight(
                    i,
                    light.lightInfo().toVector4Array(
                            toVector3f(worldOrigin.applyOffset(light.pos())),
                            light.blockId()
                    ),
                    new Vector4f(
                            toVector3f(worldOrigin.applyOffset(light.previousPos())),
                            (light.previousPosValid() ? 1.0f : -1.0f)
                                    * (light.temporalDomainToken() + 1.0f)
                    )
            );
        }

        if (mostRecentLights != null)
            lights.createMapping(mostRecentLights)
                    .forEachIndex(this::storeMapping);

        prepareUpload();
        uniformUpdater.updateNextFrame();
    }

    // Upload

    protected abstract void upload();

    @Override
    public void onFrameBegin() {
        lock.lock();

        try {
            renderFrameSequence++;
            var externalSnapshot = ExternalLightList.snapshot();
            long nowNanos = System.nanoTime();
            if (sectionLightsRevision != combinedSectionLightsRevision
                    || externalSnapshot.revision() != externalLightsRevision
                    || nowNanos >= nextProxyReleaseNanos) {
                combinedSectionLightsRevision = sectionLightsRevision;
                externalLightsRevision = externalSnapshot.revision();
                storeLightsLocked(combineLights(
                        sectionLights,
                        sectionLightsRevision,
                        externalSnapshot,
                        nowNanos
                ));
            }

            upload();

            boolean listChanged = lights != mostRecentLights;
            if (listChanged)
                mostRecentLights = lights;

            // Publish the size and priority prefix from the same generation that
            // was just uploaded. Temporal mapping remains valid for this frame.
            uniformUpdater.updateAll();

            if (listChanged)
                clearMapping();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void registerUniforms(IUniformHolder uniforms) {
        uniforms.uniform3f(IUniformUpdateFrequency.perFrame(), "light_list_offset", () -> {
            var listOrigin = mostRecentLights == null ? null : mostRecentLights.origin();
            var realOrigin = worldOriginSupplier.get();

            if (listOrigin == null || realOrigin == null)
                return new Vector3f(0f);

            return toVector3f(realOrigin.sub(listOrigin, new Vector3d()));
        });
    }

    @Override
    public void registerDynamicUniforms(IDynamicUniformHolder dynamicUniforms) {
        dynamicUniforms.uniform1i(
                "light_list_size",
                () -> mostRecentLights == null ? 0 : mostRecentLights.size(),
                uniformUpdater.newNotifier()
        );
        dynamicUniforms.uniform1i(
                "ph_priority_light_count",
                () -> mostRecentLights == null ? 0 : mostRecentLights.priorityLightCount(),
                uniformUpdater.newNotifier()
        );
        dynamicUniforms.uniform1i(
                "ph_moving_light_count",
                () -> mostRecentLights == null ? 0 : mostRecentLights.movingLightCount(),
                uniformUpdater.newNotifier()
        );
    }

    @Override
    public void close() {
        needsReload = false;
        compilerThread.interrupt();

        lightsRegistryObserver.close();
    }

    private static final Vector3i[] NEIGHBORS = new Vector3i[]{
            new Vector3i(0, 1, 0),
            new Vector3i(0, -1, 0),

            new Vector3i(1, 0, 0),
            new Vector3i(-1, 0, 0),

            new Vector3i(0, 0, 1),
            new Vector3i(0, 0, -1),
    };

    private static Vector3f toVector3f(Vector3dc vector) {
        return new Vector3f((float) vector.x(), (float) vector.y(), (float) vector.z());
    }

    private record SectionLightCell(int x, int y, int z) {
    }

    private record ProxyCandidateState(
            long firstSeenNanos,
            long firstSeenFrame,
            TracedLightPosition light
    ) {
    }

    private record ProxyOwnershipClaim(long untilNanos) {
    }

}
