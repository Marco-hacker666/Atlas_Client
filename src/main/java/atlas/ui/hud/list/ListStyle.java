package atlas.ui.hud.list;

/**
 * Everything about how the module list looks, as plain numbers. A new one is the SIDEBAR preset's shape (2026-11-14, owner: "different module lists, as
 * customisable as it gets"). The HUD module fills one of these from its settings each frame; {@link ListLayout}
 * turns it and the rows into rectangles and text; a {@link ListPainter} draws those. Nothing here knows the game,
 * so a look can be drawn to a picture and checked without it.
 */
public final class ListStyle {

    /** What a module's name is drawn in. */
    public enum Text {
        /** The row's colour. */
        COLOR,
        WHITE,
        /** Near black, for rows filled with their colour. */
        DARK
    }

    /** What a module's tag (its mode, after the name) is drawn in. */
    public enum Suffix {
        GRAY,
        WHITE,
        /** The row's colour. */
        COLOR,
        /** The name's colour, fainter. */
        DIM
    }

    public enum Background {
        NONE,
        /** A box behind each row, as wide as the row's text. */
        ROW,
        /** One box behind the whole list, as wide as its widest row. */
        BLOCK
    }

    public enum Corners {
        NONE,
        /** Only the corners that stick out of the list's outline. */
        OUTER,
        /** Every row all round. */
        EACH
    }

    public enum Bar {
        NONE,
        /** Down the edge the list is anchored to. */
        SIDE,
        /** Down the rows' free ends. */
        INNER,
        /** Along the first row's outer edge. */
        CAP,
        /** Under each row. */
        UNDERLINE,
        /** Round the whole list. */
        OUTLINE
    }

    /** Where a row's colour comes from. */
    public enum Paint {
        /** The HUD's own colour for the line (its colour mode, the menu accent, the Theme tab's Interface group). */
        HUD,
        /** The Theme tab's own colours (the Theme module), whatever the HUD's are. */
        THEME,
        STATIC,
        /** Colour 1 to colour 2 and back, travelling down the list. */
        GRADIENT,
        /** Colours 1, 2, 3 and back. */
        TRIPLE,
        /** Colours 1 to 4 in a loop. */
        PALETTE,
        RAINBOW,
        /** The soft pink-to-blue half of the rainbow. */
        ASTOLFO,
        /** The whole list between colour 1 and colour 2 together. */
        PULSE,
        /** Colour 1, darker and brighter in a wave. */
        FADE,
        /** By the module's category. */
        CATEGORY,
        /** A colour of its own for each module, always the same one. */
        RANDOM
    }

    public enum Sort {
        /** Widest first. */
        WIDTH,
        /** Narrowest first. */
        WIDTH_REVERSED,
        ALPHABET,
        /** By category, widest first within one. */
        CATEGORY,
        /** As the modules are registered. */
        NONE
    }

    /** How a row comes and goes. */
    public enum Anim {
        NONE,
        SLIDE,
        FADE,
        SLIDE_FADE
    }

    /** Anchored to the right edge: the rows end there and grow to the left. */
    public boolean right = true;
    /** Anchored to the bottom: the first row is the lowest and the list grows up. */
    public boolean bottom;

    public Text text = Text.COLOR;
    public Suffix suffix = Suffix.GRAY;
    public boolean textShadow = true;

    /** Space left and right of a row's text. */
    public float padX = 3.0F;
    /** Space above and below it. */
    public float padY = 2.0F;
    /** Space between two rows. */
    public float gap;

    public Background background = Background.ROW;
    /** RGB. */
    public int backgroundColor = 0x0F0F0F;
    /** 0 to 100. */
    public int backgroundOpacity = 55;
    /** How much of the row's colour is mixed into its background, 0 to 100. */
    public int backgroundTint;

    public Corners corners = Corners.NONE;
    public float radius = 3.0F;

    public Bar bar = Bar.SIDE;
    public float barWidth = 1.5F;

    public Paint paint = Paint.HUD;
    /** RGB; how many are used depends on {@link #paint}. */
    public final int[] colors = {0x55CDFC, 0xF7A8B8, 0xFFFFFF, 0x9B6BFF};
    /** Cycles in four seconds at 1; 0 stands still; negative runs up the list. */
    public float speed = 1.0F;
    /** How far apart in the cycle two neighbouring rows are, 0 to 100 (100 is a fifth of a cycle). */
    public int spread = 50;
    /** 0 to 100. */
    public int saturation = 100;
    /** 0 to 100. */
    public int brightness = 100;

    public Sort sort = Sort.WIDTH;
    public Anim anim = Anim.SLIDE_FADE;
    /** Seconds a row takes to come or go. */
    public float animTime = 0.2F;

    /** Drawn only in game: the screen behind the rows' backgrounds blurred. Part of a preset's shape. */
    public boolean blur;
    /** Drawn only in game: a soft glow in each row's colour. An effect on top of any shape: no preset sets it. */
    public boolean glow;

    /** The names of an enum's values, for a setting's choices. */
    public static String[] names(Enum<?>[] values) {
        String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            names[i] = values[i].name();
        }
        return names;
    }

    /** The value at {@code index}, or the first when a saved index is past the end. */
    public static <E extends Enum<E>> E pick(E[] values, int index) {
        return values[index >= 0 && index < values.length ? index : 0];
    }
}
