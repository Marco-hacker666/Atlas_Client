package atlas.ui.impl.clickgui.atlas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * What the search box looks through (2026-10-23, docs/GUI-AUDIT-2026-10-23.md
 * U1): module names and descriptions, and every setting's label, key and help.
 * It used to match module names only, so none of the 2,200 settings, nor
 * the help written for them, could be found.
 *
 * A query is cut into words; an entry matches when every word is in its name
 * (or key) or its text. "full hold" finds full-hold, "slow" finds the setting
 * whose help says it slows you.
 *
 * Order, for modules and for settings alike: matches by name before matches
 * found only in the text; then this page's before the other page's, as the
 * menu always listed them; then an exact name, a name that starts with the
 * query, any other name match; then the menu's own order. For a one-word
 * query the modules found by name are the ones the old search found.
 *
 * Built when the menu opens; holds the modules and settings as plain objects,
 * so it is tested without a game.
 */
final class SearchIndex {

    static final class Entry {
        /** The module, and for a setting entry the setting (null for a module). */
        final Object module;
        final Object setting;
        /** What a result row shows: the module's or the setting's name, and the module a setting is in. */
        final String title;
        final String owner;
        final int order;
        final String name;
        final String nameCompact;
        final String text;

        Entry(Object module, Object setting, String title, String owner, int order, String names, String text) {
            this.module = module;
            this.setting = setting;
            this.title = title;
            this.owner = owner;
            this.order = order;
            this.name = normal(names);
            this.nameCompact = compact(names);
            this.text = normal(text);
        }
    }

    static final class Results {
        final List<Entry> modules;
        final List<Entry> settings;

        Results(List<Entry> modules, List<Entry> settings) {
            this.modules = modules;
            this.settings = settings;
        }

        int size() {
            return this.modules.size() + this.settings.size();
        }
    }

    static final Results NONE = new Results(Collections.<Entry>emptyList(), Collections.<Entry>emptyList());

    private final List<Entry> modules = new ArrayList<Entry>();
    private final List<Entry> settings = new ArrayList<Entry>();

    void addModule(Object module, String name, String description) {
        this.modules.add(new Entry(module, null, name, name, this.modules.size(), name, description));
    }

    /** A setting is found by its label ("Full Hold") and its key ("full-hold") alike. */
    void addSetting(Object module, Object setting, String owner, String label, String key, String help) {
        this.settings.add(new Entry(module, setting, label, owner, this.settings.size(),
                label + " " + key, help));
    }

    int moduleCount() {
        return this.modules.size();
    }

    int settingCount() {
        return this.settings.size();
    }

    /** The matches for a query, at most maxSettings settings; onPage says which modules are on this page. */
    Results search(String query, Predicate<Object> onPage, int maxSettings) {
        String[] words = words(query);
        if (words.length == 0) {
            return NONE;
        }
        String whole = compact(query);
        List<Entry> foundModules = match(this.modules, words, whole, onPage);
        List<Entry> foundSettings = match(this.settings, words, whole, onPage);
        if (foundSettings.size() > maxSettings) {
            foundSettings = new ArrayList<Entry>(foundSettings.subList(0, maxSettings));
        }
        return new Results(foundModules, foundSettings);
    }

    private static List<Entry> match(List<Entry> entries, String[] words, String whole, final Predicate<Object> onPage) {
        final List<Entry> found = new ArrayList<Entry>();
        final List<int[]> keys = new ArrayList<int[]>();
        for (Entry entry : entries) {
            int rank = rank(entry, words, whole);
            if (rank < 0) {
                continue;
            }
            boolean byName = rank < 3;
            keys.add(new int[]{byName ? 0 : 1, onPage.test(entry.module) ? 0 : 1, rank, entry.order, found.size()});
            found.add(entry);
        }
        Collections.sort(keys, new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                for (int i = 0; i < 4; i++) {
                    if (a[i] != b[i]) {
                        return a[i] < b[i] ? -1 : 1;
                    }
                }
                return 0;
            }
        });
        List<Entry> out = new ArrayList<Entry>(found.size());
        for (int[] key : keys) {
            out.add(found.get(key[4]));
        }
        return out;
    }

    /**
     * 0 the name is the query, 1 the name starts with it, 2 every word is in
     * the name, 3 every word is in the name or the text; -1 no match.
     */
    static int rank(Entry entry, String[] words, String whole) {
        boolean allInName = true;
        for (String word : words) {
            boolean inName = contains(entry.name, entry.nameCompact, word);
            if (!inName) {
                allInName = false;
                if (!entry.text.contains(word)) {
                    return -1;
                }
            }
        }
        if (!allInName) {
            return 3;
        }
        String title = compact(entry.title);
        if (title.equals(whole)) {
            return 0;
        }
        return title.startsWith(whole) ? 1 : 2;
    }

    private static boolean contains(String name, String nameCompact, String word) {
        return name.contains(word) || nameCompact.contains(compact(word));
    }

    static String[] words(String query) {
        String cleaned = normal(query).trim();
        return cleaned.isEmpty() ? new String[0] : cleaned.split("\\s+");
    }

    /** Lower case, with dashes, underscores, dots and slashes as spaces: "full-hold" reads as "full hold". */
    static String normal(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        String lower = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            out.append(c == '-' || c == '_' || c == '.' || c == '/' ? ' ' : c);
        }
        return out.toString();
    }

    /** Letters and digits only: "Full Hold", "full-hold" and "fullhold" are all "fullhold". */
    static String compact(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        String lower = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
