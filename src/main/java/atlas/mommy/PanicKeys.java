package atlas.mommy;

import org.lwjgl.input.Keyboard;

public final class PanicKeys {
    public static final int MOD_NONE = 0;
    public static final int MOD_CTRL = 1;
    public static final int MOD_SHIFT = 2;
    public static final int MOD_ALT = 3;
    public static final String[] MODIFIERS = {"None", "Ctrl", "Shift", "Alt"};

    private PanicKeys() {
    }

    public static boolean safeInMenus(int key) {
        if (key >= Keyboard.KEY_F1 && key <= Keyboard.KEY_F10 || key == Keyboard.KEY_F11 || key == Keyboard.KEY_F12
                || key >= Keyboard.KEY_F13 && key <= Keyboard.KEY_F15) {
            return true;
        }
        switch (key) {
            case Keyboard.KEY_INSERT:
            case Keyboard.KEY_PAUSE:
            case Keyboard.KEY_SCROLL:
            case Keyboard.KEY_APPS:
            case Keyboard.KEY_PRIOR:
            case Keyboard.KEY_NEXT:
                return true;
            default:
                return false;
        }
    }

    public static int toVk(int key) {
        if (key >= Keyboard.KEY_F1 && key <= Keyboard.KEY_F10) {
            return 0x70 + (key - Keyboard.KEY_F1);
        }
        switch (key) {
            case Keyboard.KEY_F11: return 0x7A;
            case Keyboard.KEY_F12: return 0x7B;
            case Keyboard.KEY_F13: return 0x7C;
            case Keyboard.KEY_F14: return 0x7D;
            case Keyboard.KEY_F15: return 0x7E;
            case Keyboard.KEY_INSERT: return 0x2D;
            case Keyboard.KEY_DELETE: return 0x2E;
            case Keyboard.KEY_HOME: return 0x24;
            case Keyboard.KEY_END: return 0x23;
            case Keyboard.KEY_PRIOR: return 0x21;
            case Keyboard.KEY_NEXT: return 0x22;
            case Keyboard.KEY_PAUSE: return 0x13;
            case Keyboard.KEY_SCROLL: return 0x91;
            case Keyboard.KEY_UP: return 0x26;
            case Keyboard.KEY_DOWN: return 0x28;
            case Keyboard.KEY_LEFT: return 0x25;
            case Keyboard.KEY_RIGHT: return 0x27;
            case Keyboard.KEY_SPACE: return 0x20;
            case Keyboard.KEY_RETURN: return 0x0D;
            case Keyboard.KEY_TAB: return 0x09;
            case Keyboard.KEY_GRAVE: return 0xC0;
            case Keyboard.KEY_NUMPAD0: return 0x60;
            case Keyboard.KEY_NUMPAD1: return 0x61;
            case Keyboard.KEY_NUMPAD2: return 0x62;
            case Keyboard.KEY_NUMPAD3: return 0x63;
            case Keyboard.KEY_NUMPAD4: return 0x64;
            case Keyboard.KEY_NUMPAD5: return 0x65;
            case Keyboard.KEY_NUMPAD6: return 0x66;
            case Keyboard.KEY_NUMPAD7: return 0x67;
            case Keyboard.KEY_NUMPAD8: return 0x68;
            case Keyboard.KEY_NUMPAD9: return 0x69;
            case Keyboard.KEY_0: return 0x30;
            default:
                break;
        }
        if (key >= Keyboard.KEY_1 && key <= Keyboard.KEY_9) {
            return 0x31 + (key - Keyboard.KEY_1);
        }
        String name = Keyboard.getKeyName(key);
        if (name != null && name.length() == 1 && name.charAt(0) >= 'A' && name.charAt(0) <= 'Z') {
            return name.charAt(0);
        }
        return 0;
    }

    public static int toWinMods(int modifier) {
        switch (modifier) {
            case MOD_CTRL: return WinApi.MOD_CONTROL;
            case MOD_SHIFT: return WinApi.MOD_SHIFT;
            case MOD_ALT: return WinApi.MOD_ALT;
            default: return 0;
        }
    }

    public static boolean modifierDown(int modifier) {
        switch (modifier) {
            case MOD_CTRL:
                return Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
            case MOD_SHIFT:
                return Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
            case MOD_ALT:
                return Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU);
            default:
                return true;
        }
    }
}
