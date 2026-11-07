package com.adaptivelearning.adaptivelearningbackend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** ADM-09: the "file is too large" message must state the limit actually enforced, not a hard-coded 10 MB. */
class UploadLimitFormatTest {

    @Test
    void defaultLimitStillReadsTenMegabytes() {
        assertEquals("10 MB", UploadLimitFormat.describe(10L * 1024 * 1024));
    }

    @Test
    void adminChangedLimitsAreStatedCorrectly() {
        assertEquals("1 MB", UploadLimitFormat.describe(1_048_576L));        // the ADM-09 test value
        assertEquals("1.5 MB", UploadLimitFormat.describe(1_572_864L));
        assertEquals("15 MB", UploadLimitFormat.describe(15L * 1024 * 1024));
    }

    @Test
    void smallerLimitsUseKilobytesAndBytes() {
        assertEquals("512 KB", UploadLimitFormat.describe(524_288L));
        assertEquals("300 bytes", UploadLimitFormat.describe(300L));
        assertEquals("1 byte", UploadLimitFormat.describe(1L));
    }
}
