package atlas.util;

import atlas.Atlas;
import atlas.module.Module;
import atlas.module.modules.KillAura;
import atlas.module.modules.KillAura2;
import net.minecraft.entity.EntityLivingBase;

/**
 * Whichever aura is running, for the modules that follow it (2026-10-15).
 *
 * Until now some twenty modules asked the old KillAura alone, so with
 * KillAura2 on they saw no aura at all: TargetHUD and TargetESP showed
 * nothing, Backtrack fell back to the last hit, BlockHit raised the sword on
 * top of the aura's own block, AntiBot, AutoTool, HitSelect and the rest
 * acted as if the player fought by hand. The two auras switch each other off,
 * so at most one answers.
 */
public final class Auras {
    private Auras() {
    }

    private static KillAura old() {
        Module module = Atlas.moduleManager == null ? null : Atlas.moduleManager.modules.get(KillAura.class);
        return module instanceof KillAura ? (KillAura) module : null;
    }

    private static KillAura2 rewrite() {
        Module module = Atlas.moduleManager == null ? null : Atlas.moduleManager.modules.get(KillAura2.class);
        return module instanceof KillAura2 ? (KillAura2) module : null;
    }

    /** Either aura is on. */
    public static boolean anyEnabled() {
        KillAura old = old();
        KillAura2 rewrite = rewrite();
        return old != null && old.isEnabled() || rewrite != null && rewrite.isEnabled();
    }

    /** The running aura's target, or null. */
    public static EntityLivingBase target() {
        KillAura2 rewrite = rewrite();
        if (rewrite != null && rewrite.isEnabled()) {
            return rewrite.getTarget();
        }
        KillAura old = old();
        return old != null && old.isEnabled() ? old.getTarget() : null;
    }

    /** The running aura's target while it may attack (not held back by Scaffold, require-press, weapons). */
    public static EntityLivingBase attackingTarget() {
        KillAura2 rewrite = rewrite();
        if (rewrite != null && rewrite.isEnabled()) {
            return rewrite.isAttackAllowed() ? rewrite.getTarget() : null;
        }
        KillAura old = old();
        return old != null && old.isEnabled() && old.isAttackAllowed() ? old.getTarget() : null;
    }

    /** The running aura blocks by itself (its auto-block is not off). */
    public static boolean blocksByItself() {
        KillAura2 rewrite = rewrite();
        if (rewrite != null && rewrite.isEnabled()) {
            return rewrite.blocksByItself();
        }
        KillAura old = old();
        return old != null && old.isEnabled() && old.autoBlock.getValue() != 0;
    }

    /** The running aura's attack range, or {@code fallback}. */
    public static float attackRange(float fallback) {
        KillAura2 rewrite = rewrite();
        if (rewrite != null && rewrite.isEnabled()) {
            return rewrite.attackRange.getValue();
        }
        KillAura old = old();
        return old != null && old.isEnabled() ? old.attackRange.getValue() : fallback;
    }
}
