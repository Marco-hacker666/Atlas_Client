package atlas.ui.hud.list;

/**
 * Ready-made module lists (2026-11-14): each sets the shape of a {@link ListStyle} -- its backgrounds, bars,
 * corners, spacing, what the text is drawn in and the blur -- and leaves the colours, the order, the animation, the
 * glow and where the list sits as they are. A start to change from, not a mode: the settings a preset writes are ordinary settings.
 */
public final class ListPresets {

    /** CUSTOM first: what the choice shows once a setting was changed by hand. */
    public static final String[] NAMES = {"CUSTOM", "CLEAN", "SIDEBAR", "OUTLINE", "PILL", "UNDERLINE", "CARD",
            "GLASS", "SOLID", "CAP", "ROUNDED"};

    private ListPresets() {
    }

    /** Sets the style's shape to the preset's; false (and nothing changed) for CUSTOM or an unknown name. */
    public static boolean apply(String name, ListStyle s) {
        if (name == null || "CUSTOM".equals(name)) {
            return false;
        }
        /* What most of them share. */
        ListStyle.Text text = ListStyle.Text.COLOR;
        ListStyle.Suffix suffix = ListStyle.Suffix.GRAY;
        boolean shadow = true;
        float padX = 3.0F;
        float padY = 2.0F;
        float gap = 0.0F;
        ListStyle.Background background = ListStyle.Background.ROW;
        int backgroundColor = 0x0F0F0F;
        int opacity = 50;
        int tint = 0;
        ListStyle.Corners corners = ListStyle.Corners.NONE;
        float radius = 3.0F;
        ListStyle.Bar bar = ListStyle.Bar.NONE;
        float barWidth = 1.5F;
        boolean blur = false;
        switch (name) {
            case "CLEAN":
                /* Text and nothing else. */
                background = ListStyle.Background.NONE;
                padX = 2.0F;
                padY = 1.0F;
                break;
            case "SIDEBAR":
                bar = ListStyle.Bar.SIDE;
                opacity = 55;
                break;
            case "OUTLINE":
                bar = ListStyle.Bar.OUTLINE;
                barWidth = 1.0F;
                opacity = 45;
                break;
            case "PILL":
                corners = ListStyle.Corners.EACH;
                radius = 6.0F;
                gap = 2.0F;
                padX = 5.0F;
                padY = 2.5F;
                opacity = 60;
                break;
            case "UNDERLINE":
                background = ListStyle.Background.NONE;
                bar = ListStyle.Bar.UNDERLINE;
                barWidth = 1.0F;
                padX = 2.0F;
                padY = 1.5F;
                gap = 2.0F;
                break;
            case "CARD":
                background = ListStyle.Background.BLOCK;
                corners = ListStyle.Corners.OUTER;
                radius = 5.0F;
                padX = 5.0F;
                opacity = 55;
                break;
            case "GLASS":
                backgroundColor = 0xFFFFFF;
                opacity = 18;
                corners = ListStyle.Corners.OUTER;
                radius = 4.0F;
                padX = 4.0F;
                text = ListStyle.Text.WHITE;
                suffix = ListStyle.Suffix.DIM;
                bar = ListStyle.Bar.INNER;
                barWidth = 1.0F;
                blur = true;
                break;
            case "SOLID":
                /* Each row filled with its colour, the text dark on it. */
                opacity = 100;
                tint = 100;
                text = ListStyle.Text.DARK;
                suffix = ListStyle.Suffix.DIM;
                shadow = false;
                padX = 4.0F;
                break;
            case "CAP":
                bar = ListStyle.Bar.CAP;
                barWidth = 2.0F;
                opacity = 55;
                break;
            case "ROUNDED":
                corners = ListStyle.Corners.OUTER;
                radius = 4.0F;
                padX = 4.0F;
                opacity = 55;
                break;
            default:
                return false;
        }
        s.text = text;
        s.suffix = suffix;
        s.textShadow = shadow;
        s.padX = padX;
        s.padY = padY;
        s.gap = gap;
        s.background = background;
        s.backgroundColor = backgroundColor;
        s.backgroundOpacity = opacity;
        s.backgroundTint = tint;
        s.corners = corners;
        s.radius = radius;
        s.bar = bar;
        s.barWidth = barWidth;
        s.blur = blur;
        return true;
    }

    /**
     * The preset whose shape this style has, or CUSTOM: what the choice shows, worked out from the settings rather
     * than remembered, so it is right whichever order the settings were read or changed in.
     */
    public static String match(ListStyle style) {
        for (int i = 1; i < NAMES.length; i++) {
            ListStyle preset = new ListStyle();
            apply(NAMES[i], preset);
            if (sameShape(preset, style)) {
                return NAMES[i];
            }
        }
        return NAMES[0];
    }

    /** Whether two styles agree in everything a preset sets. */
    static boolean sameShape(ListStyle a, ListStyle b) {
        return a.text == b.text && a.suffix == b.suffix && a.textShadow == b.textShadow && near(a.padX, b.padX)
                && near(a.padY, b.padY) && near(a.gap, b.gap) && a.background == b.background
                && (a.backgroundColor & 0xFFFFFF) == (b.backgroundColor & 0xFFFFFF)
                && a.backgroundOpacity == b.backgroundOpacity && a.backgroundTint == b.backgroundTint
                && a.corners == b.corners && near(a.radius, b.radius) && a.bar == b.bar
                && near(a.barWidth, b.barWidth) && a.blur == b.blur;
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 0.01F;
    }
}
