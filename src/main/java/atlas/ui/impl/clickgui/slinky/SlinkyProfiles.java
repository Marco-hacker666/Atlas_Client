package atlas.ui.impl.clickgui.slinky;

import atlas.config.Config;
import atlas.event.EventTarget;
import atlas.events.KeyEvent;
import atlas.util.ChatUtil;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Profiles as the Slinky-style menu shows them: every saved config in
 * config/Atlas, newest first, each with an optional key that loads it.
 *
 * Profiles are the same files {@code .config save/load} uses (Config does the
 * reading and writing), so a profile made here and one made with the command
 * are the same thing. Only the keys are this menu's own; they are kept in
 * config/Atlas/slinky-profiles.txt, one "name TAB key" per line.
 *
 * One instance listens for key presses in game (registered in Atlas) and loads
 * the profile bound to the key.
 */
public final class SlinkyProfiles {
    static final File DIR = new File("./config/Atlas/");
    private static final File KEYS = new File(DIR, "slinky-profiles.txt");
    /** Files in the folder that are not profiles. */
    private static final String[] NOT_PROFILES = {"atlas-theme"};

    public static final SlinkyProfiles INSTANCE = new SlinkyProfiles();

    static final class Row {
        final String name;
        final long modified;

        Row(String name, long modified) {
            this.name = name;
            this.modified = modified;
        }
    }

    private final Map<String, Integer> keys = new HashMap<String, Integer>();
    private boolean keysRead;

    private SlinkyProfiles() {
    }

    /** Every profile but the default, newest first. */
    static List<Row> list() {
        List<Row> out = new ArrayList<Row>();
        File[] files = DIR.listFiles();
        if (files == null) {
            return out;
        }
        for (File file : files) {
            String fileName = file.getName();
            if (!file.isFile() || !fileName.toLowerCase().endsWith(".json")) {
                continue;
            }
            String name = fileName.substring(0, fileName.length() - 5);
            if ("default".equalsIgnoreCase(name) || isNotProfile(name)) {
                continue;
            }
            out.add(new Row(name, file.lastModified()));
        }
        Collections.sort(out, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                return Long.compare(b.modified, a.modified);
            }
        });
        return out;
    }

    private static boolean isNotProfile(String name) {
        for (String other : NOT_PROFILES) {
            if (other.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    static boolean exists(String name) {
        return new File(DIR, name + ".json").isFile();
    }

    /** "Used today.", "Used 1 day ago.", "Used 4 days ago." */
    static String used(long modified) {
        LocalDate day = Instant.ofEpochMilli(modified).atZone(ZoneId.systemDefault()).toLocalDate();
        long days = Math.max(0L, ChronoUnit.DAYS.between(day, LocalDate.now()));
        if (days == 0L) {
            return "Used today.";
        }
        return days == 1L ? "Used 1 day ago." : "Used " + days + " days ago.";
    }

    static void load(String name) {
        new Config(name == null ? "default" : name, false).load();
        say("Loaded profile &f" + (name == null ? "Default" : name));
    }

    static void save(String name) {
        new Config(name, true).save();
        say("Saved profile &f" + name);
    }

    static void delete(String name) {
        if (new File(DIR, name + ".json").delete()) {
            INSTANCE.setKey(name, 0);
            say("Deleted profile &f" + name);
        } else {
            say("Could not delete &f" + name);
        }
    }

    static void openFolder() {
        try {
            java.awt.Desktop.getDesktop().open(DIR.getCanonicalFile());
        } catch (Throwable desktopFailed) {
            try {
                org.lwjgl.Sys.openURL(DIR.getCanonicalFile().toURI().toString());
            } catch (Throwable ignored) {
                say("Profiles are in &f" + DIR.getAbsolutePath());
            }
        }
    }

    /**
     * What may be typed as a profile name: letters, digits, space, dash,
     * underscore and dot, at most 32 characters, not starting with a dot.
     * Anything else could name a file outside the folder.
     */
    static String clean(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.trim().toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            }
        }
        String out = sb.toString().trim();
        while (out.startsWith(".")) {
            out = out.substring(1);
        }
        return out.length() > 32 ? out.substring(0, 32) : out;
    }

    static void say(String message) {
        ChatUtil.sendFormatted("&7[&cAtlas&7] &7" + message);
    }

    // ---- keys ------------------------------------------------------------

    int getKey(String name) {
        readKeys();
        Integer key = this.keys.get(name);
        return key == null ? 0 : key;
    }

    void setKey(String name, int key) {
        readKeys();
        if (key == 0) {
            this.keys.remove(name);
        } else {
            this.keys.put(name, key);
        }
        writeKeys();
    }

    /** Loads the profile bound to a key pressed in game. */
    @EventTarget
    public void onKey(KeyEvent event) {
        readKeys();
        if (this.keys.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Integer> entry : this.keys.entrySet()) {
            if (entry.getValue() == event.getKey() && exists(entry.getKey())) {
                load(entry.getKey());
                return;
            }
        }
    }

    private void readKeys() {
        if (this.keysRead) {
            return;
        }
        this.keysRead = true;
        try {
            if (!KEYS.isFile()) {
                return;
            }
            for (String line : Files.readAllLines(KEYS.toPath(), StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t");
                if (parts.length == 2) {
                    this.keys.put(parts[0], Integer.parseInt(parts[1].trim()));
                }
            }
        } catch (Exception ignored) {
            // No keys, then.
        }
    }

    private void writeKeys() {
        try {
            DIR.mkdirs();
            PrintWriter writer = new PrintWriter(KEYS, "UTF-8");
            try {
                for (Map.Entry<String, Integer> entry : this.keys.entrySet()) {
                    writer.println(entry.getKey() + "\t" + entry.getValue());
                }
            } finally {
                writer.close();
            }
        } catch (Exception ignored) {
            // The keys stay for this session.
        }
    }
}
