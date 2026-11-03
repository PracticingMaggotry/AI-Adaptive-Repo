package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Locale;

/**
 * Encrypts admin TOTP secrets at rest (AES-256-GCM). The key comes from the ADMIN_MFA_KEY
 * environment variable (base64 of 32 random bytes) and is never stored in the database or the repo,
 * so a database dump alone does not reveal anyone's second factor.
 *
 * Fails closed: with no valid key, admin sign-in is refused rather than falling back to weaker auth.
 * Generate a key with:  openssl rand -base64 32
 */
@Component
public class AdminSecretCipher {

    @Value("${admin.mfa.key:}")
    private String keyBase64;

    private byte[] key() {
        if (keyBase64 == null || keyBase64.isBlank()) return null;
        try {
            byte[] k = Base64.getDecoder().decode(keyBase64.trim());
            return k.length == 32 ? k : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isConfigured() {
        return key() != null;
    }

    public String encrypt(String plaintext, String email) {
        byte[] k = key();
        if (k == null) throw new IllegalStateException("ADMIN_MFA_KEY is not configured");
        return AesGcmUtil.encrypt(k, plaintext, email.toLowerCase(Locale.ROOT));
    }

    public String decrypt(String token, String email) {
        byte[] k = key();
        if (k == null) throw new IllegalStateException("ADMIN_MFA_KEY is not configured");
        return AesGcmUtil.decrypt(k, token, email.toLowerCase(Locale.ROOT));
    }
}
