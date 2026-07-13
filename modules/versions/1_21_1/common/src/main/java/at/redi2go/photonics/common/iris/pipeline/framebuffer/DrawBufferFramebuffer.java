package at.redi2go.photonics.common.iris.pipeline.framebuffer;

import org.joml.Vector2ic;

import java.util.Arrays;

final class DrawBufferFramebuffer implements InternalIrisFramebuffer {
    private final InternalIrisFramebuffer framebuffer;
    private final String[] attachmentNames;

    DrawBufferFramebuffer(InternalIrisFramebuffer framebuffer, String[] attachmentNames) {
        this.framebuffer = framebuffer;
        this.attachmentNames = Arrays.copyOf(attachmentNames, attachmentNames.length);
    }

    @Override
    public Vector2ic viewportSize() {
        return framebuffer.viewportSize();
    }

    @Override
    public void bind() {
        framebuffer.bind(attachmentNames);
    }

    @Override
    public void unbind() {
        framebuffer.unbind();
    }

    @Override
    public void flip() {
        framebuffer.flip();
    }

    @Override
    public void recalculateSizes() {
        framebuffer.recalculateSizes();
    }
}
