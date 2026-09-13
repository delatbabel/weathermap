package org.weathermap.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownTest {

    private static String html(String markdown) {
        return Markdown.toHtml(markdown, null);
    }

    @Test
    void headingsBecomeHeadings() {
        assertTrue(html("# Title").contains("<h1>Title</h1>"));
        assertTrue(html("### Deep").contains("<h3>Deep</h3>"));
        // JEditorPane has no h5/h6 worth using; deeper levels clamp.
        assertTrue(html("###### Deepest").contains("<h4>Deepest</h4>"));
    }

    @Test
    void aPipeTableBecomesATableWithoutItsSeparator() {
        final String out = html("| A | B |\n|---|---|\n| 1 | 2 |\n");

        assertTrue(out.contains("<table"), out);
        assertTrue(out.contains("<th align=left>A</th>"), out);
        assertTrue(out.contains("<td align=left>1</td>"), out);
        // The dashes are punctuation for a Markdown reader, not a row.
        assertFalse(out.contains("---"), out);
    }

    @Test
    void listsWrapAndContinue() {
        final String out = html("- first item\n  continued here\n- second\n");

        assertTrue(out.contains("<ul>"), out);
        assertTrue(out.contains("<li>first item continued here</li>"), out);
        assertTrue(out.contains("<li>second</li>"), out);
    }

    @Test
    void numberedListsAreOrdered() {
        assertTrue(html("1. one\n2. two\n").contains("<ol>"));
    }

    @Test
    void fencedCodeIsPreformattedAndNotInterpreted() {
        final String out = html("```bash\n./run.sh --cli\n# a *comment*\n```\n");

        assertTrue(out.contains("<pre>"), out);
        assertTrue(out.contains("./run.sh --cli"), out);
        // Inside a fence, markup characters are text.
        assertFalse(out.contains("<i>comment</i>"), out);
    }

    /** An image is a link with a bang, so it has to be matched first. */
    @Test
    void imagesAreNotTurnedIntoLinks() {
        final String out = html("![A chart](png/pipeline.png)");

        assertTrue(out.contains("<img src=\"png/pipeline.png\" alt=\"A chart\">"), out);
        assertFalse(out.contains("<a href"), out);
    }

    @Test
    void linksAreFollowable() {
        assertTrue(html("see [themes](themes.md) for more")
                .contains("<a href=\"themes.md\">themes</a>"));
    }

    @Test
    void emphasisAndCodeSurvive() {
        final String out = html("**bold** and *italic* and `code()`");

        assertTrue(out.contains("<b>bold</b>"), out);
        assertTrue(out.contains("<i>italic</i>"), out);
        assertTrue(out.contains("<code>code()</code>"), out);
    }

    /** Source text that looks like markup must not become markup. */
    @Test
    void angleBracketsInTheSourceAreEscaped() {
        final String out = html("a <script>alert(1)</script> in the text");

        assertFalse(out.contains("<script>"), out);
        assertTrue(out.contains("&lt;script&gt;"), out);
    }

    @Test
    void aBaseUrlIsUsedWhenGiven() {
        assertTrue(Markdown.toHtml("# x", "jar:file:/app.jar!/help/x.md")
                .contains("<base href=\"jar:file:/app.jar!/help/x.md\">"));
    }

    /** Every bundled page must render without throwing. */
    @Test
    void everyBundledHelpPageRenders() throws Exception {
        for (HelpWindow.Page page : HelpWindow.USER_GUIDE) {
            assertBundled(page.resource());
        }
        for (HelpWindow.Page page : HelpWindow.DEVELOPER_GUIDE) {
            assertBundled(page.resource());
        }
    }

    private static void assertBundled(String resource) throws Exception {
        try (var in = MarkdownTest.class.getResourceAsStream(resource)) {
            assertTrue(in != null, resource + " is not bundled - check the pom's <resources>");
            final String out = Markdown.toHtml(
                    new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8),
                    null);
            assertTrue(out.startsWith("<html>") && out.endsWith("</html>"), resource);
            assertTrue(out.length() > 200, resource + " rendered suspiciously short");
        }
    }

    /**
     * Every page in docs/ is reachable from the Help menu.
     *
     * <p>The README says the documentation is the same in the repository and in
     * the application, and a page that exists in one and not the other quietly
     * makes that false. {@code packaging.md} had been bundled into the jar and
     * shown nowhere since the Help window was written.</p>
     */
    @Test
    void everyDocumentIsReachableFromTheHelpMenu() throws Exception {
        final java.nio.file.Path docs = java.nio.file.Path.of("docs");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.isDirectory(docs), "runs from the source tree only");

        final java.util.Set<String> shown = new java.util.HashSet<>();
        for (HelpWindow.Page page : HelpWindow.USER_GUIDE) shown.add(page.resource());
        for (HelpWindow.Page page : HelpWindow.DEVELOPER_GUIDE) shown.add(page.resource());

        final java.util.List<String> missing = new java.util.ArrayList<>();
        try (var files = java.nio.file.Files.list(docs)) {
            for (java.nio.file.Path file : files.toList()) {
                final String name = file.getFileName().toString();
                if (!name.endsWith(".md")) continue;
                if (!shown.contains("/help/" + name)) missing.add(name);
            }
        }
        assertTrue(missing.isEmpty(),
                "documents with no Help tab: " + missing
                + " - add a Page to HelpWindow, or the README's claim that the same "
                + "pages are in the application is not true");
    }
}
