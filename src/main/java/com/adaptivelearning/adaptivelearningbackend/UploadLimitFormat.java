package com.adaptivelearning.adaptivelearningbackend;

import java.util.Locale;

/**
 * Human-readable text for the configurable upload limit (MAX_UPLOAD_BYTES), so the "file is too large" message
 * states the limit that is actually enforced instead of a hard-coded "10 MB" after an admin changes it.
 */
final class UploadLimitFormat {

    private static final long KB = 1024L;
    private static final long MB = 1024L * 1024L;

    private UploadLimitFormat() {}

    /** 10485760 -> "10 MB", 1572864 -> "1.5 MB", 524288 -> "512 KB", 300 -> "300 bytes". */
    static String describe(long bytes) {
        if (bytes >= MB) return trim(bytes / (double) MB) + " MB";
        if (bytes >= KB) return trim(bytes / (double) KB) + " KB";
        return bytes + (bytes == 1 ? " byte" : " bytes");
    }

    private static String trim(double value) {
        double rounded = Math.round(value * 10.0) / 10.0;
        return rounded == Math.rint(rounded)
                ? String.valueOf((long) rounded)
                : String.format(Locale.ROOT, "%.1f", rounded);
    }
}
