package myau.module;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.KeyEvent;
import myau.events.TickEvent;
import myau.module.modules.GuiModule;
import myau.module.modules.HUD;
import myau.util.ChatUtil;
import myau.util.SoundUtil;

import java.util.LinkedHashMap;

public class ModuleManager {
    private boolean sound = false;
    public final LinkedHashMap<Class<?>, Module> modules = new LinkedHashMap<>();

    public Module getModule(String string) {
        if (string == null) {
            return null;
        }
        /* 2026-10-19: names may have spaces ("Silent Aura"), and a command
           cannot type one, so spaces do not count: "silentaura" finds it. */
        String wanted = squash(string);
        Module module = this.modules.values().stream().filter(mD -> squash(mD.getName()).equals(wanted)).findFirst().orElse(null);
        if (module == null) {
            /* A renamed module by its old name, which is its class name
               (KillAura -> Silent Aura, BlockHit -> AutoBlock): old configs,
               binds and lists keep working. */
            module = this.modules.values().stream()
                    .filter(mD -> mD.getClass().getSimpleName().equalsIgnoreCase(wanted)).findFirst().orElse(null);
        }
        /* 2026-10-11: a merged module (ModuleBundle) by its own name, for
           FlagResponder and anything else that names modules. */
        return module != null ? module : myau.module.ModuleBundle.byName(string);
    }

    /** A module name as looked up: no spaces, any case. */
    public static String squash(String name) {
        return name.replace(" ", "").toLowerCase(java.util.Locale.ROOT);
    }

    public Module getModule(Class<?> clazz){
        return this.modules.get(clazz);
    }

    public void playSound() {
        this.sound = true;
    }

    @EventTarget
    public void onKey(KeyEvent event) {
        for (Module module : this.modules.values()) {
            if (module.getKey() != event.getKey()) {
                continue;
            }
            boolean shouldNotify = module.toggle();
            HUD hud = (HUD) this.modules.get(HUD.class);
            if (hud != null && shouldNotify) {
                shouldNotify = hud.toggleAlerts.getValue();
            }
            if(module instanceof GuiModule){
                shouldNotify = false;
            }
            if (shouldNotify) {
                String status = module.isEnabled() ? "&a&lON" : "&c&lOFF";
                String message = String.format("%s%s: %s&r", Myau.clientName, module.getName(), status);
                ChatUtil.sendFormatted(message);
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() == EventType.PRE) {
            if (this.sound) {
                this.sound = false;
                SoundUtil.playSound("random.click");
            }
        }
    }
}
