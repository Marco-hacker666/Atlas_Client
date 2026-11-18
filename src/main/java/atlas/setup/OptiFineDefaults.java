package atlas.setup;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class OptiFineDefaults {
    static final String FILE = "optionsof.txt";
    static final String CONNECTED_TEXTURES = "ofConnectedTextures";
    static final String OFF = "3";

    private OptiFineDefaults() {
    }

    public static void applyFirstInstall(File gameDir) {
        try {
            File file = new File(gameDir, FILE);
            List<String> lines = file.isFile()
                    ? new ArrayList<String>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8))
                    : new ArrayList<String>();
            Files.write(file.toPath(), withConnectedTexturesOff(lines), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Atlas] could not set OptiFine's defaults: " + e);
        }
    }

    static List<String> withConnectedTexturesOff(List<String> lines) {
        List<String> out = new ArrayList<String>();
        boolean found = false;
        for (String line : lines) {
            if (line.startsWith(CONNECTED_TEXTURES + ":")) {
                out.add(CONNECTED_TEXTURES + ":" + OFF);
                found = true;
            } else {
                out.add(line);
            }
        }
        if (!found) {
            out.add(CONNECTED_TEXTURES + ":" + OFF);
        }
        return out;
    }
}
