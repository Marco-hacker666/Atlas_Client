package atlas.module.modules;

final class TellyBridge {
    private TellyBridge() {
    }

    static boolean placing(boolean onGround, double motionY, boolean walking) {
        if (!walking) {
            return true;
        }
        return !onGround && motionY < 0.0;
    }
}
