package atlas.management;

import net.minecraft.client.Minecraft;

import java.awt.*;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public abstract class PlayerFileManager {
    public static Minecraft mc = Minecraft.getMinecraft();

    public final java.util.concurrent.CopyOnWriteArrayList<String> players;
    public File file;
    public Color color;

    public PlayerFileManager(File file, Color color) {
        this.players = new java.util.concurrent.CopyOnWriteArrayList<>();
        this.file = file;
        this.color = color;
    }

    public void load() {
        if (!file.exists()) {
            try {
                if ((file.getParentFile().exists() || file.getParentFile().mkdirs()) && file.createNewFile()) {
                    System.out.printf("File created: %s%n", file.getName());
                }
            } catch (IOException e) {
                System.err.println("Error creating file: " + e.getMessage());
            }
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file),
                java.nio.charset.StandardCharsets.UTF_8))) {
            List<String> read = reader.lines().map(String::trim).filter(line -> !line.isEmpty())
                    .collect(Collectors.toList());
            players.clear();
            players.addAll(read);
        } catch (IOException e) {
            System.err.println("Error reading file: " + e.getMessage());
        }
    }

    public void save() {
        File temp = new File(file.getAbsoluteFile().getParentFile(), file.getName() + ".tmp");
        try {
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(temp), java.nio.charset.StandardCharsets.UTF_8)) {
                writer.write(String.join("\n", players));
            }
            try {
                java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("Error saving file: " + e.getMessage());
        }
    }

    public String add(String name) {
        if (isFriend(name)) {
            return null;
        }
        players.add(name);
        save();
        return name;
    }

    public String remove(String name) {
        for (String player : players) {
            if (player.equalsIgnoreCase(name)) {
                players.remove(player);
                save();
                return player;
            }
        }
        return null;
    }

    public void clear() {
        players.clear();
        save();
    }

    public boolean isFriend(String string) {
        return string != null && this.players.stream().anyMatch(string2 -> string2.equalsIgnoreCase(string));
    }

    public ArrayList<String> getPlayers() {
        return new ArrayList<>(this.players);
    }

    public Color getColor() {
        return this.color;
    }
}
