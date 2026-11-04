package atlas.script.raven.model;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Raven script API: an item stack (2026-11-02). Fields as Raven has them; {@link #itemStack} is the game's. */
public class ItemStack {
    public String type;
    public String name;
    public String displayName;
    public int stackSize;
    public int maxStackSize;
    public int durability;
    public int maxDurability;
    public boolean isBlock;
    public net.minecraft.item.ItemStack itemStack;
    public int meta;

    public ItemStack(net.minecraft.item.ItemStack itemStack, byte f1) {
        if (itemStack == null || itemStack.getItem() == null) {
            return;
        }
        this.itemStack = itemStack;
        Item item = itemStack.getItem();
        this.isBlock = item instanceof ItemBlock;
        this.type = this.isBlock ? ((ItemBlock) item).getBlock().getClass().getSimpleName() : item.getClass().getSimpleName();
        this.name = registryName(item);
        this.displayName = itemStack.getDisplayName();
        this.stackSize = itemStack.stackSize;
        this.maxStackSize = itemStack.getMaxStackSize();
        this.durability = itemStack.getMaxDamage() - itemStack.getItemDamage();
        this.maxDurability = itemStack.getMaxDamage();
        this.meta = itemStack.getMetadata();
    }

    /** A stack of one of the item with this name ("diamond_sword", or "minecraft:diamond_sword"). */
    public ItemStack(String name) {
        this(stackOf(name), (byte) 0);
    }

    private static net.minecraft.item.ItemStack stackOf(String name) {
        Item item = name == null ? null : Item.getByNameOrId(name.contains(":") ? name : "minecraft:" + name);
        return item == null ? null : new net.minecraft.item.ItemStack(item);
    }

    static String registryName(Item item) {
        ResourceLocation location = Item.itemRegistry.getNameForObject(item);
        return location == null ? item.getUnlocalizedName() : location.getResourcePath();
    }

    public List<String> getTooltip() {
        List<String> out = new ArrayList<String>();
        if (this.itemStack == null) {
            return out;
        }
        /* As Raven: the advanced tooltip (ids, durability), whatever F3+H says. */
        out.addAll(this.itemStack.getTooltip(net.minecraft.client.Minecraft.getMinecraft().thePlayer, true));
        return out;
    }

    /** {id, name, level} for each enchantment. */
    /** As Raven gives them: {name, level} with the English name in lower case and '_' for spaces
     *  ("fire_aspect"), or null for none. */
    public List<Object[]> getEnchantments() {
        if (this.itemStack == null) {
            return null;
        }
        Map<Integer, Integer> enchantments = net.minecraft.enchantment.EnchantmentHelper.getEnchantments(this.itemStack);
        if (enchantments.isEmpty()) {
            return null;
        }
        List<Object[]> out = new ArrayList<Object[]>();
        for (Map.Entry<Integer, Integer> entry : enchantments.entrySet()) {
            Enchantment enchantment = Enchantment.getEnchantmentById(entry.getKey());
            String name = enchantment == null ? String.valueOf(entry.getKey())
                    : net.minecraft.util.StatCollector.translateToFallback(enchantment.getName())
                    .toLowerCase(java.util.Locale.ROOT).replace(" ", "_");
            out.add(new Object[]{name, entry.getValue()});
        }
        return out;
    }

    public static ItemStack convert(net.minecraft.item.ItemStack itemStack) {
        return itemStack == null ? null : new ItemStack(itemStack, (byte) 0);
    }

    @Override
    public String toString() {
        return "ItemStack(" + this.type + "," + this.name + ")";
    }
}
