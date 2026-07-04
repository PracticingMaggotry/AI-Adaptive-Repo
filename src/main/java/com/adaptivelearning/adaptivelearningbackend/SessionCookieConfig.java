package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.boot.web.server.servlet.Session;
import org.springframework.context.annotation.Configuration;

/**
 * Makes the JSESSIONID session cookie's security attributes explicit and
 * configurable, instead of silently inheriting whatever the servlet
 * container (Tomcat) defaults to.
 *
 * WHY THIS EXISTS: JSESSIONID is the single highest-stakes cookie in this
 * app — unlike XSRF-TOKEN (see CsrfInterceptor), which is a JS-readable
 * value compared against a header and therefore not a bearer secret on its
 * own, JSESSIONID IS a bearer credential. Anyone holding a valid
 * JSESSIONID value can act as the logged-in user for that session, admin
 * or student, with no further proof required. Before this class existed,
 * nothing in the codebase set Secure/HttpOnly/SameSite on it directly —
 * it relied entirely on Spring Boot's/Tomcat's built-in defaults, which
 * means the actual security posture of the most sensitive cookie in the
 * app was implicit rather than a decision anyone could see, review, or
 * override per environment.
 *
 * Three attributes are pinned here:
 *
 *   - Secure:   only sent by the browser over HTTPS. Controlled by the
 *               SAME app.cookie.secure property CsrfInterceptor uses for
 *               XSRF-TOKEN, so one setting governs both cookies' TLS-only
 *               behavior consistently rather than each cookie silently
 *               being able to drift out of sync with the other. Defaults
 *               to true (fail-safe for production); set
 *               app.cookie.secure=false only in a local/dev profile that
 *               serves the app over plain http://, for the same reason
 *               documented on CsrfInterceptor — otherwise the browser
 *               silently refuses to store the cookie at all and login
 *               appears to "not stick," rather than failing with an
 *               obvious configuration error.
 *
 *   - HttpOnly: true, unconditionally, and not tied to app.cookie.secure.
 *               Unlike XSRF-TOKEN (which must be HttpOnly=false so the
 *               frontend JS in api.js can read it and echo it back in the
 *               X-XSRF-TOKEN header), JSESSIONID has NO legitimate reason
 *               to ever be readable from JavaScript. Making it HttpOnly
 *               closes off session-cookie theft via XSS as an attack
 *               vector, independent of the transport-security question
 *               Secure addresses.
 *
 *   - SameSite: Lax. Blocks the cookie from being attached to genuine
 *               cross-site requests (the CSRF defense-in-depth layer
 *               CsrfInterceptor's javadoc already describes), while still
 *               allowing it on top-level navigations (e.g. following a
 *               link into the app), which Strict would break.
 *
 * Registered as a WebServerFactoryCustomizer<ConfigurableServletWebServerFactory>
 * bean — Spring Boot auto-detects and applies any such bean to the embedded
 * Tomcat factory during startup, before the server is actually created, which
 * is the supported way to reach the container's session-cookie configuration
 * without needing a server.xml or a servlet container-specific dependency.
 */
@Configuration
public class SessionCookieConfig implements WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> {

    /**
     * Shares CsrfInterceptor's app.cookie.secure property rather than
     * introducing a second, independently-settable flag — the two cookies'
     * HTTPS-only posture should never be allowed to silently diverge (e.g.
     * an admin flipping one on but forgetting the other). Defaults to true.
     */
    @Value("${app.cookie.secure:true}")
    private boolean secureCookies;

    @Override
    public void customize(ConfigurableServletWebServerFactory factory) {
        Session session = new Session();
        // Spring Boot 4.0 removed the nested Session.Cookie class — Session.getCookie()
        // now returns the shared top-level org.springframework.boot.web.server.Cookie
        // type (also used by the reactive server stack), with SameSite exposed as
        // Cookie.SameSite instead of Session.Cookie.SameSite.
        Cookie cookie = session.getCookie();
        cookie.setSecure(secureCookies);
        cookie.setHttpOnly(true);
        cookie.setSameSite(Cookie.SameSite.LAX);
        factory.setSession(session);
    }
}