package atlas.mommy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class Panic {
    public static final class Plan {
        public boolean mute = true;
        public int hide = 1;
        public boolean pauseGame = true;
        public boolean leaveServer;
        public boolean quitGame;
        public boolean cover = true;
        public boolean closeApps;
        public int action;
        public boolean force;
        public int shell;
        public int delaySeconds = 3;
        public boolean rejoin = true;

        public int restoreKey;
        public int modifier;
    }

    public static final int ACTION_NOTHING = 0;
    public static final int ACTION_BLACK = 1;
    public static final int ACTION_SLEEP = 2;
    public static final int ACTION_SHUTDOWN = 3;
    public static final int ACTION_LOCK = 4;
    public static final int ACTION_CUSTOM = 5;
    public static final String[] ACTIONS = {"Nothing", "Black screen", "Sleep", "Shut down", "Lock", "Custom"};
    public static final String[] HIDES = {"Off", "Minimize", "Hide"};

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Atlas mommy");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean active;
    private static volatile long pendingAt;
    private static ScheduledFuture<?> pending;
    private static volatile boolean systemMutedByUs;
    private static volatile Thread muteThread;
    private static float gameVolume = -1.0F;
    private static ServerData left;
    private static boolean openedMenu;
    private static int hidden;
    private static final WinApi.Hotkey HOTKEY = new WinApi.Hotkey();

    private Panic() {
    }

    public static boolean active() {
        return active;
    }

    public static long countdown() {
        long at = pendingAt;
        return at <= 0L ? -1L : Math.max(0L, at - System.currentTimeMillis());
    }

    public static void fire(final Plan plan) {
        if (active) {
            return;
        }
        active = true;
        System.out.println("[Atlas] mommy: fired (hide " + plan.hide + ", action " + plan.action + ")");
        openedMenu = false;
        left = null;
        hidden = 0;

        if (plan.mute) {
            gameVolume = mc.gameSettings.getSoundLevel(SoundCategory.MASTER);
            mc.gameSettings.setSoundLevel(SoundCategory.MASTER, 0.0F);
        }
        if (plan.leaveServer && mc.theWorld != null && !mc.isIntegratedServerRunning()) {
            left = mc.getCurrentServerData();
            mc.theWorld.sendQuittingDisconnectingPacket();
            mc.loadWorld(null);
            mc.displayGuiScreen(new GuiMultiplayer(new GuiMainMenu()));
        } else if (plan.pauseGame && mc.theWorld != null && mc.currentScreen == null) {
            mc.displayInGameMenu();
            openedMenu = true;
        }
        if (plan.hide > 0) {
            PanicPlatform.hideGame(plan.hide);
            hidden = plan.hide;
        }
        if (plan.action == ACTION_BLACK) {
            PanicPlatform.blackScreen();
        } else if (plan.action == ACTION_LOCK) {
            PanicPlatform.lock();
        }
        if (PanicPlatform.OS == PanicCommands.WINDOWS && plan.restoreKey > 0) {
            int vk = PanicKeys.toVk(plan.restoreKey);
            if (!HOTKEY.start(vk, PanicKeys.toWinMods(plan.modifier), () -> mc.addScheduledTask(Panic::restoreFromKey))
                    && hidden == 2) {
                PanicPlatform.showGame();
                PanicPlatform.hideGame(1);
                hidden = 1;
            }
        }

        final PanicSetup setup = PanicSetup.get();
        if (plan.mute) {
            Thread m = new Thread(() -> {
                boolean was = PanicPlatform.mute(true);
                systemMutedByUs = !was;

                mc.addScheduledTask(Panic::restoreGameVolume);
            }, "Atlas mommy mute");
            m.setDaemon(true);
            muteThread = m;
            m.start();
        }
        WORKER.execute(() -> {
            if (plan.closeApps) {
                PanicPlatform.closeApps(setup.close, setup.closeForce);
            }

            if (plan.cover && !setup.cover.isEmpty() && plan.action != ACTION_LOCK) {
                PanicPlatform.cover(setup.cover);
            }
            if (plan.action == ACTION_CUSTOM) {
                PanicPlatform.custom(plan.shell);
            }
        });

        boolean late = plan.action == ACTION_SLEEP || plan.action == ACTION_SHUTDOWN || plan.quitGame;
        if (late) {
            long delay = Math.max(0, plan.delaySeconds) * 1000L;
            pendingAt = System.currentTimeMillis() + delay;
            pending = WORKER.schedule(() -> {
                pendingAt = 0L;
                if (!active) {
                    return;
                }
                if (plan.action == ACTION_SLEEP) {
                    PanicPlatform.sleep();
                } else if (plan.action == ACTION_SHUTDOWN) {
                    PanicPlatform.shutdown(plan.force);
                }

                mc.addScheduledTask(() -> {
                    restoreGameVolume();
                    if (plan.quitGame) {
                        mc.shutdown();
                    }
                });
            }, delay, TimeUnit.MILLISECONDS);
        }
        rememberRejoin = plan.rejoin;
    }

    private static boolean rememberRejoin = true;

    private static void restoreFromKey() {
        restore();
    }

    public static void restore() {
        if (!active) {
            return;
        }
        active = false;
        System.out.println("[Atlas] mommy: restore (window " + (PanicPlatform.gameWindow != null ? "known" : "unknown")
                + ", hidden " + hidden + ")");
        ScheduledFuture<?> p = pending;
        if (p != null) {
            p.cancel(false);
        }
        pending = null;
        pendingAt = 0L;
        HOTKEY.stop();
        if (hidden > 0) {
            PanicPlatform.showGame();
            hidden = 0;
        }
        restoreGameVolume();
        WORKER.execute(() -> {
            Thread m = muteThread;
            if (m != null) {
                try {
                    m.join(10000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            muteThread = null;
            if (systemMutedByUs && !active) {
                systemMutedByUs = false;
                PanicPlatform.mute(false);
            }
        });
        if (openedMenu && mc.currentScreen instanceof GuiIngameMenu) {
            mc.displayGuiScreen(null);
        }
        openedMenu = false;
        ServerData s = left;
        left = null;
        if (s != null && rememberRejoin && mc.theWorld == null) {
            mc.displayGuiScreen(new GuiConnecting(new GuiMultiplayer(new GuiMainMenu()), mc, s));
        }
    }

    private static void restoreGameVolume() {
        if (gameVolume >= 0.0F) {
            mc.gameSettings.setSoundLevel(SoundCategory.MASTER, gameVolume);
            gameVolume = -1.0F;
        }
    }

    public static void fireLater(final Plan plan) {
        mc.addScheduledTask(() -> fire(plan));
    }

    public static void restoreLater() {
        System.out.println("[Atlas] mommy: restore asked (phone or hotkey), active " + active);
        mc.addScheduledTask(Panic::restore);
    }
}
