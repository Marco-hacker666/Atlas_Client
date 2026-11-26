package atlas.ui.hud.list;

import java.util.ArrayList;
import java.util.List;

public final class ListFrame {
    public static final class Op {
        public final String text;
        public final float x1;
        public final float y1;
        public final float x2;
        public final float y2;
        public final float radius;
        public final int corners;
        public final int argb;
        public final boolean shadow;

        public final boolean backing;

        Op(String text, float x1, float y1, float x2, float y2, float radius, int corners, int argb, boolean shadow,
           boolean backing) {
            this.text = text;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.radius = radius;
            this.corners = corners;
            this.argb = argb;
            this.shadow = shadow;
            this.backing = backing;
        }
    }

    public final List<Op> ops = new ArrayList<Op>();

    public final List<Op> bodies = new ArrayList<Op>();

    public float minX;
    public float minY;
    public float maxX;
    public float maxY;
    private boolean any;

    void rect(float x1, float y1, float x2, float y2, float radius, int corners, int argb, boolean backing) {
        if (x2 - x1 <= 0.0F || y2 - y1 <= 0.0F || (argb >>> 24) == 0) {
            return;
        }
        this.ops.add(new Op(null, x1, y1, x2, y2, radius, radius > 0.0F ? corners : 0, argb, false, backing));
        grow(x1, y1, x2, y2);
    }

    void body(float x1, float y1, float x2, float y2, float radius, int corners, int argb) {
        if (x2 - x1 > 0.0F && y2 - y1 > 0.0F && (argb >>> 24) != 0) {
            this.bodies.add(new Op(null, x1, y1, x2, y2, radius, radius > 0.0F ? corners : 0, argb, false, false));
        }
    }

    void text(String text, float x, float y, float width, float height, int argb, boolean shadow) {
        if (text == null || text.isEmpty()) {
            return;
        }
        this.ops.add(new Op(text, x, y, x + width, y + height, 0.0F, 0, argb, shadow, false));
        grow(x, y, x + width, y + height);
    }

    private void grow(float x1, float y1, float x2, float y2) {
        if (!this.any) {
            this.any = true;
            this.minX = x1;
            this.minY = y1;
            this.maxX = x2;
            this.maxY = y2;
            return;
        }
        this.minX = Math.min(this.minX, x1);
        this.minY = Math.min(this.minY, y1);
        this.maxX = Math.max(this.maxX, x2);
        this.maxY = Math.max(this.maxY, y2);
    }

    public void paint(ListPainter painter) {
        for (Op op : this.ops) {
            if (op.text == null) {
                painter.rect(op.x1, op.y1, op.x2, op.y2, op.radius, op.corners, op.argb);
            } else {
                painter.text(op.text, op.x1, op.y1, op.argb, op.shadow);
            }
        }
    }

    public void paintBacking(ListPainter painter, int argb) {
        for (Op op : this.ops) {
            if (op.backing) {
                painter.rect(op.x1, op.y1, op.x2, op.y2, op.radius, op.corners, argb);
            }
        }
    }

    public void paintGlow(ListPainter painter, float strength) {
        for (Op op : this.bodies) {
            float alpha = (op.argb >>> 24) / 255.0F * Math.max(0.0F, Math.min(1.0F, strength));
            painter.rect(op.x1, op.y1, op.x2, op.y2, op.radius, op.corners, ListColors.argb(op.argb, alpha));
        }
    }

    public float[] glowBounds() {
        return bounds(this.bodies, false);
    }

    public float[] backingBounds() {
        return bounds(this.ops, true);
    }

    private static float[] bounds(List<Op> list, boolean backingOnly) {
        float[] out = null;
        for (Op op : list) {
            if (op.text != null || (backingOnly && !op.backing)) {
                continue;
            }
            if (out == null) {
                out = new float[]{op.x1, op.y1, op.x2, op.y2};
            } else {
                out[0] = Math.min(out[0], op.x1);
                out[1] = Math.min(out[1], op.y1);
                out[2] = Math.max(out[2], op.x2);
                out[3] = Math.max(out[3], op.y2);
            }
        }
        return out;
    }

    public boolean hasBacking() {
        for (Op op : this.ops) {
            if (op.backing) {
                return true;
            }
        }
        return false;
    }
}
