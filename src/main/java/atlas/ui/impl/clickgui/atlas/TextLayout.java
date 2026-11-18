package atlas.ui.impl.clickgui.atlas;

import java.util.ArrayList;
import java.util.List;

final class TextLayout {
    interface Width {
        float of(String text);
    }

    private TextLayout() {
    }

    static int[] window(String text, int caret, Width width, float maxWidth, boolean preferTail) {
        int length = text.length();
        caret = Math.max(0, Math.min(length, caret));
        if (width.of(text) <= maxWidth) {
            return new int[]{0, length};
        }
        if (preferTail) {
            int start = fromEnd(text, length, width, maxWidth);
            if (caret >= start) {
                return new int[]{start, length};
            }
            return new int[]{caret, fromStart(text, caret, width, maxWidth)};
        }
        int end = fromStart(text, 0, width, maxWidth);
        if (caret <= end) {
            return new int[]{0, end};
        }
        return new int[]{fromEnd(text, caret, width, maxWidth), caret};
    }

    private static int fromStart(String text, int start, Width width, float maxWidth) {
        int end = start;
        while (end < text.length() && width.of(text.substring(start, end + 1)) <= maxWidth) {
            end++;
        }
        return end;
    }

    private static int fromEnd(String text, int end, Width width, float maxWidth) {
        int start = end;
        while (start > 0 && width.of(text.substring(start - 1, end)) <= maxWidth) {
            start--;
        }
        return start;
    }

    static int indexAt(String text, int start, Width width, float dx) {
        start = Math.max(0, Math.min(text.length(), start));
        if (dx <= 0.0F) {
            return start;
        }
        float previous = 0.0F;
        for (int i = start + 1; i <= text.length(); i++) {
            float w = width.of(text.substring(start, i));
            if (w >= dx) {
                return w - dx < dx - previous ? i : i - 1;
            }
            previous = w;
        }
        return text.length();
    }

    static List<int[]> wrap(String text, Width width, float maxWidth) {
        List<int[]> lines = new ArrayList<int[]>();
        int lineStart = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(new int[]{lineStart, i});
                lineStart = i + 1;
                continue;
            }
            String current = text.substring(lineStart, i + 1);
            if (width.of(current) > maxWidth) {
                int cut = current.lastIndexOf(' ');
                if (cut <= 0) {
                    cut = current.length() - 1;
                }
                lines.add(new int[]{lineStart, lineStart + cut});
                lineStart += cut;
                if (lineStart < text.length() && text.charAt(lineStart) == ' ') {
                    lineStart++;
                }
            }
        }
        lines.add(new int[]{lineStart, text.length()});
        return lines;
    }

    static int lineOf(List<int[]> lines, int caret) {
        int found = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i)[0] <= caret) {
                found = i;
            }
        }
        return found;
    }
}
