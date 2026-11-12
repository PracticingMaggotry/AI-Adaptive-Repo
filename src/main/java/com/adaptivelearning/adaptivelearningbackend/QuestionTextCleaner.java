package com.adaptivelearning.adaptivelearningbackend;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Cleans generated question text. A true/false question already carries its type label in the quiz UI, so the text
 * itself must be only the statement to judge, never "True or False: ...".
 */
public final class QuestionTextCleaner {
    private QuestionTextCleaner() {}

    /** "True or False", "True/False" or "T/F", with an optional trailing "question" / "statement". */
    private static final String LABEL =
            "(?:true\\s*(?:or|/|\\|)\\s*false|t\\s*/\\s*f)(?:\\s+(?:question|statement))?";

    /** A leading label: "True or False:", "True/False -", "True or False?", "[True or False]", "(T/F)". */
    private static final Pattern LEADING = Pattern.compile(
            "^\\s*(?:[\\[(]\\s*" + LABEL + "\\s*[\\])]\\s*[:\\-\u2013\u2014.,]?|" + LABEL + "\\s*[:?\\-\u2013\u2014.,])\\s*",
            Pattern.CASE_INSENSITIVE);

    /** A trailing label: "... (True or False)", "... [T/F]", "... - True or False?". */
    private static final Pattern TRAILING = Pattern.compile(
            "\\s*(?:[\\[(]\\s*" + LABEL + "\\s*[\\])]|[\\-\u2013\u2014:]\\s*" + LABEL + "\\s*\\??)\\s*[.?]?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * {@code text} without a leading or trailing true/false label. The rest of the wording is left exactly as
     * written. If removing the label would leave nothing, the original text is returned.
     */
    public static String stripTrueFalseLabel(String text) {
        if (text == null) return null;
        String cleaned = LEADING.matcher(text).replaceFirst("");
        cleaned = TRAILING.matcher(cleaned).replaceFirst("");
        cleaned = cleaned.trim();
        return cleaned.isEmpty() ? text : cleaned;
    }

    /**
     * The text to show a student for a question of {@code type}: true/false questions lose any label that was
     * stored with them (older questions have it), every other type is returned unchanged.
     */
    public static String forDisplay(String type, String text) {
        if (type != null && type.trim().toUpperCase(Locale.ROOT).equals("TRUEFALSE")) {
            return stripTrueFalseLabel(text);
        }
        return text;
    }
}
