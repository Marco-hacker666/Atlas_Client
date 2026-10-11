package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.IntProperty;
import myau.property.properties.KeyProperty;
import myau.property.properties.ModeProperty;
import myau.util.RandomUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import org.lwjgl.input.Keyboard;

/**
 * Crafts more mushroom soup for you while your own inventory screen is open and
 * the supply runs low (adapted from CrewX's AutoRecraft, 2026-10-11).
 *
 * The 2x2 grid inside the player's inventory is the crafting table, which is
 * why this only runs with that screen open. It right-clicks one item at a time
 * out of a stack into a grid slot, shift-clicks the crafted result straight
 * into the inventory, then shift-clicks anything the recipe left in the grid
 * back out again, and it does one click per randomised delay window because
 * servers count clicks.
 *
 * The recipe is the server's: mushroom soup is the vanilla one, while cocoa and
 * cactus are the custom recipes the Brazilian servers add. Nothing is clicked
 * while the cursor already holds an item, so a craft in progress by hand is
 * left alone.
 */
public class AutoRecraft extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    /** windowClick modes: pick an item up, or move a slot's whole stack away. */
    private static final int PICKUP = 0;
    private static final int QUICK_MOVE = 1;
    /** Container slots: the crafted result, then the four grid slots. */
    private static final int RESULT_SLOT = 0;
    private static final int FIRST_GRID_SLOT = 1;
    private static final int GRID_SLOTS = 4;
    /** Inventory slots worth searching: the backpack and the hotbar. */
    private static final int FIRST_INVENTORY_SLOT = 9;
    private static final int LAST_INVENTORY_SLOT = 45;

    public final ModeProperty recipe = new ModeProperty("recipe", 0, new String[]{"MUSHROOM", "COCOA", "CACTUS"});
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"AUTOMATIC", "MANUAL", "BOTH"});
    public final KeyProperty manualBind = new KeyProperty("manual-bind", Keyboard.KEY_NONE, () -> this.mode.getValue() != 0);
    public final IntProperty startWith = new IntProperty("start-with", 3, 0, 41);
    public final IntProperty startMinDelay = new IntProperty("start-min-delay", 40, 1, 400);
    public final IntProperty startMaxDelay = new IntProperty("start-max-delay", 60, 1, 400);
    public final IntProperty clickMinDelay = new IntProperty("click-min-delay", 40, 1, 400);
    public final IntProperty clickMaxDelay = new IntProperty("click-max-delay", 60, 1, 400);

    /** The slot each ingredient is taken from, and how many the recipe needs. */
    private final int[] ingredientSlot = new int[3];
    private int ingredientCount = 0;
    private int step = 0;
    private boolean crafting = false;
    private long nextClickMs = 0L;
    private long lastStartMs = 0L;

    public AutoRecraft() {
        super("AutoRecraft", false, false, "Crafts more soup while your inventory is open.");
    }

    @Override
    public void onDisabled() {
        this.reset();
    }

    /** A craft in the grid belongs to the world it started in. */
    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.reset();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }
        if (!(mc.currentScreen instanceof GuiInventory)) {
            /* The 2x2 grid only exists on that screen; closing it mid-craft
               drops whatever was placed, and the clicks must stop with it. */
            this.reset();
            return;
        }
        if (mc.thePlayer.inventory.getItemStack() != null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (!this.crafting) {
            if (!this.wantsToCraft() || now - this.lastStartMs < this.randomDelay(this.startMinDelay, this.startMaxDelay)) {
                return;
            }
            if (!this.collectIngredients()) {
                return;
            }
            this.lastStartMs = now;
            this.crafting = true;
            this.step = 0;
            this.nextClickMs = now;
            return;
        }

        if (now < this.nextClickMs) {
            return;
        }
        this.nextClickMs = now + this.randomDelay(this.clickMinDelay, this.clickMaxDelay);
        if (this.clickNextStep()) {
            this.reset();
        }
    }

    private long randomDelay(IntProperty min, IntProperty max) {
        return RandomUtil.nextInt(min.getValue(), max.getValue());
    }

    private boolean wantsToCraft() {
        int mode = this.mode.getValue();
        boolean automatic = mode != 1 && this.soupCount() <= this.startWith.getValue();
        boolean manual = mode != 0 && this.manualBind.isDown();
        return automatic || manual;
    }

    private int soupCount() {
        int count = 0;
        for (int slot = FIRST_INVENTORY_SLOT; slot < LAST_INVENTORY_SLOT; slot++) {
            ItemStack stack = this.slot(slot);
            if (stack != null && stack.getItem() == Items.mushroom_stew) {
                count += stack.stackSize;
            }
        }
        return count;
    }

    /**
     * Finds one slot per ingredient the recipe needs, bowl first. False means
     * the ingredients are not there, so nothing is clicked.
     */
    private boolean collectIngredients() {
        int bowl = this.findSlot(Items.bowl, null);
        if (bowl < 0) {
            return false;
        }
        int recipeIndex = this.recipe.getValue();
        int first;
        int second = -1;
        if (recipeIndex == 0) {
            // Mushroom soup is shapeless: one bowl and one of each mushroom.
            first = this.findSlot(Blocks.red_mushroom, null);
            second = this.findSlot(Blocks.brown_mushroom, null);
            if (first < 0 || second < 0) {
                return false;
            }
        } else {
            first = recipeIndex == 1
                    ? this.findSlot(Items.dye, EnumDyeColor.BROWN)
                    : this.findSlot(Blocks.cactus, null);
            if (first < 0) {
                return false;
            }
        }
        this.ingredientSlot[0] = bowl;
        this.ingredientSlot[1] = first;
        this.ingredientSlot[2] = second;
        this.ingredientCount = second < 0 ? 2 : 3;
        return true;
    }

    /**
     * The first inventory slot holding {@code item}, or the given dye colour of
     * it, or -1. Each ingredient is a different item type, so no slot can be
     * chosen twice.
     */
    private int findSlot(Object item, EnumDyeColor wanted) {
        for (int slot = FIRST_INVENTORY_SLOT; slot < LAST_INVENTORY_SLOT; slot++) {
            ItemStack stack = this.slot(slot);
            if (stack == null) {
                continue;
            }
            if (item == Items.dye) {
                if (stack.getItem() == Items.dye && stack.getMetadata() == wanted.getDyeDamage()) {
                    return slot;
                }
            } else if (item == Items.bowl) {
                if (stack.getItem() == Items.bowl) {
                    return slot;
                }
            } else if (item == Blocks.red_mushroom || item == Blocks.brown_mushroom || item == Blocks.cactus) {
                if (stack.getItem() instanceof ItemBlock && ((ItemBlock) stack.getItem()).getBlock() == item) {
                    return slot;
                }
            }
        }
        return -1;
    }

    /**
     * One click of the sequence, a delay window apart: two clicks per
     * ingredient (take one, place one), then the result, then the grid.
     * True when the sequence has run out of steps.
     */
    private boolean clickNextStep() {
        int gridClicks = this.ingredientCount * 2;
        if (this.step < gridClicks) {
            int ingredient = this.step / 2;
            if (this.step % 2 == 0) {
                this.click(this.ingredientSlot[ingredient], 1, PICKUP);
            } else {
                this.click(FIRST_GRID_SLOT + ingredient, 0, PICKUP);
            }
            this.step++;
            return false;
        }
        if (this.step == gridClicks) {
            this.click(RESULT_SLOT, 0, QUICK_MOVE);
            this.step++;
            return false;
        }
        int cleanup = this.step - gridClicks - 1;
        if (cleanup < GRID_SLOTS) {
            int slot = FIRST_GRID_SLOT + cleanup;
            if (this.slot(slot) != null) {
                this.click(slot, 0, QUICK_MOVE);
            }
            this.step++;
            return false;
        }
        return true;
    }

    private void click(int slot, int button, int clickMode) {
        if (slot < 0) {
            return;
        }
        mc.playerController.windowClick(
                mc.thePlayer.inventoryContainer.windowId, slot, button, clickMode, mc.thePlayer);
    }

    private ItemStack slot(int slot) {
        if (mc.thePlayer.inventoryContainer == null
                || slot < 0
                || slot >= mc.thePlayer.inventoryContainer.inventorySlots.size()) {
            return null;
        }
        return mc.thePlayer.inventoryContainer.getSlot(slot).getStack();
    }

    private void reset() {
        this.crafting = false;
        this.step = 0;
        this.ingredientCount = 0;
        this.ingredientSlot[0] = -1;
        this.ingredientSlot[1] = -1;
        this.ingredientSlot[2] = -1;
        this.nextClickMs = 0L;
        this.lastStartMs = 0L;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.crafting ? "Crafting" : this.recipe.getModeString()};
    }
}
