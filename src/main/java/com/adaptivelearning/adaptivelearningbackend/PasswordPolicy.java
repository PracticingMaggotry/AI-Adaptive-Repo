package com.adaptivelearning.adaptivelearningbackend;

import java.util.Locale;
import java.util.Set;

public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 128;

    /**
     * Minimum length of a sequential-character or keyboard-adjacent run
     * that triggers rejection. 4 catches "abcd"/"1234"/"qwer" while still
     * allowing short, non-degenerate coincidental runs (e.g. a 3-char
     * fragment buried in an otherwise-random password) to pass.
     */
    private static final int SEQUENCE_RUN_LENGTH = 4;

    /**
     * Physical QWERTY rows (lowercase/digit row only — shifted symbol runs
     * like "!@#$" are intentionally not modeled here since they require a
     * shift key on every keystroke, which meaningfully raises the bar an
     * attacker's guessing script has to clear versus a bare row walk).
     * Checked both forward and reversed so "qwerty" and "ytrewq" both hit.
     */
    private static final String[] KEYBOARD_ROWS = {
            "qwertyuiop",
            "asdfghjkl",
            "zxcvbnm",
            "1234567890"
    };

    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "password", "password1", "password123", "12345678", "123456789",
            "qwerty123", "letmein123", "admin1234", "welcome123", "iloveyou1",
            "passw0rd", "abc123456", "1234567890", "changeme1"
    );

    private PasswordPolicy() {}

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

        if (COMMON_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
            return "This password is too common. Please choose a more unique password.";
        }

        if (isAllSameCharacter(password)) {
            return "Password cannot consist of a single repeated character.";
        }

        // Catches passwords that technically satisfy the character-class
        // rule above but carry almost no real entropy because they're a
        // predictable run rather than a random mix — e.g. "Abcdefg1" has
        // upper+lower+digit and isn't in the common-password list, but
        // "abcdefg" is a straight alphabetic walk an attacker's guessing
        // tool checks before anything resembling brute force. Two shapes
        // are checked: sequential character codes (letters OR digits, in
        // either direction) and physical keyboard-row walks (also either
        // direction), independent of case.
        if (containsSequentialRun(password) || containsKeyboardRun(password)) {
            return "Password cannot contain a sequential or keyboard-pattern run of characters "
                    + "(e.g. \"abcd\", \"1234\", \"qwerty\").";
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

    /**
     * True if the password contains a run of {@link #SEQUENCE_RUN_LENGTH}
     * or more consecutive characters that are strictly ascending or
     * strictly descending by character code (case-insensitive) — e.g.
     * "abcd", "DCBA", "3456", "9876". Works for letters and digits alike
     * since both are contiguous in a single Unicode block.
     */
    private static boolean containsSequentialRun(String password) {
        String lower = password.toLowerCase(Locale.ROOT);
        int ascRun = 1;
        int descRun = 1;
        for (int i = 1; i < lower.length(); i++) {
            char prev = lower.charAt(i - 1);
            char curr = lower.charAt(i);

            ascRun = (curr - prev == 1) ? ascRun + 1 : 1;
            descRun = (prev - curr == 1) ? descRun + 1 : 1;

            if (ascRun >= SEQUENCE_RUN_LENGTH || descRun >= SEQUENCE_RUN_LENGTH) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if the password contains a run of {@link #SEQUENCE_RUN_LENGTH}
     * or more consecutive characters that appear together, in the same
     * order, on a physical QWERTY row — e.g. "qwer", "sdfg", "nbvc" — or
     * the reverse of such a run — e.g. "rewq". This catches keyboard walks
     * that aren't sequential by character code (e.g. "qwerty" jumps all
     * over the alphabet) and so would slip past {@link #containsSequentialRun}.
     */
    private static boolean containsKeyboardRun(String password) {
        String lower = password.toLowerCase(Locale.ROOT);
        for (String row : KEYBOARD_ROWS) {
            for (int i = 0; i + SEQUENCE_RUN_LENGTH <= row.length(); i++) {
                String fragment = row.substring(i, i + SEQUENCE_RUN_LENGTH);
                String reversed = new StringBuilder(fragment).reverse().toString();
                if (lower.contains(fragment) || lower.contains(reversed)) {
                    return true;
                }
            }
        }
        return false;
    }
}