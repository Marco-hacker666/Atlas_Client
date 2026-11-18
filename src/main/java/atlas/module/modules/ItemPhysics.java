package atlas.module.modules;

import atlas.module.Module;
import atlas.property.properties.FloatProperty;

import java.util.Random;

public class ItemPhysics extends Module {
    public static ItemPhysics instance;

    public final FloatProperty rotationSpeed = new FloatProperty("rotation-speed", 1.0F, 0.0F, 5.0F);

    public static final Random STILL = new StillRandom();

    public ItemPhysics() {
        super("ItemPhysics", false);
    }

    @Override
    public void onEnabled() {
        instance = this;
    }

    @Override
    public void onDisabled() {
        instance = null;
    }

    public float getRotationSpeed() {
        return this.rotationSpeed.getValue();
    }

    private static final class StillRandom extends Random {
        private static final long serialVersionUID = 1L;

        @Override
        public float nextFloat() {
            return 0.5F;
        }
    }
}
