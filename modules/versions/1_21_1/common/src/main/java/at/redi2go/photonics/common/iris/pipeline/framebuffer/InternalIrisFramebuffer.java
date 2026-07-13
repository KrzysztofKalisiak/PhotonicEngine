package at.redi2go.photonics.common.iris.pipeline.framebuffer;

import at.redi2go.photonics.core.iris.pipeline.texture.IrisFramebuffer;
import org.joml.Vector2ic;

public interface InternalIrisFramebuffer extends IrisFramebuffer {
    Vector2ic viewportSize();

    void bind();

    default void bind(String... attachmentNames) {
        bind();
    }

    void unbind();

    @Override
    default IrisFramebuffer withDrawBuffers(String... attachmentNames) {
        return new DrawBufferFramebuffer(this, attachmentNames);
    }
}
