package at.redi2go.photonics.core.rendering.lights;

import at.redi2go.photonics.api.mc.world.level.IBlockState;
import at.redi2go.photonics.core.config.lights.BlockLightInfo;
import org.joml.RoundingMode;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.Objects;

public final class TracedLightPosition {
    private final int blockId;
    private final Vector3d pos;
    private final Vector3d previousPos;
    private final boolean previousPosValid;
    private final IBlockState blockState;
    private final BlockLightInfo lightInfo;
    private final Object temporalIdentity;
    private final boolean temporallyMoving;
    private final int temporalDomainToken;

    private int luminanceMod = 0;
    private double luminance = 0f;

    public TracedLightPosition(
            int blockId,
            Vector3d pos,
            IBlockState blockState,
            BlockLightInfo lightInfo
    ) {
        this(blockId, pos, blockState, lightInfo, null, false, pos, true, 0);
    }

    public TracedLightPosition(
            int blockId,
            Vector3d pos,
            IBlockState blockState,
            BlockLightInfo lightInfo,
            Object temporalIdentity
    ) {
        this(blockId, pos, blockState, lightInfo, temporalIdentity, false, pos, true, 0);
    }

    public TracedLightPosition(
            int blockId,
            Vector3d pos,
            IBlockState blockState,
            BlockLightInfo lightInfo,
            Object temporalIdentity,
            boolean temporallyMoving
    ) {
        this(blockId, pos, blockState, lightInfo, temporalIdentity, temporallyMoving, pos, true, 0);
    }

    public TracedLightPosition(
            int blockId,
            Vector3d pos,
            IBlockState blockState,
            BlockLightInfo lightInfo,
            Object temporalIdentity,
            boolean temporallyMoving,
            Vector3d previousPos,
            boolean previousPosValid
    ) {
        this(
                blockId,
                pos,
                blockState,
                lightInfo,
                temporalIdentity,
                temporallyMoving,
                previousPos,
                previousPosValid,
                0
        );
    }

    public TracedLightPosition(
            int blockId,
            Vector3d pos,
            IBlockState blockState,
            BlockLightInfo lightInfo,
            Object temporalIdentity,
            boolean temporallyMoving,
            Vector3d previousPos,
            boolean previousPosValid,
            int temporalDomainToken
    ) {
        this.blockId = blockId;
        this.pos = pos;
        this.previousPos = previousPos;
        this.previousPosValid = previousPosValid;
        this.blockState = blockState;
        this.lightInfo = lightInfo;
        this.temporalIdentity = temporalIdentity;
        this.temporallyMoving = temporalIdentity != null && temporallyMoving;
        this.temporalDomainToken = temporalIdentity == null
                ? 0
                : Math.min(0xffff, Math.max(0, temporalDomainToken));
    }

    public int blockId() {
        return blockId;
    }

    public Vector3d pos() {
        return pos;
    }

    public Vector3d previousPos() {
        return previousPos;
    }

    public boolean previousPosValid() {
        return previousPosValid;
    }

    public Vector3i blockPos() {
        return new Vector3i(pos, RoundingMode.FLOOR);
    }

    public IBlockState blockState() {
        return blockState;
    }

    public BlockLightInfo lightInfo() {
        return lightInfo;
    }

    boolean hasTemporalIdentity() {
        return temporalIdentity != null;
    }

    boolean isTemporallyMoving() {
        return temporallyMoving;
    }

    int temporalDomainToken() {
        return temporalDomainToken;
    }

    Object temporalMappingKey() {
        if (temporalIdentity == null)
            return this;

        return new TemporalMappingKey(
                temporalIdentity,
                temporalDomainToken,
                blockId,
                blockState,
                lightInfo
        );
    }

    public double getLuminance(Vector3d cameraPosition, int mod) {
        if (this.luminanceMod != mod) {
            luminance = lightInfo.luminanceFrom(pos, cameraPosition);
            luminanceMod = mod;
        }

        return luminance;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (TracedLightPosition) obj;
        return this.blockId == that.blockId &&
                Objects.equals(this.pos, that.pos) &&
                Objects.equals(this.previousPos, that.previousPos) &&
                this.previousPosValid == that.previousPosValid &&
                Objects.equals(this.blockState, that.blockState) &&
                Objects.equals(this.lightInfo, that.lightInfo) &&
                Objects.equals(this.temporalIdentity, that.temporalIdentity) &&
                this.temporallyMoving == that.temporallyMoving &&
                this.temporalDomainToken == that.temporalDomainToken;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                blockId,
                pos,
                previousPos,
                previousPosValid,
                blockState,
                lightInfo,
                temporalIdentity,
                temporallyMoving,
                temporalDomainToken
        );
    }

    @Override
    public String toString() {
        return "TracedLightPosition[" +
                "blockId=" + blockId + ", " +
                "pos=" + pos + ", " +
                "previousPos=" + previousPos + ", " +
                "previousPosValid=" + previousPosValid + ", " +
                "blockState=" + blockState + ", " +
                "lightInfo=" + lightInfo + ", " +
                "temporalIdentity=" + temporalIdentity + ", " +
                "temporallyMoving=" + temporallyMoving + ", " +
                "temporalDomainToken=" + temporalDomainToken + ']';
    }

    private record TemporalMappingKey(
            Object temporalIdentity,
            int temporalDomainToken,
            int blockId,
            IBlockState blockState,
            BlockLightInfo lightInfo
    ) {
    }

}
