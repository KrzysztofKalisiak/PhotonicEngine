package at.redi2go.photonics.impl.mc.blaze3d.opengl;

import at.redi2go.photonics.api.gpu.textures.IAddressMode;
import at.redi2go.photonics.api.gpu.textures.IFilterMode;
import at.redi2go.photonics.api.gpu.textures.ITextureFormat;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

public final class GlEnums {
    private GlEnums() {
    }

    public static int addressMode(IAddressMode mode) {
        return mode == IAddressMode.Values.REPEAT ? GL11.GL_REPEAT : GL12.GL_CLAMP_TO_EDGE;
    }

    public static int filterMode(IFilterMode mode) {
        return mode == IFilterMode.Values.NEAREST ? GL11.GL_NEAREST : GL11.GL_LINEAR;
    }

    public static Format textureFormat(ITextureFormat format) {
        if (!(format instanceof ITextureFormat.Values value)) {
            return Format.RGBA8;
        }

        return switch (value) {
            case R8 -> new Format(GL30.GL_R8, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE);
            case RG8 -> new Format(GL30.GL_RG8, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE);
            case RGB8 -> new Format(GL11.GL_RGB8, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE);
            case RGBA, RGBA8 -> Format.RGBA8;
            case R16F -> new Format(GL30.GL_R16F, GL11.GL_RED, GL11.GL_FLOAT);
            case RG16F -> new Format(GL30.GL_RG16F, GL30.GL_RG, GL11.GL_FLOAT);
            case RGB16F -> new Format(GL30.GL_RGB16F, GL11.GL_RGB, GL11.GL_FLOAT);
            case RGBA16F -> new Format(GL30.GL_RGBA16F, GL11.GL_RGBA, GL11.GL_FLOAT);
            case R32F -> new Format(GL30.GL_R32F, GL11.GL_RED, GL11.GL_FLOAT);
            case RG32F -> new Format(GL30.GL_RG32F, GL30.GL_RG, GL11.GL_FLOAT);
            case RGB32F -> new Format(GL30.GL_RGB32F, GL11.GL_RGB, GL11.GL_FLOAT);
            case RGBA32F -> new Format(GL30.GL_RGBA32F, GL11.GL_RGBA, GL11.GL_FLOAT);
            case RGB32UI -> new Format(GL30.GL_RGB32UI, GL30.GL_RGB_INTEGER, GL11.GL_UNSIGNED_INT);
            case DEPTH32F -> new Format(GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT);
            default -> Format.RGBA8;
        };
    }

    public record Format(int internalFormat, int format, int type) {
        static final Format RGBA8 = new Format(GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE);
    }
}
