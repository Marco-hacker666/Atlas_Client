package atlas.script.raven.model;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

        out.addAll(this.itemStack.getTooltip(net.minecraft.client.Minecraft.getMinecraft().thePlayer, true));
        return out;
    }

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
