package atlas.ui.impl.clickgui.atlas;

import atlas.property.Property;

import java.util.function.Predicate;

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
            new Rule("BlockHit", "lag-chance", p -> p.isVisible() && number(p) > 0.0F,
                    "Holds your packets at random, on top of any other module that holds them (FakeLag). "
                            + "That stacking is a common cause of damage mitigation. 0 is safest."),

            new Rule("BackTrack", "range", p -> number(p) > 3.0F,
                    "Above 3.0, Intave's AttackRaytrace check flags hits (seen at 3.0011 blocks). "
                            + "3.0 or less avoids it."),
    };

    private RiskRules() {
    }

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

    private static float number(Property<?> property) {
        Object value = property.getValue();
        return value instanceof Number ? ((Number) value).floatValue() : 0.0F;
    }
}
