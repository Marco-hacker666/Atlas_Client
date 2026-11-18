package atlas.update;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;

public final class UpdateInstaller {
    static long waitMillis = 120000L;
    static long retryMillis = 500L;

    private UpdateInstaller() {
    }

    public static void main(String[] args) {
        if (args.length < 4) {
            System.err.println("usage: UpdateInstaller currentJar newJar backupDir logFile");
            System.exit(2);
            return;
        }
        PrintWriter log = openLog(new File(args[3]));
        int code;
        try {
            code = install(new File(args[0]), new File(args[1]), new File(args[2]), log);
        } catch (Throwable t) {
            log("unexpected error: " + t, log);
            t.printStackTrace(log);
            code = 1;
        }
        log.flush();
        log.close();
        System.exit(code);
    }

    static int install(File current, File update, File backupDir, PrintWriter log) throws InterruptedException {
        log("installing " + update + " in place of " + current, log);
        if (!update.isFile()) {
            log("new jar is missing, nothing to do", log);
            return 1;
        }
        if (!current.isFile()) {
            log("current jar is gone (removed by hand?), not installing", log);
            return 1;
        }
        File modsDir = current.getAbsoluteFile().getParentFile();
        File target = new File(modsDir, update.getName());
        if (!backupDir.isDirectory() && !backupDir.mkdirs()) {
            log("cannot create backup folder " + backupDir, log);
            return 1;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File backup = new File(backupDir, stamp + "-" + current.getName());

        long deadline = System.currentTimeMillis() + waitMillis;
        IOException last = null;
        boolean moved = false;
        while (System.currentTimeMillis() < deadline) {
            try {
                Files.move(current.toPath(), backup.toPath());
                moved = true;
                break;
            } catch (IOException e) {
                last = e;
                Thread.sleep(retryMillis);
            }
        }
        if (!moved) {
            log("gave up waiting for the game to release " + current + ": " + last, log);
            return 1;
        }
        log("old jar moved to " + backup, log);

        try {
            Files.move(update.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log("could not move new jar into mods: " + e + " -- restoring the old jar", log);
            try {
                Files.move(backup.toPath(), current.toPath());
                log("old jar restored", log);
            } catch (IOException e2) {
                log("RESTORE FAILED, copy " + backup + " back into " + modsDir + " by hand: " + e2, log);
            }
            return 1;
        }
        log("installed " + target, log);
        pruneBackups(backupDir, 3, log);
        return 0;
    }

    static void pruneBackups(File backupDir, int keep, PrintWriter log) {
        File[] files = backupDir.listFiles();
        if (files == null || files.length <= keep) {
            return;
        }
        java.util.Arrays.sort(files);
        for (int i = 0; i < files.length - keep; i++) {
            if (files[i].isFile() && files[i].getName().endsWith(".jar") && files[i].delete()) {
                log("removed old backup " + files[i].getName(), log);
            }
        }
    }

    private static PrintWriter openLog(File file) {
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            return new PrintWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8), true);
        } catch (IOException e) {
            return new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8), true);
        }
    }

    static void log(String line, PrintWriter log) {
        log.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " " + line);
    }
}
