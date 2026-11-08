package com.adaptivelearning.adaptivelearningbackend;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Verifies that an uploaded file's bytes really are the format its name / declared type says, using the same
 * name-then-type precedence as {@link DocumentTextExtractor#extractText}, so the parser that will run on the
 * bytes is the one whose signature was checked. Extension and Content-Type are client-supplied and spoofable.
 *
 * DOCX is additionally scanned with hard limits (entry count, total inflated bytes), which rejects zip bombs
 * before POI ever opens the file. Pure JDK: unit-testable without Spring.
 */
public final class UploadSignatureCheck {

    private static final int PDF_HEADER_WINDOW = 1024;       // the PDF spec allows the header within the first 1 KB
    private static final int TEXT_SNIFF_BYTES = 8192;
    private static final int MAX_ZIP_ENTRIES = 5_000;
    private static final long MAX_INFLATED_BYTES = 250L * 1024 * 1024;
    private static final byte[] OLE_MAGIC = {
            (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    private UploadSignatureCheck() {}

    /** Returns null if the bytes match the expected format, otherwise a short user-facing reason. */
    public static String problemWith(String originalFilename, String contentType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "The file is empty.";
        String name = Optional.ofNullable(originalFilename).orElse("").toLowerCase(Locale.ROOT);
        String type = Optional.ofNullable(contentType).orElse("").toLowerCase(Locale.ROOT);

        // Same precedence as DocumentTextExtractor.extractText.
        if (name.endsWith(".txt") || name.endsWith(".csv") || type.contains("text")) {
            return looksLikeText(bytes) ? null : "This file doesn't look like a plain-text file.";
        }
        if (name.endsWith(".pdf") || type.contains("pdf")) {
            return hasPdfHeader(bytes) ? null : "This file isn't a valid PDF.";
        }
        if (name.endsWith(".docx") || type.contains("wordprocessingml")) {
            return isSafeDocx(bytes) ? null : "This file isn't a valid DOCX document.";
        }
        if (name.endsWith(".doc") || type.contains("msword")) {
            return startsWith(bytes, OLE_MAGIC) ? null : "This file isn't a valid DOC document.";
        }
        return "Unsupported file type.";
    }

    static boolean hasPdfHeader(byte[] b) {
        int limit = Math.min(b.length - 5, PDF_HEADER_WINDOW);
        for (int i = 0; i <= limit; i++) {
            if (b[i] == '%' && b[i + 1] == 'P' && b[i + 2] == 'D' && b[i + 3] == 'F' && b[i + 4] == '-') return true;
        }
        return false;
    }

    static boolean looksLikeText(byte[] b) {
        int n = Math.min(b.length, TEXT_SNIFF_BYTES);
        for (int i = 0; i < n; i++) if (b[i] == 0) return false;   // NUL bytes mean binary content
        return true;
    }

    static boolean startsWith(byte[] b, byte[] magic) {
        if (b.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) if (b[i] != magic[i]) return false;
        return true;
    }

    /** ZIP local-file header, a word/document.xml part, and bounded entry count / inflated size. */
    static boolean isSafeDocx(byte[] b) {
        if (b.length < 4 || b[0] != 'P' || b[1] != 'K' || b[2] != 3 || b[3] != 4) return false;
        boolean hasDocumentXml = false;
        int entries = 0;
        long inflated = 0;
        byte[] buf = new byte[8192];
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(b))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) return false;
                if ("word/document.xml".equals(e.getName())) hasDocumentXml = true;
                int r;
                while ((r = zin.read(buf)) > 0) {
                    inflated += r;
                    if (inflated > MAX_INFLATED_BYTES) return false;
                }
            }
        } catch (IOException | RuntimeException ex) {
            return false;
        }
        return hasDocumentXml;
    }
}
