package atlas.module.modules;

import atlas.module.Module;
import atlas.util.client.RawMouseHelper;
import net.java.games.input.Controller;
import net.java.games.input.ControllerEnvironment;
import net.java.games.input.Mouse;
import net.minecraft.client.Minecraft;
import net.minecraft.util.MouseHelper;

import java.lang.reflect.Constructor;

public class MouseRawInput extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static Mouse mouse;
    /* Atomic, not volatile: the input thread did deltaX += n while the client
       thread read and then zeroed it, and a movement landing between the read
       and the zero was lost. */
    private static final java.util.concurrent.atomic.AtomicInteger deltaX = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger deltaY = new java.util.concurrent.atomic.AtomicInteger();
    private static volatile boolean running;
    private static Thread inputThread;

    private MouseHelper previousMouseHelper;

    public MouseRawInput() {
        super("MouseRawInput", false);
    }

    @Override
    public void onEnabled() {
        this.previousMouseHelper = mc.mouseHelper;
        mc.mouseHelper = new RawMouseHelper();
        startInputThread();
    }

    @Override
    public void onDisabled() {
        running = false;
        mouse = null;
        deltaX.set(0);
        deltaY.set(0);
        if (this.previousMouseHelper != null) {
            mc.mouseHelper = this.previousMouseHelper;
            this.previousMouseHelper = null;
        } else {
            mc.mouseHelper = new MouseHelper();
        }
    }

    public static int consumeDeltaX() {
        return deltaX.getAndSet(0);
    }

    public static int consumeDeltaY() {
        return deltaY.getAndSet(0);
    }

    /** A raw mouse has been found and is being read. */
    public static boolean hasMouse() {
        return running && mouse != null;
    }

    /** Drops whatever has piled up. */
    public static void discard() {
        deltaX.set(0);
        deltaY.set(0);
    }

    private static void startInputThread() {
        if (inputThread != null && inputThread.isAlive()) {
            running = true;
            return;
        }
        running = true;
        inputThread = new Thread(() -> {
            while (running) {
                try {
                    if (mouse == null) {
                        mouse = findMouse();
                    } else if (mouse.poll()) {
                        int x = (int) mouse.getX().getPollData();
                        int y = (int) mouse.getY().getPollData();
                        /* Polled either way, so the device's own count does
                           not build up; kept only while the game has the
                           mouse. In a menu, or tabbed out, the movement is the
                           cursor's, not the view's. */
                        if (mc.inGameHasFocus && mc.currentScreen == null) {
                            deltaX.addAndGet(x);
                            deltaY.addAndGet(y);
                        }
                    } else {
                        mouse = null;
                    }
                    Thread.sleep(1L);
                } catch (Throwable ignored) {
                    mouse = null;
                    candidates = null;
                    try {
                        Thread.sleep(250L);
                    } catch (InterruptedException ignoredInterrupted) {
                        Thread.currentThread().interrupt();
                        running = false;
                    }
                }
            }
        }, "MouseRawInput");
        inputThread.setDaemon(true);
        inputThread.start();
    }

    @SuppressWarnings("unchecked")
    private static ControllerEnvironment createDefaultEnvironment() throws ReflectiveOperationException {
        Constructor<ControllerEnvironment> constructor = (Constructor<ControllerEnvironment>) Class
                .forName("net.java.games.input.DefaultControllerEnvironment").getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    /* 2026-10-26 (review): the mice found by the last scan, and when. findMouse used to build a new controller
       environment and list every device on each call -- every millisecond while no mouse moved. Now the list is
       built at most every RESCAN_MS (a mouse plugged in later is still found) and only polled in between. */
    private static final long RESCAN_MS = 5000L;
    private static Mouse[] candidates;
    private static long candidatesAt;

    private static Mouse findMouse() throws ReflectiveOperationException {
        long now = System.currentTimeMillis();
        if (candidates == null || now - candidatesAt >= RESCAN_MS) {
            java.util.List<Mouse> found = new java.util.ArrayList<Mouse>();
            for (Controller controller : createDefaultEnvironment().getControllers()) {
                if (controller.getType() == Controller.Type.MOUSE && controller instanceof Mouse) {
                    found.add((Mouse) controller);
                }
            }
            candidates = found.toArray(new Mouse[0]);
            candidatesAt = now;
        }
        /* The one that moves is the one in use (a laptop lists its touchpad too). */
        for (Mouse candidate : candidates) {
            if (candidate.poll()
                    && (candidate.getX().getPollData() != 0.0F || candidate.getY().getPollData() != 0.0F)) {
                return candidate;
            }
        }
        return null;
    }
}