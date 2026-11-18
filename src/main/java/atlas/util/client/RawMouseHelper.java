package atlas.util.client;

import atlas.module.modules.MouseRawInput;
import net.minecraft.util.MouseHelper;

public class RawMouseHelper extends MouseHelper {
    @Override
    public void mouseXYChange() {
        super.mouseXYChange();
        if (!MouseRawInput.hasMouse()) {
            return;
        }
        this.deltaX = MouseRawInput.consumeDeltaX();
        this.deltaY = -MouseRawInput.consumeDeltaY();
    }

    @Override
    public void grabMouseCursor() {
        MouseRawInput.discard();
        super.grabMouseCursor();
    }
}
