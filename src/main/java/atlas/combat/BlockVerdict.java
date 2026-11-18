package atlas.combat;

import atlas.util.DamageModel;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

public final class BlockVerdict {
    private BlockVerdict() {
    }

    public static float[][] ranges(EntityPlayer attacker, EntityPlayerSP self) {
        ItemStack held = attacker.getHeldItem();
        float weapon = 0.0F;
        int sharpness = 0;
        if (held != null) {
            for (AttributeModifier modifier : held.getAttributeModifiers()
                    .get(SharedMonsterAttributes.attackDamage.getAttributeUnlocalizedName())) {
                if (modifier.getOperation() == 0) {
                    weapon += (float) modifier.getAmount();
                }
            }
            sharpness = EnchantmentHelper.getEnchantmentLevel(Enchantment.sharpness.effectId, held);
        }
        int strength = level(attacker.getActivePotionEffect(Potion.damageBoost));
        int weakness = level(attacker.getActivePotionEffect(Potion.weakness));
        int resistance = level(self.getActivePotionEffect(Potion.resistance));
        int protection = 0;
        for (ItemStack armour : self.inventory.armorInventory) {
            if (armour != null) {
                protection += DamageModel.protectionPoints(
                        EnchantmentHelper.getEnchantmentLevel(Enchantment.protection.effectId, armour));
            }
        }
        int armour = self.getTotalArmorValue();
        return new float[][]{
                DamageModel.expected(weapon, sharpness, strength, weakness, false, armour, protection, false, resistance),
                DamageModel.expected(weapon, sharpness, strength, weakness, true, armour, protection, false, resistance),
                DamageModel.expected(weapon, sharpness, strength, weakness, false, armour, protection, true, resistance),
                DamageModel.expected(weapon, sharpness, strength, weakness, true, armour, protection, true, resistance),
                half(DamageModel.expected(weapon, sharpness, strength, weakness, false, armour, protection, false, resistance)),
                half(DamageModel.expected(weapon, sharpness, strength, weakness, true, armour, protection, false, resistance))};
    }

    private static float[] half(float[] range) {
        return new float[]{range[0] * 0.5F, range[1] * 0.5F};
    }

    public static String classify(float lost, float[][] ranges, float tolerance) {
        boolean full = within(lost, ranges[0], tolerance) || within(lost, ranges[1], tolerance);
        boolean blocked = within(lost, ranges[2], tolerance) || within(lost, ranges[3], tolerance)
                || ranges.length > 5 && (within(lost, ranges[4], tolerance) || within(lost, ranges[5], tolerance));
        if (full && blocked) {
            return "AMBIG";
        }
        if (blocked) {
            return "REDUCED";
        }
        if (full) {
            return "FULL";
        }
        float leastBlocked = ranges.length > 5 ? Math.min(ranges[2][0], ranges[4][0]) : ranges[2][0];
        return lost < leastBlocked - tolerance ? "DIFF" : "OTHER";
    }

    private static boolean within(float value, float[] range, float tolerance) {
        return value >= range[0] - tolerance && value <= range[1] + tolerance;
    }

    private static int level(PotionEffect effect) {
        return effect == null ? 0 : effect.getAmplifier() + 1;
    }
}
