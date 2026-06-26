package com.adaptivelearning.adaptivelearningbackend;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
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
 * Review, not a security boundary change. It is always handed already-
 * extracted text/blocks — never the raw uploaded file bytes. Re-typesetting
 * that text into a fresh, server-generated PDF means the admin never opens
 * the original uploaded document at all: whatever the uploader embedded in
 * that file (scripts, malformed structure, tracking objects, etc.) never
 * reaches the admin's PDF viewer, because this class only ever draws plain
 * extracted strings onto blank pages it creates itself.
 *
 * Two entry points:
 *   - {@link #render}          — original flat-text renderer. Simple
 *                                 word-wrap, no structure. Kept for any
 *                                 caller that only has a plain string.
 *   - {@link #renderFormatted} — preferred for material content review.
 *                                 Consumes DocumentTextExtractor.TextBlock
 *                                 list so headings render bold/larger,
 *                                 bullet/numbered list items get an indent
 *                                 (and a synthetic "•" marker when the
 *                                 source format stored the marker as
 *                                 formatting rather than literal text — see
 *                                 DocumentTextExtractor.extractFormattedText
 *                                 javadoc), and blank lines become real
 *                                 paragraph spacing instead of one giant
 *                                 word-wrapped run-on string.
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
    private static final float HEADING_FONT_SIZE = 13f;
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
            PDFont titleFont = PDType1Font.HELVETICA_BOLD;
            PDFont metaFont = PDType1Font.HELVETICA_OBLIQUE;
            PDFont bodyFont = PDType1Font.HELVETICA;

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

    /**
     * Same header/footer/pagination machinery as {@link #render}, but the
     * body is laid out from classified {@link DocumentTextExtractor.TextBlock}s
     * instead of a flat string — headings render bold and larger, bullet/
     * numbered items get a hanging indent (and a synthetic bullet glyph if
     * the source format never had a literal one — see
     * DocumentTextExtractor.extractDocxFormatted javadoc), and BLANK blocks
     * become real paragraph spacing.
     *
     * @param title      same as {@link #render}
     * @param metaLines  same as {@link #render}
     * @param blocks     classified blocks from
     *                   {@link DocumentTextExtractor#extractFormattedText}
     * @return raw PDF bytes ready to write to an HTTP response body
     */
    public static byte[] renderFormatted(String title, List<String> metaLines,
                                         List<DocumentTextExtractor.TextBlock> blocks) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDFont titleFont = PDType1Font.HELVETICA_BOLD;
            PDFont metaFont = PDType1Font.HELVETICA_OBLIQUE;

            float maxWidth = PAGE_WIDTH - (2 * MARGIN);

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
            List<RenderLine> renderLines = buildRenderLines(blocks, maxWidth);
            DrawState state = new DrawState(doc, page, cursorY);
            drawRenderLinesAcrossPages(state, renderLines);

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

    // ── Formatted (TextBlock-aware) body layout ─────────────────────────

    /**
     * One line ready to draw, with its own font/size/leading already
     * resolved. Converting the whole TextBlock list into a flat list of
     * these up front lets the single pagination loop below
     * ({@link #drawRenderLinesAcrossPages}) stay completely format-agnostic
     * — it never branches on HEADING vs PARAGRAPH, it just draws whatever
     * font/size each line carries and advances by that line's leading.
     */
    private static final class RenderLine {
        final String text;
        final PDFont font;
        final float fontSize;
        final float leading;
        final float spaceBefore; // extra gap inserted above this line (e.g. before a heading)

        RenderLine(String text, PDFont font, float fontSize, float leading, float spaceBefore) {
            this.text = text;
            this.font = font;
            this.fontSize = fontSize;
            this.leading = leading;
            this.spaceBefore = spaceBefore;
        }
    }

    private static List<RenderLine> buildRenderLines(List<DocumentTextExtractor.TextBlock> blocks,
                                                     float maxWidth) throws IOException {
        List<RenderLine> lines = new ArrayList<>();

        PDFont bodyFont = PDType1Font.HELVETICA;
        PDFont boldFont = PDType1Font.HELVETICA_BOLD;
        float bodyLeading = BODY_FONT_SIZE * LINE_LEADING;
        float headingLeading = HEADING_FONT_SIZE * LINE_LEADING;
        float headingSpaceBefore = HEADING_FONT_SIZE * 0.7f;

        if (blocks == null || blocks.isEmpty()) {
            lines.add(new RenderLine("(No readable text was extracted from this file.)",
                    bodyFont, BODY_FONT_SIZE, bodyLeading, 0));
            return lines;
        }

        boolean isFirstLine = true;
        for (DocumentTextExtractor.TextBlock block : blocks) {
            switch (block.kind) {
                case BLANK -> {
                    // Paragraph spacer only — represented as an empty
                    // RenderLine so the pagination loop still advances the
                    // cursor by one body line's worth of vertical space.
                    lines.add(new RenderLine("", bodyFont, BODY_FONT_SIZE, bodyLeading, 0));
                }
                case HEADING -> {
                    List<String> wrapped = wrapLine(block.text, boldFont, HEADING_FONT_SIZE, maxWidth);
                    for (int i = 0; i < wrapped.size(); i++) {
                        float spaceBefore = (i == 0 && !isFirstLine) ? headingSpaceBefore : 0;
                        lines.add(new RenderLine(wrapped.get(i), boldFont, HEADING_FONT_SIZE, headingLeading, spaceBefore));
                    }
                }
                case BULLET -> {
                    // Source text may or may not already carry a literal
                    // marker (PDF/TXT/legacy DOC heuristics keep the
                    // original "•"/"-" character; docx-sourced bullets,
                    // detected via real numbering metadata, never have one
                    // in getText()'s output) — only synthesize one if it's
                    // missing, to avoid a doubled-up "• • text" line.
                    String content = startsWithListMarker(block.text) ? block.text : ("\u2022 " + block.text);
                    List<String> wrapped = wrapLine("    " + content, bodyFont, BODY_FONT_SIZE, maxWidth);
                    for (String w : wrapped) {
                        lines.add(new RenderLine(w, bodyFont, BODY_FONT_SIZE, bodyLeading, 0));
                    }
                }
                case NUMBERED -> {
                    // NUMBERED is only ever produced by the surface
                    // heuristic path (classifyLine), whose text already
                    // carries its own "1." / "(2)" style prefix — never
                    // synthesize a second one here.
                    List<String> wrapped = wrapLine("    " + block.text, bodyFont, BODY_FONT_SIZE, maxWidth);
                    for (String w : wrapped) {
                        lines.add(new RenderLine(w, bodyFont, BODY_FONT_SIZE, bodyLeading, 0));
                    }
                }
                default -> { // PARAGRAPH
                    List<String> wrapped = wrapLine(block.text, bodyFont, BODY_FONT_SIZE, maxWidth);
                    for (String w : wrapped) {
                        lines.add(new RenderLine(w, bodyFont, BODY_FONT_SIZE, bodyLeading, 0));
                    }
                }
            }
            isFirstLine = false;
        }

        if (lines.isEmpty()) {
            lines.add(new RenderLine("(No readable text was extracted from this file.)",
                    bodyFont, BODY_FONT_SIZE, bodyLeading, 0));
        }
        return lines;
    }

    /**
     * True if the text already starts with a bullet glyph or a "1."/"(2)"
     * style numbering prefix — used only to decide whether buildRenderLines
     * needs to synthesize a "•" marker for a BULLET block, or whether the
     * source text already carries one (PDF/TXT/legacy-DOC heuristic path).
     */
    private static boolean startsWithListMarker(String text) {
        if (text == null || text.isEmpty()) return false;
        char c = text.charAt(0);
        if (c == '\u2022' || c == '-' || c == '*' || c == '\u2023' || c == '\u25CF' || c == '\u25E6') return true;
        return text.matches("^\\(?\\d{1,3}[.)]\\)?\\s+.*");
    }

    /**
     * Paginates a flat list of already-resolved {@link RenderLine}s,
     * switching font/size per line and starting a fresh page whenever the
     * next line (plus its spaceBefore) would cross the bottom margin.
     */
    private static void drawRenderLinesAcrossPages(DrawState state, List<RenderLine> lines) throws IOException {
        float bottomLimit = MARGIN;

        PDPageContentStream cs = new PDPageContentStream(state.doc, state.page, PDPageContentStream.AppendMode.APPEND, true);
        boolean openText = false;

        for (RenderLine line : lines) {
            float neededY = state.y - line.spaceBefore - line.leading;
            if (neededY < bottomLimit) {
                if (openText) {
                    cs.endText();
                    openText = false;
                }
                cs.close();

                state.page = newPage(state.doc);
                state.y = PAGE_HEIGHT - MARGIN;

                cs = new PDPageContentStream(state.doc, state.page, PDPageContentStream.AppendMode.APPEND, true);
            }

            state.y -= line.spaceBefore;

            if (line.text.isEmpty()) {
                state.y -= line.leading;
                continue;
            }

            if (!openText) {
                cs.beginText();
                cs.newLineAtOffset(MARGIN, state.y);
                openText = true;
            }
            cs.setFont(line.font, line.fontSize);
            cs.showText(line.text);
            cs.newLineAtOffset(0, -line.leading);
            state.y -= line.leading;
        }

        if (openText) {
            cs.endText();
        }
        cs.close();
    }

    /**
     * PDFBox's built-in Standard 14 fonts (Helvetica, etc.) only cover
     * WinAnsiEncoding / Latin-1 and cannot render arbitrary Unicode (smart
     * quotes, em-dashes, bullets, emoji, etc. from copy-pasted source
     * documents). Rather than letting PDFBox throw on an unsupported glyph
     * mid-render, replace common offenders with safe ASCII equivalents and
     * drop anything else outside the printable Latin-1 range.
     *
     * NOTE: the literal "•" (U+2022) used for synthetic bullet markers in
     * buildRenderLines() is intentionally NOT stripped here — WinAnsiEncoding
     * (which PDFBox's Standard 14 fonts use) maps a bullet glyph into its
     * single-byte range and PDFBox's text layout machinery already handles
     * translating U+2022 to it, so it renders correctly without needing an
     * ASCII substitute.
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
            if (c == '\n' || c == '\t' || c == '\u2022' || (c >= 0x20 && c <= 0xFF)) {
                sb.append(c);
            } else {
                sb.append('?');
            }
        }
        return sb.toString();
    }
}