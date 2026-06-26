package com.adaptivelearning.adaptivelearningbackend;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Single source of truth for extracting plain text from an uploaded handout
 * file on disk, given its original filename (for extension/content-type
 * sniffing) and the path it was stored at.
 *
 * Previously this logic was duplicated between MaterialController (used at
 * upload time, where a MultipartFile with a declared content type is
 * available) and QuizController (used later, when regenerating Adapted/
 * Targeted quizzes from a Material row that only has a filename + stored
 * path — no MultipartFile). The two copies had already drifted: QuizController's
 * copy was missing the .doc/.docx branches entirely, silently returning ""
 * for any Word document and breaking Adapted/Targeted quiz generation for
 * those uploads. Centralizing the logic here means a new supported format,
 * or a bug fix to an existing one, only ever has to happen in one place.
 */
public final class DocumentTextExtractor {

    private DocumentTextExtractor() {}

    /**
     * One logical line of a document, classified for display purposes only.
     * This is heuristic, NOT a faithful reconstruction of the original
     * document's styling — plain-text extraction (PDFBox's PDFTextStripper,
     * POI's WordExtractor for legacy .doc) throws away font size, bold/
     * italic flags, and real list semantics; there is no way to recover
     * them after the fact. For .docx specifically, real paragraph style
     * names and numbering IDs ARE available from the OOXML structure, so
     * HEADING/BULLET/NUMBERED there reflect the author's actual styling
     * rather than a guess. For PDF/.doc/.txt, the same kinds are inferred
     * from surface patterns (short standalone lines, lines starting with a
     * bullet glyph or "1.", etc.) and will occasionally misclassify —
     * treat this as "good enough to break up a wall of text", not ground
     * truth about the source document's real formatting.
     */
    public static final class TextBlock {
        public enum Kind { HEADING, BULLET, NUMBERED, PARAGRAPH, BLANK }

        public final Kind kind;
        public final String text;

        public TextBlock(Kind kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    /**
     * Extracts text as a sequence of classified {@link TextBlock}s, preserving
     * real line/paragraph breaks instead of collapsing all whitespace to single
     * spaces. Used ONLY by the admin Content Review PDF renderer
     * ({@code PdfRenderer}) — every other caller (quiz generation, knowledge
     * extraction, FILLBLANK verbatim-excerpt matching) continues to use the
     * existing {@link #extractText} / whitespace-collapsed path unchanged,
     * since those callers depend on normalized whitespace for matching and
     * prompt construction.
     *
     * @param originalFilename the user-facing filename (e.g. "notes.docx")
     * @param contentType      declared MIME type, or null/blank if unknown
     * @param storedPath       path to the actual file bytes on disk
     * @return ordered list of blocks; empty list if extraction fails or the
     *         format is unsupported (never throws)
     */
    public static List<TextBlock> extractFormattedText(String originalFilename, String contentType, Path storedPath) {
        try {
            String name = Optional.ofNullable(originalFilename).orElse("").toLowerCase(Locale.ROOT);
            String type = Optional.ofNullable(contentType).orElse("").toLowerCase(Locale.ROOT);

            if (name.endsWith(".txt") || name.endsWith(".csv") || type.contains("text")) {
                return classifyPlainLines(readRawTextTolerant(storedPath));
            } else if (name.endsWith(".pdf") || type.contains("pdf")) {
                return extractPdfFormatted(storedPath);
            } else if (name.endsWith(".docx") || type.contains("wordprocessingml")) {
                return extractDocxFormatted(storedPath);
            } else if (name.endsWith(".doc") || type.contains("msword")) {
                return classifyPlainLines(extractDocRawLines(storedPath));
            }
            return classifyPlainLines(sniffAndExtractRaw(storedPath));
        } catch (Exception e) {
            System.err.println("Formatted text extraction failed: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // ── Heuristic line classification (shared by TXT, PDF, legacy DOC) ─────

    /**
     * Classifies raw, newline-preserved text into TextBlocks using surface
     * heuristics only (no real style metadata available for these formats):
     *   - blank line                              -> BLANK (paragraph spacer)
     *   - starts with a bullet glyph (•, -, *, ‣)  -> BULLET
     *   - starts with "1." / "1)" / "(1)" etc.     -> NUMBERED
     *   - short (<=70 chars), no ending punctuation,
     *     and either ALL CAPS or Title Case         -> HEADING
     *   - everything else                          -> PARAGRAPH
     */
    private static List<TextBlock> classifyPlainLines(String rawText) {
        List<TextBlock> blocks = new ArrayList<>();
        if (rawText == null || rawText.isBlank()) return blocks;

        String normalized = rawText.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalized.split("\n");

        for (String rawLine : lines) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                blocks.add(new TextBlock(TextBlock.Kind.BLANK, ""));
                continue;
            }
            blocks.add(new TextBlock(classifyLine(line), line));
        }
        return blocks;
    }

    private static final java.util.regex.Pattern BULLET_PATTERN =
            java.util.regex.Pattern.compile("^[•\\-*\u2023\u25CF\u25E6]\\s+(.*)$");
    private static final java.util.regex.Pattern NUMBERED_PATTERN =
            java.util.regex.Pattern.compile("^(\\(?\\d{1,3}[.)]\\)?)\\s+(.*)$");

    private static TextBlock.Kind classifyLine(String line) {
        java.util.regex.Matcher bulletM = BULLET_PATTERN.matcher(line);
        if (bulletM.matches()) return TextBlock.Kind.BULLET;

        java.util.regex.Matcher numM = NUMBERED_PATTERN.matcher(line);
        if (numM.matches()) return TextBlock.Kind.NUMBERED;

        boolean shortEnough = line.length() <= 70;
        boolean noTrailingPunctuation = !line.matches(".*[.,;:]$");
        boolean looksLikeHeading = shortEnough && noTrailingPunctuation && (isAllCapsWord(line) || isTitleCaseHeading(line));

        return looksLikeHeading ? TextBlock.Kind.HEADING : TextBlock.Kind.PARAGRAPH;
    }

    private static boolean isAllCapsWord(String line) {
        String lettersOnly = line.replaceAll("[^A-Za-z]", "");
        return lettersOnly.length() >= 3 && lettersOnly.equals(lettersOnly.toUpperCase(Locale.ROOT))
                && !lettersOnly.equals(lettersOnly.toLowerCase(Locale.ROOT));
    }

    private static boolean isTitleCaseHeading(String line) {
        String[] words = line.split("\\s+");
        if (words.length == 0 || words.length > 10) return false;
        int capitalizedCount = 0;
        for (String w : words) {
            String letters = w.replaceAll("[^A-Za-z]", "");
            if (letters.isEmpty()) continue;
            if (Character.isUpperCase(letters.charAt(0))) capitalizedCount++;
        }
        // Most words capitalized, and the line is short relative to its word
        // count (headings read as punchy, not as a wrapped sentence).
        return capitalizedCount >= Math.max(1, words.length - 1);
    }

    // ── PDF: newline-preserving extraction (real text, heuristically classified) ──

    /**
     * Same PDFBox extraction as {@link #extractPdfText}, but WITHOUT the
     * whitespace-collapsing replaceAll("\\s+", " ") that method applies.
     * PDFTextStripper already emits real line breaks between visual lines
     * on the page; preserving them (instead of flattening everything to one
     * space-separated run) is what lets classifyPlainLines() tell headings,
     * bullets, and paragraph breaks apart at all.
     */
    private static List<TextBlock> extractPdfFormatted(Path storedPath) {
        try (PDDocument doc = PDDocument.load(storedPath.toFile())) {
            doc.setAllSecurityToBeRemoved(true);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return classifyPlainLines(stripper.getText(doc));
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            // Same owner-password fallback as extractPdfText().
            try (PDDocument doc = PDDocument.load(storedPath.toFile(), "")) {
                doc.setAllSecurityToBeRemoved(true);
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                return classifyPlainLines(stripper.getText(doc));
            } catch (Exception inner) {
                System.out.println("PDF formatted extraction error (password-protected): " + inner.getMessage());
                return new ArrayList<>();
            }
        } catch (Exception e) {
            System.out.println("PDF formatted extraction error: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // ── DOCX: real structure from OOXML (style names + numbering), not a guess ──

    /**
     * Unlike every other format here, .docx carries REAL paragraph-level
     * style metadata — Word's built-in "Heading 1"/"Heading 2" styles and
     * numbering/bullet list IDs are actual structured data in the OOXML,
     * not something inferred from surface patterns. Where that metadata is
     * present it is trusted directly; only a paragraph with no heading
     * style and no list numbering falls back to the same surface heuristics
     * used for PDF/DOC/TXT (classifyLine).
     *
     * NOTE: this intentionally collapses both bulleted AND numbered Word
     * lists into Kind.BULLET. Telling them apart for real means walking the
     * document's numbering.xml definitions (abstractNumId -> numFmt) to see
     * whether a given numId resolves to "bullet" or "decimal" — more
     * machinery than this readability upgrade warrants. PdfRenderer adds a
     * synthetic "•" marker for these blocks since, unlike the heuristic
     * path below, the literal bullet/number glyph is never present in
     * getText()'s output (Word stores it as paragraph formatting, not text).
     */
    private static List<TextBlock> extractDocxFormatted(Path storedPath) {
        List<TextBlock> blocks = new ArrayList<>();
        try (InputStream is = Files.newInputStream(storedPath);
             XWPFDocument document = new XWPFDocument(is)) {

            XWPFStyles styles = document.getStyles();

            for (XWPFParagraph para : document.getParagraphs()) {
                String text = para.getText();
                if (text == null || text.isBlank()) {
                    blocks.add(new TextBlock(TextBlock.Kind.BLANK, ""));
                    continue;
                }
                String trimmed = text.strip();
                blocks.add(new TextBlock(classifyDocxParagraph(para, styles, trimmed), trimmed));
            }
        } catch (Exception e) {
            System.out.println("DOCX formatted extraction error: " + e.getMessage());
        }
        return blocks;
    }

    private static TextBlock.Kind classifyDocxParagraph(XWPFParagraph para, XWPFStyles styles, String text) {
        try {
            String styleId = para.getStyleID();
            if (styleId != null) {
                String lowerId = styleId.toLowerCase(Locale.ROOT);
                if (lowerId.contains("heading") || lowerId.contains("title")) {
                    return TextBlock.Kind.HEADING;
                }
                if (styles != null) {
                    XWPFStyle style = styles.getStyle(styleId);
                    if (style != null && style.getName() != null) {
                        String lowerName = style.getName().toLowerCase(Locale.ROOT);
                        if (lowerName.contains("heading") || lowerName.contains("title")) {
                            return TextBlock.Kind.HEADING;
                        }
                    }
                }
            }
            // Real numbering metadata — Word's bullet/number glyph is stored
            // as a numId on the paragraph, not literal characters in the
            // text, so this is the ONLY reliable way to detect a docx list
            // item; the surface BULLET_PATTERN/NUMBERED_PATTERN regexes used
            // for PDF/DOC/TXT would never match here.
            if (para.getNumID() != null) {
                return TextBlock.Kind.BULLET;
            }
        } catch (Exception e) {
            // Fall through to surface heuristics below — never let a style
            // lookup failure take down the whole extraction.
        }
        return classifyLine(text);
    }

    // ── Legacy DOC: newline-preserving raw paragraphs ───────────────────

    /**
     * Same HWPF extraction as {@link #extractDocText}, but joined with real
     * newlines between paragraphs instead of single spaces, so
     * classifyPlainLines() has actual line breaks to work with. Legacy .doc
     * has no equivalent of docx's style/numbering metadata exposed by
     * WordExtractor, so this still falls back to surface heuristics — same
     * ceiling as PDF/TXT.
     */
    private static String extractDocRawLines(Path storedPath) {
        try (InputStream is = Files.newInputStream(storedPath);
             HWPFDocument document = new HWPFDocument(is);
             WordExtractor extractor = new WordExtractor(document)) {
            String[] paragraphs = extractor.getParagraphText();
            return String.join("\n", paragraphs);
        } catch (Exception e) {
            System.out.println("DOC formatted extraction error: " + e.getMessage());
            return "";
        }
    }

    // ── Sniffed fallback: newline-preserving raw text ───────────────────

    /**
     * Same magic-byte sniffing as {@link #sniffAndExtract}, but returns
     * newline-preserving raw text for classifyPlainLines() to work with.
     * The DOCX branch deliberately does NOT call extractDocxFormatted()
     * (which needs real paragraph objects, not a flat string) — it falls
     * back to XWPFWordExtractor's flat text instead, which then goes
     * through the same surface heuristics as PDF/DOC/TXT. That's an
     * acceptable degradation for this fallback-of-a-fallback path: it only
     * runs when BOTH the filename extension AND the declared content type
     * failed to identify the format in the first place.
     */
    private static String sniffAndExtractRaw(Path storedPath) {
        try {
            byte[] head = new byte[8];
            int read;
            try (InputStream is = Files.newInputStream(storedPath)) {
                read = is.read(head);
            }
            if (read >= 4 && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F') {
                try (PDDocument doc = PDDocument.load(storedPath.toFile())) {
                    doc.setAllSecurityToBeRemoved(true);
                    PDFTextStripper stripper = new PDFTextStripper();
                    stripper.setSortByPosition(true);
                    return stripper.getText(doc);
                }
            }
            if (read >= 4 && (head[0] & 0xFF) == 0x50 && (head[1] & 0xFF) == 0x4B
                    && (head[2] & 0xFF) == 0x03 && (head[3] & 0xFF) == 0x04) {
                try (InputStream is = Files.newInputStream(storedPath);
                     XWPFDocument document = new XWPFDocument(is);
                     XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                    String text = extractor.getText();
                    return text == null ? "" : text;
                }
            }
            if (read >= 8 && (head[0] & 0xFF) == 0xD0 && (head[1] & 0xFF) == 0xCF
                    && (head[2] & 0xFF) == 0x11 && (head[3] & 0xFF) == 0xE0) {
                return extractDocRawLines(storedPath);
            }
            return readRawTextTolerant(storedPath);
        } catch (Exception e) {
            System.out.println("Formatted format sniffing failed: " + e.getMessage());
            return "";
        }
    }

    /**
     * Same tolerant UTF-8/ISO-8859-1 decoding as {@link #readTextFileTolerant},
     * but without the final replaceAll("\\s+", " ") — real line breaks are
     * exactly what classifyPlainLines() needs to tell paragraphs apart.
     */
    private static String readRawTextTolerant(Path storedPath) throws java.io.IOException {
        byte[] bytes = Files.readAllBytes(storedPath);
        String text;
        try {
            text = new String(bytes, StandardCharsets.UTF_8);
            if (text.indexOf('\uFFFD') >= 0) {
                text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
            }
        } catch (Exception e) {
            text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        }
        return text;
    }

    /**
     * Extracts text using both the filename extension and (if available) the
     * declared MIME content type to decide which extractor to use.
     *
     * @param originalFilename the user-facing filename (e.g. "notes.docx")
     * @param contentType      declared MIME type, or null/blank if unknown
     *                         (e.g. when re-reading a previously stored file
     *                         with no MultipartFile available)
     * @param storedPath       path to the actual file bytes on disk
     * @return extracted, whitespace-normalized text, or "" if the format is
     *         unsupported or extraction fails (never throws)
     */
    public static String extractText(String originalFilename, String contentType, Path storedPath) {
        try {
            String name = Optional.ofNullable(originalFilename).orElse("").toLowerCase(Locale.ROOT);
            String type = Optional.ofNullable(contentType).orElse("").toLowerCase(Locale.ROOT);

            if (name.endsWith(".txt") || name.endsWith(".csv") || type.contains("text")) {
                return readTextFileTolerant(storedPath);
            } else if (name.endsWith(".pdf") || type.contains("pdf")) {
                return extractPdfText(storedPath);
            } else if (name.endsWith(".docx") || type.contains("wordprocessingml")) {
                return extractDocxText(storedPath);
            } else if (name.endsWith(".doc") || type.contains("msword")) {
                return extractDocText(storedPath);
            }

            // Neither the filename extension nor the declared/stored content type
            // matched a known format (e.g. a re-read with no stored content type,
            // or an upload whose original filename had no extension at all). Fall
            // back to sniffing the actual file bytes — cheap and reliable for the
            // formats this app supports — instead of giving up and returning "".
            return sniffAndExtract(storedPath);
        } catch (Exception e) {
            System.err.println("Text extraction failed: " + e.getMessage());
            return "";
        }
    }

    /**
     * Last-resort format detection by reading the file's leading bytes
     * directly, used only when filename extension and declared/stored
     * content type both failed to identify the format. PDFs start with the
     * literal bytes "%PDF"; DOCX/modern Office files are ZIP archives
     * (magic bytes "PK\x03\x04"); legacy DOC files use the OLE2 compound
     * file signature. TXT/CSV have no reliable magic bytes, so as a final
     * fallback this treats the content as plain text rather than giving up.
     */
    private static String sniffAndExtract(Path storedPath) {
        try {
            byte[] head = new byte[8];
            int read;
            try (InputStream is = Files.newInputStream(storedPath)) {
                read = is.read(head);
            }
            if (read >= 4 && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F') {
                return extractPdfText(storedPath);
            }
            if (read >= 4 && (head[0] & 0xFF) == 0x50 && (head[1] & 0xFF) == 0x4B
                    && (head[2] & 0xFF) == 0x03 && (head[3] & 0xFF) == 0x04) {
                // ZIP-based — almost certainly .docx in this app's context.
                return extractDocxText(storedPath);
            }
            if (read >= 8 && (head[0] & 0xFF) == 0xD0 && (head[1] & 0xFF) == 0xCF
                    && (head[2] & 0xFF) == 0x11 && (head[3] & 0xFF) == 0xE0) {
                // OLE2 compound file signature — legacy .doc.
                return extractDocText(storedPath);
            }
            // No recognizable binary signature — last resort, try as plain text.
            return readTextFileTolerant(storedPath);
        } catch (Exception e) {
            System.out.println("Format sniffing failed: " + e.getMessage());
            return "";
        }
    }

    /** Convenience overload for callers with no declared content type available. */
    public static String extractText(String originalFilename, Path storedPath) {
        return extractText(originalFilename, null, storedPath);
    }

    /** Modern Office Open XML format (.docx) via XWPFDocument. */
    private static String extractDocxText(Path storedPath) {
        try (InputStream is = Files.newInputStream(storedPath);
             XWPFDocument document = new XWPFDocument(is);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            String text = extractor.getText();
            return text == null ? "" : text.replaceAll("\\s+", " ").trim();
        } catch (Exception e) {
            System.out.println("DOCX extraction error: " + e.getMessage());
            return "";
        }
    }

    /**
     * Reads a .txt/.csv file as text, tolerating non-UTF-8 byte sequences
     * instead of throwing. Files.readString(..., UTF_8) throws
     * MalformedInputException on the first invalid byte, which previously
     * meant a .txt/.csv handout saved in Windows-1252/Latin-1 (very common
     * from copy-pasted Word/Excel content) silently extracted to "" — caught
     * by the outer try/catch in extractText() with no indication of why.
     * UTF-8 is tried first (the common case); on failure this falls back to
     * ISO-8859-1, which can decode any byte sequence without throwing, so a
     * legitimately-encoded file always yields its actual text instead of "".
     */
    private static String readTextFileTolerant(Path storedPath) throws java.io.IOException {
        byte[] bytes = Files.readAllBytes(storedPath);
        String text;
        try {
            text = new String(bytes, StandardCharsets.UTF_8);
            if (text.indexOf('\uFFFD') >= 0) {
                // Replacement characters indicate invalid UTF-8 sequences were
                // silently substituted — re-decode with a charset that can't fail.
                text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
            }
        } catch (Exception e) {
            text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    /** PDF text extraction via PDFBox. */
    private static String extractPdfText(Path storedPath) {
        try (PDDocument doc = PDDocument.load(storedPath.toFile())) {
            doc.setAllSecurityToBeRemoved(true);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc).replaceAll("\\s+", " ").trim();
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            // Owner-password-protected (but not user-password-protected) PDFs can
            // often still be opened by explicitly loading with an empty password —
            // PDDocument.load(File) without a password fails fast on these instead
            // of trying that fallback. Without this, every owner-protected PDF
            // (a very common export setting from many "print to PDF" tools) always
            // returned "" here, even though the same file's text was readable.
            try (PDDocument doc = PDDocument.load(storedPath.toFile(), "")) {
                doc.setAllSecurityToBeRemoved(true);
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                return stripper.getText(doc).replaceAll("\\s+", " ").trim();
            } catch (Exception inner) {
                System.out.println("PDF extraction error (password-protected): " + inner.getMessage());
                return "";
            }
        } catch (Exception e) {
            System.out.println("PDF extraction error: " + e.getMessage());
            return "";
        }
    }

    /** Legacy binary Word format (.doc) via HWPFDocument — separate API from .docx. */
    private static String extractDocText(Path storedPath) {
        try (InputStream is = Files.newInputStream(storedPath);
             HWPFDocument document = new HWPFDocument(is);
             WordExtractor extractor = new WordExtractor(document)) {
            String text = String.join(" ", extractor.getParagraphText());
            return text.replaceAll("\\s+", " ").trim();
        } catch (Exception e) {
            System.out.println("DOC extraction error: " + e.getMessage());
            return "";
        }
    }
}