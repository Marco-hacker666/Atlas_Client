package atlas.mommy;

import java.io.File;

public final class PanicHooks {
    private PanicHooks() {
    }

    public static void noteGameWindow() {
        PanicPlatform.noteGameWindow();
    }

    public static File editCustom(int shell) {
        File f = PanicSetup.ensureCustomFile(shell);
        if (PanicPlatform.OS == PanicCommands.WINDOWS) {
            PanicPlatform.start("notepad.exe", f.getAbsolutePath());
        } else if (PanicPlatform.OS == PanicCommands.MAC) {
            PanicPlatform.start("open", "-e", f.getAbsolutePath());
        } else {
            PanicPlatform.start("xdg-open", f.getAbsolutePath());
        }
        return f;
    }

    public static void openPairing() {
        PanicPlatform.openTarget(PanicRemote.pairUrl());
    }
}
