package at.redi2go.photonics.core.rendering.world.block;

import org.joml.Vector3f;
import org.joml.Vector3fc;

public class VoxelNormal {
    private static final Vector3fc[] INDEX_TO_NORMAL = new Vector3f[6];

    public static int getIndex(Vector3fc normal) {
        float x = normal.x();
        float y = normal.y();
        float z = normal.z();

        float absX = Math.abs(x);
        float absY = Math.abs(y);
        float absZ = Math.abs(z);

        if (absX >= absY && absX >= absZ)
            return x < 0.0f ? 0 : 1;
        if (absY >= absZ)
            return y < 0.0f ? 2 : 3;
        return z < 0.0f ? 4 : 5;
    }

    public static Vector3fc getNormal(int index) {
        return INDEX_TO_NORMAL[index];
    }

    private static void putNormal(float x, float y, float z) {
        Vector3f normal = new Vector3f(x, y, z);
        INDEX_TO_NORMAL[getIndex(normal)] = normal;
    }

    static {
        putNormal(0, -1, 0);
        putNormal(0, 1, 0);
        putNormal(0, 0, -1);
        putNormal(0, 0, 1);
        putNormal(-1, 0, 0);
        putNormal(1, 0, 0);
    }
}
