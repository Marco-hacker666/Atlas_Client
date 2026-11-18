package atlas.property;

import atlas.property.properties.IntProperty;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;

public final class IntRange implements PropertyGroup {
    private static final Random RANDOM = new Random();

    private final IntProperty min;
    private final IntProperty max;

    private final boolean owned;

    public IntRange(String name, int min, int max, int lowest, int highest) {
        this(name, min, max, lowest, highest, null);
    }

    public IntRange(String name, int min, int max, int lowest, int highest, BooleanSupplier visible) {
        this(new IntProperty(name + "-min", min, lowest, highest, visible),
                new IntProperty(name + "-max", max, lowest, highest, visible), true);
    }

    private IntRange(IntProperty min, IntProperty max, boolean owned) {
        this.min = min;
        this.max = max;
        this.owned = owned;
    }

    public static IntRange of(IntProperty min, IntProperty max) {
        return new IntRange(min, max, false);
    }

    @Override
    public List<Property<?>> properties() {
        return this.owned ? Arrays.<Property<?>>asList(this.min, this.max) : Collections.<Property<?>>emptyList();
    }

    public int low() {
        return Math.min(this.min.getValue(), this.max.getValue());
    }

    public int high() {
        return Math.max(this.min.getValue(), this.max.getValue());
    }

    public int random() {
        return random(RANDOM);
    }

    public int random(Random random) {
        int low = low();
        int high = high();
        return low == high ? low : low + random.nextInt(high - low + 1);
    }

    public boolean contains(int value) {
        return value >= low() && value <= high();
    }

    public IntProperty minProperty() {
        return this.min;
    }

    public IntProperty maxProperty() {
        return this.max;
    }

    public String describe() {
        return low() == high() ? String.valueOf(low()) : low() + "-" + high();
    }
}
