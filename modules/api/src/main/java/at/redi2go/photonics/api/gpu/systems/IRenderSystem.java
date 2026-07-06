package at.redi2go.photonics.api.gpu.systems;

public interface IRenderSystem {
    DeviceHolder HOLDER = new DeviceHolder();

    static IGpuDevice getDevice() {
        if (HOLDER.device == null) {
            throw new IllegalStateException("No Photonics GPU device has been installed");
        }

        return HOLDER.device;
    }

    static void setDevice(IGpuDevice device) {
        HOLDER.device = device;
    }

    final class DeviceHolder {
        private IGpuDevice device;
    }
}
