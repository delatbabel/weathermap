package org.weathermap.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Just enough Markdown to render the project's own documentation.
 *
 * <h2>Why not a library</h2>
 *
 * <p>A CommonMark implementation is a megabyte and handles a specification this
 * never needs: HTML blocks, reference links, setext headings, nested block
 * quotes, autolink extensions. What the Help window has to render is one known
 * set of files, written in one house style, and the subset they use is small
 * enough to read in one screen. Adding a second runtime dependency to display
 * text the project itself wrote would be a poor trade.</p>
 *
 * <h2>Why the output looks dated</h2>
 *
 * <p>The target is {@link javax.swing.JEditorPane}, whose HTML support is
 * essentially HTML 3.2 with a little CSS. No flexbox, no {@code <section>}, and
 * styling is safest inline. So this emits plain tags with a small stylesheet
 * the pane can actually apply, rather than markup that would look correct in a
 * browser and wrong in the window.</p>
 */
public final class Markdown {

    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\(([^)]+)\\)");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ITALIC = Pattern.compile("(?<![*\\w])\\*([^*]+)\\*(?!\\*)");
    private static final Pattern CODE = Pattern.compile("`([^`]+)`");

    private Markdown() { }

    /**
     * Renders one document.
     *
     * @param markdown the source
     * @param baseUrl  what relative links and images resolve against, or null
     */
    public static String toHtml(String markdown, String baseUrl) {
        final StringBuilder out = new StringBuilder(markdown.length() * 2);
        out.append("<html><head>");
        if (baseUrl != null) out.append("<base href=\"").append(baseUrl).append("\">");
        out.append("<style>").append(STYLE).append("</style></head><body>");

        final String[] lines = markdown.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            final String line = lines[i];

            if (line.startsWith("```")) {
                i = fencedCode(lines, i, out);
            }
            else if (line.startsWith("#")) {
                heading(line, out);
                i++;
            }
            else if (line.startsWith("|")) {
                i = table(lines, i, out);
            }
            else if (line.startsWith("> ")) {
                i = quote(lines, i, out);
            }
            else if (isBullet(line) || isNumbered(line)) {
                i = list(lines, i, out);
            }
            else if (line.startsWith("---") && line.trim().chars().allMatch(c -> c == '-')) {
                out.append("<hr>");
                i++;
            }
            else if (line.isBlank()) {
                i++;
            }
            else {
                i = paragraph(lines, i, out);
            }
        }
        return out.append("</body></html>").toString();
    }

    // ---- blocks ----------------------------------------------------------

    private static void heading(String line, StringBuilder out) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') level++;
        final int shown = Math.min(level, 4);
        out.append("<h").append(shown).append('>')
           .append(inline(line.substring(level).trim()))
           .append("</h").append(shown).append('>');
    }

    private static int fencedCode(String[] lines, int i, StringBuilder out) {
        out.append("<pre>");
        i++;
        while (i < lines.length && !lines[i].startsWith("```")) {
            out.append(escape(lines[i])).append('\n');
            i++;
        }
        out.append("</pre>");
        return i + 1;                     // step over the closing fence
    }

    /**
     * A pipe table.
     *
     * <p>The separator row is dropped rather than rendered: it is punctuation
     * telling a Markdown reader where the header ends, and drawing it would put
     * a row of dashes in the middle of the table.</p>
     */
    private static int table(String[] lines, int i, StringBuilder out) {
        out.append("<table cellpadding=4 cellspacing=0 border=1 width=\"100%\">");
        boolean header = true;
        while (i < lines.length && lines[i].startsWith("|")) {
            final String row = lines[i];
            if (!row.replace("|", "").replace("-", "").replace(":", "").isBlank()) {
                final String cell = header ? "th" : "td";
                out.append("<tr>");
                for (String value : splitRow(row)) {
                    out.append('<').append(cell).append(" align=left>")
                       .append(inline(value.trim()))
                       .append("</").append(cell).append('>');
                }
                out.append("</tr>");
                header = false;
            }
            i++;
        }
        return out.append("</table>").length() > 0 ? i : i;
    }

    private static List<String> splitRow(String row) {
        final String trimmed = row.replaceAll("^\\|", "").replaceAll("\\|\\s*$", "");
        final List<String> out = new ArrayList<>();
        for (String part : trimmed.split("\\|", -1)) out.add(part);
        return out;
    }

    private static int quote(String[] lines, int i, StringBuilder out) {
        out.append("<blockquote>");
        while (i < lines.length && lines[i].startsWith(">")) {
            out.append(inline(lines[i].replaceFirst("^>\\s?", ""))).append(' ');
            i++;
        }
        return out.append("</blockquote>").length() > 0 ? i : i;
    }

    private static int list(String[] lines, int i, StringBuilder out) {
        final boolean numbered = isNumbered(lines[i]);
        out.append(numbered ? "<ol>" : "<ul>");
        while (i < lines.length && (isBullet(lines[i]) || isNumbered(lines[i]))) {
            final String item = lines[i].replaceFirst("^\\s*([-*+]|\\d+\\.)\\s+", "");
            final StringBuilder text = new StringBuilder(item);
            i++;
            // A wrapped continuation line is indented and is part of the item.
            while (i < lines.length && lines[i].startsWith("  ") && !lines[i].isBlank()
                    && !isBullet(lines[i]) && !isNumbered(lines[i])) {
                text.append(' ').append(lines[i].trim());
                i++;
            }
            out.append("<li>").append(inline(text.toString())).append("</li>");
        }
        out.append(numbered ? "</ol>" : "</ul>");
        return i;
    }

    private static int paragraph(String[] lines, int i, StringBuilder out) {
        final StringBuilder text = new StringBuilder();
        while (i < lines.length && !lines[i].isBlank()
                && !lines[i].startsWith("#") && !lines[i].startsWith("|")
                && !lines[i].startsWith("```") && !lines[i].startsWith(">")
                && !isBullet(lines[i]) && !isNumbered(lines[i])) {
            if (text.length() > 0) text.append(' ');
            text.append(lines[i].trim());
            i++;
        }
        out.append("<p>").append(inline(text.toString())).append("</p>");
        return i;
    }

    private static boolean isBullet(String line) {
        return line.matches("^\\s*[-*+]\\s+.*");
    }

    private static boolean isNumbered(String line) {
        return line.matches("^\\s*\\d+\\.\\s+.*");
    }

    // ---- inline ----------------------------------------------------------

    /**
     * Inline spans, in an order that matters.
     *
     * <p>Escaping comes first so that source text containing {@code <} survives;
     * images before links, because an image is a link with a bang in front of
     * it and the link pattern would otherwise swallow it; and code last among
     * the emphasis marks, so that asterisks inside backticks are left alone.</p>
     */
    static String inline(String text) {
        String s = escape(text);
        s = IMAGE.matcher(s).replaceAll(m -> "<img src=\"" + Matcher.quoteReplacement(m.group(2))
                + "\" alt=\"" + Matcher.quoteReplacement(m.group(1)) + "\">");
        s = LINK.matcher(s).replaceAll(m -> "<a href=\"" + Matcher.quoteReplacement(m.group(2))
                + "\">" + Matcher.quoteReplacement(m.group(1)) + "</a>");
        s = BOLD.matcher(s).replaceAll(m -> "<b>" + Matcher.quoteReplacement(m.group(1)) + "</b>");
        s = ITALIC.matcher(s).replaceAll(m -> "<i>" + Matcher.quoteReplacement(m.group(1)) + "</i>");
        s = CODE.matcher(s).replaceAll(m -> "<code>" + Matcher.quoteReplacement(m.group(1)) + "</code>");
        return s;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Deliberately small, and in absolute sizes.
     *
     * <p>{@code JEditorPane} applies a fraction of CSS and ignores the rest
     * silently, so anything clever here is a guess about which half. Colours are
     * left to the look and feel except where the pane would otherwise pick its
     * own, which is how a dark theme ends up with black text on a dark
     * background.</p>
     */
    private static final String STYLE = """
            body  { font-family: sans-serif; font-size: 10pt; margin: 12px 16px; }
            h1    { font-size: 17pt; margin: 4px 0 10px 0; }
            h2    { font-size: 13pt; margin: 18px 0 6px 0; }
            h3    { font-size: 11pt; margin: 14px 0 4px 0; }
            h4    { font-size: 10pt; margin: 12px 0 4px 0; }
            p     { margin: 6px 0; }
            li    { margin: 3px 0; }
            pre   { font-family: monospace; font-size: 9pt; margin: 8px 0;
                    padding: 6px; }
            code  { font-family: monospace; font-size: 9pt; }
            table { margin: 8px 0; }
            th    { font-weight: bold; }
            blockquote { margin: 8px 16px; font-style: italic; }
            """;
}
