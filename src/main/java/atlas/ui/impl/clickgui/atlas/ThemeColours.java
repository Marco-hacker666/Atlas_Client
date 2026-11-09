package atlas.ui.impl.clickgui.atlas;

/** Colours as the Theme window shows and takes them: six hex digits (2026-11-09). No drawing, so it can be tested. */
final class ThemeColours {

    private ThemeColours() {
    }

    /** RRGGBB in capitals; any alpha byte is left out. */
    static String hex(int colour) {
        return String.format("%06X", colour & 0xFFFFFF);
    }

    /**
     * What was typed into a colour's field: RGB, RRGGBB or AARRGGBB, with or
     * without '#' and spaces around it. Returns red, green and blue only, or
     * null when it is not a colour.
     */
    static Integer parse(String typed) {
        if (typed == null) {
            return null;
        }
        String digits = typed.trim();
        if (digits.startsWith("#")) {
            digits = digits.substring(1).trim();
        }
        if (digits.length() == 3) {
            StringBuilder doubled = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                doubled.append(digits.charAt(i)).append(digits.charAt(i));
            }
            digits = doubled.toString();
        } else if (digits.length() == 8) {
            digits = digits.substring(2);
        }
        if (digits.length() != 6) {
            return null;
        }
        for (int i = 0; i < 6; i++) {
            if (Character.digit(digits.charAt(i), 16) < 0) {
                return null;
            }
        }
        return Integer.parseInt(digits, 16);
    }
}
