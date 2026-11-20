package atlas.mommy;

import org.junit.Test;
import org.lwjgl.input.Keyboard;

import java.net.InetAddress;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PanicTest {
    @Test
    public void doubleTapFiresOnTheSecondPressOnly() {
        PanicTrigger t = new PanicTrigger();
        int m = PanicTrigger.DOUBLE_TAP;
        assertFalse("first press", t.feed(m, true, 0));
        assertFalse("held", t.feed(m, true, 50));
        assertFalse("released", t.feed(m, false, 100));
        assertTrue("second press, at once", t.feed(m, true, 200));
        assertFalse("still held", t.feed(m, true, 260));
    }

    @Test
    public void slowPressesAndHeldKeysNeverFire() {
        PanicTrigger t = new PanicTrigger();
        int m = PanicTrigger.DOUBLE_TAP;
        t.feed(m, true, 0);
        t.feed(m, false, 100);
        assertFalse("too slow", t.feed(m, true, 600));
        t.feed(m, false, 650);
        assertTrue("that one was a first press", t.feed(m, true, 800));
        PanicTrigger held = new PanicTrigger();
        for (long ms = 0; ms < 3000; ms += 16) {
            assertFalse("a held key or key repeat", held.feed(m, true, ms));
        }
    }

    @Test
    public void holdNeedsThreeTenths() {
        PanicTrigger t = new PanicTrigger();
        int m = PanicTrigger.HOLD;
        assertFalse(t.feed(m, true, 0));
        assertFalse(t.feed(m, true, 200));
        assertTrue(t.feed(m, true, 310));
        assertFalse("once per hold", t.feed(m, true, 600));
        t.feed(m, false, 700);
        assertFalse("a tap", t.feed(m, true, 800));
        assertFalse(t.feed(m, false, 850));
    }

    @Test
    public void singleFiresOnThePress() {
        PanicTrigger t = new PanicTrigger();
        assertTrue(t.feed(PanicTrigger.SINGLE, true, 0));
        assertFalse(t.feed(PanicTrigger.SINGLE, true, 20));
    }

    @Test
    public void onlyKeysThatTypeNothingWorkInMenus() {
        assertTrue(PanicKeys.safeInMenus(Keyboard.KEY_F9));
        assertTrue(PanicKeys.safeInMenus(Keyboard.KEY_INSERT));
        assertFalse(PanicKeys.safeInMenus(Keyboard.KEY_M));
        assertFalse(PanicKeys.safeInMenus(Keyboard.KEY_SPACE));
        assertFalse(PanicKeys.safeInMenus(Keyboard.KEY_1));
    }

    @Test
    public void gameKeysMapToWindowsKeys() {
        assertEquals(0x70, PanicKeys.toVk(Keyboard.KEY_F1));
        assertEquals(0x79, PanicKeys.toVk(Keyboard.KEY_F10));
        assertEquals(0x7B, PanicKeys.toVk(Keyboard.KEY_F12));
        assertEquals('M', PanicKeys.toVk(Keyboard.KEY_M));
        assertEquals('1', PanicKeys.toVk(Keyboard.KEY_1));
        assertEquals('0', PanicKeys.toVk(Keyboard.KEY_0));
        assertEquals(0x2D, PanicKeys.toVk(Keyboard.KEY_INSERT));
        assertEquals(0x60, PanicKeys.toVk(Keyboard.KEY_NUMPAD0));
        assertEquals(0, PanicKeys.toVk(Keyboard.KEY_NONE));
    }

    @Test
    public void windowsMuteRemembersTheStateBefore() {
        String on = PanicCommands.winMuteScript(true);
        assertTrue(on.indexOf("'was:' + [AtlasAudio]::Muted()") < on.indexOf("[AtlasAudio]::Mute($true)"));
        assertTrue(PanicCommands.winMuteScript(false).contains("[AtlasAudio]::Mute($false)"));
        String[] ps = PanicCommands.powershell("x");
        assertEquals("-EncodedCommand", ps[ps.length - 2]);
        assertEquals("eAA=", ps[ps.length - 1]);
    }

    @Test
    public void macAndLinuxCommands() {
        assertArrayEquals(new String[]{"osascript", "-e", "set volume output muted true"},
                PanicCommands.mute(PanicCommands.MAC, true));
        assertTrue(PanicCommands.mute(PanicCommands.LINUX, false)[2].startsWith("pactl set-sink-mute @DEFAULT_SINK@ 0"));
        assertArrayEquals(new String[]{"pmset", "sleepnow"}, PanicCommands.sleep(PanicCommands.MAC));
        assertArrayEquals(new String[]{"systemctl", "suspend"}, PanicCommands.sleep(PanicCommands.LINUX));
        assertArrayEquals(new String[]{"shutdown.exe", "/s", "/t", "0", "/f"},
                PanicCommands.shutdown(PanicCommands.WINDOWS, true));
        assertTrue(PanicCommands.gameWindow(PanicCommands.MAC, 42, false)[2].contains("unix id is 42) to false"));
        assertTrue(PanicCommands.gameWindow(PanicCommands.LINUX, 42, true)[2].contains("windowactivate"));
        assertArrayEquals(new String[]{"pkill", "-15", "-x", "discord"},
                PanicCommands.closeApp(PanicCommands.LINUX, "discord", false));
        assertEquals("quit app \"Dis\\\"cord\"", PanicCommands.closeApp(PanicCommands.MAC, "Dis\"cord", false)[2]);
    }

    @Test
    public void mutedOutputIsRead() {
        assertTrue(PanicCommands.saysMuted("true\n"));
        assertTrue(PanicCommands.saysMuted("Mute: yes"));
        assertTrue(PanicCommands.saysMuted("1"));
        assertFalse(PanicCommands.saysMuted("false"));
        assertFalse(PanicCommands.saysMuted("Mute: no"));
        assertFalse(PanicCommands.saysMuted("0"));
        assertFalse(PanicCommands.saysMuted(""));
    }

    @Test
    public void customRunsTheFileInItsShell() {
        assertEquals(Arrays.asList("cmd.exe", "/c", "C:\\a b\\x.cmd"),
                Arrays.asList(PanicCommands.custom(PanicCommands.SHELL_CMD, "C:\\a b\\x.cmd")));
        assertEquals("C:\\x.ps1", PanicCommands.custom(PanicCommands.SHELL_POWERSHELL, "C:\\x.ps1")[7]);
        assertArrayEquals(new String[]{"sh", "/x.sh"}, PanicCommands.custom(PanicCommands.SHELL_SH, "/x.sh"));
        assertTrue(PanicCommands.template(PanicCommands.SHELL_CMD).startsWith("REM "));
    }

    @Test
    public void commentsInTheCustomScriptAreNotCommands() {
        assertTrue(PanicSetup.commentLine("REM taskkill /im x.exe", PanicCommands.SHELL_CMD));
        assertTrue(PanicSetup.commentLine(":: note", PanicCommands.SHELL_CMD));
        assertTrue(PanicSetup.commentLine("   ", PanicCommands.SHELL_CMD));
        assertFalse(PanicSetup.commentLine("taskkill /im chrome.exe /f", PanicCommands.SHELL_CMD));
        assertFalse(PanicSetup.commentLine("REMOVE-thing", PanicCommands.SHELL_CMD));
        assertTrue(PanicSetup.commentLine("# Stop-Process", PanicCommands.SHELL_POWERSHELL));
        assertFalse(PanicSetup.commentLine("Stop-Process -Name chrome", PanicCommands.SHELL_POWERSHELL));
        assertTrue(PanicSetup.commentLine("# pkill", PanicCommands.SHELL_SH));
    }

    @Test
    public void theCommandSettingNeverTravelsInAConfig() {
        CommandProperty p = new CommandProperty("custom-command", () -> PanicCommands.SHELL_CMD, null);
        com.google.gson.JsonObject saved = new com.google.gson.JsonObject();
        p.write(saved);
        assertFalse("not written into a config", saved.has("custom-command"));
        com.google.gson.JsonObject other = new com.google.gson.JsonObject();
        other.addProperty("custom-command", "format c:");
        assertTrue(p.read(other));
        assertFalse("a config's command is ignored", "format c:".equals(p.getValue()));
    }

    @Test
    public void onlyWebAddressesForPages() {
        assertEquals("https://classroom.google.com", PanicSetup.normalizeUrl(" classroom.google.com "));
        assertTrue(PanicSetup.validUrl("https://classroom.google.com"));
        assertFalse(PanicSetup.validUrl("file:///C:/Windows/System32/cmd.exe"));
        assertFalse(PanicSetup.validUrl("javascript:alert(1)"));
        assertFalse(PanicSetup.validUrl("https://a b.com"));
    }

    @Test
    public void theRemoteAnswersOnlyTheHomeNetworkWithTheKey() throws Exception {
        assertTrue(PanicRemote.local(InetAddress.getByName("127.0.0.1")));
        assertTrue(PanicRemote.local(InetAddress.getByName("192.168.1.20")));
        assertTrue(PanicRemote.local(InetAddress.getByName("10.0.0.5")));
        assertTrue(PanicRemote.local(InetAddress.getByName("fd12::1")));
        assertFalse(PanicRemote.local(InetAddress.getByName("8.8.8.8")));
        assertFalse(PanicRemote.local(InetAddress.getByName("2001:4860::8888")));
        String key = PanicSetup.newKey();
        assertEquals(32, key.length());
        assertTrue(PanicRemote.sameKey(key, key));
        assertFalse(PanicRemote.sameKey(key.substring(1), key));
        assertFalse(PanicRemote.sameKey("", key));
        assertFalse(PanicRemote.sameKey(null, key));
        assertFalse("no key set never matches", PanicRemote.sameKey("", ""));
    }
}
