package atlas.util;

import java.util.regex.Pattern;

public final class MatchChat {
    public enum Kind { NONE, START, WIN, LOSS }

    private static final Pattern START = Pattern.compile(
            "(?i)^(match start!|(the )?game starts in 1( second\\b|\\.\\.\\.).*)$");

    private MatchChat() {
    }

    public static Kind classify(String line, String me, String lastNameLine) {
        if (line == null || me == null || me.isEmpty()) {
            return Kind.NONE;
        }
        String text = line.trim();
        if (text.isEmpty() || text.contains(": ")) {
            return Kind.NONE;
        }
        if (START.matcher(text).matches()) {
            return Kind.START;
        }
        if (text.toUpperCase().startsWith("YOU WON")) {
            return Kind.WIN;
        }
        int winner = text.indexOf("WINNER!");
        if (winner < 0) {
            return Kind.NONE;
        }
        int loser = text.indexOf("LOSER!");
        if (loser >= 0) {
            if (lastNameLine == null) {
                return Kind.NONE;
            }
            String names = lastNameLine.trim();
            int at = names.indexOf(me);
            if (at < 0) {
                return Kind.NONE;
            }
            boolean meLeft = at + me.length() / 2.0 < names.length() / 2.0;
            boolean winnerLeft = winner < loser;
            return meLeft == winnerLeft ? Kind.WIN : Kind.LOSS;
        }

        if (!text.contains(me)) {
            return Kind.NONE;
        }
        String before = text.substring(0, winner).trim();
        return before.endsWith(me) && (before.length() == me.length()
                || !Character.isLetterOrDigit(before.charAt(before.length() - me.length() - 1))
                && before.charAt(before.length() - me.length() - 1) != '_')
                ? Kind.WIN : Kind.LOSS;
    }

    public static boolean namesMe(String line, String me) {
        return line != null && me != null && !me.isEmpty() && line.contains(me) && !line.contains(": ")
                && !line.contains("WINNER!") && !line.contains("LOSER!");
    }
}
