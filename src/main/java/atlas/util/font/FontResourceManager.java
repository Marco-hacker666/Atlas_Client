package atlas.util.font;

import atlas.util.font.impl.FontRenderer;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;

/**
 * Keeps track of font resources over their lifetime, so they do not leak
 */
public class FontResourceManager {
    private static final Set<WeakReference<FontRenderer>> fontReferences = new HashSet<>();

    /**
     * Registers a font renderer reference so all of them can be cleaned up together
     */
    public static void registerFont(FontRenderer fontRenderer) {
        if (fontRenderer != null) {
            fontReferences.add(new WeakReference<>(fontRenderer));
        }
    }

    /**
     * Cleans up every registered font resource
     */
    public static void cleanupAllFonts() {
        fontReferences.removeIf(ref -> {
            FontRenderer font = ref.get();
            if (font != null) {
                font.destroy();
                return true;
            }
            // a cleared reference is removed from the set
            return true;
        });
    }

    /**
     * Removes dead references (weak references after garbage collection)
     */
    public static void cleanupInvalidReferences() {
        fontReferences.removeIf(ref -> ref.get() == null);
    }
}