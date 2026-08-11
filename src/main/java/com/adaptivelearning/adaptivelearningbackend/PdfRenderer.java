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

/** Renders plain extracted text into a paginated, readable PDF using PDFBox, without ever opening the original file. */
public final class PdfRenderer {

    private static final float PAGE_WIDTH = PDRectangle.LETTER.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.LETTER.getHeight();
    private static final float MARGIN = 56f;
    private static final float TITLE_FONT_SIZE = 16f;
    private static final float HEADING_FONT_SIZE = 13f;
    private static final float META_FONT_SIZE = 9f;
    private static final float BODY_FONT_SIZE = 11f;
    private static final float LINE_LEADING = 1.42f;

    private PdfRenderer() {}

    /** Renders a title, meta lines, and flat body text into a paginated PDF. */
    public static byte[] render(String title, List<String> metaLines, String bodyText) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDFont titleFont = PDType1Font.HELVETICA_BOLD;
            PDFont metaFont = PDType1Font.HELVETICA_OBLIQUE;
            PDFont bodyFont = PDType1Font.HELVETICA;

            float maxWidth = PAGE_WIDTH - (2 * MARGIN);

            List<String> wrappedLines = wrapParagraphs(bodyText, bodyFont, BODY_FONT_SIZE, maxWidth);

            PDPage page = newPage(doc);
            PDPageContentStream cs = new PDPageContentStream(doc, page);
            float cursorY = PAGE_HEIGHT - MARGIN;

            cursorY = drawWrappedText(doc, cs, List.of(safe(title, "Untitled Document")),
                    titleFont, TITLE_FONT_SIZE, MARGIN, cursorY, maxWidth, TITLE_FONT_SIZE * LINE_LEADING);
            cursorY -= 6;

            if (metaLines != null && !metaLines.isEmpty()) {
                List<String> wrappedMeta = new ArrayList<>();
                for (String m : metaLines) {
                    if (m == null || m.isBlank()) continue;
                    wrappedMeta.addAll(wrapLine(m, metaFont, META_FONT_SIZE, maxWidth));
                }
                cursorY = drawWrappedText(doc, cs, wrappedMeta, metaFont, META_FONT_SIZE,
                        MARGIN, cursorY, maxWidth, META_FONT_SIZE * LINE_LEADING);
            }

            cursorY -= 10;
            cs.setLineWidth(0.75f);
            cs.moveTo(MARGIN, cursorY);
            cs.lineTo(PAGE_WIDTH - MARGIN, cursorY);
            cs.stroke();
            cursorY -= 18;

            cs.close();

            DrawState state = new DrawState(doc, page, cursorY);
            drawBodyAcrossPages(state, wrappedLines, bodyFont, BODY_FONT_SIZE, maxWidth);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** Renders a title, meta lines, and classified text blocks into a paginated PDF with heading/bullet formatting. */
    public static byte[] renderFormatted(String title, List<String> metaLines,
                                         List<DocumentTextExtractor.TextBlock> blocks) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDFont titleFont = PDType1Font.HELVETICA_BOLD;
            PDFont metaFont = PDType1Font.HELVETICA_OBLIQUE;

            float maxWidth = PAGE_WIDTH - (2 * MARGIN);

            PDPage page = newPage(doc);
            PDPageContentStream cs = new PDPageContentStream(doc, page);
            float cursorY = PAGE_HEIGHT - MARGIN;

            cursorY = drawWrappedText(doc, cs, List.of(safe(title, "Untitled Document")),
                    titleFont, TITLE_FONT_SIZE, MARGIN, cursorY, maxWidth, TITLE_FONT_SIZE * LINE_LEADING);
            cursorY -= 6;

            if (metaLines != null && !metaLines.isEmpty()) {
                List<String> wrappedMeta = new ArrayList<>();
                for (String m : metaLines) {
                    if (m == null || m.isBlank()) continue;
                    wrappedMeta.addAll(wrapLine(m, metaFont, META_FONT_SIZE, maxWidth));
                }
                cursorY = drawWrappedText(doc, cs, wrappedMeta, metaFont, META_FONT_SIZE,
                        MARGIN, cursorY, maxWidth, META_FONT_SIZE * LINE_LEADING);
            }

            cursorY -= 10;
            cs.setLineWidth(0.75f);
            cs.moveTo(MARGIN, cursorY);
            cs.lineTo(PAGE_WIDTH - MARGIN, cursorY);
            cs.stroke();
            cursorY -= 18;

            cs.close();

            List<RenderLine> renderLines = buildRenderLines(blocks, maxWidth);
            DrawState state = new DrawState(doc, page, cursorY);
            drawRenderLinesAcrossPages(state, renderLines);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static String safe(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static PDPage newPage(PDDocument doc) {
        PDPage page = new PDPage(PDRectangle.LETTER);
        doc.addPage(page);
        return page;
    }

    /** Draws a short, fixed set of header lines and returns the new Y cursor. */
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

    /** Splits text into paragraphs and word-wraps each to fit maxWidth, inserting blank spacer lines between them. */
    private static List<String> wrapParagraphs(String text, PDFont font, float fontSize, float maxWidth) throws IOException {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            result.add("(No readable text was extracted from this file.)");
            return result;
        }

        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        String[] paragraphs = normalized.split("\n+");

        for (int i = 0; i < paragraphs.length; i++) {
            String para = paragraphs[i].trim();
            if (para.isEmpty()) continue;
            result.addAll(wrapLine(para, font, fontSize, maxWidth));
            if (i < paragraphs.length - 1) {
                result.add("");
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

    /** One resolved line ready to draw, with its own font, size, and leading. */
    private static final class RenderLine {
        final String text;
        final PDFont font;
        final float fontSize;
        final float leading;
        final float spaceBefore;

        RenderLine(String text, PDFont font, float fontSize, float leading, float spaceBefore) {
            this.text = text;
            this.font = font;
            this.fontSize = fontSize;
            this.leading = leading;
            this.spaceBefore = spaceBefore;
        }
    }

    /** Converts classified text blocks into flat, format-resolved render lines. */
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
                    String content = startsWithListMarker(block.text) ? block.text : ("\u2022 " + block.text);
                    List<String> wrapped = wrapLine("    " + content, bodyFont, BODY_FONT_SIZE, maxWidth);
                    for (String w : wrapped) {
                        lines.add(new RenderLine(w, bodyFont, BODY_FONT_SIZE, bodyLeading, 0));
                    }
                }
                case NUMBERED -> {
                    List<String> wrapped = wrapLine("    " + block.text, bodyFont, BODY_FONT_SIZE, maxWidth);
                    for (String w : wrapped) {
                        lines.add(new RenderLine(w, bodyFont, BODY_FONT_SIZE, bodyLeading, 0));
                    }
                }
                default -> {
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

    /** True if text already starts with a bullet glyph or a "1."/"(2)" numbering prefix. */
    private static boolean startsWithListMarker(String text) {
        if (text == null || text.isEmpty()) return false;
        char c = text.charAt(0);
        if (c == '\u2022' || c == '-' || c == '*' || c == '\u2023' || c == '\u25CF' || c == '\u25E6') return true;
        return text.matches("^\\(?\\d{1,3}[.)]\\)?\\s+.*");
    }

    /** Paginates resolved render lines, starting a new page whenever the next line would cross the bottom margin. */
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

    /** Replaces Unicode punctuation with Latin-1 equivalents and strips unsupported glyphs so PDFBox's Standard 14 fonts can render the text. */
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