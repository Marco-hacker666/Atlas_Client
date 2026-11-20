package atlas.mommy;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class PanicPlatform {
    static final int OS = PanicCommands.os();

    static volatile Pointer gameWindow;

    interface PowrProf extends StdCallLibrary {
        boolean SetSuspendState(boolean hibernate, boolean force, boolean disableWake);
    }

    private PanicPlatform() {
    }

    static long pid() {
        if (OS == PanicCommands.WINDOWS && WinApi.available()) {
            return WinApi.pid();
        }
        try {
            String n = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            return Long.parseLong(n.substring(0, n.indexOf('@')));
        } catch (Exception e) {
            return -1L;
        }
    }

    static void noteGameWindow() {
        if (OS != PanicCommands.WINDOWS || !WinApi.available()) {
            return;
        }
        Pointer fg = WinApi.user32().GetForegroundWindow();
        if (fg != null && WinApi.processOf(fg) == WinApi.pid()) {
            gameWindow = fg;
        }
    }

    static void start(String... argv) {
        try {
            new ProcessBuilder(argv).redirectErrorStream(true).start();
        } catch (Exception e) {
            System.err.println("[Atlas] mommy: " + argv[0] + ": " + e.getMessage());
        }
    }

    static String output(int seconds, String... argv) {
        try {
            Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            InputStream in = p.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            long until = System.currentTimeMillis() + seconds * 1000L;
            while (System.currentTimeMillis() < until) {
                int n = in.read(b);
                if (n < 0) {
                    break;
                }
                buf.write(b, 0, n);
            }
            p.waitFor(1, TimeUnit.SECONDS);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    static boolean mute(boolean on) {
        if (OS == PanicCommands.WINDOWS) {
            String out = output(8, PanicCommands.powershell(PanicCommands.winMuteScript(on)));
            return on && out.contains("was:True");
        }
        boolean was = on && PanicCommands.saysMuted(output(3, PanicCommands.muted(OS)));
        output(3, PanicCommands.mute(OS, on));
        return was;
    }

    static void hideGame(int mode) {
        if (OS == PanicCommands.WINDOWS) {
            Pointer w = gameWindow;
            if (w != null && WinApi.available()) {
                WinApi.user32().ShowWindow(w, mode == 2 ? WinApi.SW_HIDE : WinApi.SW_MINIMIZE);
            }
            return;
        }
        start(PanicCommands.gameWindow(OS, pid(), false));
    }

    static void showGame() {
        if (OS == PanicCommands.WINDOWS) {
            Pointer w = gameWindow;
            if (w != null && WinApi.available()) {
                WinApi.bringToFront(w);
            }
            return;
        }
        start(PanicCommands.gameWindow(OS, pid(), true));
    }

    static void blackScreen() {
        if (OS == PanicCommands.WINDOWS) {
            WinApi.monitorsOff();
        } else {
            start(PanicCommands.blackScreen(OS));
        }
    }

    static void lock() {
        if (OS == PanicCommands.WINDOWS && WinApi.available()) {
            WinApi.user32().LockWorkStation();
        } else {
            start(PanicCommands.lock(OS));
        }
    }

    static void sleep() {
        if (OS == PanicCommands.WINDOWS) {
            try {
                PowrProf p = (PowrProf) Native.loadLibrary("powrprof", PowrProf.class);
                p.SetSuspendState(false, false, false);
                return;
            } catch (Throwable ignored) {
            }
            start(PanicCommands.powershell("Add-Type -AssemblyName System.Windows.Forms; "
                    + "[System.Windows.Forms.Application]::SetSuspendState('Suspend', $false, $false)"));
            return;
        }
        start(PanicCommands.sleep(OS));
    }

    static void shutdown(boolean force) {
        start(PanicCommands.shutdown(OS, force));
    }

    static void custom(int shell) {
        File f = PanicSetup.ensureCustomFile(shell);
        start(PanicCommands.custom(shell, f.getAbsolutePath()));
    }

    static void closeApps(List<String> names, boolean force) {
        if (names.isEmpty()) {
            return;
        }
        if (OS == PanicCommands.WINDOWS && !force && WinApi.available()) {
            Map<Integer, String> procs = processNames();
            for (WinApi.Window w : WinApi.windows()) {
                String n = procs.get(w.pid);
                if (n != null && containsIgnoreCase(names, n)) {
                    WinApi.user32().PostMessageA(w.hwnd, WinApi.WM_CLOSE, 0, 0);
                }
            }
            return;
        }
        for (String n : names) {
            start(PanicCommands.closeApp(OS, n, force));
        }
    }

    static void cover(List<PanicSetup.Cover> items) {
        Map<Integer, String> procs = null;
        List<WinApi.Window> windows = null;
        for (PanicSetup.Cover c : items) {
            if ("window".equals(c.type) && OS == PanicCommands.WINDOWS && WinApi.available()) {
                if (procs == null) {
                    procs = processNames();
                    windows = WinApi.windows();
                }
                WinApi.Window match = null;
                for (WinApi.Window w : windows) {
                    String n = procs.get(w.pid);
                    if (n != null && n.equalsIgnoreCase(c.value)) {
                        match = w;
                        if (!c.label.isEmpty() && w.title.equals(c.label)) {
                            break;
                        }
                    }
                }
                if (match != null) {
                    WinApi.user32().ShowWindow(match.hwnd, WinApi.SW_RESTORE);
                    WinApi.user32().SetForegroundWindow(match.hwnd);
                }
                continue;
            }
            if ("window".equals(c.type)) {
                if (OS == PanicCommands.MAC) {
                    start("osascript", "-e", "tell application " + PanicCommands.appleQuote(c.value) + " to activate");
                }
                continue;
            }
            openTarget(c.value);
        }
    }

    static void openTarget(String target) {
        if (target == null || target.trim().isEmpty()) {
            return;
        }
        if (OS == PanicCommands.WINDOWS) {
            start("rundll32.exe", "url.dll,FileProtocolHandler", target.trim());
        } else {
            start(PanicCommands.open(OS, target.trim()));
        }
    }

    static Map<Integer, String> processNames() {
        Map<Integer, String> out = new HashMap<Integer, String>();
        for (String line : output(5, "tasklist.exe", "/fo", "csv", "/nh").split("\r?\n")) {
            String[] f = line.split("\",\"");
            if (f.length >= 2) {
                try {
                    out.put(Integer.parseInt(f[1].replace("\"", "").trim()), f[0].replace("\"", "").trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out;
    }

    static List<String[]> openPrograms() {
        List<String[]> out = new ArrayList<String[]>();
        if (OS == PanicCommands.WINDOWS && WinApi.available()) {
            Map<Integer, String> procs = processNames();
            for (WinApi.Window w : WinApi.windows()) {
                String n = procs.get(w.pid);
                if (n == null || n.equalsIgnoreCase("TextInputHost.exe") || w.title.equals("Program Manager")
                        || n.equalsIgnoreCase("ApplicationFrameHost.exe") && w.title.isEmpty()) {
                    continue;
                }
                out.add(new String[]{n, w.title});
            }
            return out;
        }
        String text = output(5, PanicCommands.listApps(OS));
        for (String s : text.split(OS == PanicCommands.MAC ? ",\\s*|\r?\n" : "\r?\n")) {
            if (!s.trim().isEmpty()) {
                out.add(new String[]{s.trim(), ""});
            }
        }
        return out;
    }

    static List<String> allProcessNames() {
        java.util.TreeSet<String> names = new java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        if (OS == PanicCommands.WINDOWS) {
            names.addAll(processNames().values());
        } else {
            for (String line : output(5, "ps", "-axco", "comm=").split("\r?\n")) {
                if (!line.trim().isEmpty()) {
                    names.add(line.trim());
                }
            }
        }
        names.remove("System Idle Process");
        names.remove("System");
        names.remove("Registry");
        return new ArrayList<String>(names);
    }

    private static boolean containsIgnoreCase(List<String> list, String s) {
        String l = s.toLowerCase(Locale.ROOT);
        for (String x : list) {
            if (x.toLowerCase(Locale.ROOT).equals(l)) {
                return true;
            }
        }
        return false;
    }
}
