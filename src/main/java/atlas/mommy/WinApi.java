package atlas.mommy;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;

import java.util.ArrayList;
import java.util.List;

final class WinApi {
    static final int SW_HIDE = 0;
    static final int SW_MINIMIZE = 6;
    static final int SW_RESTORE = 9;
    static final int SW_SHOW = 5;
    static final int WM_CLOSE = 0x0010;
    static final int WM_SYSCOMMAND = 0x0112;
    static final int SC_MONITORPOWER = 0xF170;
    static final int WM_HOTKEY = 0x0312;
    static final int MOD_ALT = 1;
    static final int MOD_CONTROL = 2;
    static final int MOD_SHIFT = 4;
    static final int MOD_NOREPEAT = 0x4000;

    interface User32 extends StdCallLibrary {
        Pointer GetForegroundWindow();

        boolean ShowWindow(Pointer hwnd, int cmd);

        boolean SetForegroundWindow(Pointer hwnd);

        boolean IsWindow(Pointer hwnd);

        boolean IsWindowVisible(Pointer hwnd);

        boolean PostMessageA(Pointer hwnd, int msg, int wParam, int lParam);

        boolean LockWorkStation();

        int GetWindowThreadProcessId(Pointer hwnd, int[] pid);

        int GetWindowTextW(Pointer hwnd, char[] text, int max);

        boolean EnumWindows(EnumProc proc, Pointer data);

        boolean RegisterHotKey(Pointer hwnd, int id, int mods, int vk);

        boolean UnregisterHotKey(Pointer hwnd, int id);

        int GetMessageA(Pointer msg, Pointer hwnd, int min, int max);

        boolean PostThreadMessageA(int thread, int msg, int wParam, int lParam);

        void keybd_event(byte vk, byte scan, int flags, Pointer extra);

        boolean IsIconic(Pointer hwnd);

        boolean BringWindowToTop(Pointer hwnd);

        boolean SetWindowPos(Pointer hwnd, Pointer after, int x, int y, int cx, int cy, int flags);

        boolean AttachThreadInput(int from, int to, boolean attach);
    }

    interface Kernel32 extends StdCallLibrary {
        int GetCurrentProcessId();

        int GetCurrentThreadId();
    }

    interface EnumProc extends StdCallLibrary.StdCallCallback {
        boolean callback(Pointer hwnd, Pointer data);
    }

    private static User32 user32;
    private static Kernel32 kernel32;
    private static boolean failed;

    private WinApi() {
    }

    static boolean available() {
        if (user32 == null && !failed) {
            try {
                user32 = (User32) Native.loadLibrary("user32", User32.class);
                kernel32 = (Kernel32) Native.loadLibrary("kernel32", Kernel32.class);
            } catch (Throwable t) {
                failed = true;
            }
        }
        return user32 != null;
    }

    static User32 user32() {
        return available() ? user32 : null;
    }

    static int pid() {
        return available() ? kernel32.GetCurrentProcessId() : -1;
    }

    static int threadId() {
        return available() ? kernel32.GetCurrentThreadId() : 0;
    }

    static void bringToFront(Pointer w) {
        if (!available() || w == null) {
            return;
        }
        user32.ShowWindow(w, user32.IsIconic(w) ? SW_RESTORE : SW_SHOW);
        Pointer fg = user32.GetForegroundWindow();
        int me = threadId();
        int other = fg == null ? 0 : user32.GetWindowThreadProcessId(fg, new int[1]);
        boolean attached = other != 0 && other != me && user32.AttachThreadInput(me, other, true);
        try {
            user32.BringWindowToTop(w);
            user32.SetForegroundWindow(w);
        } finally {
            if (attached) {
                user32.AttachThreadInput(me, other, false);
            }
        }
        int flags = 0x0001 | 0x0002 | 0x0040;
        user32.SetWindowPos(w, Pointer.createConstant(-1), 0, 0, 0, 0, flags);
        user32.SetWindowPos(w, Pointer.createConstant(-2), 0, 0, 0, 0, flags);
    }

    static void monitorsOff() {
        if (available()) {
            user32.PostMessageA(Pointer.createConstant(0xFFFF), WM_SYSCOMMAND, SC_MONITORPOWER, 2);
        }
    }

    static String title(Pointer hwnd) {
        char[] b = new char[512];
        int n = user32.GetWindowTextW(hwnd, b, b.length);
        return n <= 0 ? "" : new String(b, 0, n);
    }

    static int processOf(Pointer hwnd) {
        int[] pid = new int[1];
        user32.GetWindowThreadProcessId(hwnd, pid);
        return pid[0];
    }

    static final class Window {
        final Pointer hwnd;
        final int pid;
        final String title;

        Window(Pointer hwnd, int pid, String title) {
            this.hwnd = hwnd;
            this.pid = pid;
            this.title = title;
        }
    }

    static List<Window> windows() {
        final List<Window> out = new ArrayList<Window>();
        if (!available()) {
            return out;
        }
        final int self = pid();
        user32.EnumWindows(new EnumProc() {
            @Override
            public boolean callback(Pointer hwnd, Pointer data) {
                if (user32.IsWindowVisible(hwnd)) {
                    String t = title(hwnd);
                    int p = processOf(hwnd);
                    if (!t.trim().isEmpty() && p != self) {
                        out.add(new Window(hwnd, p, t));
                    }
                }
                return true;
            }
        }, null);
        return out;
    }

    static final class Hotkey {
        private volatile int thread;
        private volatile boolean registered;

        boolean start(final int vk, final int mods, final Runnable hit) {
            if (!available() || vk <= 0) {
                return false;
            }
            final Object lock = new Object();
            final boolean[] done = new boolean[1];
            Thread t = new Thread(() -> {
                this.thread = threadId();
                this.registered = user32.RegisterHotKey(null, 1, mods | MOD_NOREPEAT, vk);
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
                if (!this.registered) {
                    return;
                }
                Memory msg = new Memory(64);
                try {
                    while (user32.GetMessageA(msg, null, 0, 0) > 0) {
                        if (msg.getInt(Pointer.SIZE) == WM_HOTKEY) {
                            hit.run();
                        }
                    }
                } finally {
                    user32.UnregisterHotKey(null, 1);
                    this.registered = false;
                }
            }, "Atlas mommy hotkey");
            t.setDaemon(true);
            t.start();
            synchronized (lock) {
                long until = System.currentTimeMillis() + 1000L;
                while (!done[0] && System.currentTimeMillis() < until) {
                    try {
                        lock.wait(100L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            return this.registered;
        }

        void stop() {
            int th = this.thread;
            if (th != 0 && available()) {
                user32.PostThreadMessageA(th, 0x0012, 0, 0);
            }
            this.thread = 0;
        }
    }
}
