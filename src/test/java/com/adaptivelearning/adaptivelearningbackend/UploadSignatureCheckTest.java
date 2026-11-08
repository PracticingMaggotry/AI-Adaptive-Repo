package com.adaptivelearning.adaptivelearningbackend;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class UploadSignatureCheckTest {

    private static byte[] zip(String entryName, int payloadBytes) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            z.putNextEntry(new ZipEntry(entryName));
            z.write(new byte[payloadBytes]);
            z.closeEntry();
        }
        return bos.toByteArray();
    }

    @Test
    void realPdfHeaderPasses_fakeFails() {
        assertNull(UploadSignatureCheck.problemWith("a.pdf", "application/pdf", "%PDF-1.7\n...".getBytes(StandardCharsets.US_ASCII)));
        assertNotNull(UploadSignatureCheck.problemWith("a.pdf", "application/pdf", "MZ\u0090\u0000 not a pdf".getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    void docxNeedsDocumentXml() throws Exception {
        assertNull(UploadSignatureCheck.problemWith("a.docx", "application/octet-stream", zip("word/document.xml", 100)));
        assertNotNull(UploadSignatureCheck.problemWith("a.docx", "application/octet-stream", zip("evil.bin", 100)));
        assertNotNull(UploadSignatureCheck.problemWith("a.docx", "application/octet-stream", "plain text".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void zipBombIsRejected() throws Exception {
        // ~300 MB of zeros deflates to roughly 300 KB: over the inflated-size cap.
        assertNotNull(UploadSignatureCheck.problemWith("a.docx", "application/octet-stream", zip("word/document.xml", 300 * 1024 * 1024)));
    }

    @Test
    void docNeedsOleMagic() {
        byte[] ole = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0, 0};
        assertNull(UploadSignatureCheck.problemWith("a.doc", "application/msword", ole));
        assertNotNull(UploadSignatureCheck.problemWith("a.doc", "application/msword", "%PDF-1.4".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void textRejectsBinary_andParserPrecedenceMatchesExtractor() {
        assertNull(UploadSignatureCheck.problemWith("n.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8)));
        assertNotNull(UploadSignatureCheck.problemWith("n.txt", "text/plain", new byte[]{'a', 0, 'b'}));
        // name says .doc but the type says pdf: the extractor would run PDFBox, so the PDF header is what must match
        assertNotNull(UploadSignatureCheck.problemWith("x.doc", "application/pdf", "not a pdf at all".getBytes(StandardCharsets.UTF_8)));
    }
}
