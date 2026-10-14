package myau.init;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * The client's data moved from config/Myau to config/Atlas, and the account
 * list from openmyau.accounts.json to atlas.accounts.json (2026-10-14,
 * rename to Atlas). Runs once, from the mixin plugin's onLoad, before any
 * client class reads a path; does nothing when the new location exists.
 *
 * <p>A rename is tried first; if it fails (another process holds a file),
 * the old data is copied and left in place, so nothing is ever lost.
 */
public final class DataDirMigration {
    private static boolean done;

    private DataDirMigration() {
    }

    public static synchronized void run(File gameDir) {
        if (done) {
            return;
        }
        done = true;
        move(new File(gameDir, "config/Myau"), new File(gameDir, "config/Atlas"));
        move(new File(gameDir, "openmyau.accounts.json"), new File(gameDir, "atlas.accounts.json"));
    }

    static void move(File from, File to) {
        if (!from.exists() || to.exists()) {
            return;
        }
        try {
            Files.move(from.toPath(), to.toPath());
            System.out.println("[Atlas] moved " + from.getName() + " to " + to.getPath());
        } catch (IOException e) {
            try {
                copy(from.toPath(), to.toPath());
                System.out.println("[Atlas] copied " + from.getPath() + " to " + to.getPath()
                        + " (moving failed: " + e + "; the old copy is left in place)");
            } catch (IOException copyFailed) {
                System.err.println("[Atlas] could not move or copy " + from.getPath() + " to " + to.getPath()
                        + ": " + copyFailed + "; settings start from defaults");
            }
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
            return;
        }
        Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
