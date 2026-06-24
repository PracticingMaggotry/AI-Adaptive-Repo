package com.adaptivelearning.adaptivelearningbackend;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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