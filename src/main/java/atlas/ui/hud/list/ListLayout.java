package atlas.ui.hud.list;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The module list laid out (2026-11-14): a style and the rows in, rectangles and text out. No drawing and no game:
 * the HUD module paints the frame with OpenGL, a test paints it into a picture, and both get the same list.
 *
 * (0, 0) is the corner the list is anchored to. The first row sits against it and the others follow, down for a
 * list anchored to the top and up for one anchored to the bottom; every row ends at the anchored side and grows
 * toward the free one. A side bar lies between the anchored edge and the rows; an inner bar lies past their free
 * ends.
 */
public final class ListLayout {
    /** How far a row slides while it fades in or out. */
    static final float SLIDE = 25.0F;
    static final int GRAY = 0xAAAAAA;
    static final int WHITE = 0xFFFFFF;
    static final int DARK = 0x14161A;
    /** Rows this close in width count as equal: no step between them. */
    private static final float SAME = 0.5F;

    private ListLayout() {
    }

    /** A row's height: its text and the space above and below. */
    public static float rowHeight(ListStyle style, float textHeight) {
        return textHeight + 2.0F * Math.max(0.0F, style.padY);
    }

    /**
     * @param rows       the rows to draw, each measured and with its appear and slot set ({@link ListAnim})
     * @param textHeight the height of a line of text
     * @param source     the colour of a row where the style's paint is HUD or THEME, which only the game knows;
     *                   may be null (colour 1 then)
     */
    public static ListFrame frame(ListStyle style, List<ListRow> rows, float textHeight, long timeMs,
                                  ListColors.Source source) {
        ListFrame frame = new ListFrame();
        final int count = rows.size();
        if (count == 0) {
            return frame;
        }
        /* The free direction: the way the rows grow from the anchored edge. */
        final float sx = style.right ? -1.0F : 1.0F;
        float padX = Math.max(0.0F, style.padX);
        float padY = Math.max(0.0F, style.padY);
        float gap = Math.max(0.0F, style.gap);
        float rowH = rowHeight(style, textHeight);
        float step = rowH + gap;
        float barW = style.bar == ListStyle.Bar.NONE ? 0.0F : Math.max(0.5F, style.barWidth);
        boolean sideBar = style.bar == ListStyle.Bar.SIDE;
        boolean innerBar = style.bar == ListStyle.Bar.INNER;
        boolean block = style.background == ListStyle.Background.BLOCK;
        boolean detached = gap > SAME;
        boolean slides = style.anim == ListStyle.Anim.SLIDE || style.anim == ListStyle.Anim.SLIDE_FADE;
        boolean fades = style.anim == ListStyle.Anim.FADE || style.anim == ListStyle.Anim.SLIDE_FADE;
        /* Where the rows' bodies start: past the side bar, when there is one. */
        float edge = sideBar ? sx * barW : 0.0F;

        final float[] width = new float[count];
        float widest = 0.0F;
        for (int i = 0; i < count; i++) {
            width[i] = padX * 2.0F + rows.get(i).textWidth();
            widest = Math.max(widest, width[i]);
        }
        if (block) {
            for (int i = 0; i < count; i++) {
                width[i] = widest;
            }
        }
        final float[] y1 = new float[count];
        float[] y2 = new float[count];
        float[] shift = new float[count];
        float[] alpha = new float[count];
        int[] rgb = new int[count];
        for (int i = 0; i < count; i++) {
            ListRow row = rows.get(i);
            float appear = Math.max(0.0F, Math.min(1.0F, row.appear));
            float eased = ListAnim.ease(appear);
            /* Out past the anchored edge: the way a row comes from and goes to. */
            float reach = style.anim == ListStyle.Anim.SLIDE ? width[i] + barW + 4.0F : SLIDE;
            shift[i] = slides ? -sx * (1.0F - eased) * reach : 0.0F;
            alpha[i] = fades ? appear : 1.0F;
            float top = row.slot * step;
            if (style.bottom) {
                y2[i] = -top;
                y1[i] = y2[i] - rowH;
            } else {
                y1[i] = top;
                y2[i] = top + rowH;
            }
            boolean outside = style.paint == ListStyle.Paint.HUD || style.paint == ListStyle.Paint.THEME;
            rgb[i] = outside && source != null ? source.color(i) & 0xFFFFFF
                    : ListColors.color(style, timeMs, i, row.name, row.category);
        }
        /* The rows from the top of the screen down, to know each one's neighbours. */
        List<Integer> down = new ArrayList<Integer>();
        for (int i = 0; i < count; i++) {
            down.add(i);
        }
        Collections.sort(down, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Float.compare(y1[a], y1[b]);
            }
        });
        int[] above = new int[count];
        int[] below = new int[count];
        for (int k = 0; k < count; k++) {
            int i = down.get(k);
            above[i] = k > 0 ? down.get(k - 1) : -1;
            below[i] = k < count - 1 ? down.get(k + 1) : -1;
        }

        float radius = style.corners == ListStyle.Corners.NONE ? 0.0F
                : Math.max(0.0F, Math.min(style.radius, rowH / 2.0F));

        /* Each row's area, for the glow. */
        for (int i = 0; i < count; i++) {
            float a = edge + shift[i];
            float b = a + sx * width[i];
            frame.body(Math.min(a, b), y1[i], Math.max(a, b), y2[i], radius,
                    style.corners == ListStyle.Corners.NONE ? 0 : ListPainter.ALL, ListColors.argb(rgb[i], alpha[i]));
        }

        /* The backgrounds. */
        if (block) {
            float top = y1[down.get(0)];
            float bottom = y2[down.get(count - 1)];
            float a = edge;
            float b = edge + sx * widest;
            int base = ListColors.mix(style.backgroundColor, rgb[0], style.backgroundTint / 100.0F);
            /* As there as the most present row: whole while one is, fading out with the last one to go, slid or
               faded (review, PR #42). */
            float present = 0.0F;
            for (ListRow row : rows) {
                present = Math.max(present, Math.max(0.0F, Math.min(1.0F, row.appear)));
            }
            float blockRadius = style.corners == ListStyle.Corners.NONE ? 0.0F
                    : Math.max(0.0F, Math.min(style.radius, Math.min(widest, bottom - top) / 2.0F));
            int corners = ListPainter.ALL;
            if (sideBar) {
                /* The bar lies against that side: it stays square. */
                corners &= ~(style.right ? ListPainter.TOP_RIGHT | ListPainter.BOTTOM_RIGHT
                        : ListPainter.TOP_LEFT | ListPainter.BOTTOM_LEFT);
            }
            frame.rect(Math.min(a, b), top, Math.max(a, b), bottom, blockRadius, corners,
                    ListColors.argb(base, style.backgroundOpacity / 100.0F * present), true);
        } else if (style.background == ListStyle.Background.ROW) {
            for (int i = 0; i < count; i++) {
                float a = edge + shift[i];
                float b = a + sx * width[i];
                int corners = 0;
                if (style.corners == ListStyle.Corners.EACH) {
                    corners = ListPainter.ALL;
                } else if (style.corners == ListStyle.Corners.OUTER) {
                    boolean first = above[i] < 0 || detached;
                    boolean last = below[i] < 0 || detached;
                    boolean freeTop = !innerBar && (first || width[i] > width[above[i]] + SAME);
                    boolean freeBottom = !innerBar && (last || width[i] > width[below[i]] + SAME);
                    boolean anchorTop = !sideBar && first;
                    boolean anchorBottom = !sideBar && last;
                    if (style.right) {
                        corners = (freeTop ? ListPainter.TOP_LEFT : 0) | (freeBottom ? ListPainter.BOTTOM_LEFT : 0)
                                | (anchorTop ? ListPainter.TOP_RIGHT : 0) | (anchorBottom ? ListPainter.BOTTOM_RIGHT : 0);
                    } else {
                        corners = (freeTop ? ListPainter.TOP_RIGHT : 0) | (freeBottom ? ListPainter.BOTTOM_RIGHT : 0)
                                | (anchorTop ? ListPainter.TOP_LEFT : 0) | (anchorBottom ? ListPainter.BOTTOM_LEFT : 0);
                    }
                }
                int base = ListColors.mix(style.backgroundColor, rgb[i], style.backgroundTint / 100.0F);
                frame.rect(Math.min(a, b), y1[i], Math.max(a, b), y2[i], radius, corners,
                        ListColors.argb(base, style.backgroundOpacity / 100.0F * alpha[i]), true);
            }
        }

        /* The bars. */
        boolean pill = style.corners == ListStyle.Corners.EACH || detached;
        for (int i = 0; i < count && barW > 0.0F; i++) {
            int colour = ListColors.argb(rgb[i], alpha[i]);
            float a = edge + shift[i];
            float b = a + sx * width[i];
            float left = Math.min(a, b);
            float right = Math.max(a, b);
            switch (style.bar) {
                case SIDE: {
                    float outer = shift[i];
                    frame.rect(Math.min(outer, a), y1[i], Math.max(outer, a), y2[i],
                            pill && style.corners != ListStyle.Corners.NONE ? barW / 2.0F : 0.0F, ListPainter.ALL,
                            colour, false);
                    break;
                }
                case INNER: {
                    float outer = b + sx * barW;
                    frame.rect(Math.min(outer, b), y1[i], Math.max(outer, b), y2[i],
                            pill && style.corners != ListStyle.Corners.NONE ? barW / 2.0F : 0.0F, ListPainter.ALL,
                            colour, false);
                    break;
                }
                case CAP:
                    /* Along the outer edge of the row the list starts with. */
                    if (i == 0) {
                        if (style.bottom) {
                            frame.rect(left, y2[i], right, y2[i] + barW, 0.0F, 0, colour, false);
                        } else {
                            frame.rect(left, y1[i] - barW, right, y1[i], 0.0F, 0, colour, false);
                        }
                    }
                    break;
                case UNDERLINE:
                    /* Just under the row, clear of the letters' tails; rows that touch get it on their last line. */
                    if (detached) {
                        frame.rect(left, y2[i], right, y2[i] + Math.min(barW, gap), 0.0F, 0, colour, false);
                    } else {
                        frame.rect(left, y2[i] - barW, right, y2[i], 0.0F, 0, colour, false);
                    }
                    break;
                case OUTLINE:
                    outline(frame, i, left, right, y1[i], y2[i], barW, colour, detached, above, below, width, sx,
                            edge + shift[i], rgb, alpha);
                    break;
                default:
                    break;
            }
        }

        /* The text, last: over everything. */
        for (int i = 0; i < count; i++) {
            ListRow row = rows.get(i);
            int nameRgb = style.text == ListStyle.Text.WHITE ? WHITE : style.text == ListStyle.Text.DARK ? DARK : rgb[i];
            int suffixRgb;
            float suffixAlpha = alpha[i];
            switch (style.suffix) {
                case WHITE:
                    suffixRgb = WHITE;
                    break;
                case COLOR:
                    suffixRgb = rgb[i];
                    break;
                case DIM:
                    suffixRgb = nameRgb;
                    suffixAlpha *= 0.6F;
                    break;
                case GRAY:
                default:
                    suffixRgb = GRAY;
                    break;
            }
            float a = edge + shift[i];
            float x = style.right ? a - padX - row.textWidth() : a + padX;
            float y = y1[i] + padY;
            /* A shadow under dark text only smears it. */
            boolean shadow = style.textShadow && style.text != ListStyle.Text.DARK;
            frame.text(row.name, x, y, row.nameWidth, textHeight, textArgb(nameRgb, alpha[i]), shadow);
            if (!row.suffix.isEmpty()) {
                frame.text(row.suffix, x + row.nameWidth, y, row.suffixWidth, textHeight,
                        textArgb(suffixRgb, suffixAlpha), shadow && style.suffix != ListStyle.Suffix.DIM
                                || shadow && style.text != ListStyle.Text.DARK);
            }
        }
        return frame;
    }

    /**
     * The outline's pieces for one row. Rows apart get a box each. Rows that touch share one outline: each row
     * gives the two upright pieces at its ends, the first and last close the top and bottom, and where two
     * neighbours differ in width the wider one gives the piece along the step.
     */
    private static void outline(ListFrame frame, int i, float left, float right, float top, float bottom, float line,
                                int colour, boolean detached, int[] above, int[] below, float[] width, float sx,
                                float start, int[] rgb, float[] alpha) {
        frame.rect(left - line, top, left, bottom, 0.0F, 0, colour, false);
        frame.rect(right, top, right + line, bottom, 0.0F, 0, colour, false);
        if (detached || above[i] < 0) {
            frame.rect(left - line, top - line, right + line, top, 0.0F, 0, colour, false);
        } else if (width[i] > width[above[i]] + SAME) {
            step(frame, start, sx, width[above[i]], width[i], line, top - line, top, colour);
        }
        if (detached || below[i] < 0) {
            frame.rect(left - line, bottom, right + line, bottom + line, 0.0F, 0, colour, false);
        } else if (width[i] > width[below[i]] + SAME) {
            step(frame, start, sx, width[below[i]], width[i], line, bottom, bottom + line, colour);
        }
    }

    /** The piece along a step: from the narrower neighbour's end to the wider row's, the corner included. */
    private static void step(ListFrame frame, float start, float sx, float narrow, float wide, float line, float y1,
                             float y2, int colour) {
        float a = start + sx * narrow;
        float b = start + sx * (wide + line);
        frame.rect(Math.min(a, b), y1, Math.max(a, b), y2, 0.0F, 0, colour, false);
    }

    /** Text never below alpha 4: the game's font reads 0 as fully opaque. */
    static int textArgb(int rgb, float alpha) {
        int a = Math.max(4, Math.min(255, Math.round(alpha * 255.0F)));
        return (a << 24) | (rgb & 0xFFFFFF);
    }
}
