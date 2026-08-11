package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.boot.web.server.servlet.Session;
import org.springframework.context.annotation.Configuration;

/**
 * Sets JSESSIONID's security attributes explicitly instead of relying on
 * Tomcat's defaults. Unlike XSRF-TOKEN, JSESSIONID is a bearer credential —
 * anyone holding it can act as that user — so its posture shouldn't be implicit.
 *
 * - Secure: HTTPS-only, gated by app.cookie.secure (shared with CsrfInterceptor
 *   so both cookies stay in sync). Set false only for local http:// dev.
 * - HttpOnly: always true — no legitimate reason for JS to read this cookie.
 * - SameSite: Lax — blocks cross-site requests, allows top-level navigation.
 *
 * Registered as a WebServerFactoryCustomizer bean so Spring Boot applies it
 * to the embedded Tomcat factory at startup.
 */
@Configuration
public class SessionCookieConfig implements WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> {

    @Value("${app.cookie.secure:true}")
    private boolean secureCookies;

    @Override
    public void customize(ConfigurableServletWebServerFactory factory) {
        Session session = new Session();
        // Spring Boot 4.0 moved SameSite onto the shared Cookie type (Session.Cookie was removed).
        Cookie cookie = session.getCookie();
        cookie.setSecure(secureCookies);
        cookie.setHttpOnly(true);
        cookie.setSameSite(Cookie.SameSite.LAX);
        factory.setSession(session);
    }
}