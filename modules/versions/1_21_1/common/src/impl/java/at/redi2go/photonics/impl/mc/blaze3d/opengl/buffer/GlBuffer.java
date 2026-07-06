package at.redi2go.photonics.impl.mc.blaze3d.opengl.buffer;

import at.redi2go.photonics.api.gpu.buffers.BufferUsage;
import at.redi2go.photonics.api.gpu.buffers.IGpuBuffer;
import at.redi2go.photonics.api.gpu.buffers.IGpuBufferSlice;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL43;

public class GlBuffer implements IGpuBuffer {
    private final int handle;
    private final long size;
    private final int usage;
    private boolean closed;

    public GlBuffer(long size, @BufferUsage int usage) {
        this.handle = GL15.glGenBuffers();
        this.size = size;
        this.usage = usage;
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, handle);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, size, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    public int handle() {
        return handle;
    }

    public int glTarget() {
        return (usage & BufferUsage.UNIFORM) != 0 ? GL31.GL_UNIFORM_BUFFER : GL43.GL_SHADER_STORAGE_BUFFER;
    }

    @Override
    public long ph$size() {
        return size;
    }

    @Override
    public int ph$usage() {
        return usage;
    }

    public int usage() {
        return usage;
    }

    @Override
    public boolean ph$isClosed() {
        return closed;
    }

    @Override
    public IGpuBufferSlice ph$slice(long offset, long length) {
        return new GlBufferSlice(this, offset, length);
    }

    @Override
    public void close() {
        if (closed) return;

        closed = true;
        GL15.glDeleteBuffers(handle);
    }
}
