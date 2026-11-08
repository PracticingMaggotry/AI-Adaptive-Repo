package com.adaptivelearning.adaptivelearningbackend;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SecurityHeadersFilterTest {

    private static MockHttpServletResponse run(boolean reportOnly) throws Exception {
        SecurityHeadersFilter filter = new SecurityHeadersFilter();
        ReflectionTestUtils.setField(filter, "cspReportOnly", reportOnly);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/login.html"), response, new MockFilterChain());
        return response;
    }

    @Test
    void sendsAnEnforcingPolicyByDefault() throws Exception {
        MockHttpServletResponse r = run(false);
        assertEquals(SecurityHeadersFilter.POLICY, r.getHeader("Content-Security-Policy"));
        assertNull(r.getHeader("Content-Security-Policy-Report-Only"));
    }

    @Test
    void canRunInReportOnlyMode() throws Exception {
        MockHttpServletResponse r = run(true);
        assertEquals(SecurityHeadersFilter.POLICY, r.getHeader("Content-Security-Policy-Report-Only"));
        assertNull(r.getHeader("Content-Security-Policy"));
    }

    @Test
    void policyHasTheRestrictionsItIsMeantToProvide() {
        String p = SecurityHeadersFilter.POLICY;
        for (String directive : List.of("default-src 'self'", "connect-src 'self'", "base-uri 'self'",
                "form-action 'self'", "frame-ancestors 'self'")) {
            assertTrue(p.contains(directive), "missing: " + directive);
        }
        assertFalse(p.contains("unsafe-eval"), "no eval is needed");
        assertFalse(p.contains("http://"), "no plain-http sources");
        assertFalse(p.contains(" * "), "no wildcard sources");
        assertFalse(p.contains("https://cdn.jsdelivr.net;"), "jsDelivr is allowed by path, not by whole host");
    }

    /**
     * Guards against the pages and the policy drifting apart: a page that loads a script or stylesheet from a
     * host/path the policy does not allow breaks in the browser, and a CDN tag without an exact version and an
     * SRI hash is exactly the supply-chain gap the policy is meant to close. (The one cdnjs script carries no SRI
     * hash because its served bytes could not be verified against the npm package; the policy still pins its path.)
     */
    @Test
    void everyExternalAssetOnEveryPageIsAllowedByThePolicyPinnedAndHashed() throws Exception {
        Path dir = Path.of("src/main/resources/static");
        assertTrue(Files.isDirectory(dir), "run the tests from the project root");

        List<String> allowed = new ArrayList<>();
        for (String token : SecurityHeadersFilter.POLICY.split("[ ;]+")) {
            if (token.startsWith("https://")) allowed.add(token);
        }
        Pattern tag = Pattern.compile("<(?:script|link)\\b[^>]*?\\b(?:src|href)=\"(https?://[^\"]+)\"[^>]*>",
                Pattern.CASE_INSENSITIVE);

        List<Path> pages;
        try (Stream<Path> walk = Files.walk(dir)) {
            pages = walk.filter(p -> p.toString().endsWith(".html")).toList();
        }

        int seen = 0;
        List<String> problems = new ArrayList<>();
        for (Path page : pages) {
            Matcher m = tag.matcher(Files.readString(page));
            while (m.find()) {
                seen++;
                String url = m.group(1);
                String where = page.getFileName() + ": " + url;
                if (allowed.stream().noneMatch(url::startsWith)) {
                    problems.add(where + " is not allowed by the Content-Security-Policy");
                }
                if (url.startsWith("https://cdn.jsdelivr.net/")) {
                    if (!url.matches("https://cdn\\.jsdelivr\\.net/npm/[^/@]+@\\d+\\.\\d+\\.\\d+/.*")) {
                        problems.add(where + " is not pinned to an exact version");
                    }
                    if (!m.group().contains("integrity=\"sha384-")) {
                        problems.add(where + " has no SRI hash");
                    }
                }
            }
        }
        assertTrue(seen > 10, "expected to find the CDN tags, found " + seen);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }
}
