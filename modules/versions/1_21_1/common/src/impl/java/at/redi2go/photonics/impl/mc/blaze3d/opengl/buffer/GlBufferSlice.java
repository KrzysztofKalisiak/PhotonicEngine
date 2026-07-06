package at.redi2go.photonics.impl.mc.blaze3d.opengl.buffer;

import at.redi2go.photonics.api.gpu.buffers.IGpuBuffer;
import at.redi2go.photonics.api.gpu.buffers.IGpuBufferSlice;

public record GlBufferSlice(GlBuffer buffer, long offset, long length) implements IGpuBufferSlice {
    @Override
    public IGpuBuffer ph$buffer() {
        return buffer;
    }

    @Override
    public long ph$offset() {
        return offset;
    }

    @Override
    public long ph$length() {
        return length;
    }

    @Override
    public IGpuBufferSlice ph$slice(long offset, long length) {
        return new GlBufferSlice(buffer, this.offset + offset, length);
    }
}
