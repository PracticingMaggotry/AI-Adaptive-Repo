package com.adaptivelearning.adaptivelearningbackend;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders plain extracted text into a clean, paginated, readable PDF using
 * PDFBox (already a project dependency — see MaterialController's diagram
 * extraction). No new library is introduced.
 *
 * IMPORTANT — this exists purely as a READABILITY upgrade for admin Content
 * Review, not a security boundary change. It is always handed the SAME
 * already-extracted plain text that AdminController's existing
 * GET /materials/{id}/content endpoint already returns (re-run fresh by
 * DocumentTextExtractor on the stored file) — never the raw uploaded file
 * bytes. Re-typesetting that text into a fresh, server-generated PDF means
 * the admin never opens the original uploaded document at all: whatever the
 * uploader embedded in that file (scripts, malformed structure, tracking
 * objects, etc.) never reaches the admin's PDF viewer, because this class
 * only ever draws plain extracted strings onto blank pages it creates itself.
 *
 * Layout is intentionally simple — one serif body font, basic word-wrap,
 * automatic pagination — favoring legibility and predictable behavior over
 * visual polish. This is a moderation tool, not a publishing pipeline.
 */
public final class PdfRenderer {

    private static final float PAGE_WIDTH = PDRectangle.LETTER.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.LETTER.getHeight();
    private static final float MARGIN = 56f; // ~0.78in
    private static final float TITLE_FONT_SIZE = 16f;
    private static final float META_FONT_SIZE = 9f;
    private static final float BODY_FONT_SIZE = 11f;
    private static final float LINE_LEADING = 1.42f; // multiplier on font size

    private PdfRenderer() {}

    /**
     * @param title      shown as a heading at the top of page 1 (e.g. the
     *                   original filename) — plain text, no markup
     * @param metaLines  short lines of context shown under the title in a
     *                   smaller, muted style (e.g. "Topic: ... | Uploaded
     *                   by: ... | ..."); pass an empty list for none
     * @param bodyText   the extracted document text to lay out below the
     *                   header; long lines are word-wrapped and the content
     *                   flows across as many pages as needed
     * @return raw PDF bytes ready to write to an HTTP response body
     */
    public static byte[] render(String title, List<String> metaLines, String bodyText) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDFont titleFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDFont metaFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);
            PDFont bodyFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            float maxWidth = PAGE_WIDTH - (2 * MARGIN);

            // Pre-wrap the entire body into individual display lines (this also
            // handles existing newlines in the source text as paragraph breaks).
            List<String> wrappedLines = wrapParagraphs(bodyText, bodyFont, BODY_FONT_SIZE, maxWidth);

            PDPage page = newPage(doc);
            PDPageContentStream cs = new PDPageContentStream(doc, page);
            float cursorY = PAGE_HEIGHT - MARGIN;

            // ── Header: title ────────────────────────────────────────────
            cursorY = drawWrappedText(doc, cs, List.of(safe(title, "Untitled Document")),
                    titleFont, TITLE_FONT_SIZE, MARGIN, cursorY, maxWidth, TITLE_FONT_SIZE * LINE_LEADING);
            cursorY -= 6;

            // ── Header: meta line(s) ─────────────────────────────────────
            if (metaLines != null && !metaLines.isEmpty()) {
                List<String> wrappedMeta = new ArrayList<>();
                for (String m : metaLines) {
                    if (m == null || m.isBlank()) continue;
                    wrappedMeta.addAll(wrapLine(m, metaFont, META_FONT_SIZE, maxWidth));
                }
                cursorY = drawWrappedText(doc, cs, wrappedMeta, metaFont, META_FONT_SIZE,
                        MARGIN, cursorY, maxWidth, META_FONT_SIZE * LINE_LEADING);
            }

            // Divider rule under the header
            cursorY -= 10;
            cs.setLineWidth(0.75f);
            cs.moveTo(MARGIN, cursorY);
            cs.lineTo(PAGE_WIDTH - MARGIN, cursorY);
            cs.stroke();
            cursorY -= 18;

            cs.close();

            // ── Body: flowed across as many pages as needed ─────────────
            DrawState state = new DrawState(doc, page, cursorY);
            drawBodyAcrossPages(state, wrappedLines, bodyFont, BODY_FONT_SIZE, maxWidth);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    // ── Internals ────────────────────────────────────────────────────────

    private static String safe(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static PDPage newPage(PDDocument doc) {
        PDPage page = new PDPage(PDRectangle.LETTER);
        doc.addPage(page);
        return page;
    }

    /**
     * Draws a fixed (already known-short) set of lines starting at the given
     * Y and returns the new Y cursor. Used only for the header block, which
     * never needs to paginate.
     */
    private static float drawWrappedText(PDDocument doc, PDPageContentStream cs, List<String> lines,
                                         PDFont font, float fontSize, float x, float startY,
                                         float maxWidth, float leading) throws IOException {
        float y = startY;
        cs.beginText();
        cs.setFont(font, fontSize);
        cs.newLineAtOffset(x, y);
        boolean first = true;
        for (String line : lines) {
            if (!first) {
                cs.newLineAtOffset(0, -leading);
                y -= leading;
            }
            cs.showText(line);
            first = false;
        }
        cs.endText();
        return y;
    }

    /** Mutable cursor/page state threaded through body pagination. */
    private static final class DrawState {
        final PDDocument doc;
        PDPage page;
        float y;
        DrawState(PDDocument doc, PDPage page, float y) {
            this.doc = doc;
            this.page = page;
            this.y = y;
        }
    }

    private static void drawBodyAcrossPages(DrawState state, List<String> lines, PDFont font,
                                            float fontSize, float maxWidth) throws IOException {
        float leading = fontSize * LINE_LEADING;
        float bottomLimit = MARGIN;

        PDPageContentStream cs = new PDPageContentStream(state.doc, state.page, PDPageContentStream.AppendMode.APPEND, true);
        cs.beginText();
        cs.setFont(font, fontSize);
        cs.newLineAtOffset(MARGIN, state.y);
        boolean openText = true;

        for (String line : lines) {
            if (state.y - leading < bottomLimit) {
                // Page full — close out this stream, start a fresh page.
                cs.endText();
                cs.close();

                state.page = newPage(state.doc);
                state.y = PAGE_HEIGHT - MARGIN;

                cs = new PDPageContentStream(state.doc, state.page, PDPageContentStream.AppendMode.APPEND, true);
                cs.beginText();
                cs.setFont(font, fontSize);
                cs.newLineAtOffset(MARGIN, state.y);
                openText = true;
            }

            if (line.isEmpty()) {
                // Blank line = paragraph spacing only.
                cs.newLineAtOffset(0, -leading);
                state.y -= leading;
                continue;
            }

            cs.showText(line);
            cs.newLineAtOffset(0, -leading);
            state.y -= leading;
        }

        if (openText) {
            cs.endText();
        }
        cs.close();
    }

    /**
     * Splits raw text into paragraphs (on blank lines / single newlines),
     * then word-wraps each paragraph to fit maxWidth, inserting a single
     * empty string between paragraphs to render as a blank spacer line.
     */
    private static List<String> wrapParagraphs(String text, PDFont font, float fontSize, float maxWidth) throws IOException {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            result.add("(No readable text was extracted from this file.)");
            return result;
        }

        // Normalize Windows/Mac line endings, then split into paragraphs on
        // any run of 1+ newlines — the extracted text from DocumentTextExtractor
        // is already whitespace-collapsed, so this mostly produces one big
        // paragraph, which wrapLine() below handles fine either way.
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        String[] paragraphs = normalized.split("\n+");

        for (int i = 0; i < paragraphs.length; i++) {
            String para = paragraphs[i].trim();
            if (para.isEmpty()) continue;
            result.addAll(wrapLine(para, font, fontSize, maxWidth));
            if (i < paragraphs.length - 1) {
                result.add(""); // paragraph spacer
            }
        }

        if (result.isEmpty()) {
            result.add("(No readable text was extracted from this file.)");
        }
        return result;
    }

    /** Greedy word-wrap of a single paragraph into lines that fit maxWidth. */
    private static List<String> wrapLine(String paragraph, PDFont font, float fontSize, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        String[] words = paragraph.split("\\s+");
        StringBuilder current = new StringBuilder();

        for (String word : words) {
            String candidate = current.length() == 0 ? word : current + " " + word;
            float width = font.getStringWidth(sanitize(candidate)) / 1000f * fontSize;
            if (width > maxWidth && current.length() > 0) {
                lines.add(sanitize(current.toString()));
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (current.length() > 0) {
            lines.add(sanitize(current.toString()));
        }
        return lines;
    }

    /**
     * Standard14Fonts/WinAnsiEncoding cannot render arbitrary Unicode (smart
     * quotes, em-dashes, bullets, emoji, etc. from copy-pasted source
     * documents). Rather than letting PDFBox throw on an unsupported glyph
     * mid-render, replace common offenders with safe ASCII equivalents and
     * drop anything else outside the printable Latin-1 range.
     */
    private static String sanitize(String input) {
        if (input == null) return "";
        String replaced = input
                .replace('\u2018', '\'').replace('\u2019', '\'')
                .replace('\u201C', '"').replace('\u201D', '"')
                .replace('\u2013', '-').replace('\u2014', '-')
                .replace('\u2026', '.').replace('\u00A0', ' ');
        StringBuilder sb = new StringBuilder(replaced.length());
        for (int i = 0; i < replaced.length(); i++) {
            char c = replaced.charAt(i);
            if (c == '\n' || c == '\t' || (c >= 0x20 && c <= 0xFF)) {
                sb.append(c);
            } else {
                sb.append('?');
            }
        }
        return sb.toString();
    }
}