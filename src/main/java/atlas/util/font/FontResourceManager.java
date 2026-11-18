package atlas.util.font;

import atlas.util.font.impl.FontRenderer;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;

public class FontResourceManager {
    private static final Set<WeakReference<FontRenderer>> fontReferences = new HashSet<>();

    public static void registerFont(FontRenderer fontRenderer) {
        if (fontRenderer != null) {
            fontReferences.add(new WeakReference<>(fontRenderer));
        }
    }

    public static void cleanupAllFonts() {
        fontReferences.removeIf(ref -> {
            FontRenderer font = ref.get();
            if (font != null) {
                font.destroy();
                return true;
            }

            return true;
        });
    }

    public static void cleanupInvalidReferences() {
        fontReferences.removeIf(ref -> ref.get() == null);
    }
}
