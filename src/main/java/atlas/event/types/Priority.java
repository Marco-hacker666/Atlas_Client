package atlas.event.types;

/**
 * The priority for the dispatcher to determine what method should be invoked first.
 * Ram was talking about the memory usage of the way I store the data so I decided
 * to just use bytes for the priority because they take up only 8 bits of memory
 * per value compared to the 32 bits per value of an enum (Same as an integer).
 *
 * @author DarkMagician6
 * @since August 3, 2013
 */
public final class Priority {
    public static final byte

            HIGHEST = 0,

    HIGH = 1,

    MEDIUM = 2,

    LOW = 3,

    LOWEST = 4;

    public static final byte[] VALUE_ARRAY;

    static {
        VALUE_ARRAY = new byte[]{
                HIGHEST,
                HIGH,
                MEDIUM,
                LOW,
                LOWEST
        };
    }
}
