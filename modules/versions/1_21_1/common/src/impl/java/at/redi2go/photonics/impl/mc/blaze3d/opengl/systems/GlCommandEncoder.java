package at.redi2go.photonics.impl.mc.blaze3d.opengl.systems;

import at.redi2go.photonics.api.gpu.buffers.IGpuBuffer;
import at.redi2go.photonics.api.gpu.buffers.IGpuBufferSlice;
import at.redi2go.photonics.api.gpu.systems.ICommandEncoder;
import at.redi2go.photonics.api.gpu.textures.IGpuTexture;
import at.redi2go.photonics.api.gpu.textures.IGpuTexture2D;
import at.redi2go.photonics.api.gpu.textures.IGpuTexture3D;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.GlEnums;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.buffer.GlBuffer;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.buffer.GlBufferSlice;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.textures.AbstractGlTexture;
import org.joml.Vector2ic;
import org.joml.Vector3ic;
import org.joml.Vector4fc;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

import java.nio.ByteBuffer;

public enum GlCommandEncoder implements ICommandEncoder {
    INSTANCE;

    @Override
    public void ph$clearColorTexture(IGpuTexture<?> gpuTexture, Vector4fc clearColor) {
        // Minecraft 1.21.1 targets older Blaze3D APIs; current Photonics callers do not rely
        // on this clear path during setup, so leave it as a compatibility no-op for now.
    }

    @Override
    public void ph$writeToBuffer(IGpuBuffer buffer, ByteBuffer byteBuffer) {
        writeToBuffer(new GlBufferSlice((GlBuffer) buffer, 0, byteBuffer.remaining()), byteBuffer);
    }

    @Override
    public void ph$writeToBuffer(IGpuBufferSlice slice, ByteBuffer byteBuffer) {
        writeToBuffer((GlBufferSlice) slice, byteBuffer);
    }

    private void writeToBuffer(GlBufferSlice slice, ByteBuffer byteBuffer) {
        var buffer = slice.buffer();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer.handle());
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, slice.offset(), byteBuffer);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    @Override
    public IGpuBuffer.MappedView ph$mapBuffer(IGpuBuffer buffer, boolean readable, boolean writeable) {
        return ph$mapBuffer(buffer.ph$slice(0, buffer.ph$size()), readable, writeable);
    }

    @Override
    public IGpuBuffer.MappedView ph$mapBuffer(IGpuBufferSlice bufferSlice, boolean readable, boolean writeable) {
        var slice = (GlBufferSlice) bufferSlice;
        var buffer = slice.buffer();

        int access = 0;
        if (readable) access |= GL30.GL_MAP_READ_BIT;
        if (writeable) access |= GL30.GL_MAP_WRITE_BIT;

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer.handle());
        ByteBuffer mapped = GL30.glMapBufferRange(GL15.GL_ARRAY_BUFFER, slice.offset(), slice.length(), access);
        return new MappedView(mapped);
    }

    @Override
    public void ph$copyToBuffer(IGpuBufferSlice slice1, IGpuBufferSlice slice2) {
        var source = (GlBufferSlice) slice1;
        var target = (GlBufferSlice) slice2;

        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, source.buffer().handle());
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, target.buffer().handle());
        GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, source.offset(), target.offset(), Math.min(source.length(), target.length()));
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
    }

    @Override
    public void ph$writeToTexture(IGpuTexture2D texture, ByteBuffer data, Vector2ic offset, Vector2ic size) {
        var glTexture = (AbstractGlTexture<?>) texture;
        var format = GlEnums.textureFormat(texture.ph$format());
        GL30.glBindTexture(GL30.GL_TEXTURE_2D, glTexture.handle());
        GL30.glTexSubImage2D(GL30.GL_TEXTURE_2D, 0, offset.x(), offset.y(), size.x(), size.y(), format.format(), format.type(), data);
        GL30.glBindTexture(GL30.GL_TEXTURE_2D, 0);
    }

    @Override
    public void ph$writeToTexture(IGpuTexture3D texture, ByteBuffer data, Vector3ic offset, Vector3ic size) {
        var glTexture = (AbstractGlTexture<?>) texture;
        var format = GlEnums.textureFormat(texture.ph$format());
        GL30.glBindTexture(GL30.GL_TEXTURE_3D, glTexture.handle());
        GL30.glTexSubImage3D(GL30.GL_TEXTURE_3D, 0, offset.x(), offset.y(), offset.z(), size.x(), size.y(), size.z(), format.format(), format.type(), data);
        GL30.glBindTexture(GL30.GL_TEXTURE_3D, 0);
    }

    private static class MappedView implements IGpuBuffer.MappedView {
        private final ByteBuffer data;

        private MappedView(ByteBuffer data) {
            this.data = data;
        }

        @Override
        public ByteBuffer ph$data() {
            return data;
        }

        @Override
        public void close() {
            GL15.glUnmapBuffer(GL15.GL_ARRAY_BUFFER);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        }
    }
}
