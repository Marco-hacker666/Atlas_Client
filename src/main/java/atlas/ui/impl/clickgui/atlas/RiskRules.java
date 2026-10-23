package atlas.ui.impl.clickgui.atlas;

import atlas.property.Property;

import java.util.function.Predicate;

/**
 * Setting values worth a warning in the menu (2026-10-23,
 * docs/GUI-AUDIT-2026-10-23.md F1, owner D4). Only values our own logs showed
 * to be trouble go in; a rule is a note, never a limit -- the value can still
 * be set.
 *
 * Matched by the module's class name and the setting's key, so a module's
 * menu name can change without breaking a rule.
 */
final class RiskRules {

    static final class Rule {
        final String module;
        final String key;
        final Predicate<Property<?>> when;
        final String why;

        Rule(String module, String key, Predicate<Property<?>> when, String why) {
            this.module = module;
            this.key = key;
            this.when = when;
            this.why = why;
        }
    }

    static final Rule[] RULES = {
            /* 2026-10-21: the mitigation the owner hit was most likely this
               lag stacked on FakeLag's. Shown only in Slinky mode, where the
               setting is used (isVisible). */
            new Rule("BlockHit", "lag-chance", p -> p.isVisible() && number(p) > 0.0F,
                    "Holds your packets at random, on top of any other module that holds them (FakeLag). "
                            + "That stacking is a common cause of damage mitigation. 0 is safest."),
            /* Intave's AttackRaytrace flagged hits at 3.0011 blocks; the default
               came down from 3.5 to 3.0 for it (2.6.1, 2026-10-21). */
            new Rule("BackTrack", "range", p -> number(p) > 3.0F,
                    "Above 3.0, Intave's AttackRaytrace check flags hits (seen at 3.0011 blocks). "
                            + "3.0 or less avoids it."),
    };

    private RiskRules() {
    }

    /** Why this setting's value is risky, or null when it is not (or no rule covers it). */
    static String warning(Class<?> module, Property<?> property) {
        if (module == null || property == null) {
            return null;
        }
        String type = module.getSimpleName();
        for (Rule rule : RULES) {
            if (rule.module.equals(type) && rule.key.equals(property.getName()) && rule.when.test(property)) {
                return rule.why;
            }
        }
        return null;
    }

    /** Whether any rule names this module at all, so the others need not be looked through. */
    static boolean covers(Class<?> module) {
        if (module == null) {
            return false;
        }
        String type = module.getSimpleName();
        for (Rule rule : RULES) {
            if (rule.module.equals(type)) {
                return true;
            }
        }
        return false;
    }

    /** The value in effect, as a number; 0 for a setting that is not one. */
    private static float number(Property<?> property) {
        Object value = property.getValue();
        return value instanceof Number ? ((Number) value).floatValue() : 0.0F;
    }
}
