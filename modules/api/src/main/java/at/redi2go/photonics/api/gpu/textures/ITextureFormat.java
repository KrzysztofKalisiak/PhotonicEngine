package at.redi2go.photonics.api.gpu.textures;

public interface ITextureFormat {
    static ITextureFormat rgba() { return Values.RGBA; }
    static ITextureFormat r8() { return Values.R8; }
    static ITextureFormat rg8() { return Values.RG8; }
    static ITextureFormat rgb8() { return Values.RGB8; }
    static ITextureFormat rgba8() { return Values.RGBA8; }
    static ITextureFormat r8Snorm() { return Values.R8_SNORM; }
    static ITextureFormat rg8Snorm() { return Values.RG8_SNORM; }
    static ITextureFormat rgb8Snorm() { return Values.RGB8_SNORM; }
    static ITextureFormat rgba8Snorm() { return Values.RGBA8_SNORM; }
    static ITextureFormat r16() { return Values.R16; }
    static ITextureFormat rg16() { return Values.RG16; }
    static ITextureFormat rgb16() { return Values.RGB16; }
    static ITextureFormat rgba16() { return Values.RGBA16; }
    static ITextureFormat r16Snorm() { return Values.R16_SNORM; }
    static ITextureFormat rg16Snorm() { return Values.RG16_SNORM; }
    static ITextureFormat rgb16Snorm() { return Values.RGB16_SNORM; }
    static ITextureFormat rgba16Snorm() { return Values.RGBA16_SNORM; }
    static ITextureFormat r16f() { return Values.R16F; }
    static ITextureFormat rg16f() { return Values.RG16F; }
    static ITextureFormat rgb16f() { return Values.RGB16F; }
    static ITextureFormat rgba16f() { return Values.RGBA16F; }
    static ITextureFormat r32f() { return Values.R32F; }
    static ITextureFormat rg32f() { return Values.RG32F; }
    static ITextureFormat rgb32f() { return Values.RGB32F; }
    static ITextureFormat rgba32f() { return Values.RGBA32F; }
    static ITextureFormat r8i() { return Values.R8I; }
    static ITextureFormat rg8i() { return Values.RG8I; }
    static ITextureFormat rgb8i() { return Values.RGB8I; }
    static ITextureFormat rgba8i() { return Values.RGBA8I; }
    static ITextureFormat r8ui() { return Values.R8UI; }
    static ITextureFormat rg8ui() { return Values.RG8UI; }
    static ITextureFormat rgb8ui() { return Values.RGB8UI; }
    static ITextureFormat rgba8ui() { return Values.RGBA8UI; }
    static ITextureFormat r16i() { return Values.R16I; }
    static ITextureFormat rg16i() { return Values.RG16I; }
    static ITextureFormat rgb16i() { return Values.RGB16I; }
    static ITextureFormat rgba16i() { return Values.RGBA16I; }
    static ITextureFormat r16ui() { return Values.R16UI; }
    static ITextureFormat rg16ui() { return Values.RG16UI; }
    static ITextureFormat rgb16ui() { return Values.RGB16UI; }
    static ITextureFormat rgba16ui() { return Values.RGBA16UI; }
    static ITextureFormat r32i() { return Values.R32I; }
    static ITextureFormat rg32i() { return Values.RG32I; }
    static ITextureFormat rgb32i() { return Values.RGB32I; }
    static ITextureFormat rgba32i() { return Values.RGBA32I; }
    static ITextureFormat r32ui() { return Values.R32UI; }
    static ITextureFormat rg32ui() { return Values.RG32UI; }
    static ITextureFormat rgb32ui() { return Values.RGB32UI; }
    static ITextureFormat rgba32ui() { return Values.RGBA32UI; }
    static ITextureFormat rgba2() { return Values.RGBA2; }
    static ITextureFormat rgba4() { return Values.RGBA4; }
    static ITextureFormat r3g3b2() { return Values.R3G3B2; }
    static ITextureFormat rgb5a1() { return Values.RGB5A1; }
    static ITextureFormat rgb565() { return Values.RGB565; }
    static ITextureFormat rgb10a2() { return Values.RGB10A2; }
    static ITextureFormat rgb10A2ui() { return Values.RGB10_A2UI; }
    static ITextureFormat r11fg11fb10f() { return Values.R11F_G11F_B10F; }
    static ITextureFormat rgb9e5() { return Values.RGB9_E5; }
    static ITextureFormat depth32() { return Values.DEPTH32F; }

    enum Values implements ITextureFormat {
        RGBA, R8, RG8, RGB8, RGBA8,
        R8_SNORM, RG8_SNORM, RGB8_SNORM, RGBA8_SNORM,
        R16, RG16, RGB16, RGBA16,
        R16_SNORM, RG16_SNORM, RGB16_SNORM, RGBA16_SNORM,
        R16F, RG16F, RGB16F, RGBA16F,
        R32F, RG32F, RGB32F, RGBA32F,
        R8I, RG8I, RGB8I, RGBA8I,
        R8UI, RG8UI, RGB8UI, RGBA8UI,
        R16I, RG16I, RGB16I, RGBA16I,
        R16UI, RG16UI, RGB16UI, RGBA16UI,
        R32I, RG32I, RGB32I, RGBA32I,
        R32UI, RG32UI, RGB32UI, RGBA32UI,
        RGBA2, RGBA4, R3G3B2, RGB5A1, RGB565,
        RGB10A2, RGB10_A2UI, R11F_G11F_B10F, RGB9_E5,
        DEPTH32F
    }
}
