package com.adaptivelearning.adaptivelearningbackend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmailEscapeTest {

    @Test
    void markupInDisplayNameIsNeutralised() {
        assertEquals("&lt;a href=&quot;https://evil&quot;&gt;Click&lt;/a&gt; &amp; &#39;x&#39;",
                EmailService.escapeHtml("<a href=\"https://evil\">Click</a> & 'x'"));
    }

    @Test
    void ordinaryNamesAreUnchanged() {
        assertEquals("Ana Reyes", EmailService.escapeHtml("Ana Reyes"));
    }
}
