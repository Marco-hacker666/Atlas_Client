package atlas.ui.hud.list;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class ListRow {
    public final Object key;
    public final String name;

    public final String suffix;

    public final int category;
    public final float nameWidth;
    public final float suffixWidth;

    public float appear = 1.0F;

    public float slot;

    public ListRow(Object key, String name, String suffix, int category, float nameWidth, float suffixWidth) {
        this.key = key;
        this.name = name;
        this.suffix = suffix == null ? "" : suffix;
        this.category = category;
        this.nameWidth = nameWidth;
        this.suffixWidth = this.suffix.isEmpty() ? 0.0F : suffixWidth;
    }

    public float textWidth() {
        return this.nameWidth + this.suffixWidth;
    }

    public static List<ListRow> sorted(List<ListRow> rows, ListStyle.Sort sort) {
        List<ListRow> out = new ArrayList<ListRow>(rows);
        final Comparator<ListRow> widest = new Comparator<ListRow>() {
            @Override
            public int compare(ListRow a, ListRow b) {
                return Float.compare(b.textWidth(), a.textWidth());
            }
        };
        switch (sort) {
            case WIDTH:
                Collections.sort(out, widest);
                break;
            case WIDTH_REVERSED:
                Collections.sort(out, Collections.reverseOrder(widest));
                break;
            case ALPHABET:
                Collections.sort(out, new Comparator<ListRow>() {
                    @Override
                    public int compare(ListRow a, ListRow b) {
                        return a.name.compareToIgnoreCase(b.name);
                    }
                });
                break;
            case CATEGORY:
                Collections.sort(out, new Comparator<ListRow>() {
                    @Override
                    public int compare(ListRow a, ListRow b) {
                        return a.category != b.category ? Integer.compare(a.category, b.category)
                                : widest.compare(a, b);
                    }
                });
                break;
            case NONE:
            default:
                break;
        }
        return out;
    }
}
