package atlas.util;

import atlas.Atlas;
import atlas.module.Module;
import atlas.module.modules.KillAura;
import net.minecraft.entity.EntityLivingBase;

public final class Auras {
    private Auras() {
    }

    private static KillAura old() {
        Module module = Atlas.moduleManager == null ? null : Atlas.moduleManager.modules.get(KillAura.class);
        return module instanceof KillAura ? (KillAura) module : null;
    }

    public static boolean anyEnabled() {
        KillAura old = old();
        return old != null && old.isEnabled();
    }

    public static EntityLivingBase target() {
        KillAura old = old();
        return old != null && old.isEnabled() ? old.getTarget() : null;
    }

    public static EntityLivingBase attackingTarget() {
        KillAura old = old();
        return old != null && old.isEnabled() && old.isAttackAllowed() ? old.getTarget() : null;
    }

    public static boolean blocksByItself() {
        KillAura old = old();
        return old != null && old.isEnabled() && old.blocksByItself();
    }

    public static float attackRange(float fallback) {
        KillAura old = old();
        return old != null && old.isEnabled() ? old.attackReach() : fallback;
    }
}
