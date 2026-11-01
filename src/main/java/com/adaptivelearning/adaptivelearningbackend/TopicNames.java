package com.adaptivelearning.adaptivelearningbackend;

/**
 * Rules for user-supplied topic names. Names are free text that end up in URLs, HTML, PDFs and AI
 * prompts, so characters that only make sense as markup or as invisible control are refused at intake.
 * Output escaping in the frontend remains the primary XSS defence; this removes the payload class.
 *
 * "/" and "\\" are deliberately still allowed ("TCP/IP", "Input/Output"): topics are never put in a URL
 * path any more, only in query strings, where they are safe once encoded.
 */
final class TopicNames {

    /** Upper bound for lookups/deletes of an existing name; the configurable upload limit is enforced separately. */
    static final int MAX_LOOKUP_LENGTH = 500;

    private TopicNames() {}

    /** @return a user-facing message if the name is unacceptable, otherwise null. */
    static String problemWith(String raw) {
        if (raw == null || raw.isBlank()) return "Please enter a topic name.";
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '<' || c == '>') {
                return "Topic names can't contain < or >.";
            }
            if (Character.isISOControl(c) && c != ' ') {
                return "Topic names can't contain control characters.";
            }
            // zero-width characters and bidi overrides/isolates: invisible, used to spoof or collide names
            if ((c >= '\u200B' && c <= '\u200F') || (c >= '\u202A' && c <= '\u202E')
                    || (c >= '\u2066' && c <= '\u2069') || c == '\uFEFF') {
                return "Topic names can't contain invisible formatting characters.";
            }
        }
        return null;
    }
}
