package myau.setup;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * OptiFine settings set for a player the first time this client installs
 * OptiFine for them (2026-10-11, the owner's request).
 *
 * Connected Textures goes off: OptiFine keeps it in optionsof.txt as
 * {@code ofConnectedTextures}, 1 = Fast, 2 = Fancy (OptiFine's default),
 * 3 = Off -- checked against the owner's own file, where Off is stored as 3.
 * Written only right after our own OptiFine install, so a player who turns
 * it on later is never overridden.
 */
public final class OptiFineDefaults {
    static final String FILE = "optionsof.txt";
    static final String CONNECTED_TEXTURES = "ofConnectedTextures";
    static final String OFF = "3";

    private OptiFineDefaults() {
    }

    /** Sets Connected Textures off in {@code gameDir}/optionsof.txt, creating the file if need be. */
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
