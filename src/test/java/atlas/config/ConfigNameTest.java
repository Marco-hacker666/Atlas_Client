package atlas.config;

import org.junit.Test;

import static org.junit.Assert.*;

/** A profile name stays one file in ./config/Atlas/ (2026-10-30, audit: ".config save ../../x"). */
public class ConfigNameTest {

    @Test
    public void ordinaryNamesAreKept() {
        assertTrue(Config.validName("default"));
        assertTrue(Config.validName("yuri-legit"));
        assertTrue(Config.validName("default (1)"));
        assertTrue(Config.validName("gsd   fg"));
        assertTrue(Config.validName("v2.9_test"));
        assertTrue(Config.validName("練習"));
    }

    @Test
    public void namesThatLeaveTheFolderAreRefused() {
        assertFalse(Config.validName("../../launcher_profiles"));
        assertFalse(Config.validName("..\\..\\options"));
        assertFalse(Config.validName("sub/name"));
        assertFalse(Config.validName("C:\\Windows\\x"));
        assertFalse(Config.validName(".."));
        assertFalse(Config.validName(".hidden"));
    }

    @Test
    public void namesWindowsCannotHoldAreRefused() {
        assertFalse(Config.validName(""));
        assertFalse(Config.validName(null));
        assertFalse(Config.validName("con"));
        assertFalse(Config.validName("NUL"));
        assertFalse(Config.validName("com1"));
        assertFalse(Config.validName("lpt9.x"));
        assertFalse(Config.validName("a?b"));
        assertFalse(Config.validName("trailing."));
        assertFalse(Config.validName("trailing "));
        assertFalse(Config.validName("tab\there"));
    }
}
