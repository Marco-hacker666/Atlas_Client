package atlas.module.modules;

import atlas.mommy.Panic;
import atlas.mommy.PanicCommands;
import atlas.mommy.PanicKeys;
import atlas.mommy.PanicRemote;
import atlas.mommy.PanicTrigger;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.KeyProperty;
import atlas.property.properties.ModeProperty;
import atlas.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;

public class MommyIsHere extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final KeyProperty panicKey = new KeyProperty("panic-key", Keyboard.KEY_NONE);
    public final ModeProperty trigger = new ModeProperty("trigger", PanicTrigger.DOUBLE_TAP, PanicTrigger.MODES);
    public final ModeProperty modifier = new ModeProperty("modifier", PanicKeys.MOD_NONE, PanicKeys.MODIFIERS);
    public final KeyProperty restoreKey = new KeyProperty("restore-key", Keyboard.KEY_NONE);
    public final BooleanProperty mute = new BooleanProperty("mute", true);
    public final ModeProperty hide = new ModeProperty("hide-game", 1, Panic.HIDES);
    public final BooleanProperty cover = new BooleanProperty("cover", true);

    public final BooleanProperty closeApps = new BooleanProperty("close-apps", false) {
        @Override
        public boolean setValue(Object object) {
            boolean turningOn = Boolean.TRUE.equals(object) && !Boolean.TRUE.equals(this.getValue());
            boolean ok = super.setValue(object);
            if (ok && turningOn && !atlas.config.Config.loading) {
                mc.addScheduledTask(() -> mc.displayGuiScreen(new atlas.mommy.ProcessPickerScreen(mc.currentScreen)));
            }
            return ok;
        }
    };
    public final BooleanProperty pauseGame = new BooleanProperty("pause-game", true);
    public final BooleanProperty leaveServer = new BooleanProperty("leave-server", false);
    public final BooleanProperty rejoin = new BooleanProperty("auto-rejoin", true, this.leaveServer::getValue);
    public final ModeProperty action = new ModeProperty("action", Panic.ACTION_NOTHING, Panic.ACTIONS);
    public final BooleanProperty force = new BooleanProperty("force", false,
            () -> this.action.getValue() == Panic.ACTION_SHUTDOWN);
    public final ModeProperty shell = new ModeProperty("custom-shell", PanicCommands.os() == PanicCommands.WINDOWS
            ? PanicCommands.SHELL_CMD : PanicCommands.SHELL_SH, PanicCommands.SHELLS,
            () -> this.action.getValue() == Panic.ACTION_CUSTOM);

    public final atlas.mommy.CommandProperty customCommand = new atlas.mommy.CommandProperty("custom-command",
            this.shell::getValue, () -> this.action.getValue() == Panic.ACTION_CUSTOM);
    public final BooleanProperty quitGame = new BooleanProperty("quit-game", false);
    public final IntProperty delay = new IntProperty("delay", 3, 0, 10,
            () -> this.quitGame.getValue() || this.action.getValue() == Panic.ACTION_SLEEP
                    || this.action.getValue() == Panic.ACTION_SHUTDOWN);

    public final BooleanProperty phone = new BooleanProperty("phone-remote", false) {
        @Override
        public boolean setValue(Object object) {
            boolean turningOn = Boolean.TRUE.equals(object) && !Boolean.TRUE.equals(this.getValue());
            boolean ok = super.setValue(object);
            if (ok && turningOn && !atlas.config.Config.loading) {
                mc.addScheduledTask(MommyIsHere.this::pairPhone);
            }
            return ok;
        }
    };

    private final PanicTrigger gesture = new PanicTrigger();
    private boolean restoreWasDown;
    private long windowNotedAt;
    private boolean hinted;
    private long remoteTriedAt;

    public MommyIsHere() {
        super("MOMMY IS HERE!!!", false);
        FMLCommonHandler.instance().bus().register(this);
    }

    public Panic.Plan plan() {
        Panic.Plan p = new Panic.Plan();
        p.mute = this.mute.getValue();
        p.hide = this.hide.getValue();
        p.cover = this.cover.getValue();
        p.closeApps = this.closeApps.getValue();
        p.pauseGame = this.pauseGame.getValue();
        p.leaveServer = this.leaveServer.getValue();
        p.rejoin = this.rejoin.getValue();
        p.action = this.action.getValue();
        p.force = this.force.getValue();
        p.shell = this.shell.getValue();
        p.quitGame = this.quitGame.getValue();
        p.delaySeconds = this.delay.getValue();
        int rk = this.restoreKey.getValue();
        p.restoreKey = rk != Keyboard.KEY_NONE ? rk : this.panicKey.getValue();
        p.modifier = this.modifier.getValue();
        return p;
    }

    @Override
    public void onEnabled() {
        this.gesture.reset();
        if (!this.hinted && this.panicKey.getValue() == Keyboard.KEY_NONE && mc.thePlayer != null) {
            this.hinted = true;
            ChatUtil.sendFormatted("&b[Atlas] &7MOMMY IS HERE!!! is on: set &fpanic-key&7 in its settings, and "
                    + "&f.mommy setup&7 for what it brings up and closes.&r");
        }
    }

    @Override
    public void onDisabled() {
        this.gesture.reset();
    }

    public void pairPhone() {
        if (!PanicRemote.start(this::plan)) {
            ChatUtil.sendFormatted("&c[Atlas] The phone remote could not open port "
                    + atlas.mommy.PanicSetup.get().remotePort + " (another program uses it?).&r");
            return;
        }
        atlas.mommy.PanicHooks.openPairing();
        ChatUtil.sendFormatted("&b[Atlas] &7Phone remote on: scan the QR code that opened in your browser (same Wi-Fi). "
                + "Or open on the phone: &f" + PanicRemote.phoneUrl().replaceAll("k=.*", "k=...") + "&r");
    }

    @SubscribeEvent
    public void onFrame(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        if (this.phone.getValue()) {
            long t = System.currentTimeMillis();
            if (!PanicRemote.running() && t - this.remoteTriedAt > 10_000L) {
                this.remoteTriedAt = t;
                PanicRemote.start(this::plan);
            }
        } else if (PanicRemote.running()) {
            PanicRemote.stop();
        }
        if (!this.isEnabled()) {
            return;
        }
        if (!Display.isActive()) {
            this.gesture.reset();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - this.windowNotedAt > 500L) {
            this.windowNotedAt = now;
            atlas.mommy.PanicHooks.noteGameWindow();
        }
        int key = this.panicKey.getValue();
        boolean typing = mc.currentScreen != null;
        if (Panic.active()) {
            int rk = this.restoreKey.getValue() != Keyboard.KEY_NONE ? this.restoreKey.getValue() : key;
            boolean down = rk != Keyboard.KEY_NONE && Keyboard.isKeyDown(rk) && PanicKeys.modifierDown(this.modifier.getValue())
                    && (!typing || PanicKeys.safeInMenus(rk) || mc.currentScreen instanceof net.minecraft.client.gui.GuiIngameMenu);
            if (down && !this.restoreWasDown) {
                Panic.restore();
            }
            this.restoreWasDown = down;
            this.gesture.reset();
            return;
        }
        this.restoreWasDown = true;
        if (key == Keyboard.KEY_NONE || typing && !PanicKeys.safeInMenus(key)) {
            this.gesture.feed(this.trigger.getValue(), false, now);
            return;
        }
        boolean down = Keyboard.isKeyDown(key) && PanicKeys.modifierDown(this.modifier.getValue());
        if (this.gesture.feed(this.trigger.getValue(), down, now)) {
            Panic.fire(this.plan());
        }
    }
}
