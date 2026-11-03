package com.adaptivelearning.adaptivelearningbackend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** RFC 6238 Appendix B reference vectors (SHA-1) plus replay/window behaviour and AES-GCM integrity. */
class TotpTest {

    private static final byte[] RFC_KEY = "12345678901234567890".getBytes();

    @Test
    void matchesRfc6238Vectors() {
        long[] t = {59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
        String[] v = {"94287082", "07081804", "14050471", "89005924", "69279037", "65353130"};
        for (int i = 0; i < t.length; i++) assertEquals(v[i], Totp.code(RFC_KEY, t[i] / 30, 8));
    }

    @Test
    void rejectsReplayAndOutOfWindowCodes() {
        String secret = Totp.base32Encode(RFC_KEY);
        long now = 59_000L;
        assertEquals(1L, Totp.verify(secret, "287082", -1, now));
        assertEquals(-1L, Totp.verify(secret, "287082", 1, now));          // same step already used
        assertEquals(1L, Totp.verify(secret, "287082", -1, now + 30_000L)); // +1 step drift tolerated
        assertEquals(-1L, Totp.verify(secret, "287082", -1, now + 90_000L)); // outside window
        assertEquals(-1L, Totp.verify(secret, "28708a", -1, now));
        assertEquals(-1L, Totp.verify(secret, null, -1, now));
    }

    @Test
    void aesGcmBindsCiphertextToAccountAndDetectsTampering() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        String ct = AesGcmUtil.encrypt(key, "JBSWY3DPEHPK3PXP", "a@x.com");
        assertEquals("JBSWY3DPEHPK3PXP", AesGcmUtil.decrypt(key, ct, "a@x.com"));
        assertThrows(IllegalStateException.class, () -> AesGcmUtil.decrypt(key, ct, "b@x.com"));
    }
}
