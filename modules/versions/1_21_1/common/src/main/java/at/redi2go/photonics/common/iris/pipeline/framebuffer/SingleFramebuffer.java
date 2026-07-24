package at.redi2go.photonics.common.iris.pipeline.framebuffer;

import at.redi2go.photonics.core.Photonics;
import at.redi2go.photonics.api.gpu.textures.ITextureFormat;
import at.redi2go.photonics.core.iris.pipeline.texture.ISamplerHolder;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.textures.IGlTexture;
import com.google.common.collect.ImmutableList;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.minecraft.client.Minecraft;
import org.joml.Vector2i;
import org.joml.Vector2ic;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryStack;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SingleFramebuffer extends GlFramebuffer implements InternalIrisFramebuffer {
    private List<FramebufferAttachment> attachments;

    private final FramebufferSize sizeSupplier;
    private final String diagnosticRole;
    private final Vector2i currentSize = new Vector2i(-1, -1);

    public SingleFramebuffer(
            List<FramebufferAttachment> attachments,
            FramebufferSize sizeSupplier,
            String diagnosticRole
    ) {
        this.attachments = ImmutableList.copyOf(attachments);
        this.sizeSupplier = sizeSupplier;
        this.diagnosticRole = diagnosticRole;

        int maximumDrawBuffers = GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS);
        int maximumColorAttachments = GL11.glGetInteger(GL30.GL_MAX_COLOR_ATTACHMENTS);
        if (attachments.size() > maximumDrawBuffers
                || attachments.size() > maximumColorAttachments) {
            throw new IllegalStateException(
                    "Framebuffer requires " + attachments.size()
                            + " attachments, but OpenGL exposes "
                            + maximumDrawBuffers + " draw buffers and "
                            + maximumColorAttachments + " color attachments"
            );
        }
        if (attachments.size() >= 7) {
            Photonics.LOGGER.info(
                    "Photonics framebuffer capacity v68: buffer={}, attachments={}, maxDrawBuffers={}, maxColorAttachments={}",
                    diagnosticRole,
                    attachments.size(),
                    maximumDrawBuffers,
                    maximumColorAttachments
            );
        }

        setDrawBuffers();
    }

    private void setDrawBuffers() {
        setDrawBuffers(null);
    }

    private void setDrawBuffers(String[] attachmentNames) {
        Set<String> selectedAttachments = attachmentNames == null
                ? null
                : new HashSet<>(Arrays.asList(attachmentNames));
        int[] drawBuffers = new int[attachments.size()];
        for (int i = 0; i < attachments.size(); i++) {
            addColorAttachment(i, ((IGlTexture) attachments.get(i).texture()).handle());
            drawBuffers[i] = selectedAttachments == null || selectedAttachments.contains(attachments.get(i).name())
                    ? GL30.GL_COLOR_ATTACHMENT0 + i
                    : GL11.GL_NONE;
        }

        IrisRenderSystem.drawBuffers(getGlId(), drawBuffers);
        int framebufferStatus = GL45.glCheckNamedFramebufferStatus(
                getGlId(),
                GL30.GL_FRAMEBUFFER
        );
        if (framebufferStatus != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException(
                    "Incomplete Photonics framebuffer " + diagnosticRole
                            + ": status=0x"
                            + Integer.toHexString(framebufferStatus)
            );
        }
    }

    private void clearAttachments() {
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, getGlId());

        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int i = 0; i < attachments.size(); i++) {
                var attachment = attachments.get(i);
                String name = attachment.name();
                if (attachment.texture().ph$format() == ITextureFormat.Values.RGB32UI) {
                    GL30.glClearBufferuiv(
                            GL11.GL_COLOR,
                            i,
                            stack.ints(0, 0, 0, 0)
                    );
                    continue;
                }

                float invalidComponent = name.equals("restir_lighting")
                        || name.equals("restir_lighting_variance")
                        || name.equals("restir_external_lighting")
                        ? -999.0f
                        : 0.0f;
                float directLightIndex = name.equals("restir_direct_reservoirs0") ? -1.0f : invalidComponent;

                GL30.glClearBufferfv(
                        GL11.GL_COLOR,
                        i,
                        stack.floats(directLightIndex, invalidComponent, invalidComponent, invalidComponent)
                );
            }
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousFramebuffer);
        }
    }

    public List<FramebufferAttachment> attachments() {
        return attachments;
    }

    FramebufferAttachment attachment(String name) {
        for (var attachment : attachments) {
            if (attachment.name().equals(name)) return attachment;
        }

        return null;
    }

    Vector2ic currentSize() {
        return currentSize;
    }

    @Override
    public Vector2ic viewportSize() {
        recalculateSizes();
        return new Vector2i(currentSize);
    }

    @Override
    public void bind() {
        bind((String[]) null);
    }

    @Override
    public void bind(String... attachmentNames) {
        recalculateSizes();
        GL45.glTextureBarrier();
        super.bind();
        setDrawBuffers(attachmentNames);
        GL11.glViewport(0, 0, currentSize.x(), currentSize.y());
    }

    @Override
    public void unbind() {
        int width = Minecraft.getInstance().getWindow().getWidth();
        int height = Minecraft.getInstance().getWindow().getHeight();
        GL11.glViewport(0, 0, width, height);
        Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
    }

    @Override
    public void flip() {

    }

    @Override
    public void recalculateSizes() {
        var newSize = sizeSupplier.get();
        if (currentSize.equals(newSize)) return;

        var previousSize = new Vector2i(currentSize);
        currentSize.set(newSize);
        for (FramebufferAttachment attachment : attachments)
            attachment.resize(newSize);

        setDrawBuffers();
        clearAttachments();

        var historyAttachments = attachments.stream()
                .filter(FramebufferAttachment::createPrevSampler)
                .map(FramebufferAttachment::name)
                .toList();
        if (!historyAttachments.isEmpty()) {
            Photonics.LOGGER.info(
                    "Photonics history reset v18: reason={}, buffer={}, attachments={}, size={}x{} -> {}x{}",
                    previousSize.x < 0 ? "initial-clear" : "viewport-resize",
                    diagnosticRole,
                    historyAttachments,
                    previousSize.x,
                    previousSize.y,
                    newSize.x(),
                    newSize.y()
            );
        }
    }

    @Override
    public void registerCustomTextures(ISamplerHolder samplers) {
        for (int i = 0; i < attachments.size(); i++) {
            var attachment = attachments.get(i);
            final int attachmentIndex = i;

            if (attachment.createSampler()) {
                samplers.addDefaultSampler(attachment.name(), () -> attachments.get(attachmentIndex)
                        .texture());
            }
        }
    }

    @Override
    public void close() {
        for (var attachment : attachments)
            attachment.close();
    }
}
