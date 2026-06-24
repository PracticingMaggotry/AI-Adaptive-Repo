package com.adaptivelearning.adaptivelearningbackend;

import java.util.Set;

/**
 * Server-side password strength policy, enforced on registration.
 *
 * Previously AuthController.registerUser() only checked that the password
 * was non-blank, so a single-character password like "a" was accepted and
 * happily BCrypt-encoded. There was no minimum length, complexity, or
 * common-password check at all — client-side validation (if any existed)
 * is trivially bypassed by posting directly to /register, so the real gate
 * has to live here.
 *
 * This is intentionally a plain validator class (no Spring annotations)
 * so it can be unit-tested in isolation and reused anywhere a password is
 * accepted, mirroring how QuestionValidator/DifficultyTier centralize a
 * single set of rules rather than letting them drift across callers.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 128; // generous cap; just guards against absurd input/DoS via BCrypt cost

    // A short list of extremely common passwords that trivially defeat any
    // length/complexity rule (e.g. "Password1" satisfies length+complexity
    // below but is one of the first guesses in any credential-stuffing list).
    // This is deliberately small — it's a backstop, not a full breach-corpus
    // check (e.g. Have I Been Pwned), which would need a network/DB call.
    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "password", "password1", "password123", "12345678", "123456789",
            "qwerty123", "letmein123", "admin1234", "welcome123", "iloveyou1",
            "passw0rd", "abc123456", "1234567890", "changeme1"
    );

    private PasswordPolicy() {}

    /**
     * Validates a candidate password against the policy.
     *
     * @param password the raw (not yet encoded) password
     * @return null if the password is acceptable, or a user-facing reason
     *         string explaining why it was rejected
     */
    public static String validate(String password) {
        if (password == null || password.isBlank()) {
            return "Password is required.";
        }
        if (password.length() < MIN_LENGTH) {
            return "Password must be at least " + MIN_LENGTH + " characters long.";
        }
        if (password.length() > MAX_LENGTH) {
            return "Password must be no more than " + MAX_LENGTH + " characters long.";
        }

        boolean hasUpper = false, hasLower = false, hasDigit = false, hasSymbol = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isUpperCase(c)) hasUpper = true;
            else if (Character.isLowerCase(c)) hasLower = true;
            else if (Character.isDigit(c)) hasDigit = true;
            else if (!Character.isWhitespace(c)) hasSymbol = true;
        }

        int classesPresent = (hasUpper ? 1 : 0) + (hasLower ? 1 : 0) + (hasDigit ? 1 : 0) + (hasSymbol ? 1 : 0);
        if (classesPresent < 3) {
            return "Password must contain at least 3 of the following: uppercase letters, "
                    + "lowercase letters, numbers, and symbols.";
        }

        // Reject if the whole password is just whitespace-trimmed-equal to a
        // common password, case-insensitively — catches "Password1", "PASSWORD123", etc.
        if (COMMON_PASSWORDS.contains(password.toLowerCase(java.util.Locale.ROOT))) {
            return "This password is too common. Please choose a more unique password.";
        }

        // Reject simple repeated-character passwords (e.g. "aaaaaaaa", "11111111")
        // that can satisfy length but nothing else meaningful.
        if (isAllSameCharacter(password)) {
            return "Password cannot consist of a single repeated character.";
        }

        return null;
    }

    private static boolean isAllSameCharacter(String password) {
        char first = password.charAt(0);
        for (int i = 1; i < password.length(); i++) {
            if (password.charAt(i) != first) return false;
        }
        return true;
    }
}