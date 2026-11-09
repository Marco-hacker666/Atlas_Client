package atlas.mixin;

import net.minecraft.client.settings.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(KeyBinding.class)
public interface IAccessorKeyBinding {
    @Accessor("pressed")
    void setPressed(boolean boolean1);

    @Accessor("pressed")
    boolean getPressed();

    /** Presses not yet taken by isPressed() (AutoBlock Full, 2026-10-21). */
    @Accessor("pressTime")
    int getPressTime();

    /** One more press for isPressed() to take (AutoBlock Crab, 2026-11-09: a click on this key alone). */
    @Accessor("pressTime")
    void setPressTime(int pressTime);
}
