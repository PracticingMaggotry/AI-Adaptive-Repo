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
                return Files.readString(storedPath, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
            } else if (name.endsWith(".pdf") || type.contains("pdf")) {
                return extractPdfText(storedPath);
            } else if (name.endsWith(".docx")) {
                return extractDocxText(storedPath);
            } else if (name.endsWith(".doc")) {
                return extractDocText(storedPath);
            }
            return "";
        } catch (Exception e) {
            System.err.println("Text extraction failed: " + e.getMessage());
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

    /** PDF text extraction via PDFBox. */
    private static String extractPdfText(Path storedPath) {
        try (PDDocument doc = PDDocument.load(storedPath.toFile())) {
            doc.setAllSecurityToBeRemoved(true);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc).replaceAll("\\s+", " ").trim();
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