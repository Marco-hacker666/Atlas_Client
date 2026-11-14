package atlas.ui.hud.list;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws module lists into pictures, with no game (2026-11-14): the way to look at a style before anyone starts the
 * client. Not a test -- run its main with an output folder and, optionally, a font file. The layout and the colours
 * are the game's own ({@link ListLayout}, {@link ListColors}); the font's shapes, the blur and the glow are not.
 */
public final class ListPreview {
    /** Picture pixels to one unit of the list: about a 4x GUI scale. */
    static final float ZOOM = 4.0F;
    static final float TEXT_HEIGHT = 8.0F;

    static final String[][] MODULES = {
            {"KillAura", "Switch", "0"}, {"Velocity", "Hypixel", "0"}, {"Scaffold", "GodBridge", "1"},
            {"BackTrack", "120ms", "0"}, {"InvManager", "", "1"}, {"ChestStealer", "", "1"}, {"NoSlow", "Blink", "2"},
            {"AutoClicker", "12", "0"}, {"Sprint", "", "2"}, {"FullBright", "", "3"}, {"ESP", "Box", "3"},
            {"Timer", "", "5"}};

    /** Java2D behind {@link ListPainter}: (0, 0) of the list at {@code originX}, {@code originY} of the picture. */
    static final class Canvas implements ListPainter {
        private final Graphics2D g;
        private final float originX;
        private final float originY;
        private final Font font;

        Canvas(Graphics2D g, float originX, float originY, Font font) {
            this.g = g;
            this.originX = originX;
            this.originY = originY;
            this.font = font;
        }

        @Override
        public void rect(float x1, float y1, float x2, float y2, float radius, int corners, int argb) {
            float left = this.originX + x1 * ZOOM;
            float top = this.originY + y1 * ZOOM;
            float right = this.originX + x2 * ZOOM;
            float bottom = this.originY + y2 * ZOOM;
            float r = Math.min(radius * ZOOM, Math.min(right - left, bottom - top) / 2.0F);
            float tl = (corners & TOP_LEFT) != 0 ? r : 0.0F;
            float tr = (corners & TOP_RIGHT) != 0 ? r : 0.0F;
            float bl = (corners & BOTTOM_LEFT) != 0 ? r : 0.0F;
            float br = (corners & BOTTOM_RIGHT) != 0 ? r : 0.0F;
            Path2D.Float path = new Path2D.Float();
            path.moveTo(left + tl, top);
            path.lineTo(right - tr, top);
            path.quadTo(right, top, right, top + tr);
            path.lineTo(right, bottom - br);
            path.quadTo(right, bottom, right - br, bottom);
            path.lineTo(left + bl, bottom);
            path.quadTo(left, bottom, left, bottom - bl);
            path.lineTo(left, top + tl);
            path.quadTo(left, top, left + tl, top);
            path.closePath();
            this.g.setColor(new Color(argb, true));
            this.g.fill(path);
        }

        @Override
        public void text(String text, float x, float y, int argb, boolean shadow) {
            this.g.setFont(this.font);
            FontMetrics metrics = this.g.getFontMetrics();
            float px = this.originX + x * ZOOM;
            /* The line's box is TEXT_HEIGHT high; the letters sit in its middle. */
            float py = this.originY + y * ZOOM + (TEXT_HEIGHT * ZOOM - metrics.getHeight()) / 2.0F
                    + metrics.getAscent();
            if (shadow) {
                this.g.setColor(new Color(0, 0, 0, Math.min(255, ((argb >>> 24) * 150) / 255)));
                this.g.drawString(text, px + ZOOM * 0.5F, py + ZOOM * 0.5F);
            }
            this.g.setColor(new Color(argb, true));
            this.g.drawString(text, px, py);
        }
    }

    /** Something like a game behind the list: sky, hills, ground, so translucent rows have something to show. */
    static void scene(Graphics2D g, int x, int y, int w, int h) {
        g.setPaint(new GradientPaint(0, y, new Color(0x78A7FF), 0, y + h * 0.6F, new Color(0xCFE3FF)));
        g.fillRect(x, y, w, h);
        g.setColor(new Color(0x6FA85A));
        g.fillRect(x, y + (int) (h * 0.62F), w, h);
        g.setColor(new Color(0x4F8A45));
        for (int i = 0; i < 6; i++) {
            g.fillRect(x + i * w / 5 - 20, y + (int) (h * (0.5F + (i % 3) * 0.05F)), w / 7, h);
        }
        g.setColor(new Color(0x8A6B4A));
        g.fillRect(x, y + (int) (h * 0.86F), w, h);
        g.setColor(new Color(255, 255, 255, 235));
        g.fillRoundRect(x + w / 9, y + h / 9, w / 4, h / 12, 18, 18);
    }

    static List<ListRow> rows(Graphics2D g, Font font, ListStyle style, int howMany, boolean suffixes) {
        FontMetrics metrics = g.getFontMetrics(font);
        List<ListRow> rows = new ArrayList<ListRow>();
        for (int i = 0; i < Math.min(howMany, MODULES.length); i++) {
            String name = MODULES[i][0];
            String suffix = suffixes && !MODULES[i][1].isEmpty() ? " " + MODULES[i][1] : "";
            rows.add(new ListRow(name, name, suffix, Integer.parseInt(MODULES[i][2]),
                    metrics.stringWidth(name) / ZOOM, metrics.stringWidth(suffix) / ZOOM));
        }
        rows = ListRow.sorted(rows, style.sort);
        new ListAnim().step(rows, noAnimation(style), 0.0F);
        return rows;
    }

    private static ListStyle noAnimation(ListStyle style) {
        ListStyle still = new ListStyle();
        still.anim = ListStyle.Anim.NONE;
        return still;
    }

    /** One list in a cell of the sheet, with its label underneath. */
    static void cell(Graphics2D g, Font font, Font label, int x, int y, int w, int h, String title, ListStyle style,
                     List<ListRow> rows, long time) {
        g.setClip(x, y, w, h);
        scene(g, x, y, w, h);
        float margin = 4.0F * ZOOM;
        float ox = style.right ? x + w - margin : x + margin;
        float oy = style.bottom ? y + h - margin - 26 : y + margin;
        ListFrame frame = ListLayout.frame(style, rows, TEXT_HEIGHT, time, null);
        frame.paint(new Canvas(g, ox, oy, font));
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRect(x, y + h - 26, w, 26);
        g.setColor(Color.WHITE);
        g.setFont(label);
        g.drawString(title, x + 8, y + h - 8);
        g.setClip(null);
        g.setColor(new Color(0x202020));
        g.setStroke(new BasicStroke(2));
        g.drawRect(x, y, w, h);
    }

    static Graphics2D graphics(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        return g;
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "list-preview");
        out.mkdirs();
        Font base = args.length > 1 ? Font.createFont(Font.TRUETYPE_FONT, new File(args[1]))
                : new Font(Font.SANS_SERIF, Font.PLAIN, 1);
        Font font = base.deriveFont(Font.PLAIN, 8.6F * ZOOM);
        Font label = new Font(Font.SANS_SERIF, Font.BOLD, 15);
        long time = 1700L;

        /* Sheet 1: every preset, in a moving gradient. */
        String[] presets = ListPresets.NAMES;
        int cw = 470;
        int ch = 470;
        int cols = 5;
        int shown = presets.length - 1;
        BufferedImage sheet = new BufferedImage(cols * cw, ((shown + cols - 1) / cols) * ch, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = graphics(sheet);
        for (int i = 1; i < presets.length; i++) {
            ListStyle style = new ListStyle();
            ListPresets.apply(presets[i], style);
            style.paint = ListStyle.Paint.GRADIENT;
            style.colors[0] = 0x55CDFC;
            style.colors[1] = 0xF7A8B8;
            cell(g, font, label, ((i - 1) % cols) * cw, ((i - 1) / cols) * ch, cw, ch, presets[i], style,
                    rows(g, font, style, 8, true), time);
        }
        g.dispose();
        ImageIO.write(sheet, "png", new File(out, "presets.png"));

        /* Sheet 2: every colour mode the list works out itself, on the SIDEBAR preset (HUD and THEME are the
           game's colours). */
        List<ListStyle.Paint> paints = new ArrayList<ListStyle.Paint>();
        for (ListStyle.Paint paint : ListStyle.Paint.values()) {
            if (paint != ListStyle.Paint.HUD && paint != ListStyle.Paint.THEME) {
                paints.add(paint);
            }
        }
        sheet = new BufferedImage(cols * cw, ((paints.size() + cols - 1) / cols) * ch, BufferedImage.TYPE_INT_RGB);
        g = graphics(sheet);
        for (int i = 0; i < paints.size(); i++) {
            ListStyle style = new ListStyle();
            ListPresets.apply("SIDEBAR", style);
            style.paint = paints.get(i);
            cell(g, font, label, (i % cols) * cw, (i / cols) * ch, cw, ch, paints.get(i).name(), style,
                    rows(g, font, style, 8, true), time);
        }
        g.dispose();
        ImageIO.write(sheet, "png", new File(out, "colours.png"));

        /* Sheet 3: the four corners of the screen, the sorts, and a few awkward mixes. */
        Object[][] mixes = {
                {"left, top: ROUNDED", "ROUNDED", false, false, ListStyle.Sort.WIDTH},
                {"left, bottom: OUTLINE", "OUTLINE", false, true, ListStyle.Sort.WIDTH},
                {"right, bottom: SIDEBAR", "SIDEBAR", true, true, ListStyle.Sort.WIDTH},
                {"right, bottom: CARD", "CARD", true, true, ListStyle.Sort.WIDTH},
                {"left: PILL", "PILL", false, false, ListStyle.Sort.WIDTH},
                {"alphabet: OUTLINE", "OUTLINE", true, false, ListStyle.Sort.ALPHABET},
                {"alphabet: ROUNDED", "ROUNDED", true, false, ListStyle.Sort.ALPHABET},
                {"narrowest first: OUTLINE", "OUTLINE", true, false, ListStyle.Sort.WIDTH_REVERSED},
                {"category: SOLID", "SOLID", true, false, ListStyle.Sort.CATEGORY},
                {"left: GLASS", "GLASS", false, false, ListStyle.Sort.WIDTH}};
        sheet = new BufferedImage(cols * cw, ((mixes.length + cols - 1) / cols) * ch, BufferedImage.TYPE_INT_RGB);
        g = graphics(sheet);
        for (int i = 0; i < mixes.length; i++) {
            ListStyle style = new ListStyle();
            ListPresets.apply((String) mixes[i][1], style);
            style.right = (Boolean) mixes[i][2];
            style.bottom = (Boolean) mixes[i][3];
            style.sort = (ListStyle.Sort) mixes[i][4];
            style.paint = style.sort == ListStyle.Sort.CATEGORY ? ListStyle.Paint.CATEGORY : ListStyle.Paint.GRADIENT;
            cell(g, font, label, (i % cols) * cw, (i / cols) * ch, cw, ch, (String) mixes[i][0], style,
                    rows(g, font, style, 8, true), time);
        }
        g.dispose();
        ImageIO.write(sheet, "png", new File(out, "mixes.png"));
        System.out.println("wrote " + out.getAbsolutePath());
    }
}
