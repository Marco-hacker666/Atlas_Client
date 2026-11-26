package atlas.management;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class StackSampler {
    static final long PERIOD_MS = 100L;
    private static final int TOP = 12;
    private static final String[] NOT_A_MOD = {
            "java.", "javax.", "sun.", "jdk.", "com.sun.", "net.minecraft.", "net.minecraftforge.", "cpw.mods.",
            "org.lwjgl.", "paulscode.", "io.netty.", "com.google.", "com.mojang.", "org.apache.", "it.unimi.",
            "gnu.trove.", "oshi.", "org.spongepowered.", "com.ibm.", "org.objectweb."
    };
    private static final String[] ATLAS_PLACES = {
            "atlas.module.modules.", "atlas.ui.", "atlas.nickresolver.", "atlas.script.", "atlas.mixin."
    };

    private final Thread client;
    private final java.util.function.BooleanSupplier active;
    private final Tally clientTally = new Tally();
    private final Tally serverTally = new Tally();
    private Thread server;
    private Thread worker;

    public StackSampler(Thread client, java.util.function.BooleanSupplier active) {
        this.client = client;
        this.active = active;
    }

    public synchronized void start() {
        if (this.worker != null) {
            return;
        }
        Thread thread = new Thread(this::loop, "Atlas sampler");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        this.worker = thread;
        thread.start();
    }

    public synchronized void setServer(Thread server) {
        this.server = server;
    }

    private void loop() {
        while (true) {
            try {
                Thread.sleep(PERIOD_MS);
            } catch (InterruptedException e) {
                return;
            }
            try {
                if (!this.active.getAsBoolean()) {
                    continue;
                }
                sample();
            } catch (Throwable ignored) {
            }
        }
    }

    private void sample() {
        StackTraceElement[] clientStack = this.client.getStackTrace();
        Thread serverThread;
        synchronized (this) {
            serverThread = this.server;
        }
        StackTraceElement[] serverStack = serverThread != null && serverThread.isAlive()
                ? serverThread.getStackTrace() : null;
        synchronized (this) {
            this.clientTally.add(clientStack);
            if (serverStack != null) {
                this.serverTally.add(serverStack);
            }
        }
    }

    public synchronized List<String> take() {
        List<String> lines = new ArrayList<String>();
        this.clientTally.write("client thread", lines);
        if (this.serverTally.samples > 0) {
            this.serverTally.write("server thread", lines);
        }
        this.clientTally.reset();
        this.serverTally.reset();
        return lines;
    }

    static String owner(StackTraceElement[] stack) {
        if (stack == null || stack.length == 0) {
            return "idle";
        }
        for (int i = 0; i < stack.length; i++) {
            String type = stack[i].getClassName();
            if (type.startsWith("atlas.")) {
                return "Atlas " + atlasPlace(stack, i);
            }
            if (isMixinHandler(stack[i].getMethodName())) {
                return "mixin " + simple(type) + "." + stack[i].getMethodName();
            }
            if (type.startsWith("ASMEventHandler")) {
                continue;
            }
            if (type.indexOf('.') < 0 || type.startsWith("net.optifine.")) {
                return "OptiFine " + simple(type);
            }
            if (isMod(type)) {
                return "mod " + modRoot(type);
            }
        }
        for (StackTraceElement frame : stack) {
            if (frame.getClassName().startsWith("net.minecraft.") && !frame.getClassName().startsWith("net.minecraft.launchwrapper.")) {
                return "Minecraft " + simple(frame.getClassName()) + "." + frame.getMethodName();
            }
        }
        return "other " + simple(stack[0].getClassName()) + "." + stack[0].getMethodName();
    }

    static boolean atlasAnywhere(StackTraceElement[] stack) {
        if (stack == null) {
            return false;
        }
        for (StackTraceElement frame : stack) {
            if (frame.getClassName().startsWith("atlas.")) {
                return true;
            }
        }
        return false;
    }

    private static String atlasPlace(StackTraceElement[] stack, int from) {
        for (int i = from; i < stack.length; i++) {
            String type = stack[i].getClassName();
            if (!type.startsWith("atlas.")) {
                continue;
            }
            for (String place : ATLAS_PLACES) {
                if (type.startsWith(place)) {
                    return simple(type) + "." + stack[i].getMethodName();
                }
            }
        }
        return simple(stack[from].getClassName()) + "." + stack[from].getMethodName();
    }

    private static boolean isMixinHandler(String method) {
        return method.startsWith("handler$") || method.startsWith("redirect$") || method.startsWith("modify$")
                || method.startsWith("constant$") || method.startsWith("localvar$");
    }

    private static boolean isMod(String type) {
        for (String prefix : NOT_A_MOD) {
            if (type.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    private static String modRoot(String type) {
        String[] parts = type.split("\\.");
        if (parts.length <= 2) {
            return type;
        }
        return parts.length > 3 ? parts[0] + "." + parts[1] + "." + parts[2] : parts[0] + "." + parts[1];
    }

    private static String simple(String type) {
        int dot = type.lastIndexOf('.');
        String name = dot < 0 ? type : type.substring(dot + 1);
        int dollar = name.indexOf('$');
        return dollar > 0 ? name.substring(0, dollar) : name;
    }

    private static final class Tally {
        private final Map<String, int[]> owners = new HashMap<String, int[]>();
        private int samples;
        private int atlas;

        void add(StackTraceElement[] stack) {
            this.samples++;
            if (atlasAnywhere(stack)) {
                this.atlas++;
            }
            String owner = owner(stack);
            int[] count = this.owners.get(owner);
            if (count == null) {
                count = new int[1];
                this.owners.put(owner, count);
            }
            count[0]++;
        }

        void write(String title, List<String> lines) {
            if (this.samples == 0) {
                lines.add(title + ": no samples");
                return;
            }
            lines.add(String.format("%s: %d samples, Atlas somewhere in the stack %d%%", title, this.samples,
                    Math.round(100.0 * this.atlas / this.samples)));
            List<Map.Entry<String, int[]>> sorted = new ArrayList<Map.Entry<String, int[]>>(this.owners.entrySet());
            Collections.sort(sorted, new Comparator<Map.Entry<String, int[]>>() {
                @Override
                public int compare(Map.Entry<String, int[]> a, Map.Entry<String, int[]> b) {
                    return Integer.compare(b.getValue()[0], a.getValue()[0]);
                }
            });
            for (int i = 0; i < Math.min(TOP, sorted.size()); i++) {
                Map.Entry<String, int[]> entry = sorted.get(i);
                lines.add(String.format("  %3d%%  %s", Math.round(100.0 * entry.getValue()[0] / this.samples),
                        entry.getKey()));
            }
        }

        void reset() {
            this.owners.clear();
            this.samples = 0;
            this.atlas = 0;
        }
    }
}
