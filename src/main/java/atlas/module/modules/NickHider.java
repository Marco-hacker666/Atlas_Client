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
