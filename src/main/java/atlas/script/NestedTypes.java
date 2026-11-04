package atlas.script;

import java.util.ArrayList;
import java.util.List;

/**
 * Raven bS 17's rule for classes a script declares (2026-11-03): a script is the body of a class, so a class it
 * declares is an inner class, and Java 8 lets no inner class hold an enum or an interface. bS 17 makes such a
 * class static (and every class around one), and so does this -- by inserting "static " before its "class",
 * which keeps the lines where they are. Written for Atlas from that description.
 *
 * Strings, characters and comments are skipped; "X.class" is no declaration; a class inside a method is left
 * alone (a local class cannot be static).
 */
final class NestedTypes {
    private NestedTypes() {
    }

    private static final class Token {
        final String text;
        final int position;
        final int depth;

        Token(String text, int position, int depth) {
            this.text = text;
            this.position = position;
            this.depth = depth;
        }
    }

    private static final class Type {
        boolean isClass;
        boolean declaredStatic;
        int keyword;
        int position;
        int depth;
        int bodyOpen;
        int bodyClose;
        boolean member;
        Boolean becomesStatic;
        final List<Type> children = new ArrayList<Type>();
    }

    static String makeStatic(String source) {
        if (source == null || source.indexOf("class") < 0
                || (source.indexOf("enum") < 0 && source.indexOf("interface") < 0)) {
            return source;
        }
        List<Token> tokens = tokenize(source);
        List<Type> types = declarations(tokens);
        if (types.isEmpty()) {
            return source;
        }
        nest(types);
        List<Integer> inserts = new ArrayList<Integer>();
        for (Type type : types) {
            if (type.isClass && type.member && !type.declaredStatic && becomesStatic(type)) {
                inserts.add(type.position);
            }
        }
        if (inserts.isEmpty()) {
            return source;
        }
        inserts.sort((a, b) -> b - a);
        StringBuilder out = new StringBuilder(source);
        for (int position : inserts) {
            out.insert(position, "static ");
        }
        return out.toString();
    }

    private static boolean becomesStatic(Type type) {
        if (!type.isClass) {
            return true;
        }
        if (type.becomesStatic != null) {
            return type.becomesStatic;
        }
        type.becomesStatic = type.declaredStatic;
        if (!type.becomesStatic) {
            for (Type child : type.children) {
                if (child.member && becomesStatic(child)) {
                    type.becomesStatic = true;
                    break;
                }
            }
        }
        return type.becomesStatic;
    }

    /** Which type each one sits in, directly; a member is one declared in the script's body or a member's. */
    private static void nest(List<Type> types) {
        for (Type type : types) {
            Type around = null;
            for (Type other : types) {
                if (other != type && other.bodyOpen < type.keyword && other.bodyClose > type.keyword
                        && (around == null || other.bodyOpen > around.bodyOpen)) {
                    around = other;
                }
            }
            if (around == null) {
                type.member = type.depth == 0;
            } else {
                boolean direct = type.depth == around.depth + 1;
                type.member = direct && around.member;
                if (direct) {
                    around.children.add(type);
                }
            }
        }
    }

    private static List<Type> declarations(List<Token> tokens) {
        List<Type> out = new ArrayList<Type>();
        for (int i = 0; i < tokens.size(); i++) {
            String text = tokens.get(i).text;
            boolean isClass = "class".equals(text);
            if (!isClass && !"enum".equals(text) && !"interface".equals(text)) {
                continue;
            }
            if (i > 0 && ".".equals(tokens.get(i - 1).text)) {
                continue;
            }
            int open = body(tokens, i);
            int close = open < 0 ? -1 : matching(tokens, open);
            if (close < 0) {
                continue;
            }
            Type type = new Type();
            type.isClass = isClass;
            type.keyword = i;
            type.position = tokens.get(i).position;
            type.depth = tokens.get(i).depth;
            type.bodyOpen = open;
            type.bodyClose = close;
            type.declaredStatic = !isClass || hasStatic(tokens, i);
            out.add(type);
        }
        return out;
    }

    /** The "{" that opens the type's body, at its own depth; -1 when a ";" comes first. */
    private static int body(List<Token> tokens, int keyword) {
        int depth = tokens.get(keyword).depth;
        int parentheses = 0;
        for (int i = keyword + 1; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.depth < depth) {
                return -1;
            }
            if (token.depth != depth) {
                continue;
            }
            if ("(".equals(token.text) || "[".equals(token.text)) {
                parentheses++;
            } else if ((")".equals(token.text) || "]".equals(token.text)) && parentheses > 0) {
                parentheses--;
            } else if (parentheses == 0 && "{".equals(token.text)) {
                return i;
            } else if (parentheses == 0 && ";".equals(token.text)) {
                return -1;
            }
        }
        return -1;
    }

    private static int matching(List<Token> tokens, int open) {
        int depth = tokens.get(open).depth;
        for (int i = open + 1; i < tokens.size(); i++) {
            if ("}".equals(tokens.get(i).text) && tokens.get(i).depth == depth) {
                return i;
            }
        }
        return -1;
    }

    private static boolean hasStatic(List<Token> tokens, int keyword) {
        int depth = tokens.get(keyword).depth;
        for (int i = keyword - 1; i >= 0; i--) {
            Token token = tokens.get(i);
            if (token.depth < depth) {
                return false;
            }
            if (token.depth > depth) {
                continue;
            }
            if (";".equals(token.text) || "{".equals(token.text) || "}".equals(token.text)) {
                return false;
            }
            if ("static".equals(token.text)) {
                return true;
            }
        }
        return false;
    }

    /** Words and the symbols that matter, with the brace depth each is at ("{" and "}" at the outer one). */
    private static List<Token> tokenize(String source) {
        List<Token> out = new ArrayList<Token>();
        int depth = 0;
        int length = source.length();
        int i = 0;
        while (i < length) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < length && source.charAt(i + 1) == '/') {
                while (i < length && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < length && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < length && source.charAt(i) != c) {
                    if (source.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                i++;
            } else if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < length && Character.isJavaIdentifierPart(source.charAt(i))) {
                    i++;
                }
                out.add(new Token(source.substring(start, i), start, depth));
            } else if (c == '{') {
                out.add(new Token("{", i, depth));
                depth++;
                i++;
            } else if (c == '}') {
                depth = Math.max(0, depth - 1);
                out.add(new Token("}", i, depth));
                i++;
            } else if (c == '(' || c == ')' || c == '[' || c == ']' || c == ';' || c == '.') {
                out.add(new Token(String.valueOf(c), i, depth));
                i++;
            } else {
                i++;
            }
        }
        return out;
    }
}
