package atlas.util;

public final class DamageModel {
    private DamageModel() {
    }

    public static float[] expected(float weapon, int sharpness, int strength, int weakness, boolean crit,
                                   int armour, int protRaw) {
        return expected(weapon, sharpness, strength, weakness, crit, armour, protRaw, false, 0);
    }

    public static float[] expected(float weapon, int sharpness, int strength, int weakness, boolean crit,
                                   int armour, int protRaw, boolean blocking, int resistance) {
        float damage = (1.0F + weapon - 0.5F * weakness) * (1.0F + 1.3F * strength);
        if (crit) {
            damage *= 1.5F;
        }
        damage += 1.25F * sharpness;
        damage = Math.max(0.0F, damage);
        if (blocking && damage > 0.0F) {
            damage = (1.0F + damage) * 0.5F;
        }
        damage = damage * (25 - clamp(armour, 0, 25)) / 25.0F;
        if (resistance > 0) {
            damage = damage * (25 - clamp(resistance * 5, 0, 25)) / 25.0F;
        }
        int raw = clamp(protRaw, 0, 25);
        int least = (raw + 1) >> 1;
        int most = Math.min(20, least + (raw >> 1));
        least = Math.min(20, least);
        return new float[]{damage * (25 - most) / 25.0F, damage * (25 - least) / 25.0F};
    }

    public static int protectionPoints(int protectionLevel) {
        if (protectionLevel <= 0) {
            return 0;
        }
        return (int) ((6 + protectionLevel * protectionLevel) / 3.0F * 0.75F);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
