package com.adaptivelearning.adaptivelearningbackend;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Keeps email addresses out of application logs. Student ids in this app are email addresses, so any
 * log line that names a student or a mail recipient goes through here first.
 *
 *   email("jane.doe@school.edu")  ->  "j***@school.edu"
 *   email("a@x.com")              ->  "*@x.com"
 *   scrub("Duplicate entry 'jane@x.com' for key 'uk_email'")  ->  "Duplicate entry 'j***@x.com' for key 'uk_email'"
 *
 * Use scrub() for text you do not control (exception messages, remote API error bodies), which can echo
 * an address back.
 */
public final class LogMask {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+'\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    private LogMask() {}

    /** Masks one email address. Null/blank gives "-"; anything without an "@" gives "***". */
    public static String email(String email) {
        if (email == null || email.isBlank()) return "-";
        String e = email.trim().toLowerCase(Locale.ROOT);
        int at = e.indexOf('@');
        if (at < 0) return "***";
        String domain = e.substring(at);
        return (at == 1 ? "*" : e.charAt(0) + "***") + domain;
    }

    /** Masks every email-looking substring in free text. Null gives "". */
    public static String scrub(String text) {
        if (text == null) return "";
        return EMAIL.matcher(text).replaceAll(m -> {
            // The pattern allows an apostrophe inside an address (o'brien@x.com), so a quote that merely wraps the
            // address in the surrounding text ('jane@x.com') is matched too: leave those leading quotes alone.
            String found = m.group();
            int lead = 0;
            while (lead < found.length() && found.charAt(lead) == '\'') lead++;
            return java.util.regex.Matcher.quoteReplacement("'".repeat(lead) + email(found.substring(lead)));
        });
    }

    /** Convenience for logging a Throwable without leaking addresses its message may contain. */
    public static String scrub(Throwable t) {
        return t == null ? "" : scrub(t.getClass().getSimpleName() + ": " + t.getMessage());
    }
}
