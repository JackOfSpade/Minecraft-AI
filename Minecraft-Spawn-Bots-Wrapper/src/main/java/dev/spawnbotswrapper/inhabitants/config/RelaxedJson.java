package dev.spawnbotswrapper.inhabitants.config;

/**
 * Makes hand-edited JSON parseable: strips {@code //} and {@code /* *}{@code /} comments and trailing
 * commas before {@code }} / {@code ]}, always leaving string literals untouched. Gson's lenient mode
 * accepts comments but not trailing commas inside objects, which is the most common hand-editing slip.
 */
public final class RelaxedJson {
    private RelaxedJson() {
    }

    public static String relax(String in) {
        StringBuilder out = new StringBuilder(in.length());
        int n = in.length();
        int i = 0;
        boolean inString = false;
        while (i < n) {
            char c = in.charAt(i);
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < n) {
                    out.append(in.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                i++;
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                i++;
            } else if (c == '/' && i + 1 < n && in.charAt(i + 1) == '/') {
                i += 2;
                while (i < n && in.charAt(i) != '\n' && in.charAt(i) != '\r') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && in.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(in.charAt(i) == '*' && in.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
                out.append(' ');
            } else if (c == '}' || c == ']') {
                int k = out.length() - 1;
                while (k >= 0 && Character.isWhitespace(out.charAt(k))) {
                    k--;
                }
                if (k >= 0 && out.charAt(k) == ',') {
                    out.deleteCharAt(k);
                }
                out.append(c);
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
