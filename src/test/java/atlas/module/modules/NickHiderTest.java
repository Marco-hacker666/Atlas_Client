package atlas.module.modules;

import org.junit.Test;

import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;

public class NickHiderTest {

    private static String hide(String name, String text) {
        return Pattern.compile(NickHider.spaced(name), Pattern.CASE_INSENSITIVE).matcher(text).replaceAll("You");
    }

    @Test
    public void theNameIsHiddenInAnyCase() {
        assertEquals("[MVP+] You joined", hide("Marco_Dev", "[MVP+] Marco_Dev joined"));
        assertEquals("you said: hi", hide("Marco_Dev", "marco_dev said: hi").replace("You", "you"));
    }

    @Test
    public void aNameColouredLetterByLetterIsHiddenToo() {
        assertEquals("§7Killed by §cYou!", hide("Abc", "§7Killed by §cA§6b§ec!"));
    }

    @Test
    public void otherTextIsLeftAlone() {
        assertEquals("Marco Dev", hide("Marco_Dev", "Marco Dev"));
        assertEquals("a.b", hide("a_b", "a.b"));
    }
}
