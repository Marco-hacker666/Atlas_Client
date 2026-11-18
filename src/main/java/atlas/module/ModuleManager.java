package atlas.module;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.KeyEvent;
import atlas.events.TickEvent;
import atlas.module.modules.GuiModule;
import atlas.module.modules.HUD;
import atlas.util.ChatUtil;
import atlas.util.SoundUtil;

import java.util.LinkedHashMap;

public class ModuleManager {
    private boolean sound = false;
    public final LinkedHashMap<Class<?>, Module> modules = new LinkedHashMap<>();

    public Module getModule(String string) {
        if (string == null) {
            return null;
        }

        Module module = null;
        for (Module candidate : this.modules.values()) {
            if (sameName(candidate.getName(), string)) {
                module = candidate;
                break;
            }
        }
        if (module == null) {
            for (Module candidate : this.modules.values()) {
                if (sameName(candidate.getClass().getSimpleName(), string)) {
                    module = candidate;
                    break;
                }
            }
        }

        return module != null ? module : atlas.module.ModuleBundle.byName(string);
    }

    public static String squash(String name) {
        return name.replace(" ", "").toLowerCase(java.util.Locale.ROOT);
    }

    public static boolean sameName(String name, String wanted) {
        if (name.equalsIgnoreCase(wanted)) {
            return true;
        }
        if (name.indexOf(' ') < 0 && wanted.indexOf(' ') < 0) {
            return false;
        }
        return squash(name).equals(squash(wanted));
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
                String message = String.format("%s%s: %s&r", Atlas.clientName, module.getName(), status);
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
