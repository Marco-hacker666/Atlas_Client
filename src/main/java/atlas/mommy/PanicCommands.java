package atlas.mommy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class PanicCommands {
    public static final int WINDOWS = 0;
    public static final int MAC = 1;
    public static final int LINUX = 2;

    public static final int SHELL_CMD = 0;
    public static final int SHELL_POWERSHELL = 1;
    public static final int SHELL_SH = 2;
    public static final String[] SHELLS = {"CMD", "PowerShell", "Shell (sh)"};

    private PanicCommands() {
    }

    public static int os() {
        String n = System.getProperty("os.name", "").toLowerCase();
        return n.contains("win") ? WINDOWS : n.contains("mac") || n.contains("darwin") ? MAC : LINUX;
    }

    static final String AUDIO_TYPE = String.join("\r\n",
            "Add-Type -TypeDefinition @'",
            "using System.Runtime.InteropServices;",
            "[Guid(\"5CDF2C82-841E-4546-9722-0CF74078229A\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]",
            "interface IAudioEndpointVolume { int f(); int g(); int h(); int i();",
            "  int SetMasterVolumeLevelScalar(float level, System.Guid ctx); int j();",
            "  int GetMasterVolumeLevelScalar(out float level); int k(); int l(); int m(); int n();",
            "  int SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, System.Guid ctx); int GetMute(out bool mute); }",
            "[Guid(\"D666063F-1587-4E43-81F1-B948E807363F\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]",
            "interface IMMDevice { int Activate(ref System.Guid id, int ctx, int p, out IAudioEndpointVolume v); }",
            "[Guid(\"A95664D2-9614-4F35-A746-DE8DB63617E6\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]",
            "interface IMMDeviceEnumerator { int f(); int GetDefaultAudioEndpoint(int flow, int role, out IMMDevice d); }",
            "[ComImport, Guid(\"BCDE0395-E52F-467C-8E3D-C4579291692E\")] class MMDeviceEnumeratorComObject { }",
            "public class AtlasAudio {",
            "  static IAudioEndpointVolume Vol() {",
            "    var e = new MMDeviceEnumeratorComObject() as IMMDeviceEnumerator; IMMDevice d = null;",
            "    Marshal.ThrowExceptionForHR(e.GetDefaultAudioEndpoint(0, 1, out d));",
            "    IAudioEndpointVolume v = null; var id = typeof(IAudioEndpointVolume).GUID;",
            "    Marshal.ThrowExceptionForHR(d.Activate(ref id, 23, 0, out v)); return v; }",
            "  public static void Mute(bool on) { Marshal.ThrowExceptionForHR(Vol().SetMute(on, System.Guid.Empty)); }",
            "  public static bool Muted() { bool m; Marshal.ThrowExceptionForHR(Vol().GetMute(out m)); return m; }",
            "}",
            "'@");

    static String winMuteScript(boolean on) {
        return "$ErrorActionPreference = 'SilentlyContinue'\r\n" + AUDIO_TYPE + "\r\n"
                + (on ? "'was:' + [AtlasAudio]::Muted()\r\n[AtlasAudio]::Mute($true)\r\n"
                : "[AtlasAudio]::Mute($false)\r\n");
    }

    static String[] powershell(String script) {
        byte[] utf16 = script.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        return new String[]{"powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-EncodedCommand", java.util.Base64.getEncoder().encodeToString(utf16)};
    }

    static String vbQuote(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    static String psQuote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    static String[] mute(int os, boolean on) {
        if (os == MAC) {
            return new String[]{"osascript", "-e", "set volume output muted " + on};
        }
        return new String[]{"sh", "-c", "pactl set-sink-mute @DEFAULT_SINK@ " + (on ? "1" : "0")
                + " || amixer -q -D pulse sset Master " + (on ? "mute" : "unmute")
                + " || amixer -q sset Master " + (on ? "mute" : "unmute")};
    }

    static String[] muted(int os) {
        if (os == MAC) {
            return new String[]{"osascript", "-e", "output muted of (get volume settings)"};
        }
        return new String[]{"sh", "-c", "pactl get-sink-mute @DEFAULT_SINK@ || amixer get Master | grep -c '\\[off\\]'"};
    }

    static boolean saysMuted(String out) {
        String s = out == null ? "" : out.trim().toLowerCase();
        return s.contains("true") || s.contains("mute: yes") || s.equals("yes") || s.matches("[1-9][0-9]*");
    }

    static String[] blackScreen(int os) {
        return os == MAC ? new String[]{"pmset", "displaysleepnow"} : new String[]{"xset", "dpms", "force", "off"};
    }

    static String[] sleep(int os) {
        return os == MAC ? new String[]{"pmset", "sleepnow"} : new String[]{"systemctl", "suspend"};
    }

    static String[] shutdown(int os, boolean force) {
        if (os == WINDOWS) {
            return force ? new String[]{"shutdown.exe", "/s", "/t", "0", "/f"} : new String[]{"shutdown.exe", "/s", "/t", "0"};
        }
        if (os == MAC) {
            return new String[]{"osascript", "-e", "tell application \"System Events\" to shut down"};
        }
        return new String[]{"systemctl", "poweroff"};
    }

    static String[] lock(int os) {
        if (os == MAC) {
            return new String[]{"osascript", "-e",
                    "tell application \"System Events\" to keystroke \"q\" using {command down, control down}"};
        }
        return new String[]{"loginctl", "lock-session"};
    }

    static String[] gameWindow(int os, long pid, boolean show) {
        if (os == MAC) {
            return new String[]{"osascript", "-e", "tell application \"System Events\" to set visible of (first process "
                    + "whose unix id is " + pid + ") to " + show + (show ? "\ntell application \"System Events\" to set "
                    + "frontmost of (first process whose unix id is " + pid + ") to true" : "")};
        }
        return new String[]{"sh", "-c", "xdotool search --pid " + pid + " | while read w; do xdotool "
                + (show ? "windowmap $w windowactivate $w" : "windowminimize $w") + "; done"};
    }

    static String[] closeApp(int os, String name, boolean force) {
        if (os == WINDOWS) {
            return force ? new String[]{"taskkill.exe", "/im", name, "/f"} : new String[]{"taskkill.exe", "/im", name};
        }
        if (os == MAC && !force) {
            return new String[]{"osascript", "-e", "quit app " + appleQuote(name)};
        }
        return new String[]{"pkill", force ? "-9" : "-15", "-x", name};
    }

    static String appleQuote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String[] listApps(int os) {
        if (os == MAC) {
            return new String[]{"osascript", "-e",
                    "tell application \"System Events\" to get name of every process whose background only is false"};
        }
        return new String[]{"sh", "-c", "wmctrl -lx 2>/dev/null | awk '{print $3}' | sort -u || ps -eo comm= | sort -u"};
    }

    static String[] open(int os, String target) {
        return os == MAC ? new String[]{"open", target} : new String[]{"xdg-open", target};
    }

    static String[] custom(int shell, String file) {
        switch (shell) {
            case SHELL_POWERSHELL:
                return new String[]{"powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                        "-File", file};
            case SHELL_SH:
                return new String[]{"sh", file};
            default:
                return new String[]{"cmd.exe", "/c", file};
        }
    }

    static String customFileName(int shell) {
        return shell == SHELL_POWERSHELL ? "mommy-command.ps1" : shell == SHELL_SH ? "mommy-command.sh"
                : "mommy-command.cmd";
    }

    static String template(int shell) {
        String c = shell == SHELL_CMD ? "REM " : "# ";
        List<String> lines = new ArrayList<String>(Arrays.asList(
                c + "MOMMY IS HERE!!! (Atlas): what runs with action Custom, as a " + SHELLS[shell] + " script.",
                c + "Lines starting with " + c.trim() + " do nothing. For example:"));
        if (shell == SHELL_POWERSHELL) {
            lines.add("# Stop-Process -Name chrome -Force");
            lines.add("# Start-Process 'https://classroom.google.com'");
        } else if (shell == SHELL_SH) {
            lines.add("# pkill -x Discord");
            lines.add("# open 'https://classroom.google.com'   (Linux: xdg-open)");
        } else {
            lines.add("REM taskkill /im chrome.exe /f");
            lines.add("REM start \"\" \"https://classroom.google.com\"");
        }
        return String.join(shell == SHELL_CMD || shell == SHELL_POWERSHELL ? "\r\n" : "\n", lines) + "\n";
    }
}
