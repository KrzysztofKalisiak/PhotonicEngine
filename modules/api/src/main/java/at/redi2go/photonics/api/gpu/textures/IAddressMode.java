package at.redi2go.photonics.api.gpu.textures;

public interface IAddressMode {
    static IAddressMode repeat() {
        return Values.REPEAT;
    }

    static IAddressMode clampToEdge() {
        return Values.CLAMP_TO_EDGE;
    }

    enum Values implements IAddressMode {
        REPEAT,
        CLAMP_TO_EDGE
    }
}
