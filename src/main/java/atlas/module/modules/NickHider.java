package atlas.module.modules;

import atlas.Atlas;
import atlas.enums.ChatColors;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.TextProperty;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hides your real name on screen.
 *
 * 2026-11-17 (owner: "it must hide my real name everywhere; the TargetHUD preview did not, nor some tags"): it used
 * to work only through the game's own font, so everything Atlas draws with its own fonts (TargetHUD's smooth text,
 * the Raven name tags, the menus) still showed the name; it matched the exact case only; and with no player in a
 * world (the main menu, the account card) it hid nothing. Now:
 * - every font draws through {@link #apply}: the game's (MixinFontRenderer), CFontRenderer, UFontRenderer, the TTF
 *   FontRenderer and the menu's LiquidFont, both when drawing and when measuring, so layouts fit the shown name;
 * - the name is found in any case, and also when a server colours it letter by letter (a colour code between
 *   letters);
 * - the name is the account's even with no world loaded;
 * - "also-hide" lists other names of yours (alts), comma separated, hidden the same way.
 */
public class NickHider extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    public final TextProperty protectName = new TextProperty("name", "You");
    public final BooleanProperty scoreboard = new BooleanProperty("scoreboard", true);
    public final BooleanProperty level = new BooleanProperty("level", true);
    public final TextProperty alsoHide = new TextProperty("also-hide", "");

    private String patternKey;
    private Pattern pattern;

    public NickHider() {
        super("NickHider", false, true);
    }

    /** The text as it should be drawn: with the real name(s) replaced while NickHider is on. */
    public static String apply(String text) {
        if (text == null || text.isEmpty() || Atlas.moduleManager == null) {
            return text;
        }
        Module module = Atlas.moduleManager.modules.get(NickHider.class);
        return module != null && module.isEnabled() ? ((NickHider) module).replaceNick(text) : text;
    }

    public String replaceNick(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        if (this.scoreboard.getValue() && input.matches("§7\\d{2}/\\d{2}/\\d{2}(?:\\d{2})?  ?§8.*")) {
            input = input.replaceAll("§8", "§8§k").replaceAll("[^\\x00-\\x7F§]", "?");
        }
        Pattern p = this.pattern();
        if (p == null) {
            return input;
        }
        Matcher m = p.matcher(input);
        if (!m.find()) {
            return input;
        }
        return m.replaceAll(Matcher.quoteReplacement(ChatColors.formatColor(this.protectName.getValue())));
    }

    /** One pattern for every name to hide, made again only when a name changes. */
    private Pattern pattern() {
        String own = mc.thePlayer != null ? mc.thePlayer.getName()
                : mc.getSession() != null ? mc.getSession().getUsername() : null;
        String key = own + "|" + this.alsoHide.getValue();
        if (key.equals(this.patternKey)) {
            return this.pattern;
        }
        List<String> names = new ArrayList<String>();
        if (own != null && NAME.matcher(own).matches()) {
            names.add(own);
        }
        String extra = this.alsoHide.getValue();
        if (extra != null) {
            for (String n : extra.split("[,;\\s]+")) {
                if (NAME.matcher(n).matches() && !names.contains(n)) {
                    names.add(n);
                }
            }
        }
        /* Longest first, so a name that contains another is replaced whole. */
        names.sort((a, b) -> b.length() - a.length());
        StringBuilder regex = new StringBuilder();
        for (String n : names) {
            if (regex.length() > 0) {
                regex.append('|');
            }
            regex.append(spaced(n));
        }
        this.pattern = regex.length() == 0 ? null : Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
        this.patternKey = key;
        return this.pattern;
    }

    /** The name with any colour or format codes allowed between its letters. */
    static String spaced(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            if (i > 0) {
                sb.append("(?:§.)*");
            }
            sb.append(Pattern.quote(String.valueOf(name.charAt(i))));
        }
        return sb.toString();
    }
}
