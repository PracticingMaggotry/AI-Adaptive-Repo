package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Password validation policy. Uses ConfigurationService for tunable requirements.
 * Registered as a Spring component so it can inject ConfigurationService.
 */
@Component
public class PasswordPolicy {

    public static final int MAX_LENGTH = 128;

    /** Minimum run length to flag as sequential/keyboard-pattern (4 = "abcd"/"1234"/"qwer"). */
    private static final int SEQUENCE_RUN_LENGTH = 4;

    /** Physical QWERTY rows, checked forward and reversed. Shifted symbol rows are excluded. */
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

    @Autowired
    private ConfigurationService configurationService;

    /**
     * Validate password against policy rules from ConfigurationService.
     * Returns null if valid, or an error message if invalid.
     */
    public String validate(String password) {
        if (password == null || password.isBlank()) {
            return "Password is required.";
        }

        int minLength = configurationService.getMinPasswordLength();
        if (password.length() < minLength) {
            return "Password must be at least " + minLength + " characters long.";
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

        // Build required character classes from config
        int classesRequired = 0;
        StringBuilder missingClasses = new StringBuilder();

        if (configurationService.requirePasswordUppercase()) {
            classesRequired++;
            if (!hasUpper) missingClasses.append("uppercase letters, ");
        }
        if (configurationService.requirePasswordNumber()) {
            classesRequired++;
            if (!hasDigit) missingClasses.append("numbers, ");
        }
        if (configurationService.requirePasswordSpecialChar()) {
            classesRequired++;
            if (!hasSymbol) missingClasses.append("special characters, ");
        }

        // Always require lowercase as baseline
        classesRequired++;
        if (!hasLower) missingClasses.append("lowercase letters, ");

        int classesPresent = (hasUpper ? 1 : 0) + (hasLower ? 1 : 0) + (hasDigit ? 1 : 0) + (hasSymbol ? 1 : 0);
        if (classesPresent < classesRequired) {
            String missing = missingClasses.toString().replaceAll(", $", "");
            return "Password must contain: " + missing + ".";
        }

        if (COMMON_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
            return "This password is too common. Please choose a more unique password.";
        }

        if (isAllSameCharacter(password)) {
            return "Password cannot consist of a single repeated character.";
        }

        // Catches low-entropy passwords that satisfy the character-class rule but are a
        // predictable walk (e.g. "Abcdefg1") rather than a real mix.
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

    /** True if the password has a run of SEQUENCE_RUN_LENGTH+ ascending/descending char codes. */
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

    /** True if the password contains a physical keyboard-row walk (forward or reversed). */
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

