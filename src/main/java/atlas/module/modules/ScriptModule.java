package atlas.module.modules;

// Ported from OpenSkid (GPL-3.0). Upstream's ScriptModule is an adapter over
// keystrokesmod script modules (keystrokesmod.module.Module plus its Setting
// types), a library that exists in neither project's tree, so there is
// nothing to port there. What this keeps is the behaviour that layer existed
// for: a loaded script is driven through the client's own module, and its
// hooks fire from the client's own events.
//
// The ported atlas.script.Script API exposes no settings, so unlike upstream
// there are no per-setting properties to mirror into the menu yet.
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.PacketEvent;
import atlas.events.TickEvent;
import atlas.module.Module;
import atlas.script.ScriptManager;
import net.minecraft.network.Packet;

public class ScriptModule extends Module {
    /** Packet summaries are short strings: scripts never see the live packet. */
    private static final int MAX_SUMMARY = 120;

    /** Scripts are being compiled; ScriptManager is locked meanwhile, so the hooks below wait it out unblocked. */
    private volatile boolean loading;

    public ScriptModule() {
        super("ScriptModule", false, false, "Loads script files and runs their hooks.");
    }

    @Override
    public void onEnabled() {
        /* 2026-10-30 (audit): compiling every script with javac froze the game on the client thread. Scripts only
           ever see strings and chat is now safe from any thread, so it runs on a thread of its own. */
        this.loading = true;
        Thread thread = new Thread(() -> {
            try {
                ScriptManager.getInstance().loadAll();
                ScriptManager.getInstance().restoreSaved();
            } finally {
                this.loading = false;
            }
        }, "Atlas-ScriptLoad");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void onDisabled() {
        ScriptManager.getInstance().disableAll();
    }

    @Override
    public String[] getSuffix() {
        if (this.loading) {
            return new String[]{"loading"};
        }
        int loaded = ScriptManager.getInstance().loadedCount();
        if (loaded == 0) {
            return new String[0];
        }
        return new String[]{loaded + " loaded"};
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || this.loading || event.getType() != EventType.POST) {
            return;
        }
        ScriptManager.getInstance().fireTick();
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || this.loading || event.getPacket() == null) {
            return;
        }
        String packetClass = event.getPacket().getClass().getSimpleName();
        String summary = this.summarize(event.getPacket());
        if (event.getType() == EventType.SEND) {
            if (!ScriptManager.getInstance().firePacketSend(packetClass, summary)) {
                event.setCancelled(true);
            }
        } else if (event.getType() == EventType.RECEIVE) {
            if (!ScriptManager.getInstance().firePacketReceive(packetClass, summary)) {
                event.setCancelled(true);
            }
        }
    }

    private String summarize(Packet<?> packet) {
        String text;
        try {
            text = String.valueOf(packet);
        } catch (Exception e) {
            return "";
        }
        if (text.length() > MAX_SUMMARY) {
            text = text.substring(0, MAX_SUMMARY);
        }
        return text;
    }
}
