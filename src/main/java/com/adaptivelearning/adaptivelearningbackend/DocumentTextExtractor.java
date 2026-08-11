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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Extracts plain and structurally-classified text from uploaded PDF/DOCX/DOC/TXT files. */
public final class DocumentTextExtractor {

    private DocumentTextExtractor() {}

    /** One classified line/paragraph of a document (heuristic classification, not exact source formatting). */
    public static final class TextBlock {
        public enum Kind { HEADING, BULLET, NUMBERED, PARAGRAPH, BLANK }

        public final Kind kind;
        public final String text;

        public TextBlock(Kind kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // PRIMARY API — byte[]-based (used when files are stored in R2)
    // ════════════════════════════════════════════════════════════════════════

    /** Extracts whitespace-normalized plain text from raw file bytes, or "" on failure. */
    public static String extractText(String originalFilename, String contentType, byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length == 0) return "";
        try {
            String name = Optional.ofNullable(originalFilename).orElse("").toLowerCase(Locale.ROOT);
            String type = Optional.ofNullable(contentType).orElse("").toLowerCase(Locale.ROOT);

            if (name.endsWith(".txt") || name.endsWith(".csv") || type.contains("text")) {
                return readTextFileTolerantBytes(fileBytes);
            } else if (name.endsWith(".pdf") || type.contains("pdf")) {
                return extractPdfTextBytes(fileBytes);
            } else if (name.endsWith(".docx") || type.contains("wordprocessingml")) {
                return extractDocxTextBytes(fileBytes);
            } else if (name.endsWith(".doc") || type.contains("msword")) {
                return extractDocTextBytes(fileBytes);
            }
            return sniffAndExtractBytes(fileBytes);
        } catch (Exception e) {
            System.err.println("Text extraction failed: " + e.getMessage());
            return "";
        }
    }

    /** Extracts classified TextBlocks (headings/bullets/paragraphs) from raw file bytes, preserving line breaks. */
    public static List<TextBlock> extractFormattedText(String originalFilename,
                                                       String contentType,
                                                       byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length == 0) return new ArrayList<>();
        try {
            String name = Optional.ofNullable(originalFilename).orElse("").toLowerCase(Locale.ROOT);
            String type = Optional.ofNullable(contentType).orElse("").toLowerCase(Locale.ROOT);

            if (name.endsWith(".txt") || name.endsWith(".csv") || type.contains("text")) {
                return classifyPlainLines(readRawTextTolerantBytes(fileBytes));
            } else if (name.endsWith(".pdf") || type.contains("pdf")) {
                return extractPdfFormattedBytes(fileBytes);
            } else if (name.endsWith(".docx") || type.contains("wordprocessingml")) {
                return extractDocxFormattedBytes(fileBytes);
            } else if (name.endsWith(".doc") || type.contains("msword")) {
                return classifyPlainLines(extractDocRawLinesBytes(fileBytes));
            }
            return classifyPlainLines(sniffAndExtractRawBytes(fileBytes));
        } catch (Exception e) {
            System.err.println("Formatted text extraction failed: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // LEGACY PATH-BASED API — for callers with a file on disk
    // ════════════════════════════════════════════════════════════════════════

    /** Extracts text from a file on disk by reading it into memory and delegating to the byte[] variant. */
    public static String extractText(String originalFilename, String contentType, Path storedPath) {
        try {
            return extractText(originalFilename, contentType, Files.readAllBytes(storedPath));
        } catch (Exception e) {
            System.err.println("Text extraction (path) failed: " + e.getMessage());
            return "";
        }
    }

    /** Convenience overload for callers with no declared content type. */
    public static String extractText(String originalFilename, Path storedPath) {
        return extractText(originalFilename, null, storedPath);
    }

    /** Extracts classified TextBlocks from a file on disk. */
    public static List<TextBlock> extractFormattedText(String originalFilename,
                                                       String contentType,
                                                       Path storedPath) {
        try {
            return extractFormattedText(originalFilename, contentType, Files.readAllBytes(storedPath));
        } catch (Exception e) {
            System.err.println("Formatted text extraction (path) failed: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // PRIVATE — byte[]-based extractors
    // ════════════════════════════════════════════════════════════════════════

    private static String extractPdfTextBytes(byte[] bytes) {
        try (PDDocument doc = PDDocument.load(bytes)) {
            doc.setAllSecurityToBeRemoved(true);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc).replaceAll("\\s+", " ").trim();
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            try (PDDocument doc = PDDocument.load(bytes, "")) {
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

    private static String extractDocxTextBytes(byte[] bytes) {
        try (InputStream is = new ByteArrayInputStream(bytes);
             XWPFDocument document = new XWPFDocument(is);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            String text = extractor.getText();
            return text == null ? "" : text.replaceAll("\\s+", " ").trim();
        } catch (Exception e) {
            System.out.println("DOCX extraction error: " + e.getMessage());
            return "";
        }
    }

    private static String extractDocTextBytes(byte[] bytes) {
        try (InputStream is = new ByteArrayInputStream(bytes);
             HWPFDocument document = new HWPFDocument(is);
             WordExtractor extractor = new WordExtractor(document)) {
            String text = String.join(" ", extractor.getParagraphText());
            return text.replaceAll("\\s+", " ").trim();
        } catch (Exception e) {
            System.out.println("DOC extraction error: " + e.getMessage());
            return "";
        }
    }

    private static String readTextFileTolerantBytes(byte[] bytes) {
        try {
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.indexOf('\uFFFD') >= 0) {
                text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
            }
            return text.replaceAll("\\s+", " ").trim();
        } catch (Exception e) {
            return new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)
                    .replaceAll("\\s+", " ").trim();
        }
    }

    private static String sniffAndExtractBytes(byte[] bytes) {
        try {
            if (bytes.length >= 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F') {
                return extractPdfTextBytes(bytes);
            }
            if (bytes.length >= 4
                    && (bytes[0] & 0xFF) == 0x50 && (bytes[1] & 0xFF) == 0x4B
                    && (bytes[2] & 0xFF) == 0x03 && (bytes[3] & 0xFF) == 0x04) {
                return extractDocxTextBytes(bytes);
            }
            if (bytes.length >= 8
                    && (bytes[0] & 0xFF) == 0xD0 && (bytes[1] & 0xFF) == 0xCF
                    && (bytes[2] & 0xFF) == 0x11 && (bytes[3] & 0xFF) == 0xE0) {
                return extractDocTextBytes(bytes);
            }
            return readTextFileTolerantBytes(bytes);
        } catch (Exception e) {
            System.out.println("Format sniffing failed: " + e.getMessage());
            return "";
        }
    }

    // ── Formatted (byte[]) ────────────────────────────────────────────────

    private static List<TextBlock> extractPdfFormattedBytes(byte[] bytes) {
        try (PDDocument doc = PDDocument.load(bytes)) {
            doc.setAllSecurityToBeRemoved(true);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return classifyPlainLines(stripper.getText(doc));
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            try (PDDocument doc = PDDocument.load(bytes, "")) {
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

    private static List<TextBlock> extractDocxFormattedBytes(byte[] bytes) {
        List<TextBlock> blocks = new ArrayList<>();
        try (InputStream is = new ByteArrayInputStream(bytes);
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

    private static String extractDocRawLinesBytes(byte[] bytes) {
        try (InputStream is = new ByteArrayInputStream(bytes);
             HWPFDocument document = new HWPFDocument(is);
             WordExtractor extractor = new WordExtractor(document)) {
            String[] paragraphs = extractor.getParagraphText();
            return String.join("\n", paragraphs);
        } catch (Exception e) {
            System.out.println("DOC formatted extraction error: " + e.getMessage());
            return "";
        }
    }

    private static String sniffAndExtractRawBytes(byte[] bytes) {
        try {
            if (bytes.length >= 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F') {
                try (PDDocument doc = PDDocument.load(bytes)) {
                    doc.setAllSecurityToBeRemoved(true);
                    PDFTextStripper stripper = new PDFTextStripper();
                    stripper.setSortByPosition(true);
                    return stripper.getText(doc);
                }
            }
            if (bytes.length >= 4
                    && (bytes[0] & 0xFF) == 0x50 && (bytes[1] & 0xFF) == 0x4B
                    && (bytes[2] & 0xFF) == 0x03 && (bytes[3] & 0xFF) == 0x04) {
                try (InputStream is = new ByteArrayInputStream(bytes);
                     XWPFDocument document = new XWPFDocument(is);
                     XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                    String text = extractor.getText();
                    return text == null ? "" : text;
                }
            }
            if (bytes.length >= 8
                    && (bytes[0] & 0xFF) == 0xD0 && (bytes[1] & 0xFF) == 0xCF
                    && (bytes[2] & 0xFF) == 0x11 && (bytes[3] & 0xFF) == 0xE0) {
                return extractDocRawLinesBytes(bytes);
            }
            return readRawTextTolerantBytes(bytes);
        } catch (Exception e) {
            System.out.println("Formatted format sniffing failed: " + e.getMessage());
            return "";
        }
    }

    private static String readRawTextTolerantBytes(byte[] bytes) {
        try {
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.indexOf('\uFFFD') >= 0) {
                text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
            }
            return text;
        } catch (Exception e) {
            return new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // SHARED CLASSIFICATION HELPERS (format-agnostic)
    // ════════════════════════════════════════════════════════════════════════

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
        if (BULLET_PATTERN.matcher(line).matches()) return TextBlock.Kind.BULLET;
        if (NUMBERED_PATTERN.matcher(line).matches()) return TextBlock.Kind.NUMBERED;

        boolean shortEnough = line.length() <= 70;
        boolean noTrailingPunctuation = !line.matches(".*[.,;:]$");
        boolean looksLikeHeading = shortEnough && noTrailingPunctuation
                && (isAllCapsWord(line) || isTitleCaseHeading(line));

        return looksLikeHeading ? TextBlock.Kind.HEADING : TextBlock.Kind.PARAGRAPH;
    }

    private static boolean isAllCapsWord(String line) {
        String lettersOnly = line.replaceAll("[^A-Za-z]", "");
        return lettersOnly.length() >= 3
                && lettersOnly.equals(lettersOnly.toUpperCase(Locale.ROOT))
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
        return capitalizedCount >= Math.max(1, words.length - 1);
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
            if (para.getNumID() != null) {
                return TextBlock.Kind.BULLET;
            }
        } catch (Exception e) {
            // Fall through to surface heuristics
        }
        return classifyLine(text);
    }
}