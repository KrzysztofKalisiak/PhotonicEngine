package at.redi2go.photonics.api.gpu.textures;

public interface IFilterMode {
    static IFilterMode nearest() {
        return Values.NEAREST;
    }

    static IFilterMode linear() {
        return Values.LINEAR;
    }

    enum Values implements IFilterMode {
        NEAREST,
        LINEAR
    }
}
