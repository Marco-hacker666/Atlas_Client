package atlas.ui.impl.clickgui.atlas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

final class SearchIndex {
    static final class Entry {
        final Object module;
        final Object setting;

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
