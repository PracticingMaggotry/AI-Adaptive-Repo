package com.adaptivelearning.adaptivelearningbackend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LogMaskTest {

    @Test
    void masksTheLocalPartButKeepsTheDomain() {
        assertEquals("j***@school.edu", LogMask.email("jane.doe@school.edu"));
        assertEquals("j***@school.edu", LogMask.email("  Jane.Doe@School.edu "));
        assertEquals("*@x.com", LogMask.email("a@x.com"));
    }

    @Test
    void handlesMissingOrMalformedInput() {
        assertEquals("-", LogMask.email(null));
        assertEquals("-", LogMask.email("   "));
        assertEquals("***", LogMask.email("not-an-email"));
    }

    @Test
    void scrubMasksEveryAddressInFreeText() {
        String masked = LogMask.scrub("Duplicate entry 'jane@x.com' for key 'uk_email' (also bob@y.org)");
        assertFalse(masked.contains("jane@"), masked);
        assertFalse(masked.contains("bob@"), masked);
        assertTrue(masked.contains("j***@x.com"), masked);
        assertTrue(masked.contains("b***@y.org"), masked);
        assertTrue(masked.contains("uk_email"), masked);
    }

    @Test
    void scrubIsNullSafe() {
        assertEquals("", LogMask.scrub((String) null));
        assertEquals("", LogMask.scrub((Throwable) null));
        assertFalse(LogMask.scrub(new RuntimeException("failed for jane@x.com")).contains("jane@"));
    }
}
