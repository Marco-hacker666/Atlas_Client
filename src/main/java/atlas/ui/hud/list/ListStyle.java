package atlas.ui.hud.list;

public final class ListStyle {
    public enum Text {
        COLOR,
        WHITE,

        DARK
    }

    public enum Suffix {
        GRAY,
        WHITE,

        COLOR,

        DIM
    }

    public enum Background {
        NONE,

        ROW,

        BLOCK
    }

    public enum Corners {
        NONE,

        OUTER,

        EACH
    }

    public enum Bar {
        NONE,

        SIDE,

        INNER,

        CAP,

        UNDERLINE,

        OUTLINE
    }

    public enum Paint {
        HUD,

        THEME,
        STATIC,

        GRADIENT,

        TRIPLE,

        PALETTE,
        RAINBOW,

        ASTOLFO,

        PULSE,

        FADE,

        CATEGORY,

        RANDOM
    }

    public enum Sort {
        WIDTH,

        WIDTH_REVERSED,
        ALPHABET,

        CATEGORY,

        NONE
    }

    public enum Anim {
        NONE,
        SLIDE,
        FADE,
        SLIDE_FADE
    }

    public boolean right = true;

    public boolean bottom;

    public Text text = Text.COLOR;
    public Suffix suffix = Suffix.GRAY;
    public boolean textShadow = true;

    public float padX = 3.0F;

    public float padY = 2.0F;

    public float gap;

    public Background background = Background.ROW;

    public int backgroundColor = 0x0F0F0F;

    public int backgroundOpacity = 55;

    public int backgroundTint;

    public Corners corners = Corners.NONE;
    public float radius = 3.0F;

    public Bar bar = Bar.SIDE;
    public float barWidth = 1.5F;

    public Paint paint = Paint.HUD;

    public final int[] colors = {0x55CDFC, 0xF7A8B8, 0xFFFFFF, 0x9B6BFF};

    public float speed = 1.0F;

    public int spread = 50;

    public int saturation = 100;

    public int brightness = 100;

    public Sort sort = Sort.WIDTH;
    public Anim anim = Anim.SLIDE_FADE;

    public float animTime = 0.2F;

    public boolean blur;

    public boolean glow;

    public static String[] names(Enum<?>[] values) {
        String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            names[i] = values[i].name();
        }
        return names;
    }

    public static <E extends Enum<E>> E pick(E[] values, int index) {
        return values[index >= 0 && index < values.length ? index : 0];
    }
}
